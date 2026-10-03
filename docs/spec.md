# Catalog Consolidation — Specification

This document is the single source of truth. If any generated artifact (Spec Kit spec, plan, tasks) conflicts with it, this document wins.

## 1. Context and goal

The system receives a products file from a seller and consolidates it into a single SQLite catalog: a new product is inserted and linked to the seller; a product that already exists is not inserted again, it is only linked to the seller.

This document is the specification for spec-driven development. All code, tests, API fields, commit messages and documentation are written in English. Everything the implementation must do is written here; anything not written here is out of scope.

Three ideas drive every decision:

- **Canonical product vs. seller listing.** The catalog holds one canonical Product. Each seller has its own listing (SellerName + SellerProductId) that points to exactly one product.
- **Conservative matching.** When the data does not allow a safe decision, the system writes nothing and reports the record. A wrong merge is worse than a missed one.
- **Every record is accounted for.** Each input element produces exactly one entry in the report.

## 2. Scope

In scope: one synchronous endpoint that receives a JSON products file, consolidates it into the SQLite catalog in a single transaction and returns a per-record report.

| Area | Decision |
| --- | --- |
| Entry point | `POST /imports`, synchronous, one file per request |
| Catalog | Existing SQLite database, adjusted by a manual schema script |
| Matching | Normalized name plus brand compatibility, conservative |
| Catalog data | Preserved: no update or enrichment of existing products |
| Report | One result per input element, with outcome, product id and warnings |
| Observability | Micrometer metrics and structured logs with importId |
| Authentication and authorization | **Out of scope, by deliberate decision** (see below) |

### Authentication and authorization: deliberately out of scope

Authentication and authorization are important and are intentionally not implemented in this delivery. The scope was limited on purpose so the core domain (identity, consolidation, consistency) can be done well. This is a decision, not an oversight.

Risks accepted by this decision:

- Anyone who can reach the endpoint can import.
- The seller is declared inside each record, so a caller can impersonate another seller.
- There is no rate limiting and no TLS termination.

What a real environment needs: a credential per seller, the seller derived from the credential and never from the payload, import permissions, rate limiting and TLS. Until then the service runs only locally.

## 3. Glossary and naming

The same word is used in the business text, the API and the code. Code names are fixed here so agents do not invent synonyms.

| Business term | Code name | Meaning |
| --- | --- | --- |
| Product | `Product` | Canonical catalog entry, unique by normalized name |
| Seller listing (cadastro) | `SellerProduct` | A seller's code for a product: (`sellerName`, `sellerProductId`) pointing to one product |
| Input record | `ImportRecord` | One element of the uploaded array |
| Seller product code | `sellerProductId` | The seller's own code, always handled as text |
| Normalizer | `TextNormalizer` | Pure function that produces the normalized text used for matching |
| Identity decision | `Identity` | Sealed interface: `New`, `Existing(product)`, `Inconclusive(reason)` |
| Record outcome | `Outcome` | `CREATED_AND_LINKED`, `LINKED_TO_EXISTING`, `ALREADY_LINKED`, `NOT_PROCESSED` |
| Rejection reason | `RejectionReason` | `INVALID_RECORD`, `INCONCLUSIVE`, `INCONSISTENT` |
| Warning | `WarningCode` | Currently `CATEGORY_DIVERGENCE` |
| Import report | `ImportReport` | Totals plus one result per record, with an `importId` (UUID) used only for correlation |

The confirmed and authoritative base package is `com.wagneralmeida.catalog`.

### Class names

These names are fixed. Do not rename them or add classes with overlapping responsibilities.

