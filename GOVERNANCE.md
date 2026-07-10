# Governance

`cloud-itonami-isic-4510` is an OSS open-business blueprint. Governance
covers both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- VehicleSale-LLM cannot directly list, confirm a sale, or resolve a
  dispute.
- VehicleSaleGovernor remains independent of the advisor.
- hard governor violations (lien-clearance-gate, odometer-disclosure-gate,
  source-provenance-gate) cannot be overridden by human approval.
- a dispute/request never auto-resolves, at any rollout phase.
- a salvage/flood/rebuilt-title sale confirmation always reaches a human.
- every commit, hold and disclosure event is auditable.
- no schema field exists for payment processing, escrow or funds transfer.
- real VIN/title/lien/buyer data stays outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and
data-flow review.
