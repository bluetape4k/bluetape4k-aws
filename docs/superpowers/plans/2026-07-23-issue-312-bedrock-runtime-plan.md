# Bedrock Runtime Minimal Facade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add model-neutral Amazon Bedrock Runtime `Converse` and `ConverseStream` helpers to the Java SDK v2 and AWS Kotlin SDK modules while preserving native SDK types, cancellation, bounded streaming demand, and explicit client ownership.

**Architecture:** Add Bedrock Runtime as a `compileOnly` service dependency in both published modules, then build small client, request, response, and operation extensions around each SDK's native types. Java streaming adapts `SdkPublisher` into a cold `Flow` with `asFlow().buffer(0)` and a generation-aware terminal state machine; Kotlin streaming collects the SDK's native Flow directly. English/Korean module READMEs share the API contract but use separate localized sequence-diagram assets; release-bound manual pages remain unchanged until the `0.5.0` manual refresh.

**Tech Stack:** Kotlin 2.4, AWS SDK for Java v2 Bedrock Runtime, AWS SDK for Kotlin Bedrock Runtime, Kotlin Coroutines/Reactive, bluetape4k-coroutines Flow extensions, JUnit 5, MockK, Gradle, SVG, CairoSVG.

---

## Approved Inputs And Stop Conditions

- Approved spec: `docs/superpowers/specs/2026-07-23-issue-312-bedrock-runtime-design.md`
- Repository: `bluetape4k/bluetape4k-aws`
- Base/head: `develop` / `feat/issue-312-bedrock-runtime`
- PR creation is authorized after all Type A gates; merge is not authorized.
- Production implementation starts only after this plan is approved and Step 3-R is `P0=0`, `P1=0`.
- Real Bedrock invocation remains opt-in and is never a default CI requirement.

## File Map

### Dependency and build ownership

- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify: `aws-java/build.gradle.kts`
- Modify: `aws-kotlin/build.gradle.kts`

### Java SDK v2 facade

- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeClientSupport.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeAsyncClientSupport.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeClientExtensions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeAsyncClientExtensions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeAsyncClientCoroutinesExtensions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/model/BedrockRuntimeRequestSupport.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/model/BedrockRuntimeResponseSupport.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeClientSupportTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeRequestSupportTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeResponseSupportTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeClientExtensionsTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeAsyncClientExtensionsTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeAsyncClientCoroutinesExtensionsTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensionsTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/RecordingSdkPublisher.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeSmokeTest.kt`
- Create: `aws-java/src/consumerFixture/kotlin/io/bluetape4k/aws/bedrock/consumer/JavaBedrockConsumerFixture.kt`

### AWS Kotlin SDK facade

- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeClientSupport.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeClientExtensions.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeFlowExtensions.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/bedrock/model/BedrockRuntimeRequestSupport.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/bedrock/model/BedrockRuntimeResponseSupport.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeClientSupportTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeRequestSupportTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeResponseSupportTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeClientExtensionsTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeFlowExtensionsTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeSmokeTest.kt`
- Create: `aws-kotlin/src/consumerFixture/kotlin/io/bluetape4k/aws/kotlin/bedrock/consumer/KotlinBedrockConsumerFixture.kt`

### Documentation, diagrams, review, and lessons

- Modify: `aws-java/README.md`
- Modify: `aws-java/README.ko.md`
- Modify: `aws-kotlin/README.md`
- Modify: `aws-kotlin/README.ko.md`
- Create: `docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-en-01.svg`
- Create: `docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-en-01.png`
- Create: `docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-ko-01.svg`
- Create: `docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-ko-01.png`
- Reuse: `docs/assets/aws-icons/official-04302026/Architecture-Service-Icons_04302026/Arch_Artificial-Intelligence/48/Arch_Amazon-Bedrock_48.svg`
- Modify: `CHANGELOG.md`
- Modify: `WIP.md`
- Create: `docs/review/2026-07-23-issue-312-plan-review.md`
- Create: `docs/review/2026-07-23-issue-312-code-review.md`
- Create: `docs/lessons/2026-07-23-issue-312-bedrock-runtime.md`
- Modify: `docs/lessons/README.md`

## Spec Traceability

| Spec requirement | Plan task | Proof |
|---|---|---|
| Existing AWS version authority and `compileOnly` service SDK | Task 1 | dependency insight and published metadata checks |
| Exact Java/Kotlin client and model signatures | Tasks 2, 3, 5 | focused compile/tests and KDoc |
| Java sync, future, suspend calls | Task 3 | delegation/error/cancellation tests |
| Java cold streaming Flow and bounded demand | Task 4 | recording publisher ledger and race tests |
| Kotlin native suspend/Flow and scoped close | Task 5 | mock client and lifecycle tests |
| `castNotNull`/`takeUntil` use without `Flow.log()` | Tasks 4, 5, 7 | source assertions, Flow tests, examples |
| Response text mapping and single-pass join | Tasks 2, 5 | mapping tests with large/mixed blocks |
| Endpoint/credential and generated-output trust boundaries | Tasks 2, 5, 7 | negative endpoint tests and docs |
| Opt-in smoke with fail-closed default | Task 6 | ordinary-test exclusion and property/env tests |
| English/Korean docs and separate SVG/PNG | Task 7 | locale parity and diagram audits |
| Type A review, lesson, PR/DoD | Task 8 | review artifact, lesson commit, exact-head PR |

## Step 3-P Risk Prediction

| Risk | Early signal | Mitigation in task | Rollback/rerun point |
|---|---|---|---|
| Java future/publisher double terminal | second completion, leaked subscription, hanging test | generation ledger and first-terminal-wins tests in Task 4 | revert Task 4 commit and rerun all Flow tests |
| SDK retry repeats semantic output | repeated text after publisher replacement | preserve native behavior, document no exactly-once, block transactional delta use | switch caller to non-streaming `Converse`; do not add hidden dedupe |
| Cancellation leaks request/subscription | future remains active after collector cancellation | cancel-once ledger and timeout tests | stop at Task 4/5 and repair before docs |
| `compileOnly` boundary leaks or consumer setup is omitted | published metadata contains service SDK or README lacks the required coordinate | runtimeClasspath/publication audit plus consumer dependency examples in Tasks 6–7 | revert aliases/build declarations and docs together |
| Credentialed smoke runs accidentally | default test attempts network/client creation | default tag exclusion plus property/env gate before client creation | disable `-PbedrockSmoke`; smoke remains N/A |
| Client closes before cold Flow collection | `withBedrockRuntimeClient` returns unusable Flow | lifecycle tests and block-local terminal collection docs | use caller-owned client path |
| Diagram overstates exactly-once/backpressure | visual says retry is transparent or unbounded | source-aligned retry alt frame and full-size inspection | revise SVG source, regenerate PNG, rerun audits |

If pre-release rollback is required, revert in reverse dependency order:
status/docs/diagrams, smoke/publication/consumer guards, Kotlin facade, Java
streaming, Java non-streaming operations, Java foundations, then module/root
dependency declarations and catalog aliases. Regenerate publication metadata
after the rollback and rerun consumer fixture compilation, module compilation,
manual/diagram checks where still applicable, and `git diff --check`.

### Task 1: Register Bedrock Runtime Dependencies

**Complexity:** medium

**Depends on:** approved spec

**Applies:** `bluetape-kotlin-patterns`, `test-driven-development`

**Write scope:** catalog and four Gradle build files only

- [ ] **Step 1: Add catalog aliases under existing version authorities**

Modify `gradle/libs.versions.toml` beside the other service aliases:

```toml
aws2-bedrock-runtime = { module = "software.amazon.awssdk:bedrockruntime" }
aws-kotlin-bedrock-runtime = { module = "aws.sdk.kotlin:bedrockruntime" }
```

Do not add a new version key. Java resolves through the existing AWS SDK v2 BOM;
Kotlin resolves through the root `aws-kotlin` constraint.

- [ ] **Step 2: Add root dependency-management entries**

Modify the generated local-alias section of `build.gradle.kts`:

```kotlin
dependency("aws.sdk.kotlin:bedrockruntime:${bt4kVersion("aws-kotlin")}")
dependency("software.amazon.awssdk:bedrockruntime:${bt4kVersion("aws2")}")
```

Place each entry in lexical order within its SDK group.

- [ ] **Step 3: Add module-scoped compile and test dependencies**

Modify `aws-java/build.gradle.kts`:

```kotlin
compileOnly(libs.aws2.bedrock.runtime)
testImplementation(libs.aws2.bedrock.runtime)
```

Modify `aws-kotlin/build.gradle.kts`:

```kotlin
compileOnly(libs.aws.kotlin.bedrock.runtime)
testImplementation(libs.aws.kotlin.bedrock.runtime)
```

- [ ] **Step 4: Verify resolution and compile-only placement**

Run:

```bash
./gradlew \
  :bluetape4k-aws-java:dependencyInsight \
  --dependency bedrockruntime \
  --configuration compileClasspath \
  --no-daemon \
  --no-configuration-cache

./gradlew \
  :bluetape4k-aws-kotlin:dependencyInsight \
  --dependency bedrockruntime \
  --configuration compileClasspath \
  --no-daemon \
  --no-configuration-cache
```

Expected: Java resolves the existing `aws2` version and Kotlin resolves the
existing `aws-kotlin` version; neither command introduces a new version key.

