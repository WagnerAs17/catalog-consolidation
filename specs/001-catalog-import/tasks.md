# Tasks: Catalog Consolidation Import

**Source of truth**: `docs/spec.md` wins every conflict.

**Design inputs**: `specs/001-catalog-import/plan.md`, `spec.md`,
`data-model.md`, `research.md`, `contracts/import-api.md`, and `quickstart.md`.

**Execution rule**: Every automated task begins by writing and running the named
failing test and implements only enough code to satisfy it. T003 may end with a
documented and disabled Hibernate dialect/mapping incompatibility for T004 to
resolve; every other automated task finishes with `./mvnw verify` passing. T027
is an explicitly manual verification after the automated build is green. No task
may add a section 12 non-goal or an undefined endpoint, field, status, error code,
table, column, index, or dependency.

## Format

- `[P]` means the task can run in parallel with the immediately preceding
  unfinished task because it touches different files and has no dependency on it.
- `[US1]`, `[US2]`, and `[US3]` map behavior to the user stories in `spec.md`.
- BR and C identifiers in each behavior task are the required traceability links.

## Slice 0: Scaffold and SQLite/Hibernate Dialect Spike

**Goal**: Establish the allowlisted Java 21 build and retire SQLite dialect risk
before any domain or feature implementation.

**Independent test**: The scaffold loads, the exact section 11 SQL resources are
drift-checked, both tables map and round-trip against a temporary SQLite file,
constraints and required pragmas hold, transactions begin in IMMEDIATE mode, and
C14 malicious text remains data. This slice ends with an explicit JPA-versus-JDBC
decision.

- [X] T001 Write a failing Spring context smoke test in `src/test/java/com/wagneralmeida/catalog/CatalogApplicationTest.java`, confirm the latest Spring Boot 3.5.x patch on start.spring.io or Maven Central, then scaffold Java 21, the Maven wrapper, that confirmed Spring Boot patch, `com.wagneralmeida.catalog.CatalogApplication`, the package tree from `plan.md`, and only the allowlisted dependencies in `pom.xml`, `.mvn/wrapper/`, `mvnw`, `mvnw.cmd`, and `src/main/java/com/wagneralmeida/catalog/CatalogApplication.java` (docs/spec.md section 10); finish with `./mvnw verify` passing.
- [X] T002 Write a failing `SchemaScriptConsistencyTest` in `src/test/java/com/wagneralmeida/catalog/infrastructure/out/persistence/SchemaScriptConsistencyTest.java` that extracts both fenced SQL blocks from `docs/spec.md` section 11, then copy them byte-for-byte into `src/main/resources/db/catalog-schema.sql` and `src/test/resources/db/original-schema.sql` so the test prevents drift; finish with `./mvnw verify` passing.
- [X] T003 [US2] Write and run a failing Hibernate `SqliteDialectSpikeIT` in `src/test/java/com/wagneralmeida/catalog/infrastructure/out/persistence/SqliteDialectSpikeIT.java` using a temporary file initialized from both section 11 resources, then implement exact explicit mappings in `ProductEntity`, `SellerProductEntity`, `ProductJpaRepository`, and `SellerProductJpaRepository` at `src/main/java/com/wagneralmeida/catalog/infrastructure/out/persistence/` and prove insert, read, `UX_Product_NormalizedName`, `UX_SellerProduct_Seller_Code`, `IX_SellerProduct_ProductId`, the Product foreign key, and `c14_maliciousTextIsData` with bound parameters. If the spike passes, keep it enabled for T004. If and only if it fails because of Hibernate dialect or mapping incompatibility, document the failing evidence and reason in `specs/001-catalog-import/research.md` and disable the spike with that reason for T004; do not disable or reinterpret any other failure (C14, BR-01, docs/spec.md sections 10-11). T003 is the first half of the decision gate and is not independently required to end with a green `./mvnw verify`.
- [X] T004 [US2] Write and run the failing completion of the persistence decision gate in `src/test/java/com/wagneralmeida/catalog/infrastructure/out/persistence/SqliteDialectSpikeIT.java`, asserting `foreign_keys=ON`, WAL, `busy_timeout`, write pool size 1, and xerial `transaction_mode=IMMEDIATE`; any required SQLite-setting failure stops the work and is reported without switching paths. If T003 passed, retain Spring Data JPA/Hibernate. If T003 was disabled for the documented dialect/mapping reason, replace the JPA dependencies in `pom.xml` with the allowed Spring JDBC fallback, add the equivalent enabled JDBC mapping/insert/read/constraint/C14 spike using `JdbcProductRepositoryAdapter` and `JdbcSellerProductRepositoryAdapter`, and keep using bound parameters. Record the selected path and evidence in `specs/001-catalog-import/research.md`; finish the combined T003/T004 gate with `./mvnw verify` passing before any later slice begins (C14, BR-01, docs/spec.md section 10 “First step”).

