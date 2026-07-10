# Contributing

`cloud-itonami-isic-4510` accepts contributions to the OSS actor, governor
tests, documentation, examples and open business blueprint.

## Development

```bash
clojure -M:dev:test
clojure -M:lint
```

Keep changes small and include tests for governor, audit, store or
disclosure behavior.

## Rules

- Do not commit real VINs, real title/lien records, or real buyer/seller
  identity data.
- Keep production listings and sale confirmations behind
  VehicleSaleGovernor.
- Treat every new check as high-risk: add tests for lien-clearance-gate,
  odometer-disclosure-gate, source-provenance-gate, confidence floor and
  audit logging.
- Never fabricate a source-catalog entry to expand apparent free-source
  coverage — a new state DMV feed is a `dmv-license` record with a real
  operator credential, not a catalog entry.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor invariant is affected
- how it was tested
- whether operator or certification docs need updates
