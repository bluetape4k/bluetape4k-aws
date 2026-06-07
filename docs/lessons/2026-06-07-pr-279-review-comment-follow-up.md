# PR 279 Review Comment Follow-Up

## Context

PR #279 was merged after review comments had been submitted. That was a process
miss: the merge gate checked CI and PR body evidence, but did not re-check
unresolved review threads immediately before merge.

The review comments also identified repeated Micrometer magic strings in the
new observability code.

## Decision

Address the review in a separate follow-up PR:

- Centralize Micrometer service names, tag keys, outcomes, and operation names
  as constants.
- Add operation-specific record helper methods for the Ktor S3 Micrometer
  wrapper.
- Keep emitted metric names and tag values stable.

## Outcome

Ktor and Spring Boot Micrometer code now shares stable constants instead of
inline strings across the support and adapter layers. Tests use the same
constants for metric lookup assertions.

## Verification

- Ktor and Spring Boot compile passed.
- Focused Micrometer tests passed.
- Full affected module tests passed: 85 Ktor tests and 195 Spring Boot tests.
- `git diff --check` passed.

## Future Guidance

Before merging any PR after CI turns green, re-read PR reviews and review
threads. Any unresolved or newer user review comment reopens the merge gate, even
if the user had previously said to merge.

Do not leave repeated magic strings in production code or tests. Metric names,
tag keys, tag values, operation names, outcome names, queue/bucket fallbacks, and
other externally observed literals must be promoted to named constants, enums,
property references, or another type-safe representation near the owning
boundary. Keep only one-off literals inline when the literal is genuinely local,
self-explanatory, and not part of a public contract; record that exception in the
review evidence if it is non-obvious.
