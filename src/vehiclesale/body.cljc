(ns vehiclesale.body
  "Vehicle body + camera-scan coverage. Pure. No GPU, no network.

  The marketplace listing shows body dimensions the dealer asserted and
  the camera angles that were actually captured. The governor refuses a
  JP `:vehicle/list` / `:scan/record` whose angle set is short of
  `required-angles`. CIDs in demo data are fictitious labels, not IPFS
  objects — do not GET them."
  (:require [clojure.set :as set]))

(def required-angles
  "Minimum Carsensor-like pack. Engine / underbody / trunk are optional."
  #{:front :rear :left :right :interior-front :dashboard :odometer})

(def angle-labels
  {:front "前方" :rear "後方" :left "左側" :right "右側"
   :interior-front "室内前方" :interior-rear "室内後方"
   :dashboard "ダッシュボード" :odometer "オドメーター"
   :engine "エンジンルーム" :underbody "下回り" :trunk "荷室"})

(def drive-labels
  {:ff "FF" :fr "FR" :4wd "4WD" :mr "MR"})

(defn demo-scan
  "Fictitious CID labels for every required angle. Not retrievable bytes."
  [vin]
  {:angles (into {} (map (fn [a] [a (str "bafkdemo-" vin "-" (name a))])
                         required-angles))
   :captured-at "2026-07-01"
   :operator-id "da-1"
   :fictitious? true})

(defn present-angles [scan]
  (into #{} (keys (:angles scan))))

(defn missing-angles [scan]
  (set/difference required-angles (present-angles scan)))

(defn scan-complete? [scan]
  (empty? (missing-angles scan)))

(defn describe
  "Display projection for the SPA. Missing optional fields stay nil."
  [{:keys [doors seats drive weight-kg length-mm width-mm height-mm]}]
  {:doors doors
   :seats seats
   :drive (get drive-labels drive (some-> drive name))
   :weight (when weight-kg (str weight-kg " kg"))
   :size (when (and length-mm width-mm height-mm)
           (str length-mm "×" width-mm "×" height-mm " mm"))})