- [ ] **Step 5: Commit the dependency boundary**

```bash
git add gradle/libs.versions.toml build.gradle.kts aws-java/build.gradle.kts aws-kotlin/build.gradle.kts
git commit -m "Enable the Bedrock facade without widening runtime dependencies" \
  -m "Constraint: Bedrock service SDKs remain compileOnly and reuse existing AWS version authorities
Confidence: high
Scope-risk: narrow
Tested: Java and Kotlin dependencyInsight for bedrockruntime
Not-tested: Public facade code is introduced in later tasks"
```

### Task 2: Build Java Client, Request, And Response Foundations

**Complexity:** high

**Depends on:** Task 1

**Applies:** `bluetape-kotlin-patterns`, `test-driven-development`

**Write scope:** Java client support, model support, and their focused tests

- [ ] **Step 1: Write failing Java client lifecycle and endpoint tests**

Create `BedrockRuntimeClientSupportTest.kt` with these executable cases:

```kotlin
@Test
fun `sync and async factories create closeable clients`() {
    val endpoint = URI("http://localhost:4566")
    val credentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test"),
    )

    bedrockRuntimeClientOf(endpoint, Region.US_EAST_1, credentials).close()
    bedrockRuntimeAsyncClientOf(endpoint, Region.US_EAST_1, credentials).close()
}

@Test
fun `plain HTTP endpoint must be loopback`() {
    assertFailsWith<IllegalArgumentException> {
        bedrockRuntimeClientOf(endpoint = URI("http://example.com"))
    }
    assertFailsWith<IllegalArgumentException> {
        bedrockRuntimeAsyncClientOf(endpoint = URI("http://192.0.2.1"))
    }
}
```

Run:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests '*BedrockRuntimeClientSupportTest' \
  --no-daemon --no-configuration-cache
```

Expected: FAIL because the Bedrock client helpers do not exist.

- [ ] **Step 2: Implement Java sync and async client factories**

Create `BedrockRuntimeClientSupport.kt` and
`BedrockRuntimeAsyncClientSupport.kt`. Use the established STS/EventBridge
shape, register every newly built client, and reject non-loopback HTTP:

```kotlin
@PublishedApi
internal fun URI.requireTrustedBedrockEndpoint(): URI = apply {
    val normalizedHost = host
        ?.lowercase()
        ?.removePrefix("[")
        ?.removeSuffix("]")
    val loopback = normalizedHost == "localhost" ||
        normalizedHost == "127.0.0.1" ||
        normalizedHost == "::1"
    require(scheme.equals("https", ignoreCase = true) ||
            (scheme.equals("http", ignoreCase = true) && loopback)) {
        "Bedrock endpoint must use HTTPS; plain HTTP is allowed only for loopback tests"
    }
}

inline fun bedrockRuntimeClient(
    builder: BedrockRuntimeClientBuilder.() -> Unit,
): BedrockRuntimeClient {
    val client = BedrockRuntimeClient.builder().apply(builder).build()
    try {
        client.serviceClientConfiguration()
            .endpointOverride()
            ?.requireTrustedBedrockEndpoint()
    } catch (cause: Throwable) {
        try {
            client.close()
        } finally {
            throw cause
        }
    }
    return client.apply { ShutdownQueue.register(this) }
}

inline fun bedrockRuntimeClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: BedrockRuntimeClientBuilder.() -> Unit = {},
): BedrockRuntimeClient = bedrockRuntimeClient {
    builder()
    endpoint?.requireTrustedBedrockEndpoint()?.let(::endpointOverride)
    region?.let(::region)
    credentialsProvider?.let(::credentialsProvider)
    httpClient(httpClient)
}
```

This is an explicit literal allowlist, not a DNS lookup. Add negative tests for
`http://127.0.0.2`, `http://[::2]`, missing hosts, and non-HTTP schemes, plus
positive tests for `localhost`, `127.0.0.1`, and `[::1]`. Apply the same
normalization and test matrix to the Kotlin `Url` helper in Task 5.

Mirror the same contract with `BedrockRuntimeAsyncClientBuilder`,
`SdkAsyncHttpClient`, and `SdkAsyncHttpClientProvider.defaultHttpClient`.
Both raw builder factories and the `Of` factories must validate the final
built client's `serviceClientConfiguration().endpointOverride()` before
`ShutdownQueue` registration. If validation fails, close that provisional
client exactly once and return nothing. Add sync/async regression tests where
the explicit endpoint parameter is `null` but the builder alone sets
`http://example.com`; both must reject and close before registration.
Write English KDoc for every public factory, including caller-owned close
responsibility, trusted endpoint rules, and the fact that consumers must add
the Java Bedrock Runtime SDK.

- [ ] **Step 3: Write failing Java request-builder tests**

Create `BedrockRuntimeRequestSupportTest.kt`. Cover all helper-owned fields and
builder precedence:

```kotlin
@Test
fun `request keeps required inputs and explicit inference config`() {
    val expectedInference = InferenceConfiguration.builder()
        .maxTokens(64)
        .temperature(0.2F)
        .build()
    val request = converseRequestOf(
        modelId = "model-id",
        messages = listOf(userMessageOf("hello")),
        inferenceConfig = expectedInference,
    ) {
        modelId("builder-model")
        messages(emptyList())
        inferenceConfig { maxTokens(1) }
        additionalModelRequestFields(Document.fromMap(mapOf("top_k" to Document.fromNumber(10))))
    }

    request.modelId() shouldBeEqualTo "model-id"
    request.messages().size shouldBeEqualTo 1
    request.inferenceConfig() shouldBeEqualTo expectedInference
    request.additionalModelRequestFields().shouldNotBeNull()
}

@Test
fun `null inference config preserves builder value`() {
    converseStreamRequestOf("model-id", listOf(userMessageOf("hello"))) {
        inferenceConfig { maxTokens(17) }
    }.inferenceConfig().maxTokens() shouldBeEqualTo 17
}
```

Also assert blank text/model ID and empty messages fail before any client call.

Run:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests '*BedrockRuntimeRequestSupportTest' \
  --no-daemon --no-configuration-cache
```

Expected: FAIL because request helpers do not exist.

- [ ] **Step 4: Implement Java request helpers**

Create `model/BedrockRuntimeRequestSupport.kt` with these complete contracts:

```kotlin
inline fun contentBlockOf(
    text: String,
    builder: ContentBlock.Builder.() -> Unit = {},
): ContentBlock {
    text.requireNotBlank("text")
    return ContentBlock.builder().apply(builder).text(text).build()
}

inline fun userMessageOf(
    text: String,
    builder: Message.Builder.() -> Unit = {},
): Message {
    text.requireNotBlank("text")
    return Message.builder()
        .apply(builder)
        .role(ConversationRole.USER)
        .content(contentBlockOf(text))
        .build()
}