**Checkpoint**: The persistence choice is final for this feature and the exact SQL
resources cannot drift from `docs/spec.md`.

---

## Slice 1: Pure Domain

**Goal**: Implement every framework-free validation, normalization, identity,
new-product, and warning rule before application or web orchestration.

**Independent test**: Pure JUnit 5/AssertJ tests exercise raw JSON-type state
without Spring, JPA, Jackson, or web types and prove C2-C7, C12-C13, C19, and C21.

- [X] T005 [US2] Write failing `ImportRecordTest` cases in `src/test/java/com/wagneralmeida/catalog/domain/ImportRecordTest.java` for BR-01 precedence and every detail code (`NOT_AN_OBJECT`, `MISSING_ID`, `MISSING_SELLER_NAME`, `MISSING_NAME`, `WRONG_FIELD_TYPE`, `NAME_NOT_NORMALIZABLE`), including `c13_invalidRecordContinues` and an assertion that unknown JSON fields are ignored, then implement framework-neutral `ImportRecord`, `Outcome`, `RejectionReason`, and `ImportReport` in `src/main/java/com/wagneralmeida/catalog/domain/`; preserve the array element's own raw JSON type, make a non-object produce `INVALID_RECORD/NOT_AN_OBJECT`, accept `Id` only as string or integer with exact decimal text, reject boolean/decimal/array/object `Id` as `WRONG_FIELD_TYPE`, and enforce required `SellerName`/`Name` plus optional `Brand`/`Category` string-null-missing-blank rules (BR-01, C13, docs/spec.md section 4); finish with `./mvnw verify` passing.
- [X] T006 [P] [US1] Write failing `TextNormalizerTest` cases in `src/test/java/com/wagneralmeida/catalog/domain/TextNormalizerTest.java` named for `c03_normalizationRecognizesEquivalentText` and `c19_symbolOnlyNameIsNotNormalizable`, then implement the exact normalization algorithm once in `src/main/java/com/wagneralmeida/catalog/domain/TextNormalizer.java`, including absent normalized brands (BR-02, BR-03, C3, C19); finish with `./mvnw verify` passing.
- [X] T007 [US1] Write failing `IdentityTest` cases in `src/test/java/com/wagneralmeida/catalog/domain/IdentityTest.java` for `c02_existingEquivalentProduct`, `c04_bothBrandsAbsent`, `c05_brandPresentOnOneSideOnly`, `c06_brandMismatch`, `c07_distinctNamesRemainDistinct`, and `c21_laterBrandMakesIdentityInconclusive`, then implement `Product` and sealed `Identity` in `src/main/java/com/wagneralmeida/catalog/domain/Product.java` and `src/main/java/com/wagneralmeida/catalog/domain/Identity.java` with `New`, `Existing(product)`, and `Inconclusive(reason)` decisions that never guess or write on uncertainty (BR-04, BR-05, C2, C4, C5, C6, C7, C21); finish with `./mvnw verify` passing.
- [X] T008 [US1] Write failing `ProductRulesTest` cases in `src/test/java/com/wagneralmeida/catalog/domain/ProductRulesTest.java` for `c12_categoryDivergencePreservesCatalog` and first-occurrence storage normalization, then implement `SellerProduct` and `WarningCode` and complete `Product`/`ImportReport` behavior in `src/main/java/com/wagneralmeida/catalog/domain/SellerProduct.java`, `WarningCode.java`, `Product.java`, and `ImportReport.java` so existing products are never updated, category divergence appears only when both normalized categories differ, and new product text follows the first occurrence (BR-08, BR-09, C12); finish with `./mvnw verify` passing.

