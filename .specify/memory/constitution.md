<!--
Sync Impact Report
- Version change: template (unratified) → 1.0.0
- Added principles:
  - I. Specification Is Authoritative
  - II. Domain First
  - III. Conservative Decisions
  - IV. Input Is Data
  - V. Test First
  - VI. File-Level Consistency
  - VII. Deliberate Scope
  - VIII. English and Synchronized Documentation
- Added sections: Core Principles, Governance
- Removed sections: unresolved template placeholders
- Templates:
  - ✅ .specify/templates/plan-template.md
  - ✅ .specify/templates/spec-template.md
  - ✅ .specify/templates/tasks-template.md
- Follow-up TODOs: none
-->
# Catalog Consolidation Constitution

## Core Principles

### I. Specification Is Authoritative

`docs/spec.md` MUST be the single source of truth for behavior, contracts, rules,
scenarios, and technical decisions. Generated artifacts MUST reference it and MUST
never contradict it; conflicts MUST be fixed in the generated artifact.

### II. Domain First

Business rules MUST live in a pure domain with no Spring, JPA, or web types.
Dependencies MUST point inward through ports and adapters.

### III. Conservative Decisions

When identity cannot be decided safely, the system MUST write nothing and report
the record. It MUST never guess.

### IV. Input Is Data

Input MUST be treated only as data. Queries MUST use bound parameters, record
contents MUST NOT appear in logs, and error responses MUST NOT expose internal
details.

### V. Test First

Tests MUST be written before implementation. Every scenario id C1 through C21 in
`docs/spec.md` MUST appear in a test name, and a consistency test MUST enforce
complete scenario coverage.

### VI. File-Level Consistency

Any system failure MUST roll back the whole file. Business outcomes MUST be
represented as outcomes and MUST NOT be exceptions.

### VII. Deliberate Scope

Authentication and authorization MUST remain out of scope by decision. Everything
listed as a non-goal in `docs/spec.md` section 12 MUST remain out of scope unless
the specification is explicitly amended first.

### VIII. English and Synchronized Documentation

Code, identifiers, tests, API fields, logs, comments, commits, and documentation
MUST be in English. Documentation MUST be updated in the same change as behavior.

## Governance

This constitution governs generated artifacts and implementation work while
`docs/spec.md` remains authoritative for product behavior. Amendments MUST be
explicit, keep dependent templates synchronized, and use semantic versioning:
MAJOR for incompatible governance changes, MINOR for added or materially expanded
rules, and PATCH for clarifications. Plans and reviews MUST verify compliance with
all eight principles; unresolved conflicts block implementation.

**Version**: 1.0.0 | **Ratified**: 2026-10-02 | **Last Amended**: 2026-10-02
