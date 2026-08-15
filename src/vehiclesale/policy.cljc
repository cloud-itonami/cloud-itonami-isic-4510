(ns vehiclesale.policy
  "VehicleSaleGovernor — the independent compliance layer that earns the
  VehicleSale-LLM the right to list, confirm a sale, or resolve a dispute.
  The LLM has no notion of lien-payoff proof, federal odometer-disclosure
  law, or a subscriber's disclosure entitlement, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  HARD checks, in priority order. A human approver CANNOT override
  them. SOFT / always-escalate: confidence floor, salvage-title, dispute,
  and money-adjacent ops (`:escrow/capture`, `:escrow/propose-release`,
  `:payout/bind`, `:x402/unlock`). Export/import certs always escalate
  (filings-adjacent; this actor never files).

    1. rbac
    2. lien-clearance-gate
    3. odometer-disclosure-gate   (US 49 USC only)
    4. source-provenance-gate
    5. licensed-disclosure
    6. kobutsusho-license-gate    (JP)
    7. dealer-license-gate        (non-JP markets that require a trade licence)
    8. repair-history-disclosure-gate
    9. inquiry-target-gate
   10. scan-coverage-gate
   11. shaken-validity-gate
   12. x402-receipt-gate
   13. escrow-conservation-gate
   14. payout-destination-gate
   15. funds-not-arrived-gate
   16. custody-handover-gate
   17. unknown-market-gate
   18. denied-destination-gate
   19. steering-incompatible-gate
   20. export-certificate-gate
   21. import-permit-gate
   22. landed-uncomputable-gate
   23. tariff-conservation-gate
   24. hs-adjudication-gate
   25. import-age-gate           (KS 1515 YoR cap)
   26. import-regime-gate        (AU SEVS/RAWS / Chile Ley 18.483)
   27. scope-exclusion-gate"

  (:require [clojure.set :as set]
            [clojure.string :as str]
            [vehiclesale.body :as body]
            [vehiclesale.border :as border]
            [vehiclesale.commerce :as commerce]
            [vehiclesale.facts :as facts]
            [vehiclesale.store :as store]
            [vehiclesale.x402 :as x402]))

;; ───────────────────────── policy tables ─────────────────────────

(def hard-rule-ids
  "Closed set of HARD gates. A console that does not exercise every one of
  these is incomplete — `render-html/-main` refuses to write in that case."
  #{:rbac :lien-clearance-gate :odometer-disclosure-gate :source-provenance-gate
    :licensed-disclosure :kobutsusho-license-gate :dealer-license-gate
    :repair-history-disclosure-gate :inquiry-target-gate
    :scan-coverage-gate :shaken-validity-gate :x402-receipt-gate
    :escrow-conservation-gate :payout-destination-gate
    :funds-not-arrived-gate :custody-handover-gate
    :unknown-market-gate :denied-destination-gate :steering-incompatible-gate
    :export-certificate-gate :import-permit-gate :landed-uncomputable-gate
    :tariff-conservation-gate :hs-adjudication-gate
    :import-age-gate :import-regime-gate :scope-exclusion-gate})

(def always-escalate-ops
  "Money-adjacent writes. Independently kept out of every phase `:auto` set."
  #{:escrow/capture :escrow/propose-release :payout/bind :x402/unlock})

(def filings-escalate-ops
  "Export/import artefacts. This actor never files; a human must confirm
  the label. Never in `:auto`."
  #{:export/certify :import/permit})

(def allowed-effects
  #{:listing-upsert :vehicle-sale-confirm :disclosure-serve :inquiry-upsert
    :correction-apply :escrow-upsert :custody-upsert :payout-upsert
    :x402-receipt-upsert :scan-upsert :export-cert-upsert
    :import-permit-upsert :border-quote-upsert :noop})

(def confidence-floor 0.6)

(def permissions
  "actor-role → set of operations it may perform."
  {:dealer-agent   #{:vehicle/list :sale/confirm :scan/record
                     :escrow/open :custody/transfer :custody/handover
                     :border/quote :export/certify}
   :title-officer  #{:vehicle/list :sale/confirm :dispute/request :scan/record
                     :escrow/open :escrow/capture :escrow/propose-release
                     :payout/bind :custody/transfer :custody/handover
                     :border/quote :export/certify :import/permit}
   :buyer          #{:disclosure/query :inquiry/submit :x402/unlock :border/quote}})

(def tier-columns
  "For `:disclosure/query` — the columns each licensed subscriber tier may
  see."
  (let [base #{:vin :make :model :year :title-status :price
               :prefecture :mileage :body-type :repair-history?}
        dealer-extra #{:odometer :lien-status :kobutsusho-license
                       :scan :inspection-expires :running-cost}]
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
  "`:vehicle/list` asserting a rollback reading (any market), or
  `:sale/confirm` on a non-exempt US vehicle missing the 49 U.S.C.
  Chapter 327 disclosure statement, is HARD. Non-US markets do not
  inherit that federal form."
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
          us? (= :us (:jurisdiction veh))
          exempt? (and veh (:year veh)
                       (>= (- 2026 (:year veh)) facts/odometer-exempt-model-year-age))]
      (when (and us? (not exempt?)
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
                         (get-in proposal [:value :prefecture])
                         (get-in proposal [:value :region])
                         (get-in proposal [:value :jurisdiction]))]
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

