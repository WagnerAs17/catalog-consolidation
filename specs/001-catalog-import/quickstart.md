# Quickstart Validation: Catalog Consolidation Import

This guide validates the implementation described by [plan.md](plan.md). The
expected behavior comes from `docs/spec.md`; do not use this guide to introduce
new behavior.

## Prerequisites

- Java 21
- The repository Maven wrapper
- The `sqlite3` command for the manual schema step
- The committed `data/catalog.db` and `data/products.json` files

## 1. Automated Verification

Run the complete build. Tests create their own temporary file-backed SQLite
databases and must not read either committed data file.

```bash
./mvnw verify
```

The build passes only when domain, persistence, API, rollback, metrics, and all
C1 through C21 tests pass, including the scenario-id consistency test.

## 2. Create a Disposable Working Catalog

Never open or modify `data/catalog.db` through the application. Create the
git-ignored working copy and apply the exact schema script to that copy.

```bash
mkdir -p data/work
cp data/catalog.db data/work/catalog.db
sqlite3 data/work/catalog.db < src/main/resources/db/catalog-schema.sql
```

Apply the schema script only once to a fresh working copy. To start over, delete
`data/work/catalog.db`, copy `data/catalog.db` again, and re-run the script. Never
apply the script a second time to the same working copy.

The application defaults `catalog.db.path` to `./data/work/catalog.db`. Startup
validates the schema and runs the idempotent `NormalizedNameBackfill`. A collision
or leftover NULL must fail startup rather than guess.

## 3. Start the Service

```bash
./mvnw spring-boot:run
```

Only `POST /imports` and the management `health` and `metrics` endpoints are
exposed.

## 4. First Smoke Import

In another terminal, upload the challenge sample:

```bash
curl -sS -F file=@data/products.json http://localhost:8080/imports
```

Expected report totals:

| Field | Expected |
| --- | ---: |
| `records` | 269 |
| `createdAndLinked` | 3 |
| `linkedToExisting` | 265 |
| `alreadyLinked` | 1 |
| `notProcessed` | 0 |

Exactly one result contains the `CATEGORY_DIVERGENCE` warning.

## 5. Idempotency Smoke Import

Run the same upload again:

```bash
curl -sS -F file=@data/products.json http://localhost:8080/imports
```

Expected second report: 269 records, all 269 `ALREADY_LINKED`, with zero new
products and zero new seller links.

## 6. Management Surface

```bash
curl -sS http://localhost:8080/actuator/health
curl -sS http://localhost:8080/actuator/metrics
```

The metrics list includes only the specified catalog instruments in addition to
standard runtime metrics:

- `catalog.import.records`
- `catalog.import.rejections`
- `catalog.import.duration`
- `catalog.import.failures`
- `catalog.import.file.rejections`

No seller name or seller product code appears in metric tags or logs.