**Checkpoint**: All domain decisions are framework-free and return values rather
than exceptions for business outcomes.

---

## Slice 2: Persistence, SQLite Configuration, and Backfill

**Goal**: Complete inward-facing persistence ports and adapters over the exact
schema, production SQLite settings, isolated temporary-file fixtures, and atomic
startup normalization.

**Independent test**: Adapter contract tests use temporary SQLite files created
from the original DDL plus schema script, bound lookups return correct domain
objects, every connection has the required settings, and backfill is idempotent
and atomic.

- [X] T009 [US1] Write failing `ProductRepositoryAdapterIT` port contract cases in `src/test/java/com/wagneralmeida/catalog/infrastructure/out/persistence/ProductRepositoryAdapterIT.java` for normalized-name lookup and preservation of existing product data, then implement `ProductRepository` in `src/main/java/com/wagneralmeida/catalog/application/port/out/ProductRepository.java` and the T004-selected path under `src/main/java/com/wagneralmeida/catalog/infrastructure/out/persistence/`: if Hibernate was chosen, use explicit `@Table`/`@Column` mappings in `ProductEntity`, `ProductJpaRepository`, and `ProductRepositoryAdapter`; if Spring JDBC was chosen, omit entities and Spring Data repositories and implement `JdbcProductRepositoryAdapter` with `JdbcClient`/`JdbcTemplate`, bound parameters, and the same section 11 table and column names. Both paths must satisfy the same port contract tests (BR-04, BR-08, C2, C12, docs/spec.md sections 10-11); finish with `./mvnw verify` passing.
- [X] T010 [US1] Write failing `SellerProductRepositoryAdapterIT` port contract cases in `src/test/java/com/wagneralmeida/catalog/infrastructure/out/persistence/SellerProductRepositoryAdapterIT.java` for seller/code uniqueness, cross-seller independence, and exact case-sensitive `SellerName` comparison after trim, then implement `SellerProductRepository` in `src/main/java/com/wagneralmeida/catalog/application/port/out/SellerProductRepository.java` and the T004-selected path under `src/main/java/com/wagneralmeida/catalog/infrastructure/out/persistence/`: if Hibernate was chosen, use explicit mappings in `SellerProductEntity`, `SellerProductJpaRepository`, and `SellerProductRepositoryAdapter`; if Spring JDBC was chosen, omit entities and Spring Data repositories and implement `JdbcSellerProductRepositoryAdapter` with `JdbcClient`/`JdbcTemplate`, bound parameters, and the same section 11 table and column names. Both paths must satisfy the same port contract tests (BR-06, BR-07, C8, C9, C10, C11, docs/spec.md sections 4 and 10-11); finish with `./mvnw verify` passing.
- [X] T011 Write a failing `SqliteConfigIT` in `src/test/java/com/wagneralmeida/catalog/infrastructure/config/SqliteConfigIT.java`, then implement `SqliteConfig` in `src/main/java/com/wagneralmeida/catalog/infrastructure/config/SqliteConfig.java` and `src/main/resources/application.yml` so `catalog.db.path` defaults to `./data/work/catalog.db`, every connection has `foreign_keys=ON`, WAL, `busy_timeout`, and xerial `transaction_mode=IMMEDIATE`, and the write pool maximum is 1; on the Hibernate path set `spring.jpa.hibernate.ddl-auto=validate`, while on the Spring JDBC path assert Hibernate and JPA are absent from the classpath and add an equivalent schema-validation check that the Product and SellerProduct tables and columns match docs/spec.md section 11 (docs/spec.md sections 9-11); finish with `./mvnw verify` passing.
- [X] T012 Write a failing `SqliteTemporaryFileIT` in `src/test/java/com/wagneralmeida/catalog/infrastructure/out/persistence/SqliteTemporaryFileIT.java`, then add reusable test support in `src/test/java/com/wagneralmeida/catalog/infrastructure/out/persistence/SqliteIntegrationTestSupport.java` that creates a fresh temporary file, applies `src/test/resources/db/original-schema.sql` followed by `src/main/resources/db/catalog-schema.sql`, loads only synthetic fixtures from `src/test/resources/fixtures/`, overrides `catalog.db.path`, and never reads `data/` (docs/spec.md sections 9-11); finish with `./mvnw verify` passing.
- [X] T013 [US1] Write failing `NormalizedNameBackfillIT` cases in `src/test/java/com/wagneralmeida/catalog/infrastructure/config/NormalizedNameBackfillIT.java` for successful fill, repeat idempotency, collision rollback, and leftover-NULL startup failure, then implement `NormalizedNameBackfill` in `src/main/java/com/wagneralmeida/catalog/infrastructure/config/NormalizedNameBackfill.java` as one transaction reusing `TextNormalizer`, leaving no partially filled values after failure (BR-02, docs/spec.md sections 10-11); finish with `./mvnw verify` passing.