(defn- catalog-subject?
  [proposal st]
  (let [vin (get-in proposal [:value :vin])
        from-value (get-in proposal [:value :catalog?])
        from-store (when vin (:catalog? (store/vehicle st vin)))]
    (true? (or from-value from-store))))

(defn- dealer-license-violations
  "Non-JP markets that declare `:dealer-license-required?` must carry a
  local trade-licence analogue. JP keeps `:kobutsusho-license-gate`."
  [{:keys [op]} proposal st]
  (when (= op :vehicle/list)
    (let [iso (or (get-in proposal [:value :jurisdiction])
                  (get-in proposal [:value :country])
                  (when-let [vin (get-in proposal [:value :vin])]
                    (:jurisdiction (store/vehicle st vin))))
          m (border/market iso)]
      (when (and m (:dealer-license-required? m) (not= iso :jp)
                 (str/blank? (str (get-in proposal [:value :dealer-license]))))
        [{:rule :dealer-license-gate
          :detail (str "販売免許が無い: market=" iso
                       " vin=" (get-in proposal [:value :vin]))}]))))

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

(defn- scan-of [proposal st]
  (or (get-in proposal [:value :scan])
      (when-let [vin (get-in proposal [:value :vin])]
        (:scan (store/vehicle st vin)))))

(defn- scan-coverage-violations
  "Public catalog list / scan/record must carry the required camera angles."
  [{:keys [op]} proposal st]
  (when (and (contains? #{:vehicle/list :scan/record} op)
             (or (jp-subject? proposal st) (catalog-subject? proposal st)))
    (let [missing (body/missing-angles (scan-of proposal st))]
      (when (seq missing)
        [{:rule :scan-coverage-gate
          :detail (str "必須カメラ角が足りない: vin=" (get-in proposal [:value :vin])
                       " missing=" (vec missing))}]))))

(defn- shaken-validity-violations
  [{:keys [op]} proposal st]
  (when (and (contains? #{:sale/confirm :escrow/propose-release} op)
             (jp-subject? proposal st))
    (let [vin (get-in proposal [:value :vin])
          expires (or (get-in proposal [:value :inspection-expires])
                      (:inspection-expires (store/vehicle st vin)))]
      (when (commerce/shaken-expired? expires)
        [{:rule :shaken-validity-gate
          :detail (str "車検が切れている: vin=" vin " expires=" expires)}]))))

(defn- x402-receipt-violations
  [{:keys [op]} proposal]
  (when (= op :x402/unlock)
    (let [errs (x402/receipt-errors (:value proposal))]
      (when (seq errs)
        [{:rule :x402-receipt-gate
          :detail (str "x402 レシートが導出できない: " (pr-str errs))}]))))

(defn- escrow-conservation-violations
  [{:keys [op]} proposal]
  (when (= op :escrow/open)
    (let [p (or (get-in proposal [:value :plan])
                (when-let [g (get-in proposal [:value :gross-yen])]
                  (commerce/plan g)))]
      (when-not (commerce/conserved? p)
        [{:rule :escrow-conservation-gate
          :detail (str "精算プランが保存則を満たさない: " (pr-str p))}]))))

(defn- payout-destination-violations
  [{:keys [op]} proposal st]
  (when (contains? #{:escrow/open :payout/bind} op)
    (let [seller (or (get-in proposal [:value :seller-id])
                     (get-in proposal [:value :dealer]))
          dest (store/payout st seller)
          errs (if (= op :payout/bind)
                 (commerce/payout-errors (get-in proposal [:value :destination] dest))
                 (commerce/payout-errors dest))]
      (when (seq errs)
        [{:rule :payout-destination-gate
          :detail (str "払出先が未検証: seller=" seller " errs=" (pr-str errs))}]))))

(defn- funds-not-arrived-violations
  [{:keys [op]} proposal st]
  (when (= op :escrow/propose-release)
    (let [id (get-in proposal [:value :escrow-id])
          esc (store/escrow st id)]
      (when (or (nil? esc) (nil? (:capture esc)))
        [{:rule :funds-not-arrived-gate
          :detail (str "キャプチャが無いエスクローを解放しようとした: escrow-id=" id)}]))))

(defn- custody-handover-violations
  [{:keys [op]} proposal st]
  (when (= op :escrow/propose-release)
    (let [vin (get-in proposal [:value :vin])
          rec (store/custody st vin)]
      (when-not (= :handed-over (:status rec))
        [{:rule :custody-handover-gate
          :detail (str "未納車のままエスクロー解放: vin=" vin
                       " custody=" (:status rec))}]))))

(def ^:private veh-overlay-keys
  "Proposal flags that change eligibility / landed-cost without being
  stored on the listing. ZOFRI re-export is a destination mode, not a
  VIN attribute."
  [:zofri-reexport? :returning-resident? :sevs-eligible? :raws?
   :steering-waiver?])

(defn- veh-of [proposal st]
  (merge (or (store/vehicle st (get-in proposal [:value :vin]))
             (:value proposal))
         (select-keys (:value proposal) veh-overlay-keys)))

(defn- dest-of [proposal]
  (or (get-in proposal [:value :dest-country])
      (get-in proposal [:value :dest])))

(defn- quoted [proposal]
  (or (get-in proposal [:value :quote])
      (get-in proposal [:value :landed])))

(defn- border-op? [op]
  (contains? #{:sale/confirm :border/quote :export/certify :import/permit} op))

(defn- unknown-market-violations
  [{:keys [op]} proposal st]
  (when (contains? #{:vehicle/list :sale/confirm :border/quote
                     :export/certify :import/permit} op)
    (let [iso (or (get-in proposal [:value :jurisdiction])
                  (get-in proposal [:value :country])
                  (border/origin-of (veh-of proposal st)))
          dest (dest-of proposal)
          unknown (cond
                    (and iso (not (border/known-market? iso))
                         (not (border/denied-dest? iso))) iso
                    (and dest (not (border/known-market? dest))
                         (not (border/denied-dest? dest))) dest
                    :else nil)]
      (when unknown
        [{:rule :unknown-market-gate
          :detail (str "閉じた市場表に無い ISO: " unknown)}]))))

(defn- denied-destination-violations
  [{:keys [op]} proposal]
  (when (border-op? op)
    (when (border/denied-dest? (dest-of proposal))
      [{:rule :denied-destination-gate
        :detail (str "denied destination fixture: dest=" (dest-of proposal))}])))

(defn- steering-incompatible-violations
  [{:keys [op]} proposal st]
  (when (= op :sale/confirm)
    (let [veh (veh-of proposal st)
          origin (border/origin-of veh)
          dest (dest-of proposal)]
      (when (and dest (border/cross-border? origin dest)
                 (not (border/steering-ok? veh origin dest)))
        [{:rule :steering-incompatible-gate
          :detail (str "ハンドル位置が市場と不一致: " origin " → " dest)}]))))

(defn- export-certificate-violations
  [{:keys [op]} proposal st]
  (when (= op :sale/confirm)
    (let [veh (veh-of proposal st)
          origin (border/origin-of veh)
          dest (dest-of proposal)
          vin (get-in proposal [:value :vin])
          cert (store/export-cert st vin)
          claimed (get-in proposal [:value :export-certified?])]
      (when (and dest (border/cross-border? origin dest)
                 (not (or claimed (:certified? cert))))
        [{:rule :export-certificate-gate
          :detail (str "輸出証明が無い: vin=" vin " dest=" dest)}]))))

(defn- import-permit-violations
  [{:keys [op]} proposal st]
  (when (= op :sale/confirm)
    (let [veh (veh-of proposal st)
          origin (border/origin-of veh)
          dest (dest-of proposal)
          vin (get-in proposal [:value :vin])
          permit (store/import-permit st vin)
          claimed (get-in proposal [:value :import-permit?])]
      (when (and dest (border/cross-border? origin dest)
                 (not (or claimed (:permitted? permit))))
        [{:rule :import-permit-gate
          :detail (str "輸入許可が無い: vin=" vin " dest=" dest)}]))))

(defn- landed-uncomputable-violations
  [{:keys [op]} proposal st]
  (when (contains? #{:sale/confirm :border/quote} op)
    (let [veh (veh-of proposal st)
          dest (dest-of proposal)]
      (when (and dest (border/cross-border? (border/origin-of veh) dest)
                 (border/known-market? dest)
                 (not (border/denied-dest? dest)))
        (let [q (border/landed-cost veh dest)]
          (when-not (:landed/computable? q)
            [{:rule :landed-uncomputable-gate
              :detail (str "関税概算が計算できない: dest=" dest
                           " missing=" (:landed/missing q))}]))))))

(defn- tariff-conservation-violations
  [{:keys [op]} proposal st]
  (when (contains? #{:sale/confirm :border/quote} op)
    (let [veh (veh-of proposal st)
          dest (dest-of proposal)
          quote (quoted proposal)]
      (when (and dest quote)
        (let [errs (border/quote-errors veh dest quote)]
          (when (some #{:rate-manufactured :not-conserved} errs)
            [{:rule :tariff-conservation-gate
              :detail (str "関税合計が再導出と一致しない: errs=" (pr-str errs))}]))))))

(defn- hs-adjudication-violations
  [{:keys [op]} proposal]
  (when (contains? #{:sale/confirm :border/quote :export/certify :import/permit} op)
    (when (true? (get-in proposal [:value :hs :adjudicated?]))
      [{:rule :hs-adjudication-gate
        :detail "HS を確定分類として提案した。候補のみ許可。"}])))

(defn- import-age-violations
  "KS 1515 eight-year YoR cap. A missing KRA tariff does not hide an
  ineligible year — eligibility is checked on its own."
  [{:keys [op]} proposal st]
  (when (contains? #{:sale/confirm :border/quote} op)
    (let [veh (veh-of proposal st)
          dest (dest-of proposal)
          el (when dest (border/eligibility veh dest))]
      (when (= :age-ineligible (:reason el))
        [{:rule :import-age-gate
          :detail (str "仕向地の年式上限を超える: dest=" dest
                       " yor=" (:yor el) " min-yor=" (:min-yor el)
                       " source=" (:source el))}]))))

(defn- import-regime-violations
  "Used-import restricted destinations cannot be quoted as if a passenger
  car simply pays duty. AU: SEVS / RAWS / 25-year. CL: Ley 18.483
  mainland ban; ZOFRI re-export / returning-resident / 50-year historic
  are the documented exceptions."
  [{:keys [op]} proposal st]
  (when (contains? #{:sale/confirm :border/quote} op)
    (let [veh (veh-of proposal st)
          dest (dest-of proposal)
          el (when dest (border/eligibility veh dest))]
      (when (= :used-import-restricted (:reason el))
        [{:rule :import-regime-gate
          :detail (str "中古輸入制度に該当しない: dest=" dest
                       " regime=" (:regime el)
                       " source=" (:source el))}]))))

(defn- scope-exclusion-violations
  [{:keys [op already-transferred?]} proposal]
  (let [effect (:effect proposal)
        claim? (or already-transferred?
                   (get-in proposal [:value :already-transferred?])
                   (and effect (not (contains? allowed-effects effect))))]
    (when claim?
      [{:rule :scope-exclusion-gate
        :detail (str "送金・払出を実行したと主張する提案: op=" op
                     " effect=" effect)}])))

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
                              (dealer-license-violations request proposal st)
                              (repair-history-disclosure-violations request proposal st)
                              (inquiry-target-violations request proposal st)
                              (scan-coverage-violations request proposal st)
                              (shaken-validity-violations request proposal st)
                              (x402-receipt-violations request proposal)
                              (escrow-conservation-violations request proposal)
                              (payout-destination-violations request proposal st)
                              (funds-not-arrived-violations request proposal st)
                              (custody-handover-violations request proposal st)
                              (unknown-market-violations request proposal st)
                              (denied-destination-violations request proposal)
                              (steering-incompatible-violations request proposal st)
                              (export-certificate-violations request proposal st)
                              (import-permit-violations request proposal st)
                              (landed-uncomputable-violations request proposal st)
                              (tariff-conservation-violations request proposal st)
                              (hs-adjudication-violations request proposal)
                              (import-age-violations request proposal st)
                              (import-regime-violations request proposal st)
                              (scope-exclusion-violations request proposal)))
        conf     (:confidence proposal 0.0)
        low?     (< conf confidence-floor)
        vin      (or (get-in proposal [:value :vin]) (:subject request))
        salvage? (and (= (:op request) :sale/confirm) (salvage-title? st vin))
        dispute? (= :dispute/request (:op request))
        money?   (contains? always-escalate-ops (:op request))
        filings? (contains? filings-escalate-ops (:op request))
        hard?    (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not salvage?) (not dispute?)
                        (not money?) (not filings?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? salvage? dispute? money? filings?))
     :salvage?     salvage?
     :dispute?     dispute?
     :money?       money?
     :filings?     filings?}))

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
