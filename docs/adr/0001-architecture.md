# ADR-0001: cloud-itonami-isic-4510 — VehicleSale-LLM を封じ込めた知能ノードとする自動車販売アクター設計

- Status: Accepted (2026-07-10)
- 関連: `cloud-itonami-isic-6311`(MarketData-LLM を MarketDataGovernor で
  封じ込める構図の直接の手本)、`cloud-itonami-isic-4774`(同セッションの
  二次流通マーケットプレイス actor、業態差の対比対象)

## 課題

「成熟度を高めて」というオーナー指示のもと、`kotoba-lang/industry`
registry の未着手 `:spec` スロットから ISIC Rev.4 4510「Sale of motor
vehicles」を選定した。`cloud-itonami-isic-4774`(中古品全般のリセール
マーケットプレイス)とは異なり、自動車販売は権原(タイトル)・リーエン・
走行距離開示という車両固有の実定法上の制約を持つため、独立した業態として
実装する。

## 決定

### 1. VehicleSale-LLM は最下層の1ノードに封じ込め、直接成約させない

> **VehicleSale-LLM は、VehicleSaleGovernor が拒否する出品確定・成約確定・
> 紛争解決を決して行わない。**

### 2. VehicleSaleGovernor は HARD 25 + SOFT（confidence / salvage / dispute / money-rail / filings-adjacent）

US 成約のオドメーター開示は `:us` のみ。国境は `vehiclesale.border`
（`cloud-itonami-marketplace-crossborder` と同じ禁止: 通関申告しない、
HS を確定しない、無い税率を捏造しない）。公開検索は閉じたデモ市場の
`:catalog? true` 行。`:zz` は ISO user-assigned の denied-destination
fixture であって実在国ではない。

公開検索面は `vehiclesale.catalog`（純関数）。書き込みは引き続き
OperationActor。車両代金は Stripe separate charges + transfers の認可
（`execute? false`）。情報面は x402 `:direct-split`（`nexus-x402`、鍵なし）。
カストディは VIN の状態。送金の実行は
`cloud-itonami-marketplace-settlement`。

### 3. R0 の正直なスコープ

出典カタログ(`src/vehiclesale/facts.cljc`)は実在する3つの無料公式ソース
(NMVTIS、NHTSA recalls、国交省リコール・不具合情報)+ 2つの構造的クラス
`:operator-licensed-dmv-feed`(州 DMV)と
`:operator-licensed-shakensho-feed`(電子車検証 / AIRIS)。

### 4. Robotics premise: false

デジタルの認可サービス。実車のロット運営はしない。カストディは状態遷移の
記録。決済レールの実行はこの actor の外（`execute?` は常に false）。

## Consequences

- (+) `kotoba-lang/industry` registry の 4510 スロットが実装へ昇格。
- (+) `clojure -M:dev:test`/`clojure -M:lint` で検証済み。
- (-) 州ごとの権原/リーエンと JP 電子車検証は operator の credential
  登録が必須。無料公式ソースは履歴・リコール面まで。

## References

- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`
