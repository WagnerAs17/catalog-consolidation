# Phase 0 Research: Catalog Consolidation Import

`docs/spec.md` is authoritative. This document records implementation decisions
needed by the plan; it does not add product behavior.

## Runtime and Dependency Alignment

**Decision**: Use Java 21 with Spring Boot 3.5.16, the current 3.5.x patch, and
let Spring Boot manage versions for Hibernate core,
`hibernate-community-dialects`, SQLite JDBC, Micrometer, JUnit, and AssertJ. Add
only dependencies allowed by `AGENTS.md`.

**Rationale**: Spring Boot's 3.5 dependency management aligns
`hibernate-community-dialects` with Hibernate core and manages SQLite JDBC. This
avoids version skew while honoring the fixed 3.5.x line.

**Alternatives considered**: Spring Boot 4, manually overriding managed versions,
or adding unlisted libraries are rejected.

**Primary source**: [Spring Boot 3.5 managed dependency coordinates](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html)

## Persistence Compatibility Gate

**Decision**: Begin slice 0 with a JPA/SQLite dialect spike. Against a temporary
file initialized from the exact original DDL and schema script, map both tables,
insert and read both entities, prove uniqueness and foreign-key enforcement, and
round-trip the C14 malicious text through bound parameters. Continue with JPA
only if every check passes.

**Rationale**: `docs/spec.md` identifies the community dialect as the only
unconfirmed technical compatibility point. Retiring it first prevents the rest
of the implementation from depending on an unproved adapter.

**Alternatives considered**: Spring JDBC is the only allowed fallback and only
after a failed spike. A custom ORM, generated schema, or another database is
rejected.

