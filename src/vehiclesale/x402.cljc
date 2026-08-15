(ns vehiclesale.x402
  "x402 *information* catalog for a listing.

  Micropayments unlock scan / 車検抜粋 / 維持費内訳. They are not the
  vehicle purchase. The facilitator is `nexus-x402` (x402.nexus); this
  namespace holds no keys, opens no socket, and never settles. A receipt
  recorded here is evidence the governor can read — the same shape
  `cloud-itonami-marketplace-settlement` uses for x402 `:direct-split`
  reconciliation, not a payout rail."
  (:require [clojure.string :as str]))

(def facilitator
  {:host "https://x402.nexus"
   :kind :direct-split
   :custodial? false
   :note "facilitator holds no keys; pay-to is each seller treasury"})

(def resources
  "Closed menu. USD strings match x402 wire (not yen). Demo pay-to is
  fictitious and must not be sent on-chain."
  [{:id :scan-pack
    :usd "0.50"
    :path-prefix "/v/"
    :description "カメラスキャン全角"
    :pay-to "0xDEMO000000000000000000000000000000000001"
    :network :base
    :asset :usdc}
   {:id :shaken-record
    :usd "0.10"
    :path-prefix "/v/"
    :description "車検・電子車検証抜粋"
    :pay-to "0xDEMO000000000000000000000000000000000001"
    :network :base
    :asset :usdc}
   {:id :running-cost
    :usd "0.05"
    :path-prefix "/v/"
    :description "年間維持費の内訳"
    :pay-to "0xDEMO000000000000000000000000000000000001"
    :network :base
    :asset :usdc}])

(def resource-ids (into #{} (map :id resources)))

(defn resource [id]
  (first (filter #(= id (:id %)) resources)))

(defn challenge
  "HTTP 402 body shape. No network."
  [id vin]
  (when-let [r (resource id)]
    {:x402Version 1
     :accepts [{:scheme "exact"
                :network (name (:network r))
                :maxAmountRequired (:usd r)
                :resource (str (:path-prefix r) vin "/" (name id))
                :payTo (:pay-to r)
                :asset (name (:asset r))}]
     :facilitator facilitator
     :vin vin
     :resource id}))

(defn receipt-errors
  "A receipt that cannot be derived is refused before a human is asked.
  `:tx` may be a demo label; a claim of on-chain settlement without any
  tx id is the failure mode."
  [{:keys [receipt-id vin resource payer tx already-settled?]}]
  (cond-> []
    (str/blank? (str receipt-id)) (conj :missing-receipt-id)
    (str/blank? (str vin)) (conj :missing-vin)
    (not (contains? resource-ids resource)) (conj :unknown-resource)
    (str/blank? (str payer)) (conj :missing-payer)
    (and already-settled? (str/blank? (str tx))) (conj :settled-without-tx)))

(defn unlocked?
  "True when the store has a receipt for this vin × resource."
  [receipts vin resource]
  (boolean (some (fn [r]
                   (and (= vin (:vin r)) (= resource (:resource r))))
                 receipts)))
