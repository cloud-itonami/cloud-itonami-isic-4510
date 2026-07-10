(ns vehiclesale.store-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [vehiclesale.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Demo" (:make (store/vehicle s "vin-100"))))
      (is (= :clean (:title-status (store/vehicle s "vin-100"))))
      (is (= :salvage (:title-status (store/vehicle s "vin-300"))))
      (is (true? (:active? (store/title-record s "vin-200"))))
      (is (false? (:active? (store/title-record s "vin-100"))))
      (is (= 32000 (:reading (store/odometer-latest s "vin-100"))))
      (is (true? (:active? (store/dmv-license s "dmv-demo-ca"))))
      (is (false? (:active? (store/dmv-license s "dmv-demo-expired"))))
      (is (= 4 (count (store/all-vehicles s)))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "listing upsert updates vehicle and odometer"
        (store/commit-record! s {:effect :listing-upsert
                                 :value {:vin "vin-100" :make "Demo" :model "Sedan" :year 2022
                                         :title-status :clean :price 18000.00M :odometer 35000
                                         :source {:class :federal-title-registry :ref "demo"}}})
        (is (= 35000 (:reading (store/odometer-latest s "vin-100")))))
      (testing "sale confirm clears the active flag"
        (store/commit-record! s {:effect :vehicle-sale-confirm :value {:vin "vin-200"}})
        (is (false? (:active? (store/title-record s "vin-200")))))
      (testing "correction-apply patches the vehicle"
        (store/commit-record! s {:effect :correction-apply
                                 :value {:patch {:title-status :clean}}
                                 :path ["vin-300"]})
        (is (= :clean (:title-status (store/vehicle s "vin-300")))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (take-last 2 (store/ledger s)))))))))

(deftest contract-lookup
  (doseq [[label s] (backends)]
    (testing label
      (is (= :tier/dealer (:tier (store/contract s "tenant-acme"))))
      (is (nil? (store/contract s "tenant-ghost"))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/vehicle s "nope")))
    (is (= [] (store/all-vehicles s)))
    (is (= [] (store/ledger s)))
    (store/with-vehicles s {"x" {:vin "x" :make "X" :title-status :clean}})
    (is (= "X" (:make (store/vehicle s "x"))))))