**Checkpoint**: Persistence respects the fixed schema and the original catalog has
not been opened, migrated, or modified by tests.

---

## Slice 3: Import Use Case, Transaction, Rollback, and Concurrency

**Goal**: Implement the complete section 6 record flow in file order, listing
semantics, the single file transaction, and non-blocking import admission.

**Independent test**: Service tests prove creation, linking, repetition,
inconsistency, idempotency, sequential visibility, rollback, and deterministic
parallel-import behavior without sleeps.

- [X] T014 [US1] Write failing `ImportProductsServiceTest` cases in `src/test/java/com/wagneralmeida/catalog/application/service/ImportProductsServiceTest.java` named `c01_createAndLinkNewProduct`, `c02_linkExistingProduct`, `c17_secondSellerSeesEarlierCreation`, and `c21_laterRecordSeesEarlierBrandlessProduct`, then implement `ImportProductsUseCase` in `src/main/java/com/wagneralmeida/catalog/application/port/in/ImportProductsUseCase.java` and `ImportProductsService` in `src/main/java/com/wagneralmeida/catalog/application/service/ImportProductsService.java` to accept the complete ordered `List<ImportRecord>`, preserve 1-based positions, execute docs/spec.md section 6 in order, and enforce sequential visibility (BR-04, BR-05, BR-10, C1, C2, C17, C21); finish with `./mvnw verify` passing.
- [X] T015 [US1] Extend the failing `ImportProductsServiceTest` in `src/test/java/com/wagneralmeida/catalog/application/service/ImportProductsServiceTest.java` with `c08_repeatedCodeAlreadyLinked`, `c09_twoCodesLinkSameProduct`, `c10_sameCodeAcrossSellersIsIndependent`, `c11_existingListingNeverRedirects`, and `c15_reimportCreatesNothing`, including the C15 assertion that records previously reported as `NOT_PROCESSED` remain `NOT_PROCESSED` on re-run, then complete listing flow in `ImportProductsService` and `SellerProduct` at `src/main/java/com/wagneralmeida/catalog/application/service/ImportProductsService.java` and `src/main/java/com/wagneralmeida/catalog/domain/SellerProduct.java` so listing conflicts write nothing and re-imported links are idempotent (BR-06, BR-07, C8, C9, C10, C11, C15); finish with `./mvnw verify` passing.
- [X] T016 [US3] Write a failing `ImportProductsRollbackIT` in `src/test/java/com/wagneralmeida/catalog/application/service/ImportProductsRollbackIT.java` that uses a Mockito spy on the real temporary-file persistence adapter and configures it to throw on a chosen middle record, then configure the infrastructure transaction boundary in `SqliteConfig` around the entire `ImportProductsService` invocation so one IMMEDIATE transaction commits only after success and escapes unexpected exceptions for rollback; assert the logical table state of Product and SellerProduct is unchanged from before the file (BR-11, C16, docs/spec.md sections 6 and 9-10); finish with `./mvnw verify` passing.
- [X] T017 [US3] Write a failing parallel `ImportConcurrencyIT` in `src/test/java/com/wagneralmeida/catalog/infrastructure/in/web/ImportConcurrencyIT.java` that uses a Mockito spy on the real persistence adapter and a deterministic latch to hold the first request without sleeps, asserts the second gets 409 `IMPORT_IN_PROGRESS` and writes nothing, then proves a later request enters after both a successful and a failed first request; implement non-blocking `ImportLock` and the primary `LockingImportProductsUseCase` decorator in `src/main/java/com/wagneralmeida/catalog/application/service/`, with the decorator acquiring the lock before it invokes the injected transactional `ImportProductsService` proxy and releasing it in `finally` after commit/rollback, and map the fixed 409 through `ApiExceptionHandler` in `src/main/java/com/wagneralmeida/catalog/infrastructure/in/web/` (BR-11, docs/spec.md sections 3, 7, and 9); finish with `./mvnw verify` passing.

