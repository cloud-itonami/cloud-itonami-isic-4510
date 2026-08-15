(ns vehiclesale.border-test
  (:require [clojure.test :refer [deftest is]]
            [vehiclesale.border :as border]
            [vehiclesale.store :as store]))

(defn- prius []
  (store/vehicle (store/seed-db) "JP-100"))

(deftest closed-markets-do-not-invent-duty
  (is (border/known-market? :jp))
  (is (not (border/known-market? :xx)))
  (is (border/denied-dest? :zz))
  (is (false? (:landed/computable? (border/landed-cost (prius) :xx))))
  (is (= :denied-destination (:landed/missing (border/landed-cost (prius) :zz))))
  (let [sg (border/landed-cost (prius) :sg)]
    (is (false? (:landed/computable? sg)))
    (is (contains? #{:duty-uncomputable :vat-uncomputable} (:landed/missing sg)))))

(deftest hs-candidate-is-never-adjudicated
  (let [hs (border/hs-candidate (prius))]
    (is (= "870323" (:hs hs)))
    (is (false? (:adjudicated? hs)))))

(deftest jp-to-de-quote-is-conserved
  (let [q (border/landed-cost (prius) :de)]
    (is (true? (:landed/computable? q)))
    (is (border/conserved-quote? q))
    (is (= "test-fixture" (:landed/rate-source q)))
    (is (= [] (border/quote-errors (prius) :de q)))
    (is (= [:rate-manufactured]
           (border/quote-errors (prius) :de (assoc q :landed/total-minor 1))))))

(deftest rhd-to-lhd-is-incompatible
  (is (border/compatible-steering? :jp :au))
  (is (not (border/compatible-steering? :jp :us)))
  (is (not (border/compatible-steering? :de :jp))))

(deftest procedure-is-labels-not-filings
  (let [steps (border/procedure :jp :au)]
    (is (some #{:export-certificate} (map :id steps)))
    (is (some #{:import-permit} (map :id steps)))
    (is (every? string? (map :label steps)))))

(deftest to-jpy-uses-fixture-fx
  (is (= 1850000 (border/to-jpy 1850000M :jpy)))
  (is (= 4702500 (border/to-jpy 28500M :eur))))
