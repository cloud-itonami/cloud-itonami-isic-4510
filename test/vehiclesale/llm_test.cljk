(ns vehiclesale.llm-test
  (:require [clojure.test :refer [deftest is testing]]
            [vehiclesale.store :as store]
            [vehiclesale.llm :as llm]))

(deftest listing-proposal-carries-source-and-cites
  (let [db (store/seed-db)
        p (llm/infer db {:op :vehicle/list :subject "vin-100" :vin "vin-100" :make "Demo"
                         :model "Sedan" :year 2022 :title-status :clean :price 18500.00M
                         :odometer 33000 :state :ca
                         :source {:class :federal-title-registry :ref "demo"}})]
    (is (= :listing-upsert (:effect p)))
    (is (= {:class :federal-title-registry :ref "demo"} (:source p)))
    (is (>= (:confidence p) 0.9))))

(deftest unsourced-listing-proposal-carries-nil-source
  (testing "the LLM layer does not filter -- that is the governor's job"
    (let [db (store/seed-db)
          p (llm/infer db {:op :vehicle/list :subject "vin-100" :vin "vin-100" :make "Demo"
                           :model "Sedan" :year 2022 :title-status :clean :price 18500.00M
                           :odometer 33000 :state :ca
                           :source {:class :federal-title-registry :ref "demo"} :unsourced? true})]
      (is (nil? (:source p)))
      (is (>= (:confidence p) 0.85)))))

(deftest confirm-proposal-passes-through-claims-untouched
  (let [db (store/seed-db)
        p (llm/infer db {:op :sale/confirm :subject "vin-200" :vin "vin-200"
                         :lien-cleared? false :odometer-disclosure-statement? true})]
    (is (= :vehicle-sale-confirm (:effect p)))
    (is (false? (get-in p [:value :lien-cleared?])))))

(deftest disclosure-proposal-greedy-adds-extra-columns
  (let [db (store/seed-db)
        clean (llm/infer db {:op :disclosure/query :subject "vin-100" :vin "vin-100"})
        greedy (llm/infer db {:op :disclosure/query :subject "vin-100" :vin "vin-100" :greedy? true})]
    (is (< (count (:columns clean)) (count (:columns greedy))))
    (is (some #{:odometer :lien-status} (:columns greedy)))))

(deftest dispute-proposal-never-marks-high-confidence
  (let [db (store/seed-db)
        p (llm/infer db {:op :dispute/request :subject "vin-100" :disputed-field :title-status
                         :claim :clean})]
    (is (= :correction-apply (:effect p)))
    (is (< (:confidence p) 0.9))))