**Checkpoint**: One admitted import owns one complete transaction; business
outcomes do not throw, system failures roll back, and the lock never leaks.

---

## Slice 4: HTTP API, Tree Parsing, Limits, Errors, and Serialization

**Goal**: Complete the exact `POST /imports` contract without moving BR-01 into
the web layer or adding any status, code, or response field.

**Independent test**: MockMvc covers multipart/UTF-8/BOM/tree parsing, invalid
element forwarding, empty arrays, both limits, every fixed file-level status and
code, generic ProblemDetail content, and every serialization presence rule.

- [X] T018 [US2] Write failing `ImportControllerParsingTest`, `ImportFileParserTest`, and `ImportRecordMapperTest` cases in `src/test/java/com/wagneralmeida/catalog/infrastructure/in/web/` for the `POST /imports` multipart `file` contract, strict UTF-8, leading BOM, trailing-token rejection, ignored multipart filename and part content type, ignored unknown JSON fields, JSON tree traversal, non-object elements, every raw field and node type, exact integer `Id` text, invalid neighbors, and `c20_emptyArrayReturnsZeroTotals`, using the real `ImportProductsUseCase`, real services, and real persistence adapters over a temporary SQLite file to verify the complete ordered result including invalid records; then implement `ImportFileParser` and `ImportRecordMapper` in `src/main/java/com/wagneralmeida/catalog/infrastructure/in/web/` so they convert `JsonNode` elements to framework-neutral `ImportRecord` values without BR-01 validation while `ImportController` only orchestrates the parser and use case (BR-01, C13, C20, docs/spec.md sections 3-4); finish with `./mvnw verify` passing.
- [X] T019 [US2] Write failing `ImportLimitsTest` MockMvc integration cases in `src/test/java/com/wagneralmeida/catalog/infrastructure/in/web/ImportLimitsTest.java` for the default and overridden limits, plus a record containing one 100 KB string field that remains within the configured body maximum and is processed normally with no per-field length rejection; assert the logical Product and SellerProduct table state is unchanged for 413 `FILE_TOO_LARGE` and 422 `TOO_MANY_RECORDS`, then configure `catalog.import.max-body-size=5MB`, `catalog.import.max-records=5000`, `spring.servlet.multipart.max-file-size`, and `spring.servlet.multipart.max-request-size` in `src/main/resources/application.yml`; map `MaxUploadSizeExceededException` to 413 `FILE_TOO_LARGE` in `ApiExceptionHandler` and enforce both limits in `ImportFileParser` before the use case is invoked (C18, docs/spec.md sections 4, 7, and 10); finish with `./mvnw verify` passing.
- [X] T020 [US2] Write failing `ImportErrorContractTest` and `WebLayerRefactorContractIT` MockMvc integration cases in `src/test/java/com/wagneralmeida/catalog/infrastructure/in/web/` separately for empty input, trailing garbage, missing `file`, invalid UTF-8, and invalid JSON as 400 `FILE_UNREADABLE`; 409 `IMPORT_IN_PROGRESS`; 413 `FILE_TOO_LARGE`; 415 `UNSUPPORTED_MEDIA_TYPE`; 422 `NOT_A_JSON_ARRAY`; and 500 `INTERNAL_ERROR`, asserting `application/problem+json`, exactly the standard `status`, `title`, generic `detail`, and extra `code` fields, no record contents or internal messages, and logically unchanged Product and SellerProduct tables for every C18 rejection variant; then implement `FileRejection`, `FileRejectionException`, and `ApiExceptionHandler` in `src/main/java/com/wagneralmeida/catalog/infrastructure/in/web/` (BR-11, C18, docs/spec.md sections 3-4 and 7); finish with `./mvnw verify` passing.
- [X] T021 [US1] Write failing `ImportResponseContractTest` MockMvc cases in `src/test/java/com/wagneralmeida/catalog/infrastructure/in/web/ImportResponseContractTest.java` plus a failing full-flow `ImportMaliciousTextIT` in `src/test/java/com/wagneralmeida/catalog/infrastructure/in/web/ImportMaliciousTextIT.java`, then implement `ImportResponse`, `ImportIdFilter`, and response mapping in `src/main/java/com/wagneralmeida/catalog/infrastructure/in/web/` so the filter gives each `POST /imports` request one UUID `importId`, exposes the same value as a request attribute and through MDC correlation, clears MDC in `finally`, and never persists it; ensure `reason`/`detail` are omitted unless outcome is `NOT_PROCESSED`, `productId` is always present and null for `NOT_PROCESSED`, `sellerName`/`sellerProductId` are always present and null when unreadable, `warnings` is always an array, order and 1-based position are preserved, and totals reconcile; run C14 malicious text through the real API/use-case/persistence flow and verify both tables remain intact afterwards (C1, C2, C12, C13, C14, docs/spec.md sections 3-4 and 7); finish with `./mvnw verify` passing.

