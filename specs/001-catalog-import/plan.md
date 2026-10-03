# Implementation Plan: Catalog Consolidation Import

**Branch**: `001-catalog-import` | **Date**: 2026-10-02 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification at `specs/001-catalog-import/spec.md`, with
`docs/spec.md` as the authoritative source and `AGENTS.md` as the architecture
and convention guide.

## Summary

Build the synchronous `POST /imports` flow as a Java 21 Spring Boot service.
`ImportFileParser` reads the multipart upload as a `JsonNode` tree and
`ImportRecordMapper` converts every array element into a framework-neutral
`ImportRecord` that retains each raw value's JSON type but contains no Jackson
type. `ImportProductsUseCase` receives
the complete ordered list, including invalid elements and their 1-based
positions; the domain owns BR-01 validation, `WRONG_FIELD_TYPE`, and
`INVALID_RECORD` results. The application processes the records within one
immediate write transaction. Slice 0 is one persistence decision gate: T003 runs
the Hibernate dialect and mapping spike; only a documented dialect or mapping
incompatibility permits T004 to replace it with the Spring JDBC spike. A failure
of `foreign_keys`, WAL, `busy_timeout`, or IMMEDIATE transactions blocks both
paths and stops the work. T004 records the selected path and ends the gate with a
green `./mvnw verify`. Existing catalog data is never enriched, inconclusive
records write nothing, and system failures roll back the entire file.

## Technical Context

**Language/Version**: Java 21

**Build**: Maven wrapper; `./mvnw verify` is the completion gate for every slice

**Framework**: Spring Boot 3.5.x, using the latest 3.5.x patch confirmed from
start.spring.io or Maven Central when Slice 0 begins, with Spring MVC

**Primary Dependencies**: `spring-boot-starter-web`,
`spring-boot-starter-data-jpa`, `spring-boot-starter-actuator`,
`org.xerial:sqlite-jdbc`, and
`org.hibernate.orm:hibernate-community-dialects`; test scope uses only
`spring-boot-starter-test`. Spring Boot dependency management keeps Hibernate
core and community dialect versions aligned. If and only if the Hibernate
dialect or mapping is incompatible, replace the JPA starter with
`spring-boot-starter-jdbc`; a required SQLite-setting failure blocks both paths.

**Storage**: File-backed SQLite. The runtime path comes from `catalog.db.path`
with default `./data/work/catalog.db`. On the Hibernate path, Hibernate validates
the schema and never creates or migrates it. The Spring JDBC fallback uses
`JdbcProductRepositoryAdapter` and `JdbcSellerProductRepositoryAdapter` against
the same exact schema without entities. The exact section 11 schema script is
applied manually to a copy of the original catalog.

**Connection/Transaction Model**: Every connection enables
`foreign_keys=ON`, WAL, and `busy_timeout`; xerial's driver configuration sets
`transaction_mode=IMMEDIATE`, and the write pool has size 1.
`LockingImportProductsUseCase` is the primary `ImportProductsUseCase` bean and
acquires `ImportLock` before delegating to the injected transactional proxy for
`ImportProductsService`. The boundary configured by `SqliteConfig` opens one
transaction around the complete service call, commits only after the whole call
returns, and rolls back when an unexpected exception escapes. The decorator
releases `ImportLock` in `finally` after that transaction ends. Slice 0 verifies that the
driver begins write transactions in IMMEDIATE mode. The driver configuration
supplies the busy-timeout behavior without introducing a new project-level
timeout contract.

**Import Limits**: `catalog.import.max-body-size=5MB` and
`catalog.import.max-records=5000`. Spring multipart configuration maps both
`spring.servlet.multipart.max-file-size` and
`spring.servlet.multipart.max-request-size` to
`${catalog.import.max-body-size}`. The record cap is enforced after parsing the
array root and before processing records.

