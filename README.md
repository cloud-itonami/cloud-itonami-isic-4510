# cloud-itonami-isic-4510

Open Business Blueprint for **ISIC Rev.4 4510**: sale of motor vehicles —
a dealership/marketplace for new and used vehicles.

The **product face** is a Carsensor-like used-car search (`docs/index.html`):
filter by maker / prefecture / body / price / mileage, open a listing,
send a dealer inquiry. Search is a pure catalog read
(`vehiclesale.catalog`). Writes — list, confirm, inquire — still go through
VehicleSale-LLM ⊣ VehicleSaleGovernor.

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

This actor **lists, discloses, takes inquiries, and confirms sale
decisions**. It never processes payment, never holds escrow, never takes
custody of the vehicle — there is no field anywhere in this schema for
payment processing (see `docs/adr/0001-architecture.md`). Provenance is
limited to real, citable public sources (`src/vehiclesale/facts.cljc`:
NMVTIS, NHTSA recalls, 国交省リコール・不具合情報) or an operator-registered
feed (`:operator-licensed-dmv-feed` / `:operator-licensed-shakensho-feed`).

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
- Do not add a schema field for payment processing, escrow or funds transfer.
- Do not bypass the VehicleSaleGovernor for production listings or sale
  confirmations.
- Do not confirm a sale on a title with an unresolved, undisclosed lien.
- Do not fabricate a source-catalog entry or a feed-credential record.
- Do not list a JP vehicle without a 古物商許可番号.
- Do not confirm a JP sale without an explicit 修復歴 disclosure.

License: AGPL-3.0-or-later.
