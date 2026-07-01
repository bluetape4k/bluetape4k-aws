# Issue #273 Service Coverage Examples Lesson

## Context

Issue #273 asked for examples that close remaining AWS service coverage gaps
after the service plugins landed.

## Decision

Use one Ktor service coverage module for SES/v2, SNS, CloudWatch, CloudWatch
Logs, Kinesis, and STS. Keep tests deterministic by injecting operation
facades, and document emulator fallback instead of pretending every covered
service has equal emulator support.

## Outcome

The module now compiles, tests route/plugin accessor behavior for all six
service areas, updates the README locale set, refreshes the service coverage
chart, and wires CI/Nightly registration.

## Future Guidance

When adding example coverage for services with uneven emulator behavior, split
the contract clearly:

- deterministic route/plugin tests use injected operations;
- emulator or live AWS tests are explicit compatibility checks;
- README files must say which path is being verified.
