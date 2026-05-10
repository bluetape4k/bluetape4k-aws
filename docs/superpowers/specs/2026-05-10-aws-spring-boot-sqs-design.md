# AWS Spring Boot SQS Design

Date: 2026-05-10 KST
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/2
Worktree: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/2-spring-boot-sqs`
Branch: `feat/2-spring-boot-sqs`

## Problem

`aws-spring-boot` needs an awspring-free SQS integration that follows the
newly merged S3 Spring Boot pattern:

- auto-configure AWS SDK v2 clients only when SQS is on the runtime classpath
- expose coroutine-friendly send/receive/delete APIs
- support annotation-driven SQS listener methods, including `suspend` handlers
- provide a coroutine-based listener loop with explicit ack/nack behavior
- support long polling, batch receive, DLQ-related queue attributes, and tests
  with LocalStack/Testcontainers

The implementation must not depend on awspring. It can reuse the existing
`aws` module SQS helpers and coroutine extensions.

## Current Evidence

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3AutoConfiguration.kt`
  is the current Spring Boot service auto-configuration pattern. It uses
  `@AutoConfiguration`, string-based `@ConditionalOnClass`, `@ConditionalOnProperty`,
  `@EnableConfigurationProperties`, `@ConditionalOnMissingBean`, and Spring-owned
  AWS SDK client beans.
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  currently registers `AwsAutoConfiguration` and `S3AutoConfiguration`.
- `aws/src/main/kotlin/io/bluetape4k/aws/sqs/SqsAsyncClientCoroutinesExtensions.kt`
  already provides suspend wrappers for `createQueue`, `getQueueUrl`, `send`,
  `sendBatch`, `receiveMessages`, `changeMessageVisibility`, `deleteMessage`,
  `deleteMessageBatch`, and `deleteQueue`.
- `aws/src/main/kotlin/io/bluetape4k/aws/sqs/SqsAsyncClientExtensions.kt`
  validates SQS receive batch size `1..10` and wraps `CompletableFuture` APIs.
- `aws/src/main/kotlin/io/bluetape4k/aws/sqs/model/ReceiveMessage.kt`
  records SQS long-poll limits: `maxNumberOfMessages` `1..10`,
  `waitTimeSeconds` `0..20`.
- `aws/src/test/kotlin/io/bluetape4k/aws/sqs/AbstractSqsTest.kt` and
  `SqsAsyncClientTest.kt` show LocalStack queue creation and send/receive/delete
  test patterns.
- Spring Boot 4.0.3 documentation says library auto-configurations are registered
  through `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
  It also recommends `@EnableConfigurationProperties` for library-managed
  `@ConfigurationProperties`, `ApplicationContextRunner` for auto-configuration
  tests, and `FilteredClassLoader` to test optional dependency absence.
- AWS SDK Java v2 SQS documentation uses `SqsAsyncClient`/`SqsClient` operations
  `sendMessage`, `receiveMessage`, `deleteMessage`, `changeMessageVisibility`,
  `createQueue`, and `getQueueUrl`. Receive uses `maxNumberOfMessages`, and
  long polling uses `waitTimeSeconds`.

## Constraints

- Kotlin 2.3, Java 21, Spring Boot 4.
- AWS service SDK dependencies stay `compileOnly`; consumers add runtime
  dependencies.
- Auto-configuration must be safe when `software.amazon.awssdk:sqs` is absent.
- Spring must own lifecycle for auto-configured SDK clients; do not use
  `SqsClientFactory` because it registers clients with `ShutdownQueue`.
- Public API KDoc should be Korean.
- Production code must not use `runBlocking`, `GlobalScope`, or `Thread.sleep`.
- Listener containers must stop cooperatively and cancel coroutines on Spring
  lifecycle stop.
- A failed listener invocation must not delete the message.

## Goals

1. Add SQS Spring Boot auto-configuration.
2. Add typed SQS properties under `bluetape4k.aws.sqs`.
3. Add `SqsOperations` and `SqsCoroutinesTemplate`.
4. Add `@SqsListener` annotation and listener endpoint registration.
5. Add `SqsMessageListenerContainer` with coroutine polling.
6. Support ack/delete and nack/change-visibility behavior.
7. Support LocalStack tests for send/receive/delete and listener ack/nack.
8. Update README/README.ko.

## Non-Goals

- No awspring dependency.
- No Spring Cloud AWS compatibility layer.
- No FIFO-specific high-level abstraction beyond preserving message attributes
  and exposing message group/dedup IDs where the AWS SDK already supports them.
- No JSON conversion framework in the first PR. Listener handlers receive the
  raw message body or AWS `Message`/custom wrapper.
- No SNS fanout handling in this issue; that belongs to `#4` and example `#13`.

