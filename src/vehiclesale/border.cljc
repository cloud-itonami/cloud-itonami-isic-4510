(ns vehiclesale.border
  "Vehicle cross-border procedure + landed-cost *authorisation*. Pure.

  Compose with `cloud-itonami-marketplace-crossborder`: this namespace
  never files a customs declaration, never classifies (HS is a
  candidate stamped `:adjudicated? false`), and never adjudicates a
  dispute. Duty/VAT rows are operator fixtures with `:rate/source` and
  `:rate/as-of`. A missing row is `:landed/computable? false`, not a
  guess — a understated quote is worse than an honest refusal.

  Japan-hub first. Destination coverage follows observed JP used-vehicle
  export demand, not GDP. Closed demo markets — not a 195-country
  gazetteer. An ISO 3166 alpha-2 outside `markets` is refused so we
  cannot invent a duty. `:zz` is the ISO user-assigned code used as the
  denied-destination fixture (not a real country). Russia is omitted
  from the closed table: a sanctions list is operator input.")

(def as-of
  "Clock of the fixture table. Same discipline as running-cost."
  "2026-08")

(def clock-year
  "Calendar year of `as-of`. Age caps are year-granular (KEBS YoR)."
  2026)

(def rate-source "test-fixture")

(def markets
  "ISO 3166 alpha-2 → profile. Labels are Japanese for the SPA.
  `:dealer-license-required?` is the local trade-licence analogue of
  古物商. `:inspection-required-on-sale?` means a current periodic
  inspection date is required to confirm a *domestic* sale.
  `:used-import` is the used-vehicle *regime* (allowed / age-capped /
  SEVS-RAWS restricted). Missing duty is independent of regime."
  {:jp {:iso :jp :label "日本" :currency :jpy :steering :rhd
        :dealer-license-required? true :inspection-required-on-sale? true
        :inspection-name "車検" :export-doc "輸出抹消登録"
        :homologation "国交省型式指定 / 輸入車特別取扱"
        :used-import :domestic}
   :us {:iso :us :label "アメリカ合衆国" :currency :usd :steering :lhd
        :dealer-license-required? false :inspection-required-on-sale? false
        :inspection-name "state inspection" :export-doc "title / export title"
        :homologation "EPA / DOT / NHTSA"
        :used-import :allowed}
   :de {:iso :de :label "ドイツ" :currency :eur :steering :lhd
        :dealer-license-required? true :inspection-required-on-sale? true
        :inspection-name "TÜV/HU" :export-doc "Exportbescheinigung"
        :homologation "EU type-approval / COC"
        :used-import :allowed}
   :gb {:iso :gb :label "イギリス" :currency :gbp :steering :rhd
        :dealer-license-required? true :inspection-required-on-sale? true
        :inspection-name "MOT" :export-doc "export certificate of origin"
        :homologation "IVA / type approval"
        :used-import :allowed}
   :au {:iso :au :label "オーストラリア" :currency :aud :steering :rhd
        :dealer-license-required? true :inspection-required-on-sale? false
        :inspection-name "state roadworthy" :export-doc "export approval"
        :homologation "SEVS / RAWS"
        :used-import :restricted-sevs-raws
        :used-import-source "DITRDCA used import: SEVS, RAWS, or 25-year vehicle"
        :vintage-years 25}
   :ae {:iso :ae :label "アラブ首長国連邦" :currency :aed :steering :rhd
        :dealer-license-required? true :inspection-required-on-sale? false
        :inspection-name "RTA test" :export-doc "export declaration"
        :homologation "ESMA / GSO"
        :used-import :allowed
        :re-export-hub? true}
   :nz {:iso :nz :label "ニュージーランド" :currency :nzd :steering :rhd
        :dealer-license-required? true :inspection-required-on-sale? false
        :inspection-name "WoF" :export-doc "export entry"
        :homologation "NZTA entry certification / MPI biosecurity"
        :used-import :allowed
        :psi-label "MPI biosecurity + entry certification"}
   :ca {:iso :ca :label "カナダ" :currency :cad :steering :lhd
        :dealer-license-required? false :inspection-required-on-sale? false
        :inspection-name "provincial safety" :export-doc "export title"
        :homologation "Registrar of Imported Vehicles"
        :used-import :allowed}
   :fr {:iso :fr :label "フランス" :currency :eur :steering :lhd
        :dealer-license-required? true :inspection-required-on-sale? true
        :inspection-name "contrôle technique" :export-doc "certificat de dédouanement"
        :homologation "réception UE / RTI"
        :used-import :allowed}
   :sg {:iso :sg :label "シンガポール" :currency :sgd :steering :rhd
        :dealer-license-required? true :inspection-required-on-sale? false
        :inspection-name "LTA inspection" :export-doc "export permit"
        :homologation "LTA VES / ARF"
        :used-import :allowed}
   :ke {:iso :ke :label "ケニア" :currency :kes :steering :rhd
        :dealer-license-required? true :inspection-required-on-sale? false
        :inspection-name "KEBS / NTSA" :export-doc "export certificate / logbook"
        :homologation "KS 1515 / KEBS"
        :used-import :allowed-with-age-cap
        :max-age-years 8
        :age-basis :first-registration-year
        :age-source "KS 1515:2000 / KEBS importer notice (YoR, rolling calendar)"
        :psi-label "QISJ Certificate of Roadworthiness"}
   :tz {:iso :tz :label "タンザニア" :currency :tzs :steering :rhd
        :dealer-license-required? true :inspection-required-on-sale? false
        :inspection-name "TBS / TRA" :export-doc "export certificate / logbook"
        :homologation "TBS PVoC"
        :used-import :allowed
        :age-basis :unverified
        :age-note "Dealer blogs describe an 8-year excise penalty, not a sourced TBS ban. Age is not invented as HARD."
        :psi-label "TBS PVoC Certificate of Roadworthiness"}
   :cl {:iso :cl :label "チリ" :currency :clp :steering :lhd
        :dealer-license-required? true :inspection-required-on-sale? false
        :inspection-name "revisión técnica" :export-doc "export certificate / logbook"
        :homologation "MTT homologación / revisión técnica"
        :used-import :restricted-mainland-used
        :used-import-source "Ley 18.483 Art. 21: used passenger import into mainland Chile is prohibited. Documented exceptions: ZOFRI re-export, Aduana partida 00.33 returning resident, historic 50+ years."
        :vintage-years 50
        :opposite-steering :reexport-or-waiver
        :re-export-hub? true
        :steering-note "LHD country. JP RHD volume is ZOFRI re-export (Iquique), not mainland road use. A 5-year age cap is dealer-blog only and is not invented as HARD."}
   :mn {:iso :mn :label "モンゴル" :currency :mnt :steering :lhd
        :dealer-license-required? true :inspection-required-on-sale? false
        :inspection-name "NATC / technical inspection"
        :export-doc "export certificate / logbook"
        :homologation "Mongolia Customs / NATC"
        :used-import :allowed
        :age-basis :unverified
        :age-note "No sourced age ban. Dealer blogs describe higher excise on older cars, not a HARD cap."
        :opposite-steering :import-license
        :steering-note "LHD country. RHD Japanese imports are the volume path; a Mongolian import licence is a procedure label, not a silent steering HARD."
        :psi-label "radiation check (Japan-origin)"}
   :za {:iso :za :label "南アフリカ" :currency :zar :steering :rhd
        :dealer-license-required? true :inspection-required-on-sale? false
        :inspection-name "roadworthy / eNaTIS"
        :export-doc "export certificate / logbook"
        :homologation "ITAC import permit / NRCS Letter of Authority"
        :used-import :restricted-itac-used
        :used-import-source "gov.za + ITAC used/second-hand vehicle guidelines: used import is permit-only. Documented exceptions: returning resident / immigrant PR, vintage 40+, racing / inherited / specially designed. Commercial resale is not a path. JUMV volume is treated as Durban Removal-in-Bond transit, not mainland used-import."
        :age-basis :unverified
        :vintage-years 40
        :re-export-hub? true
        :steering-note "RHD country. JP steering matches. Do not invent an age cap; ITAC is permit-category, not year-of-registration."
        :psi-label "NRCS Letter of Authority"}})

(def jp-export-demand
  "Japan-origin used-vehicle export demand, calendar 2025. Secondary
  compilation (JUMV), not a live 財務省 extract. `:in-table?` is whether
  this actor will even attempt a quote. Russia is ranked but omitted
  from `markets` because a sanctions list is operator input. Sri Lanka
  / Thailand stay omitted until a sourced used-import regime + duty
  row exists. South Africa is in-table as ITAC fail-closed + RIB transit."
  {:as-of "2025"
   :source "JUMV used-vehicle export ranking (compilation of Japan export counts)"
   :source-url "https://jumv.net/basic_knowledge_usedcar_export/export_statistics/statistics?year_from=2025"
   :total-units 1714279
   :rows [{:rank 1 :iso :ae :units 253897 :in-table? true
           :note "再輸出ハブ（国内需要だけではない）"}
          {:rank 2 :iso :ru :units 186583 :in-table? false
           :omit-reason :sanctions-operator-input
           :note "制裁リストは operator 入力。閉じた市場に載せない。"}
          {:rank 3 :iso :tz :units 119036 :in-table? true
           :note "関税は未掲載（捏造しない）。TBS PVoC は手続ラベル。"}
          {:rank 4 :iso :cl :units 83523 :in-table? true
           :note "本土の中古乗用車輸入は Ley 18.483 で禁止。数量の本体は ZOFRI（イキケ）再輸出。関税 6%+IVA 19% は例外が認めたときだけ。"}
          {:rank 5 :iso :ke :units 77286 :in-table? true
           :note "KS 1515 の 8年（初度登録年）。QISJ CoR。関税は未掲載。"}
          {:rank 6 :iso :nz :units 71633 :in-table? true
           :note "関税 0% + GST 15%。NZTA entry certification / MPI。"}
          {:rank 7 :iso :mn :units 66469 :in-table? true
           :note "年式上限は無い。関税 5%+VAT 10%。物品税（排気量×年式）は帯が割れるので欠落。"}
          {:rank 8 :iso :za :units 64555 :in-table? true
           :note "ITAC の中古輸入は許可制（本土での転売は不可）。数量の本体はダーバン RIB 通過。関税 25%+VAT 15% は例外が認めたときだけ。ATV 10% 上乗せと従価物品税は欠落。"}
          {:rank 9 :iso :lk :units 62335 :in-table? false
           :omit-reason :duty-schedule-deferred
           :note "2025-02 に輸入再開。付加税が重く、税率表をこのスライスでは載せない。"}
          {:rank 10 :iso :th :units 48927 :in-table? false
           :omit-reason :used-import-banned
           :note "商務省通知で中古乗用車の国内向け輸入は原則禁止。再輸出・保税の可能性。"}]})

(def denied-destinations
  "ISO user-assigned `:zz`. A real sanctions list is an operator input
  and is not shipped as a guess."
  #{:zz})

(def jpy-per-unit
  "JPY per 1 unit of listing currency. Fixture, not a live FX feed.
  KES/TZS are omitted: East-Africa landed cost stays uncomputable.
  CLP/MNT are sub-1 so `to-jpy`/`from-jpy` use double, not integer quot."
  {:jpy 1 :usd 150 :eur 165 :gbp 190 :aud 100 :aed 41 :nzd 90 :cad 110 :sgd 112
   :clp 0.16 :mnt 0.044 :zar 8})

(def duty-bps-by-dest
  "MFN passenger-car (HS 8703) duty in basis points. `nil` = no row.
  KE/TZ have no row — TRA/KRA schedules are not invented.
  CL 600 is the general 6% arancel when an exception admits the vehicle;
  ZOFRI re-export zeroes Chilean duty in `landed-cost`, not in this table.
  MN 500 is the 5% customs duty; automobile excise (age × cc) is a documented
  gap — dealer/IAM/old-law tables disagree, so it is not invented.
  ZA 2500 is the general 25% HS 8703 rate when an exception admits the
  vehicle; RIB transit zeroes South African duty in `landed-cost`, not
  in this table. Ad valorem excise is a documented gap."
  {:jp 0 :us 250 :de 1000 :gb 1000 :au 500 :ae 500 :nz 0 :ca 620 :fr 1000
   :sg nil :ke nil :tz nil :cl 600 :mn 500 :za 2500})

(def vat-bps-by-dest
  "GST/VAT/消費税. US federal has none; state sales tax is a documented
  gap, not zeroed silently — `:us` vat-bps 0 with `:vat-gap :state-tax`.
  `nil` = uncomputable (Singapore ARF/OMV; Kenya/Tanzania schedules)."
  {:jp 1000 :us 0 :de 1900 :gb 2000 :au 1000 :ae 500 :nz 1500 :ca 500 :fr 2000
   :sg nil :ke nil :tz nil :cl 1900 :mn 1000 :za 1500})

(def freight-dest-minor
  "Ocean RoRo assumption, in *destination* whole currency units.
  Missing corridor → uncomputable freight. KE/TZ freight is present so
  a quote fails on duty/VAT nil, not on a missing corridor."
  {[:jp :au] 1800 [:jp :nz] 1600 [:jp :gb] 2200 [:jp :ae] 1400
   [:jp :us] 2500 [:jp :de] 2300 [:jp :ca] 2600 [:jp :fr] 2300 [:jp :sg] 1600
   [:jp :ke] 1800 [:jp :tz] 1900 [:jp :cl] 2000000 [:jp :mn] 7000000
   [:jp :za] 35000
   [:de :us] 1800 [:de :jp] 240000 [:de :gb] 900 [:de :fr] 400
   [:us :ca] 800 [:us :de] 1500 [:us :jp] 280000 [:us :gb] 1600
   [:gb :jp] 250000 [:gb :de] 700 [:gb :au] 2100
   [:au :jp] 220000 [:ae :jp] 180000 [:fr :de] 350 [:ca :us] 700
   [:nz :jp] 210000})

(defn market [iso]
  (get markets iso))

(defn known-market? [iso]
  (contains? markets iso))

(defn denied-dest? [iso]
  (contains? denied-destinations iso))

(defn cross-border? [origin dest]
  (and origin dest (not= origin dest)))

(defn to-jpy
  "CLP is sub-1 JPY/unit, so the multiply cannot go through integer quot."
  [amount currency]
  (when amount
    (let [rate (get jpy-per-unit (or currency :jpy))]
      (when rate (long (* (double amount) (double rate)))))))

(defn from-jpy
  [jpy-amount dest-currency]
  (let [rate (get jpy-per-unit dest-currency)]
    (when (and jpy-amount rate (pos? (double rate)))
      (long (/ (double jpy-amount) (double rate))))))

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

(defn opposite-steering-policy
  "Default `:incompatible`. Chile's JP volume is ZOFRI re-export of RHD
  (`:reexport-or-waiver`). Mongolia accepts RHD with an import licence
  (`:import-license`) — not a silent LHD HARD on the actual corridor."
  [dest]
  (or (:opposite-steering (market dest)) :incompatible))

(defn steering-ok?
  [veh origin dest]
  (or (not (cross-border? origin dest))
      (compatible-steering? origin dest)
      (true? (:steering-waiver? veh))
      (= :import-license (opposite-steering-policy dest))
      (and (= :reexport-or-waiver (opposite-steering-policy dest))
           (true? (:zofri-reexport? veh)))))

(defn first-registration-year
  "KS 1515 uses year of first registration. Demo rows that omit it fall
  back to model year — a documented approximation, not a logbook."
  [veh]
  (or (:first-registration-year veh) (:year veh)))

(defn min-first-registration-year
  "KEBS rolling calendar: 2024 notice allowed YoR 2017+, 2025 2018+.
  min-yor = clock-year - (max-age-years - 1)."
  [dest]
  (let [m (market dest)]
    (when (and (= :first-registration-year (:age-basis m))
               (:max-age-years m))
      (- clock-year (dec (:max-age-years m))))))

(defn age-ok?
  [veh dest]
  (let [min-y (min-first-registration-year dest)
        yor (first-registration-year veh)]
    (or (nil? min-y) (and yor (>= yor min-y)))))

(defn vintage-unrestricted?
  "AU 25-year / CL 50-year / ZA 40-year collector paths. Not a SEVS listing."
  [veh dest]
  (let [n (:vintage-years (market dest))]
    (boolean (and n (:year veh) (<= (:year veh) (- clock-year n))))))

(defn regime-ok?
  [veh dest]
  (let [regime (or (:used-import (market dest)) :allowed)]
    (case regime
      (:allowed :allowed-with-age-cap :domestic) true
      :restricted-sevs-raws
      (or (true? (:sevs-eligible? veh))
          (true? (:raws? veh))
          (vintage-unrestricted? veh dest))
      :restricted-mainland-used
      (or (true? (:zofri-reexport? veh))
          (true? (:returning-resident? veh))
          (vintage-unrestricted? veh dest))
      :restricted-itac-used
      (or (true? (:rib-transit? veh))
          (true? (:returning-resident? veh))
          (vintage-unrestricted? veh dest))
      false)))

(defn eligibility
  "Import *eligibility*, independent of duty arithmetic. A 2016 JP car
  is not Kenya-eligible even though we also refuse to invent a KRA
  tariff. `:ok? false` is a HARD hold, not a warning."
  [veh dest]
  (cond
    (denied-dest? dest)
    {:ok? false :reason :denied-destination :dest dest}
    (not (known-market? dest))
    {:ok? false :reason :unknown-destination :dest dest}
    (not (known-market? (origin-of veh)))
    {:ok? false :reason :unknown-origin :origin (origin-of veh)}
    (not (cross-border? (origin-of veh) dest))
    {:ok? true :reason :domestic :dest dest}
    (not (age-ok? veh dest))
    {:ok? false :reason :age-ineligible :dest dest
     :yor (first-registration-year veh)
     :min-yor (min-first-registration-year dest)
     :source (:age-source (market dest))}
    (not (regime-ok? veh dest))
    {:ok? false :reason :used-import-restricted :dest dest
     :regime (:used-import (market dest))
     :source (:used-import-source (market dest))}
    (and (cross-border? (origin-of veh) dest)
         (not (steering-ok? veh (origin-of veh) dest)))
    {:ok? false :reason :steering-incompatible
     :origin (origin-of veh) :dest dest}
    :else
    {:ok? true :reason :eligible :dest dest}))

(defn eligible?
  [veh dest]
  (true? (:ok? (eligibility veh dest))))

(defn jp-demand-dests
  "Closed-table destinations from the Japan-hub demand ranking."
  []
  (->> (:rows jp-export-demand)
       (filter :in-table?)
       (map :iso)
       vec))

(defn procedure
  "Ordered checklist. Each step is a label, not a filing."
  [origin dest]
  (if-not (cross-border? origin dest)
    [{:id :domestic :label "国内取引（輸出入なし）" :required? false}]
    (let [o (market origin)
          d (market dest)]
      (cond-> [{:id :title-clear :label "輸出国の権原・担保の確認" :required? true}
               {:id :export-certificate :label (str "輸出証明（" (:export-doc o) "）") :required? true}
               {:id :transport :label "輸送手配（RoRo / コンテナ）" :required? true}
               {:id :hs-candidate :label "HS 8703 候補（未確定）" :required? true}
               {:id :duty-vat-quote :label "関税・付加価値税の概算（国境が正）" :required? true}
               {:id :import-permit :label (str "輸入側の許可・適合（" (:homologation d) "）") :required? true}]
        (:psi-label d)
        (conj {:id :pre-shipment-inspection :label (str "船前検査（" (:psi-label d) "）") :required? true})
        (:max-age-years d)
        (conj {:id :age-cap :label (str "年式上限 " (:max-age-years d) "年（"
                                       (name (or (:age-basis d) :n-a)) "）")
               :required? true})
        (= :restricted-sevs-raws (:used-import d))
        (conj {:id :sevs-raws :label "中古輸入は SEVS / RAWS / 25年車に限る" :required? true})
        (= :restricted-mainland-used (:used-import d))
        (conj {:id :ley-18483
               :label "本土の中古乗用車輸入は Ley 18.483 で禁止。ZOFRI再輸出 / 帰国者 00.33 / 50年歴史車が例外。"
               :required? true})
        (= :restricted-itac-used (:used-import d))
        (conj {:id :itac-permit
               :label "中古輸入は ITAC 許可制。帰国者・永住移民 / 40年ヴィンテージ / 競技・相続等が例外。商業転売は不可。ダーバン RIB は通過。"
               :required? true})
        true
        (conj {:id :steering :label (str "ハンドル位置 " (name (or (:steering o) :n-a))
                                        " → " (name (or (:steering d) :n-a)))
               :required? (and (not (compatible-steering? origin dest))
                               (not= :import-license (opposite-steering-policy dest)))}
              {:id :destination-registration :label (str "登録地の検査（" (:inspection-name d) "）")
               :required? true})))))

(defn landed-cost
  "Returns a quote map. Never guesses a missing rate or corridor.
  Totals are dest-currency whole units. `execute?` is not a key here
  — this is arithmetic, not a rail. Eligibility is *not* folded in:
  a SEVS-ineligible AU quote can still show the duty arithmetic next
  to a HARD regime hold."
  [veh dest]
  (let [origin (origin-of veh)
        hs (hs-candidate veh)
        dest-ccy (get-in markets [dest :currency])
        origin-ccy (listing-currency veh)
        zofri? (and (= dest :cl) (true? (:zofri-reexport? veh)))
        rib? (and (= dest :za) (true? (:rib-transit? veh)))
        transit-zero? (or zofri? rib?)
        duty-bps (if transit-zero? 0 (get duty-bps-by-dest dest ::missing))
        vat-bps (if transit-zero? 0 (get vat-bps-by-dest dest ::missing))
        freight (if (cross-border? origin dest)
                  (get freight-dest-minor [origin dest] ::missing)
                  0)
        price-jpy (to-jpy (:price veh) origin-ccy)
        cif-dest (when (and price-jpy dest-ccy)
                   (let [converted (from-jpy price-jpy dest-ccy)]
                     (when (and converted (number? freight))
                       (+ converted freight))))
        missing (cond
                  (denied-dest? dest) :denied-destination
                  (not (known-market? origin)) :unknown-origin
                  (not (known-market? dest)) :unknown-destination
                  (nil? dest-ccy) :unknown-destination
                  (nil? price-jpy) :unknown-fx
                  (not (number? freight)) :missing-freight-corridor
                  (nil? cif-dest) :unknown-fx
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
         :landed/vat-gap (cond transit-zero? :onward-destination-duty
                               (= dest :us) :state-tax
                               (= dest :za) :atv-uplift)
         :landed/duty-gap (cond (and (= dest :cl) (not zofri?)) :luxury-displacement
                                (= dest :mn) :excise-age-engine
                                (and (= dest :za) (not rib?)) :ad-valorem-excise)
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

(defn corridor-board
  "Japan-hub demand rows joined with this vehicle's eligibility +
  landed-cost. Used by the SPA so a Kenya age fail is visible even
  when duty is also uncomputable."
  [veh]
  (mapv (fn [{:keys [rank iso units note in-table? omit-reason]}]
          (let [el (when in-table? (eligibility veh iso))
                landed (when in-table? (landed-cost veh iso))]
            {:rank rank
             :iso iso
             :label (or (:label (market iso)) (name iso))
             :units units
             :note note
             :in-table? in-table?
             :omit-reason omit-reason
             :eligible? (boolean (:ok? el))
             :eligibility-reason (:reason el)
             :landed-computable? (boolean (:landed/computable? landed))
             :landed-missing (:landed/missing landed)}))
        (:rows jp-export-demand)))