| Layer | Name | Role |
| --- | --- | --- |
| `application/port/in` | `ImportProductsUseCase` | Use case interface: takes a list of `ImportRecord`, returns `ImportReport` |
| `application/port/out` | `ProductRepository`, `SellerProductRepository` | Persistence ports |
| `application/service` | `ImportProductsService` | Use case implementation, owns the per-record flow |
| `application/service` | `ImportLock` | In-memory non-blocking lock (BR-11) |
| `application/service` | `LockingImportProductsUseCase` | Decorator that applies `ImportLock` around the transactional `ImportProductsService` (BR-11). Implements `ImportProductsUseCase` |
| `infrastructure/in/web` | `ImportController`, `ImportResponse`, `ApiExceptionHandler` | `POST /imports`, response DTOs, ProblemDetail mapping |
| `infrastructure/in/web` | `ImportFileParser` | File-level validation and JSON reading (UTF-8, BOM, JSON tree, array check, record limit) |
| `infrastructure/in/web` | `ImportRecordMapper` | `JsonNode` to `ImportRecord` |
| `infrastructure/in/web` | `FileRejection` | Enum of file-level rejections (status, title, detail; the code is the enum name) |
| `infrastructure/in/web` | `FileRejectionException` | Exception carrying a `FileRejection` |
| `infrastructure/in/web` | `ImportIdFilter` | Generates the `importId` UUID per request and sets it in the MDC |
| `infrastructure/out/persistence` (Hibernate path only) | `ProductEntity`, `SellerProductEntity`, `ProductJpaRepository`, `SellerProductJpaRepository`, `ProductRepositoryAdapter`, `SellerProductRepositoryAdapter` | JPA entities, Spring Data repositories and port adapters; the four JPA entity/repository names apply only when Hibernate is selected by the dialect spike |
| `infrastructure/out/persistence` (Spring JDBC fallback only) | `JdbcProductRepositoryAdapter`, `JdbcSellerProductRepositoryAdapter` | Spring JDBC adapters implementing the same `ProductRepository` and `SellerProductRepository` out-ports; no JPA entities or Spring Data repositories |
| `infrastructure/config` | `SqliteConfig`, `NormalizedNameBackfill` | Pragmas and the startup backfill |

### JSON field casing

Input fields follow the challenge file and use PascalCase (`Id`, `SellerName`, `Name`, `Brand`, `Category`). Output fields use camelCase (`sellerProductId`, `sellerName`, ...). This difference is intentional; do not "fix" either side.

## 4. Input contract

`POST /imports` with `multipart/form-data`. The part is named `file` and holds a UTF-8 JSON array of objects.

| Field | Required | Rule |
| --- | --- | --- |
| `Id` | Yes | The seller's product code. A JSON string, or a JSON integer (stored as its decimal text). Booleans, decimals (`1.0`), arrays and objects are `WRONG_FIELD_TYPE`. Trimmed, not blank |
| `SellerName` | Yes | String, trimmed, not blank. Compared exactly after trimming: no case folding, so `Acme` and `acme` are different sellers |
| `Name` | Yes | String, trimmed, not blank, and must have a non-empty normalized form |
| `Brand` | No | String. JSON `null`, missing or blank means absent. Any non-string value is `WRONG_FIELD_TYPE` |
| `Category` | No | String. JSON `null`, missing or blank means absent. Any non-string value is `WRONG_FIELD_TYPE` |
| any other field | n/a | Ignored |

Parsing rules:

- The `file` part is read as UTF-8. A leading BOM is accepted. Invalid UTF-8 or invalid JSON is rejected as a whole (400).
- Only the request content type (`multipart/form-data`) is checked. The part's own content type and file name are ignored.
- The array is parsed as a JSON tree (`JsonNode`), never as a typed list of objects, so one malformed record cannot fail the whole file.
- No length limit per field; the configured maximum body size (default 5 MB) bounds everything.

File-level limits and errors:

- Maximum body size: the configured maximum through `catalog.import.max-body-size` (default 5 MB). Maximum records: the configured maximum through `catalog.import.max-records` (default 5,000 records).
- An empty array is valid and returns 200 with zero totals.
- An element that is not an object, or a field with the wrong type, makes only that record `INVALID_RECORD`. It never fails the whole file.
- A file that is not a JSON array, cannot be parsed, or exceeds the limits is rejected as a whole (see section 7 for status codes and `code` values).

## 5. Business rules

