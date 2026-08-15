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
を通る。決済・エスクロー・車両カストディは境界外のまま。

## 2. VehicleSaleGovernor(独立検閲層)

`src/vehiclesale/policy.cljc`。HARD 8 + SOFT 3。
`lien-clearance-gate`/`odometer-disclosure-gate` は US 成約のドメイン固有
HARD。JP は `kobutsusho-license-gate`(出品)と
`repair-history-disclosure-gate`(成約)と `inquiry-target-gate`(問合せ)。
`salvage-title-gate` は `cloud-itonami-isic-6311` の halted-instrument
gate の写像。

## 3. R0 の正直なスコープ

`src/vehiclesale/facts.cljc`: 無料公式ソースは 3 種 — NMVTIS、NHTSA
recalls、国交省リコール・不具合情報。構造的クラスは 2 種 —
`:operator-licensed-dmv-feed`(州 DMV)と
`:operator-licensed-shakensho-feed`(電子車検証 / AIRIS)。デモ在庫の
都道府県は出品行に出る 5 県だけ（47 県gazetteer ではない）。

## 4. Phase 0→3

`default-phase=1`(保守的、初期実装時点から)。`:dispute/request` は
どの phase の `:auto` にも入らない。
