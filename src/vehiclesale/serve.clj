(ns vehiclesale.serve
  "Local HTTP face for register / inquire / deal authorisation.

  JDK HttpServer — no new dependency. API bodies are EDN or HTML forms.
  Durable file is gitignored. This process still does not move yen."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [vehiclesale.catalog :as catalog]
            [vehiclesale.live :as live]
            [vehiclesale.render-html :as rh]
            [vehiclesale.store :as store])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress URLDecoder]
           [java.nio.charset StandardCharsets]))

(def ^:private utf8 StandardCharsets/UTF_8)

(defn store-path
  []
  (or (System/getenv "ITONAMI_4510_STORE")
      (str (System/getProperty "user.home") "/.itonami/isic-4510/live.edn")))

(defn listen-port
  []
  (Long/parseLong (or (System/getenv "ITONAMI_4510_PORT") "4510")))

(defn load-store
  [path]
  (let [f (io/file path)]
    (if (.isFile f)
      (store/->MemStore (atom (edn/read-string (slurp f))))
      (store/seed-db))))

(defn persist!
  [st path]
  (when (and path (instance? vehiclesale.store.MemStore st))
    (let [f (io/file path)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (pr-str @(:a st))))))

(defn- header [^HttpExchange ex k]
  (.getFirst (.getRequestHeaders ex) k))