inline fun converseRequestOf(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseRequest {
    modelId.requireNotBlank("modelId")
    messages.requireNotEmpty("messages")
    return ConverseRequest.builder().apply(builder)
        .modelId(modelId)
        .messages(messages)
        .apply { inferenceConfig?.let(::inferenceConfig) }
        .build()
}
```

Implement `converseStreamRequestOf` identically with
`ConverseStreamRequest.Builder`.
Write English KDoc for all four public model builders, including helper-owned
field precedence and model-neutral behavior.

- [ ] **Step 5: Write failing Java response-mapping tests**

Create `BedrockRuntimeResponseSupportTest.kt` and construct mixed text/tool-use
content. Verify:

```kotlin
response.textContents() shouldBeEqualTo listOf("hello", " world")
response.firstTextOrNull() shouldBeEqualTo "hello"
response.textOrEmpty() shouldBeEqualTo "hello world"
response.textOrEmpty("|") shouldBeEqualTo "hello| world"
nonTextResponse.textContents().shouldBeEmpty()
textDeltaOutput.textDeltaOrNull() shouldBeEqualTo "delta"
metadataOutput.textDeltaOrNull().shouldBeNull()
```

Include 1,000 text blocks and assert order/content without timing thresholds.

Run:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests '*BedrockRuntimeResponseSupportTest' \
  --no-daemon --no-configuration-cache
```

Expected: FAIL because response helpers do not exist.

- [ ] **Step 6: Implement Java response helpers**

Create `model/BedrockRuntimeResponseSupport.kt`:

```kotlin
fun ConverseResponse.textContents(): List<String> =
    output()?.message()?.content().orEmpty()
        .mapNotNull(ContentBlock::text)

fun ConverseResponse.firstTextOrNull(): String? =
    output()?.message()?.content().orEmpty()
        .firstNotNullOfOrNull(ContentBlock::text)

fun ConverseResponse.textOrEmpty(separator: String = ""): String =
    buildString {
        var first = true
        for (block in output()?.message()?.content().orEmpty()) {
            val text = block.text() ?: continue
            if (!first) append(separator)
            append(text)
            first = false
        }
    }

fun ConverseStreamOutput.textDeltaOrNull(): String? =
    (this as? ContentBlockDeltaEvent)?.delta()?.text()
```

These accessors are pinned to AWS SDK for Java v2 `2.47.1`: non-text Java
unions expose `null` from `text()`, and stream delta events are the concrete
`ContentBlockDeltaEvent` subtype of `ConverseStreamOutput`. Do not add a
synthetic discriminator or catch SDK exceptions.

Test absent output, an output without the message variant, and missing content
as empty text content, matching the Kotlin helper contract.
Use mocked native response/output/message objects backed by a counting
`AbstractList<ContentBlock>` to prove `firstTextOrNull()` stops at the first
text block and `textOrEmpty()` performs one iterator traversal. Add a
source-contract assertion that neither helper delegates to `textContents()`;
the full join must not allocate an intermediate text list.
Write English KDoc for every response helper and state that non-text content is
skipped while raw native SDK types remain available.

- [ ] **Step 7: Verify Java foundations GREEN**

Run:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests '*BedrockRuntimeClientSupportTest' \
  --tests '*BedrockRuntimeRequestSupportTest' \
  --tests '*BedrockRuntimeResponseSupportTest' \
  --no-daemon --no-configuration-cache
```

Expected: PASS with no network call.

- [ ] **Step 8: Commit Java foundations**

```bash
git add aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock \
  aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock
git commit -m "Establish native Bedrock request and lifecycle boundaries" \
  -m "Constraint: Helper-owned fields cannot be overridden and non-loopback HTTP endpoints are rejected
Confidence: high
Scope-risk: moderate
Tested: Java Bedrock client, request, and response unit tests
Not-tested: Remote Bedrock calls and streaming are covered later"
```

### Task 3: Add Java Sync, Future, And Suspend Converse Operations

**Complexity:** medium

**Depends on:** Task 2

**Applies:** `bluetape-kotlin-patterns`, `kotlin-coroutines-skill`, `test-driven-development`

**Write scope:** Java operation extensions and their tests

- [ ] **Step 1: Write failing sync and future delegation tests**

Create `BedrockRuntimeClientExtensionsTest.kt` and
`BedrockRuntimeAsyncClientExtensionsTest.kt`:

```kotlin
@Test
fun `sync convenience call delegates once and preserves response identity`() {
    val client = mockk<BedrockRuntimeClient>()
    val expected = ConverseResponse.builder().build()
    every { client.converse(any<ConverseRequest>()) } returns expected

    client.converse("model-id", listOf(userMessageOf("hello"))) shouldBeSameInstanceAs expected
    verify(exactly = 1) { client.converse(any<ConverseRequest>()) }
}

@Test
fun `async convenience call returns original future`() {
    val client = mockk<BedrockRuntimeAsyncClient>()
    val future = CompletableFuture.completedFuture(ConverseResponse.builder().build())
    every { client.converse(any<ConverseRequest>()) } returns future

    client.converseAsync("model-id", listOf(userMessageOf("hello"))) shouldBeSameInstanceAs future
}
```

Run:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests '*BedrockRuntimeClientExtensionsTest' \
  --tests '*BedrockRuntimeAsyncClientExtensionsTest' \
  --no-daemon --no-configuration-cache
```

Expected: FAIL because the convenience operations do not exist.

- [ ] **Step 2: Implement sync and future convenience operations**

Create the two extension files:

```kotlin
inline fun BedrockRuntimeClient.converse(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseResponse =
    converse(converseRequestOf(modelId, messages, inferenceConfig, builder))

inline fun BedrockRuntimeAsyncClient.converseAsync(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseRequest.Builder.() -> Unit = {},
): CompletableFuture<ConverseResponse> =
    converse(converseRequestOf(modelId, messages, inferenceConfig, builder))
```

Do not add raw-request overloads; native SDK members already own those.
Write English KDoc for sync/future helpers with exactly-one-call and native
response/error ownership.

- [ ] **Step 3: Write failing suspend success, failure, and cancellation tests**

Create `BedrockRuntimeAsyncClientCoroutinesExtensionsTest.kt`:

```kotlin
@Test
fun `suspend converse awaits the SDK future`() = runTest {
    val expected = ConverseResponse.builder().build()
    every { client.converse(any<ConverseRequest>()) } returns
        CompletableFuture.completedFuture(expected)

    client.converse("model-id", listOf(userMessageOf("hello"))) shouldBeSameInstanceAs expected
}

@Test
fun `cancelling coroutine cancels the future`() = runTest {
    val future = CompletableFuture<ConverseResponse>()
    every { client.converse(any<ConverseRequest>()) } returns future

    val job = launch { client.converse("model-id", listOf(userMessageOf("hello"))) }
    runCurrent()
    job.cancelAndJoin()

    future.isCancelled.shouldBeTrue()
}
```

Also complete a future exceptionally with an SDK exception and assert the same
exception type reaches the caller.

- [ ] **Step 4: Implement suspend operation with `await()`**

Create `BedrockRuntimeAsyncClientCoroutinesExtensions.kt`:

```kotlin
suspend inline fun BedrockRuntimeAsyncClient.converse(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseResponse =
    converseAsync(modelId, messages, inferenceConfig, builder).await()
```

KDoc must state that coroutine cancellation is forwarded to the SDK future and
that the extension does not close the external client or add retry/timeout.

- [ ] **Step 5: Verify Java operation GREEN**

Run:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests '*BedrockRuntime*ExtensionsTest' \
  --no-daemon --no-configuration-cache
```

Expected: PASS; MockK verifies exactly one SDK invocation per helper call.

- [ ] **Step 6: Commit Java non-streaming operations**

```bash
git add aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock \
  aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock
git commit -m "Preserve native Bedrock responses across Java call styles" \
  -m "Constraint: Sync, future, and suspend helpers issue exactly one SDK request
Confidence: high
Scope-risk: narrow
Tested: Java Bedrock sync, async, suspend, error, and cancellation tests
Not-tested: Streaming state machine is isolated in the next task"
```

### Task 4: Implement Java Streaming With Bounded Demand

**Complexity:** high

**Depends on:** Tasks 2 and 3

**Applies:** `bluetape-kotlin-patterns`, `kotlin-coroutines-skill`, `test-driven-development`

**Write scope:** Java Flow extension, deterministic test publisher, and streaming tests

- [ ] **Step 1: Create a deterministic reactive-streams test publisher**

Create `RecordingSdkPublisher.kt`. It must record every request, outstanding
demand, emitted item, and cancel call while obeying reactive-streams demand:

```kotlin
internal class RecordingSdkPublisher<T>(
    private val values: ArrayDeque<T> = ArrayDeque(),
) : SdkPublisher<T> {
    val requests = mutableListOf<Long>()
    var outstanding = 0L
        private set
    var maxOutstanding = 0L
        private set
    var cancelCount = 0
        private set
    private var subscriber: Subscriber<in T>? = null
    private var cancelled = false
    private var terminal = false

    override fun subscribe(subscriber: Subscriber<in T>) {
        check(this.subscriber == null) { "RecordingSdkPublisher supports one subscriber" }
        this.subscriber = subscriber
        subscriber.onSubscribe(object : Subscription {
            override fun request(n: Long) {
                if (n <= 0) {
                    terminal = true
                    subscriber.onError(IllegalArgumentException("Reactive Streams demand must be positive"))
                    return
                }
                if (cancelled || terminal) return
                requests += n
                outstanding += n
                maxOutstanding = maxOf(maxOutstanding, outstanding)
                emitAvailable()
            }

            override fun cancel() {
                if (!cancelled) {
                    cancelled = true
                    cancelCount++
                }
            }
        })
    }

    fun emitAvailable() {
        if (cancelled || terminal) return
        while (outstanding > 0 && values.isNotEmpty()) {
            outstanding--
            subscriber!!.onNext(values.removeFirst())
        }
    }

    fun complete() {
        if (cancelled || terminal) return
        terminal = true
        subscriber!!.onComplete()
    }

    fun fail(cause: Throwable) {
        if (cancelled || terminal) return
        terminal = true
        subscriber!!.onError(cause)
    }
}
```

Use synchronization or atomics around ledger mutations so race tests do not
depend on unsafely shared mutable state. Ordinary `emitOne`, `complete`, and
`fail` methods suppress signals after cancel/terminal. Provide separate
explicit adversarial methods for late-event/error/complete tests so
misbehaving-publisher cases cannot leak into normal assertions.

- [ ] **Step 2: Write the RED tests for coldness, incrementality, and demand**

Create `BedrockRuntimeFlowExtensionsTest.kt`. Capture the
`ConverseStreamResponseHandler` passed to the mocked async client and drive it
manually.

Required tests:

```kotlin
@Test
fun `collection is cold and each collector invokes SDK once`() = runTest {
    val flow = client.converseStreamFlow(request)
    verify(exactly = 0) { client.converseStream(any(), any()) }

    flow.toList()
    flow.toList()

    verify(exactly = 2) { client.converseStream(any(), any()) }
}

@Test
fun `first event arrives before operation future completes`() = runTest {
    val future = CompletableFuture<Void>()
    val first = contentDelta("a")
    val firstSeen = CompletableDeferred<ConverseStreamOutput>()
    val collector = launch {
        client.converseStreamFlow(request).collect { event ->
            firstSeen.complete(event)
        }
    }
    runCurrent()

    val publisher = RecordingSdkPublisher()
    handler.onEventStream(publisher)
    runCurrent()
    publisher.emitOne(first)
    runCurrent()

    firstSeen.await() shouldBeSameInstanceAs first
    future.isDone.shouldBeFalse()

    publisher.complete()
    handler.complete()
    future.complete(null)
    collector.join()
}
```

Add a slow collector test that asserts every recorded request is `1`,
`maxOutstanding == 1`, order is preserved, and no publisher-side queue grows
from unrequested values. Add a separate `first()` test proving that an
early-terminal collector cancels the active subscription and operation future
exactly once; do not use `first()` in the incrementality test because it
intentionally cancels upstream after the first element.

Run:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests '*BedrockRuntimeFlowExtensionsTest' \
  --no-daemon --no-configuration-cache
```

Expected: FAIL because streaming Flow does not exist.

- [ ] **Step 3: Define the generation-aware coordinator**

In `BedrockRuntimeFlowExtensions.kt`, keep state private to one collection:

```kotlin
private sealed interface StreamTerminal {
    data object Active : StreamTerminal
    data object Completed : StreamTerminal
    data class Failed(val cause: Throwable) : StreamTerminal
    data object Cancelled : StreamTerminal
}

private data class StreamAttempt(
    val generation: Long,
    val job: Job,
    val completion: CompletableDeferred<Result<Unit>>,
    val cancelled: AtomicBoolean = AtomicBoolean(),
)

private class StreamCoordinator<T>(
    private val scope: CoroutineScope,
    private val emit: suspend (T) -> Unit,
) {
    private val mutex = Mutex()
    private val callbackSequence = AtomicLong()
    private var generation = 0L
    private var attempt: StreamAttempt? = null
    private var futureSucceeded = false
    private var terminal: StreamTerminal = StreamTerminal.Active
}
```

Implement coordinator methods with these exact invariants:

- `replaceFromCallback(publisher)` assigns a monotonically increasing sequence
  synchronously at callback receipt, then launches `replace(sequence,
  publisher)` as a child of the collection scope.
- `replace(sequence, publisher)` claims the newest generation under `Mutex`.
  A sequence older than the claimed generation is subscribed with an
  immediate-cancel subscriber and cannot replace it.
- After claiming, detach the prior `StreamAttempt`, call its atomic
  `cancelOnce()`, and join it outside the mutex. Recheck generation and terminal
  state before subscribing. If a newer callback won while cancellation/join
  was in progress, immediately cancel this publisher instead.
- The winning generation collects `publisher.asFlow().buffer(0)`. Its
  `StreamAttempt.cancelOnce()` guards every replacement/finally/terminal path,
  so concurrent replacements cannot cancel the same subscription twice.
- Every emitted/terminal signal checks the captured generation under `Mutex`;
  old-generation signals are ignored.
- The mutex protects only non-suspending state snapshots and transitions.
  Never call `emit`/`send`, `join`, subscribe, or cancel while holding it. The
  attempt child owns the potentially suspended rendezvous `send`, so a newer
  callback can cancel the old attempt and activate its replacement without
  waiting for a slow collector.
- `futureSucceeded()` completes only after latest publisher completes, completes
  empty when no publisher arrived, and preserves latest publisher error. It is
  a suspending terminal barrier: the operation future's success path must await
  the winning attempt before the enclosing `finally` can cancel active state.
- `futureFailed(cause)` and `cancel()` atomically win once and cancel the active
  attempt exactly once.
- A publisher callback after future success is subscribed only with an
  immediate-cancel subscriber; it never replaces the current generation.
- No dispatcher switch, external scope, retry, replay, logging, or content
  deduplication is introduced.

- [ ] **Step 4: Connect the AWS response handler and operation future**

Implement the raw-request overload as a cold `channelFlow`:

```kotlin
fun BedrockRuntimeAsyncClient.converseStreamFlow(
    request: ConverseStreamRequest,
): Flow<ConverseStreamOutput> = channelFlow {
    val coordinator = StreamCoordinator<ConverseStreamOutput>(this) { send(it) }
    val handler = object : ConverseStreamResponseHandler {
        override fun responseReceived(response: ConverseStreamResponse) = Unit

        override fun onEventStream(publisher: SdkPublisher<ConverseStreamOutput>) {
            coordinator.replaceFromCallback(publisher)
        }

        override fun exceptionOccurred(throwable: Throwable) {
            coordinator.handlerFailureFromCallback(throwable)
        }

        override fun complete() {
            coordinator.handlerCompletedFromCallback()
        }
    }

    var operation: CompletableFuture<Void>? = null
    try {
        operation = converseStream(request, handler)
        operation.await()
        coordinator.futureSucceeded()
    } catch (ce: CancellationException) {
        coordinator.cancel()
        operation?.cancel(true)
        throw ce
    } catch (cause: Throwable) {
        coordinator.futureFailed(cause)
        throw cause
    } finally {
        coordinator.cancelActiveAttempt()
    }
}.buffer(0)
```

Capture and mock the generated `ConverseStreamResponseHandler`, not the
protected generic parent interface. `responseReceived` preserves no new state;
the raw events remain the public output. `exceptionOccurred` records the latest
handler-attempt failure but does not beat the operation future because it may be
called during a retry. `complete` records handler completion but does not
replace the required latest-publisher terminal plus operation-future success
barrier. `replaceFromCallback` launches only child jobs of the current
`channelFlow` scope; it must not use `GlobalScope`.
The outer `.buffer(0)` is mandatory: the inner reactive bridge bounds
subscription demand, while the outer rendezvous boundary prevents
`channelFlow`'s default capacity from queuing ahead of a slow collector.
The two public `converseStreamFlow` overloads receive English KDoc covering
cold/billable recollection, caller-owned client lifetime, `request(1)`,
collector cancellation, SDK retry semantic duplicates, and the absence of
exactly-once/deduplication.

Add the convenience overload by building
`converseStreamRequestOf(modelId, messages, inferenceConfig, builder)`.

- [ ] **Step 5: Add race, retry, and terminal RED/GREEN tests**

Extend `BedrockRuntimeFlowExtensionsTest.kt` with deterministic barriers rather
than wall-clock sleeps:

- publisher complete before future success;
- future success before publisher complete;
- future failure before any publisher;
- publisher error followed by replacement generation;
- publisher error followed by successful future and no replacement;
- replacement cancels previous subscription once;
- old generation late event/error/complete is ignored;
- partial event from generation N remains visible and generation N+1 may emit
  semantically duplicate text;
- collector cancellation before publisher callback cancels the future and
  immediately cancels the late publisher;
- callback racing with cancellation still yields one terminal outcome;
- callbacks A and B received before scheduler advancement still activate B,
  cancel A immediately, and never reorder A after B;
- callback B received while A is in cancel/join becomes the only active
  generation and does not double-cancel A;
- callback B received while A is suspended on the outer rendezvous `send`
  cancels A exactly once and activates B before the collector barrier is
  released; A cannot deliver a late item;
- synchronous `converseStream` failure after invoking `onEventStream` still
  enters `finally`, cancels the subscription once, and preserves the original
  exception;
- `withTimeout` cancels future and subscription without starting another call.

Use `CompletableDeferred`/`Channel` barriers and assert `cancelCount`,
invocation count, terminal count, and emitted identity. In the slow-collector
case, hold the collector behind a barrier and prove the publisher cannot
request/deliver the next item until the prior event crosses the outer
rendezvous boundary. Run the targeted command after each test cluster; expected
final result is PASS.

- [ ] **Step 6: Add text-delta Flow mapping with bluetape4k-coroutines**

Add:

```kotlin
fun Flow<ConverseStreamOutput>.textDeltaFlow(): Flow<String> =
    map(ConverseStreamOutput::textDeltaOrNull).castNotNull<String>()
```

Test text delta order, empty-string preservation, non-text filtering, and SDK
error propagation. Add one `takeUntil(stopSignal)` test that confirms the
repository extension ends after the next upstream event once signalled; do not
claim immediate cancellation of a silent upstream.
KDoc must state that non-text events are filtered, empty text is preserved, and
no logging, parallel mapping, retry, or replay is added.

- [ ] **Step 7: Verify Java streaming and compile diagnostics**

Run:

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true \
./gradlew \
  :bluetape4k-aws-java:test \
  --tests '*BedrockRuntimeFlowExtensionsTest' \
  :bluetape4k-aws-java:compileTestKotlin \
  --warning-mode all \
  --no-daemon \
  --no-configuration-cache
```

Expected: PASS; ledger shows `request(1)`, maximum outstanding demand one,
cancel-once, and one terminal result.

- [ ] **Step 8: Commit the Java streaming boundary**

```bash
git add aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt \
  aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/model/BedrockRuntimeResponseSupport.kt \
  aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensionsTest.kt \
  aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/RecordingSdkPublisher.kt
git commit -m "Bound Bedrock streaming demand to the collector lifecycle" \
  -m "Constraint: SDK retries may repeat semantic output but old-generation signals cannot leak
Rejected: Attempt-wide buffering | It would prevent incremental emission
Confidence: medium
Scope-risk: broad
Directive: Preserve generation checks and first-terminal-wins on future changes
Tested: Java streaming coldness, request ledger, retry, race, timeout, and cancellation tests
Not-tested: Credentialed Bedrock smoke remains opt-in"
```

### Task 5: Implement The AWS Kotlin SDK Facade

**Complexity:** high

**Depends on:** Task 1; behavior mirrors Tasks 2–4 without sharing SDK types

**Applies:** `bluetape-kotlin-patterns`, `kotlin-coroutines-skill`, `test-driven-development`

**Write scope:** AWS Kotlin Bedrock source and unit tests only

- [ ] **Step 1: Write failing Kotlin client lifecycle tests**

Create `BedrockRuntimeClientSupportTest.kt`. Verify caller-owned creation,
explicit close, block-owned close on success/error/cancellation, builder/HTTP
engine forwarding, and endpoint rejection:

```kotlin
@Test
fun `with client closes after block failure`() = runTest {
    val cause = IllegalStateException("boom")
    assertFailsWith<IllegalStateException> {
        withBedrockRuntimeClient(region = "us-east-1") {
            throw cause
        }
    } shouldBeSameInstanceAs cause
}

@Test
fun `plain HTTP endpoint must be loopback`() {
    assertFailsWith<IllegalArgumentException> {
        bedrockRuntimeClientOf(endpointUrl = Url.parse("http://example.com"))
    }
}
```

Drive close ownership through the internal factory overload defined in Step 2:
return a mocked `BedrockRuntimeClient`, run the public lifecycle body through
the seam, and verify `close()` exactly once for success, failure, and
cancellation.

- [ ] **Step 2: Implement Kotlin client ownership**

Create `BedrockRuntimeClientSupport.kt`:

```kotlin
inline fun bedrockRuntimeClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: BedrockRuntimeClient.Config.Builder.() -> Unit = {},
): BedrockRuntimeClient {
    val client = BedrockRuntimeClient {
        builder()
        endpointUrl?.requireTrustedBedrockEndpoint()?.let { this.endpointUrl = it }
        region?.let { this.region = it }
        credentialsProvider?.let { this.credentialsProvider = it }
        httpClient?.let { this.httpClient = it }
    }
    try {
        client.config.endpointUrl?.requireTrustedBedrockEndpoint()
    } catch (cause: Throwable) {
        try {
            client.close()
        } finally {
            throw cause
        }
    }
    return client
}

