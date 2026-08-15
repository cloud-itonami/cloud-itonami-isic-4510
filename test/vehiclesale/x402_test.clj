(ns vehiclesale.x402-test
  (:require [clojure.test :refer [deftest is]]
            [vehiclesale.x402 :as x402]))

(deftest challenge-is-402-shape-not-yen
  (let [c (x402/challenge :scan-pack "JP-100")]
    (is (= 1 (:x402Version c)))
    (is (= "0.50" (get-in c [:accepts 0 :maxAmountRequired])))
    (is (false? (get-in c [:facilitator :custodial?])))))

(deftest receipt-errors-missing-payer
  (is (some #{:missing-payer}
            (x402/receipt-errors {:receipt-id "r1" :vin "JP-100"
                                  :resource :scan-pack})))
  (is (empty? (x402/receipt-errors {:receipt-id "r1" :vin "JP-100"
                                    :resource :scan-pack :payer "0xBUYERDEMO"}))))
