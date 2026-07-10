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

## 2. VehicleSaleGovernor(独立検閲層)

`src/vehiclesale/policy.cljc`。8チェック(5 HARD + 3 SOFT)。
`lien-clearance-gate`/`odometer-disclosure-gate` は他の cloud-itonami
actor に存在しないドメイン固有 HARD チェック。`salvage-title-gate` は
`cloud-itonami-isic-6311`の halted-instrument gate の写像。

## 3. R0 の正直なスコープ

`src/vehiclesale/facts.cljc`: NMVTIS(米国DOJ公式無料ゲートウェイ)+
NHTSA recalls(公式無料API)+ 1つの構造的クラス
`:operator-licensed-dmv-feed`(州ごとの権原/リーエンデータは全米統一の
無料ソースが存在しないため、operator が自前の州DMVアクセス権を登録)。

## 4. Phase 0→3

`default-phase=1`(保守的、初期実装時点から)。`:dispute/request` は
どの phase の `:auto` にも入らない。
