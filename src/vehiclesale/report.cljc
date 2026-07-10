(ns vehiclesale.report
  "Disclosure rendering — output as a GOVERNED read. The column set is not
  chosen here; it is whatever the VehicleSaleGovernor's licensed-disclosure
  gate approved for the caller's contract tier."
  (:require [vehiclesale.store :as store]))

(defn render-listing
  [db vin columns]
  (let [veh (store/vehicle db vin)
        odo (store/odometer-latest db vin)
        title (store/title-record db vin)
        cell (fn [col]
               (case col
                 :vin           vin
                 :make          (:make veh)
                 :model         (:model veh)
                 :year          (:year veh)
                 :title-status  (:title-status veh)
                 :price         (:price veh)
                 :odometer      (:reading odo)
                 :lien-status   (if (:active? title) :active-lien :clear)
                 nil))]
    (into {} (map (juxt identity cell)) columns)))
