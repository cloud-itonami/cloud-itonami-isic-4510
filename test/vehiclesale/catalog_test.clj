(ns vehiclesale.catalog-test
  (:require [clojure.test :refer [deftest is]]
            [vehiclesale.catalog :as catalog]
            [vehiclesale.store :as store]))

(defn- listings []
  (let [s (store/seed-db)]
    (mapv #(catalog/hydrate % (store/odometer-latest s (:vin %))
                            (store/title-record s (:vin %)))
          (store/all-vehicles s))))

(deftest yen-groups-thousands
  (is (= "¥1,850,000" (catalog/yen 1850000M)))
  (is (= "¥450,000" (catalog/yen 450000))))

(deftest default-search-is-jp-unsold
  (let [hits (catalog/search (listings) {})]
    (is (every? #(= :jp (:jurisdiction %)) hits))
    (is (not-any? #(= :sold (:listed-status %)) hits))
    (is (= 5 (count hits)))))

(deftest filter-by-make-and-prefecture
  (let [all (listings)]
    (is (= ["JP-100"] (mapv :vin (catalog/search all {:make "トヨタ"}))))
    (is (= ["JP-200"] (mapv :vin (catalog/search all {:prefecture :osaka}))))))

(deftest filter-by-price-and-mileage-caps
  (let [all (listings)
        cheap (catalog/search all {:price-max 1000000M})
        low-miles (catalog/search all {:mileage-max 20000})]
    (is (every? #(<= (:price %) 1000000M) cheap))
    (is (some #{"JP-200" "JP-500"} (map :vin cheap)))
    (is (= ["JP-400"] (mapv :vin low-miles)))))

(deftest keyword-q-hits-model-or-prefecture-label
  (let [all (listings)]
    (is (= ["JP-200"] (mapv :vin (catalog/search all {:q "フィット"}))))
    (is (some #{"JP-100"} (map :vin (catalog/search all {:q "東京"}))))))

(deftest card-is-display-ready
  (let [hit (first (catalog/search (listings) {:make "トヨタ"}))
        c (catalog/card hit)]
    (is (= "JP-100" (:vin c)))
    (is (= "#v/JP-100" (:href c)))
    (is (= "¥1,850,000" (:price c)))
    (is (= "東京都" (:prefecture c)))
    (is (= "修復歴なし" (:repair-history c)))
    (is (string? (:annual-cost c)))
    (is (true? (:scan-complete? c)))))

(deftest hydrate-joins-running-cost-and-scan
  (let [all (listings)
        prius (first (filter #(= "JP-100" (:vin %)) all))
        impreza (first (filter #(= "JP-500" (:vin %)) all))]
    (is (true? (:scan-complete? prius)))
    (is (false? (:scan-complete? impreza)))
    (is (pos? (get-in prius [:running-cost :total-yen])))))
