# Open Business Blueprint: cloud-itonami-isic-4510

Dealership/marketplace sales platform for new and used motor vehicles.

## Classification

- ISIC Rev.4 4510: Sale of motor vehicles
- Distinct from `cloud-itonami-isic-4774` (general second-hand-goods
  resale): vehicle-specific regulatory regime (title/lien, odometer
  disclosure, salvage/flood flagging)

## Customer

- independent/franchise dealers listing inventory
- private-party sellers (basic tier)
- buyers doing pre-purchase due diligence

## Offer

- title/lien-cleared listing verification
- odometer history with federal disclosure-statement enforcement
- salvage/flood/rebuilt-title flagging, always human-reviewed at sale
- governed, tier-scoped disclosure
- immutable audit ledger

## Revenue

- per-listing or subscription fee (dealer tier)
- wholesale API access to other cloud-itonami blueprint operators
- dmv-feed integration package (per-state)

## Non-Negotiables

- Do not commit real VINs/title/lien/buyer data.
- Do not add a payment/escrow/custody field.
- Do not bypass VehicleSaleGovernor.
- Do not confirm a sale with an unresolved undisclosed lien.
- Do not fabricate a source-catalog entry or dmv-license.
