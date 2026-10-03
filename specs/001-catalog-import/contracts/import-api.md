# Import API Contract

`docs/spec.md` sections 4 and 7 are authoritative. This contract defines the only
business endpoint; it does not define additional behavior.

## POST /imports

Synchronous request with `multipart/form-data`. The part name is `file` and its
content is a UTF-8 JSON array of objects. The part's own content type and filename
are ignored.

### Input Fields

| Field | Required | Contract |
| --- | --- | --- |
| `Id` | Yes | JSON string or integer, stored as decimal text; trimmed and not blank. Boolean, decimal, array, and object values are `WRONG_FIELD_TYPE`. |
| `SellerName` | Yes | String, trimmed and not blank; compared exactly after trimming with no case folding. |
| `Name` | Yes | String, trimmed and not blank, with a non-empty normalized form. |
| `Brand` | No | String; null, missing, or blank means absent. Other JSON types are `WRONG_FIELD_TYPE`. |
| `Category` | No | String; null, missing, or blank means absent. Other JSON types are `WRONG_FIELD_TYPE`. |
| Any other field | No | Ignored. |

The service accepts a leading UTF-8 BOM. It parses the root as a JSON tree, not a
typed list. One invalid element produces that record's result and does not reject
the file. The body limit is 5 MB and the record limit is 5,000. An empty array is
valid.

### HTTP 200 ImportReport

A syntactically processable array returns HTTP 200 even when individual records
are `NOT_PROCESSED`.

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

| Field | Contract |
| --- | --- |
| `importId` | UUID generated per request, used only for log correlation and never persisted |
| `totals.records` | Number of array elements and sum of the four outcome totals |
| `results` | Exactly one item per array element, in file order |
| `position` | One-based array position |
| `sellerName`, `sellerProductId` | Echoed input values, or null when unreadable from the record |
| `outcome` | `CREATED_AND_LINKED`, `LINKED_TO_EXISTING`, `ALREADY_LINKED`, or `NOT_PROCESSED` |
| `productId` | Product id for the first three outcomes; null for `NOT_PROCESSED` |
| `reason`, `detail` | Present only for `NOT_PROCESSED`; values are fixed by the business rules |
| `warnings` | Array containing only defined warning codes; currently `CATEGORY_DIVERGENCE` |

#### Result Serialization Rules

- `reason` and `detail` are omitted when `outcome` is not `NOT_PROCESSED`; they
  are present only for `NOT_PROCESSED`.
- `productId` is always present and is null for `NOT_PROCESSED`.
- `sellerName` and `sellerProductId` are always present and are null when the
  corresponding input value is unreadable.
- `warnings` is always present as an array, including when it is empty.

### File-Level Errors

Errors use `application/problem+json` with RFC 9457 standard fields `status`,
`title`, and generic `detail`, plus exactly one extra field, `code`. Record content
and internal exception messages never appear.

| Status | `code` | Condition |
| --- | --- | --- |
| 400 | `FILE_UNREADABLE` | Missing `file` part, invalid UTF-8, or invalid JSON |
| 409 | `IMPORT_IN_PROGRESS` | Another import is running |
| 413 | `FILE_TOO_LARGE` | Body over 5 MB |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Request is not `multipart/form-data` |
| 422 | `NOT_A_JSON_ARRAY` | Valid JSON whose root is not an array |
| 422 | `TOO_MANY_RECORDS` | More than 5,000 array elements |
| 500 | `INTERNAL_ERROR` | Unexpected exception; the file transaction was rolled back |

No other business endpoint, response field, status, or error code is permitted.
