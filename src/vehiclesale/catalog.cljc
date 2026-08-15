(ns vehiclesale.catalog
  "Used-car marketplace catalog — the Carsensor-like *read* face.

  Search, sort and card projection are pure functions over store listings.
  They never write, never talk to the LLM, and never bypass the governor.
  Write paths (`:vehicle/list`, `:sale/confirm`, `:inquiry/submit`) stay on
  the OperationActor.

  Default marketplace slice is `:jurisdiction :jp`. US VIN demo rows remain
  visible on the operator console; they are not this catalog's inventory."
  (:require [clojure.string :as str]
            [vehiclesale.body :as body]
            [vehiclesale.running-cost :as cost]))

(def prefectures
  "Closed label table for the demo inventory. Not a complete 47-prefecture
  gazetteer — only prefectures that appear in `store/demo-data`."
  {:tokyo "東京都" :osaka "大阪府" :aichi "愛知県"
   :fukuoka "福岡県" :hokkaido "北海道"})

(def body-types
  {:sedan "セダン" :hatch "ハッチバック" :suv "SUV"
   :minivan "ミニバン" :kei "軽自動車"})

(def fuels
  {:hybrid "ハイブリッド" :gasoline "ガソリン" :diesel "ディーゼル" :ev "電気"})

(defn grouped-int
  "Thousands-separated integer. Used for yen and for km."
  [n]
  (when n
    (let [n-long (long n)
          i (if (neg? n-long) (- n-long) n-long)
          digits (str i)
          grouped (->> (reverse digits)
                       (partition 3 3 nil)
                       (map #(apply str (reverse %)))
                       reverse
                       (str/join ","))]
      (str (when (neg? n-long) "-") grouped))))

(defn yen
  "JPY display. Input is a number or BigDecimal of whole yen."
  [n]
  (when n (str "¥" (grouped-int n))))

(defn hydrate
  "Join a vehicle row with its latest odometer and title record. Catalog
  search runs on this projection so mileage/lien are not a second lookup
  at the call site."
  [veh odo title]
  (cond-> veh
    (and (nil? (:mileage veh)) (:reading odo)) (assoc :mileage (:reading odo))
    title (assoc :lien-status (if (:active? title) :active-lien :clear))
    true (assoc :running-cost (cost/estimate veh)
                :scan-complete? (body/scan-complete? (:scan veh))
                :body-spec (body/describe veh))))

(defn- includes-ci? [hay needle]
  (and (seq needle)
       hay
       (str/includes? (str/lower-case (str hay))
                      (str/lower-case (str needle)))))

(defn search
  "Filter `listings` (hydrated vehicle maps). Unknown / nil criteria are
  ignored. Sold rows (`:listed-status :sold`) are excluded unless
  `:include-sold?` is true."
  [listings {:keys [make prefecture year-min year-max price-max mileage-max
                    body-type q include-sold? jurisdiction]
             :or {jurisdiction :jp}}]
  (->> listings
       (filter #(= jurisdiction (:jurisdiction %)))
       (remove #(and (not include-sold?) (= :sold (:listed-status %))))
       (filter #(or (nil? make) (= make (:make %))))
       (filter #(or (nil? prefecture) (= prefecture (:prefecture %))))
       (filter #(or (nil? body-type) (= body-type (:body-type %))))
       (filter #(or (nil? year-min) (and (:year %) (>= (:year %) year-min))))
       (filter #(or (nil? year-max) (and (:year %) (<= (:year %) year-max))))
       (filter #(or (nil? price-max) (and (:price %) (<= (:price %) price-max))))
       (filter #(or (nil? mileage-max) (and (:mileage %) (<= (:mileage %) mileage-max))))
       (filter #(or (nil? q) (empty? q)
                    (some (fn [field] (includes-ci? field q))
                          [(:make %) (:model %) (:grade %) (:dealer %)
                           (get prefectures (:prefecture %))])))
       (sort-by (juxt (comp - #(or % 0) :year)
                      #(or (:price %) 0M)
                      :vin))
       vec))

(defn makers [listings]
  (->> listings (map :make) (remove nil?) distinct sort vec))

(defn card
  "Stable card projection for the SPA. Keys are display-ready strings plus
  the identity `:vin` so a view can address `#v/<vin>`."
  [{:keys [vin make model grade year mileage price prefecture body-type
           fuel inspection-expires repair-history? dealer listed-status
           running-cost scan-complete?]}]
  {:vin vin
   :title (str make " " model (when grade (str " " grade)))
   :year (str year "年")
   :mileage (when mileage (str (grouped-int mileage) " km"))
   :price (yen price)
   :prefecture (get prefectures prefecture (some-> prefecture name))
   :body (get body-types body-type (some-> body-type name))
   :fuel (get fuels fuel (some-> fuel name))
   :inspection (or inspection-expires "車検情報なし")
   :repair-history (if repair-history? "修復歴あり" "修復歴なし")
   :dealer dealer
   :sold? (= :sold listed-status)
   :annual-cost (yen (:total-yen running-cost))
   :scan-complete? (boolean scan-complete?)
   :href (str "#v/" vin)})
