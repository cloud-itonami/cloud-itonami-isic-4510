(ns vehiclesale.live-test
  (:require [clojure.test :refer [deftest is]]
            [vehiclesale.commerce :as commerce]
            [vehiclesale.live :as live]
            [vehiclesale.store :as store]))

(def seller-reg {:handle "seller-a" :password "password1" :role :buyer
                 :salt "s1" :token "tok-seller"})
(def buyer-reg {:handle "buyer-a" :password "password2" :role :buyer
                :salt "s2" :token "tok-buyer"})

(def user-listing
  {:vin "USER-1" :make "トヨタ" :model "カローラ" :year 2020
   :price 890000 :mileage 41000 :prefecture :tokyo :body-type :sedan
   :fuel :gasoline})

(deftest passphrase-digest-round-trip
  (let [d (live/digest-passphrase "salt" "password1")]
    (is (true? (live/verify-passphrase d "password1")))
    (is (false? (live/verify-passphrase d "password2")))))

(deftest register-login-logout
  (let [st (store/seed-db)
        r (live/register! st seller-reg)]
    (is (true? (:ok r)))
    (is (= "seller-a" (:handle (store/account st "seller-a"))))
    (is (nil? (:pass-digest (live/public-account (store/account st "seller-a")))))
    (is (= "seller-a" (:handle (store/session st "tok-seller"))))
    (is (= :buyer (:actor-role (live/context-for st "tok-seller"))))
    (is (= :live (:channel (live/context-for st "tok-seller"))))
    (is (false? (:ok (live/register! st seller-reg))))
    (let [login (live/login st {:handle "seller-a" :password "password1"
                                :token "tok-seller-2"})]
      (is (true? (:ok login)))
      (is (false? (:ok (live/login st {:handle "seller-a" :password "nope"})))))
    (live/logout st "tok-seller")
    (is (nil? (store/session st "tok-seller")))))

(deftest private-listing-inquiry-reply-and-escrow-authorisation
  (let [st (store/seed-db)
        _ (live/register! st seller-reg)
        _ (live/register! st buyer-reg)
        seller (live/context-for st "tok-seller")
        buyer (live/context-for st "tok-buyer")
        listed (live/list-vehicle! st seller user-listing)]
    (is (true? (:ok listed)) (pr-str listed))
    (is (false? (:demo? (store/vehicle st "USER-1"))))
    (is (= "seller-a" (:owner-id (store/vehicle st "USER-1"))))
    (let [inq (live/inquire! st buyer {:vin "USER-1" :body "現車確認できますか"
                                       :inquiry-id "inq-USER-1-buyer-a-1"})]
      (is (true? (:ok inq)) (pr-str inq))
      (let [rep (live/reply! st seller {:inquiry-id "inq-USER-1-buyer-a-1"
                                        :body "日曜の午後が可能です"})]
        (is (true? (:ok rep)) (pr-str rep))
        (is (= "日曜の午後が可能です"
               (-> (store/inquiry st "inq-USER-1-buyer-a-1") :replies last :body))))
      (let [deal (live/deal! st buyer {:vin "USER-1"})]
        (is (true? (:ok deal)) (pr-str deal))
        (let [esc (store/escrow st "esc-USER-1")]
          (is (= :open (:status esc)))
          (is (true? (commerce/conserved? (:plan esc))))
          (is (false? (:execute? (commerce/stripe-release-instruction
                                  {:escrow-id (:escrow-id esc)
                                   :seller-account "acct_authz_seller-a"
                                   :seller-payout-yen (get-in esc [:plan :plan/seller-payout-yen])
                                   :vin "USER-1"})))))))))

(deftest live-buyer-cannot-buy-demo-stock
  (let [st (store/seed-db)
        _ (live/register! st buyer-reg)
        buyer (live/context-for st "tok-buyer")
        deal (live/deal! st buyer {:vin "JP-100"})]
    (is (false? (:ok deal)))
    (is (= :hold (:disposition deal)))
    (is (some #{:demo-inventory-gate} (:basis (last (store/ledger st)))))))

(deftest stranger-cannot-reply
  (let [st (store/seed-db)
        _ (live/register! st seller-reg)
        _ (live/register! st buyer-reg)
        _ (live/register! st {:handle "stranger" :password "password3"
                              :role :buyer :salt "s3" :token "tok-stranger"})
        seller (live/context-for st "tok-seller")
        buyer (live/context-for st "tok-buyer")
        stranger (live/context-for st "tok-stranger")]
    (is (true? (:ok (live/list-vehicle! st seller user-listing))))
    (is (true? (:ok (live/inquire! st buyer {:vin "USER-1" :body "hi"
                                             :inquiry-id "inq-x"}))))
    (let [rep (live/reply! st stranger {:inquiry-id "inq-x" :body "nope"})]
      (is (false? (:ok rep)))
      (is (some #{:inquiry-party-gate} (:basis (last (store/ledger st))))))))
