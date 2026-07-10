# Operator Guide

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-4510
cd cloud-itonami-isic-4510
clojure -M:dev:test
clojure -M:dev:run
```

## 2. Production Checklist

- replace demo vehicles/title-records with real, source-cited data (extend
  `vehiclesale.facts/catalog` honestly for free/official sources — never
  fabricate one — and register real `dmv-license` records for state DMV
  feeds)
- configure Datomic Local or an equivalent durable SSoT
- define subscriber contract tenants/tiers and RBAC rules
- run `clojure -M:dev:test` / `clojure -M:lint`
- verify audit-ledger export
- get written legal review for the jurisdictions you serve (title/lien
  law and odometer-disclosure enforcement vary by state)

## 3. Operator Responsibilities

- lawful basis for each title/lien data source and jurisdiction served
- secure infrastructure and tenant isolation
- honest source-catalog and dmv-license maintenance
- human review workflow for salvage-title and dispute operations
- data-retention policy
