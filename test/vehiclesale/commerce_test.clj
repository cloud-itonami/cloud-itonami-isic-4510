(ns vehiclesale.commerce-test
  (:require [clojure.test :refer [deftest is]]
            [vehiclesale.commerce :as commerce]
            [vehiclesale.running-cost :as cost]))

(deftest plan-is-conserved-and-non-executing
  (let [p (commerce/plan 1850000)]
    (is (commerce/conserved? p))
    (is (= 1850000 (:plan/gross-yen p)))
    (is (= 55500 (:plan/commission-yen p)))
    (is (= 1794500 (:plan/seller-payout-yen p)))
    (is (false? (:plan/custodial? p)))
    (is (= :stripe-separate (:plan/rail p)))))

(deftest remainder-stays-with-seller
  (let [p (commerce/plan 100)]
    (is (= 3 (:plan/commission-yen p)))
    (is (= 97 (:plan/seller-payout-yen p)))
    (is (commerce/conserved? p))))

(deftest conserved?-rejects-broken-plan
  (is (not (commerce/conserved?
            {:plan/gross-yen 100 :plan/commission-yen 50
             :plan/seller-payout-yen 10 :plan/conserved? true}))))

(deftest shaken-expired-vs-demo-clock
  (is (commerce/shaken-expired? "2025-12"))
  (is (not (commerce/shaken-expired? "2026-08")))
  (is (not (commerce/shaken-expired? "2027-04")))
  (is (not (commerce/shaken-expired? nil)))
  (is (= "2026-08" cost/as-of-year-month)))

(deftest stripe-release-stays-dry-run
  (let [i (commerce/stripe-release-instruction
           {:escrow-id "esc-JP-200" :seller-account "acct_demo_osaka"
            :seller-payout-yen 950600 :vin "JP-200"})]
    (is (false? (:execute? i)))
    (is (= "jpy" (:currency i)))
    (is (= :transfers.create (:method i)))))

(deftest payout-errors-unverified
  (is (some #{:unverified-destination}
            (commerce/payout-errors {:verified? false :account "acct_x"})))
  (is (empty? (commerce/payout-errors {:verified? true :account "acct_x"}))))