## Architecture Pre-Design

### Components

```text
SqsAutoConfiguration
  -> SqsProperties
  -> SqsAsyncClient
  -> SqsOperations / SqsCoroutinesTemplate
  -> SqsMessageListenerContainerRegistry
  -> SqsListenerAnnotationBeanPostProcessor
       -> SqsMessageListenerContainer per @SqsListener method
```

### Runtime Flow

```text
Spring context refresh
  -> SqsAutoConfiguration registers SqsAsyncClient and template
  -> BeanPostProcessor scans beans for @SqsListener methods
  -> Registry creates listener containers
  -> SmartLifecycle starts containers after context refresh
  -> Container loop:
       receiveMessage(queueUrl/name, maxMessages, waitTimeSeconds)
       for each Message:
         invoke handler
         on success -> deleteMessage
         on failure -> changeMessageVisibility or leave message untouched
```

## API Design

### Package

All new Spring SQS code lives under:

```text
io.bluetape4k.aws.spring.sqs
```

### `SqsProperties`

```kotlin
@ConfigurationProperties(prefix = "bluetape4k.aws.sqs")
data class SqsProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val listener: Listener = Listener(),
    val queues: Map<String, Queue> = emptyMap(),
) {
    data class Listener(
        val enabled: Boolean = true,
        val autoStartup: Boolean = true,
        val phase: Int = Int.MAX_VALUE,
        val maxMessages: Int = 10,
        val waitTimeSeconds: Int = 20,
        val visibilityTimeoutSeconds: Int? = null,
        val errorVisibilityTimeoutSeconds: Int? = null,
        val concurrency: Int = 1,
        val stopTimeoutMillis: Long = 25_000,
    )

    data class Queue(
        val url: String? = null,
        val redrivePolicy: RedrivePolicy? = null,
    )

    data class RedrivePolicy(
        val deadLetterTargetArn: String,
        val maxReceiveCount: Int,
    )
}
```

Validation:

- `endpointOverride != null` requires nonblank `region`.
- `maxMessages` is `1..10`.
- `waitTimeSeconds` is `0..20`.
- visibility timeout values are `0..43_200` seconds when present.
- `concurrency >= 1`.
- `stopTimeoutMillis >= 1`.
- `RedrivePolicy.deadLetterTargetArn` is nonblank and `maxReceiveCount >= 1`.

Validation is implemented in `init {}` blocks, matching the current S3
properties style. The first implementation does not create DLQ queues
automatically, but `createConfiguredQueue` applies `RedrivePolicy` as an SQS
`RedrivePolicy` queue attribute when a caller explicitly creates a named queue
configuration.

### `SqsOperations`

```kotlin
interface SqsOperations {
    suspend fun getQueueUrl(queueName: String): String
    suspend fun createQueue(queueName: String, attributes: Map<QueueAttributeName, String> = emptyMap()): String
    suspend fun createConfiguredQueue(queueName: String): String
    suspend fun send(queueUrl: String, body: String, delaySeconds: Int? = null): SendMessageResponse
    suspend fun receive(
        queueUrl: String,
        maxMessages: Int = 10,
        waitTimeSeconds: Int = 20,
        visibilityTimeoutSeconds: Int? = null,
    ): List<SqsReceivedMessage>
    suspend fun delete(queueUrl: String, receiptHandle: String): DeleteMessageResponse
    suspend fun changeVisibility(queueUrl: String, receiptHandle: String, timeoutSeconds: Int): ChangeMessageVisibilityResponse
    fun receiveFlow(
        queueUrl: String,
        maxMessages: Int = 10,
        waitTimeSeconds: Int = 20,
        visibilityTimeoutSeconds: Int? = null,
    ): Flow<SqsReceivedMessage>
}
```

`SqsReceivedMessage` wraps the AWS SDK `Message` and queue URL:

```kotlin
data class SqsReceivedMessage(
    val queueUrl: String,
    val message: Message,
) {
    val body: String get() = message.body()
    val receiptHandle: String get() = message.receiptHandle()
}
```

### `SqsCoroutinesTemplate`

`SqsCoroutinesTemplate` implements `SqsOperations` by delegating to the existing
`aws` module SQS coroutine extensions where possible. It can still call
`SqsAsyncClient` directly for options not covered by the convenience extensions.