| Id | Rule |
| --- | --- |
| BR-01 | **Validation.** A record is invalid when `Id`, `SellerName` or `Name` is missing, blank, of the wrong type, or when the record is not an object. Detail codes: `NOT_AN_OBJECT`, `MISSING_ID`, `MISSING_SELLER_NAME`, `MISSING_NAME`, `WRONG_FIELD_TYPE`, `NAME_NOT_NORMALIZABLE`. When several apply, only the first in this order is reported, checking `Id`, `SellerName`, `Name`, `Brand`, `Category` for missing/type problems, then the normalized name |
| BR-02 | **Normalization.** Lowercase (`Locale.ROOT`), then Unicode NFD and removal of combining marks, then removal of the characters ' ’ " “ ”, then replacement of every character outside `[a-z0-9]` with a space, then collapse of repeated spaces and trim. `5.5` stays different from `55`. An empty result for the name makes the record invalid (`NAME_NOT_NORMALIZABLE`) |
| BR-03 | **Brand.** Normalized with the same algorithm. An empty normalized brand means absent |
| BR-04 | **Identity.** The normalized name is unique in the catalog, so there is at most one candidate product. If the brands are both absent or equal after normalization, the identity is `Existing(candidate)`. If there is no candidate, it is `New`. If only one side has a brand, or both have different brands, it is `Inconclusive` (`BRAND_PRESENT_ON_ONE_SIDE_ONLY` or `BRAND_MISMATCH`) |
| BR-05 | **Inconclusive writes nothing.** No product and no link is created, and the record is reported as `NOT_PROCESSED` with reason `INCONCLUSIVE` |
| BR-06 | **Seller listing.** A listing is (`sellerName`, `sellerProductId`) and points to exactly one product. Several codes of the same seller may point to the same product. The same code in different sellers is unrelated. A listing is never redirected to another product |
| BR-07 | **Existing listing.** If the listing already points to the identified product, the outcome is `ALREADY_LINKED` and nothing is written. If identity is `New`, or the listing points to a different product, the record is `NOT_PROCESSED` with reason `INCONSISTENT` (`LISTING_LINKED_TO_ANOTHER_PRODUCT`) |
| BR-08 | **Catalog preserved.** Existing products are never updated or enriched. The warning `CATEGORY_DIVERGENCE` is added only when both the record and the stored product have a category and they differ after normalization. If either side has no category, there is no warning |
| BR-09 | **New product data.** Taken from the first occurrence in file order. `Name` is stored trimmed with inner whitespace collapsed; brand and category are stored trimmed, or NULL when absent |
| BR-10 | **Sequential processing.** Records are processed in file order inside one transaction, so a later record sees what earlier records wrote. Precedence of rejections: invalid, then inconclusive, then inconsistent |
| BR-11 | **System failure.** Any unexpected exception (database error, constraint violation, bug) rolls back the whole file and returns HTTP 500. Business outcomes are never exceptions. Only one import runs at a time |

## 6. Per-record decision flow

For each record, in file order:

1. **Validate** (BR-01). Invalid: outcome `NOT_PROCESSED`, reason `INVALID_RECORD`, stop.
2. **Normalize** name and brand (BR-02, BR-03). Look up the product by normalized name.
3. **Decide identity** (BR-04): `New`, `Existing(p)` or `Inconclusive`.
4. **Inconclusive:** `NOT_PROCESSED`, reason `INCONCLUSIVE`, stop. Nothing is written.
5. **Look up the listing** (`sellerName`, `sellerProductId`).
    - Listing exists and points to the identified product: `ALREADY_LINKED`.
    - Listing exists and identity is `New`, or it points to a different product: `NOT_PROCESSED`, reason `INCONSISTENT`.
    - No listing and identity is `Existing(p)`: create the link, outcome `LINKED_TO_EXISTING`.
    - No listing and identity is `New`: create the product and the link, outcome `CREATED_AND_LINKED`.
6. If the product existed and the category differs, add the warning `CATEGORY_DIVERGENCE` to the result.

All writes happen in the file's single transaction; any unexpected exception rolls everything back (BR-11).

## 7. Output contract

A processed file always returns HTTP 200 with an `ImportReport`, even when some records are `NOT_PROCESSED`.

