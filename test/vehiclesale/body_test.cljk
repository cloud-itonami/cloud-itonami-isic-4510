(ns vehiclesale.body-test
  (:require [clojure.test :refer [deftest is]]
            [vehiclesale.body :as body]))

(deftest demo-scan-covers-required-angles
  (is (body/scan-complete? (body/demo-scan "JP-100")))
  (is (empty? (body/missing-angles (body/demo-scan "JP-100")))))

(deftest short-pack-is-incomplete
  (let [scan {:angles {:front "bafkdemo-x-front"}}]
    (is (not (body/scan-complete? scan)))
    (is (contains? (body/missing-angles scan) :odometer))))
