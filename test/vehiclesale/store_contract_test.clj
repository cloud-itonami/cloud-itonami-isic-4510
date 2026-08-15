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
      (is (= 13 (count (store/all-vehicles s))))
      (is (= "トヨタ" (:make (store/vehicle s "JP-100"))))
      (is (= :tokyo (:prefecture (store/vehicle s "JP-100"))))
      (is (true? (:catalog? (store/vehicle s "DE-100"))))
      (is (= :eur (:currency (store/vehicle s "DE-100"))))
      (is (map? (:scan (store/vehicle s "JP-100"))))
      (is (true? (:verified? (store/payout s "デモモータース東京"))))
      (is (false? (:verified? (store/payout s "デモ自動車札幌"))))
      (is (= :at-dealer (:status (store/custody s "JP-100"))))
      (is (= :at-dealer (:status (store/custody s "DE-100")))))))

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
      (testing "inquiry upsert and sale confirm marks JP listing sold"
        (store/commit-record! s {:effect :inquiry-upsert
                                 :value {:inquiry-id "inq-1" :vin "JP-200"
                                         :buyer-id "buyer-demo" :body "試乗" :status :open}})
        (is (= "JP-200" (:vin (store/inquiry s "inq-1"))))
        (store/commit-record! s {:effect :vehicle-sale-confirm :value {:vin "JP-400"}})
        (is (= :sold (:listed-status (store/vehicle s "JP-400")))))
      (testing "escrow / custody / x402 / scan blobs round-trip"
        (store/commit-record! s {:effect :escrow-upsert
                                 :value {:escrow-id "esc-x" :vin "JP-200" :status :open}})
        (is (= :open (:status (store/escrow s "esc-x"))))
        (store/commit-record! s {:effect :custody-upsert
                                 :value {:vin "JP-200" :status :at-lot :holder "lot"}})
        (is (= :at-lot (:status (store/custody s "JP-200"))))
        (store/commit-record! s {:effect :x402-receipt-upsert
                                 :value {:receipt-id "r-1" :vin "JP-200" :resource :scan-pack}})
        (is (= :scan-pack (:resource (store/x402-receipt s "r-1"))))
        (store/commit-record! s {:effect :scan-upsert
                                 :value {:vin "JP-500" :scan {:angles {:front "x" :rear "y"}}}})
        (is (= "y" (get-in (store/vehicle s "JP-500") [:scan :angles :rear]))))
      (testing "border artefacts round-trip"
        (store/commit-record! s {:effect :export-cert-upsert
                                 :value {:vin "JP-100" :certified? true :origin :jp}})
        (is (true? (:certified? (store/export-cert s "JP-100"))))
        (store/commit-record! s {:effect :import-permit-upsert
                                 :value {:vin "JP-100" :permitted? true :dest-country :au}})
        (is (true? (:permitted? (store/import-permit s "JP-100"))))
        (store/commit-record! s {:effect :border-quote-upsert
                                 :value {:quote-id "bq-1" :vin "JP-100" :dest-country :de}})
        (is (= :de (:dest-country (store/border-quote s "bq-1")))))
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
