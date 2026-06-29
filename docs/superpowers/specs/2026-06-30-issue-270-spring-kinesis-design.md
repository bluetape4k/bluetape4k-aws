# Issue #270 Spring Boot Kinesis Design

Date: 2026-06-30
Issue: #270 `feat(aws-spring-boot): add Kinesis auto-configuration and operations`
Milestone: 0.5.0
Repository: `bluetape4k-aws`

## Problem

`bluetape4k-aws-spring-boot` currently provides Spring Boot 4 auto-configuration and operations for S3, SQS, SNS, SES, DynamoDB, CloudWatch, IMDS, KMS, Secrets Manager, and Parameter Store, but the service coverage chart still marks Kinesis as unsupported for the Spring Boot module.

The core modules already have Kinesis support:

- `aws-java` exposes AWS SDK v2 `KinesisClient` / `KinesisAsyncClient` factories and coroutine extensions under `io.bluetape4k.aws.kinesis`.
- `aws-kotlin` exposes native suspend helpers and `recordFlow` under `io.bluetape4k.aws.kotlin.kinesis`.
- `aws-spring-boot` already depends on `:bluetape4k-aws-java` as `api` and follows an `AsyncClient + Operations + CoroutinesTemplate + Properties + AutoConfiguration` pattern for services such as SQS and SNS.

The gap is the Spring Boot integration layer: users should be able to inject Kinesis clients and coroutine-oriented operations without hand-writing Spring beans.

## Current Evidence