```json
{
  "importId": "6f1c0d52-0b8e-4c3a-9a57-2f0e4f7c1a10",
  "totals": {
    "records": 4,
    "createdAndLinked": 1,
    "linkedToExisting": 1,
    "alreadyLinked": 1,
    "notProcessed": 1
  },
  "results": [
    { "position": 1, "sellerName": "Acme", "sellerProductId": "100", "outcome": "CREATED_AND_LINKED", "productId": 501, "warnings": [] },
    { "position": 2, "sellerName": "Acme", "sellerProductId": "101", "outcome": "LINKED_TO_EXISTING", "productId": 17, "warnings": ["CATEGORY_DIVERGENCE"] },
    { "position": 3, "sellerName": "Acme", "sellerProductId": "102", "outcome": "ALREADY_LINKED", "productId": 17, "warnings": [] },
    { "position": 4, "sellerName": "Acme", "sellerProductId": "103", "outcome": "NOT_PROCESSED", "productId": null, "reason": "INCONCLUSIVE", "detail": "BRAND_MISMATCH", "warnings": [] }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `importId` | UUID generated per request. Used only for correlation in logs; it is not persisted and there is no endpoint to query it |
| `totals` | Counts per outcome. `records` equals the number of array elements and equals the sum of the other four |
| `results[]` | Exactly one entry per input element, in file order |
| `position` | 1-based position of the element in the array |
| `sellerName`, `sellerProductId` | Echo of the input; null when the record was too broken to read them |
| `outcome` | `CREATED_AND_LINKED`, `LINKED_TO_EXISTING`, `ALREADY_LINKED` or `NOT_PROCESSED` |
| `productId` | Product id for the first three outcomes; null for `NOT_PROCESSED` |
| `reason`, `detail` | Only for `NOT_PROCESSED`: `INVALID_RECORD`, `INCONCLUSIVE` or `INCONSISTENT`, with a detail code from section 5 |
| `warnings` | List of warning codes; currently only `CATEGORY_DIVERGENCE` |

File-level errors use RFC 9457 `ProblemDetail` (`application/problem+json`) with the standard `status`, `title` and `detail` fields plus one extra property, `code`. `detail` is a short generic sentence and never includes record contents or internal messages. Status codes and the `code` values are fixed:

| Status | `code` | When |
| --- | --- | --- |
| 400 | `FILE_UNREADABLE` | Missing `file` part, invalid UTF-8 or invalid JSON |
| 409 | `IMPORT_IN_PROGRESS` | Another import is running |
| 413 | `FILE_TOO_LARGE` | Body over the configured maximum (default 5 MB) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Request is not `multipart/form-data` |
| 422 | `NOT_A_JSON_ARRAY` | Valid JSON that is not an array |
| 422 | `TOO_MANY_RECORDS` | More than the configured maximum (default 5,000 records) |
| 500 | `INTERNAL_ERROR` | Any unexpected exception; the file was rolled back |

The limits are configured through `catalog.import.max-body-size` (default 5 MB) and `catalog.import.max-records` (default 5000).

No other status codes, codes or fields are added.

## 8. Acceptance scenarios

Each scenario id is used in test names (for example `c05_...`). A consistency test fails the build when a scenario id listed here has no test.

| # | Given | When | Then | Rule |
| --- | --- | --- | --- | --- |
| C1 | A product with no candidate in the catalog | A seller sends it | The product is created and linked to the seller (`CREATED_AND_LINKED`) | BR-04, BR-07 |
| C2 | A product in the catalog, equivalent name, same or both-absent brand | Another seller sends the same product | No new product; the existing one is linked and its data is unchanged (`LINKED_TO_EXISTING`) | BR-04, BR-08 |
| C3 | A product such as "iPhone 15 Pro" or "Levi's" | It arrives with a double space, an accent, different quotes or as "Levis" | Recognized as the same product | BR-02 |
| C4 | An existing product without brand | The same name arrives, also without brand | Same product; it is linked | BR-04 |
| C5 | An existing product without brand (or with brand) | The same name arrives with a brand (or without) | Inconclusive: nothing written, `BRAND_PRESENT_ON_ONE_SIDE_ONLY` | BR-04, BR-05 |
| C6 | An existing product with a brand | The same name arrives with a different brand | Inconclusive: nothing written, `BRAND_MISMATCH` | BR-04, BR-05 |
| C7 | Similar but distinct names ("iPhone 15" and "iPhone 15 Pro"), or a translation ("Router" and "Roteador") | Both arrive | Separate products; translation is a documented limitation | BR-02, BR-04 |
| C8 | A seller repeats the same code in the same file for the same product | The file is processed | One link; repetitions are `ALREADY_LINKED` | BR-06, BR-07 |
| C9 | A seller uses two codes for the same product | The file is processed | Both codes are kept and linked to the same product | BR-06 |
| C10 | Different sellers use the same code | The files are processed | Each listing is evaluated separately; the code does not tie sellers together | BR-06 |
| C11 | A seller code already linked to a product | The same seller and code arrive identifying another product | `NOT_PROCESSED` / `INCONSISTENT`; the existing link does not change | BR-07 |
| C12 | An existing product with category "Photography" | A seller sends "Photo" | It is linked; "Photography" is kept and `CATEGORY_DIVERGENCE` is reported | BR-08 |
| C13 | A record without name, seller or code | The file is processed | `NOT_PROCESSED` / `INVALID_RECORD` with detail; the other records continue | BR-01 |
| C14 | A field with text like `x'; DROP TABLE Product; --` | The file is processed | Treated as text; the tables stay intact | BR-01 |
| C15 | The same file was already processed | It is processed again | Zero creations, zero new links; every record that was linked before is `ALREADY_LINKED`; records that were `NOT_PROCESSED` stay `NOT_PROCESSED` | BR-07 |
| C16 | A system error in the middle of the file | Processing fails | HTTP 500 and nothing from the file is stored | BR-11 |
| C17 | Two sellers send the same new product in the same file | The file is processed | One product is created; the second listing is linked to it | BR-10 |
| C18 | An unreadable file, not a JSON array, or over the limits | The request arrives | Rejected as a whole, nothing changed, with the reason | Section 4 |
| C19 | A record whose name has only symbols (for example `!!!`) | The file is processed | `INVALID_RECORD` / `NAME_NOT_NORMALIZABLE` | BR-02 |
| C20 | An empty JSON array | The request arrives | HTTP 200 with all totals at zero | Section 4 |
| C21 | A product created earlier in the same file without brand | A later record has the same name with a brand | Inconclusive (`BRAND_PRESENT_ON_ONE_SIDE_ONLY`) | BR-04, BR-10 |

