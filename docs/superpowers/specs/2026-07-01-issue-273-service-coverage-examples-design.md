# Issue #273 Service Coverage Examples Design

## Context

Issue #273 closes the remaining AWS Ktor example coverage gaps after SES/v2, SNS,
CloudWatch, CloudWatch Logs, Kinesis, and STS support landed in the library.
The existing repository pattern is one example module per user-facing Ktor
example area, with root README coverage and CI/Nightly registration.

## Decision

Add `examples/aws-ktor-service-coverage-examples` as one focused module for the
remaining service plugins. The module demonstrates route-level Ktor usage with
operation interfaces injected through the existing plugin configs:

- SES/v2 email send
- SNS topic publish
- CloudWatch metric publish
- CloudWatch Logs event publish
- Kinesis record publish
- STS caller identity lookup

Tests use injected MockK operations instead of external emulators. This keeps
the example deterministic while still proving Ktor plugin installation,
application accessors, request mapping, and response mapping. README files
document that real deployments can pass AWS clients/endpoints and that emulator
coverage depends on the target emulator service support.

## Acceptance Criteria

- `settings.gradle.kts` includes `:aws-ktor-service-coverage-examples`.
- The module compiles and its route tests pass.
- Root `README.md` and `README.ko.md` list the module and matching test command.
- Module `README.md` and `README.ko.md` explain routes, plugin setup, and
  emulator/fallback behavior.
- The service coverage chart marks example coverage for SES/v2, SNS,
  CloudWatch, CloudWatch Logs, Kinesis, and STS.
- CI/Nightly workflows include the new example module.
- `./gradlew projects`, targeted tests, workflow lint, diagram render, and
  `git diff --check` provide completion evidence.
