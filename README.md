# cloud-itonami-isic-4510

Open Business Blueprint for **ISIC Rev.4 4510**: sale of motor vehicles —
a dealership/marketplace sales platform for new and used vehicles,
published as an OSS business that any qualified operator can fork, deploy,
run, improve and sell.

Distinct from [`cloud-itonami-isic-4774`](https://github.com/cloud-itonami/cloud-itonami-isic-4774)'s
general second-hand-goods resale marketplace: vehicle sales carry their own
regulatory regime — title/lien verification, VIN history and odometer
disclosure, salvage/flood-title flagging — that a generic resale actor
does not model. Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph runtime.

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

This actor **lists, discloses and confirms sale decisions**. It never
processes payment, never holds escrow, never takes custody of the vehicle —
there is no field anywhere in this schema for payment processing (see
`docs/adr/0001-architecture.md`). Provenance is limited to real, citable
public reference sources (`src/vehiclesale/facts.cljc`: the official DOJ
NMVTIS one-report-per-VIN gateway, NHTSA recalls) or an operator-registered
`:operator-licensed-dmv-feed` for state-by-state title/lien data.

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
clojure -M:lint
```

## Non-Negotiables

- Do not commit real VINs, title/lien records, or buyer/seller identity data.
- Do not add a schema field for payment processing, escrow or funds transfer.
- Do not bypass the VehicleSaleGovernor for production listings or sale
  confirmations.
- Do not confirm a sale on a title with an unresolved, undisclosed lien.
- Do not fabricate a source-catalog entry or a dmv-license record.

License: AGPL-3.0-or-later.
