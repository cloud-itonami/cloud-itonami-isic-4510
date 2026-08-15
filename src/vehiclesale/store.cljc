(ns vehiclesale.store
  "SSoT for the motor-vehicle-sale actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. Deterministic default.
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible EAV
                       store.

  Both implement the same protocol and pass the same contract
  (test/vehiclesale/store_contract_test.clj).

  Entity shapes (ADR): a vehicle listing (VIN, make/model/year, title
  status, asking price, plus JP marketplace fields: prefecture, body-type,
  古物商許可, 修復歴, 車検, body dimensions, camera-scan pack), the
  title/lien record, odometer history, a dmv-license (US DMV *and* JP
  AIRIS/電子車検証), a subscriber contract, a buyer inquiry, an escrow
  *plan* (yen hold authorisation), a custody record, a payout destination,
  and an x402 information receipt.

  This actor never executes a transfer. A committed `:escrow-upsert` is a
  computation + authorisation record. `cloud-itonami-marketplace-settlement`
  is the money actor; Stripe separate-charges-and-transfers and nexus-x402
  are rails outside this process. Physical custody is a status on the VIN,
  not a lot this actor operates."
  (:require [clojure.string :as str]
            [langchain.db :as d]
            [langchain-store.core :as ls]
            [vehiclesale.body :as body]))