- Issue #270 requires Kinesis auto-configuration and operations, tests for conditional beans/property binding/representative paths, emulator-backed tests when reliable, and README chart updates.
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` currently registers many service auto-configurations but no Kinesis auto-configuration.
- `aws-spring-boot/build.gradle.kts` currently declares many AWS SDK v2 services as `compileOnly`/`testImplementation` but not `libs.aws2.kinesis`.
- `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg` marks `aws-spring-boot` + `Kinesis` as `-`.
- Baseline verification before this design: `./gradlew :bluetape4k-aws-spring-boot:test --no-configuration-cache` passed with 221 tests.
- Spring Boot 4.1 documentation supports the existing pattern: `@AutoConfiguration`, classpath conditions, configuration properties, imports registration, and `ApplicationContextRunner` slice tests.
- AWS SDK v2 documentation and local code confirm service clients are configured through builder methods such as `region`, `endpointOverride`, and `credentialsProvider`.

## Constraints

- Keep `bluetape4k-aws-spring-boot` thin over existing core module contracts.
- Use AWS Java SDK v2 `KinesisAsyncClient` for Spring Boot auto-configuration, matching the existing S3/SQS/SNS/KMS module style.
- Do not introduce an awspring dependency or Spring Cloud Stream/Kinesis Binder compatibility layer.
- Keep service SDK dependencies `compileOnly`; applications must carry runtime Kinesis SDK dependencies.
- Apply shared AWS defaults from `AwsProperties` and service-specific overrides from `KinesisProperties`.
- Support global `AwsAsyncClientCustomizer` and service-specific `AwsClientCustomizer<KinesisAsyncClientBuilder>`.
- Public APIs need English KDoc.
- README changes must update both `README.md` and `README.ko.md`, plus `aws-spring-boot/README.md` and `aws-spring-boot/README.ko.md` when module-facing docs change.
- README service coverage chart updates require SVG and PNG regeneration/visual validation.

## Design Options

### Option A: Java SDK v2 AsyncClient Operations

Add `KinesisAutoConfiguration`, `KinesisProperties`, `KinesisOperations`, and `KinesisCoroutinesTemplate` backed by `KinesisAsyncClient`.

The operations surface covers common producer, stream metadata, shard iterator, polling, and single-shard Flow consumption paths:

- `createStream`
- `deleteStream`
- `describeStream`
- `putRecord`
- `putRecords`
- `getShardIterator`
- `getRecords`
- `recordFlow`

`recordFlow` is a cold Flow that polls one shard using `GetRecords`, emits records, advances `nextShardIterator`, stops when the shard closes, and propagates cancellation immediately.

This is the recommended option because it matches existing Spring Boot service patterns and avoids adding a second AWS SDK family to the Spring Boot API surface.

### Option B: Wrap `aws-kotlin` `recordFlow`

Reuse the existing AWS Kotlin SDK `KinesisClient.recordFlow` directly from Spring Boot.

This would reuse mature Flow behavior, but it would make the Spring Boot module expose a Kotlin SDK client alongside Java SDK v2 clients. Existing Spring auto-configurations consistently use Java SDK v2 async clients, so this would make dependency and customizer behavior less predictable for Spring users.

Rejected for this PR.

### Option C: SQS-Style Annotation Listener Runtime

Add `@KinesisListener`, listener container registry, converter hooks, retry/backoff, and observability similar to SQS.

This is useful eventually, but it is a larger runtime with ordering, checkpointing, resharding, lease coordination, and failure semantics that should not be bundled into the first Kinesis Spring Boot support PR.

Rejected for this PR. The initial PR should expose operations and a simple shard Flow. A follow-up issue can define annotation listener/checkpoint semantics.

## Selected Design

Implement Option A.

### Components

- `KinesisProperties`
  - Prefix: `bluetape4k.aws.kinesis`
  - Fields: `enabled`, `region`, `endpointOverride`, `streams`, `consumer`
  - `streams` maps configured stream names to `shardCount` for configuration-driven stream creation.
  - Advanced create-stream options stay on the raw SDK client for this PR so the Spring facade does not over-model Kinesis service features before they are needed.
  - `consumer` holds safe defaults for Flow polling: `batchLimit`, `pollInterval`, `emptyBackoff`, retry limits, and throttle backoff.

- `KinesisAutoConfiguration`
  - Registered in `AutoConfiguration.imports`.
  - Guarded by `@ConditionalOnClass(name = ["software.amazon.awssdk.http.async.SdkAsyncHttpClient", "software.amazon.awssdk.services.kinesis.KinesisAsyncClient"])`.
  - Guarded by `@ConditionalOnProperty(prefix = "bluetape4k.aws.kinesis", name = ["enabled"], havingValue = "true", matchIfMissing = true)`.
  - Registers `KinesisAsyncClient` with shared defaults, credentials, optional async HTTP client, global customizers, and service customizers.
  - Registers `KinesisOperations` as `KinesisCoroutinesTemplate`.

- `KinesisOperations`
  - Public coroutine API for Spring applications.
  - Keeps AWS SDK response types so advanced users do not lose service metadata.
  - Uses request data classes only where they reduce same-typed parameter mistakes.

- `KinesisCoroutinesTemplate`
  - Delegates to existing `io.bluetape4k.aws.kinesis` coroutine extensions when those functions already express the contract.
  - Adds Spring-oriented configuration-driven stream creation and Flow polling.
  - Rethrows `CancellationException` before broad exception handling.
  - Uses `currentCoroutineContext().ensureActive()` inside polling loops.

### API Shape

The operations API should be explicit and small:

```kotlin
interface KinesisOperations {
    suspend fun createStream(streamName: String, shardCount: Int = 1): CreateStreamResponse
    suspend fun createConfiguredStream(streamName: String): CreateStreamResponse
    suspend fun deleteStream(streamName: String): DeleteStreamResponse
    suspend fun describeStream(streamName: String): DescribeStreamResponse
    suspend fun putRecord(request: KinesisPutRecordRequest): PutRecordResponse
    suspend fun putRecords(streamName: String, entries: List<PutRecordsRequestEntry>): PutRecordsResponse
    suspend fun getShardIterator(request: KinesisShardIteratorRequest): GetShardIteratorResponse
    suspend fun getRecords(shardIterator: String, limit: Int = 100): GetRecordsResponse
    fun recordFlow(request: KinesisRecordFlowRequest): Flow<Record>
}
```

Use named request values for `putRecord`, shard iterator lookup, and Flow consumption because those APIs naturally contain multiple `String` parameters.

### Error Handling

- Validation failures are `IllegalArgumentException` through `require*` style checks.
- AWS SDK exceptions propagate unchanged.
- Flow cancellation is propagated immediately.
- Retry behavior is limited to Flow iterator/throttle recovery, modeled after the existing `aws-kotlin` Kinesis Flow behavior.
- Java SDK v2 exception handling should use concrete Kinesis/AWS SDK exception types available in `software.amazon.awssdk.services.kinesis.model` and must not infer retryability from Kotlin SDK-only metadata.
- No checkpoint persistence is introduced in this PR.

### Testing

Required tests:

- Auto-configuration registers `KinesisAsyncClient`, `KinesisProperties`, `KinesisOperations`, and `KinesisCoroutinesTemplate`.
- Auto-configuration backs off when disabled.
- Custom `KinesisAsyncClient` and custom `KinesisOperations` beans are respected.
- Classpath guard backs off when Kinesis SDK is absent.
- Endpoint override requires a region, and shared defaults can supply the region.
- Global and Kinesis-specific async customizers run in order.
- Property binding covers configured stream and consumer settings.
- Property validation covers `shardCount >= 1`, `batchLimit in 1..10_000`, non-negative delays, positive retry counts, and valid jitter bounds.
- Template unit tests cover validation and request mapping for representative operations.
- Emulator-backed smoke tests should cover create, put, describe, get shard iterator, get records, and Flow collection if Floci/LocalStack Kinesis support is reliable in this repository. If emulator support fails for a service limitation, record the fallback reason in Step DoD and keep unit/slice tests as the gate.

### Documentation

- Root README and Korean README should mention Spring Boot Kinesis operations in the module table and service section.
- `aws-spring-boot/README.md` and `README.ko.md` should add a Kinesis operations section with dependency snippet and coroutine usage.
- Service coverage chart should change `aws-spring-boot × Kinesis` from `-` to `S` only after implementation/tests pass.
- Generated SVG and PNG chart assets must pass XML/render validation and visual inspection.

## Risks And Mitigations

1. **Listener semantics creep**
   - Risk: A full listener runtime expands into checkpointing, resharding, concurrency, and operational semantics.
   - Mitigation: This PR stops at operations plus single-shard Flow. Listener runtime is a follow-up.

2. **Emulator reliability**
   - Risk: Kinesis emulator support may differ between Floci, LocalStack, and MiniStack.
   - Mitigation: Try a focused emulator smoke. If unreliable, record the exact blocker and keep deterministic unit/slice coverage.

3. **Dependency leakage**
   - Risk: Adding Kinesis SDK as implementation would violate the repo's compileOnly service SDK policy.
   - Mitigation: Add `libs.aws2.kinesis` as `compileOnly` and `testImplementation` only.

4. **Flow data loss assumptions**
   - Risk: Recovering an expired iterator from `LATEST` without a checkpoint can skip records.
   - Mitigation: Mirror the existing `aws-kotlin` design: do not silently recover from `LATEST` without a last seen sequence number.

5. **Public docs drift**
   - Risk: README examples can name APIs before they exist or after they change.
   - Mitigation: After implementation, grep README API names against source and include that check in validation.

## Acceptance Criteria

- `bluetape4k-aws-spring-boot` exposes Kinesis auto-configuration and coroutine operations.
- Kinesis SDK dependencies remain compile-only for production and test-only for tests.
- Conditional bean creation, property binding, customizer ordering, and classpath backoff tests pass.
- Representative Kinesis operations are covered by unit/slice tests, plus emulator smoke if reliable.
- README locale set and service coverage chart are updated from current source.
- Step 2-R, Step 3-R, Step 6-R, and Step 7-R review gates converge with P0/P1 = 0.
- PR body final `##` section is `## DoD Status`.
