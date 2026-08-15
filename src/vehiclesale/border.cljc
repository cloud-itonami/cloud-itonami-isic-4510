(ns vehiclesale.border
  "Vehicle cross-border procedure + landed-cost *authorisation*. Pure.

  Compose with `cloud-itonami-marketplace-crossborder`: this namespace
  never files a customs declaration, never classifies (HS is a
  candidate stamped `:adjudicated? false`), and never adjudicates a
  dispute. Duty/VAT rows are operator fixtures with `:rate/source` and
  `:rate/as-of`. A missing row is `:landed/computable? false`, not a
  guess — a understated quote is worse than an honest refusal.

  Closed demo markets. Not a 195-country gazetteer. An ISO 3166
  alpha-2 outside `markets` is refused so we cannot invent a duty.
  `:zz` is the ISO user-assigned code used as the denied-destination
  fixture (not a real country)."
  )

(def as-of
  "Clock of the fixture table. Same discipline as running-cost."
  "2026-08")

(def rate-source "test-fixture")

(def markets
  "ISO 3166 alpha-2 → profile. Labels are Japanese for the SPA.
  `:dealer-license-required?` is the local trade-licence analogue of
  古物商. `:inspection-required-on-sale?` means a current periodic
  inspection date is required to confirm a *domestic* sale."
  {:jp {:iso :jp :label "日本" :currency :jpy :steering :rhd
        :dealer-license-required? true :inspection-required-on-sale? true
        :inspection-name "車検" :export-doc "輸出抹消登録"
        :homologation "国交省型式指定 / 輸入車特別取扱"}
   :us {:iso :us :label "アメリカ合衆国" :currency :usd :steering :lhd
        :dealer-license-required? false :inspection-required-on-sale? false
        :inspection-name "state inspection" :export-doc "title / export title"
        :homologation "EPA / DOT / NHTSA"}
   :de {:iso :de :label "ドイツ" :currency :eur :steering :lhd
        :dealer-license-required? true :inspection-required-on-sale? true
        :inspection-name "TÜV/HU" :export-doc "Exportbescheinigung"
        :homologation "EU type-approval / COC"}
   :gb {:iso :gb :label "イギリス" :currency :gbp :steering :rhd
        :dealer-license-required? true :inspection-required-on-sale? true
        :inspection-name "MOT" :export-doc "export certificate of origin"
        :homologation "IVA / type approval"}
   :au {:iso :au :label "オーストラリア" :currency :aud :steering :rhd
        :dealer-license-required? true :inspection-required-on-sale? false
        :inspection-name "state roadworthy" :export-doc "export approval"
        :homologation "SEVS / RAWS"}
   :ae {:iso :ae :label "アラブ首長国連邦" :currency :aed :steering :rhd
        :dealer-license-required? true :inspection-required-on-sale? false
        :inspection-name "RTA test" :export-doc "export declaration"
        :homologation "ESMA / GSO"}
   :nz {:iso :nz :label "ニュージーランド" :currency :nzd :steering :rhd
        :dealer-license-required? false :inspection-required-on-sale? false
        :inspection-name "WoF" :export-doc "export entry"
        :homologation "entry certification"}
   :ca {:iso :ca :label "カナダ" :currency :cad :steering :lhd
        :dealer-license-required? false :inspection-required-on-sale? false
        :inspection-name "provincial safety" :export-doc "export title"
        :homologation "Registrar of Imported Vehicles"}
   :fr {:iso :fr :label "フランス" :currency :eur :steering :lhd
        :dealer-license-required? true :inspection-required-on-sale? true
        :inspection-name "contrôle technique" :export-doc "certificat de dédouanement"
        :homologation "réception UE / RTI"}
   :sg {:iso :sg :label "シンガポール" :currency :sgd :steering :rhd
        :dealer-license-required? true :inspection-required-on-sale? false
        :inspection-name "LTA inspection" :export-doc "export permit"
        :homologation "LTA VES / ARF"}})

