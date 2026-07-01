# EventBridge Core Wrappers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add focused Amazon EventBridge wrappers to `bluetape4k-aws-java` and `bluetape4k-aws-kotlin`.

**Architecture:** Follow existing service wrapper patterns. Java SDK v2 gets client factories, request builders, sync helpers, async `CompletableFuture` helpers, and coroutine adapters. AWS Kotlin SDK gets client factories, focused request builders, and native suspend helpers. All helper calls preserve raw SDK response objects, avoid hidden batching/retry/cleanup, and leave partial-failure handling to callers.

**Tech Stack:** Kotlin 2.4, Java SDK v2 EventBridge, AWS Kotlin SDK EventBridge, JUnit 5, MockK, bluetape4k-assertions, Gradle.

---

## File Map

- Modify: `gradle/libs.versions.toml`
- Modify: `aws-java/build.gradle.kts`
- Modify: `aws-kotlin/build.gradle.kts`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeClientSupport.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeAsyncClientSupport.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeClientExtensions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeAsyncClientExtensions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeAsyncClientCoroutinesExtensions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/model/EventBridgeRequestSupport.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeRequestSupportTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeClientSupportTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeClientExtensionsTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeAsyncClientCoroutinesExtensionsTest.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/eventbridge/EventBridgeClientSupport.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/eventbridge/EventBridgeClientExtensions.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/eventbridge/model/EventBridgeRequestSupport.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/eventbridge/EventBridgeRequestSupportTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/eventbridge/EventBridgeClientSupportTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/eventbridge/EventBridgeClientExtensionsTest.kt`
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `aws-java/README.md`
- Modify: `aws-java/README.ko.md`
- Modify: `aws-kotlin/README.md`
- Modify: `aws-kotlin/README.ko.md`
- Create or update: `docs/review/2026-07-01-issue-308-code-review.md`
- Create: `docs/lessons/2026-07-01-issue-308-eventbridge-core.md`

## Task 1: Dependency Aliases And RED Tests

**Complexity:** medium

**Applies:** `$bluetape4k-code-patterns`, `$test-driven-development`

- [ ] **Step 1: Add failing Java request-builder tests**

Create `aws-java/src/test/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeRequestSupportTest.kt`.

Cover:

- `putEventsRequestOf(entries)` rejects empty entries and more than 10 entries.
- `putEventsRequestEntryOf(source, detailType, detail, ...)` rejects blank `source`, `detailType`, and `detail`.
- Optional event `resources` may be absent but supplied blank resource values are rejected.
- `putRuleRequestOf(...)` rejects missing or blank `eventPattern` and `scheduleExpression`.
- `putTargetsRequestOf(rule, targets)` rejects empty or more than 10 targets.
- `targetOf(id, arn, ...)` rejects blank id and ARN.
- `removeTargetsRequestOf(rule, ids)` rejects empty or more than 10 ids.

Run:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*EventBridgeRequestSupportTest' --no-configuration-cache
```

Expected: FAIL because EventBridge aliases/classes do not exist.

- [ ] **Step 2: Add failing AWS Kotlin request-builder tests**

Create `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/eventbridge/EventBridgeRequestSupportTest.kt`.

Use the same behavioral cases as Java, with AWS Kotlin SDK model names.