### `@SqsListener`

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SqsListener(
    val queue: String,
    val id: String = "",
    val maxMessages: Int = -1,
    val waitTimeSeconds: Int = -1,
    val visibilityTimeoutSeconds: Int = -1,
    val errorVisibilityTimeoutSeconds: Int = -1,
    val autoStartup: Boolean = true,
)
```

`queue` accepts either:

- a full queue URL
- a queue name resolved through `SqsOperations.getQueueUrl`
- a key in `SqsProperties.queues`

Supported handler signatures in the first PR:

```kotlin
@SqsListener("queue-name")
fun handle(body: String)

@SqsListener("queue-name")
suspend fun handle(body: String)

@SqsListener("queue-name")
fun handle(message: Message)

@SqsListener("queue-name")
suspend fun handle(message: SqsReceivedMessage)
```

Unsupported signatures fail fast during context startup with an
`IllegalArgumentException`.

Queue/id annotation values are resolved through `Environment.resolvePlaceholders`,
so `${app.queue}` works. SpEL is not supported in the first PR; values containing
`#{...}` fail fast with a clear error.

The annotation scanner must inspect the user class with `AopUtils.getTargetClass`
so methods on proxied beans are discovered. Invocation must still call through
the Spring bean proxy so advice such as `@Transactional` remains active.

### `SqsMessageListenerContainer`

The container implements Spring `SmartLifecycle`:

- `start()` launches one parent `SupervisorJob`.
- `phase` defaults to `Int.MAX_VALUE` so listeners start late and stop early.
- `concurrency` means the number of polling coroutines.
- each polling coroutine receives up to `maxMessages` messages per poll.
- messages inside one received batch are processed sequentially by that polling
  coroutine; aggregate parallelism is bounded by `concurrency`.
- sync handlers run from the container coroutine on `Dispatchers.IO`; suspend
  handlers are also invoked from the same container coroutine context.
- `stop()` cancels the parent job and stops new receives.
- `stop(callback)` waits up to `stopTimeoutMillis`, then calls the callback even
  if an AWS HTTP long-poll future has not observed cancellation.
- in-flight receive results returned after stop are ignored.
- each message is processed independently.
- success deletes the message.
- failure changes visibility to `errorVisibilityTimeoutSeconds` when configured;
  if the value is null, the message is left for the queue visibility timeout.
- `CancellationException` is rethrown before generic handler error handling.

The listener container should not use `Thread.sleep`; it relies on long polling
and coroutine cancellation.

There is no automatic visibility heartbeat in the first PR. Users whose handlers
can exceed queue visibility timeout must configure a larger
`visibilityTimeoutSeconds` or add explicit visibility extension in later work.
SQS remains at-least-once delivery; handlers must be idempotent.

### `SqsMessageListenerContainerRegistry`

Registry owns listener containers as Spring lifecycle beans. It provides:

- `register(container)`
- `getContainer(id)`
- `containers`
- `start/stop` forwarding through `SmartLifecycle`

This keeps annotation processing separate from container lifecycle.

Queue URL resolution happens once when a container starts and is cached for the
container lifetime. Resolution order is:

1. configured `queues[name].url`
2. literal URL starting with `http://` or `https://`
3. `SqsOperations.getQueueUrl(queueName)`

### `SqsListenerAnnotationBeanPostProcessor`

This scans initialized beans for methods annotated with `@SqsListener`.
It resolves method invokers once at startup and registers containers in the
registry.

Use Kotlin reflection only where needed for suspend detection and invocation.
If Kotlin reflection is unavailable or too heavy, use Spring's coroutine-aware
invocation support only after verifying the API. First implementation may use
`kotlin.reflect.full.callSuspend` because Kotlin reflect is already available in
this repository's common subproject dependencies.

## `receiveFlow` Contract

`SqsOperations.receiveFlow` returns a cold, infinite flow. Each collection starts
its own receive loop and emits `SqsReceivedMessage` values. The flow does not
delete messages automatically; consumers call `delete` or `changeVisibility`
explicitly. Cancellation stops future receives, and an in-flight receive result
that arrives after cancellation is ignored.

## Dependency Changes

`aws-spring-boot/build.gradle.kts`:

```kotlin
compileOnly(libs.aws2.sqs)
testImplementation(libs.aws2.sqs)
```

No new external runtime dependencies beyond already present Spring Boot,
Kotlin coroutines, and AWS SDK v2 artifacts.

`kotlin-reflect` is already configured in the root subproject dependencies and is
therefore available for suspend handler reflection.

## Design Options

### Option A - Template Only

Add `SqsAutoConfiguration`, `SqsProperties`, and `SqsCoroutinesTemplate`, but no
annotation listener.

Pros:

- small, low risk
- simple LocalStack tests

Cons:

- does not satisfy issue goal for `@SqsListener`
- users still need to write polling loops

Decision: rejected for this issue.

### Option B - Minimal Listener Container and Raw Message Conversion

Add template plus a lightweight annotation processor and container. Support only
raw `String`, AWS `Message`, and `SqsReceivedMessage` handler parameters.

Pros:

- satisfies issue scope
- keeps conversion and retry semantics explicit
- small enough for one PR
- avoids awspring compatibility promises

Cons:

- no JSON/object conversion yet
- no advanced listener endpoint registry semantics

Decision: selected.

### Option C - awspring-like Listener Framework

Build a richer endpoint registrar with payload conversion, header mapping,
manual ack objects, retry policy DSL, and queue creation.

Pros:

- more feature-complete

Cons:

- too broad for `#2`
- high risk of re-implementing Spring Cloud AWS prematurely
- larger testing matrix

Decision: rejected for first SQS PR.

## Failure Modes And Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Optional SQS SDK is absent but auto-config references concrete classes too early | App startup failure | Use string `@ConditionalOnClass` at auto-config class level, mirror S3 pattern, and add `FilteredClassLoader` test. |
| Listener deletes messages after failed handler | Data loss | Delete only after successful handler completion; failure uses visibility timeout or no-op. |
| Listener loop leaks coroutines on context shutdown | Hanging tests/app shutdown | `SmartLifecycle.stop` cancels `SupervisorJob`; tests assert container stops. |
| Long polling blocks shutdown | Slow shutdown | Stop prevents new receives, ignores late results, and uses `stopTimeoutMillis` for lifecycle callback completion. |
| `CancellationException` swallowed by handler error path | Listener refuses to stop | Catch `CancellationException` first and rethrow. |
| Proxied Spring beans hide `@SqsListener` annotations | Listener silently not registered | Scan `AopUtils.getTargetClass(bean)` and invoke through the proxy bean. |
| Handler signature support is ambiguous | Runtime surprises | Fail fast at context startup for unsupported signatures. |
| Queue name vs URL resolution is unclear | Listener cannot start | Resolution order: configured `queues[name].url`, full URL, then `getQueueUrl(queueName)`. Document it. |
| Handler runs longer than visibility timeout | Duplicate processing | No heartbeat in first PR; document idempotency and visibility sizing. |
| DLQ scope grows into full provisioning | Larger feature surface | First PR only applies `RedrivePolicy` when `createConfiguredQueue` is explicitly called. |

## Acceptance Criteria

- `SqsAutoConfiguration` is registered in `AutoConfiguration.imports`.
- `SqsAsyncClient` is auto-configured when SQS SDK is present and backs off for
  user beans.
- `SqsProperties` binds and validates endpoint/receive/listener constraints.
- `SqsOperations` and `SqsCoroutinesTemplate` support create/get/send/receive/delete/change-visibility/flow.
- `@SqsListener` supports sync and suspend handlers for `String`, `Message`, and
  `SqsReceivedMessage`.
- `${...}` placeholders in `@SqsListener` queue/id values are supported; SpEL
  expressions fail fast.
- proxied beans with `@SqsListener` methods are discovered.
- `SqsMessageListenerContainer` acks on success and does not delete on failure.
- listener shutdown does not hang beyond `stopTimeoutMillis`.
- `receiveFlow` is documented and tested as an explicit-ack cold flow.
- LocalStack tests cover template send/receive/delete and listener ack/nack.
- tests cover SQS SDK absence via `FilteredClassLoader`.
- tests cover unsupported listener method signatures fail fast.
- tests cover `concurrency > 1` receives without exceeding configured polling
  coroutine count.
- README.md and README.ko.md show SQS auto-configuration and listener usage.
- README.md and README.ko.md document SQS at-least-once delivery and idempotent
  handler responsibility.
- Nightly examples job remains valid after this change.

## Verification Plan

- `./gradlew :aws-spring-boot:compileKotlin --no-daemon`
- `./gradlew :aws-spring-boot:test --no-daemon`
- `./gradlew :aws-spring-boot:koverHtmlReport --no-daemon`
- `./gradlew detekt --parallel --no-daemon`
- `./gradlew build -x test --parallel --no-daemon`
- `rg 'runBlocking|Thread\\.sleep|GlobalScope' aws-spring-boot/src/main/kotlin`
- `rg 'CancellationException' aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs`
- `yq e '.' .github/workflows/nightly.yml >/dev/null`
- `git diff --check`

## Open Questions

