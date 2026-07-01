# Issue #272 Ktor Kinesis And STS Implementation Plan

Goal: add Ktor Kinesis and STS helpers on top of existing `aws-java` SDK
wrappers while preserving explicit lifecycle, cancellation, and raw AWS SDK
response contracts.

## Task 1 - RED Tests And Dependency Wiring

- [x] Add `libs.aws2.kinesis` and `libs.aws2.sts` to `aws-ktor` as
  `compileOnly` and `testImplementation`.
- [x] Add Kinesis template tests for request mapping, record `Flow` starting
  positions, cold collection, repeat collection, cancellation, and failed future
  propagation.
- [x] Add STS template tests for caller identity, assume-role, session-token,
  duration validation, cancellation, and failed future propagation.
- [x] Add Kinesis and STS plugin lifecycle tests.
- [x] Run `:bluetape4k-aws-ktor:compileTestKotlin` and record the expected RED
  failures before production implementation.

## Task 2 - Kinesis Ktor Integration

- [x] Extend `AwsKtorCore` with `AwsKtorKinesisAsyncClientCustomizer`.
- [x] Add Kinesis request, stream, starting-position, and flow option models.
- [x] Add `KinesisKtorOperations` and `KinesisKtorTemplate`.
- [x] Add `KinesisKtorRuntime`, `KinesisKtorPluginConfig`, and
  `KinesisKtorPlugin`.
- [x] Keep `recordFlow` single-shard, caller-collected, cold, and cancellable.

## Task 3 - STS Ktor Integration

- [x] Extend `AwsKtorCore` with `AwsKtorStsAsyncClientCustomizer`.
- [x] Add STS request models with duration validation.
- [x] Add `StsKtorOperations` and `StsKtorTemplate`.
- [x] Add `StsKtorRuntime`, `StsKtorPluginConfig`, and `StsKtorPlugin`.
- [x] Preserve raw AWS SDK response objects for identity/session metadata.

## Task 4 - Documentation, Review, And Validation

- [x] Update root and `aws-ktor` README locale pairs for Kinesis and STS.
- [x] Add review and lesson artifacts.
- [x] Run targeted Kinesis/STS tests.
- [x] Run `./gradlew :bluetape4k-aws-ktor:compileTestKotlin --warning-mode all`.
- [x] Run `git diff --check`.
- [ ] Commit with Lore trailers and open a PR linked to #272 with issue
  metadata parity and final `## DoD Status`.

## Verification Matrix

| Requirement | Evidence |
|---|---|
| Kinesis request mapping | `KinesisKtorTemplateTest` |
| Kinesis flow cancellation | `KinesisKtorTemplateTest` |
| STS identity/session mapping | `StsKtorTemplateTest` |
| Ktor plugin lifecycle | `KinesisKtorPluginTest`, `StsKtorPluginTest` |
| Optional SDK dependency | `aws-ktor/build.gradle.kts` |
| README locale parity | root and `aws-ktor` README diffs |
| Final build health | targeted tests, compileTestKotlin, `git diff --check` |
