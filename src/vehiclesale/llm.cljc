(ns vehiclesale.llm
  "VehicleSale-LLM client — the *contained intelligence node*.

  It normalizes incoming listing data, drafts sale-confirmation proposals,
  proposes subscriber disclosure column sets, and drafts dispute-resolution
  proposals. CRITICAL: it is a smart-but-untrusted advisor — it returns a
  *proposal*, never a committed listing/sale/disclosure. Every output is
  censored downstream by `vehiclesale.policy` (the VehicleSaleGovernor)
  before anything touches the SSoT or is disclosed.

  Deterministic mock so the actor graph runs offline and the governor
  contract is exercised end-to-end. In production this calls a real LLM
  (kotoba-llm) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary str :rationale str :cites [kw|str ..]
     :source {:class kw :ref str :license-id str?}|nil
     :effect kw :value map|nil :columns [kw ..]|nil :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [vehiclesale.store :as store]))

(defn- propose-list
  "Listing normalization — the LLM only normalizes/validates the caller-
  supplied listing (adds no new facts). `:unsourced?` injects the failure
  mode we must defend against: a listing arriving with no odometer-source
  citation at all — the source-provenance-gate must reject this outright."
  [_db {:keys [vin make model year title-status price odometer state source unsourced?]}]
  (let [src (when-not unsourced? source)]
    {:summary   (str "vehicle listing: " vin " " make " " model " " year)
     :rationale "出典引用済みデータの正規化のみ。新規事実の生成なし。"
     :cites     [:vin :make :model :year :title-status :price :odometer]
     :source    src
     :effect    :listing-upsert
     :value     {:vin vin :make make :model model :year year
                 :title-status title-status :price price :odometer odometer
                 :state state :source src}
     :confidence (if unsourced? 0.9 0.95)}))

(defn- propose-confirm
  "Sale-confirmation draft. `lien-cleared?`/`odometer-disclosure-statement?`
  are caller-asserted claims the LLM passes through untouched — it is the
  governor's job (lien-clearance-gate / odometer-disclosure-gate), not the
  LLM's, to verify them against the SSoT."
  [_db {:keys [vin lien-cleared? odometer-disclosure-statement?]}]
  {:summary   (str "sale confirm: " vin)
   :rationale "リーエン解消/走行距離開示証明の申告を伝達のみ。検証は governor が行う。"
   :cites     [:vin]
   :source    nil
   :effect    :vehicle-sale-confirm
   :value     {:vin vin :lien-cleared? lien-cleared?
               :odometer-disclosure-statement? odometer-disclosure-statement?}
   :confidence 0.9})

(defn- propose-disclosure
  "Disclosure column-set proposal. `:greedy?` injects over-disclosure
  (pulls `:odometer`/`:lien-status` beyond a basic-tier contract)."
  [_db {:keys [vin greedy?]}]
  (let [base [:vin :make :model :year :title-status :price]
        greedy-extra [:odometer :lien-status]]
    {:summary   (str "開示列提案: " vin)
     :rationale (if greedy? "分析に有用そうな列を広めに含めた。" "契約 tier に必要な最小列のみ。")
     :cites     base
     :source    nil
     :effect    :disclosure-serve
     :columns   (if greedy? (into base greedy-extra) base)
     :confidence 0.9}))

(defn- propose-dispute
  "Buyer/seller dispute resolution draft. This NEVER auto-applies —
  `vehiclesale.policy` and `vehiclesale.phase` both structurally force every
  `:dispute/request` to human review, independent of confidence."
  [_db {:keys [disputed-field claim]}]
  {:summary   (str "vehicle sale の " disputed-field " について紛争解決案ドラフト")
   :rationale (str "申立て内容: " claim "。裏取りは人間レビューで行う。")
   :cites     [disputed-field]
   :source    nil
   :effect    :correction-apply
   :value     {:patch {disputed-field claim}}
   :confidence 0.5})

(defn infer
  [db {:keys [op] :as request}]
  (case op
    :vehicle/list         (propose-list db request)
    :sale/confirm          (propose-confirm db request)
    :disclosure/query      (propose-disclosure db request)
    :dispute/request       (propose-dispute db request)
    {:summary "未対応の操作" :rationale (str op) :cites [] :source nil
     :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは中古/新車販売の出品・成約アドバイザーです。与えられた事実の"
       "みに基づき、提案を1つだけ EDN マップで返します。説明や前置きは一切"
       "書かず、EDN だけを出力します。\n"
       "キー: :summary :rationale :cites :source(nilも可) "
       ":effect(:listing-upsert|:vehicle-sale-confirm|:disclosure-serve|"
       ":correction-apply) :value :confidence(0..1)。\n"
       "重要: 出典を伴わない出品や、走行距離のロールバックは絶対に提案しては"
       "いけません。リーエン解消の真偽判定や走行距離開示証明の要否判定は"
       "あなたの責務ではありません(governor が判定します)。"))

(defn- facts-for [st {:keys [op subject vin]}]
  (case op
    :disclosure/query {:vehicle (store/vehicle st (or vin subject))}
    {:vehicle (store/vehicle st (or vin subject))}))

(defn- parse-proposal
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :source nil :effect :noop :confidence 0.0})))

(defn llm-advisor
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace [request proposal]
  {:t          :vehiclesalellm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :source     (:source proposal)
   :confidence (:confidence proposal)})