No user-blocking questions. The selected first implementation intentionally keeps
message conversion raw and defers JSON/object conversion to a later issue.

The following policies are fixed for this PR:

- `concurrency` is polling coroutine count.
- `${...}` placeholders are supported; SpEL is not.
- failed handlers keep the queue visibility timeout by default.
- DLQ support is limited to explicit `createConfiguredQueue` redrive attributes.
- visibility heartbeat and Micrometer metrics are deferred.

## Step Checklist

### Step 0 - Worktree Setup

| Item | Status | Notes |
|---|---|---|
| Feature worktree created | Done | `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/2-spring-boot-sqs` |
| Commands run inside worktree | Done | Spec path is inside worktree. |
| Spec/plan written inside worktree | Done | This spec is in `docs/superpowers/specs`. |
| Refreshed from current origin/develop | Done | Worktree created from `origin/develop` after PR #28/#29 merge. |

### Step 1 - Requirements Gathering

| Item | Status | Notes |
|---|---|---|
| Target repo confirmed | Done | `bluetape4k-aws`, issue #2. |
| Memory anchors checked | Done | PR title prefix, absolute advisor paths, Korean docs/KDoc, worktree hygiene. |
| Review-only boundary | N/A | User requested implementation. |
| Concrete artifact inspected | Done | GitHub issue #2 body inspected. |
| Intent and boundaries clear | Done | Sequential #2 then #3. |

### Step 1-R - Pre-Spec Research

| Item | Status | Notes |
|---|---|---|
| Official docs checked | Done | Spring Boot 4.0.3 auto-config docs; AWS SDK Java v2 SQS docs. |
| Current repo searched | Done | S3 Spring Boot pattern and existing SQS helpers/tests inspected. |
| Third-party assumptions checked | Done | SQS receive/delete/visibility and Spring imports/conditional docs checked. |
| Adopt/borrow/skip decisions recorded | Done | Reuse `aws` SQS extensions; skip awspring and rich conversion. |
| Technical constraints identified | Done | compileOnly SDK, coroutine lifecycle, LocalStack. |

### Step 2 - Brainstorming + Spec

| Item | Status | Notes |
|---|---|---|
| Architecture pre-design | Done | Component and runtime flow above. |
| Research incorporated | Done | Current evidence and constraints sections. |
| Current behavior claims cite evidence | Done | File paths listed. |
| Spec path confirmed | Done | Worktree-local path. |
| Risks/failure modes included | Done | Failure modes table. |
| Approach comparison included | Done | Options A/B/C. |
| Open questions resolved | Done | No blocking questions. |

## Claude Code Opus Advisor

Artifact:
`/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/2-spring-boot-sqs/.omx/artifacts/ask-claude-aws-spring-boot-sqs-spec-20260510-184644.md`

Model: `${CLAUDE_ADVISOR_MODEL:-claude-opus-4-7}`

| Severity | Finding | Decision | Follow-up |
|---|---|---|---|
| high | Concurrency and batch processing model undefined. | Accepted | Defined `concurrency` as polling coroutine count and batch processing as sequential per polling coroutine. |
| high | Sync handler dispatcher unspecified. | Accepted | Specified `Dispatchers.IO` invocation for handlers. |
| high | `SmartLifecycle` phase default `0` is risky. | Accepted | Changed default to `Int.MAX_VALUE`. |
| high | Long-poll shutdown cannot rely on coroutine cancellation alone. | Accepted | Added `stopTimeoutMillis`, late-result ignore, and stop callback policy. |
| high | `CancellationException` rethrow rule missing. | Accepted | Added explicit cancellation rule and verification grep. |
| high | Proxied beans can hide listener annotations. | Accepted | Added `AopUtils.getTargetClass` scan and proxy invocation rule. |
| high | Placeholder/SpEL policy undefined. | Accepted | Support `${...}` placeholders; reject SpEL in first PR. |
| high | `receiveFlow` contract undefined. | Accepted | Added cold infinite flow, explicit ack, cancellation semantics. |
| medium | DLQ properties unused are YAGNI. | Accepted with scope limit | Replaced loose fields with `RedrivePolicy` tied to `createConfiguredQueue`. |
| medium | `kotlin-reflect` runtime availability should be explicit. | Accepted | Recorded root dependency availability. |
| medium | Validation mechanism unspecified. | Accepted | Chose `init {}` validation matching S3. |
| medium | Visibility heartbeat/idempotency missing. | Accepted | Documented no heartbeat and at-least-once/idempotent handler responsibility. |
| medium | Test matrix missing classpath, shutdown, signature, concurrency cases. | Accepted | Added acceptance criteria. |
