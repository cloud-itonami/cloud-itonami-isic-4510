# cloud-itonami-isic-4510

Open Business Blueprint for **ISIC Rev.4 4510**: sale of motor vehicles —
a dealership/marketplace for new and used vehicles.

The **product face** is a used-car search (`docs/index.html`) centred on
Japan stock, then the highest-demand Japan-export destinations (UAE,
Tanzania, Chile/ZOFRI, Kenya, New Zealand, Mongolia, South Africa/RIB). Filter by maker / country / export
destination eligibility / region / body / JPY-equivalent price /
mileage. Open a listing to see KS 1515 age / SEVS / Ley 18.483 / Mongolia /
ITAC eligibility
beside a landed-cost *estimate*. Search is a pure catalog read
(`vehiclesale.catalog`). Writes still go through VehicleSale-LLM ⊣
VehicleSaleGovernor.

Distinct from [`cloud-itonami-isic-4774`](https://github.com/cloud-itonami/cloud-itonami-isic-4774)
(general second-hand goods): vehicles have their own regime. US listings
use title/lien, VIN history and federal odometer disclosure. JP listings
use 古物商許可, 修復歴 disclosure, and an operator-licensed 電子車検証 /
AIRIS credential. A generic resale actor does not model either.

> **Why an actor layer at all?** A VehicleSale-LLM is great at normalizing
> listing data and drafting sale-confirmation proposals — but it has **no
> notion of lien-payoff proof, federal odometer-disclosure law (49 U.S.C.
> Chapter 327), or a subscriber's disclosure entitlement**. Letting it
> confirm a sale directly invites a buyer inheriting an undisclosed lien, a
> rolled-back odometer reading passing through unchecked, or a salvage-
> title vehicle selling without disclosure. This project seals the
> VehicleSale-LLM into a single node and wraps it with an independent
> **VehicleSaleGovernor**, a human **review workflow**, and an immutable
> **audit ledger**.

## Scope (deliberately narrow — read this before anything else)

This actor **lists, discloses, takes inquiries, confirms sale decisions,
records camera-scan / inspection / JP 維持費概算, authorises escrow /
custody / x402 unlocks, and quotes cross-border procedure + landed cost**.
It never executes a transfer (`execute?` stays false). It never files a
customs declaration, never classifies HS (`:adjudicated? false`), and
never invents a missing duty row (`:landed/computable? false`). Compose
with `cloud-itonami-marketplace-crossborder` (same refusal) and
`cloud-itonami-marketplace-settlement`. Do not duplicate settleops or
brokerage (`cloud-itonami-isic-5229`).
Yen vehicle purchase is a Stripe separate-charges-and-transfers *plan*
released only after capture + 納車. Listing info (scan / 車検抜粋 / 維持費)
is x402 `:direct-split` via `nexus-x402`. Custody is a status on the VIN,
not a lot this actor operates. Compose with
`cloud-itonami-marketplace-settlement` — do not duplicate settleops.

## The core contract

```
request + injected role/tenant/phase context
        │
        ▼
   ┌────────────────┐  proposal      ┌─────────────────────────┐
   │ VehicleSale-LLM │ ─────────────▶│ VehicleSaleGovernor      │
   │ (sealed)        │  draft+source │  lien · odometer ·       │
   └────────────────┘                │  provenance · human      │
                                      └─────────────────────────┘
                                              │
                                   commit / publish only if allowed
                                              ▼
                                    append-only audit ledger
```

**Single invariant**: VehicleSale-LLM never lists, confirms a sale, or
resolves a dispute the VehicleSaleGovernor would reject.

## Run

```bash
clojure -M:dev:test
clojure -M:dev:run
clojure -M:dev:render-html   # regenerates docs/index.html + docs/samples/operator-console.html
clojure -M:lint
```

Open `docs/index.html` (fragment SPA: `#search` / `#v/<vin>` / `#operator`).

## Non-Negotiables

- Do not commit real VINs, title/lien records, or buyer/seller identity data.
- Do not set `execute?` true or call a rail from this repo. Authorisation
  records only.
- Do not bypass the VehicleSaleGovernor for production listings or sale
  confirmations.
- Do not confirm a sale on a title with an unresolved, undisclosed lien.
- Do not fabricate a source-catalog entry or a feed-credential record.
- Do not list a JP vehicle without a 古物商許可番号.
- Do not confirm a JP sale without an explicit 修復歴 disclosure.
- Do not list a JP vehicle whose required camera angles are missing.
- Do not confirm a JP sale (or propose escrow release) on expired 車検.
- Do not release escrow without capture and `:handed-over` custody.
- Do not file a customs declaration or treat an HS candidate as adjudicated.
- Do not invent a duty/VAT/freight when the closed market table has no row.
- Do not treat `:zz` as a real country (denied-destination fixture only).
- Do not quote a Kenya import that fails KS 1515's eight-year YoR cap.
- Do not treat ordinary JP passenger cars as Australia-importable (SEVS / RAWS / 25-year only).
- Do not treat ordinary JP passenger cars as Chile-mainland-importable (Ley 18.483). ZOFRI re-export / returning-resident / 50-year historic are the documented exceptions. Do not invent a 5-year age cap.
- Do not put Russia in the closed market table; sanctions lists are operator input.
- Do not invent Mongolia's automobile excise matrix (age × cc tables disagree). Duty 5% + VAT 10% may still quote with that gap.
- Do not treat ordinary JP passenger cars as South Africa-importable (ITAC permit). Durban Removal-in-Bond / returning-resident / 40-year vintage are the documented exceptions. Do not invent an age cap. SARS ATV 10% uplift and ad valorem excise are gaps, not zero.
- Do not treat Thailand as an ordinary used-import destination (used-import ban). Sri Lanka's post-2025 tariff is not invented.

License: AGPL-3.0-or-later.
