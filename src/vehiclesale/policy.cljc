(ns vehiclesale.policy
  "VehicleSaleGovernor — the independent compliance layer that earns the
  VehicleSale-LLM the right to list, confirm a sale, or resolve a dispute.
  The LLM has no notion of lien-payoff proof, federal odometer-disclosure
  law, or a subscriber's disclosure entitlement, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  Eight checks, in priority order. The first five are HARD violations: a
  human approver CANNOT override them. The last three are SOFT/always-
  escalate: they route to a human, who may approve.

    1. rbac                    — does actor-role have permission for op?
  2. lien-clearance-gate      — a `:sale/confirm` on a title with an active
                                  lien, without an explicit payoff/release
                                  confirmation, is rejected.
  3. odometer-disclosure-gate — a reading that rolls back below the last
                                  recorded value, or a `:sale/confirm` on a
                                  non-exempt-age vehicle with no disclosure
                                  statement, is rejected (49 U.S.C. Chapter
                                  327 / 49 CFR Part 580). JP listings are
                                  not under that statute; they use
                                  repair-history-disclosure-gate instead.
  4. source-provenance-gate   — does the listing/odometer report cite an
                                  allowed provenance class, and — for an
                                  operator-licensed feed — an ACTIVE
                                  credential scoped to the vehicle's
                                  state/prefecture?
  5. licensed-disclosure      — is there an active subscriber contract,
                                  and does the requested column set stay
                                  within its tier?
  6. kobutsusho-license-gate  — a JP `:vehicle/list` without a 古物商許可
                                  number is rejected (古物営業法).
  7. repair-history-disclosure-gate — a JP `:sale/confirm` without an
                                  explicit 修復歴 disclosure is rejected.
  8. inquiry-target-gate      — an `:inquiry/submit` against a VIN that is
                                  not in the SSoT is rejected.

  Confidence floor, salvage-title-gate and dispute-request remain SOFT /
  always-escalate."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [vehiclesale.facts :as facts]
            [vehiclesale.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def hard-rule-ids
  "Closed set of HARD gates. A console that does not exercise every one of
  these is incomplete — `render-html/-main` refuses to write in that case."
  #{:rbac :lien-clearance-gate :odometer-disclosure-gate :source-provenance-gate
    :licensed-disclosure :kobutsusho-license-gate
    :repair-history-disclosure-gate :inquiry-target-gate})

(def confidence-floor 0.6)

(def permissions
  "actor-role → set of operations it may perform."
  {:dealer-agent   #{:vehicle/list :sale/confirm}
   :title-officer  #{:vehicle/list :sale/confirm :dispute/request}
   :buyer          #{:disclosure/query :inquiry/submit}})

(def tier-columns
  "For `:disclosure/query` — the columns each licensed subscriber tier may
  see."
  (let [base #{:vin :make :model :year :title-status :price
               :prefecture :mileage :body-type :repair-history?}
        dealer-extra #{:odometer :lien-status :kobutsusho-license}]
    {:tier/basic  base
     :tier/dealer (into base dealer-extra)}))

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " は " op " の権限を持たない")}]))

(defn- lien-clearance-violations
  "Only `:sale/confirm` moves title to a buyer. An active, unreleased lien
  without an explicit `:lien-cleared?` payoff confirmation is a HARD
  rejection regardless of confidence — silently passing a lien to a buyer
  is exactly the failure mode this check exists to catch."
  [{:keys [op]} proposal st]
  (when (= op :sale/confirm)
    (let [vin (get-in proposal [:value :vin])
          rec (store/title-record st vin)]
      (when (and rec (:active? rec) (not (get-in proposal [:value :lien-cleared?])))
        [{:rule :lien-clearance-gate
          :detail (str "アクティブなリーエンが未解消: vin=" vin
                       " lien-holder=" (:lien-holder rec))}]))))

(defn- odometer-disclosure-violations
  "`:vehicle/list` asserting a rollback reading, or `:sale/confirm` on a
  non-exempt-age vehicle missing the disclosure statement, is HARD."
  [{:keys [op]} proposal st]
  (case op
    :vehicle/list
    (let [vin (get-in proposal [:value :vin])
          new-reading (get-in proposal [:value :odometer])
          prior (:reading (store/odometer-latest st vin))]
      (when (and prior new-reading (< new-reading prior))
        [{:rule :odometer-disclosure-gate
          :detail (str "走行距離がロールバック: vin=" vin " prior=" prior
                       " new=" new-reading)}]))

    :sale/confirm
    (let [vin (get-in proposal [:value :vin])
          veh (store/vehicle st vin)
          jp? (= :jp (:jurisdiction veh))
          exempt? (and veh (:year veh)
                       (>= (- 2026 (:year veh)) facts/odometer-exempt-model-year-age))]
      (when (and (not jp?) (not exempt?)
                 (not (get-in proposal [:value :odometer-disclosure-statement?])))
        [{:rule :odometer-disclosure-gate
          :detail (str "連邦法上の走行距離開示証明が無い(非適用除外車両): vin=" vin)}]))

    nil))

