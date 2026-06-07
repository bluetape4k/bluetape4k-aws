# Issue #230 Micrometer Observability Design

Date: 2026-06-07
Issue: #230

## Goal

Add Micrometer-backed observability adapters for SQS and selected S3 operations
while preserving the existing Spring Boot and Ktor extension contracts.

## Current State

- `aws-spring-boot` already has `api(libs.micrometer.core)` and CloudWatch
  meter publishing uses `MeterRegistry`.
- `aws-ktor` has no Micrometer dependency; it should stay optional with
  `compileOnly(libs.micrometer.core)` and test-only registry support.
- Spring SQS exposes `SqsListenerInterceptor` for receive, handle, and
  acknowledgement phases.
- Ktor SQS exposes `SqsConsumerObserver` / `SqsConsumerObservation`; receive,
  invoke, ack, nack, conversion failure, and retry/failure observations already
  carry operation, outcome, queue URL, duration, and tags.
- Spring S3 exposes `S3Operations`; Ktor S3 exposes `S3KtorClient` without a
  common interface.
- Micrometer Observation API supports low-cardinality key values. Direct
  `Timer` recording remains the lightest fit for coroutine decorators where a
  registry is present.

## Design

### Spring Boot SQS

- Add a Micrometer `SqsOperations` decorator for producer/administrative
  operations.
- Register the decorator from `SqsAutoConfiguration` when `MeterRegistry` is
  present.
- Add a Micrometer `SqsListenerInterceptor` for listener receive, handle, and
  acknowledgement phases.
- Register the listener interceptor automatically when `MeterRegistry` is
  present so common Spring Boot users do not wire it manually.

### Spring Boot S3

- Add a Micrometer `S3Operations` decorator for selected object operations:
  upload, download, delete, list, resource, and presign.
- Register the decorator from `S3AutoConfiguration` when `MeterRegistry` is
  present.

### Ktor SQS

- Add `MicrometerSqsConsumerObserver`, an opt-in bridge from
  `SqsConsumerObservation` to Micrometer timers/counters.
- Add a DSL helper on `SqsConsumerPluginConfig` so users can install it through
  the existing `observer` hook.
- Extend `SqsConsumerRuntime.send` to emit `send` observations so producer
  usage can be measured through the same observer.

### Ktor S3

- Add a lightweight `MicrometerS3KtorClient` wrapper for selected operations
  rather than changing `S3KtorClient` ownership or adding global state.
- Provide `S3KtorClient.withMicrometer(...)` for opt-in usage.

## Metrics

Default meter names:

- `bluetape4k.aws.sqs.operation`
- `bluetape4k.aws.sqs.listener`
- `bluetape4k.aws.s3.operation`
- `bluetape4k.aws.ktor.sqs.operation`
- `bluetape4k.aws.ktor.s3.operation`

Default low-cardinality tags:

- `service`
- `operation`
- `outcome`
- `exception`
- `listener.id` where available
- `queue.name` only when safely derived or configured
- `bucket` only when explicitly enabled by configuration

Queue URLs, message IDs, object keys, receipt handles, and raw exception
messages must not be default tags.

## Non-Goals

- Do not add OpenTelemetry-specific dependencies.
- Do not instrument every S3 multipart helper in this issue.
- Do not introduce global registries.
- Do not force Micrometer as a runtime dependency for `aws-ktor` users.

## Validation

- Dependency checks for Micrometer presence in `aws-spring-boot` and optional
  compile/test scope in `aws-ktor`.
- Focused Spring Boot tests for SQS/S3 decorators and conditional registration.
- Focused Ktor tests for SQS observer mapping, send observation, and S3 wrapper.
- `:bluetape4k-aws-spring-boot:test`
- `:bluetape4k-aws-ktor:test`
- `git diff --check`
