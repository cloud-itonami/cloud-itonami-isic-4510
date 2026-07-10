# Security Policy

This project handles vehicle title, lien and odometer provenance data.
Treat vulnerabilities as potentially high impact even when the demo data is
synthetic — a defective title or a rolled-back odometer reaching a buyer
has direct financial and legal consequences.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential or dmv-license-key exposure
- VehicleSaleGovernor bypass (lien-clearance-gate, odometer-disclosure-gate,
  source-provenance-gate)
- audit-ledger tampering
- over-disclosure beyond a subscriber contract's tier
- confirmation of a sale on a title with an unresolved lien
- publication of a salvage/flood-title vehicle sale without human review

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

## Production Guidance

- Store secrets and dmv-license keys outside Git.
- Run governor tests before deployment.
- Export and review audit logs regularly.
- Alert on any lien-clearance-gate or odometer-disclosure-gate HOLD spike —
  it may indicate a compromised or malfunctioning upstream feed.
