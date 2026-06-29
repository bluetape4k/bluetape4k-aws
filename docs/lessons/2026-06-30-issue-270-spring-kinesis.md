# Issue #270 Spring Kinesis Lesson

## Context

Issue #270 added Spring Boot 4 Kinesis support to `bluetape4k-aws-spring-boot`.
The repo already had Java SDK v2 coroutine Kinesis helpers and Kotlin SDK
Kinesis flows, but Spring adapters consistently wrap Java SDK v2 async clients.

## Decision

Use `KinesisAsyncClient` plus `KinesisOperations` and keep the Spring surface as
explicit operations: stream creation, record publish, shard iterator lookup,
bounded `GetRecords`, and a cold single-shard `Flow<Record>`.

Do not add `@KinesisListener` or checkpoint/lease management in this PR. Those
semantics need a separate design because they define application ownership,
checkpoint storage, failure recovery, and shard coordination.

## Outcome

- Added Kinesis auto-configuration gated by classpath and
  `bluetape4k.aws.kinesis.enabled`.
- Added named request values and configurable Flow polling/retry options.
- Added unit tests for conditional beans, property binding, request mapping,
  Flow coldness, repeated collection, EOF, failure propagation, and cancellation.
- Added Floci emulator coverage for create, put, describe, and Flow collection.
- Updated root/module README files in English and Korean plus the service
  coverage chart.

## Verification

- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*Kinesis*' --no-configuration-cache`: 22 passing.
- `./gradlew :bluetape4k-aws-spring-boot:test --no-configuration-cache`: 243 passing.
- `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin --warning-mode all --no-configuration-cache --rerun-tasks`: BUILD SUCCESSFUL.
- SVG parse and PNG regeneration passed for `bluetape4k-aws-service-coverage-chart-05`.

## Future Work

If listener support is added later, start with a design for shard lease
coordination, checkpoint persistence, backpressure, retry/DLQ behavior, and
shutdown semantics before introducing annotations or containers.