(def denied-destinations
  "ISO user-assigned `:zz`. A real sanctions list is an operator input
  and is not shipped as a guess."
  #{:zz})

(def jpy-per-unit
  "JPY per 1 unit of listing currency. Fixture, not a live FX feed."
  {:jpy 1 :usd 150 :eur 165 :gbp 190 :aud 100 :aed 41 :nzd 90 :cad 110 :sgd 112})

(def duty-bps-by-dest
  "MFN passenger-car (HS 8703) duty in basis points. `nil` = no row."
  {:jp 0 :us 250 :de 1000 :gb 1000 :au 500 :ae 500 :nz 0 :ca 620 :fr 1000
   :sg nil})

(def vat-bps-by-dest
  "GST/VAT/消費税. US federal has none; state sales tax is a documented
  gap, not zeroed silently — `:us` vat-bps 0 with `:vat-gap :state-tax`.
  `nil` = uncomputable (Singapore ARF/OMV)."
  {:jp 1000 :us 0 :de 1900 :gb 2000 :au 1000 :ae 500 :nz 1500 :ca 500 :fr 2000
   :sg nil})

(def freight-dest-minor
  "Ocean RoRo assumption, in *destination* whole currency units.
  Missing corridor → uncomputable freight."
   {[:jp :au] 1800 [:jp :nz] 1600 [:jp :gb] 2200 [:jp :ae] 1400
    [:jp :us] 2500 [:jp :de] 2300 [:jp :ca] 2600 [:jp :fr] 2300 [:jp :sg] 1600
   [:de :us] 1800 [:de :jp] 240000 [:de :gb] 900 [:de :fr] 400
   [:us :ca] 800 [:us :de] 1500 [:us :jp] 280000 [:us :gb] 1600
   [:gb :jp] 250000 [:gb :de] 700 [:gb :au] 2100
   [:au :jp] 220000 [:ae :jp] 180000 [:fr :de] 350 [:ca :us] 700})

(defn market [iso]
  (get markets iso))

(defn known-market? [iso]
  (contains? markets iso))

(defn denied-dest? [iso]
  (contains? denied-destinations iso))

(defn cross-border? [origin dest]
  (and origin dest (not= origin dest)))

(defn to-jpy
  [amount currency]
  (when amount
    (let [rate (get jpy-per-unit (or currency :jpy))]
      (when rate (long (* (long amount) rate))))))

(defn from-jpy
  [jpy-amount dest-currency]
  (let [rate (get jpy-per-unit dest-currency)]
    (when (and jpy-amount rate (pos? rate))
      (quot (long jpy-amount) rate))))

(defn hs-candidate
  "Displacement/fuel → HS 8703 six-digit *candidate*. Never adjudicated."
  [{:keys [fuel displacement-cc]}]
  (let [cc (or displacement-cc 0)
        code (cond
               (= fuel :ev) "870380"
               (= fuel :diesel)
               (cond (<= cc 1500) "870331"
                     (<= cc 2500) "870332"
                     :else "870333")
               :else
               (cond (<= cc 1000) "870321"
                     (<= cc 1500) "870322"
                     (<= cc 3000) "870323"
                     :else "870324"))]
    {:hs code
     :heading "8703"
     :adjudicated? false
     :basis :displacement-band
     :proposer "vehiclesale.border/hs-candidate"
     :note "候補。通関分類の確定ではない。cloud-itonami-marketplace-crossborder と同じ禁止。"}))

(defn- listing-currency [veh]
  (or (:currency veh)
      (get-in markets [(:jurisdiction veh) :currency])
      :jpy))

(defn origin-of [veh]
  (or (:country veh) (:jurisdiction veh)))

(defn compatible-steering?
  [origin dest]
  (let [a (:steering (market origin))
        b (:steering (market dest))]
    (or (nil? a) (nil? b) (= a b))))

(defn procedure
  "Ordered checklist. Each step is a label, not a filing."
  [origin dest]
  (if-not (cross-border? origin dest)
    [{:id :domestic :label "国内取引（輸出入なし）" :required? false}]
    (let [o (market origin)
          d (market dest)]
      [{:id :title-clear :label "輸出国の権原・担保の確認" :required? true}
       {:id :export-certificate :label (str "輸出証明（" (:export-doc o) "）") :required? true}
       {:id :transport :label "輸送手配（RoRo / コンテナ）" :required? true}
       {:id :hs-candidate :label "HS 8703 候補（未確定）" :required? true}
       {:id :duty-vat-quote :label "関税・付加価値税の概算（国境が正）" :required? true}
       {:id :import-permit :label (str "輸入側の許可・適合（" (:homologation d) "）") :required? true}
       {:id :steering :label (str "ハンドル位置 " (name (or (:steering o) :n-a))
                                  " → " (name (or (:steering d) :n-a)))
        :required? (not (compatible-steering? origin dest))}
       {:id :destination-registration :label (str "登録地の検査（" (:inspection-name d) "）")
        :required? true}])))

(defn landed-cost
  "Returns a quote map. Never guesses a missing rate or corridor.
  Totals are dest-currency whole units. `execute?` is not a key here
  — this is arithmetic, not a rail."
  [veh dest]
  (let [origin (origin-of veh)
        hs (hs-candidate veh)
        dest-ccy (get-in markets [dest :currency])
        origin-ccy (listing-currency veh)
        duty-bps (get duty-bps-by-dest dest ::missing)
        vat-bps (get vat-bps-by-dest dest ::missing)
        freight (if (cross-border? origin dest)
                  (get freight-dest-minor [origin dest] ::missing)
                  0)
        price-jpy (to-jpy (:price veh) origin-ccy)
        cif-dest (when (and price-jpy dest-ccy)
                   (+ (from-jpy price-jpy dest-ccy) (if (number? freight) freight 0)))
        missing (cond
                  (denied-dest? dest) :denied-destination
                  (not (known-market? origin)) :unknown-origin
                  (not (known-market? dest)) :unknown-destination
                  (nil? dest-ccy) :unknown-destination
                  (nil? price-jpy) :unknown-fx
                  (not (number? freight)) :missing-freight-corridor
                  (= duty-bps ::missing) :missing-duty-row
                  (nil? duty-bps) :duty-uncomputable
                  (= vat-bps ::missing) :missing-vat-row
                  (nil? vat-bps) :vat-uncomputable
                  :else nil)]
    (if missing
      {:landed/computable? false
       :landed/missing missing
       :landed/origin origin
       :landed/dest dest
       :landed/hs (:hs hs)
       :landed/estimate? true
       :landed/rate-source rate-source
       :landed/rate-as-of as-of
       :hs hs}
      (let [duty (quot (* cif-dest duty-bps) 10000)
            vat (quot (* (+ cif-dest duty) vat-bps) 10000)
            total (+ cif-dest duty vat)]
        {:landed/computable? true
         :landed/origin origin
         :landed/dest dest
         :landed/currency dest-ccy
         :landed/hs (:hs hs)
         :landed/customs-value-minor cif-dest
         :landed/freight-minor (if (cross-border? origin dest) freight 0)
         :landed/duty-bps duty-bps
         :landed/duty-minor duty
         :landed/vat-bps vat-bps
         :landed/vat-minor vat
         :landed/vat-base :duty-inclusive
         :landed/vat-gap (when (= dest :us) :state-tax)
         :landed/total-minor total
         :landed/conserved? (= total (+ cif-dest duty vat))
         :landed/estimate? true
         :landed/rate-source rate-source
         :landed/rate-as-of as-of
         :landed/note "概算。国境の賦課が正。通関申告ではない。"
         :hs hs}))))

(defn conserved-quote? [q]
  (boolean (and q (:landed/computable? q) (:landed/conserved? q)
                (= (long (:landed/total-minor q))
                   (+ (long (:landed/customs-value-minor q))
                      (long (:landed/duty-minor q))
                      (long (:landed/vat-minor q)))))))

(defn quote-errors
  "Governor re-derives. An advisor cannot manufacture a rate."
  [veh dest quote]
  (cond
    (denied-dest? dest) [:denied-destination]
    (not (known-market? dest)) [:unknown-destination]
    (not (known-market? (origin-of veh))) [:unknown-origin]
    :else
    (let [fresh (landed-cost veh dest)]
      (cond
        (true? (get-in quote [:hs :adjudicated?])) [:self-adjudicated-hs]
        (not (:landed/computable? fresh)) [:uncomputable]
        (nil? quote) []
        (not= (:landed/total-minor quote) (:landed/total-minor fresh)) [:rate-manufactured]
        (not (conserved-quote? fresh)) [:not-conserved]
        :else []))))

(defn quotes-from
  "Every computable dest from this vehicle's origin, for the SPA."
  [veh]
  (->> (keys markets)
       (map (fn [d] (landed-cost veh d)))
       (filter :landed/computable?)
       (sort-by :landed/dest)
       vec))