**Primary source**: [Hibernate ORM dialect guidance](https://docs.jboss.org/hibernate/orm/6.5/userguide/html_single/Hibernate_User_Guide.html#database-dialect)

**Slice 0 result**: Retain Spring Data JPA/Hibernate; the Spring JDBC fallback
is not needed. With Spring Boot 3.5.16 managing Hibernate 6.6.53.Final and
sqlite-jdbc 3.49.1.0, the enabled file-backed spike validates the exact schema,
maps and round-trips both tables, enforces both uniqueness constraints, the
Product foreign key, and `IX_SellerProduct_ProductId`, and proves C14 text is
bound data. Hibernate uses its standard physical naming strategy to preserve
the explicit section 11 identifiers, and the three `Long` identifier mappings
declare the DDL's SQLite `INTEGER` type for schema validation. The same spike
verifies `foreign_keys=ON`, WAL, a positive `busy_timeout`, xerial
`transaction_mode=IMMEDIATE`, and write-pool size 1; a read-started transaction
blocks a competing writer until it ends.

## SQLite Connection Configuration

**Decision**: `SqliteConfig` builds connections from `catalog.db.path`, applies
`foreign_keys=ON`, WAL, and `busy_timeout` on every connection, selects xerial's
`IMMEDIATE` transaction mode, and limits the write pool to one connection. No
project-specific busy-timeout duration is introduced; use the driver-managed
setting because the authoritative specification fixes the pragma but no numeric
override.

**Rationale**: Foreign keys and busy handling are connection concerns, WAL is the
required journal mode, and xerial maps transaction mode `IMMEDIATE` to
`BEGIN IMMEDIATE`. A single writer matches SQLite and the explicit project rule.

**Alternatives considered**: One-time PRAGMA execution, deferred transactions,
multiple writers, or retries are rejected.

**Primary sources**:

- [xerial SQLite configuration](https://github.com/xerial/sqlite-jdbc/blob/master/src/main/java/org/sqlite/SQLiteConfig.java)
- [xerial transaction-mode mapping](https://github.com/xerial/sqlite-jdbc/blob/master/src/main/java/org/sqlite/SQLiteConnectionConfig.java)
- [Spring Boot SQL datasource configuration](https://docs.spring.io/spring-boot/3.5/reference/data/sql.html)

## File Transaction and Concurrency

**Decision**: Acquire `ImportLock` before obtaining the database transaction.
Within the lock, execute the entire file-order loop in one immediate read-write
transaction. Release the lock in `finally`. Unexpected exceptions escape the
transaction and roll it back; invalid, inconclusive, and inconsistent records
remain ordinary outcomes.

**Rationale**: Lock-before-transaction returns HTTP 409 without waiting for the
single database connection. One transaction lets later records observe earlier
writes and satisfies C16 whole-file rollback.

**Alternatives considered**: Per-record transactions, blocking lock acquisition,
exception-driven business outcomes, and retries are rejected.

## Exact Schema and Startup Backfill

**Decision**: Treat the two SQL blocks in `docs/spec.md` section 11 as immutable
inputs. Apply the schema script manually to a working copy; configure Hibernate
with explicit names and `ddl-auto=validate`. After schema validation,
`NormalizedNameBackfill` uses the domain `TextNormalizer`, remains idempotent,
and fails startup on a normalization collision or leftover NULL.

**Rationale**: The exact unique indexes enforce identity and idempotency. The
unique normalized-name index intentionally exposes collisions, and one
normalizer prevents divergent matching behavior.

**Alternatives considered**: Flyway, generated DDL, automatic migration,
database-side normalization, duplicate normalizers, and silent collision repair
are rejected.

## Data Files and Test Databases

**Decision**: Keep `data/catalog.db` read-only. Manual validation copies it to
git-ignored `data/work/catalog.db` and uses `data/products.json` only for the
specified smoke run. Automated tests create isolated temporary SQLite files,
apply the exact original DDL followed by the exact schema script, and load only
small synthetic fixtures.

**Rationale**: This preserves the challenge artifacts and lets tests exercise
real SQLite constraints, locking, and transaction behavior without depending on
repository data.

**Alternatives considered**: Modifying the original catalog, pooled in-memory
SQLite, substitute databases, Testcontainers, or test dependence on `data/` are
rejected.

## JSON Parsing and Error Contract

**Decision**: `ImportFileParser` performs the size and record-count guards, reads
strict UTF-8 with an optional leading BOM, rejects trailing tokens, and parses
the root as a `JsonNode` tree. `ImportRecordMapper` extracts each element into a
framework-neutral raw record that retains the JSON type of every value but
contains no Jackson type. BR-01 validation and all `INVALID_RECORD` results are
produced in the domain. The web adapter never binds the upload to a typed list.
MockMvc tests assert exactly
the section 7 statuses and `code` values and verify that `ProblemDetail` contains
no record content or internal message.

**Rationale**: Tree parsing preserves integer-versus-decimal distinctions and
allows one malformed record to be reported without failing neighboring records.

**Alternatives considered**: `List<ImportRecord>` binding, coercion of wrong JSON
types, extra statuses/codes/fields, or diagnostic leakage are rejected.

## Observability Placement

**Decision**: `ImportIdFilter` generates `importId` for each `POST /imports`,
places it in MDC and a request attribute, and clears MDC in `finally`, including
for rejections raised before the controller. Record
`catalog.import.records{outcome}` and
`catalog.import.rejections{reason}` only after commit. Record
`catalog.import.duration`, `catalog.import.failures` for HTTP 500 only, and
`catalog.import.file.rejections{code}` for the fixed 4xx codes. Register all five
`catalog.import.*` instruments at startup, with counters at zero, so every name
appears in `/actuator/metrics` before the first event. Expose only Actuator
`health` and `metrics`.

**Rationale**: Recording outcome counters outside the completed transaction keeps
rolled-back files out of successful counters. Fixed low-cardinality tags avoid
input leakage and unbounded cardinality.

**Alternatives considered**: Seller/code tags, payload logs, persisted import
records, extra metrics, or additional management endpoints are rejected.

## Test Strategy and Slice Order

**Decision**: Use pure JUnit 5/AssertJ domain tests, file-backed SQLite
integration tests, and MockMvc API tests. Test method names carry C1 through C21;
a consistency test extracts scenario ids from `docs/spec.md` and verifies all are
represented. Execute slices in the order fixed by `AGENTS.md`, beginning every
slice with failing tests and ending with `./mvnw verify`.

**Rationale**: The levels prove pure rules, actual SQLite behavior, and the public
HTTP contract separately while maintaining direct traceability to the source of
truth.

**Alternatives considered**: Spring domain tests, mocked persistence as the only
integration evidence, a duplicated scenario registry, or implementation-first
work are rejected.
