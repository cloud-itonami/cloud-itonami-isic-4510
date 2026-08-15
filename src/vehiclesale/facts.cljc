(ns vehiclesale.facts
  "R0 source-basis catalog — the ONLY provenance classes the
  VehicleSaleGovernor will accept as a citation for a title/lien/odometer
  fact (mirrors `cloud-itonami-isic-6311`'s `marketdata.facts` discipline:
  honesty over coverage). Two kinds of entry:

    1. Real, free, official reference sources — genuinely citable today.
    2. `:operator-licensed-dmv-feed` — the *structural* class for
       state-by-state DMV title/lien records. There is no single free,
       uniform, nationwide (US) source for live lien-holder status — each
       state DMV is its own system. This actor does not (and cannot) claim
       one; an operator must register a real per-state DMV data-access
       credential as a `dmv-license` record before a listing citing this
       class is accepted (same operator-supplies-own-feed boundary as
       `cloud-itonami-isic-6311`/`kotoba-lang/securities`).

  Adding coverage means adding a real, citable catalog entry (kind 1) or a
  real registered dmv-license (kind 2) — never fabricating either.")

(def catalog
  "Each entry: {:id :name :class :access :url}. `:class` is the value that
  must appear in a listing's `:source :class` for the source-provenance-gate
  to accept it as grounded (for `:operator-licensed-dmv-feed`, grounding
  also requires an active `dmv-license` scoped to the listing's state,
  checked separately)."
  [{:id :nmvtis-doj-gateway
    :name "NMVTIS official one-report-per-VIN gateway (U.S. DOJ, vehiclehistory.gov)"
    :class :federal-title-registry
    :access :public-free-per-vin
    :url "https://vehiclehistory.bja.ojp.gov/"}
   {:id :nhtsa-recalls
    :name "NHTSA vehicle recalls (National Highway Traffic Safety Administration)"
    :class :federal-recall-registry
    :access :public-api
    :url "https://www.nhtsa.gov/recalls"}
   {:id :mlit-recall
    :name "国土交通省 自動車のリコール・不具合情報"
    :class :mlit-recall-registry
    :access :public-free
    :url "https://www.mlit.go.jp/jidosha/carinf/rcl/index.html"}
   {:id :operator-licensed-dmv-feed
    :name "Operator-registered state DMV title/lien data-access credential"
    :class :operator-licensed-dmv-feed
    :access :operator-licensed
    :url nil}
   {:id :operator-licensed-shakensho-feed
    :name "Operator-registered 電子車検証 / AIRIS credential (JP title equivalent)"
    :class :operator-licensed-shakensho-feed
    :access :operator-licensed
    :url "https://www.denshishakensho-portal.mlit.go.jp/"}
   {:id :operator-licensed-eu-type-feed
    :name "Operator-registered EU type-approval / COC credential"
    :class :operator-licensed-eu-type-feed
    :access :operator-licensed
    :url nil}
   {:id :operator-licensed-dvla-feed
    :name "Operator-registered UK DVLA / MOT credential"
    :class :operator-licensed-dvla-feed
    :access :operator-licensed
    :url nil}
   {:id :operator-licensed-ppsr-feed
    :name "Operator-registered Australia PPSR / state dealer credential"
    :class :operator-licensed-ppsr-feed
    :access :operator-licensed
    :url nil}
   {:id :operator-licensed-uae-rta-feed
    :name "Operator-registered UAE RTA / GSO credential"
    :class :operator-licensed-uae-rta-feed
    :access :operator-licensed
    :url nil}
   {:id :operator-licensed-nzta-feed
    :name "Operator-registered NZTA entry-certification / WoF credential"
    :class :operator-licensed-nzta-feed
    :access :operator-licensed
    :url "https://nzta.govt.nz/vehicles/importing-a-vehicle/2-complying-with-vehicle-standards-and-providing-evidence/used-vehicles-from-japan"}
   {:id :operator-licensed-kebs-feed
    :name "Operator-registered KEBS / QISJ Certificate of Roadworthiness credential"
    :class :operator-licensed-kebs-feed
    :access :operator-licensed
    :url "https://www.kebs.org/"}])

(def allowed-source-classes
  "Closed set — a class not in `catalog` (e.g. :inference, :seller-assertion,
  :scraped) must be rejected, not silently accepted because it looks like a
  keyword."
  (into #{} (map :class catalog)))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers — never
  overstate ('全米の権原/リーエン情報' in prose, 2 free federal sources + 1
  structural per-state licensed class in fact)."
  []
  {:source-count (count catalog)
   :free-public-sources (into #{} (map :id (filter #(not= :operator-licensed (:access %)) catalog)))
   :note (str "R0 scope: NMVTIS one-report-per-VIN (US federal, free) + NHTSA "
              "recalls (US federal, free) + MLIT リコール・不具合情報 (JP, free) "
              "+ structural operator-licensed feed classes (US DMV, JP AIRIS, "
              "EU type-approval, UK DVLA, AU PPSR, UAE RTA, NZTA, KEBS). Not a live "
              "tariff database — duty rows live in vehiclesale.border fixtures. "
              "Japan-hub demand ranking is a dated compilation, not a live 財務省 "
              "extract. Extend only by appending a real, citable catalog entry or a "
              "real registered feed credential — never fabricate either.")})

(defn class-allowed? [source-class]
  (contains? allowed-source-classes source-class))

(defn licensed-dmv-class? [source-class]
  (contains? #{:operator-licensed-dmv-feed :operator-licensed-shakensho-feed
               :operator-licensed-eu-type-feed :operator-licensed-dvla-feed
               :operator-licensed-ppsr-feed :operator-licensed-uae-rta-feed
               :operator-licensed-nzta-feed :operator-licensed-kebs-feed}
             source-class))

(def odometer-exempt-model-year-age
  "U.S. federal Truth in Mileage Act / 49 CFR Part 580: vehicles of model
  year 20+ years before the current model year, and vehicles with a GVWR
  over 16,000 lbs, are exempt from the odometer-disclosure-statement
  requirement. This actor only implements the age exemption (R0 — GVWR
  exemption is a documented gap, not silently ignored; see docs/DESIGN.md)."
  20)