suspend fun <R> withBedrockRuntimeClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    builder: BedrockRuntimeClient.Config.Builder.() -> Unit = {},
    block: suspend (BedrockRuntimeClient) -> R,
): R = withBedrockRuntimeClient(
    clientFactory = {
        bedrockRuntimeClientOf(
            endpointUrl, region, credentialsProvider, httpClient, builder,
        )
    },
    block = block,
)

internal suspend fun <R> withBedrockRuntimeClient(
    clientFactory: () -> BedrockRuntimeClient,
    block: suspend (BedrockRuntimeClient) -> R,
): R = clientFactory().useSafe(block)
```

The public overload delegates ownership to the internal factory overload. Unit
tests pass a mocked `BedrockRuntimeClient` through that seam and verify
`close()` exactly once after success, failure, and cancellation; production
callers cannot replace the factory.
Write English KDoc for the two public lifecycle helpers, explicitly separating
caller-owned and block-owned clients and prohibiting collection of an escaped
cold Flow after the scoped block closes.

Validate HTTPS/loopback HTTP from `Url.scheme.protocolName` and
`Url.host.toString()` without DNS resolution.
The post-build `client.config.endpointUrl` validation is mandatory even when
the explicit `endpointUrl` argument is `null`, because the builder may set it.
Add a builder-only `http://example.com` regression test and verify that the
provisional client closes exactly once before the exception escapes.