Run:

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*EventBridgeRequestSupportTest' --no-configuration-cache
```

Expected: FAIL because EventBridge aliases/classes do not exist.

- [ ] **Step 3: Add dependency aliases and declarations**

Add to `gradle/libs.versions.toml`:

```toml
aws2-eventbridge = { module = "software.amazon.awssdk:eventbridge", version.ref = "aws2" }
aws-kotlin-eventbridge = { module = "aws.sdk.kotlin:eventbridge", version.ref = "aws-kotlin" }
```

Add to `aws-java/build.gradle.kts`:

```kotlin
compileOnly(libs.aws2.eventbridge)
testImplementation(libs.aws2.eventbridge)
```

Add to `aws-kotlin/build.gradle.kts`:

```kotlin
compileOnly(libs.aws.kotlin.eventbridge)
testImplementation(libs.aws.kotlin.eventbridge)
```

- [ ] **Step 4: Implement minimal request builders**

Implement only enough production code for the request-builder tests to pass.
Use bluetape4k validation helpers and English KDoc.

- [ ] **Step 5: Verify GREEN**

Run both commands from Steps 1 and 2. Expected: PASS.

## Task 2: Java SDK v2 EventBridge Helpers

**Complexity:** high

**Applies:** `$bluetape4k-code-patterns`, `$test-driven-development`

- [ ] **Step 1: Add failing Java client/support tests**

Create `EventBridgeClientSupportTest.kt` and `EventBridgeClientExtensionsTest.kt`.

Cover:

- `eventBridgeClientOf(endpoint, region, credentialsProvider, httpClient)` builds a closeable client.
- `eventBridgeAsyncClientOf(endpoint, region, credentialsProvider, httpClient)` builds a closeable async client.
- Sync helpers call exactly one SDK operation and return raw SDK responses.
- `putEvents`, `putTargets`, and `removeTargets` helpers preserve raw SDK responses with failed-entry counts/details.
- No helper collapses partial success to Boolean success.

Run:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*EventBridgeClient*' --no-configuration-cache
```

Expected: FAIL because helpers do not exist.

- [ ] **Step 2: Add failing Java coroutine adapter tests**

Create `EventBridgeAsyncClientCoroutinesExtensionsTest.kt`.

Cover:

- Coroutine helpers call the async SDK method and `await()` the returned future.
- A `CompletableFuture` completed exceptionally with `CancellationException` propagates cancellation.
- Repeated `putEvents` helper calls invoke the SDK once per helper call and do not fan out through hidden batching.

Run:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*EventBridgeAsyncClientCoroutinesExtensionsTest' --no-configuration-cache
```

Expected: FAIL because coroutine helpers do not exist.

- [ ] **Step 3: Implement Java client factories**

Follow existing `KinesisClientSupport.kt` / `KinesisAsyncClientSupport.kt`.
Register every created Java `EventBridgeClient` and `EventBridgeAsyncClient`
with `ShutdownQueue.register(this)` immediately after `build()`, matching the
Kinesis support files.

- [ ] **Step 4: Implement Java sync/async/coroutine extensions**

Add helpers for:

- `createEventBus`
- `deleteEventBus`
- `putRule`
- `deleteRule`
- `putTargets`
- `removeTargets`
- `listRules`
- `listTargetsByRule`
- `putEvents`

Keep helpers as one SDK request per invocation. Add English KDoc for summary,
contract, usage, partial-failure response visibility, cancellation propagation,
and delete-order caveats.

- [ ] **Step 5: Verify Java GREEN**

Run:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*EventBridge*' --no-configuration-cache
./gradlew :bluetape4k-aws-java:compileTestKotlin --warning-mode all
```

Expected: PASS.

## Task 3: AWS Kotlin SDK EventBridge Helpers

**Complexity:** high

**Applies:** `$bluetape4k-code-patterns`, `$test-driven-development`

- [ ] **Step 1: Add failing AWS Kotlin client/support tests**

Create `EventBridgeClientSupportTest.kt` and `EventBridgeClientExtensionsTest.kt`.

Cover:

- `eventBridgeClientOf(...)` creates a caller-owned client.
- `withEventBridgeClient { }` closes its owned short-lived client.
- Native suspend helpers map inputs into SDK requests.
- `putEvents`, `putTargets`, and `removeTargets` helpers preserve raw SDK responses with failed-entry counts/details.
- No helper adds hidden retry, batching, or cleanup.