## 9. Non-functional requirements

Each requirement states how it is verified. Authentication and authorization are the last row, deliberately not covered.

| Topic | Requirement | How it is verified |
| --- | --- | --- |
| Integrity | A system failure leaves no partial change from the file | Test that forces a failure mid-file and checks the database is untouched (C16) |
| Idempotency | Re-sending the same file creates no product and no link, guaranteed by database uniqueness (normalized name; seller + code) and by checking before inserting | Re-send test (C15) |
| Concurrency | One import at a time through an in-memory non-blocking lock released in `finally`; a concurrent request gets HTTP 409. Valid for a single instance | Test with two parallel imports |
| Input limits | Body and record limits use the configured maximums (defaults: 5 MB and 5,000 records). A body over the configured maximum returns HTTP 413. A valid JSON array with more than the configured maximum records, or JSON that is not an array, returns HTTP 422. Unreadable or missing `file` part: 400. Wrong content type: 415 | Tests for each status (C18) |
| Content safety | All text is data, never a command. Error responses do not expose internal details; record contents are never logged | C14 and inspection of error responses |
| Traceability | Every import has an `importId` and a report with one result per record; the sum of the totals equals `records` | Test that checks the sum |
| Observability | Micrometer metrics: `catalog.import.records` (tag `outcome`), `catalog.import.rejections` (tag `reason`), `catalog.import.duration`, `catalog.import.failures` (HTTP 500 only) and `catalog.import.file.rejections` (tag `code`, one of the 4xx codes in section 7). All five instruments, with every allowed fixed-enum `outcome`, `reason` and `code` tag value, are registered at application startup so they exist with value 0 before the first import. Record counters (`records`, `rejections`) are incremented only after the file's transaction commits, so a rolled-back file counts nothing there. No seller or seller-product-code tags are added. Structured logs carry `importId` in MDC. Only `health` and `metrics` are exposed | Test startup registration and that counters match the report |
| Health | `GET /actuator/health` is liveness only and must respond promptly while an import is running. The database health indicator is disabled with `management.health.db.enabled=false` because writes use a single connection | Test health while an import is held open |
| Response time | Synchronous, with no timeout of its own in this version; the configured maximum record count (default 5,000 records) bounds the work. Larger volumes need the asynchronous evolution | Manual smoke run with `data/products.json` (269 records) on a fresh working copy of `data/catalog.db`. Expected report totals: 269 records, 3 `CREATED_AND_LINKED`, 265 `LINKED_TO_EXISTING`, 1 `ALREADY_LINKED`, 0 `NOT_PROCESSED`, and 1 `CATEGORY_DIVERGENCE` warning. A second run of the same file must give 269 `ALREADY_LINKED` |
| Authentication and authorization | Out of scope by deliberate decision; see section 2 | Not verified in this version |