- [ ] **Step 3: Write failing Kotlin request and response tests**

Create the two model test files. Use native sealed-union constructors:

```kotlin
contentBlockOf("hello") shouldBeEqualTo ContentBlock.Text("hello")
userMessageOf("hello").role shouldBeEqualTo ConversationRole.User

val response = ConverseResponse {
    output = ConverseOutput.Message(
        Message {
            role = ConversationRole.Assistant
            content = listOf(ContentBlock.Text("a"), ContentBlock.Text("b"))
        },
    )
}
response.textContents() shouldBeEqualTo listOf("a", "b")
response.textOrEmpty() shouldBeEqualTo "ab"
```

Verify the same inference-config precedence, blank/empty rejection, mixed
content filtering, empty delta preservation, and 1,000-block single-pass join
as Java.

- [ ] **Step 4: Implement Kotlin model helpers**

Create `model/BedrockRuntimeRequestSupport.kt`:

```kotlin
fun contentBlockOf(text: String): ContentBlock {
    text.requireNotBlank("text")
    return ContentBlock.Text(text)
}

inline fun userMessageOf(
    text: String,
    crossinline builder: Message.Builder.() -> Unit = {},
): Message {
    text.requireNotBlank("text")
    return Message {
        builder()
        role = ConversationRole.User
        content = listOf(ContentBlock.Text(text))
    }
}
```

For request builders, apply the builder first, then set `modelId`, `messages`,
and non-null `inferenceConfig`.

Create `model/BedrockRuntimeResponseSupport.kt`:

```kotlin
fun ConverseResponse.textContents(): List<String> =
    output?.asMessageOrNull()?.content.orEmpty()
        .mapNotNull(ContentBlock::asTextOrNull)

fun ConverseResponse.firstTextOrNull(): String? =
    output?.asMessageOrNull()?.content.orEmpty()
        .firstNotNullOfOrNull(ContentBlock::asTextOrNull)

fun ConverseResponse.textOrEmpty(separator: String = ""): String =
    buildString {
        var first = true
        for (block in output?.asMessageOrNull()?.content.orEmpty()) {
            val text = block.asTextOrNull() ?: continue
            if (!first) append(separator)
            append(text)
            first = false
        }
    }

fun ConverseStreamOutput.textDeltaOrNull(): String? =
    asContentBlockDeltaOrNull()?.delta?.asTextOrNull()
```

Write English KDoc for every public Kotlin model/response helper, covering
helper-owned precedence, sealed-union filtering, empty values, and native SDK
type preservation. Mirror the Java counting-list and source-contract tests:
`firstTextOrNull()` stops at the first text block, `textOrEmpty()` traverses
content once, neither delegates to `textContents()`, and the join allocates no
intermediate text list.

- [ ] **Step 5: Write failing native suspend and Flow tests**

Create `BedrockRuntimeClientExtensionsTest.kt` and
`BedrockRuntimeFlowExtensionsTest.kt`. Mock the native suspend operations and
verify:

- convenience `converse` delegates once and preserves response identity;
- SDK exception and coroutine cancellation propagate unchanged;
- Flow is cold and invokes `converseStream` once per collection;
- `response.stream == null` completes empty only after successful operation;
- native event order/error/cancellation is preserved without extra buffering;
- `withBedrockRuntimeClient` examples collect before the owned client closes;
- a barrier-backed active stream cancellation records
  `stream-finally -> client-close-once -> caller cancellation`, proving close
  does not race ahead of upstream structured cancellation;
- `textDeltaFlow()` uses `castNotNull`, preserves empty text, and filters
  non-text events;
- `takeUntil` test uses its next-upstream-event termination contract.

- [ ] **Step 6: Implement Kotlin operations and Flow**

Create `BedrockRuntimeClientExtensions.kt`:

```kotlin
suspend inline fun BedrockRuntimeClient.converse(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    crossinline builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseResponse =
    converse(converseRequestOf(modelId, messages, inferenceConfig, builder))
```

Create `BedrockRuntimeFlowExtensions.kt`:

```kotlin
fun BedrockRuntimeClient.converseStreamFlow(
    request: ConverseStreamRequest,
): Flow<ConverseStreamOutput> = flow {
    converseStream(request) { response ->
        response.stream?.collect { emit(it) }
    }
}

inline fun BedrockRuntimeClient.converseStreamFlow(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    crossinline builder: ConverseStreamRequest.Builder.() -> Unit = {},
): Flow<ConverseStreamOutput> =
    converseStreamFlow(
        converseStreamRequestOf(modelId, messages, inferenceConfig, builder),
    )

fun Flow<ConverseStreamOutput>.textDeltaFlow(): Flow<String> =
    map(ConverseStreamOutput::textDeltaOrNull).castNotNull<String>()
```

Do not catch exceptions or switch dispatchers.
Write English KDoc for all Kotlin operation/Flow helpers, including native
structured cancellation, cold/billable recollection, scoped-client collection,
and no added retry/replay/logging.

- [ ] **Step 7: Verify Kotlin facade GREEN**

Run:

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true \
./gradlew \
  :bluetape4k-aws-kotlin:test \
  --tests '*BedrockRuntime*Test' \
  :bluetape4k-aws-kotlin:compileTestKotlin \
  --warning-mode all \
  --no-daemon \
  --no-configuration-cache
```

Expected: PASS; no real AWS calls.

- [ ] **Step 8: Commit the Kotlin facade**

```bash
git add aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/bedrock \
  aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/bedrock
git commit -m "Keep native Bedrock suspension inside explicit client ownership" \
  -m "Constraint: Scoped clients must finish Flow collection before useSafe closes them
Confidence: high
Scope-risk: moderate
Tested: Kotlin Bedrock model, lifecycle, suspend, Flow, error, and cancellation tests
Not-tested: Credentialed Bedrock smoke remains opt-in"
```

### Task 6: Prove Opt-In Smoke And Publication Boundaries

**Complexity:** medium

**Depends on:** Tasks 1–5

**Applies:** `bluetape-kotlin-patterns`, `test-driven-development`

**Write scope:** module test configuration, Bedrock smoke tests, root consumer
fixture tasks, and isolated consumer sources

- [ ] **Step 1: Exclude credentialed smoke tests by default**

Add the same JUnit tag policy to `aws-java/build.gradle.kts` and
`aws-kotlin/build.gradle.kts`:

```kotlin
val bedrockSmokeRequested = providers.gradleProperty("bedrockSmoke").isPresent
val bedrockSmokeMissingInputs = listOf("BEDROCK_REGION", "BEDROCK_MODEL_ID")
    .filter { providers.environmentVariable(it).orNull.isNullOrBlank() }
