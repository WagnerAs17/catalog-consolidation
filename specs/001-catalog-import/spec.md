# Feature Specification: Catalog Consolidation Import

**Feature Branch**: `001-catalog-import`

**Created**: 2026-10-02

**Status**: Draft

**Input**: Build the feature specification for the catalog consolidation import
from `docs/spec.md`, sections 1 to 8 and 12 to 14.

**Authority**: `docs/spec.md` is the single source of truth. This artifact
restates its requirements for feature planning and MUST NOT contradict it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Consolidate Seller Products (Priority: P1)

As a catalog operator, I want a seller's product file consolidated into the
canonical catalog so that new products are created once, existing products are
reused safely, and each seller listing points to the correct product.

**Why this priority**: Product identity and seller linking are the feature's
primary business value.

**Independent Test**: Process files that exercise creation, matching,
normalization, brand decisions, seller-code behavior, preserved catalog data,
and sequential records; verify the per-record outcomes and stored relationships.

**Acceptance Scenarios**:

| ID | Given | When | Then | Rule |
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
| C17 | Two sellers send the same new product in the same file | The file is processed | One product is created; the second listing is linked to it | BR-10 |
| C21 | A product created earlier in the same file without brand | A later record has the same name with a brand | Inconclusive (`BRAND_PRESENT_ON_ONE_SIDE_ONLY`) | BR-04, BR-10 |

---

### User Story 2 - Report Invalid and Unsafe Records (Priority: P2)

As a catalog operator, I want invalid, ambiguous, and unsafe input reported
without unsafe writes so that every record is accounted for and valid records can
still be processed.

**Why this priority**: A wrong merge is worse than a missed merge, and malformed
records must not compromise the catalog or the rest of the file.

**Independent Test**: Process files containing missing fields, malicious-looking
text, unreadable or oversized input, symbol-only names, and an empty array; verify
the documented rejection or empty report without unintended catalog changes.

**Acceptance Scenarios**:

| ID | Given | When | Then | Rule |
| --- | --- | --- | --- | --- |
| C13 | A record without name, seller or code | The file is processed | `NOT_PROCESSED` / `INVALID_RECORD` with detail; the other records continue | BR-01 |
| C14 | A field with text like `x'; DROP TABLE Product; --` | The file is processed | Treated as text; the tables stay intact | BR-01 |
| C18 | An unreadable file, not a JSON array, or over the limits | The request arrives | Rejected as a whole, nothing changed, with the reason | Section 4 |
| C19 | A record whose name has only symbols (for example `!!!`) | The file is processed | `INVALID_RECORD` / `NAME_NOT_NORMALIZABLE` | BR-02 |
| C20 | An empty JSON array | The request arrives | HTTP 200 with all totals at zero | Section 4 |

---

### User Story 3 - Preserve Import Consistency (Priority: P3)

As a catalog operator, I want repeated imports to be idempotent and unexpected
failures to leave no partial changes so that the catalog remains consistent.

**Why this priority**: Repeatability and whole-file rollback protect the catalog
after the primary consolidation decisions have been made.

**Independent Test**: Process the same file twice and force a system failure in
the middle of a file; verify the documented outcomes and absence of partial writes.

**Acceptance Scenarios**:

| ID | Given | When | Then | Rule |
| --- | --- | --- | --- | --- |
| C15 | The same file was already processed | It is processed again | Zero creations, zero new links; every record that was linked before is `ALREADY_LINKED`; records that were `NOT_PROCESSED` stay `NOT_PROCESSED` | BR-07 |
| C16 | A system error in the middle of the file | Processing fails | HTTP 500 and nothing from the file is stored | BR-11 |

### Edge Cases

- An input element is not an object.
- Required fields are missing, blank, or of the wrong type.
- Optional fields are null, missing, blank, or of the wrong type.
- A name normalizes to an empty value.
- Only one side of an identity comparison has a brand, or both brands differ.
- A seller code is repeated for the same product or identifies another product.
- Different sellers use the same seller product code.
- A later record depends on a product created earlier in the same file.
- The file is empty, unreadable, not an array, too large, or has too many records.
- An unexpected failure occurs after earlier records have produced writes.