(defn- source-provenance-violations
  [{:keys [op]} proposal st]
  (when (= op :vehicle/list)
    (let [src (:source proposal)
          vin (get-in proposal [:value :vin])]
      (cond
        (or (nil? src) (not (facts/class-allowed? (:class src))))
        [{:rule :source-provenance-gate
          :detail (str "出典が無いか許可された出典クラスでない: " (pr-str src))}]

        (facts/licensed-dmv-class? (:class src))
        (let [lic (store/dmv-license st (:license-id src))
              region (or (get-in proposal [:value :state])
                         (get-in proposal [:value :prefecture]))]
          (when (or (nil? lic) (not (:active? lic))
                    (not (contains? (:states lic) region)))
            [{:rule :source-provenance-gate
              :detail (str "有効な feed credential が無いか地域対象外: "
                           "license-id=" (:license-id src) " region=" region " vin=" vin)}]))

        :else nil))))

(defn- licensed-disclosure-violations
  [{:keys [op]} {:keys [tenant]} proposal st]
  (when (= op :disclosure/query)
    (let [c (when tenant (store/contract st tenant))]
      (if (or (nil? c) (not (:active? c)))
        [{:rule :licensed-disclosure :detail (str "有効な契約が無い: tenant=" tenant)}]
        (let [allowed (get tier-columns (:tier c) #{})
              cols    (set (:columns proposal))
              extra   (set/difference cols allowed)]
          (when (seq extra)
            [{:rule :licensed-disclosure
              :detail (str "契約 tier " (:tier c) " に対し過剰な列: " (vec extra))}]))))))

(defn- jp-subject?
  [proposal st]
  (let [vin (get-in proposal [:value :vin])
        from-value (get-in proposal [:value :jurisdiction])
        from-store (when vin (:jurisdiction (store/vehicle st vin)))]
    (= :jp (or from-value from-store))))

(defn- kobutsusho-license-violations
  "古物営業法: a Japan listing must carry a 古物商許可番号. The governor
  does not call a prefectural police API (none is uniformly free); it
  rejects the empty case so an unsourced dealer cannot list."
  [{:keys [op]} proposal st]
  (when (and (= op :vehicle/list) (jp-subject? proposal st))
    (when (str/blank? (str (get-in proposal [:value :kobutsusho-license])))
      [{:rule :kobutsusho-license-gate
        :detail (str "古物商許可番号が無い: vin=" (get-in proposal [:value :vin]))}])))

(defn- repair-history-disclosure-violations
  "JP sale confirmation must say whether 修復歴 is present. Silence is a
  HARD hold — same shape as the US odometer-disclosure-statement gate."
  [{:keys [op]} proposal st]
  (when (and (= op :sale/confirm) (jp-subject? proposal st))
    (when-not (contains? (:value proposal) :repair-history-disclosed?)
      [{:rule :repair-history-disclosure-gate
        :detail (str "修復歴の開示が無い: vin=" (get-in proposal [:value :vin]))}])))

(defn- inquiry-target-violations
  [{:keys [op]} proposal st]
  (when (= op :inquiry/submit)
    (let [vin (get-in proposal [:value :vin])]
      (when-not (store/vehicle st vin)
        [{:rule :inquiry-target-gate
          :detail (str "出品が存在しない車両への問い合わせ: vin=" vin)}]))))

(defn- salvage-title? [st vin]
  (when vin
    (let [veh (store/vehicle st vin)]
      (boolean (and veh (contains? #{:salvage :flood :rebuilt} (:title-status veh)))))))

(defn check
  "Censors a VehicleSale-LLM proposal against the policy tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :salvage? bool
    :hard? bool :dispute? bool}."
  [request context proposal st]
  (let [hard    (into []
                      (concat (rbac-violations request context)
                              (lien-clearance-violations request proposal st)
                              (odometer-disclosure-violations request proposal st)
                              (source-provenance-violations request proposal st)
                              (licensed-disclosure-violations request context proposal st)
                              (kobutsusho-license-violations request proposal st)
                              (repair-history-disclosure-violations request proposal st)
                              (inquiry-target-violations request proposal st)))
        conf     (:confidence proposal 0.0)
        low?     (< conf confidence-floor)
        vin      (or (get-in proposal [:value :vin]) (:subject request))
        salvage? (and (= (:op request) :sale/confirm) (salvage-title? st vin))
        dispute? (= :dispute/request (:op request))
        hard?    (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not salvage?) (not dispute?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? salvage? dispute?))
     :salvage?     salvage?
     :dispute?     dispute?}))

(defn hold-fact
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
