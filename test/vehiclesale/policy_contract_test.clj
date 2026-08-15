(ns vehiclesale.policy-contract-test
  "The governor contract as executable tests. The single invariant under
  test: VehicleSale-LLM never lists/confirms/discloses/resolves a record
  the VehicleSaleGovernor would reject, and every decision (commit OR hold)
  leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [vehiclesale.commerce :as commerce]
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

(def jp-airis
  {:class :operator-licensed-shakensho-feed :ref "airis-demo:JP-100" :license-id "airis-demo"})

(deftest jp-listing-without-kobutsusho-is-held
  (let [[db actor] (fresh)
        seed (store/vehicle db "JP-100")
        res (exec-op actor "jp-k"
                     (merge seed
                            {:op :vehicle/list :subject "JP-100" :source jp-airis
                             :kobutsusho-license "" :odometer 32100})
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:kobutsusho-license-gate} (-> (store/ledger db) first :basis)))))

(deftest jp-listing-with-kobutsusho-commits
  (let [[db actor] (fresh)
        seed (store/vehicle db "JP-100")
        res (exec-op actor "jp-ok"
                     (merge {:op :vehicle/list :subject "JP-100" :source jp-airis
                             :odometer 32100}
                            seed)
                     agent-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 32100 (:reading (store/odometer-latest db "JP-100"))))))

(deftest jp-sale-without-repair-history-disclosure-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "jp-rh"
                     {:op :sale/confirm :subject "JP-400" :vin "JP-400" :lien-cleared? true}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:repair-history-disclosure-gate} (-> (store/ledger db) first :basis)))
    (is (not= :sold (:listed-status (store/vehicle db "JP-400"))))))

(deftest jp-sale-with-repair-history-disclosure-commits
  (let [[db actor] (fresh)
        res (exec-op actor "jp-sold"
                     {:op :sale/confirm :subject "JP-400" :vin "JP-400"
                      :lien-cleared? true :repair-history-disclosed? false}
                     agent-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :sold (:listed-status (store/vehicle db "JP-400"))))))

(deftest inquiry-against-missing-vin-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "ghost"
                     {:op :inquiry/submit :subject "JP-ghost" :vin "JP-ghost"
                      :buyer-id "buyer-demo" :body "x" :inquiry-id "inq-ghost"}
                     {:actor-id "b-1" :actor-role :buyer :tenant "tenant-basic" :phase 3})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:inquiry-target-gate} (-> (store/ledger db) first :basis)))))

(deftest inquiry-against-listed-vin-commits
  (let [[db actor] (fresh)
        res (exec-op actor "inq"
                     {:op :inquiry/submit :subject "JP-200" :vin "JP-200"
                      :buyer-id "buyer-demo" :body "試乗希望" :inquiry-id "inq-JP-200"}
                     {:actor-id "b-1" :actor-role :buyer :tenant "tenant-basic" :phase 3})]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "JP-200" (:vin (store/inquiry db "inq-JP-200"))))))

(deftest jp-short-scan-listing-is-held
  (let [[db actor] (fresh)
        seed (store/vehicle db "JP-500")
        res (exec-op actor "scan-short"
                     (merge seed {:op :vehicle/list :subject "JP-500" :source jp-airis
                                  :odometer 120000})
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:scan-coverage-gate} (-> (store/ledger db) first :basis)))))

(deftest jp-expired-shaken-sale-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "shaken"
                     {:op :sale/confirm :subject "JP-300" :vin "JP-300"
                      :lien-cleared? true :repair-history-disclosed? true}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:shaken-validity-gate} (-> (store/ledger db) first :basis)))))

(deftest x402-unlock-without-payer-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "x402"
                     {:op :x402/unlock :subject "JP-100" :vin "JP-100"
                      :resource :scan-pack}
                     {:actor-id "b-1" :actor-role :buyer :tenant "tenant-basic" :phase 3})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:x402-receipt-gate} (-> (store/ledger db) first :basis)))))

(deftest broken-escrow-plan-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "plan"
                     {:op :escrow/open :subject "JP-100" :vin "JP-100"
                      :buyer-id "buyer-demo"
                      :plan {:plan/gross-yen 100 :plan/commission-yen 50
                             :plan/seller-payout-yen 10 :plan/conserved? false}}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:escrow-conservation-gate} (-> (store/ledger db) first :basis)))))

(deftest unverified-seller-payout-blocks-escrow-open
  (let [[db actor] (fresh)
        res (exec-op actor "payout"
                     {:op :escrow/open :subject "JP-500" :vin "JP-500"
                      :buyer-id "buyer-demo"}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:payout-destination-gate} (-> (store/ledger db) first :basis)))))

(deftest release-without-capture-handover-or-execution-claim-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "rel"
                     {:op :escrow/propose-release :subject "JP-100" :vin "JP-100"
                      :escrow-id "esc-missing" :already-transferred? true}
                     officer-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (let [basis (-> (store/ledger db) first :basis set)]
      (is (contains? basis :funds-not-arrived-gate))
      (is (contains? basis :custody-handover-gate))
      (is (contains? basis :scope-exclusion-gate)))))

(deftest conserved-escrow-open-commits
  (let [[db actor] (fresh)
        res (exec-op actor "esc-ok"
                     {:op :escrow/open :subject "JP-100" :vin "JP-100"
                      :buyer-id "buyer-demo"}
                     agent-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :open (:status (store/escrow db "esc-JP-100"))))
    (is (commerce/conserved? (:plan (store/escrow db "esc-JP-100"))))))