## 10. Technical decisions

| Topic | Decision | Reason |
| --- | --- | --- |
| Language and build | Java 21, Maven (`./mvnw verify`) | Records and sealed interfaces model the decisions |
| Framework | Spring Boot 3.5.x (latest 3.x patch) with Spring MVC | Chosen by the owner; the rationale is explained by the owner. Note: 3.x free support ended in June 2026 |
| Architecture | Ports and adapters: `domain` (pure rules), `application` (`port/in`, `port/out`, `service`), `infrastructure` (`in/web`, `out/persistence`, `config`) | Business rules do not depend on web or database and are testable without Spring |
| Persistence | Spring Data JPA with Hibernate 6. Entities only in `infrastructure`, explicit `@Table`/`@Column` names, `ddl-auto=validate` | Parameter binding protects against injection. Concatenated queries stay forbidden |
| Database | SQLite through `org.xerial:sqlite-jdbc` and `hibernate-community-dialects` (version aligned with Hibernate). Pragmas: `foreign_keys=ON`, WAL, `busy_timeout`, immediate write transactions, pool size 1 for writes | One writer at a time; SQLite has foreign keys off by default |
| First step | Dialect spike: map both tables, insert, read and run the malicious-text test. Fall back to Spring JDBC only for Hibernate dialect or mapping incompatibility. A failure of `foreign_keys`, WAL, `busy_timeout` or IMMEDIATE transactions blocks both persistence paths: stop and report it; do not switch | Dialect compatibility is not confirmed; required SQLite behavior is shared by both paths |
| Schema | Versioned SQL script run manually, no Flyway (section 11). Hibernate only validates. Works on a copy of `catalog.db` | The schema belongs to the case; the unique index is created before the backfill to expose collisions |
| Normalized name | A startup initializer backfills `NormalizedName` of existing products with the Java `TextNormalizer` in a single transaction. It is idempotent. On a collision or any remaining NULL, nothing is changed and application startup fails | The rule lives in one place (SQLite cannot strip accents) |
| Data files and configuration | `data/catalog.db` (original, committed, read-only) and `data/products.json` (challenge sample, committed, manual smoke run only). The schema script is applied to a git-ignored working copy, `data/work/catalog.db`, which the application opens through the property `catalog.db.path` (default `./data/work/catalog.db`). Import limits use `catalog.import.max-body-size` (default 5 MB) and `catalog.import.max-records` (default 5000). Tests use temporary files and synthetic fixtures and never depend on `data/` | Keeps the original intact and the build independent of local files |
| Input | Multipart `file` part; size limit by configuration, record limit in code; validation per record | One bad record must not fail the request |
| Errors | `ProblemDetail`: 400, 413, 415, 422, 409, 500 as in section 7 | Distinguish file, concurrency and system errors |
| Observability | Micrometer through Actuator, only `health` and `metrics`, local use | Consistent with leaving authentication out |
| Tests | JUnit 5 and AssertJ. Domain without Spring; MockMvc for the API; integration with a temporary SQLite file built from the original DDL plus the schema script; synthetic fixtures. Test names carry scenario ids (C1...) and a consistency test checks every scenario id has a test. Tests do not declare implementations of production interfaces (no hand-written classes, anonymous classes or lambdas that implement an application port). Mockito mocks and spies are allowed to isolate a collaborator whose real implementation does not exist yet, and to inject a failure or block execution on top of the real adapter (rollback and concurrency scenarios). When the real implementation exists, tests that verify business behavior use it: the real services and the real persistence adapters over a temporary SQLite file, because mocks do not reproduce database constraints or transactions. When a real implementation becomes available in a later slice, tests that mocked it switch to it. Pure domain tests need no doubles. Production code contains no test-only implementations (no no-op or in-memory variants). | In-memory SQLite with a pool creates one database per connection. Slower tests; mitigated by copying a template database per test. |
| Code language | English for code, tests, API fields, docs and commits | One standard across the repository |