val bedrockSmokeEnabled = bedrockSmokeRequested && bedrockSmokeMissingInputs.isEmpty()

tasks.test {
    systemProperty("bluetape4k.aws.emulator", System.getProperty("bluetape4k.aws.emulator", "floci"))
    useJUnitPlatform {
        if (bedrockSmokeEnabled) {
            includeTags("bedrock-smoke")
        } else {
            excludeTags("bedrock-smoke")
        }
    }
    onlyIf("Bedrock smoke inputs are complete before client creation") {
        val runnable = !bedrockSmokeRequested || bedrockSmokeEnabled
        if (!runnable) {
            logger.lifecycle(
                "bedrock-smoke: SKIP before client creation; missing={}",
                bedrockSmokeMissingInputs.joinToString(","),
            )
        }
        runnable
    }
}
```

Only the conjunction of `-PbedrockSmoke`, non-blank `BEDROCK_REGION`, and
non-blank `BEDROCK_MODEL_ID` includes the smoke tag. Property absence runs
ordinary tests with the tag excluded. Property presence with either environment
value missing skips the task with the explicit pre-client reason above.

- [ ] **Step 2: Add one model-neutral smoke test per SDK**

Create both `BedrockRuntimeSmokeTest.kt` files with
`@Tag("bedrock-smoke")`. Recheck both environment values before constructing a
client so a direct test runner also fails closed. Use one user message,
`maxTokens = 8`, the native `Converse` response, and no provider-specific
phrase/model assertion.

Java uses a 30-second `ClientOverrideConfiguration.apiCallTimeout` and
`bedrockRuntimeClientOf(region = Region.of(region)).use { ... }`. Kotlin sets
`callTimeout = 30.seconds` in the scoped builder and wraps the call in
`withTimeout(35.seconds)`. Both assert at least one text content block.

Record only an allowlisted evidence line: lane, pass/fail, elapsed milliseconds,
approved region, approved model ID, and request ID. Java reads
`response.responseMetadata().requestId()`. AWS Kotlin SDK `1.8.0` does not expose
request metadata on `ConverseResponse`, so its success evidence records
`requestId=not-exposed-by-sdk-1.8.0`; a service failure may record the SDK
exception request ID when available. Never log prompt, generated output,
credentials, endpoint secrets, or a raw exception body.

Each smoke test must catch SDK/service and transport failures before JUnit can
render the original throwable. Rethrow `CancellationException` unchanged; for
all other failures, extract only exception class, SDK error code when
available, request ID when available, lane, elapsed time, approved region, and
approved model ID. Fail with a new sanitized `AssertionError` that has no
original cause or suppressed exception. Never include `message`, stack trace,
endpoint, headers, or response body. Add an offline unit test with a fake SDK
exception whose message/cause contain sentinel secrets; assert the sanitized
failure contains only the allowlisted fields and neither sentinel.

- [ ] **Step 3: Prove default tests remain offline**

Run without the opt-in property:

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew \
  :bluetape4k-aws-java:test \
  :bluetape4k-aws-kotlin:test \
  --tests '*BedrockRuntime*Test' \
  --no-daemon \
  --no-configuration-cache
```

Expected: all unit tests pass and the tagged smoke methods are absent from the
executed test report. Preserve the XML report count as DoD evidence.

- [ ] **Step 4: Document but do not require the credentialed command**

The operator command is:

```bash
BEDROCK_REGION=us-east-1 \
BEDROCK_MODEL_ID='<enabled-model-id>' \
./gradlew \
  :bluetape4k-aws-java:test \
  :bluetape4k-aws-kotlin:test \
  -PbedrockSmoke \
  --no-daemon \
  --no-configuration-cache
```

Run it only when both variables and usable AWS credentials are intentionally
available. Otherwise run once with `-PbedrockSmoke` and the known missing
variables to capture the explicit pre-client `SKIP` line, then record
`N/A: credentialed Bedrock invocation is opt-in; <skip reason>` without
weakening the ordinary completion gate.

- [ ] **Step 5: Verify the published metadata stays service-SDK-neutral**

Run:

```bash
./gradlew \
  :bluetape4k-aws-java:generateMetadataFileForBluetapeAwsPublication \
  :bluetape4k-aws-java:generatePomFileForBluetapeAwsPublication \
  :bluetape4k-aws-kotlin:generateMetadataFileForBluetapeAwsPublication \
  :bluetape4k-aws-kotlin:generatePomFileForBluetapeAwsPublication \
  --no-daemon \
  --no-configuration-cache

ruby scripts/publication/validate_poms.rb

test -f aws-java/build/publications/BluetapeAws/pom-default.xml
test -f aws-java/build/publications/BluetapeAws/module.json
test -f aws-kotlin/build/publications/BluetapeAws/pom-default.xml
test -f aws-kotlin/build/publications/BluetapeAws/module.json

if rg -n 'bedrockruntime' \
  aws-java/build/publications/BluetapeAws/pom-default.xml \
  aws-java/build/publications/BluetapeAws/module.json \
  aws-kotlin/build/publications/BluetapeAws/pom-default.xml \
  aws-kotlin/build/publications/BluetapeAws/module.json; then
  exit 1
fi

./gradlew \
  :bluetape4k-aws-java:dependencyInsight \
  --dependency bedrockruntime \
  --configuration runtimeClasspath \
  --no-daemon --no-configuration-cache

./gradlew \
  :bluetape4k-aws-kotlin:dependencyInsight \
  --dependency bedrockruntime \
  --configuration runtimeClasspath \
  --no-daemon --no-configuration-cache
```

Expected: the publication audit passes and neither published POM nor Gradle
module metadata makes either Bedrock service SDK runtime-transitive. Both
runtimeClasspath reports must state that no matching dependency was found.

- [ ] **Step 6: Compile isolated consumer fixtures**

Create one Kotlin source under each `src/consumerFixture` path. The Java fixture
imports `bedrockRuntimeAsyncClientOf`, `userMessageOf`,
`converseStreamFlow`, and `textDeltaFlow`; the Kotlin fixture imports the
equivalent native-client helpers. Each function returns `Flow<String>` without
running a client, proving the public facade and native SDK types compile.

In root `build.gradle.kts`, create two resolvable configurations. The Java
fixture classpath must explicitly add:

```kotlin
project(":bluetape4k-aws-java")
libs.aws2.bedrock.runtime
bt4k.bluetape4k.coroutines
libs.kotlinx.coroutines.core
libs.kotlinx.coroutines.reactive
```

The Kotlin fixture classpath must explicitly add:

```kotlin
project(":bluetape4k-aws-kotlin")
libs.aws.kotlin.bedrock.runtime
bt4k.bluetape4k.coroutines
libs.kotlinx.coroutines.core
```

Import `org.gradle.api.artifacts.Configuration`,
`org.jetbrains.kotlin.gradle.dsl.JvmTarget`, and
`org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile`. Register the isolated
configurations and compile tasks with the existing consumer-fixture pattern:

```kotlin
fun Configuration.configureBedrockConsumerFixtureVersions() {
    isCanBeConsumed = false
    isCanBeResolved = true
    resolutionStrategy.eachDependency {
        when (requested.group) {
            "software.amazon.awssdk" -> useVersion(bt4kVersion("aws2"))
            "aws.sdk.kotlin" -> useVersion(bt4kVersion("aws-kotlin"))
            "org.jetbrains.kotlinx" -> if (requested.name.startsWith("kotlinx-coroutines")) {
                useVersion(bt4kVersion("kotlinx-coroutines"))
            }
        }
        because("consumer fixture resolves versions from the central bt4k catalog")
    }
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}

val bedrockJavaConsumerFixtureClasspath =
    configurations.create("bedrockJavaConsumerFixtureClasspath") {
        configureBedrockConsumerFixtureVersions()
    }
val bedrockKotlinConsumerFixtureClasspath =
    configurations.create("bedrockKotlinConsumerFixtureClasspath") {
        configureBedrockConsumerFixtureVersions()
    }

dependencies {
    bedrockJavaConsumerFixtureClasspath(project(":bluetape4k-aws-java"))
    bedrockJavaConsumerFixtureClasspath(libs.aws2.bedrock.runtime)
    bedrockJavaConsumerFixtureClasspath(bt4kLibrary("bluetape4k-coroutines"))
    bedrockJavaConsumerFixtureClasspath(libs.kotlinx.coroutines.core)
    bedrockJavaConsumerFixtureClasspath(libs.kotlinx.coroutines.reactive)

    bedrockKotlinConsumerFixtureClasspath(project(":bluetape4k-aws-kotlin"))
    bedrockKotlinConsumerFixtureClasspath(libs.aws.kotlin.bedrock.runtime)
    bedrockKotlinConsumerFixtureClasspath(bt4kLibrary("bluetape4k-coroutines"))
    bedrockKotlinConsumerFixtureClasspath(libs.kotlinx.coroutines.core)
}

fun registerBedrockConsumerFixtureCompile(
    name: String,
    sourcePath: String,
    classpath: Configuration,
    outputPath: String,
    moduleJarTask: String,
) = tasks.register<KotlinJvmCompile>(name) {
    description = "Compiles a minimal external Bedrock consumer."
    group = "verification"
    source(fileTree(sourcePath) { include("**/*.kt") })
    libraries.from(classpath)
    destinationDirectory.set(layout.buildDirectory.dir(outputPath))
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    dependsOn(moduleJarTask)
}

val compileBedrockJavaConsumerFixture = registerBedrockConsumerFixtureCompile(
    "compileBedrockJavaConsumerFixture",
    "aws-java/src/consumerFixture/kotlin",
    bedrockJavaConsumerFixtureClasspath,
    "consumer-fixtures/aws-java-bedrock/classes",
    ":bluetape4k-aws-java:jar",
)
val compileBedrockKotlinConsumerFixture = registerBedrockConsumerFixtureCompile(
    "compileBedrockKotlinConsumerFixture",
    "aws-kotlin/src/consumerFixture/kotlin",
    bedrockKotlinConsumerFixtureClasspath,
    "consumer-fixtures/aws-kotlin-bedrock/classes",
    ":bluetape4k-aws-kotlin:jar",
)

tasks.named("check") {
    dependsOn(compileBedrockJavaConsumerFixture)
    dependsOn(compileBedrockKotlinConsumerFixture)
}
```