**Testing**: JUnit 5 and AssertJ for pure domain tests, MockMvc for API contracts,
and file-backed temporary SQLite databases for persistence and integration tests.
Each database starts from the exact original DDL and exact schema script from
`docs/spec.md` section 11. Fixtures are small and synthetic.

**Target Platform**: Single local JVM process with a writable filesystem and one
SQLite writer

**Project Type**: Synchronous web service

**Performance Goals**: No application timeout is introduced. The request is
bounded by the configured maximums (defaults: 5 MB and 5,000 records). The
required manual smoke run processes the 269-record challenge file and produces
the exact section 9 totals; the second run is fully idempotent.

**Constraints**: `docs/spec.md` wins every conflict; use only allowlisted
dependencies and fixed names; parse the array as a JSON tree; use bound
parameters; never log record contents; expose only `POST /imports` plus Actuator
`health` and `metrics`; return only the section 7 statuses and `code` values; do
not modify `data/catalog.db`; do not use pooled in-memory SQLite; do not add any
section 12 non-goal.

**Scale/Scope**: One file per request, one import at a time per instance,
configured maximums defaulting to 5,000 records and 5 MB, sequential processing,
and exactly one report entry per input element

## Constitution Check

*GATE: Passed before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Result | Plan evidence |
| --- | --- | --- |
| Specification authority | PASS | Every artifact references `docs/spec.md`; schema, contracts, metrics, errors, and names are copied without divergence. |
| Domain first | PASS | Pure records/sealed interfaces live in `domain`; ports are in `application`; Spring, JPA, Jackson, and web types stay in `infrastructure`. |
| Conservative decisions | PASS | `Identity.Inconclusive` produces `NOT_PROCESSED` and no write; the plan has no guessing path. |
| Input is data | PASS | The web adapter converts `JsonNode` elements to framework-neutral raw records that preserve JSON value types; BR-01 validation stays in the domain, persistence uses repository/JDBC binding only, and logs and errors exclude record and internal data. |
| Test first | PASS | Every slice begins with tests; C1 through C21 appear in test names and a consistency test reads scenario ids from `docs/spec.md`. |
| File-level consistency | PASS | One immediate transaction owns the file; unexpected exceptions escape and roll back; business outcomes remain values. |
| Deliberate scope | PASS | Authentication, authorization, and every section 12 non-goal remain excluded. |
| English and synchronized documentation | PASS | All generated artifacts are English; behavior changes require `docs/spec.md` and README updates in the same change. |

### Post-Design Re-check

`research.md`, `data-model.md`, `contracts/import-api.md`, and `quickstart.md`
preserve all eight gates. No violation or complexity exception is required.

## Project Structure

### Documentation (this feature)

```text
specs/001-catalog-import/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── import-api.md
├── checklists/
│   └── requirements.md
└── tasks.md              # Generated by /speckit-tasks
```

### Source Code (repository root)

