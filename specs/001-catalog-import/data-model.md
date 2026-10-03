# Data Model: Catalog Consolidation Import

This artifact restates `docs/spec.md` sections 3 and 11 for planning. If any text
here conflicts with `docs/spec.md`, the source specification wins.

## Persistent Model

```text
Product                              SellerProduct
-------                              -------------
Id              key, autoincrement   Id               key, autoincrement
Name            text, required       SellerName       text, required
Brand           text, optional       SellerProductId  text (was integer)
Category        text, optional       ProductId        FK to Product.Id
NormalizedName  text, unique
                                     Unique: (SellerName, SellerProductId)
Unique index on NormalizedName       Index on ProductId

Product 1 ---- N SellerProduct
```

### Product

| Field | Storage | Rule |
| --- | --- | --- |
| `Id` | INTEGER primary key, autoincrement | Canonical product identifier |
| `Name` | TEXT, required | Stored trimmed with inner whitespace collapsed for a new product |
| `Brand` | TEXT, optional | Stored trimmed or NULL; existing values are never enriched |
| `Category` | TEXT, optional | Stored trimmed or NULL; existing values are never changed |
| `NormalizedName` | TEXT, unique | Produced only by `TextNormalizer`; nullable during backfill and non-null after successful initialization |

### SellerProduct

| Field | Storage | Rule |
| --- | --- | --- |
| `Id` | INTEGER primary key, autoincrement | Listing identifier |
| `SellerName` | TEXT, required | Trimmed and compared exactly; case is significant |
| `SellerProductId` | TEXT, required | Seller code stored as text |
| `ProductId` | INTEGER, required foreign key | References `Product(Id)` |

### Relationships and Constraints

- One Product has zero or more SellerProduct rows; each SellerProduct points to
  exactly one Product.
- `UX_Product_NormalizedName` allows at most one candidate for a normalized name.
- `UX_SellerProduct_Seller_Code` makes (`SellerName`, `SellerProductId`) unique.
- `IX_SellerProduct_ProductId` supports listing lookup by product.
- The Product foreign key is enforced because every connection enables
  `foreign_keys=ON`.
- No other table, column, constraint, or index is permitted.

## Exact Schema Script

The implementation MUST store and apply this script exactly as written:

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

The script is manual, not a startup migration. It runs only against a copy of
`data/catalog.db`. Hibernate validates the resulting schema.

## Exact Original DDL for Test Fixtures

Integration tests MUST create their temporary file from this DDL exactly as
written, then apply the schema script above:

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

## Startup State Transition

1. A manual operator copies `data/catalog.db` to `data/work/catalog.db`.
2. The exact schema script adds `NormalizedName`, creates the unique index, and
   recreates SellerProduct with the required text code and constraints.
3. The application opens only the path selected by `catalog.db.path`.
4. Hibernate validates the exact schema.
5. `NormalizedNameBackfill` runs in one transaction, finds existing products
   whose value is NULL, computes each value through `TextNormalizer`, and persists
   it. A normalization collision rolls back the transaction, leaving no partially
   filled `NormalizedName` values.
6. The initializer commits only when no collision and no NULL remain. Re-running
   it makes no change.

## Non-Persistent Domain Model

| Type | Meaning |
| --- | --- |
| `ImportRecord` | One framework-neutral raw uploaded array element; preserves its 1-based file position and each field's JSON value type for domain validation without exposing Jackson types |
| `Identity` | Sealed decision: `New`, `Existing(product)`, or `Inconclusive(reason)` |
| `Outcome` | `CREATED_AND_LINKED`, `LINKED_TO_EXISTING`, `ALREADY_LINKED`, or `NOT_PROCESSED` |
| `RejectionReason` | `INVALID_RECORD`, `INCONCLUSIVE`, or `INCONSISTENT` |
| `WarningCode` | `CATEGORY_DIVERGENCE` only |
| `ImportReport` | One result per input element plus totals and the request `importId`; not persisted |

Each raw `ImportRecord` field retains enough type information for the domain to
distinguish a string, integer, decimal number, boolean, null, array, object, or
missing value. BR-01 therefore produces `WRONG_FIELD_TYPE` and the correct
`INVALID_RECORD` result without any Jackson type crossing into the domain. A JSON
integer in `Id` retains its exact decimal text and is never converted through a
floating-point representation.

## Record Outcome Transitions

```text
input element
  ├─ invalid → NOT_PROCESSED / INVALID_RECORD
  └─ valid
      ├─ identity inconclusive → NOT_PROCESSED / INCONCLUSIVE
      └─ identity new or existing
          ├─ listing conflicts → NOT_PROCESSED / INCONSISTENT
          ├─ listing already points to product → ALREADY_LINKED
          ├─ existing product, no listing → LINKED_TO_EXISTING
          └─ new product, no listing → CREATED_AND_LINKED
```

Business outcomes never throw exceptions. Any unexpected exception aborts the
file transaction, so none of its transient state becomes persistent.