(deftest de-listing-without-dealer-license-is-held
  (let [[db actor] (fresh)
        seed (store/vehicle db "DE-100")
        res (exec-op actor "de-lic"
                     (merge seed {:op :vehicle/list :subject "DE-100"
                                  :dealer-license "" :odometer 42000
                                  :source {:class :operator-licensed-eu-type-feed
                                           :ref "eu-type-demo:DE-100"
                                           :license-id "eu-type-demo"}})
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:dealer-license-gate} (-> (store/ledger db) first :basis)))))

(deftest unknown-dest-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "xx"
                     {:op :sale/confirm :subject "JP-100" :vin "JP-100"
                      :lien-cleared? true :repair-history-disclosed? true
                      :dest-country :xx}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-market-gate} (-> (store/ledger db) first :basis)))))

(deftest denied-dest-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "zz"
                     {:op :sale/confirm :subject "JP-100" :vin "JP-100"
                      :lien-cleared? true :repair-history-disclosed? true
                      :dest-country :zz}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:denied-destination-gate} (-> (store/ledger db) first :basis)))))

(deftest steering-mismatch-without-waiver-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "steer"
                     {:op :sale/confirm :subject "JP-100" :vin "JP-100"
                      :lien-cleared? true :repair-history-disclosed? true
                      :dest-country :us :export-certified? true :import-permit? true}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:steering-incompatible-gate} (-> (store/ledger db) first :basis)))))

(deftest missing-export-cert-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "exp"
                     {:op :sale/confirm :subject "JP-200" :vin "JP-200"
                      :lien-cleared? true :repair-history-disclosed? true
                      :dest-country :au}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:export-certificate-gate} (-> (store/ledger db) first :basis)))))

(deftest missing-import-permit-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "imp"
                     {:op :sale/confirm :subject "JP-200" :vin "JP-200"
                      :lien-cleared? true :repair-history-disclosed? true
                      :dest-country :gb :export-certified? true}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:import-permit-gate} (-> (store/ledger db) first :basis)))))

(deftest singapore-quote-is-uncomputable
  (let [[db actor] (fresh)
        res (exec-op actor "sg"
                     {:op :sale/confirm :subject "JP-100" :vin "JP-100"
                      :lien-cleared? true :repair-history-disclosed? true
                      :dest-country :sg :export-certified? true :import-permit? true}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:landed-uncomputable-gate} (-> (store/ledger db) first :basis)))))

(deftest manufactured-tariff-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "tariff"
                     {:op :border/quote :subject "JP-100" :vin "JP-100"
                      :dest-country :de
                      :quote {:landed/computable? true :landed/total-minor 1
                              :landed/customs-value-minor 1 :landed/duty-minor 0
                              :landed/vat-minor 0 :landed/conserved? true}}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:tariff-conservation-gate} (-> (store/ledger db) first :basis)))))

(deftest self-adjudicated-hs-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "hs"
                     {:op :border/quote :subject "JP-100" :vin "JP-100"
                      :dest-country :de
                      :hs {:hs "870323" :adjudicated? true}}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:hs-adjudication-gate} (-> (store/ledger db) first :basis)))))

(deftest honest-border-quote-commits
  (let [[db actor] (fresh)
        res (exec-op actor "bq-ok"
                     {:op :border/quote :subject "JP-100" :vin "JP-100"
                      :dest-country :de}
                     agent-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :de (:dest-country (store/border-quote db "bq-JP-100-de"))))))

(deftest kenya-age-cap-holds-2016
  (let [[db actor] (fresh)
        res (exec-op actor "ke-age"
                     {:op :sale/confirm :subject "JP-500" :vin "JP-500"
                      :lien-cleared? true :repair-history-disclosed? true
                      :dest-country :ke :export-certified? true :import-permit? true}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:import-age-gate} (-> (store/ledger db) first :basis)))))

(deftest au-sevs-regime-holds-even-with-certs
  (let [[db actor] (fresh)
        res (exec-op actor "au-sevs"
                     {:op :sale/confirm :subject "JP-100" :vin "JP-100"
                      :lien-cleared? true :repair-history-disclosed? true
                      :dest-country :au :export-certified? true :import-permit? true}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:import-regime-gate} (-> (store/ledger db) first :basis)))))

(deftest chile-ley-18483-holds-ordinary-quote
  (let [[db actor] (fresh)
        res (exec-op actor "cl-ley"
                     {:op :border/quote :subject "JP-100" :vin "JP-100"
                      :dest-country :cl}
                     agent-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:import-regime-gate} (-> (store/ledger db) first :basis)))))

(deftest chile-zofri-quote-commits-with-zero-chilean-duty
  (let [[db actor] (fresh)
        res (exec-op actor "cl-zofri"
                     {:op :border/quote :subject "JP-100" :vin "JP-100"
                      :dest-country :cl :zofri-reexport? true}
                     agent-p3)
        q (store/border-quote db "bq-JP-100-cl")]
    (is (= :commit (get-in res [:state :disposition])))
    (is (zero? (get-in q [:quote :landed/duty-bps])))
    (is (= :onward-destination-duty (get-in q [:quote :landed/vat-gap])))))

(deftest mongolia-quote-commits-with-excise-gap
  (let [[db actor] (fresh)
        res (exec-op actor "mn-q"
                     {:op :border/quote :subject "JP-100" :vin "JP-100"
                      :dest-country :mn}
                     agent-p3)
        q (store/border-quote db "bq-JP-100-mn")]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 500 (get-in q [:quote :landed/duty-bps])))
    (is (= :excise-age-engine (get-in q [:quote :landed/duty-gap])))))