```text
pom.xml
mvnw
mvnw.cmd
.mvn/wrapper/
src/
├── main/
│   ├── java/com/wagneralmeida/catalog/
│   │   ├── CatalogApplication.java
│   │   ├── domain/
│   │   │   ├── Product.java
│   │   │   ├── SellerProduct.java
│   │   │   ├── ImportRecord.java
│   │   │   ├── TextNormalizer.java
│   │   │   ├── Identity.java
│   │   │   ├── Outcome.java
│   │   │   ├── RejectionReason.java
│   │   │   ├── WarningCode.java
│   │   │   └── ImportReport.java
│   │   ├── application/
│   │   │   ├── port/in/ImportProductsUseCase.java
│   │   │   ├── port/out/ProductRepository.java
│   │   │   ├── port/out/SellerProductRepository.java
│   │   │   └── service/
│   │   │       ├── ImportProductsService.java
│   │   │       ├── ImportLock.java
│   │   │       └── LockingImportProductsUseCase.java
│   │   └── infrastructure/
│   │       ├── in/web/
│   │       │   ├── ImportController.java
│   │       │   ├── ImportResponse.java
│   │       │   ├── ApiExceptionHandler.java
│   │       │   ├── ImportFileParser.java
│   │       │   ├── ImportRecordMapper.java
│   │       │   ├── FileRejection.java
│   │       │   ├── FileRejectionException.java
│   │       │   └── ImportIdFilter.java
│   │       ├── out/persistence/
│   │       │   ├── ProductEntity.java                  # Hibernate path only
│   │       │   ├── SellerProductEntity.java            # Hibernate path only
│   │       │   ├── ProductJpaRepository.java           # Hibernate path only
│   │       │   ├── SellerProductJpaRepository.java     # Hibernate path only
│   │       │   ├── ProductRepositoryAdapter.java       # Hibernate path only
│   │       │   ├── SellerProductRepositoryAdapter.java # Hibernate path only
│   │       │   ├── JdbcProductRepositoryAdapter.java   # Spring JDBC path only
│   │       │   └── JdbcSellerProductRepositoryAdapter.java # Spring JDBC path only
│   │       └── config/
│   │           ├── SqliteConfig.java
│   │           └── NormalizedNameBackfill.java
│   └── resources/
│       ├── application.yml
│       └── db/catalog-schema.sql
└── test/
    ├── java/com/wagneralmeida/catalog/
    │   ├── domain/
    │   ├── application/
    │   └── infrastructure/
    └── resources/
        ├── db/original-schema.sql
        └── fixtures/

data/
├── catalog.db                 # Committed, read-only
├── products.json              # Manual smoke run only
└── work/catalog.db            # Generated, git-ignored
```

**Structure Decision**: Use one Maven module and the exact ports-and-adapters
package layout from `AGENTS.md`. The confirmed and authoritative base package is
`com.wagneralmeida.catalog`. Domain types have no Spring, JPA, Jackson, or web
imports. Fixed class names from
`docs/spec.md` section 3 are used without synonyms or overlapping replacements.
The application entry point is the only scaffold type outside that table. T004
selects the persistence path: Hibernate keeps the entity and Spring Data files
shown above, while the Spring JDBC fallback omits those files and implements the
same out-ports through `JdbcProductRepositoryAdapter` and
`JdbcSellerProductRepositoryAdapter` with `JdbcClient`/`JdbcTemplate` and the
exact section 11 table and column names.

## Implementation Slices

1. **Slice 0 — Scaffold and dialect spike**: create the Maven wrapper project with
   only allowlisted dependencies and treat T003/T004 as one decision gate. T003
   writes the Hibernate spike against a temporary file built from both section 11
   SQL blocks and tests exact mappings, insert/read, constraints, and C14. If it
   fails only because of Hibernate dialect or mapping incompatibility, document
   the failing evidence and disable that spike with the reason; T003 need not end
   with a green build. T004 then replaces JPA with Spring JDBC, adds the equivalent
   JDBC spike, records the selected path, and must end with a green
   `./mvnw verify`. If Hibernate succeeds, T004 records that decision and retains
   it. In either path, T004 verifies `foreign_keys`, WAL, `busy_timeout`, and
   `transaction_mode=IMMEDIATE`; failure of any required SQLite setting stops the
   work and is never a reason to switch persistence paths. Add a consistency test
   that extracts the two SQL blocks from `docs/spec.md` section 11 and compares
   them with `src/main/resources/db/catalog-schema.sql` and
   `src/test/resources/db/original-schema.sql`, so neither resource can drift.
2. **Slice 1 — Domain**: test and implement `ImportRecord` as the
   framework-neutral raw record, `TextNormalizer`, BR-01 validation,
   `WRONG_FIELD_TYPE`, `INVALID_RECORD` results, `Identity`, outcomes, reports,
   and BR-02 through BR-09 with no framework types.
