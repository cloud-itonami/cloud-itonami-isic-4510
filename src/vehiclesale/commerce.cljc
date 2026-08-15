(ns vehiclesale.commerce
  "Vehicle purchase escrow + physical custody. Pure.

  Money does not move here. A 1-seller plan is a computation; a release
  *authorises* `cloud-itonami-marketplace-settlement` / a Stripe
  separate-charges-and-transfers rail to perform the transfer. x402 is
  the information rail (`vehiclesale.x402`), not this yen hold.

  Connect shape (hold-and-release, platform is merchant of record):

    dashboard: express
    fees_collector: application
    losses_collector: application
    charge pattern: separate charges and transfers

  Destination charges transfer on capture and cannot hold until 納車.
  Direct charges would make the dealer merchant of record; the platform
  owns the buyer relationship on this face."
  (:require [clojure.string :as str]
            [vehiclesale.running-cost :as cost]))

(def commission-bps
  "Demo platform fee. 3% of listing yen. Not a live pricing tool value."
  300)

(def custody-statuses
  #{:at-dealer :in-transit :at-lot :handed-over :returned})

(def escrow-statuses
  #{:open :held :released :disputed :refunded})

(defn plan
  "1 seller. Remainder of integer division stays with the seller so the
  operator cannot harvest rounding dust. Conserved by construction;
  the governor still re-checks."
  [gross-yen]
  (let [gross (long gross-yen)
        commission (quot (* gross commission-bps) 10000)
        seller (- gross commission)]
    {:plan/gross-yen gross
     :plan/commission-yen commission
     :plan/seller-payout-yen seller
     :plan/conserved? (= (+ seller commission) gross)
     :plan/custodial? false
     :plan/rail :stripe-separate
     :plan/commission-bps commission-bps}))

(defn conserved? [p]
  (boolean (and p (:plan/conserved? p)
                (= (+ (long (:plan/seller-payout-yen p))
                      (long (:plan/commission-yen p)))
                   (long (:plan/gross-yen p))))))

(defn ym<=
  "Year-month strings `YYYY-MM`. Nil is not a date."
  [a b]
  (and (string? a) (string? b)
       (<= (compare a b) 0)))

(defn shaken-expired?
  ([expires] (shaken-expired? expires cost/as-of-year-month))
  ([expires as-of]
   (and (string? expires) (not (ym<= as-of expires)))))

(defn stripe-release-instruction
  "Dry-run Connect transfer. `execute?` is false and stays false in this
  repo — the same default as settleops.rail/execute-transfer!."
  [{:keys [escrow-id seller-account seller-payout-yen vin]}]
  {:method :transfers.create
   :execute? false
   :amount seller-payout-yen
   :currency "jpy"
   :destination seller-account
   :transfer_group escrow-id
   :idempotency-key (str escrow-id ":" vin)
   :note "authorisation only; settlement actor / rail performs the transfer"})

(defn custody-transition-ok?
  [from to]
  (contains? (case from
               nil #{:at-dealer}
               :at-dealer #{:in-transit :at-lot :handed-over :returned}
               :in-transit #{:at-lot :at-dealer :returned}
               :at-lot #{:handed-over :returned :at-dealer}
               :handed-over #{}
               :returned #{:at-dealer}
               #{})
             to))

(defn payout-errors
  [dest]
  (cond-> []
    (nil? dest) (conj :missing-destination)
    (and dest (not (:verified? dest))) (conj :unverified-destination)
    (and dest (str/blank? (str (:account dest)))) (conj :missing-account)))