**Checkpoint**: The only business endpoint is contract-complete and exposes no
framework defaults or diagnostic data beyond `docs/spec.md`.

---

## Slice 5: Metrics, Logging, Management, Traceability, and Manual Validation

**Goal**: Add only the fixed observability surface, prove commit-aware counting
and non-blocking health, enforce scenario traceability, and document/run the
authoritative smoke validation.

**Independent test**: All five catalog instruments and every allowed tag value
exist before an import; committed and rolled-back files affect the correct
counters; MDC is cleared; logs contain no records; only health/metrics are
exposed; health completes while the writer is held; every C id appears in a test
name; and the two manual sample runs produce the section 9 totals.

- [X] T022 Write a failing `ImportMetricsRegistrationTest` in `src/test/java/com/wagneralmeida/catalog/infrastructure/in/web/ImportMetricsRegistrationTest.java` that queries `/actuator/metrics` before any import, then register from a startup configuration bean declared in `src/main/java/com/wagneralmeida/catalog/CatalogApplication.java`, not from `ImportController` or `ApiExceptionHandler`, all five instruments: `catalog.import.records` with every `Outcome` tag value, `catalog.import.rejections` with every `RejectionReason` tag value, `catalog.import.file.rejections` with all fixed section 7 4xx `code` values, `catalog.import.duration`, and `catalog.import.failures`, with counters initially zero (docs/spec.md section 9); finish with `./mvnw verify` passing.
- [X] T023 Write a failing `ImportMetricsCommitIT` in `src/test/java/com/wagneralmeida/catalog/infrastructure/in/web/ImportMetricsCommitIT.java`, then complete metrics recording in `ImportController` and `ApiExceptionHandler` at `src/main/java/com/wagneralmeida/catalog/infrastructure/in/web/` so `catalog.import.records` and `catalog.import.rejections` increment only after successful commit, their committed values equal the returned `ImportReport`, a C16 rolled-back file contributes neither, `catalog.import.duration` is recorded, `catalog.import.file.rejections{code}` counts only fixed 4xx responses, `catalog.import.failures` counts HTTP 500 only, and no seller or seller-product-code tags exist (BR-11, C16, docs/spec.md section 9); finish with `./mvnw verify` passing.
- [X] T024 Write a failing `ImportObservabilityIT` and the 413/415 log-correlation cases in `WebLayerRefactorContractIT` under `src/test/java/com/wagneralmeida/catalog/infrastructure/in/web/` that hold an import at a deterministic latch, prove `/actuator/health` completes before the latch is released without sleeps, verify only `health` and `metrics` are exposed, capture logs/MDC for controller and pre-controller rejections, and assert the `importId` returned in the response is the same value present in MDC for that request; then implement MDC ownership in `ImportIdFilter`, keep lifecycle logs and commit-aware metrics in `ImportController`, keep rejection/failure metrics in `ApiExceptionHandler`, and retain `management.health.db.enabled=false` in `src/main/resources/application.yml` (C18, docs/spec.md sections 3, 7, 9, and 12); finish with `./mvnw verify` passing.
- [X] T025 [P] Write a failing `ScenarioCoverageConsistencyTest` in `src/test/java/com/wagneralmeida/catalog/ScenarioCoverageConsistencyTest.java` that extracts C1 through C21 from `docs/spec.md` section 8 and scans test method names, then complete any missing scenario-name traceability in the existing test files without creating a duplicated scenario registry (C1-C21, docs/spec.md section 8); finish with `./mvnw verify` passing.
- [X] T026 Write a failing `DocumentationConsistencyTest` in `src/test/java/com/wagneralmeida/catalog/DocumentationConsistencyTest.java` for the required README data-file, configuration, security-scope, error, metric, and smoke-run statements plus the `data/work/` ignore rule, then create/update `README.md` and `.gitignore` only as required by docs/spec.md sections 2, 7, 9-12 and `AGENTS.md`; finish with `./mvnw verify` passing.
- [X] T027 Manual verification: after T026 and a passing `./mvnw verify`, follow `specs/001-catalog-import/quickstart.md` on a fresh `data/work/catalog.db` without modifying `data/catalog.db`; verify the first `data/products.json` run reports 269 records, 3 `CREATED_AND_LINKED`, 265 `LINKED_TO_EXISTING`, 1 `ALREADY_LINKED`, 0 `NOT_PROCESSED`, and 1 `CATEGORY_DIVERGENCE`, then verify the second run reports 269 `ALREADY_LINKED` (docs/spec.md sections 9-11).

