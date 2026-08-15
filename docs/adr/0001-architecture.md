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

### 2. VehicleSaleGovernor は HARD 16 + SOFT（confidence / salvage / dispute / money-rail）

US 成約のドメイン固有 HARD は `lien-clearance-gate` と
`odometer-disclosure-gate`。JP 出品/成約/問合せの HARD は
`kobutsusho-license-gate`、`repair-history-disclosure-gate`、
`inquiry-target-gate`、`scan-coverage-gate`、`shaken-validity-gate`。
マネー面の HARD は `x402-receipt-gate`、`escrow-conservation-gate`、
`payout-destination-gate`、`funds-not-arrived-gate`、
`custody-handover-gate`、`scope-exclusion-gate`。
`salvage-title-gate` はサルベージ/水没/再建権原車両の成約を常に人間承認へ
回す SOFT チェック。

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
