(ns vehiclesale.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [vehiclesale.facts :as facts]))

(deftest catalog-entries-are-well-formed
  (doseq [{:keys [id name class access]} facts/catalog]
    (testing (str id)
      (is (keyword? id))
      (is (string? name))
      (is (keyword? class))
      (is (keyword? access)))))

(deftest allowed-source-classes-matches-catalog
  (is (= (into #{} (map :class facts/catalog)) facts/allowed-source-classes)))

(deftest class-allowed?-rejects-unlisted-classes
  (is (facts/class-allowed? :federal-title-registry))
  (is (facts/class-allowed? :operator-licensed-dmv-feed))
  (is (not (facts/class-allowed? :inference)))
  (is (not (facts/class-allowed? :seller-assertion)))
  (is (not (facts/class-allowed? nil))))

(deftest licensed-dmv-class-recognized
  (is (facts/licensed-dmv-class? :operator-licensed-dmv-feed))
  (is (not (facts/licensed-dmv-class? :federal-title-registry))))

(deftest coverage-is-honest-not-aspirational
  (let [c (facts/coverage)]
    (is (= (count facts/catalog) (:source-count c)))
    (is (<= (:source-count c) 20))
    (is (= 2 (count (:free-public-sources c))))))