**Checkpoint**: The feature satisfies the full source specification and the
committed challenge files were used only for the manual smoke run.

---

## Dependencies and Execution Order

### Slice Dependencies

```text
Slice 0 scaffold/dialect decision
  → Slice 1 pure domain
    → Slice 2 persistence/backfill
      → Slice 3 use case/transaction/concurrency
        → Slice 4 HTTP contract
          → Slice 5 observability/traceability/manual validation
```

- Slice 0 is a hard T003/T004 decision gate. Do not start later tasks until T004
  records JPA versus JDBC and ends with a green `./mvnw verify`; a required
  SQLite-setting failure stops the work on both paths.
- Slice 1 supplies the domain types and rules required by every later slice.
- Slice 2 supplies real adapters and temporary-file fixtures required by rollback
  and integration tests.
- Slice 3 supplies the use case, transaction, and lock required by the API.
- Slice 4 completes the endpoint required by metrics and manual validation.
- Slice 5 is the release-completion slice.

### Task Dependencies

- T001 → T002 → T003 → T004, with T003 allowed to carry only a documented and
  disabled Hibernate dialect/mapping failure into T004; T004 must make the
  selected path green.
- After T004, T005 and T006 may run concurrently; T007 depends on T006, and T008
  depends on T007.
- T009 → T010 → T011 → T012 → T013, following the persistence path selected by
  T004; T009 and T010 run the same port contracts on that path.
