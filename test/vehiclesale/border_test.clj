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
    (is (= [:ae :tz :cl :ke :nz :mn :za] (border/jp-demand-dests))))

(deftest corridor-board-shows-kenya-age-and-missing-duty
  (let [row (first (filter #(= :ke (:iso %)) (border/corridor-board (veh "JP-500"))))]
    (is (false? (:eligible? row)))
    (is (= :age-ineligible (:eligibility-reason row)))
    (is (false? (:landed-computable? row)))))

(deftest chile-mainland-used-import-is-restricted
  (is (= :restricted-mainland-used (get-in border/markets [:cl :used-import])))
  (is (= :used-import-restricted (:reason (border/eligibility (prius) :cl))))
  (is (true? (:landed/computable? (border/landed-cost (prius) :cl))))
  (is (= 600 (:landed/duty-bps (border/landed-cost (prius) :cl))))
  (is (= :luxury-displacement (:landed/duty-gap (border/landed-cost (prius) :cl))))
  (is (not (border/compatible-steering? :jp :cl)))
  (is (false? (border/steering-ok? (prius) :jp :cl))))

(deftest chile-zofri-reexport-is-the-volume-exception
  (let [zofri (assoc (prius) :zofri-reexport? true)
        q (border/landed-cost zofri :cl)]
    (is (true? (border/eligible? zofri :cl)))
    (is (true? (border/steering-ok? zofri :jp :cl)))
    (is (true? (:landed/computable? q)))
    (is (zero? (:landed/duty-bps q)))
    (is (zero? (:landed/vat-bps q)))
    (is (= :onward-destination-duty (:landed/vat-gap q)))
    (is (border/conserved-quote? q))))

(deftest chile-returning-resident-clears-regime-not-steering
  (let [ret (assoc (prius) :returning-resident? true)]
    (is (= :steering-incompatible (:reason (border/eligibility ret :cl))))
    (is (true? (border/regime-ok? ret :cl)))))

(deftest chile-historic-50-year-clears-regime
  (let [old {:jurisdiction :jp :country :jp :year 1970 :steering-waiver? true}]
    (is (true? (border/eligible? old :cl)))))

(deftest chile-age-cap-is-not-invented
  (is (nil? (get-in border/markets [:cl :max-age-years])))
  (is (nil? (border/min-first-registration-year :cl))))

(deftest procedure-labels-ley-18483
  (is (some #{:ley-18483} (map :id (border/procedure :jp :cl)))))

(deftest clp-fx-is-fractional-and-conserved
  (is (= 1850000 (border/to-jpy 11562500 :clp)))
  (is (= 11562500 (border/from-jpy 1850000 :clp)))
  (is (border/conserved-quote? (border/landed-cost (prius) :cl))))

(deftest mongolia-rhd-is-eligible-without-age-cap
  (is (true? (border/eligible? (prius) :mn)))
  (is (true? (border/eligible? (veh "JP-500") :mn)))
  (is (not (border/compatible-steering? :jp :mn)))
  (is (true? (border/steering-ok? (prius) :jp :mn)))
  (is (nil? (border/min-first-registration-year :mn)))
  (is (= :unverified (get-in border/markets [:mn :age-basis]))))

(deftest mongolia-quote-is-computable-with-excise-gap
  (let [q (border/landed-cost (prius) :mn)]
    (is (true? (:landed/computable? q)))
    (is (= 500 (:landed/duty-bps q)))
    (is (= 1000 (:landed/vat-bps q)))
    (is (= :excise-age-engine (:landed/duty-gap q)))
    (is (border/conserved-quote? q))))

(deftest mongolia-procedure-labels-radiation
  (let [steps (border/procedure :jp :mn)]
    (is (some #{:pre-shipment-inspection} (map :id steps)))
    (is (false? (:required? (first (filter #(= :steering (:id %)) steps)))))))

(deftest demand-ranking-omits-closed-used-import-dests
  (let [rows (:rows border/jp-export-demand)
        za (first (filter #(= :za (:iso %)) rows))
        lk (first (filter #(= :lk (:iso %)) rows))
        th (first (filter #(= :th (:iso %)) rows))]
    (is (true? (:in-table? za)))
    (is (nil? (:omit-reason za)))
    (is (false? (:in-table? lk)))
    (is (= :duty-schedule-deferred (:omit-reason lk)))
    (is (false? (:in-table? th)))
    (is (= :used-import-banned (:omit-reason th)))
    (is (border/known-market? :za))
    (is (not (border/known-market? :lk)))
    (is (not (border/known-market? :th)))))

(deftest south-africa-itac-restricts-ordinary-jp-passenger
  (is (= :restricted-itac-used (get-in border/markets [:za :used-import])))
  (is (= :used-import-restricted (:reason (border/eligibility (prius) :za))))
  (is (true? (:landed/computable? (border/landed-cost (prius) :za))))
  (is (= 2500 (:landed/duty-bps (border/landed-cost (prius) :za))))
  (is (= 1500 (:landed/vat-bps (border/landed-cost (prius) :za))))
  (is (= :ad-valorem-excise (:landed/duty-gap (border/landed-cost (prius) :za))))
  (is (= :atv-uplift (:landed/vat-gap (border/landed-cost (prius) :za))))
  (is (border/compatible-steering? :jp :za))
  (is (true? (border/steering-ok? (prius) :jp :za)))
  (is (border/conserved-quote? (border/landed-cost (prius) :za))))

(deftest south-africa-rib-transit-is-the-volume-exception
  (let [rib (assoc (prius) :rib-transit? true)
        q (border/landed-cost rib :za)]
    (is (true? (border/eligible? rib :za)))
    (is (true? (:landed/computable? q)))
    (is (zero? (:landed/duty-bps q)))
    (is (zero? (:landed/vat-bps q)))
    (is (= :onward-destination-duty (:landed/vat-gap q)))
    (is (border/conserved-quote? q))))

(deftest south-africa-returning-resident-clears-regime-and-steering-matches
  (let [ret (assoc (prius) :returning-resident? true)]
    (is (true? (border/eligible? ret :za)))
    (is (true? (border/regime-ok? ret :za)))))

(deftest south-africa-vintage-40-year-clears-regime
  (let [old {:jurisdiction :jp :country :jp :year 1980}]
    (is (true? (border/eligible? old :za)))))

(deftest south-africa-age-cap-is-not-invented
  (is (nil? (get-in border/markets [:za :max-age-years])))
  (is (nil? (border/min-first-registration-year :za)))
  (is (= :unverified (get-in border/markets [:za :age-basis]))))

(deftest procedure-labels-itac-permit
  (is (some #{:itac-permit} (map :id (border/procedure :jp :za))))
  (is (some #{:pre-shipment-inspection} (map :id (border/procedure :jp :za)))))

(deftest zar-fx-is-conserved
  (is (= 1850000 (border/to-jpy 231250 :zar)))
  (is (= 231250 (border/from-jpy 1850000 :zar))))
