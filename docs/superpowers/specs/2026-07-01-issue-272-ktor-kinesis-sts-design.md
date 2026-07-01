# Issue #272 Design - Ktor Kinesis And STS Integration

## Context

Issue #272 targets milestone `0.6.0`. `aws-java` already provides focused
AWS SDK v2 coroutine helpers for Kinesis and STS, and `aws-spring-boot`
already exposes Kinesis stream operations and an explicit single-shard
record `Flow`. `aws-ktor` should expose the same runtime-safe service surface
without depending on Spring Boot.

## Evidence

- `aws-ktor` service plugins use an `Operations` interface, SDK-backed
  `Template`, `Runtime`, `PluginConfig`, and Ktor `Application` accessors.
- `AwsKtorCore` owns shared Java SDK v2 region, endpoint, credentials, and
  service builder customizers.
- `aws-spring-boot` Kinesis keeps the long-running consumer explicit: one
  shard, caller-collected cold `Flow`, no lease coordination, and cancellation
  through `CompletableFuture.await()`.
- STS helpers are identity/session requests, not background runtime work. They
  should return raw AWS SDK responses so callers keep account, ARN, assumed-role
  credentials, and session metadata.

## Goals

1. Add Ktor Kinesis operations and plugin lifecycle support.
2. Add Ktor STS identity/session operations and plugin lifecycle support.
3. Reuse existing `aws-java` coroutine adapters where their contracts match.
4. Keep Kinesis consumption explicit, cold, cancellable, and single-shard.
5. Keep service SDK dependencies optional for consumers.
6. Update English and Korean README coverage.

## Non-Goals

- Do not add Kinesis Client Library lease coordination or checkpoint storage.
- Do not add hidden background consumers, automatic retry publishing, or
  listener containers.
- Do not add live-AWS tests.
- Do not make STS request-scoped identity a Ktor authentication provider in
  this issue.

## Selected Design

### Kinesis

Add package `io.bluetape4k.aws.ktor.kinesis`.

Public API:

- `KinesisKtorOperations`
- `KinesisKtorTemplate`
- `KinesisKtorPluginConfig`
- `KinesisKtorRuntime`
- `KinesisKtorPlugin`
- `Application.kinesis()`
- `Application.kinesisOrNull()`
- request/option value objects for record publishing, shard iterators, stream
  declarations, and record `Flow`.

The template delegates simple stream and publishing calls to `aws-java`
coroutine helpers. `recordFlow` is implemented locally because it is a Ktor
application lifecycle concern and must remain Spring-free.

### STS

Add package `io.bluetape4k.aws.ktor.sts`.

Public API:

- `StsKtorOperations`
- `StsKtorTemplate`
- `StsKtorPluginConfig`
- `StsKtorRuntime`
- `StsKtorPlugin`
- `Application.sts()`
- `Application.stsOrNull()`
- `StsAssumeRoleRequest`
- `StsSessionTokenRequest`

The template maps Ktor-local request objects to AWS SDK v2 requests and awaits
the backing futures. It validates the standard STS duration ranges locally
because the current `aws-java` duration validators are internal.

## Acceptance Criteria

- Kinesis put/get/flow request mapping is tested.
- Kinesis `recordFlow` is cold and cancels a pending AWS future on coroutine
  cancellation.
- STS caller identity, assume role, and session token request mapping is
  tested.
- Kinesis and STS plugin tests cover injected operations, disabled accessors,
  application-owned clients, plugin-owned client closure, and shared versus
  service customizer order.
- `AwsKtorCore` exposes Kinesis and STS shared customizers.
- `aws-ktor` declares Kinesis and STS SDK dependencies as `compileOnly` plus
  `testImplementation`.
- README locale pairs document Kinesis and STS Ktor coverage.

## DoD

- Spec and plan exist before production source edits.
- #272 remains assigned to `debop` with milestone `0.6.0`.
- `git diff --check` passes.
- Targeted Kinesis/STS Ktor tests pass.
- `./gradlew :bluetape4k-aws-ktor:compileTestKotlin --warning-mode all` passes.
- PR metadata mirrors issue assignee, milestone, and labels with final
  `## DoD Status`.
