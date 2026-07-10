(ns vehiclesale.policy-contract-test
  "The governor contract as executable tests. The single invariant under
  test: VehicleSale-LLM never lists/confirms/discloses/resolves a record
  the VehicleSaleGovernor would reject, and every decision (commit OR hold)
  leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [vehiclesale.store :as store]
            [vehiclesale.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def agent-p3   {:actor-id "da-1" :actor-role :dealer-agent :phase 3})
(def officer-p3 {:actor-id "to-1" :actor-role :title-officer :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest authorized-listing-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :vehicle/list :subject "vin-100" :vin "vin-100" :make "Demo"
                   :model "Sedan" :year 2022 :title-status :clean :price 18500.00M
                   :odometer 34000 :state :ca
                   :source {:class :federal-title-registry :ref "nmvtis-doj-gateway:vin-100"}}
                  agent-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 34000 (:reading (store/odometer-latest db "vin-100"))))
    (is (= 1 (count (store/ledger db))))))

(deftest unauthorized-role-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t2"
                  {:op :vehicle/list :subject "vin-100" :vin "vin-100" :make "Demo"
                   :model "Sedan" :year 2022 :title-status :clean :price 18500.00M
                   :odometer 34000 :state :ca
                   :source {:class :federal-title-registry :ref "demo"}}
                  {:actor-id "b-1" :actor-role :buyer :phase 3})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= [:rbac] (-> (store/ledger db) first :basis)))))

(deftest active-lien-blocks-sale-confirm
  (let [[db actor] (fresh)
        res (exec-op actor "t3"
                  {:op :sale/confirm :subject "vin-200" :vin "vin-200" :lien-cleared? false
                   :odometer-disclosure-statement? true}
                  agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:lien-clearance-gate} (-> (store/ledger db) first :basis)))
    (is (true? (:active? (store/title-record db "vin-200"))))))

(deftest lien-cleared-sale-confirms
  (let [[db actor] (fresh)
        res (exec-op actor "t4"
                  {:op :sale/confirm :subject "vin-200" :vin "vin-200" :lien-cleared? true
                   :odometer-disclosure-statement? true}
                  agent-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (false? (:active? (store/title-record db "vin-200"))))))

(deftest odometer-rollback-blocks-listing
  (let [[db actor] (fresh)
        res (exec-op actor "t5"
                  {:op :vehicle/list :subject "vin-300" :vin "vin-300" :make "Demo"
                   :model "Hatchback" :year 2019 :title-status :salvage :price 6200.00M
                   :odometer 50000 :state :ca
                   :source {:class :federal-title-registry :ref "nmvtis-doj-gateway:vin-300"}}
                  agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:odometer-disclosure-gate} (-> (store/ledger db) first :basis)))
    (is (= 88000 (:reading (store/odometer-latest db "vin-300"))))))

(deftest missing-disclosure-statement-blocks-sale-confirm-for-non-exempt-vehicle
  (let [[db actor] (fresh)
        res (exec-op actor "t6"
                  {:op :sale/confirm :subject "vin-100" :vin "vin-100" :lien-cleared? true
                   :odometer-disclosure-statement? false}
                  agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:odometer-disclosure-gate} (-> (store/ledger db) first :basis)))))

(deftest age-exempt-vehicle-sale-confirms-without-disclosure-statement
  (testing "vin-400 is a 1998 model (28 years old, exceeds the 20-year exemption threshold)"
    (let [[_db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :sale/confirm :subject "vin-400" :vin "vin-400" :lien-cleared? true
                     :odometer-disclosure-statement? false}
                    agent-p3)]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest unlicensed-dmv-feed-blocks-listing
  (let [[db actor] (fresh)
        res (exec-op actor "t8"
                  {:op :vehicle/list :subject "vin-100" :vin "vin-100" :make "Demo"
                   :model "Sedan" :year 2022 :title-status :clean :price 18500.00M
                   :odometer 34000 :state :tx
                   :source {:class :operator-licensed-dmv-feed :ref "dmv-demo-expired:vin-100"
                             :license-id "dmv-demo-expired"}}
                  agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis)))))

(deftest uncontracted-disclosure-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t9"
                  {:op :disclosure/query :subject "vin-100" :vin "vin-100"}
                  {:actor-id "b-2" :actor-role :buyer :tenant "tenant-ghost" :phase 3})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis)))))

(deftest over-disclosure-beyond-tier-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t10"
                  {:op :disclosure/query :subject "vin-100" :vin "vin-100" :greedy? true}
                  {:actor-id "b-1" :actor-role :buyer :tenant "tenant-basic" :phase 3})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis)))))

(deftest salvage-title-sale-escalates-then-human-decides
  (let [[db actor] (fresh)
        r1 (exec-op actor "t11"
                 {:op :sale/confirm :subject "vin-300" :vin "vin-300" :lien-cleared? true
                  :odometer-disclosure-statement? true}
                 agent-p3)]
    (is (= :interrupted (:status r1)))
    (is (= :salvage-title (-> r1 :state :audit last :reason)))
    (let [r2 (g/run* actor {:approval {:status :approved :by "officer-1"}}
                     {:thread-id "t11" :resume? true})]
      (is (= :commit (get-in r2 [:state :disposition])))
      (is (= :commit (-> (store/ledger db) last :disposition))))))

(deftest dispute-request-always-escalates-regardless-of-confidence
  (let [[db actor] (fresh)
        r1 (exec-op actor "t12"
                 {:op :dispute/request :subject "vin-100" :disputed-field :title-status
                  :claim :clean}
                 officer-p3)]
    (is (= :interrupted (:status r1)))
    (is (= :buyer-seller-dispute (-> r1 :state :audit last :reason)))
    (testing "reject leaves the vehicle unchanged"
      (let [before (store/vehicle db "vin-100")
            r2 (g/run* actor {:approval {:status :rejected :by "officer-1"}}
                       {:thread-id "t12" :resume? true})]
        (is (= :hold (get-in r2 [:state :disposition])))
        (is (= before (store/vehicle db "vin-100")))))))

(deftest every-decision-leaves-one-ledger-fact
  (let [[db actor] (fresh)]
    (exec-op actor "a" {:op :vehicle/list :subject "vin-100" :vin "vin-100" :make "Demo"
                        :model "Sedan" :year 2022 :title-status :clean :price 18500.00M
                        :odometer 34000 :state :ca
                        :source {:class :federal-title-registry :ref "demo"}}
             agent-p3)
    (exec-op actor "b" {:op :sale/confirm :subject "vin-200" :vin "vin-200"
                        :lien-cleared? false :odometer-disclosure-statement? true}
             agent-p3)
    (is (= 2 (count (store/ledger db))))))
