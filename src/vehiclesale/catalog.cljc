(ns vehiclesale.catalog
  "Used-car marketplace catalog — the Carsensor-like *read* face.

  Search, sort and card projection are pure functions over store listings.
  They never write, never talk to the LLM, and never bypass the governor.
  Write paths (`:vehicle/list`, `:sale/confirm`, `:inquiry/submit`,
  `:inquiry/reply`, `:escrow/open`) stay on the OperationActor.

  Default marketplace slice is every `:catalog? true` unsold row, every
  closed market. US `vin-*` operator rows stay off this face. Price caps
  compare in JPY via `vehiclesale.border/to-jpy` so mixed currencies do
  not silently compare 28,500 EUR against 1,000,000 JPY."
  (:require [clojure.string :as str]
            [vehiclesale.body :as body]
            [vehiclesale.border :as border]
            [vehiclesale.running-cost :as cost]))

(def prefectures
  "Closed label table for the demo inventory. Not a complete 47-prefecture
  gazetteer — only prefectures that appear in `store/demo-data`."
  {:tokyo "東京都" :osaka "大阪府" :aichi "愛知県"
   :fukuoka "福岡県" :hokkaido "北海道"})

(def countries
  (into {} (map (fn [[k v]] [k (:label v)]) border/markets)))

(def body-types
  {:sedan "セダン" :hatch "ハッチバック" :suv "SUV"
   :minivan "ミニバン" :kei "軽自動車"})

(def fuels
  {:hybrid "ハイブリッド" :gasoline "ガソリン" :diesel "ディーゼル" :ev "電気"})

(def currency-prefix
  {:jpy "¥" :usd "$" :eur "€" :gbp "£" :aud "A$" :aed "AED "
   :nzd "NZ$" :cad "C$" :sgd "S$" :kes "KSh " :tzs "TSh " :clp "CLP " :mnt "₮" :zar "R" :lkr "Rs "})

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

(defn money
  [n currency]
  (when n
    (str (get currency-prefix (or currency :jpy) "")
         (grouped-int n))))

(defn price-jpy
  [veh]
  (border/to-jpy (:price veh) (or (:currency veh) :jpy)))

(defn hydrate
  "Join a vehicle row with its latest odometer and title record. Catalog
  search runs on this projection so mileage/lien are not a second lookup
  at the call site. JP 自動車税 bands attach only to `:jp` rows."
  [veh odo title]
  (cond-> veh
    (and (nil? (:mileage veh)) (:reading odo)) (assoc :mileage (:reading odo))
    title (assoc :lien-status (if (:active? title) :active-lien :clear))
    true (assoc :scan-complete? (body/scan-complete? (:scan veh))
                :body-spec (body/describe veh)
                :price-jpy (price-jpy veh)
                :country-label (get countries
                                    (or (:country veh) (:jurisdiction veh))
                                    (some-> (or (:country veh) (:jurisdiction veh)) name)))
    (= :jp (:jurisdiction veh)) (assoc :running-cost (cost/estimate veh))))

(defn- includes-ci? [hay needle]
  (and (seq needle)
       hay
       (str/includes? (str/lower-case (str hay))
                      (str/lower-case (str needle)))))

(defn search
  "Filter `listings` (hydrated vehicle maps). Unknown / nil criteria are
  ignored. Sold rows (`:listed-status :sold`) are excluded unless
  `:include-sold?` is true. Default inventory is `:catalog? true`."
  [listings {:keys [make prefecture year-min year-max price-max mileage-max
                    body-type q include-sold? jurisdiction country export-dest]
             :or {include-sold? false}}]
  (->> listings
       (filter #(true? (:catalog? %)))
       (filter #(or (nil? jurisdiction) (= jurisdiction (:jurisdiction %))))
       (filter #(or (nil? country) (= country (or (:country %) (:jurisdiction %)))))
       (remove #(and (not include-sold?) (= :sold (:listed-status %))))
       (filter #(or (nil? make) (= make (:make %))))
       (filter #(or (nil? prefecture) (= prefecture (:prefecture %))))
       (filter #(or (nil? body-type) (= body-type (:body-type %))))
       (filter #(or (nil? year-min) (and (:year %) (>= (:year %) year-min))))
       (filter #(or (nil? year-max) (and (:year %) (<= (:year %) year-max))))
       (filter #(or (nil? price-max)
                    (and (price-jpy %) (<= (price-jpy %) (long price-max)))))
       (filter #(or (nil? mileage-max) (and (:mileage %) (<= (:mileage %) mileage-max))))
       (filter #(or (nil? export-dest)
                    (border/eligible? % export-dest)))
       (filter #(or (nil? q) (empty? q)
                    (some (fn [field] (includes-ci? field q))
                          [(:make %) (:model %) (:grade %) (:dealer %)
                           (get prefectures (:prefecture %))
                           (get countries (or (:country %) (:jurisdiction %)))])))
       (sort-by (juxt #(if (= :jp (or (:country %) (:jurisdiction %))) 0 1)
                      (comp - #(or % 0) :year)
                      #(or (price-jpy %) 0)
                      :vin))
       vec))

(defn export-dest-tokens
  "Comma-separated dest codes this listing is eligible to enter.
  Client-side 輸出先 filter reads this so the SPA does not re-implement
  KS 1515 / SEVS."
  [veh]
  (->> (border/jp-demand-dests)
       (filter #(border/eligible? veh %))
       (map name)
       (str/join ",")))

(defn makers [listings]
  (->> listings (map :make) (remove nil?) distinct sort vec))

(defn card
  "Stable card projection for the SPA. Keys are display-ready strings plus
  the identity `:vin` so a view can address `#v/<vin>`."
  [{:keys [vin make model grade year mileage price prefecture body-type
           fuel inspection-expires repair-history? dealer listed-status
           running-cost scan-complete? currency country-label jurisdiction
           demo? owner-id listing-kind]}]
  {:vin vin
   :title (str make " " model (when grade (str " " grade)))
   :year (str year "年")
   :mileage (when mileage (str (grouped-int mileage) " km"))
   :price (money price currency)
   :price-jpy (price-jpy {:price price :currency currency})
   :prefecture (or (get prefectures prefecture)
                   country-label
                   (some-> prefecture name)
                   (some-> jurisdiction name))
   :country country-label
   :body (get body-types body-type (some-> body-type name))
   :fuel (get fuels fuel (some-> fuel name))
   :inspection (or inspection-expires "検査情報なし")
   :repair-history (if repair-history? "修復歴あり" "修復歴なし")
   :dealer (or dealer owner-id)
   :sold? (= :sold listed-status)
   :demo? (boolean demo?)
   :owner-id owner-id
   :listing-kind listing-kind
   :annual-cost (when running-cost (yen (:total-yen running-cost)))
   :scan-complete? (boolean scan-complete?)
   :href (str "#v/" vin)})