(defprotocol Store
  (vehicle [s vin])
  (all-vehicles [s])
  (title-record [s vin])
  (odometer-latest [s vin] "most recent recorded odometer reading for this VIN")
  (dmv-license [s license-id])
  (contract [s tenant])
  (ledger [s])
  (inquiry [s id])
  (all-inquiries [s])
  (escrow [s id])
  (all-escrows [s])
  (custody [s vin])
  (payout [s seller-id])
  (x402-receipt [s id])
  (all-x402-receipts [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision/disclosure fact")
  (with-vehicles [s vehicles] "replace/seed vehicles (map vin→vehicle)")
  (with-title-records [s recs] "replace/seed title records (map vin→title-record)")
  (with-odometer-records [s recs] "replace/seed latest odometer readings (map vin→reading)")
  (with-dmv-licenses [s lics] "replace/seed dmv licenses (map license-id→license)")
  (with-contracts [s contracts] "replace/seed subscriber contracts (map tenant→contract)")
  (with-inquiries [s inquiries] "replace/seed buyer inquiries (map id→inquiry)")
  (with-escrows [s escrows])
  (with-custody [s recs])
  (with-payouts [s recs])
  (with-x402-receipts [s recs]))

;; ───────────────────────── demo data (fictitious, non-real VINs) ─────

(defn demo-data
  "A small, entirely fictitious dataset so the actor + tests run offline and
  no real VIN/title/lien is ever asserted by this repository. `vin-200`
  carries a demo active lien and `vin-300` a demo salvage title purely to
  exercise the governor gates — not claims about real vehicles.

  `JP-*` rows are the Japan used-car marketplace inventory (Carsensor-like
  face). Makes, models, prices and 古物商許可番号 are invented. They are
  not listings of real cars or real dealers."
  []
  {:vehicles
   {"vin-100" {:vin "vin-100" :make "Demo" :model "Sedan" :year 2022
               :title-status :clean :price 18500.00M :jurisdiction :us
               :listed-status :listed}
    "vin-200" {:vin "vin-200" :make "Demo" :model "Truck" :year 2021
               :title-status :clean :price 27500.00M :jurisdiction :us
               :listed-status :listed}
    "vin-300" {:vin "vin-300" :make "Demo" :model "Hatchback" :year 2019
               :title-status :salvage :price 6200.00M :jurisdiction :us
               :listed-status :listed}
    "vin-400" {:vin "vin-400" :make "Demo" :model "Classic Coupe" :year 1998
               :title-status :clean :price 12000.00M :jurisdiction :us
               :listed-status :listed}
    "JP-100" {:vin "JP-100" :jurisdiction :jp :make "トヨタ" :model "プリウス"
              :grade "S" :year 2021 :mileage 32000 :price 1850000M
              :prefecture :tokyo :body-type :sedan :fuel :hybrid
              :displacement-cc 1800 :color "シルバー"
              :inspection-expires "2027-04" :repair-history? false
              :dealer "デモモータース東京"
              :kobutsusho-license "東京都公安委員会 第301019900001号"
              :title-status :clean :listed-status :listed
              :weight-kg 1350 :fuel-economy-km-l 28.0
              :doors 5 :seats 5 :drive :ff
              :length-mm 4540 :width-mm 1760 :height-mm 1470
              :scan (body/demo-scan "JP-100")}
    "JP-200" {:vin "JP-200" :jurisdiction :jp :make "ホンダ" :model "フィット"
              :grade "HOME" :year 2019 :mileage 51000 :price 980000M
              :prefecture :osaka :body-type :hatch :fuel :gasoline
              :displacement-cc 1300 :color "ホワイト"
              :inspection-expires "2026-11" :repair-history? false
              :dealer "デモオート大阪"
              :kobutsusho-license "大阪府公安委員会 第621019900002号"
              :title-status :clean :listed-status :listed
              :weight-kg 1080 :fuel-economy-km-l 21.0
              :doors 5 :seats 5 :drive :ff
              :length-mm 3990 :width-mm 1695 :height-mm 1520
              :scan (body/demo-scan "JP-200")}
    "JP-300" {:vin "JP-300" :jurisdiction :jp :make "日産" :model "セレナ"
              :grade "XV" :year 2018 :mileage 78000 :price 1280000M
              :prefecture :fukuoka :body-type :minivan :fuel :gasoline
              :displacement-cc 2000 :color "ブラック"
              :inspection-expires "2025-12" :repair-history? true
              :dealer "デモカー福岡"
              :kobutsusho-license "福岡県公安委員会 第401019900003号"
              :title-status :clean :listed-status :listed
              :weight-kg 1680 :fuel-economy-km-l 13.5
              :doors 5 :seats 8 :drive :ff
              :length-mm 4770 :width-mm 1740 :height-mm 1865
              :scan (body/demo-scan "JP-300")}
    "JP-400" {:vin "JP-400" :jurisdiction :jp :make "マツダ" :model "CX-5"
              :grade "XD" :year 2022 :mileage 18000 :price 2680000M
              :prefecture :aichi :body-type :suv :fuel :diesel
              :displacement-cc 2200 :color "ソウルレッド"
              :inspection-expires "2028-01" :repair-history? false
              :dealer "デモモータース名古屋"
              :kobutsusho-license "愛知県公安委員会 第231019900004号"
              :title-status :clean :listed-status :listed
              :weight-kg 1620 :fuel-economy-km-l 18.0
              :doors 5 :seats 5 :drive :4wd
              :length-mm 4575 :width-mm 1845 :height-mm 1680
              :scan (body/demo-scan "JP-400")}
    "JP-500" {:vin "JP-500" :jurisdiction :jp :make "スバル" :model "インプレッサ"
              :grade "1.6i" :year 2016 :mileage 120000 :price 450000M
              :prefecture :hokkaido :body-type :sedan :fuel :gasoline
              :displacement-cc 1600 :color "ブルー"
              :inspection-expires "2026-08" :repair-history? true
              :dealer "デモ自動車札幌"
              :kobutsusho-license "北海道公安委員会 第101019900005号"
              :title-status :clean :listed-status :listed
              :weight-kg 1280 :fuel-economy-km-l 16.0
              :doors 4 :seats 5 :drive :4wd
              :length-mm 4580 :width-mm 1740 :height-mm 1465
              :scan {:angles {:front "bafkdemo-JP-500-front"}
                     :captured-at "2026-04-02" :operator-id "da-1"
                     :fictitious? true}}}
   :title-records
   {"vin-100" {:vin "vin-100" :lien-holder nil :active? false}
    "vin-200" {:vin "vin-200" :lien-holder "Demo Credit Union (fictitious)" :active? true}
    "vin-300" {:vin "vin-300" :lien-holder nil :active? false}
    "vin-400" {:vin "vin-400" :lien-holder nil :active? false}
    "JP-100" {:vin "JP-100" :lien-holder nil :active? false}
    "JP-200" {:vin "JP-200" :lien-holder nil :active? false}
    "JP-300" {:vin "JP-300" :lien-holder nil :active? false}
    "JP-400" {:vin "JP-400" :lien-holder nil :active? false}
    "JP-500" {:vin "JP-500" :lien-holder nil :active? false}}
   :odometer-records
   {"vin-100" {:vin "vin-100" :reading 32000 :date "2026-06-01"
               :source {:class :federal-title-registry :ref "nmvtis-doj-gateway:vin-100"}}
    "vin-200" {:vin "vin-200" :reading 41000 :date "2026-05-15"
               :source {:class :federal-title-registry :ref "nmvtis-doj-gateway:vin-200"}}
    "vin-300" {:vin "vin-300" :reading 88000 :date "2026-04-10"
               :source {:class :federal-title-registry :ref "nmvtis-doj-gateway:vin-300"}}
    "JP-100" {:vin "JP-100" :reading 32000 :date "2026-07-01"
              :source {:class :operator-licensed-shakensho-feed :ref "airis-demo:JP-100"}}
    "JP-200" {:vin "JP-200" :reading 51000 :date "2026-06-20"
              :source {:class :operator-licensed-shakensho-feed :ref "airis-demo:JP-200"}}
    "JP-300" {:vin "JP-300" :reading 78000 :date "2026-05-11"
              :source {:class :operator-licensed-shakensho-feed :ref "airis-demo:JP-300"}}
    "JP-400" {:vin "JP-400" :reading 18000 :date "2026-07-08"
              :source {:class :operator-licensed-shakensho-feed :ref "airis-demo:JP-400"}}
    "JP-500" {:vin "JP-500" :reading 120000 :date "2026-04-02"
              :source {:class :operator-licensed-shakensho-feed :ref "airis-demo:JP-500"}}}
   :dmv-licenses
   {"dmv-demo-ca" {:license-id "dmv-demo-ca" :provider "Demo State DMV Data Access (fictitious)"
                   :states #{:ca :nv} :active? true}
    "dmv-demo-expired" {:license-id "dmv-demo-expired" :provider "Lapsed Demo DMV Feed (fictitious)"
                        :states #{:tx} :active? false}
    "airis-demo" {:license-id "airis-demo"
                  :provider "Demo AIRIS / 電子車検証 feed (fictitious)"
                  :states #{:tokyo :osaka :aichi :fukuoka :hokkaido}
                  :active? true}
    "airis-demo-expired" {:license-id "airis-demo-expired"
                          :provider "Lapsed Demo AIRIS feed (fictitious)"
                          :states #{:tokyo} :active? false}}
   :contracts
   {"tenant-acme"  {:tenant "tenant-acme" :tier :tier/dealer :active? true :purpose :dealer-inventory}
    "tenant-basic" {:tenant "tenant-basic" :tier :tier/basic :active? true :purpose :retail-buyer}}
   :payouts
   {"デモモータース東京" {:seller-id "デモモータース東京" :verified? true
                     :rail :stripe-separate :account "acct_demo_tokyo"}
    "デモオート大阪" {:seller-id "デモオート大阪" :verified? true
                   :rail :stripe-separate :account "acct_demo_osaka"}
    "デモカー福岡" {:seller-id "デモカー福岡" :verified? true
                  :rail :stripe-separate :account "acct_demo_fukuoka"}
    "デモモータース名古屋" {:seller-id "デモモータース名古屋" :verified? true
                      :rail :stripe-separate :account "acct_demo_aichi"}
    "デモ自動車札幌" {:seller-id "デモ自動車札幌" :verified? false
                   :rail :stripe-separate :account "acct_demo_hokkaido"}}
   :custody
   {"JP-100" {:vin "JP-100" :status :at-dealer :holder "デモモータース東京"}
    "JP-200" {:vin "JP-200" :status :at-dealer :holder "デモオート大阪"}
    "JP-300" {:vin "JP-300" :status :at-dealer :holder "デモカー福岡"}
    "JP-400" {:vin "JP-400" :status :at-dealer :holder "デモモータース名古屋"}
    "JP-500" {:vin "JP-500" :status :at-dealer :holder "デモ自動車札幌"}}
   :escrows {}
   :x402-receipts {}
   :inquiries {}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (vehicle [_ vin] (get-in @a [:vehicles vin]))
  (all-vehicles [_] (sort-by :vin (vals (:vehicles @a))))
  (title-record [_ vin] (get-in @a [:title-records vin]))
  (odometer-latest [_ vin] (get-in @a [:odometer-records vin]))
  (dmv-license [_ license-id] (get-in @a [:dmv-licenses license-id]))
  (contract [_ tenant] (get-in @a [:contracts tenant]))
  (ledger [_] (:ledger @a))
  (inquiry [_ id] (get-in @a [:inquiries id]))
  (all-inquiries [_] (sort-by :inquiry-id (vals (:inquiries @a))))
  (escrow [_ id] (get-in @a [:escrows id]))
  (all-escrows [_] (sort-by :escrow-id (vals (:escrows @a))))
  (custody [_ vin] (get-in @a [:custody vin]))
  (payout [_ seller-id] (get-in @a [:payouts seller-id]))
  (x402-receipt [_ id] (get-in @a [:x402-receipts id]))
  (all-x402-receipts [_] (sort-by :receipt-id (vals (:x402-receipts @a))))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :listing-upsert   (do (swap! a assoc-in [:vehicles (:vin value)] value)
                            (when (:odometer value)
                              (swap! a assoc-in [:odometer-records (:vin value)]
                                     {:vin (:vin value) :reading (:odometer value)
                                      :date "2026-07-10" :source (:source value)})))
      :title-upsert      (swap! a assoc-in [:title-records (:vin value)] value)
      :odometer-upsert   (swap! a assoc-in [:odometer-records (:vin value)] value)
      :vehicle-sale-confirm
      (do (swap! a update-in [:title-records (:vin value)]
                 merge {:vin (:vin value) :active? false})
          (swap! a update-in [:vehicles (:vin value)]
                 merge {:listed-status :sold}))
      :inquiry-upsert    (swap! a assoc-in [:inquiries (:inquiry-id value)] value)
      :escrow-upsert     (swap! a assoc-in [:escrows (:escrow-id value)] value)
      :custody-upsert    (swap! a assoc-in [:custody (:vin value)] value)
      :payout-upsert     (swap! a assoc-in [:payouts (:seller-id value)] value)
      :x402-receipt-upsert (swap! a assoc-in [:x402-receipts (:receipt-id value)] value)
      :scan-upsert       (swap! a update-in [:vehicles (:vin value)]
                                merge (select-keys value [:scan :vin]))
      :correction-apply  (swap! a update-in [:vehicles (first path)] merge (:patch value))
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-vehicles [s vs]        (when (seq vs) (swap! a assoc :vehicles vs)) s)
  (with-title-records [s recs] (when (seq recs) (swap! a assoc :title-records recs)) s)
  (with-odometer-records [s recs] (when (seq recs) (swap! a assoc :odometer-records recs)) s)
  (with-dmv-licenses [s lics]  (when (seq lics) (swap! a assoc :dmv-licenses lics)) s)
  (with-contracts [s cts]      (when (seq cts) (swap! a assoc :contracts cts)) s)
  (with-inquiries [s inqs]     (when (seq inqs) (swap! a assoc :inquiries inqs)) s)
  (with-escrows [s xs]         (when (seq xs) (swap! a assoc :escrows xs)) s)
  (with-custody [s xs]         (when (seq xs) (swap! a assoc :custody xs)) s)
  (with-payouts [s xs]         (when (seq xs) (swap! a assoc :payouts xs)) s)
  (with-x402-receipts [s xs]   (when (seq xs) (swap! a assoc :x402-receipts xs)) s))

(defn seed-db
  "A MemStore seeded with the demo data. The deterministic default."
  []
  (->MemStore (atom (merge (demo-data)
                           {:ledger [] :inquiries {} :escrows {}
                            :x402-receipts {}}))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs declared. Compound
  values are stored as EDN strings so `langchain.db` doesn't expand them
  into sub-entities."
  {:vehicle/vin        {:db/unique :db.unique/identity}
   :title/vin          {:db/unique :db.unique/identity}
   :odometer/vin       {:db/unique :db.unique/identity}
   :dmv-license/id     {:db/unique :db.unique/identity}
   :contract/tenant    {:db/unique :db.unique/identity}
   :inquiry/id         {:db/unique :db.unique/identity}
   :escrow/id          {:db/unique :db.unique/identity}
   :custody/vin        {:db/unique :db.unique/identity}
   :payout/id          {:db/unique :db.unique/identity}
   :x402/id            {:db/unique :db.unique/identity}
   :ledger/seq         {:db/unique :db.unique/identity}})

(defn- enc [v] (ls/enc v))
(defn- dec* [s] (ls/dec* s))

(def ^:private listing-keys
  [:jurisdiction :prefecture :mileage :body-type :kobutsusho-license
   :repair-history? :inspection-expires :dealer :grade :fuel
   :displacement-cc :color :listed-status :weight-kg :fuel-economy-km-l
   :doors :seats :drive :length-mm :width-mm :height-mm :scan])

(defn- vehicle->tx [{:keys [vin make model year title-status price] :as v}]
  (cond-> {:vehicle/vin vin :vehicle/listing (enc (select-keys v listing-keys))}
    make          (assoc :vehicle/make make)
    model         (assoc :vehicle/model model)
    year          (assoc :vehicle/year year)
    title-status  (assoc :vehicle/title-status title-status)
    price         (assoc :vehicle/price (enc price))))

(defn- pull->vehicle [m]
  (when (:vehicle/vin m)
    (merge {:vin (:vehicle/vin m) :make (:vehicle/make m) :model (:vehicle/model m)
            :year (:vehicle/year m) :title-status (:vehicle/title-status m)
            :price (dec* (:vehicle/price m))}
           (or (dec* (:vehicle/listing m)) {}))))

(def ^:private vehicle-pull
  [:vehicle/vin :vehicle/make :vehicle/model :vehicle/year :vehicle/title-status
   :vehicle/price :vehicle/listing])

(defn- title->tx [{:keys [vin lien-holder active?]}]
  {:title/vin vin :title/lien-holder (or lien-holder "") :title/active (boolean active?)})

(defn- pull->title [m]
  (when (:title/vin m)
    {:vin (:title/vin m)
     :lien-holder (let [lh (:title/lien-holder m)] (when (seq lh) lh))
     :active? (:title/active m)}))

(def ^:private title-pull [:title/vin :title/lien-holder :title/active])

(defn- odometer->tx [{:keys [vin reading date source]}]
  {:odometer/vin vin :odometer/reading reading :odometer/date date
   :odometer/source (enc source)})

(defn- pull->odometer [m]
  (when (:odometer/vin m)
    {:vin (:odometer/vin m) :reading (:odometer/reading m) :date (:odometer/date m)
     :source (dec* (:odometer/source m))}))

(def ^:private odometer-pull [:odometer/vin :odometer/reading :odometer/date :odometer/source])

(defn- dmv-license->tx [{:keys [license-id provider states active?]}]
  {:dmv-license/id license-id :dmv-license/provider provider
   :dmv-license/states (enc states) :dmv-license/active active?})

(defn- pull->dmv-license [m]
  (when (:dmv-license/id m)
    {:license-id (:dmv-license/id m) :provider (:dmv-license/provider m)
     :states (dec* (:dmv-license/states m)) :active? (:dmv-license/active m)}))

(def ^:private dmv-license-pull
  [:dmv-license/id :dmv-license/provider :dmv-license/states :dmv-license/active])

(defn- contract->tx [{:keys [tenant tier active? purpose]}]
  {:contract/tenant tenant :contract/tier tier :contract/active active? :contract/purpose purpose})

(defn- pull->contract [m]
  (when (:contract/tenant m)
    {:tenant (:contract/tenant m) :tier (:contract/tier m)
     :active? (:contract/active m) :purpose (:contract/purpose m)}))

(def ^:private contract-pull
  [:contract/tenant :contract/tier :contract/active :contract/purpose])

(defn- inquiry->tx [{:keys [inquiry-id] :as v}]
  {:inquiry/id inquiry-id :inquiry/blob (enc v)})

(defn- pull->inquiry [m]
  (when (:inquiry/id m) (dec* (:inquiry/blob m))))

(def ^:private inquiry-pull [:inquiry/id :inquiry/blob])

(defn- blob-tx [id-k blob-k id v]
  {id-k id blob-k (enc v)})

(defn- pull->blob [id-k blob-k m]
  (when (get m id-k) (dec* (get m blob-k))))

(defrecord DatomicStore [conn]
  Store
  (vehicle [_ vin] (pull->vehicle (d/pull (d/db conn) vehicle-pull [:vehicle/vin vin])))
  (all-vehicles [_]
    (->> (d/q '[:find [?v ...] :where [?e :vehicle/vin ?v]] (d/db conn))
         (map #(pull->vehicle (d/pull (d/db conn) vehicle-pull [:vehicle/vin %])))
         (sort-by :vin)))
  (title-record [_ vin] (pull->title (d/pull (d/db conn) title-pull [:title/vin vin])))
  (odometer-latest [_ vin] (pull->odometer (d/pull (d/db conn) odometer-pull [:odometer/vin vin])))
  (dmv-license [_ license-id]
    (pull->dmv-license (d/pull (d/db conn) dmv-license-pull [:dmv-license/id license-id])))
  (contract [_ tenant] (pull->contract (d/pull (d/db conn) contract-pull [:contract/tenant tenant])))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (inquiry [_ id] (pull->inquiry (d/pull (d/db conn) inquiry-pull [:inquiry/id id])))
  (all-inquiries [_]
    (->> (d/q '[:find [?i ...] :where [?e :inquiry/id ?i]] (d/db conn))
         (map #(pull->inquiry (d/pull (d/db conn) inquiry-pull [:inquiry/id %])))
         (sort-by :inquiry-id)))
  (escrow [_ id] (pull->blob :escrow/id :escrow/blob
                             (d/pull (d/db conn) [:escrow/id :escrow/blob] [:escrow/id id])))
  (all-escrows [_]
    (->> (d/q '[:find [?i ...] :where [?e :escrow/id ?i]] (d/db conn))
         (map #(pull->blob :escrow/id :escrow/blob
                           (d/pull (d/db conn) [:escrow/id :escrow/blob] [:escrow/id %])))
         (sort-by :escrow-id)))
  (custody [_ vin] (pull->blob :custody/vin :custody/blob
                               (d/pull (d/db conn) [:custody/vin :custody/blob] [:custody/vin vin])))
  (payout [_ seller-id]
    (pull->blob :payout/id :payout/blob
                (d/pull (d/db conn) [:payout/id :payout/blob] [:payout/id seller-id])))
  (x402-receipt [_ id]
    (pull->blob :x402/id :x402/blob
                (d/pull (d/db conn) [:x402/id :x402/blob] [:x402/id id])))
  (all-x402-receipts [_]
    (->> (d/q '[:find [?i ...] :where [?e :x402/id ?i]] (d/db conn))
         (map #(pull->blob :x402/id :x402/blob
                           (d/pull (d/db conn) [:x402/id :x402/blob] [:x402/id %])))
         (sort-by :receipt-id)))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :listing-upsert   (do (d/transact! conn [(vehicle->tx value)])
                            (when (:odometer value)
                              (d/transact! conn [(odometer->tx
                                                  {:vin (:vin value) :reading (:odometer value)
                                                   :date "2026-07-10" :source (:source value)})])))
      :title-upsert      (d/transact! conn [(title->tx value)])
      :odometer-upsert   (d/transact! conn [(odometer->tx value)])
      :vehicle-sale-confirm
      (do (d/transact! conn [(title->tx (merge (title-record s (:vin value))
                                               {:vin (:vin value) :active? false}))])
          (d/transact! conn [(vehicle->tx (merge (vehicle s (:vin value))
                                                 {:listed-status :sold}))]))
      :inquiry-upsert    (d/transact! conn [(inquiry->tx value)])
      :escrow-upsert     (d/transact! conn [(blob-tx :escrow/id :escrow/blob (:escrow-id value) value)])
      :custody-upsert    (d/transact! conn [(blob-tx :custody/vin :custody/blob (:vin value) value)])
      :payout-upsert     (d/transact! conn [(blob-tx :payout/id :payout/blob (:seller-id value) value)])
      :x402-receipt-upsert (d/transact! conn [(blob-tx :x402/id :x402/blob (:receipt-id value) value)])
      :scan-upsert
      (d/transact! conn [(vehicle->tx (merge (vehicle s (:vin value))
                                             (select-keys value [:scan :vin])))])
      :correction-apply
      (d/transact! conn [(vehicle->tx (merge (vehicle s (first path)) (:patch value)))])
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-vehicles [s vs]
    (when (seq vs) (d/transact! conn (mapv vehicle->tx (vals vs)))) s)
  (with-title-records [s recs]
    (when (seq recs) (d/transact! conn (mapv title->tx (vals recs)))) s)
  (with-odometer-records [s recs]
    (when (seq recs) (d/transact! conn (mapv odometer->tx (vals recs)))) s)
  (with-dmv-licenses [s lics]
    (when (seq lics) (d/transact! conn (mapv dmv-license->tx (vals lics)))) s)
  (with-contracts [s cts]
    (when (seq cts) (d/transact! conn (mapv contract->tx (vals cts)))) s)
  (with-inquiries [s inqs]
    (when (seq inqs) (d/transact! conn (mapv inquiry->tx (vals inqs)))) s)
  (with-escrows [s xs]
    (when (seq xs) (d/transact! conn (mapv #(blob-tx :escrow/id :escrow/blob (:escrow-id %) %)
                                           (vals xs)))) s)
  (with-custody [s xs]
    (when (seq xs) (d/transact! conn (mapv #(blob-tx :custody/vin :custody/blob (:vin %) %)
                                           (vals xs)))) s)
  (with-payouts [s xs]
    (when (seq xs) (d/transact! conn (mapv #(blob-tx :payout/id :payout/blob (:seller-id %) %)
                                           (vals xs)))) s)
  (with-x402-receipts [s xs]
    (when (seq xs) (d/transact! conn (mapv #(blob-tx :x402/id :x402/blob (:receipt-id %) %)
                                           (vals xs)))) s))

(defn datomic-store
  ([] (datomic-store {}))
  ([{:keys [vehicles title-records odometer-records dmv-licenses contracts
            inquiries escrows custody payouts x402-receipts]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-vehicles vehicles) (with-title-records title-records)
         (with-odometer-records odometer-records) (with-dmv-licenses dmv-licenses)
         (with-contracts contracts) (with-inquiries inquiries)
         (with-escrows escrows) (with-custody custody)
         (with-payouts payouts) (with-x402-receipts x402-receipts)))))

(defn datomic-seed-db []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "subject=" subject)
             (str "basis=" (pr-str basis))]))