## Requirements *(mandatory)*

### Functional Contract

#### Input Contract

`POST /imports` with `multipart/form-data`. The part is named `file` and holds a
UTF-8 JSON array of objects.

| Field | Required | Rule |
| --- | --- | --- |
| `Id` | Yes | The seller's product code. A JSON string, or a JSON integer (stored as its decimal text). Booleans, decimals (`1.0`), arrays and objects are `WRONG_FIELD_TYPE`. Trimmed, not blank |
| `SellerName` | Yes | String, trimmed, not blank. Compared exactly after trimming: no case folding, so `Acme` and `acme` are different sellers |
| `Name` | Yes | String, trimmed, not blank, and must have a non-empty normalized form |
| `Brand` | No | String. JSON `null`, missing or blank means absent. Any non-string value is `WRONG_FIELD_TYPE` |
| `Category` | No | String. JSON `null`, missing or blank means absent. Any non-string value is `WRONG_FIELD_TYPE` |
| any other field | n/a | Ignored |

Parsing rules:

- The `file` part is read as UTF-8. A leading BOM is accepted. Invalid UTF-8 or
  invalid JSON is rejected as a whole (400).
- Only the request content type (`multipart/form-data`) is checked. The part's own
  content type and file name are ignored.
- The array is read as a JSON tree rather than a typed list, so one malformed
  record cannot fail the whole file.
- No length limit applies per field; the configured maximum body size (default
  5 MB) bounds everything.

File-level limits and errors:

- Maximum body size: the configured maximum through
  `catalog.import.max-body-size` (default 5 MB). Maximum records: the configured
  maximum through `catalog.import.max-records` (default 5,000 records).
- An empty array is valid and returns 200 with zero totals.
- An element that is not an object, or a field with the wrong type, makes only
  that record `INVALID_RECORD`. It never fails the whole file.
- A file that is not a JSON array, cannot be parsed, or exceeds the limits is
  rejected as a whole according to the output error contract.

#### Output Contract

A processed file always returns HTTP 200 with an `ImportReport`, even when some
records are `NOT_PROCESSED`.

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
| `reason`, `detail` | Only for `NOT_PROCESSED`: `INVALID_RECORD`, `INCONCLUSIVE` or `INCONSISTENT`, with a detail code from the business rules |
| `warnings` | List of warning codes; currently only `CATEGORY_DIVERGENCE` |

File-level errors use RFC 9457 `ProblemDetail` (`application/problem+json`) with
the standard `status`, `title` and `detail` fields plus one extra property,
`code`. `detail` is a short generic sentence and never includes record contents
or internal messages. Status codes and `code` values are fixed:

| Status | `code` | When |
| --- | --- | --- |
| 400 | `FILE_UNREADABLE` | Missing `file` part, invalid UTF-8 or invalid JSON |
| 409 | `IMPORT_IN_PROGRESS` | Another import is running |
| 413 | `FILE_TOO_LARGE` | Body over the configured maximum (default 5 MB) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Request is not `multipart/form-data` |
| 422 | `NOT_A_JSON_ARRAY` | Valid JSON that is not an array |
| 422 | `TOO_MANY_RECORDS` | More than the configured maximum (default 5,000 records) |
| 500 | `INTERNAL_ERROR` | Any unexpected exception; the file was rolled back |

No other status codes, codes, or fields are added.

### Business Rules

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

### Per-Record Decision Flow

1. Validate the record under BR-01. An invalid record is `NOT_PROCESSED` with
   reason `INVALID_RECORD`; processing stops for that record.
2. Normalize name and brand under BR-02 and BR-03, then find a product by
   normalized name.
3. Decide identity under BR-04 as `New`, `Existing(product)`, or `Inconclusive`.
4. An inconclusive identity is `NOT_PROCESSED` with reason `INCONCLUSIVE`; nothing
   is written.
