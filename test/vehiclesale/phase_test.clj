(ns vehiclesale.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [vehiclesale.store :as store]
            [vehiclesale.operation :as op]))

(def dealer   {:actor-id "da-1" :actor-role :dealer-agent})
(def officer {:actor-id "to-1" :actor-role :title-officer})

(def clean-listing
  {:op :vehicle/list :subject "vin-100" :vin "vin-100" :make "Demo" :model "Sedan"
   :year 2022 :title-status :clean :price 18500.00M :odometer 33000 :state :ca
   :source {:class :federal-title-registry :ref "nmvtis-doj-gateway:vin-100"}})

(def clean-sale
  {:op :sale/confirm :subject "vin-100" :vin "vin-100" :lien-cleared? true
   :odometer-disclosure-statement? true})

(def dispute-req
  {:op :dispute/request :subject "vin-100" :disputed-field :title-status :claim :clean})

(defn- run [phase req ctx]
  (let [s (store/seed-db)
        actor (op/build s)]
    [s (g/run* actor {:request req :context (assoc ctx :phase phase)}
               {:thread-id (str "ph-" phase "-" (:op req))})]))

(deftest phase0-holds-all-writes
  (let [[s res] (run 0 clean-listing dealer)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))))

(deftest phase0-allows-governed-reads
  (let [[_ res] (run 0 {:op :disclosure/query :subject "vin-100" :vin "vin-100"}
                     {:actor-id "b-1" :actor-role :buyer :tenant "tenant-basic"})]
    (is (= :commit (get-in res [:state :disposition])))))

(deftest phase1-forces-approval-on-clean-listing
  (let [[_ res] (run 1 clean-listing dealer)]
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase2-enables-sale-confirm-under-approval
  (let [[_ res] (run 2 clean-sale dealer)]
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase3-auto-commits-clean-listing
  (let [[s res] (run 3 clean-listing dealer)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 33000 (:reading (store/odometer-latest s "vin-100"))))))

(deftest governor-hold-beats-phase
  (testing "a hard governor violation (active unresolved lien) holds even in the most permissive phase"
    (let [[_ res] (run 3 {:op :sale/confirm :subject "vin-200" :vin "vin-200"
                          :lien-cleared? false :odometer-disclosure-statement? true}
                       dealer)]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest dispute-request-never-auto-commits-at-any-phase
  (doseq [ph [0 1 2 3]]
    (let [[_ res] (run ph dispute-req officer)]
      (is (not= :commit (get-in res [:state :disposition]))
          (str "phase " ph " must not auto-commit a dispute")))))