- T014 → T015 → T016 → T017.
- T018 → T019 → T020 → T021.
- After T021, T022 and T025 may run concurrently; T023 depends on T022, T024
  depends on T023, T026 depends on T024 and T025, and manual T027 depends on T026
  and a passing automated build.

### Parallel Opportunities

- **Domain**: T006 can run alongside T005 because it touches only
  `TextNormalizer.java` and `TextNormalizerTest.java` and needs no unfinished raw
  record work.
- **Final validation**: T025 can run alongside T022 because the scenario coverage
  scanner and existing scenario tests are independent of metric registration
  files.
- No other task is marked `[P]`; the remaining work either shares production/test
  files or consumes an unfinished result from the prior task.

## Coverage Matrix

| Identifier | Task(s) |
| --- | --- |
| BR-01 | T003, T004, T005, T018 |
| BR-02 | T006, T013 |
| BR-03 | T006 |
| BR-04 | T007, T009, T014 |
| BR-05 | T007, T014 |
| BR-06 | T010, T015 |
| BR-07 | T010, T015 |
| BR-08 | T008, T009 |
| BR-09 | T008 |
| BR-10 | T014 |
| BR-11 | T016, T017, T020, T023 |
| C1 | T014, T021 |
| C2 | T007, T009, T014, T021 |
| C3 | T006 |
| C4 | T007 |
| C5 | T007 |
| C6 | T007 |
| C7 | T007 |
| C8 | T010, T015 |
| C9 | T010, T015 |
| C10 | T010, T015 |
| C11 | T010, T015 |
| C12 | T008, T009, T021 |
| C13 | T005, T018, T021 |
| C14 | T003, T004, T021 |
| C15 | T015 |
| C16 | T016, T023 |
| C17 | T014 |
| C18 | T019, T020 |
| C19 | T006 |
| C20 | T018 |
| C21 | T007, T014 |

## Implementation Strategy

### Working Import MVP

Complete Slices 0 through 4 in order. This yields the fully tested synchronous
import endpoint for all three user stories. It is not release-complete until
Slice 5 observability, traceability, documentation, and T027 manual validation
pass.

### Incremental Delivery

1. Retire the persistence risk in Slice 0.
2. Build inward-out through Domain, Persistence, and Use Case.
3. Expose only the fixed API contract in Slice 4.
4. Complete the specified operational surface and manual acceptance in Slice 5.

## Notes

- Every automated task must demonstrate its named test failing before
  implementation.
- T003 may end only with the documented and disabled Hibernate dialect/mapping
  incompatibility that T004 resolves. Every other automated task ends with the
  full `./mvnw verify` gate, not a narrower test command; T027 is manual
  verification after that gate passes.
- Test names use lower-case scenario prefixes such as `c05_...` while preserving
  the exact documented scenario id in traceability.
- If any generated artifact conflicts with `docs/spec.md`, correct the generated
  artifact; never alter the source specification to fit implementation.