5. Find the listing by (`sellerName`, `sellerProductId`):
   - If it points to the identified product, report `ALREADY_LINKED`.
   - If it exists while identity is `New`, or points to another product, report
     `NOT_PROCESSED` with reason `INCONSISTENT`.
   - If it does not exist and identity is `Existing(product)`, create the link and
     report `LINKED_TO_EXISTING`.
   - If it does not exist and identity is `New`, create the product and link and
     report `CREATED_AND_LINKED`.
6. If the product existed and its category differs, add `CATEGORY_DIVERGENCE`.

All writes occur in one file-level transaction. Any unexpected exception rolls
back the whole file under BR-11.

### Scope Boundaries

- The complete non-goals and dependency restrictions in `docs/spec.md` section
  12 apply in full; the summary below does not replace or narrow that list.
- Authentication and authorization are deliberately out of scope.
- The feature only adds products and seller listings; it does not treat a file as
  a full snapshot and does not remove listings that disappear from later files.
- Existing products are never updated or enriched.
- Dry-run mode, report pagination, import-status lookup, and additional read
  endpoints are out of scope.
- Asynchronous processing and queues are out of scope.
- Fuzzy matching, EAN/GTIN handling, price, and stock are out of scope.
- Pre-existing duplicate products in the catalog are out of scope.
- No endpoint, field, status code, error code, or persistent data structure beyond
  the functional contract may be added.
- Caching, retries, scheduled jobs, and optimistic locking are out of scope.
- When the source specification is silent, no behavior is chosen or added.

### Key Entities

- **Product**: The canonical catalog entry, unique by normalized name.
- **SellerProduct**: A seller's code for a product, identified by `sellerName` and
  `sellerProductId`, and pointing to exactly one Product.
- **ImportRecord**: One element of the uploaded array.
- **Identity**: The decision `New`, `Existing(product)`, or
  `Inconclusive(reason)`.
- **Outcome**: `CREATED_AND_LINKED`, `LINKED_TO_EXISTING`, `ALREADY_LINKED`, or
  `NOT_PROCESSED`.
- **RejectionReason**: `INVALID_RECORD`, `INCONCLUSIVE`, or `INCONSISTENT`.
- **WarningCode**: `CATEGORY_DIVERGENCE`.
- **ImportReport**: Totals and exactly one result per input element, with an
  `importId` used only for correlation.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All acceptance scenarios C1 through C21 produce their documented
  Given/When/Then results.
- **SC-002**: Every processed input element produces exactly one result in file
  order, and `totals.records` equals both the input element count and the sum of
  the four outcome totals.
- **SC-003**: Every inconclusive identity produces zero product or listing writes
  and is reported as `NOT_PROCESSED` with the documented reason.
- **SC-004**: Every unexpected system failure produces HTTP 500 and leaves zero
  changes from the entire file.
- **SC-005**: Reprocessing the same successfully linked file produces zero new
  products and zero new links.
- **SC-006**: Files within the configured maximums (defaults: 5 MB and 5,000
  records) are evaluated under the contract; inputs outside the configured
  maximums receive exactly the documented file-level response.

## Assumptions

The source specification resolves the challenge's open questions with these
decisions; none remains a clarification for this feature:

- Product identity uses normalized name and brand; absent brand on both sides
  counts as equal.
- Generic normalization does not equate translations, so translated names remain
  distinct products.
- When product identity is uncertain, the system writes nothing and reports the
  reason.
- The catalog wins on conflict; existing product data is preserved and category
  divergence is reported as a warning.
- A seller listing stores the seller, seller product code, and product
  relationship; price and stock are excluded.
- The seller is declared by each input record.
- Re-sending a file changes nothing that was already linked.
- An invalid record is rejected individually while other records continue.
- The file only adds; it is not a full snapshot.
- Existing duplicate products are assumed absent and are out of scope.
