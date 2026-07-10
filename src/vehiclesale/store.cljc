(ns vehiclesale.store
  "SSoT for the motor-vehicle-sale actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. Deterministic default.
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible EAV
                       store.

  Both implement the same protocol and pass the same contract
  (test/vehiclesale/store_contract_test.clj).

  Entity shapes (ADR): a vehicle listing (VIN, make/model/year, title
  status, asking price), the title/lien record for that VIN, the odometer
  reading history, a dmv-license (provenance for
  `:operator-licensed-dmv-feed` citations), and a subscriber contract
  (tenant × tier, licensed disclosure). There is NO field anywhere in this
  schema for payment processing, escrow, or funds transfer — this actor
  only lists, discloses and confirms a sale DECISION, it never moves money
  or holds custody of the vehicle (ADR §1, the same class of structural
  exclusion as `cloud-itonami-isic-6311`'s order-routing/custody
  exclusion).

  The ledger stays append-only on every backend."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.db :as d]))

(defprotocol Store
  (vehicle [s vin])
  (all-vehicles [s])
  (title-record [s vin])
  (odometer-latest [s vin] "most recent recorded odometer reading for this VIN")
  (dmv-license [s license-id])
  (contract [s tenant])
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision/disclosure fact")
  (with-vehicles [s vehicles] "replace/seed vehicles (map vin→vehicle)")
  (with-title-records [s recs] "replace/seed title records (map vin→title-record)")
  (with-odometer-records [s recs] "replace/seed latest odometer readings (map vin→reading)")
  (with-dmv-licenses [s lics] "replace/seed dmv licenses (map license-id→license)")
  (with-contracts [s contracts] "replace/seed subscriber contracts (map tenant→contract)"))

;; ───────────────────────── demo data (fictitious, non-real VINs) ─────

(defn demo-data
  "A small, entirely fictitious dataset so the actor + tests run offline and
  no real VIN/title/lien is ever asserted by this repository. `vin-200`
  carries a demo active lien and `vin-300` a demo salvage title purely to
  exercise the governor gates — not claims about real vehicles."
  []
  {:vehicles
   {"vin-100" {:vin "vin-100" :make "Demo" :model "Sedan" :year 2022
               :title-status :clean :price 18500.00M}
    "vin-200" {:vin "vin-200" :make "Demo" :model "Truck" :year 2021
               :title-status :clean :price 27500.00M}
    "vin-300" {:vin "vin-300" :make "Demo" :model "Hatchback" :year 2019
               :title-status :salvage :price 6200.00M}
    "vin-400" {:vin "vin-400" :make "Demo" :model "Classic Coupe" :year 1998
               :title-status :clean :price 12000.00M}}
   :title-records
   {"vin-100" {:vin "vin-100" :lien-holder nil :active? false}
    "vin-200" {:vin "vin-200" :lien-holder "Demo Credit Union (fictitious)" :active? true}
    "vin-300" {:vin "vin-300" :lien-holder nil :active? false}
    "vin-400" {:vin "vin-400" :lien-holder nil :active? false}}
   :odometer-records
   {"vin-100" {:vin "vin-100" :reading 32000 :date "2026-06-01"
               :source {:class :federal-title-registry :ref "nmvtis-doj-gateway:vin-100"}}
    "vin-200" {:vin "vin-200" :reading 41000 :date "2026-05-15"
               :source {:class :federal-title-registry :ref "nmvtis-doj-gateway:vin-200"}}
    "vin-300" {:vin "vin-300" :reading 88000 :date "2026-04-10"
               :source {:class :federal-title-registry :ref "nmvtis-doj-gateway:vin-300"}}}
   :dmv-licenses
   {"dmv-demo-ca" {:license-id "dmv-demo-ca" :provider "Demo State DMV Data Access (fictitious)"
                   :states #{:ca :nv} :active? true}
    "dmv-demo-expired" {:license-id "dmv-demo-expired" :provider "Lapsed Demo DMV Feed (fictitious)"
                        :states #{:tx} :active? false}}
   :contracts
   {"tenant-acme"  {:tenant "tenant-acme" :tier :tier/dealer :active? true :purpose :dealer-inventory}
    "tenant-basic" {:tenant "tenant-basic" :tier :tier/basic :active? true :purpose :retail-buyer}}})

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
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :listing-upsert   (do (swap! a assoc-in [:vehicles (:vin value)] value)
                            (when (:odometer value)
                              (swap! a assoc-in [:odometer-records (:vin value)]
                                     {:vin (:vin value) :reading (:odometer value)
                                      :date "2026-07-10" :source (:source value)})))
      :title-upsert      (swap! a assoc-in [:title-records (:vin value)] value)
      :odometer-upsert   (swap! a assoc-in [:odometer-records (:vin value)] value)
      :vehicle-sale-confirm (swap! a update-in [:title-records (:vin value)]
                                   merge {:vin (:vin value) :active? false})
      :correction-apply  (swap! a update-in [:vehicles (first path)] merge (:patch value))
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-vehicles [s vs]        (when (seq vs) (swap! a assoc :vehicles vs)) s)
  (with-title-records [s recs] (when (seq recs) (swap! a assoc :title-records recs)) s)
  (with-odometer-records [s recs] (when (seq recs) (swap! a assoc :odometer-records recs)) s)
  (with-dmv-licenses [s lics]  (when (seq lics) (swap! a assoc :dmv-licenses lics)) s)
  (with-contracts [s cts]      (when (seq cts) (swap! a assoc :contracts cts)) s))

(defn seed-db
  "A MemStore seeded with the demo data. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger []))))

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
   :ledger/seq         {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- vehicle->tx [{:keys [vin make model year title-status price]}]
  (cond-> {:vehicle/vin vin}
    make          (assoc :vehicle/make make)
    model         (assoc :vehicle/model model)
    year          (assoc :vehicle/year year)
    title-status  (assoc :vehicle/title-status title-status)
    price         (assoc :vehicle/price (enc price))))

(defn- pull->vehicle [m]
  (when (:vehicle/vin m)
    {:vin (:vehicle/vin m) :make (:vehicle/make m) :model (:vehicle/model m)
     :year (:vehicle/year m) :title-status (:vehicle/title-status m)
     :price (dec* (:vehicle/price m))}))

(def ^:private vehicle-pull
  [:vehicle/vin :vehicle/make :vehicle/model :vehicle/year :vehicle/title-status :vehicle/price])

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
      (d/transact! conn [(title->tx (merge (title-record s (:vin value))
                                           {:vin (:vin value) :active? false}))])
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
    (when (seq cts) (d/transact! conn (mapv contract->tx (vals cts)))) s))

(defn datomic-store
  ([] (datomic-store {}))
  ([{:keys [vehicles title-records odometer-records dmv-licenses contracts]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-vehicles vehicles) (with-title-records title-records)
         (with-odometer-records odometer-records) (with-dmv-licenses dmv-licenses)
         (with-contracts contracts)))))

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
