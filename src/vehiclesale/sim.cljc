(ns vehiclesale.sim
  "Demo runner: push representative operations through one OperationActor
  and watch the VehicleSaleGovernor + approval workflow earn the
  VehicleSale-LLM the right to list, confirm a sale, or resolve a dispute.

    op1  出典あり・ロールバック無しの出品             → commit
    op2  出典なし出品(フィード欠落)                  → source-provenance REJECT → hold
    op3  アクティブなリーエンを未解消のまま成約         → lien-clearance REJECT → hold
    op3b リーエン解消済み+開示証明ありの成約           → commit
    op4  開示クエリが tier/basic 契約なのに odometer/lien-status を要求 → hold
    op4a 開示クエリが未契約 tenant から                → hold
    op5  サルベージ権原車両の成約(他は正常)           → 人間承認へ escalate → approve → commit
    op6  紛争申立て(どの phase でも常に人間レビュー)   → escalate → approve → commit
    op7  走行距離のロールバック出品                    → odometer-disclosure REJECT → hold
    op8  適用除外車両(モデル年20年超)の成約、開示証明なし → commit(除外ロジック確認)

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [vehiclesale.store :as store]
            [vehiclesale.operation :as op]
            [vehiclesale.facts :as facts]
            [vehiclesale.report :as report]))

(defn- line [& xs] (println (apply str xs)))

(defn- run-op! [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  人間レビュー待ち (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "officer-1"}}
                             {:thread-id thread-id :resume? true})]
            (line "   ▶  " (if approve? "承認 → " "却下 → ") "disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   → disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [db    (store/seed-db)
        actor (op/build db)
        agent    {:actor-id "da-1" :actor-role :dealer-agent :phase 3}
        officer  {:actor-id "to-1" :actor-role :title-officer :phase 3}]

    (line "── R0 出典カバレッジ(正直な現状) ──")
    (line (pr-str (facts/coverage)))

    (line "\n── OperationActor (VehicleSale-LLM sealed; VehicleSaleGovernor active) ──")

    (line "\nop1  出典あり・ロールバック無しの出品")
    (run-op! actor "op1"
             {:op :vehicle/list :subject "vin-100" :vin "vin-100" :make "Demo" :model "Sedan"
              :year 2022 :title-status :clean :price 18500.00M :odometer 33500 :state :ca
              :source {:class :federal-title-registry :ref "nmvtis-doj-gateway:vin-100"}}
             agent true)

    (line "\nop2  出典なし出品(フィード欠落)")
    (run-op! actor "op2"
             {:op :vehicle/list :subject "vin-100" :vin "vin-100" :make "Demo" :model "Sedan"
              :year 2022 :title-status :clean :price 18500.00M :odometer 34000 :state :ca
              :source {:class :federal-title-registry :ref "demo"} :unsourced? true}
             agent true)

    (line "\nop3  アクティブなリーエンを未解消のまま成約")
    (run-op! actor "op3"
             {:op :sale/confirm :subject "vin-200" :vin "vin-200" :lien-cleared? false
              :odometer-disclosure-statement? true}
             agent true)

    (line "\nop3b リーエン解消済み+開示証明ありの成約")
    (run-op! actor "op3b"
             {:op :sale/confirm :subject "vin-200" :vin "vin-200" :lien-cleared? true
              :odometer-disclosure-statement? true}
             agent true)

    (line "\nop4  開示クエリ(tier/basic 契約なのに odometer/lien-status まで要求)")
    (run-op! actor "op4"
             {:op :disclosure/query :subject "vin-100" :vin "vin-100" :greedy? true}
             {:actor-id "b-1" :actor-role :buyer :tenant "tenant-basic"} true)

    (line "\nop4a 開示クエリ(登録されていない tenant から)")
    (run-op! actor "op4a"
             {:op :disclosure/query :subject "vin-100" :vin "vin-100"}
             {:actor-id "b-2" :actor-role :buyer :tenant "tenant-ghost"} true)

    (line "\nop5  サルベージ権原車両の成約(他は正常でも人間承認)")
    (run-op! actor "op5"
             {:op :sale/confirm :subject "vin-300" :vin "vin-300" :lien-cleared? true
              :odometer-disclosure-statement? true}
             agent true)

    (line "\nop6  紛争申立て(どの phase でも常に人間レビュー)")
    (run-op! actor "op6"
             {:op :dispute/request :subject "vin-100" :disputed-field :title-status :claim :clean}
             officer true)

    (line "\nop7  走行距離のロールバック出品")
    (run-op! actor "op7"
             {:op :vehicle/list :subject "vin-300" :vin "vin-300" :make "Demo" :model "Hatchback"
              :year 2019 :title-status :salvage :price 6200.00M :odometer 50000 :state :ca
              :source {:class :federal-title-registry :ref "nmvtis-doj-gateway:vin-300"}}
             agent true)

    (line "\nop8  適用除外車両(モデル年20年超)の成約、開示証明なし")
    (run-op! actor "op8"
             {:op :sale/confirm :subject "vin-400" :vin "vin-400" :lien-cleared? true
              :odometer-disclosure-statement? false}
             agent true)

    (line "\n── 開示(governor が承認した tier/basic 列のみ) ──")
    (line (pr-str (report/render-listing db "vin-100" [:vin :make :model :price])))

    (line "\n── 監査台帳 (append-only) ──")
    (doseq [f (store/ledger db)]
      (line "  " (store/ledger-line f)))

    (line "\ndone.")))
