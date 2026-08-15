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
            [vehiclesale.commerce :as commerce]
            [vehiclesale.store :as store]
            [vehiclesale.x402 :as x402]))

(def ^:private listing-pass-through
  [:vin :make :model :year :title-status :price :odometer :state
   :jurisdiction :prefecture :mileage :body-type :kobutsusho-license
   :repair-history? :inspection-expires :dealer :grade :fuel
   :displacement-cc :color :listed-status :scan :weight-kg :fuel-economy-km-l
   :doors :seats :drive :length-mm :width-mm :height-mm])

(defn- propose-list
  "Listing normalization — the LLM only normalizes/validates the caller-
  supplied listing (adds no new facts). `:unsourced?` injects the failure
  mode we must defend against: a listing arriving with no odometer-source
  citation at all — the source-provenance-gate must reject this outright."
  [_db {:keys [source unsourced?] :as req}]
  (let [src (when-not unsourced? source)
        value (-> (select-keys req listing-pass-through)
                  (assoc :source src)
                  (update :listed-status #(or % :listed)))]
    {:summary   (str "vehicle listing: " (:vin req) " " (:make req) " " (:model req) " " (:year req))
     :rationale "出典引用済みデータの正規化のみ。新規事実の生成なし。"
     :cites     [:vin :make :model :year :title-status :price :odometer]
     :source    src
     :effect    :listing-upsert
     :value     value
     :confidence (if unsourced? 0.9 0.95)}))

(defn- propose-confirm
  "Sale-confirmation draft. `lien-cleared?`/`odometer-disclosure-statement?`
  / `repair-history-disclosed?` are caller-asserted claims the LLM passes
  through untouched — it is the governor's job, not the LLM's, to verify
  them against the SSoT."
  [_db {:keys [vin lien-cleared? odometer-disclosure-statement?
               repair-history-disclosed?] :as req}]
  {:summary   (str "sale confirm: " vin)
   :rationale "リーエン解消/走行距離開示証明/修復歴開示の申告を伝達のみ。検証は governor が行う。"
   :cites     [:vin]
   :source    nil
   :effect    :vehicle-sale-confirm
   :value     (cond-> {:vin vin :lien-cleared? lien-cleared?
                       :odometer-disclosure-statement? odometer-disclosure-statement?}
                (contains? req :repair-history-disclosed?)
                (assoc :repair-history-disclosed? repair-history-disclosed?))
   :confidence 0.9})

(defn- propose-inquiry
  "Buyer inquiry (lead). The LLM does not invent a vehicle or a dealer —
  it copies the caller's vin / buyer-id / body. Identity fields beyond
  `:buyer-id` are refused by construction; do not add PII keys here."
  [_db {:keys [vin buyer-id body inquiry-id]}]
  (let [id (or inquiry-id (str "inq-" vin))]
    {:summary   (str "inquiry: " id " → " vin)
     :rationale "問い合わせ本文の正規化のみ。車両の存在確認は governor が行う。"
     :cites     [:vin :buyer-id]
     :source    nil
     :effect    :inquiry-upsert
     :value     {:inquiry-id id :vin vin :buyer-id buyer-id :body body :status :open}
     :confidence 0.9}))

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

(defn- propose-scan
  [_db {:keys [vin scan]}]
  {:summary   (str "scan record: " vin)
   :rationale "カメラ角の正規化のみ。カバレッジ判定は governor。"
   :cites     [:vin :scan]
   :source    nil
   :effect    :scan-upsert
   :value     {:vin vin :scan scan}
   :confidence 0.9})

(defn- propose-escrow-open
  [db {:keys [vin buyer-id seller-id gross-yen plan]}]
  (let [veh (store/vehicle db vin)
        seller (or seller-id (:dealer veh))
        gross (or gross-yen (:price veh))
        p (or plan (commerce/plan gross))]
    {:summary   (str "escrow open: " vin)
     :rationale "1 seller の精算プラン。送金はしない。"
     :cites     [:vin]
     :source    nil
     :effect    :escrow-upsert
     :value     {:escrow-id (str "esc-" vin)
                 :vin vin :buyer-id buyer-id :seller-id seller
                 :dealer seller :gross-yen (long gross)
                 :plan p :status :open :capture nil}
     :confidence 0.9}))

(defn- propose-escrow-capture
  [db {:keys [escrow-id vin psp-ref amount-yen]}]
  (let [prev (when escrow-id (store/escrow db escrow-id))]
    {:summary   (str "escrow capture: " escrow-id)
     :rationale "PSP 証跡の記録。解放の資金ゲートがこれを読む。"
     :cites     [:escrow-id]
     :source    nil
     :effect    :escrow-upsert
     :value     (merge prev {:escrow-id escrow-id :vin (or vin (:vin prev))
                             :capture {:psp :stripe-demo :ref psp-ref :amount-yen amount-yen}
                             :status :held})
     :confidence 0.85}))

(defn- propose-escrow-release
  [db {:keys [escrow-id vin already-transferred?]}]
  (let [prev (when escrow-id (store/escrow db escrow-id))]
    {:summary   (str "escrow release propose: " escrow-id)
     :rationale "解放の認可。レール実行は settlement actor。"
     :cites     [:escrow-id]
     :source    nil
     :effect    (if already-transferred? :stripe-transfer :escrow-upsert)
     :value     (merge prev {:escrow-id escrow-id
                             :vin (or vin (:vin prev))
                             :status :released
                             :already-transferred? (boolean already-transferred?)})
     :confidence 0.8}))

(defn- propose-custody
  [_db {:keys [vin status holder]}]
  {:summary   (str "custody: " vin " → " status)
   :rationale "所在の正規化。物理ロットは運用しない。"
   :cites     [:vin]
   :source    nil
   :effect    :custody-upsert
   :value     {:vin vin :status status :holder holder}
   :confidence 0.9})

(defn- propose-payout-bind
  [_db {:keys [seller-id destination]}]
  {:summary   (str "payout bind: " seller-id)
   :rationale "払出先の提案。検証は governor。"
   :cites     [:seller-id]
   :source    nil
   :effect    :payout-upsert
   :value     {:seller-id seller-id :destination destination
               :verified? (:verified? destination)
               :account (:account destination)
               :rail (or (:rail destination) :stripe-separate)}
   :confidence 0.7})

(defn- propose-x402
  [_db {:keys [vin resource payer tx receipt-id already-settled?]}]
  (let [id (or receipt-id (str "x402-" vin "-" (name (or resource :scan-pack))))]
    {:summary   (str "x402 unlock: " id)
     :rationale "情報面のレシート。車両代金ではない。"
     :cites     [:vin :resource]
     :source    nil
     :effect    :x402-receipt-upsert
     :value     {:receipt-id id :vin vin :resource resource :payer payer
                 :tx tx :already-settled? (boolean already-settled?)
                 :challenge (x402/challenge resource vin)}
     :confidence 0.85}))

(defn infer
  [db {:keys [op] :as request}]
  (case op
    :vehicle/list         (propose-list db request)
    :sale/confirm          (propose-confirm db request)
    :disclosure/query      (propose-disclosure db request)
    :inquiry/submit        (propose-inquiry db request)
    :dispute/request          (propose-dispute db request)
    :scan/record              (propose-scan db request)
    :escrow/open              (propose-escrow-open db request)
    :escrow/capture           (propose-escrow-capture db request)
    :escrow/propose-release   (propose-escrow-release db request)
    :custody/transfer         (propose-custody db request)
    :custody/handover         (propose-custody db (assoc request :status :handed-over))
    :payout/bind              (propose-payout-bind db request)
    :x402/unlock              (propose-x402 db request)
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
       ":inquiry-upsert|:correction-apply|:escrow-upsert|:custody-upsert|"
       ":payout-upsert|:x402-receipt-upsert|:scan-upsert) "
       ":value :confidence(0..1)。\n"
       "重要: 出典を伴わない出品や、走行距離のロールバックは絶対に提案しては"
       "いけません。送金の実行(:stripe-transfer 等)を提案してはいけません。"
       "リーエン解消・車検・スキャンカバレッジ・精算保存則の判定は"
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
