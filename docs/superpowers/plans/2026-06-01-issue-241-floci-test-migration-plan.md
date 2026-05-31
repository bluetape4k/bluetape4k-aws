# Issue 241 Floci Test Migration Plan

Issue: [#241](https://github.com/bluetape4k/bluetape4k-aws/issues/241)
Date: 2026-06-01

## Scope

Migrate LocalStack-default AWS tests to a Floci default while preserving
LocalStack as an explicit fallback for API coverage gaps.

MiniStack is not part of this implementation. It remains a comparison backend
for modules that already expose a MiniStack selector, because automatic fallback
chains can hide the failing emulator and lose test state across containers.

## Tasks

1. Change AWS-emulator-aware test tasks to default `bluetape4k.aws.emulator` to
   `floci`.
2. Replace direct shared `LocalStackServer` test fixtures with an
   `AwsEmulatorServer` selector that supports `floci` and `localstack`.
3. Keep legacy `localStackServer` fixture names as aliases to minimize call-site
   churn.
4. Gate Floci-unsupported KMS grant/key-state APIs and SNS phone opt-out APIs
   with JUnit assumptions, so LocalStack explicit runs still verify them.
5. Remove LocalStack-specific Spring test placeholders from the Java DynamoDB
   food example and inject emulator endpoint/credentials dynamically.
6. Migrate Ktor and AWS example module direct LocalStack fixtures to the same
   Floci-first selector where they are emulator-aware.
7. Update README emulator policy and capture verification evidence.

## Verification

- Compile Java/Kotlin test sources.
- Run full Java SDK wrapper tests with default Floci.
- Run full Kotlin SDK wrapper tests with default Floci.
- Run affected Ktor and AWS example module tests with default Floci.
- Run focused LocalStack fallback smoke tests for KMS grant/key-state and SNS
  phone opt-out behavior.
- Run `git diff --check`.

## Known Gaps

Class names that historically include `LocalStack` are left unchanged to keep
the diff focused. Their runtime fixture now selects Floci by default.