## 11. Data model

The case provides a SQLite catalog with two tables. Two changes are made, by a manual script run on a copy of `catalog.db`: `Product` gains a unique `NormalizedName`, and `SellerProduct` is recreated with a text code and uniqueness per seller and code.

```
Product                              SellerProduct
-------                              -------------
Id              key, autoincrement   Id               key, autoincrement
Name            text, required       SellerName       text, required
Brand           text, optional       SellerProductId  text (was integer)   <- changed
Category        text, optional       ProductId        FK to Product.Id
NormalizedName  text, unique  <- new
                                     Unique: (SellerName, SellerProductId)
Unique index on NormalizedName       Index on ProductId

Product 1 ---- N SellerProduct
```

Each listing points to exactly one product; a product has many listings.

### Constraints

| Constraint | Purpose |
| --- | --- |
| `UX_Product_NormalizedName` (unique) | At most one product per normalized name, so identity has at most one candidate (BR-04). NULL values may coexist until the backfill runs |
| `UX_SellerProduct_Seller_Code` (unique on `SellerName`, `SellerProductId`) | A listing exists once; re-sending a file cannot duplicate it (BR-06, BR-07) |
| `IX_SellerProduct_ProductId` | Fast lookup of listings by product |
| Foreign key `ProductId` to `Product.Id` | Enforced only with `PRAGMA foreign_keys=ON`, which SQLite leaves off by default; the application turns it on for every connection |

### Schema script

```sql
ALTER TABLE Product ADD COLUMN NormalizedName TEXT;
CREATE UNIQUE INDEX UX_Product_NormalizedName ON Product (NormalizedName);
DROP TABLE SellerProduct;
CREATE TABLE SellerProduct (
    Id              INTEGER PRIMARY KEY AUTOINCREMENT,
    SellerName      TEXT    NOT NULL,
    SellerProductId TEXT    NOT NULL,
    ProductId       INTEGER NOT NULL REFERENCES Product (Id),
    CONSTRAINT UX_SellerProduct_Seller_Code UNIQUE (SellerName, SellerProductId)
);
CREATE INDEX IX_SellerProduct_ProductId ON SellerProduct (ProductId);
```

The script was validated on a copy of the provided database: many NULL `NormalizedName` values coexist, collisions are blocked, a duplicate (seller, code) is blocked, two codes of one seller can point to the same product, and the same code can exist in different sellers. `SellerProduct` is recreated because the case has no rows in it that must be preserved. After the script, the startup initializer fills `NormalizedName` in a single transaction (section 10). On a collision or any remaining NULL, the transaction rolls back so nothing is changed, and application startup fails.

### Original DDL (for test fixtures)

Integration tests build the temporary database from the original DDL, then apply the schema script above.

```sql
CREATE TABLE Product (
    Id       INTEGER PRIMARY KEY AUTOINCREMENT,
    Name     TEXT NOT NULL,
    Brand    TEXT,
    Category TEXT
);
CREATE TABLE SellerProduct (
    Id              INTEGER PRIMARY KEY AUTOINCREMENT,
    SellerName      TEXT NOT NULL,
    ProductId       INTEGER CONSTRAINT FK_Product_Id REFERENCES Product (Id) NOT NULL,
    SellerProductId INTEGER NOT NULL
);
```

## 12. Non-goals

Agents must not add any of the following. If one seems necessary, stop and ask.

- Spring Security, authentication or authorization (section 2)
- Flyway or any migration tool
- Lombok, MapStruct, Docker, Testcontainers
- OpenAPI or springdoc
- Dry-run mode, report pagination
- An import-status or GET endpoint; `importId` is only for correlation and is not persisted
- Asynchronous processing or queues
- Updating or enriching existing products
- Fuzzy matching, EAN/GTIN handling, price or stock
- Any endpoint other than `POST /imports` and the Actuator `health` and `metrics` endpoints
- Any field, status code, error code, table, column or index not defined in this document
- CORS configuration, caching, retries, scheduled jobs, optimistic locking
- Dockerfiles, shell scripts or build plugins beyond what `./mvnw verify` needs
- A README beyond the one described in the last slice
- Any dependency outside the allowlist in AGENTS.md

