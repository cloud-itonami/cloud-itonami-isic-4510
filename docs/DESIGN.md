# Vehicle Sale Actor Design — VehicleSale-LLM as a contained intelligence node

Dealership/marketplace 型の中古/新車販売を、`cloud-itonami-isic-6311`
(MarketData-LLM を MarketDataGovernor で封じ込めた構図)を車両売買ドメインへ
写像して実装する。

## 1. なぜ actor 層が要るのか

出品データの正規化・成約提案は LLM で加速できるが、LLM は次の理由で
**取込・成約確定の最終権限を持てない**:

| LLM が起こしうる失敗 | 帰結 |
|---|---|
| アクティブなリーエンを見落として成約 | 買主が他人の負債を継承 |
| 走行距離のロールバックをそのまま通す | 連邦法(49 U.S.C. Chapter 327)違反 |
| サルベージ権原を高確信のまま自動成約 | 未開示の重大瑕疵車両の流通 |
| 古物商許可なしで JP 出品する | 古物営業法違反 |
| 修復歴を開示せず JP 成約する | 買主が重大瑕疵を知らない |

公開の検索面は `vehiclesale.catalog` の純関数。書き込みは OperationActor
を通る。車両代金は Stripe 分離課金の *認可*（hold → 納車後に解放指示、
`execute? false`）。情報面は x402。カストディは VIN 上の状態。送金の実行は
`cloud-itonami-marketplace-settlement` / レール。

## 2. VehicleSaleGovernor(独立検閲層)

`src/vehiclesale/policy.cljc`。HARD 27 + SOFT（confidence / salvage /
dispute / money-rail / filings-adjacent）。US 成約のオドメーター開示は
`:us` のみ（49 U.S.C. を他国に適用しない）。JP は
`kobutsusho-license-gate`。免許が必要な非 JP 市場は
`dealer-license-gate`。国境は `unknown-market-gate`、
`denied-destination-gate`（`:zz` fixture）、
`steering-incompatible-gate`、`export-certificate-gate`、
`import-permit-gate`、`landed-uncomputable-gate`、
`tariff-conservation-gate`、`hs-adjudication-gate`、
`import-age-gate`（KS 1515 初度登録年）、
`import-regime-gate`（AU SEVS/RAWS）。
`vehiclesale.border` は `cloud-itonami-marketplace-crossborder` と同じ
禁止（申告しない / 分類しない / 裁定しない / 無い税率を捏造しない）。
日本ハブの需要順は 2025 年の二次集計。ロシアは市場表に載せない。

## 3. R0 の正直なスコープ

`src/vehiclesale/facts.cljc`: 無料公式ソースは 3 種 — NMVTIS、NHTSA
recalls、国交省リコール・不具合情報。構造的クラスは US DMV / JP AIRIS /
EU type-approval / UK DVLA / AU PPSR / UAE RTA。関税率は
`vehiclesale.border` の test-fixture（`as-of 2026-08`）。閉じた市場であり
195 か国gazetteer ではない。日本ハブの需要順（UAE / TZ / KE / NZ）から
厚くする。ケニア・タンザニアの関税は無いので uncomputable。シンガポールは
ARF/OMV が無いので uncomputable。オーストラリアの中古輸入は SEVS/RAWS/25年。
US VAT は連邦 0 + `:vat-gap :state-tax`。

## 4. Phase 0→3

`default-phase=1`(保守的、初期実装時点から)。`:dispute/request`、
マネー操作、`:export/certify` / `:import/permit` はどの phase の
`:auto` にも入らない。`:border/quote` は phase 3 で governor-clean なら
auto。