Run:

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*EventBridge*' --no-configuration-cache
```

Expected: FAIL because helpers do not exist.

- [ ] **Step 2: Implement AWS Kotlin client factories and request builders**

Follow existing `KinesisClientSupport.kt` and SQS/Kinesis model support.
Keep builder helpers focused on validation/value-add constructors, not full
generated-builder mirroring.

- [ ] **Step 3: Implement native suspend extensions**

Add helpers for:

- `createEventBus`
- `deleteEventBus`
- `putRule`
- `deleteRule`
- `putTargets`
- `removeTargets`
- `listRules`
- `listTargetsByRule`
- `putEvents`

Do not wrap suspend calls in `runCatching`. Let SDK exceptions and cancellation
propagate.

- [ ] **Step 4: Verify AWS Kotlin GREEN**

Run:

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*EventBridge*' --no-configuration-cache
./gradlew :bluetape4k-aws-kotlin:compileTestKotlin --warning-mode all
```

Expected: PASS.

## Task 4: Docs, Emulator Probe, And Final Validation

**Complexity:** medium

**Applies:** `$bluetape4k-code-patterns`

- [ ] **Step 1: Update README locale set**

Update root and module README pairs:

- `README.md`
- `README.ko.md`
- `aws-java/README.md`
- `aws-java/README.ko.md`
- `aws-kotlin/README.md`
- `aws-kotlin/README.ko.md`

Document:

- EventBridge service coverage.
- Runtime dependencies: `software.amazon.awssdk:eventbridge` and `aws.sdk.kotlin:eventbridge`.
- `PutEvents`/`PutTargets` partial-failure response inspection.
- Unsupported edge capabilities: Scheduler, framework integrations, global endpoints, cross-account target orchestration, and target-specific validation beyond SDK types.

- [ ] **Step 2: Run Floci-first emulator probe**

Check whether configured emulator support can run a minimal EventBridge workflow.
If supported, run the narrow smoke test. If unsupported, record exact evidence
for Floci and LocalStack fallback in PR validation notes.

First inspect local emulator support and test scaffolding:

```bash
rg -n "EventBridge|events|floci|localstack|LocalStack|unsupportedForFloci" aws-java/src/test aws-kotlin/src/test README.md README.ko.md
```

If an EventBridge emulator smoke test is added and Floci supports the workflow,
run:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*EventBridgeEmulator*' -Dbluetape4k.aws.emulator=floci --no-configuration-cache
./gradlew :bluetape4k-aws-kotlin:test --tests '*EventBridgeEmulator*' -Dbluetape4k.aws.emulator=floci --no-configuration-cache
```

If Floci does not support the workflow but LocalStack does, run the same tests
with `-Dbluetape4k.aws.emulator=localstack`. If neither emulator path supports
EventBridge in this repository, record the exact `rg` output and the absence of
`*EventBridgeEmulator*` tests in the PR `## DoD Status` instead of claiming a
live emulator smoke.

Expected: PASS for the supported emulator path, or explicit unsupported-emulator
evidence.

- [ ] **Step 3: Run full targeted validation**

Run:

```bash
./gradlew :bluetape4k-aws-java:compileTestKotlin :bluetape4k-aws-kotlin:compileTestKotlin --warning-mode all
./gradlew :bluetape4k-aws-java:test --tests '*EventBridge*' :bluetape4k-aws-kotlin:test --tests '*EventBridge*' --no-configuration-cache
git diff --check
```

Expected: PASS.

- [ ] **Step 4: Commit spec, plan, implementation, review, and lessons**

Use Lore commit trailers. Keep planning artifacts committed before final code PR
creation.

## Verification Matrix

| Requirement | Evidence |
|---|---|
| Java factories/builders/extensions/coroutines | Java EventBridge tests + `compileTestKotlin` |
| AWS Kotlin factories/builders/suspend helpers | AWS Kotlin EventBridge tests + `compileTestKotlin` |
| PutEvents/PutTargets limits | Request-support tests |
| PutRule matcher requirement | Request-support tests |
| Partial-failure visibility | Extension tests and README/KDoc |
| No hidden batching/retry/cleanup | Mock invocation-count tests |
| Lifecycle contracts | Client support tests or explicit nearest-pattern evidence |
| Emulator readiness | Floci-first probe PASS or unsupported evidence |
| Public docs | README locale set + English KDoc grep |