(defn- session-token [^HttpExchange ex body]
  (or (some-> (header ex "Authorization") (str/replace #"^Bearer\s+" ""))
      (some-> (header ex "Cookie")
              (->> (re-find #"itonami_4510=([^;]+)") second))
      (:token body)))

(defn- send! [^HttpExchange ex status ctype body]
  (let [bytes (.getBytes (str body) utf8)
        hs (.getResponseHeaders ex)]
    (.set hs "Content-Type" (str ctype "; charset=utf-8"))
    (.sendResponseHeaders ex status (alength bytes))
    (with-open [os (.getResponseBody ex)]
      (.write os bytes))))

(defn- set-session-cookie! [^HttpExchange ex token]
  (when (seq token)
    (.add (.getResponseHeaders ex) "Set-Cookie"
          (str "itonami_4510=" token "; Path=/; SameSite=Lax"))))

(defn- form->map [s]
  (into {}
        (for [pair (str/split (str s) #"&")
              :when (seq pair)
              :let [[k v] (str/split pair #"=" 2)
                    dk (keyword (URLDecoder/decode (or k "") "UTF-8"))
                    dv (URLDecoder/decode (or v "") "UTF-8")]]
          [dk dv])))

(defn- read-body [^HttpExchange ex]
  (let [raw (slurp (.getRequestBody ex) :encoding "UTF-8")
        ctype (str (header ex "Content-Type"))]
    (cond
      (str/blank? raw) {}
      (or (str/includes? ctype "edn") (str/starts-with? (str/trim raw) "{"))
      (edn/read-string raw)
      :else (form->map raw))))

(defn- kw [v]
  (cond (keyword? v) v
        (seq (str v)) (keyword (str v))
        :else nil))

(defn- long* [v]
  (when (and v (not (str/blank? (str v))))
    (Long/parseLong (str v))))

(defn- listings [st]
  (mapv (fn [v]
          (catalog/hydrate v (store/odometer-latest st (:vin v))
                           (store/title-record st (:vin v))))
        (store/all-vehicles st)))

(defn- inbox [st handle]
  (filterv (fn [inq]
             (or (= handle (:buyer-id inq))
                 (= handle (:owner-id (store/vehicle st (:vin inq))))))
           (store/all-inquiries st)))

(defn- require-ctx [st ex body]
  (or (live/context-for st (session-token ex body))
      {:error :auth}))

(defn- handle-api [st persist-path ^HttpExchange ex]
  (let [method (.getRequestMethod ex)
        path (.getPath (.getRequestURI ex))
        body (when (= "POST" method) (read-body ex))
        persist (fn [] (persist! st persist-path))]
    (case [method path]
      ["GET" "/api/health"]
      (send! ex 200 "application/edn" (pr-str {:ok true :face "isic-4510"}))

      ["GET" "/api/listings"]
      (send! ex 200 "application/edn"
             (pr-str {:ok true
                      :listings (mapv catalog/card (catalog/search (listings st) {}))}))

      ["GET" "/api/me"]
      (let [ctx (live/context-for st (session-token ex {}))]
        (if ctx
          (send! ex 200 "application/edn"
                 (pr-str {:ok true
                          :handle (:actor-id ctx)
                          :role (:actor-role ctx)
                          :account (live/public-account
                                    (store/account st (:actor-id ctx)))}))
          (send! ex 200 "application/edn" (pr-str {:ok false :handle nil}))))

      ["GET" "/api/inbox"]
      (let [ctx (live/context-for st (session-token ex {}))]
        (if ctx
          (send! ex 200 "application/edn"
                 (pr-str {:ok true :inquiries (inbox st (:actor-id ctx))}))
          (send! ex 401 "application/edn" (pr-str {:ok false :error :auth}))))

      ["POST" "/api/register"]
      (let [res (live/register! st body)]
        (persist)
        (when (:token res) (set-session-cookie! ex (:token res)))
        (send! ex (if (:ok res) 200 400) "application/edn" (pr-str res)))

      ["POST" "/api/login"]
      (let [res (live/login st body)]
        (persist)
        (when (:token res) (set-session-cookie! ex (:token res)))
        (send! ex (if (:ok res) 200 401) "application/edn" (pr-str res)))

      ["POST" "/api/logout"]
      (do (live/logout st (session-token ex body))
          (persist)
          (send! ex 200 "application/edn" (pr-str {:ok true})))

      ["POST" "/api/inquiry"]
      (let [ctx (require-ctx st ex body)]
        (if (:error ctx)
          (send! ex 401 "application/edn" (pr-str {:ok false :error :auth}))
          (let [res (live/inquire! st ctx body)]
            (persist)
            (send! ex (if (:ok res) 200 400) "application/edn" (pr-str res)))))

      ["POST" "/api/reply"]
      (let [ctx (require-ctx st ex body)]
        (if (:error ctx)
          (send! ex 401 "application/edn" (pr-str {:ok false :error :auth}))
          (let [res (live/reply! st ctx body)]
            (persist)
            (send! ex (if (:ok res) 200 400) "application/edn" (pr-str res)))))

      ["POST" "/api/list"]
      (let [ctx (require-ctx st ex body)]
        (if (:error ctx)
          (send! ex 401 "application/edn" (pr-str {:ok false :error :auth}))
          (let [payload (cond-> body
                          (:year body) (update :year long*)
                          (:price body) (update :price long*)
                          (:mileage body) (update :mileage long*)
                          (:prefecture body) (update :prefecture kw)
                          (:body-type body) (update :body-type kw)
                          (:fuel body) (update :fuel kw))
                res (live/list-vehicle! st ctx payload)]
            (persist)
            (send! ex (if (:ok res) 200 400) "application/edn" (pr-str res)))))

      ["POST" "/api/deal"]
      (let [ctx (require-ctx st ex body)]
        (if (:error ctx)
          (send! ex 401 "application/edn" (pr-str {:ok false :error :auth}))
          (let [res (live/deal! st ctx body)]
            (persist)
            (send! ex (if (:ok res) 200 400) "application/edn" (pr-str res)))))

      (send! ex 404 "application/edn" (pr-str {:ok false :error :not-found})))))

(defn- handler [st persist-path]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (let [path (.getPath (.getRequestURI ex))]
          (if (str/starts-with? path "/api/")
            (handle-api st persist-path ex)
            (send! ex 200 "text/html" (rh/render st))))
        (catch Exception e
          (send! ex 500 "text/plain" (str "error: " (.getMessage e))))
        (finally
          (.close ex))))))

(defn start!
  ([] (start! {}))
  ([{:keys [port store-path] :or {port (listen-port) store-path (store-path)}}]
   (let [st (load-store store-path)
         server (HttpServer/create (InetSocketAddress. (int port)) 0)]
     (.createContext server "/" (handler st store-path))
     (.setExecutor server nil)
     (.start server)
     {:server server :store st :port port :store-path store-path})))

(defn -main [& _]
  (let [{:keys [port store-path]} (start!)]
    (println "isic-4510 live face on http://127.0.0.1:" port)
    (println "store" store-path)
    (println "demo stock is not for sale; execute? stays false")
    @(promise)))
