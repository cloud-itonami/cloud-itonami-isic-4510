(ns vehiclesale.border-test
  (:require [clojure.test :refer [deftest is]]
            [vehiclesale.border :as border]
            [vehiclesale.store :as store]))

(defn- prius []
  (store/vehicle (store/seed-db) "JP-100"))

(defn- veh [vin]
  (store/vehicle (store/seed-db) vin))

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
    (is (some #{:sevs-raws} (map :id steps)))
    (is (every? string? (map :label steps))))
  (let [ke (border/procedure :jp :ke)]
    (is (some #{:age-cap} (map :id ke)))
    (is (some #{:pre-shipment-inspection} (map :id ke)))))

(deftest to-jpy-uses-fixture-fx
  (is (= 1850000 (border/to-jpy 1850000M :jpy)))
  (is (= 4702500 (border/to-jpy 28500M :eur))))

(deftest kenya-min-yor-is-2019-in-2026
  (is (= 2019 (border/min-first-registration-year :ke)))
  (is (nil? (border/min-first-registration-year :tz)))
  (is (nil? (border/min-first-registration-year :nz))))

(deftest kenya-age-eligibility
  (is (true? (border/eligible? (veh "JP-100") :ke)))
  (is (true? (border/eligible? (veh "JP-200") :ke)))
  (is (false? (border/eligible? (veh "JP-300") :ke)))
  (is (false? (border/eligible? (veh "JP-500") :ke)))
  (is (= :age-ineligible (:reason (border/eligibility (veh "JP-500") :ke)))))

(deftest tanzania-age-is-not-invented
  (is (true? (border/eligible? (veh "JP-500") :tz)))
  (is (= :unverified (get-in border/markets [:tz :age-basis])))
  (is (false? (:landed/computable? (border/landed-cost (veh "JP-100") :tz)))))

(deftest au-sevs-restricts-ordinary-jp-passenger
  (is (= :used-import-restricted (:reason (border/eligibility (veh "JP-100") :au))))
  (is (true? (:landed/computable? (border/landed-cost (veh "JP-100") :au))))
  (is (= :domestic (:reason (border/eligibility (veh "AU-100") :au)))))

(deftest nz-is-eligible-and-computable-from-jp
  (is (true? (border/eligible? (veh "JP-100") :nz)))
  (is (true? (:landed/computable? (border/landed-cost (veh "JP-100") :nz)))))

(deftest demand-ranking-omits-russia-from-table
  (let [ru (first (filter #(= :ru (:iso %)) (:rows border/jp-export-demand)))]
    (is (false? (:in-table? ru)))
    (is (= :sanctions-operator-input (:omit-reason ru))))
  (is (= [:ae :tz :ke :nz] (border/jp-demand-dests))))

(deftest corridor-board-shows-kenya-age-and-missing-duty
  (let [row (first (filter #(= :ke (:iso %)) (border/corridor-board (veh "JP-500"))))]
    (is (false? (:eligible? row)))
    (is (= :age-ineligible (:eligibility-reason row)))
    (is (false? (:landed-computable? row)))))
