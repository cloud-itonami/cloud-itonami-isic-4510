(ns vehiclesale.running-cost-test
  (:require [clojure.test :refer [deftest is]]
            [vehiclesale.running-cost :as cost]
            [vehiclesale.store :as store]))

(deftest automobile-tax-bands
  (is (= 30500 (cost/automobile-tax-yen 1300)))
  (is (= 36000 (cost/automobile-tax-yen 1800)))
  (is (= 36000 (cost/automobile-tax-yen 2000))))

(deftest estimate-sums-known-parts
  (let [veh (store/vehicle (store/seed-db) "JP-100")
        e (cost/estimate veh)]
    (is (pos? (:total-yen e)))
    (is (= (:total-yen e)
           (+ (:automobile-tax-yen e) (:weight-tax-yen e) (:fuel-yen e)
              (:shaken-yen e) (:compulsory-insurance-yen e)
              (:voluntary-insurance-yen e))))
    (is (re-find #"概算" (:assumption e)))))