3. **Slice 2 — Persistence and initialization**: implement the persistence path
   selected by T004 behind the same out-ports and port contract tests. The
   Hibernate path uses JPA entities and Spring Data repositories; the Spring JDBC
   fallback uses `JdbcProductRepositoryAdapter` and
   `JdbcSellerProductRepositoryAdapter` with `JdbcClient`/`JdbcTemplate` and no
   entities. Both use
   the exact section 11 table and column names, SQLite connection configuration,
   manual schema resource, and temporary-file test fixtures. Implement the
   idempotent `NormalizedNameBackfill` in one transaction; on a collision or any
   remaining NULL, it changes nothing and application startup fails.
4. **Slice 3 — Use case**: create `ImportProductsUseCase` so it accepts every raw
   record, including invalid ones, in file order with its 1-based position
   preserved, then test and implement sequential per-record processing,
   idempotency, and BR-10/BR-11. Implement `LockingImportProductsUseCase` as the
   primary port decorator: it acquires the non-blocking `ImportLock`, invokes the
   injected transactional proxy for `ImportProductsService`, and releases the
   lock in `finally` only after commit or rollback. For C16, use a Mockito spy on
   the real temporary-file persistence adapter and configure it to throw on a
   chosen middle record, then assert that product and seller-listing state is
   identical to its pre-import state. Add a parallel-import test that uses a
   Mockito spy on the real adapter to hold the first import open with a
   deterministic latch, never a sleep; the second request receives 409
   `IMPORT_IN_PROGRESS`, writes nothing, and a later request proves the lock was
   released after the first import finishes, both when it succeeds and when it
   fails.
5. **Slice 4 — API**: use `ImportFileParser` for size, strict UTF-8/BOM, JSON
   tree/array, trailing-token, and record-count checks, and use
   `ImportRecordMapper` to convert each element, without BR-01 validation, into a
   framework-neutral `ImportRecord` that preserves every raw JSON value type and
   the 1-based position. `FileRejectionException` carries a `FileRejection` enum
   value to `ApiExceptionHandler`. Pass the complete list, including invalid
   elements, to `ImportProductsUseCase`. Configure
   `catalog.import.max-body-size=5MB` and `catalog.import.max-records=5000`; bind
   both Spring multipart size settings to the body-size property, map
   `MaxUploadSizeExceededException` to 413 `FILE_TOO_LARGE`, and check the record
   limit in code to return 422 `TOO_MANY_RECORDS`. Implement only the fixed
   section 7 errors. Serialize successful results so `reason` and `detail` are
   omitted unless outcome is `NOT_PROCESSED`; `productId` is always present and
   null for `NOT_PROCESSED`; `sellerName` and `sellerProductId` are always present
   and null when unreadable; and `warnings` is always an array. MockMvc contract
   tests assert every presence, omission, nullability, limit, and error-mapping
   rule. Re-run C14 through the complete API and persistence flow and verify the
   tables afterwards.
6. **Slice 5 — Observability and documentation**: register all five instruments
   from a startup configuration bean so they appear in `/actuator/metrics` before
   the first event, with counters initially zero. Increment
   `catalog.import.records{outcome}` and
   `catalog.import.rejections{reason}` only after the file transaction commits,
   so rolled-back files contribute nothing. Record
   `catalog.import.file.rejections{code}` for the fixed section 7 4xx codes,
   `catalog.import.failures` for HTTP 500 only, and
   `catalog.import.duration`. Add a test that asserts startup registration before
   any import. `ImportIdFilter` generates the per-request UUID for `POST /imports`,
   exposes it as a request attribute, owns MDC setup/cleanup, and keeps the MDC
   active for controller and exception-handler logs. Keep management exposure for only
   `health` and `metrics`, and configure `management.health.db.enabled=false` so
   liveness responds promptly while an import holds the single write connection.
   Complete README content, then perform the two-run manual smoke test separately
   from automated tests.

Every automated slice task starts with failing tests. T003 may end with the
documented and disabled Hibernate incompatibility that T004 resolves; every other
automated task ends with `./mvnw verify`, and the manual smoke verification
follows a passing automated build.

## Complexity Tracking

No constitution violations or justified exceptions are present.
