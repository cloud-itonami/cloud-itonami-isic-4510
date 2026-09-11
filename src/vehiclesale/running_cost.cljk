(ns vehiclesale.running-cost
  "Annual running-cost *estimate* for a JP passenger car.

  Brackets follow the publicly tabulated 自動車税種別割 (普通自動車・自家用)
  bands. 重量税 / 自賠責 / 任意保険 / 燃料 / 車検償却 are labelled
  assumptions, not quotes. This is not an insurer, a tax office, or a
  dealer invoice.")

(def as-of-year-month
  "Clock of the demo. Not `(java.time)` — the actor must stay deterministic."
  "2026-08")

(def assumed-annual-km 10000)
(def assumed-gasoline-yen-per-l 170)
(def assumed-diesel-yen-per-l 160)
(def assumed-voluntary-insurance-yen 80000)
(def assumed-shaken-cycle-yen 120000)
(def compulsory-24mo-yen 17650)

(defn automobile-tax-yen
  "地方税法の自家用乗用車 標準税率帯（概算）。軽自動車は別表なので nil。"
  [cc]
  (when cc
    (cond
      (<= cc 1000) 25000
      (<= cc 1500) 30500
      (<= cc 2000) 36000
      (<= cc 2500) 43500
      (<= cc 3000) 50000
      (<= cc 3500) 57000
      (<= cc 4000) 66500
      (<= cc 4500) 76500
      (<= cc 6000) 88000
      :else 110000)))

(defn weight-tax-annual-yen
  "車検2年あたりの重量税を年割り。0.5t 刻み 8,200 円帯の概算。"
  [weight-kg]
  (when weight-kg
    (let [half-tons (quot (+ (long weight-kg) 499) 500)]
      (long (/ (* half-tons 8200 12) 24)))))

(defn fuel-annual-yen
  [{:keys [fuel fuel-economy-km-l]}]
  (when (and fuel-economy-km-l (pos? fuel-economy-km-l))
    (let [litres (/ (double assumed-annual-km) (double fuel-economy-km-l))
          yen-per-l (case fuel
                      :diesel assumed-diesel-yen-per-l
                      :ev 0
                      assumed-gasoline-yen-per-l)]
      (long (* litres yen-per-l)))))

(defn shaken-annual-yen []
  (long (/ assumed-shaken-cycle-yen 2)))

(defn compulsory-annual-yen []
  (long (/ compulsory-24mo-yen 2)))

(defn estimate
  "Returns a map of yen integers plus `:assumption` notes. Missing
  displacement yields a partial estimate (tax nil, total still sums what
  is known)."
  [{:keys [displacement-cc weight-kg] :as veh}]
  (let [tax (automobile-tax-yen displacement-cc)
        weight (weight-tax-annual-yen weight-kg)
        fuel (fuel-annual-yen veh)
        shaken (shaken-annual-yen)
        jibaiseki (compulsory-annual-yen)
        voluntary assumed-voluntary-insurance-yen
        parts (remove nil? [tax weight fuel shaken jibaiseki voluntary])
        total (reduce + 0 parts)]
    {:as-of as-of-year-month
     :annual-km assumed-annual-km
     :automobile-tax-yen tax
     :weight-tax-yen weight
     :fuel-yen fuel
     :shaken-yen shaken
     :compulsory-insurance-yen jibaiseki
     :voluntary-insurance-yen voluntary
     :total-yen total
     :assumption (str "概算。自動車税は排気量帯、その他は "
                      assumed-annual-km " km/年・燃料単価仮定・"
                      "任意保険 " voluntary " 円固定。見積ではない。")}))