If the Kotlin Gradle plugin rejects a task property during the RED compile,
inspect the plugin's existing task API and correct the registration before
writing facade code; do not weaken the isolated classpath. Then run:

```bash
./gradlew \
  compileBedrockJavaConsumerFixture \
  compileBedrockKotlinConsumerFixture \
  --no-daemon \
  --no-configuration-cache
```

Expected: both fixtures compile only because the consumer configurations
explicitly provide the Bedrock and coroutine/reactive dependencies omitted from
published runtime metadata.

- [ ] **Step 7: Commit smoke, consumer, and metadata guards**

```bash
git add build.gradle.kts aws-java/build.gradle.kts aws-kotlin/build.gradle.kts \
  aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeSmokeTest.kt \
  aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeSmokeTest.kt \
  aws-java/src/consumerFixture/kotlin/io/bluetape4k/aws/bedrock/consumer/JavaBedrockConsumerFixture.kt \
  aws-kotlin/src/consumerFixture/kotlin/io/bluetape4k/aws/kotlin/bedrock/consumer/KotlinBedrockConsumerFixture.kt
git commit -m "Keep Bedrock credentials outside the default verification path" \
  -m "Constraint: Real model invocation requires an explicit property, region, model, and credentials
Confidence: high
Scope-risk: moderate
Directive: Do not move credentialed Bedrock calls into ordinary CI
Tested: Default tagged-test exclusion, isolated consumer compile, and publication metadata audit
Not-tested: Real Bedrock invocation unless operator credentials are intentionally supplied"
```

### Task 7: Publish Bilingual Guidance And Streaming Diagrams

**Complexity:** high

**Depends on:** Tasks 2–6 and the final source shape

**Applies:** `bluetape-writer`, `bluetape-diagram`

**Write scope:** four module READMEs, two locale diagram pairs, `CHANGELOG.md`,
and `WIP.md`

- [ ] **Step 1: Add source-aligned Java and Kotlin guidance**

Update the English and Korean README for each module. Keep locale structure
aligned while writing natural Korean prose. Do not modify the release-bound
`docs/manual/` pages: their manifest is pinned to `0.4.0`, and the new Bedrock
source/diagram assets do not exist in that peeled release tree. The detailed
manual update belongs to the `0.5.0` release-manual refresh; record that
boundary in the PR DoD instead of introducing dead release links.

Each README pair must include:

- a copyable Gradle snippet importing
  `io.github.bluetape4k:bluetape4k-dependencies:<version>`, then the facade
  module and consumer-owned service/runtime dependencies;
- Java's snippet must include `bluetape4k-aws-java`,
  `software.amazon.awssdk:bedrockruntime`, `bluetape4k-coroutines`,
  `kotlinx-coroutines-core`, and `kotlinx-coroutines-reactive`;
- Kotlin's snippet must include `bluetape4k-aws-kotlin` and
  `aws.sdk.kotlin:bedrockruntime`;
- model-neutral `Converse` and `ConverseStream` examples using native SDK types;
- Java sync/future/suspend choices and Kotlin native suspend behavior;
- caller-owned clients versus Kotlin `withBedrockRuntimeClient`;
- terminal collection inside the scoped client block;
- `textDeltaFlow()` with `castNotNull` and a cooperative
  `takeUntil(stopSignal)` example that states termination occurs only on the
  next upstream event and does not hard-stop a silent stream;
- a separate `withTimeout` example for a hard caller-owned deadline, stating
  that timeout cancels the SDK call/subscription while already emitted partial
  output remains visible;
- cold-flow cost warning: every collection is a new billable invocation and
  may produce a different result;
- Java SDK retry warning: already emitted partial text may be semantically
  duplicated, with no exactly-once or deduplication guarantee;
- non-streaming `Converse` as the safer choice for transactional consumption;
- public failure/cancellation contract: helper validation uses
  `IllegalArgumentException`, AWS failures retain native SDK exception types,
  exceptional futures stay exceptional, coroutine/collector cancellation
  propagates upstream without facade-added retry, and streamed partial output
  is not rolled back;
- lifecycle/error safety: externally supplied clients remain caller-owned and
  raw SDK exceptions must not be logged or returned wholesale;
- endpoint/credential trust, untrusted generated output, no automatic tool
  execution, and allowlist-only operational logging.

Do not use `Flow.log`, `FlowEvent`, parallel mapping, provider-specific prompt
DTOs, or a prompt framework in code samples. The locale parity check in Step 5
must verify the cooperative-vs-hard termination distinction and every
error/cancellation/lifecycle statement above in all four READMEs.

- [ ] **Step 2: Design one locale-specific sequence diagram family**

Before drawing, open `aws-java-sequence-03.png` and
`aws-kotlin-sequence-03.png` at full size with the local image viewer and
record the observed palette, participant-card, numbered-message, alt-frame,
lifeline, typography, and warning-card conventions. Then use their SVG sources
as editable references and create:

```text
docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-en-01.svg
docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-en-01.png
docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-ko-01.svg
docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-ko-01.png
```

Reuse the official Amazon Bedrock icon from:

```text
docs/assets/aws-icons/official-04302026/Architecture-Service-Icons_04302026/Arch_Artificial-Intelligence/48/Arch_Amazon-Bedrock_48.svg
```

The sequence must show collector, facade, `bluetape4k-coroutines`, Java
`SdkPublisher` or Kotlin native Flow, Bedrock client, and Amazon Bedrock. Include
numbered request/event messages, Java `request(1)`, retry publisher replacement,
old-generation cancellation, late-signal discard, semantic duplicate warning,
normal/error branches, collector cancellation, and a prominent per-collection
cost warning. English and Korean assets use separate reader-facing text.

- [ ] **Step 3: Normalize, render, and audit both SVGs**

Run:

```bash
diagram_scripts="${CODEX_HOME:-$HOME/.codex}/skills/bluetape-diagram/scripts"
svg_en=docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-en-01.svg
svg_ko=docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-ko-01.svg

python3 "$diagram_scripts/diagram-svg-text-normalize.py" --write "$svg_en" "$svg_ko"
xmllint --noout "$svg_en" "$svg_ko"
node docs/diagram-validation/validate-readme-diagram-svg.mjs "$svg_en" "$svg_ko"
python3 "$diagram_scripts/diagram-connector-audit.py" "$svg_en" "$svg_ko"
python3 "$diagram_scripts/diagram-geometry-audit.py" --fail-diagonal "$svg_en" "$svg_ko"
python3 "$diagram_scripts/diagram-endpoint-audit.py" "$svg_en" "$svg_ko"
python3 "$diagram_scripts/diagram-mixed-corner-audit.py" "$svg_en" "$svg_ko"
python3 "$diagram_scripts/diagram-sequence-style-audit.py" "$svg_en" "$svg_ko"

~/.local/bin/cairosvg "$svg_en" \
  -o docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-en-01.png \
  -s 2
~/.local/bin/cairosvg "$svg_ko" \
  -o docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-ko-01.png \
  -s 2
```

Then use `sips` to prove each PNG is exactly twice its SVG viewport and inspect
both PNGs at full size with the local image viewer. Check label collisions,
arrow endpoints, branch frames, retry/cancel semantics, Korean glyphs, and the
cost warning before continuing.

Record reproducible dimensions with:

```bash
xmllint --xpath \
  'concat(/*[local-name()="svg"]/@width,"x",/*[local-name()="svg"]/@height)' \
  "$svg_en"
xmllint --xpath \
  'concat(/*[local-name()="svg"]/@width,"x",/*[local-name()="svg"]/@height)' \
  "$svg_ko"
sips -g pixelWidth -g pixelHeight \
  docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-en-01.png
sips -g pixelWidth -g pixelHeight \
  docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-ko-01.png
```

The review artifact records both SVG viewports, both PNG dimensions, and the
2x comparison result.

- [ ] **Step 4: Align repository status surfaces**

Add issue #312 to `CHANGELOG.md` under `Unreleased / Added`. Refresh `WIP.md`
from live GitHub state immediately before editing:

```bash
gh issue view 312 --repo bluetape4k/bluetape4k-aws \
  --json number,title,state,milestone,labels,url
gh pr list --repo bluetape4k/bluetape4k-aws --state open \
  --json number,title,headRefName,baseRefName,state,url
gh api 'repos/bluetape4k/bluetape4k-aws/milestones?state=all&per_page=100' \
  --jq '.[] | select(.title == "0.5.0") | {title,state,open_issues,closed_issues}'
```

Remove #312 from `Backlog`, add it to `Active Queue` with the observed
milestone and branch, and update snapshot/date/counts from those results
without rewriting unrelated backlog entries.

- [ ] **Step 5: Verify document and asset contracts**

Run:

```bash
./gradlew exportManualModuleInventory --no-daemon --no-configuration-cache
ruby scripts/manual/manual_contract_test.rb
ruby scripts/manual/validate_manuals.rb
ruby scripts/manual/export_manifest.rb \
  docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
git diff --check
```

Also resolve every new Markdown image/link from its containing file and verify
the English files reference only the English diagram and the Korean files only
the Korean diagram.

- [ ] **Step 6: Commit documentation and authoritative assets**

```bash
git add aws-java/README.md aws-java/README.ko.md \
  aws-kotlin/README.md aws-kotlin/README.ko.md \
  docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-*-01.svg \
  docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-*-01.png \
  CHANGELOG.md WIP.md
git commit -m "Explain Bedrock streaming costs and lifecycle before adoption" \
  -m "Constraint: Each locale requires editable SVG and authoritative 2x PNG assets
Rejected: Shared bilingual diagram | Reader-facing warnings must remain natural in each locale
Confidence: high
Scope-risk: moderate
Directive: Keep retry duplication and client ownership warnings aligned with source behavior
Tested: Manual contracts, SVG audits, 2x renders, links, and full-size inspection
Not-tested: Credentialed Bedrock smoke remains opt-in"
```

### Task 8: Run Type A Review, Record Lessons, And Open The PR

**Complexity:** high

**Depends on:** Tasks 1–7

**Applies:** `requesting-code-review`, `verification-before-completion`,
`finishing-a-development-branch`, `bluetape-workflow`

**Write scope:** review/lesson artifacts plus corrections required by review

- [ ] **Step 1: Run the complete local verification matrix**

Run targeted tests first, then the full modules sequentially because emulator
tests share Docker resources:

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew \
  :bluetape4k-aws-java:test \
  --tests '*BedrockRuntime*Test' \
  --no-daemon --no-configuration-cache

JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew \
  :bluetape4k-aws-kotlin:test \
  --tests '*BedrockRuntime*Test' \
  --no-daemon --no-configuration-cache

JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew \
  :bluetape4k-aws-java:test \
  :bluetape4k-aws-kotlin:test \
  --no-daemon --no-configuration-cache

./gradlew detekt --no-daemon --no-configuration-cache
./gradlew build -x test --parallel --no-daemon --no-configuration-cache
./gradlew \
  compileBedrockJavaConsumerFixture \
  compileBedrockKotlinConsumerFixture \
  :bluetape4k-aws-java:generateMetadataFileForBluetapeAwsPublication \
  :bluetape4k-aws-java:generatePomFileForBluetapeAwsPublication \
  :bluetape4k-aws-kotlin:generateMetadataFileForBluetapeAwsPublication \
  :bluetape4k-aws-kotlin:generatePomFileForBluetapeAwsPublication \
  --no-daemon --no-configuration-cache
ruby scripts/publication/validate_poms.rb
test -f aws-java/build/publications/BluetapeAws/pom-default.xml
test -f aws-java/build/publications/BluetapeAws/module.json
test -f aws-kotlin/build/publications/BluetapeAws/pom-default.xml
test -f aws-kotlin/build/publications/BluetapeAws/module.json
if rg -n 'bedrockruntime' \
  aws-java/build/publications/BluetapeAws/pom-default.xml \
  aws-java/build/publications/BluetapeAws/module.json \
  aws-kotlin/build/publications/BluetapeAws/pom-default.xml \
  aws-kotlin/build/publications/BluetapeAws/module.json; then
  exit 1
fi
git diff --check
```

Record task names, counts, outcome, and the Colima socket/JDK attach environment
needed to reproduce the evidence. A failed prerequisite stops downstream review
until repaired.

- [ ] **Step 2: Run Step 6-R independent code review**

Review the exact branch diff through Developer/API, stability, operator/ops,
security, user/caller, and performance lenses. Require file/line evidence and
P0–P3 severity. Integrate findings in
`docs/review/2026-07-23-issue-312-code-review.md`; fix every P0/P1, rerun the
affected lens and validation, and proceed only at P0=0/P1=0.

The review must challenge:

- public signatures against the pinned Java/Kotlin SDK sources;
- Java generation/terminal races, `request(1)`, cancel-once, and late signals;
- Kotlin cancellation and scoped close ownership;
- `compileOnly` publication/runtime behavior;
- prompt/output logging and endpoint trust;
- per-collection cost, retry semantic duplicates, and diagram accuracy;
- allocation/order behavior for large text responses.

- [ ] **Step 3: Record the required Type A lesson**

Create `docs/lessons/2026-07-23-issue-312-bedrock-runtime.md` in Korean and add
it to `docs/lessons/README.md`. Include reusable evidence for:

- repairing workflow state after the worktree was created before machine
  workflow initialization;
- distinguishing Colima/socket/JDK attach environment failures from code
  regressions;
- verifying generated SDK union shapes from the pinned source artifacts before
  freezing a public API;
- preserving SDK retries without falsely promising exactly-once stream output;
- why `castNotNull` and `takeUntil` were reused while `Flow.log`, `FlowEvent`,
  and parallel mapping were rejected;
- keeping editable SVG and authoritative 2x PNG assets in sync.

- [ ] **Step 4: Run Step 7-R final integration review**

Re-read the approved spec, implementation plan, final diff, test evidence,
review artifact, lesson, READMEs, unchanged release-bound manual contracts,
diagrams, `CHANGELOG.md`, and `WIP.md`. Confirm every spec traceability row has
concrete proof, every new public declaration has English KDoc, no public
contract drift remains, all P0/P1 counts are zero, and no unrelated changes
entered the branch.

- [ ] **Step 5: Commit review and lesson evidence**

```bash
git add docs/review/2026-07-23-issue-312-code-review.md \
  docs/lessons/2026-07-23-issue-312-bedrock-runtime.md \
  docs/lessons/README.md
git commit -m "Preserve the Bedrock delivery evidence for future SDK changes" \
  -m "Constraint: Type A completion requires converged review and a reusable lesson
Confidence: high
Scope-risk: narrow
Directive: Revalidate generated SDK unions and stream terminal races on upgrades
Tested: Step 6-R and Step 7-R P0=0 P1=0 plus the complete local verification matrix
Not-tested: Credentialed Bedrock smoke unless explicitly recorded in the lesson"
```

- [ ] **Step 6: Open the authorized pull request**

Verify branch and exact head first:

```bash
repo-status
repo-diff origin/develop...HEAD
git log --oneline --decorate origin/develop..HEAD
git push -u origin feat/issue-312-bedrock-runtime
```

Create the PR in `bluetape4k/bluetape4k-aws` with base `develop` and head
`feat/issue-312-bedrock-runtime`. The English body must accurately summarize
native SDK facades, Flow/cancellation behavior, dependency ownership,
documentation/diagram assets, and test evidence. Its final level-two section
must be `## DoD Status`.

Use:

```bash
gh pr create \
  --repo bluetape4k/bluetape4k-aws \
  --base develop \
  --head feat/issue-312-bedrock-runtime \
  --title 'feat(aws): add Bedrock Runtime minimal facade' \
  --body-file build/issue-312-pr-body.md
```

- [ ] **Step 7: Refresh WIP against the newly opened PR**

Immediately query the created PR and issue:

```bash
gh pr view --repo bluetape4k/bluetape4k-aws \
  feat/issue-312-bedrock-runtime \
  --json number,title,state,headRefName,headRefOid,baseRefName,url
gh issue view 312 --repo bluetape4k/bluetape4k-aws \
  --json number,state,milestone,url
```

Replace `WIP.md`'s stale `Open PRs: None` row with the observed PR number,
branch, and status; keep #312 active until merge. Run the document contracts
and `git diff --check`, then commit and push:

```bash
git add WIP.md
git commit -m "Keep the Bedrock work queue aligned with its live pull request" \
  -m "Constraint: WIP status must name the actual PR and exact feature branch
Confidence: high
Scope-risk: narrow
Tested: Live issue/PR query, manual contract tests, and git diff --check
Not-tested: Merge remains separately approval-gated"
git push
```

Treat this WIP-only commit as a new exact head: review its delta independently,
refresh the PR body/DoD or review provenance if the head SHA changed, and
re-check that no code or diagram artifact changed.

- [ ] **Step 8: Verify the exact PR head and stop at merge-ready**

Poll current checks, reviews, and unresolved threads. Confirm the PR head SHA
equals local `HEAD`, refresh review provenance after every correction commit,
and report the exact PR/head plus merge-ready evidence. Do not enable
auto-merge, merge, publish, tag, release, or delete the branch/worktree without
a fresh explicit user approval.
