(ns vehiclesale.live
  "Register / session / live marketplace writes.

  Accounts hold a salted SHA-256 of the passphrase, never the passphrase.
  Writes still go VehicleSale-LLM ⊣ VehicleSaleGovernor. This namespace
  does not bypass the governor. Yen does not move (`execute?` stays false)."
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [vehiclesale.body :as body]
            [vehiclesale.operation :as op]
            [vehiclesale.store :as store])
  (:import [java.security MessageDigest SecureRandom]))

(def handle-re #"^[a-z0-9][a-z0-9-]{1,30}$")
(def vin-re #"^[A-Z0-9][A-Z0-9-]{1,20}$")
(def allowed-roles #{:buyer :dealer-agent})

(defn- bytes->hex [^bytes bs]
  (apply str (map #(format "%02x" %) bs)))

(defn sha256-hex
  [s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (bytes->hex (.digest md (.getBytes (str s) "UTF-8")))))

(defn digest-passphrase
  [salt password]
  (str salt "$" (sha256-hex (str salt password))))

(defn verify-passphrase
  [digest password]
  (let [[salt hash] (str/split (str digest) #"\$" 2)]
    (and (seq salt) (seq hash)
         (= hash (sha256-hex (str salt password))))))

(defn- random-hex [n]
  (let [bs (byte-array n)]
    (.nextBytes (SecureRandom.) bs)
    (bytes->hex bs)))

(defn public-account
  [acc]
  (when acc (dissoc acc :pass-digest)))

(defn context-for
  "Session token → actor context at phase 3 with `:channel :live`."
  [st token]
  (when-let [sess (and (seq token) (store/session st token))]
    (when-let [acc (store/account st (:handle sess))]
      {:actor-id (:handle acc)
       :actor-role (:role acc)
       :tenant "tenant-basic"
       :phase 3
       :account-id (:handle acc)
       :channel :live})))

(defn- payout-for [handle]
  {:seller-id handle
   :verified? true
   :rail :authorisation-only
   :account (str "acct_authz_" handle)
   :note "authorisation destination only; this actor does not move yen"})

(defn register!
  [st {:keys [handle password role salt token]}]
  (let [handle (str/lower-case (str/trim (str handle)))
        role (or role :buyer)
        role (if (string? role) (keyword role) role)]
    (cond
      (not (re-matches handle-re handle))
      {:ok false :error :bad-handle}
      (< (count (str password)) 8)
      {:ok false :error :short-password}
      (not (contains? allowed-roles role))
      {:ok false :error :bad-role}
      (store/account st handle)
      {:ok false :error :handle-taken}
      :else
      (let [salt (or salt (random-hex 16))
            tok (or token (random-hex 24))
            acc {:handle handle :role role
                 :pass-digest (digest-passphrase salt password)}]
        (store/commit-record! st {:effect :account-upsert :value acc})
        (store/commit-record! st {:effect :payout-upsert :value (payout-for handle)})
        (store/commit-record! st {:effect :session-upsert
                                  :value {:token tok :handle handle}})
        {:ok true :token tok :handle handle :role role
         :account (public-account acc)}))))

(defn login
  [st {:keys [handle password token]}]
  (let [handle (str/lower-case (str/trim (str handle)))
        acc (store/account st handle)]
    (cond
      (nil? acc) {:ok false :error :unknown-handle}
      (not (verify-passphrase (:pass-digest acc) password))
      {:ok false :error :bad-password}
      :else
      (let [tok (or token (random-hex 24))]
        (store/commit-record! st {:effect :session-upsert
                                  :value {:token tok :handle handle}})
        {:ok true :token tok :handle handle :role (:role acc)
         :account (public-account acc)}))))

(defn logout
  [st token]
  (when (seq token)
    (store/commit-record! st {:effect :session-delete :value {:token token}}))
  {:ok true})

(defn- run-op!
  [st ctx request]
  (let [actor (op/build st)
        tid (or (:thread-id request)
                (str "live-" (System/nanoTime) "-" (name (:op request))))
        res (g/run* actor {:request (dissoc request :thread-id)
                           :context ctx}
                    {:thread-id tid})
        disp (get-in res [:state :disposition])]
    {:ok (= :commit disp)
     :disposition disp
     :status (:status res)
     :ledger-tail (last (store/ledger st))
     :value (get-in res [:state :record :value])}))

(defn inquire!
  [st ctx {:keys [vin body inquiry-id]}]
  (run-op! st ctx {:op :inquiry/submit :subject vin :vin vin
                   :buyer-id (:actor-id ctx) :body body
                   :inquiry-id inquiry-id :channel :live}))

(defn reply!
  [st ctx {:keys [inquiry-id body]}]
  (run-op! st ctx {:op :inquiry/reply :subject inquiry-id
                   :inquiry-id inquiry-id :body body
                   :by (:actor-id ctx) :channel :live}))

(defn list-vehicle!
  [st ctx {:keys [vin make model year price mileage prefecture body-type
                  fuel color grade]}]
  (let [vin (str/upper-case (str/trim (str vin)))
        handle (:actor-id ctx)
        pref (keyword (name (or prefecture :tokyo)))
        body (keyword (name (or body-type :sedan)))
        fuel (keyword (name (or fuel :gasoline)))]
    (cond
      (not (re-matches vin-re vin))
      {:ok false :error :bad-vin :disposition :hold}
      (store/vehicle st vin)
      {:ok false :error :vin-taken :disposition :hold}
      :else
      (run-op! st ctx
               {:op :vehicle/list :subject vin :vin vin
                :make (str make) :model (str model)
                :year (long year) :price (bigdec (long price))
                :mileage (long mileage) :odometer (long mileage)
                :jurisdiction :jp :country :jp :catalog? true
                :currency :jpy :steering :rhd :title-status :clean
                :listed-status :listed :demo? false
                :owner-id handle :listing-kind :private-party
                :private-sale-attested? true
                :prefecture pref :body-type body :fuel fuel
                :grade grade :color (or color "未記入")
                :repair-history? false :inspection-expires "2028-12"
                :scan (body/demo-scan vin)
                :source {:class :owner-attested-private-sale
                         :ref (str "owner:" handle)}
                :channel :live}))))

(defn deal!
  "Open escrow authorisation against a live (non-demo) listing.
  Does not move yen."
  [st ctx {:keys [vin]}]
  (run-op! st ctx {:op :escrow/open :subject vin :vin vin
                   :buyer-id (:actor-id ctx) :channel :live}))
