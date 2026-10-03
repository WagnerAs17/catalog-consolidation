# Spec Kit prompts

Run these inside the agent chat (not the terminal), in this order. Command syntax depends on your Spec Kit version: `/speckit-<name>` on newer versions, `/speckit.<name>` on older ones. Codex uses `$speckit-<name>`. Type `/speckit` to see the list.

Before starting: commit `docs/spec.md` and `AGENTS.md`, then run `specify init --here --force --integration claude` and check `git diff` to see what it changed.

## 1. constitution

```
/speckit-constitution Create the project constitution from AGENTS.md and
docs/spec.md. Keep it short (maximum 8 principles). Use these principles:

1. docs/spec.md is the single source of truth. Generated artifacts reference it
   and never contradict it.
2. Domain first: business rules live in a pure domain with no Spring, JPA or web
   types. Dependencies point inward (ports and adapters).
3. Conservative by default: when identity cannot be decided safely, write
   nothing and report the record. Never guess.
4. Input is data, never a command: bound parameters only, no record contents in
   logs, no internal details in error responses.
5. Test first: every scenario id (C1..C21) in the spec has a test whose name
   carries the id; a consistency test enforces it.
6. Consistency of the file: any system failure rolls back the whole file.
   Business outcomes are never exceptions.
7. Deliberate scope: authentication and authorization are out of scope by
   decision, and so is everything in the non-goals list (spec section 12).
8. English everywhere, and documentation is updated in the same change as the
   behavior.

Do not add principles about libraries, frameworks or process beyond these.
```

## 2. specify

```
/speckit-specify Read docs/spec.md, sections 1 to 8 and 12 to 14. Build the
feature specification for the catalog consolidation import from it.

- Keep business rule ids (BR-01..BR-11) and scenario ids (C1..C21) exactly as
  written, and carry the Given/When/Then of each scenario as acceptance
  scenarios.
- Copy the input contract (section 4) and output contract (section 7) as the
  functional contract.
- Do not add requirements, endpoints, fields or behaviors that are not in the
  document.
- Do not include any technology choice. The "how" goes in the plan.
- Mark nothing as [NEEDS CLARIFICATION] that the document already decides.
```

## 3. clarify

```
/speckit-clarify Ask only about gaps that docs/spec.md does not already decide.
If the spec answers a question, use the spec's answer and do not ask me.
```

## 4. plan

```
/speckit-plan Use docs/spec.md sections 9 to 11 as the source, and AGENTS.md for
architecture and conventions.

- Java 21, Maven wrapper, Spring Boot 3.5.x, Spring MVC.
- Spring Data JPA with Hibernate 6 and SQLite (sqlite-jdbc and
  hibernate-community-dialects). Pragmas: foreign_keys ON, WAL, busy_timeout,
  immediate write transactions, pool size 1 for writes.
- Ports and adapters with the package layout in AGENTS.md.
- Schema: use the schema script and the original DDL from spec section 11 exactly
  as written. Do not generate a different data model. No Flyway; a startup
  initializer backfills NormalizedName with TextNormalizer.
- Data files: follow the "Data files" table in AGENTS.md and the "Data files and
  configuration" row in spec section 10. The application reads the database path
  from the property catalog.db.path (default ./data/work/catalog.db). Never
  modify data/catalog.db; tests use temporary files and synthetic fixtures.
- Class names: use exactly the "Class names" table in spec section 3.
- Dependencies: only the allowlist in AGENTS.md.
- Errors: the status codes and `code` values in spec section 7, no others.
- Parse the uploaded array as a JsonNode tree, not a typed list.
- Observability: the Micrometer metric names in spec section 9.
- Tests: JUnit 5, AssertJ, MockMvc, temporary SQLite file for integration tests.
- The first slice is a dialect spike (spec section 10, "First step"), with a
  Spring JDBC fallback if the Hibernate dialect does not work.
- If anything here conflicts with a generated artifact, docs/spec.md wins.
```

## 5. tasks

```
/speckit-tasks Generate tasks in dependency order, grouped in these vertical
slices. Every task that implements behavior must name the scenario ids (C1..C21)
or rule ids (BR-xx) it covers, and the test comes before the code.

Slice 0: scaffold (Maven wrapper, Spring Boot 3.5.x, packages) and the
  SQLite/Hibernate dialect spike: map both tables, insert, read, and the
  malicious-text test (C14). Decide Hibernate vs Spring JDBC here.
Slice 1: domain. TextNormalizer (BR-02, BR-03, C3, C19), Identity decision
  (BR-04, BR-05, C2, C4, C5, C6, C7, C21), validation (BR-01, C13).
Slice 2: persistence. Original DDL fixture, schema script, JPA entities and
  adapters, pragmas, NormalizedName backfill initializer (fails on collision or
  leftover NULL).
Slice 3: use case. Per-record flow (spec section 6), listings (BR-06, BR-07,
  C8..C11, C15, C17), category warning (BR-08, C12), one transaction and
  rollback on system failure (BR-11, C16), one-import-at-a-time lock.
Slice 4: API. POST /imports multipart, report (spec section 7), ProblemDetail
  errors and status codes 400/409/413/415/422/500 (C18, C20).
Slice 5: Micrometer metrics, structured logs with importId, consistency test
  between spec scenario ids and tests, README, and the manual smoke run with
  data/products.json on a fresh working copy of data/catalog.db. Expected
  totals are in spec section 9 (Response time row); a second run must give
  269 ALREADY_LINKED.

Do not add tasks for anything in the non-goals list (spec section 12).
```

## 6. analyze

```
/speckit-analyze Check spec, plan and tasks against docs/spec.md. Report every
rule id and scenario id from the spec that is not covered by a task, every
requirement that is not in the spec, and any conflict. Read-only.
```

## 7. implement

Run one slice at a time and review the diff of each before the next:

```
/speckit-implement Implement Slice 0 only. Stop when ./mvnw verify passes and
tell me the dialect spike result.
```

Repeat for slices 1 to 5. After the last slice run `converge` (or your version's equivalent) and repeat implement/converge until it confirms.

## Review prompt (second agent)

Use a second agent or a fresh session as reviewer after each slice:

```
Review the last slice against docs/spec.md and AGENTS.md. List: behavior that
differs from the spec, scenario ids without a test, anything in the non-goals
list, layers depending in the wrong direction, input concatenated into queries,
and documentation that no longer matches the code. Do not edit files.
```