When this document is silent, stop and ask; do not choose.

## 13. Challenge ambiguities and decisions

Eleven ambiguities in the challenge statement, each with a recorded decision.

| Ambiguity | Decision | Where |
| --- | --- | --- |
| What defines a duplicate | Normalized name and brand equal; brand absent on both sides counts as equal | BR-02, BR-04 |
| Variation between sellers | Generic normalization; translations become distinct products, as a documented limit | BR-02, section 15 |
| Which version wins on conflict | The catalog is preserved; a divergent category becomes a warning | BR-08 |
| Seller-specific data (price, stock, code) | Only the seller code is stored; price and stock are out | Section 2, BR-06 |
| File format and seller identification | JSON uploaded as multipart; seller inside each record; required fields defined | Section 4 |
| Repetition (same file, same product in the file) | Database uniqueness plus a check before inserting; re-sending changes nothing | BR-06, BR-07 |
| Invalid records and failures | An invalid record is rejected with a reason and the rest continues; a system failure rolls back the file | BR-01, BR-11 |
| Doubt whether it is the same product | Write nothing and report the reason (a third class beyond new and duplicate) | BR-04, BR-05 |
| Duplicates already in the catalog | Out of scope | Section 2 |
| System interface | Synchronous HTTP API with multipart upload | Section 10 |
| Database changes | Normalized-name column with a unique index; link table recreated with text code and uniqueness per seller and code | Section 11 |

## 14. Questions for the recruiter

Seven questions validate the highest-impact assumptions. Without an answer we proceed with the stated default and record it in the README.

| Priority | Question | Default without an answer |
| --- | --- | --- |
| High | Is there a unique product identifier (EAN/GTIN, manufacturer code) or only the text description? | Normalized name and brand |
| High | When it is uncertain that two records are the same product, should the system write nothing and report, or create a new product? | Write nothing and report the reason |
| High | What should the seller-product link store besides who sells it (price, stock, seller code)? | Seller, seller code and product |
| Medium | Should seller data update or complete an existing product? | No, the catalog is preserved |
| Medium | Does the file only add products, or is it a full snapshot that would allow deactivating what disappeared? | Only adds |
| Medium | With an invalid record, reject only the record or the whole file? | Only the record |
| Low | Does the current catalog contain duplicates we should handle? | Assume not; out of scope |

## 15. Limitations and evolution

The solution takes the simplest safe path for each point. The table shows what stays open and how a mature marketplace would advance.

| Today | Limitation | Evolution |
| --- | --- | --- |
| Identity by normalized name and brand | Equal products written very differently (translation, abbreviation) become new products | EAN/GTIN as primary key; text similarity with a human review queue |
| Fixed normalization rule | Changing the rule requires recomputing the normalized name of every product | Version the rule and recompute in a controlled way |
| Inconclusive writes nothing | The fix happens outside the system and the file is re-sent | Curation queue and manual merge/split with history |
| Result only in the response | The report exists in the response and the log; there is no table of rejected records or import results | Import results (audit) table with the `importId` and later lookup |
| Catalog preserved | Seller data does not improve the catalog; a new product uses the first occurrence in the file | Enrichment with provenance per field and seller-confidence priority |
| Minimal link | No price, stock or condition | Full offer, with its own price and stock flows |
| The file only adds | What the seller stopped selling stays linked | File as a full snapshot, deactivating what disappeared |
| Synchronous API, one import at a time | Not for large volumes or simultaneous uploads; the in-memory lock holds for one instance | Asynchronous processing (202 and result lookup), batch queue, database with concurrent writes |
| No authentication or authorization | Any client that reaches the API can import; a seller can send records on behalf of another | Credential per seller, seller derived from the credential, permissions, rate limiting and TLS |
| Seller name as text | Spelling variations create "different" sellers | Seller table, identified by authentication |

For production, measurable targets would be defined: recovery after failure, report and log retention, real capacity and deadlines, and protection of files and database. Authentication and authorization come before any exposure of the API. None of these change the functional rules above.
