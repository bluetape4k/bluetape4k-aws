# Spring Boot Kinesis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Spring Boot 4 Kinesis auto-configuration and coroutine operations to `bluetape4k-aws-spring-boot`.

**Architecture:** Follow the existing SNS/SQS pattern: configure a Java SDK v2 `KinesisAsyncClient`, expose a `KinesisOperations` interface, and implement it with a `KinesisCoroutinesTemplate`. Keep listener/checkpoint runtime out of this PR; expose only explicit operations plus a cold single-shard `Flow`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 auto-configuration, AWS SDK for Java v2 Kinesis, Kotlin coroutines Flow, JUnit 5, MockK, bluetape4k-assertions, ApplicationContextRunner, optional Floci/LocalStack smoke tests.

---

## File Structure

- Modify `aws-spring-boot/build.gradle.kts`: add `libs.aws2.kinesis` as `compileOnly` and `testImplementation`.
- Create `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisProperties.kt`: service properties and validation.
- Create `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisRequests.kt`: named request values to avoid same-typed parameter mistakes.
- Create `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisStartingPosition.kt`: Spring-local Java SDK v2 shard iterator position model.
- Create `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisRecordFlowOptions.kt`: Spring-local Flow polling and retry options.
- Create `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisOperations.kt`: public coroutine API.
- Create `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisCoroutinesTemplate.kt`: Java SDK v2 async-client implementation.
- Create `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisAutoConfiguration.kt`: conditional Spring Boot auto-configuration.
- Modify `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`: register Kinesis auto-configuration.
- Create `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/NoopKinesisOperations.kt`: test override object.
- Create `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisAutoConfigurationTest.kt`: conditional bean/property/customizer tests.
- Create `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisCoroutinesTemplateTest.kt`: deterministic MockK request-mapping tests.
- Create `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisCoroutinesTemplateAwsEmulatorTest.kt`: focused emulator smoke if reliable.
- Modify root/module README locale set and service coverage chart after code/tests pass.
- Create `docs/lessons/2026-06-30-issue-270-spring-kinesis.md`.

## Task 1: Dependency and Auto-Configuration Slice

complexity: medium
sub-skill: `bluetape4k-code-patterns`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`
verification: `./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisAutoConfigurationTest' --no-configuration-cache`

**Files:**
- Modify: `aws-spring-boot/build.gradle.kts`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisProperties.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisAutoConfiguration.kt`
- Modify: `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisAutoConfigurationTest.kt`
- Test helper: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/NoopKinesisOperations.kt`

- [ ] **Step 1: Add Kinesis SDK dependency**

Add to `aws-spring-boot/build.gradle.kts` near the other AWS SDK v2 dependencies:

```kotlin
compileOnly(libs.aws2.kinesis)
testImplementation(libs.aws2.kinesis)
```

- [ ] **Step 2: Write failing auto-configuration tests**

Create `KinesisAutoConfigurationTest` with tests named:

```kotlin
class KinesisAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                KinesisAutoConfiguration::class.java,
            )
        )
        .withPropertyValues("bluetape4k.aws.kinesis.region=us-east-1")

    @Test
    fun `register Kinesis client and operations`() {
        contextRunner.run { context ->
            context shouldHaveSingleBean KinesisAsyncClient::class
            context shouldHaveSingleBean KinesisOperations::class
        }
    }

    @Test
    fun `back off when Kinesis auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.kinesis.enabled=false")
            .run { context ->
                context shouldNotHaveBean KinesisAsyncClient::class
                context shouldNotHaveBean KinesisOperations::class
            }
    }

    @Test
    fun `custom client bean backs off auto configured client`() {
        val customClient = mockk<KinesisAsyncClient>(relaxed = true)

        contextRunner
            .withBean(KinesisAsyncClient::class.java) { customClient }
            .run { context ->
                context.getBean(KinesisAsyncClient::class.java) shouldBeSameInstanceAs customClient
                context shouldHaveSingleBean KinesisOperations::class
            }
    }

    @Test
    fun `custom operations bean backs off template`() {
        contextRunner
            .withBean(KinesisOperations::class.java) { NoopKinesisOperations }
            .run { context ->
                context.getBean(KinesisOperations::class.java) shouldBeSameInstanceAs NoopKinesisOperations
            }
    }
}
```

Use bluetape4k assertions only, following `SqsAutoConfigurationTest` style. The remaining tests in this slice must cover endpoint override validation, shared default binding, global and service-specific customizer ordering, missing SDK classpath backoff, configured stream binding, and invalid consumer binding.

- [ ] **Step 3: Verify tests fail before implementation**

Run:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisAutoConfigurationTest' --no-configuration-cache
```

Expected: compilation fails because `KinesisAutoConfiguration`, `KinesisProperties`, and `KinesisOperations` do not exist.

- [ ] **Step 4: Implement `KinesisProperties`**

Create `KinesisProperties`:

```kotlin
@ConfigurationProperties(prefix = "bluetape4k.aws.kinesis")
data class KinesisProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val streams: Map<String, Stream> = emptyMap(),
    val consumer: Consumer = Consumer(),
) : Serializable {
    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "bluetape4k.aws.kinesis.region is required when endpointOverride is configured."
        }
    }

    data class Stream(val shardCount: Int = 1) : Serializable {
        init {
            require(shardCount >= 1) { "shardCount must be greater than or equal to 1." }
        }
        companion object { private const val serialVersionUID: Long = 1L }
    }

    data class Consumer(
        val batchLimit: Int = 100,
        val pollInterval: Duration = Duration.ofSeconds(1),
        val emptyBackoff: Duration = Duration.ofSeconds(1),
        val maxIteratorRetries: Int = 3,
        val maxThrottleRetries: Int = 3,
        val initialThrottleBackoff: Duration = Duration.ofMillis(100),
        val maxThrottleBackoff: Duration = Duration.ofSeconds(5),
        val jitterRatio: Double = 1.0,
    ) : Serializable {
        init {
            require(batchLimit in 1..10_000) { "batchLimit must be between 1 and 10000." }
            require(!pollInterval.isNegative) { "pollInterval must not be negative." }
            require(!emptyBackoff.isNegative) { "emptyBackoff must not be negative." }
            require(maxIteratorRetries >= 1) { "maxIteratorRetries must be greater than or equal to 1." }
            require(maxThrottleRetries >= 1) { "maxThrottleRetries must be greater than or equal to 1." }
            require(!initialThrottleBackoff.isNegative) { "initialThrottleBackoff must not be negative." }
            require(!maxThrottleBackoff.isNegative) { "maxThrottleBackoff must not be negative." }
            require(jitterRatio in 0.0..1.0) { "jitterRatio must be between 0.0 and 1.0." }
        }
        companion object { private const val serialVersionUID: Long = 1L }
    }

    companion object { private const val serialVersionUID: Long = 1L }
}
```

- [ ] **Step 5: Implement `KinesisAutoConfiguration`**

Follow `SnsAutoConfiguration` and use:

```kotlin
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.http.async.SdkAsyncHttpClient",
        "software.amazon.awssdk.services.kinesis.KinesisAsyncClient",
    ]
)
@ConditionalOnProperty(prefix = "bluetape4k.aws.kinesis", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(KinesisProperties::class)
class KinesisAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun kinesisAsyncClient(
        awsProperties: AwsProperties,
        kinesisProperties: KinesisProperties,
        credentialsProvider: AwsCredentialsProvider?,
        asyncHttpClient: SdkAsyncHttpClient?,
        globalCustomizers: ObjectProvider<AwsClientCustomization<KinesisAsyncClientBuilder>>,
        serviceCustomizers: ObjectProvider<KinesisAsyncClientCustomizer>,
    ): KinesisAsyncClient {
        val builder = KinesisAsyncClient.builder()
        AwsClientBuilderSupport.applyDefaults(
            builder = builder,
            serviceName = "kinesis",
            awsProperties = awsProperties,
            region = kinesisProperties.region,
            endpointOverride = kinesisProperties.endpointOverride,
            credentialsProvider = credentialsProvider,
            asyncHttpClient = asyncHttpClient,
            customizers = globalCustomizers.orderedStream().toList(),
        )
        serviceCustomizers.orderedStream().forEach { it.customize(builder) }
        return builder.build()
    }

    @Bean
    @ConditionalOnMissingBean(KinesisOperations::class)
    fun kinesisCoroutinesTemplate(
        kinesisAsyncClient: KinesisAsyncClient,
        properties: KinesisProperties,
    ): KinesisCoroutinesTemplate = KinesisCoroutinesTemplate(kinesisAsyncClient, properties)
}
```

Service name passed to global customizers must be `"kinesis"`.

- [ ] **Step 6: Register auto-configuration import**

Add:

```text
io.bluetape4k.aws.spring.kinesis.KinesisAutoConfiguration
```

to `AutoConfiguration.imports` near the other service configurations.

- [ ] **Step 7: Run auto-configuration tests**

Run:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisAutoConfigurationTest' --no-configuration-cache
```

Expected: all Kinesis auto-configuration tests pass.

## Task 2: Operations API and Template Unit Tests

complexity: high
sub-skill: `bluetape4k-code-patterns`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`, `kotlin-coroutines-skill`
verification: `./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisCoroutinesTemplateTest' --no-configuration-cache`

**Files:**
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisRequests.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisStartingPosition.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisRecordFlowOptions.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisOperations.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisCoroutinesTemplate.kt`
- Test: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisCoroutinesTemplateTest.kt`

- [ ] **Step 1: Write request value objects**

Create serializable request values:

```kotlin
data class KinesisPutRecordRequest(
    val streamName: String,
    val partitionKey: String,
    val data: SdkBytes,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

data class KinesisShardIteratorRequest(
    val streamName: String,
    val shardId: String,
    val type: ShardIteratorType = ShardIteratorType.TRIM_HORIZON,
    val startingSequenceNumber: String? = null,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

data class KinesisRecordFlowRequest(
    val streamName: String,
    val shardId: String,
    val position: KinesisStartingPosition = KinesisStartingPosition.TrimHorizon,
    val options: KinesisRecordFlowOptions? = null,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}
```

Use private constructors/companion factories only if validation needs to prevent invalid `copy()` values; otherwise validate in template call paths to preserve simple data carriers.

- [ ] **Step 2: Define Spring-local Flow position and options**

Create `KinesisStartingPosition` without depending on AWS Kotlin SDK types:

```kotlin
sealed interface KinesisStartingPosition : Serializable {
    data object TrimHorizon : KinesisStartingPosition {
        private const val serialVersionUID: Long = 1L
    }

    data object Latest : KinesisStartingPosition {
        private const val serialVersionUID: Long = 1L
    }

    data class AtSequenceNumber(val sequenceNumber: String) : KinesisStartingPosition {
        companion object { private const val serialVersionUID: Long = 1L }
    }

    data class AfterSequenceNumber(val sequenceNumber: String) : KinesisStartingPosition {
        companion object { private const val serialVersionUID: Long = 1L }
    }

    data class AtTimestamp(val timestamp: Instant) : KinesisStartingPosition {
        companion object { private const val serialVersionUID: Long = 1L }
    }
}
```

Create `KinesisRecordFlowOptions` with Java `Duration` values so property conversion stays Spring-native:

```kotlin
data class KinesisRecordFlowOptions(
    val batchLimit: Int = 100,
    val pollInterval: Duration = Duration.ofSeconds(1),
    val emptyBackoff: Duration = Duration.ofSeconds(1),
    val maxIteratorRetries: Int = 3,
    val maxThrottleRetries: Int = 3,
    val initialThrottleBackoff: Duration = Duration.ofMillis(100),
    val maxThrottleBackoff: Duration = Duration.ofSeconds(5),
    val jitterRatio: Double = 1.0,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}
```

Map these values to Java SDK v2 `ShardIteratorType` in the template:

- `TrimHorizon` -> `ShardIteratorType.TRIM_HORIZON`
- `Latest` -> `ShardIteratorType.LATEST`
- `AtSequenceNumber` -> `ShardIteratorType.AT_SEQUENCE_NUMBER` plus `startingSequenceNumber`
- `AfterSequenceNumber` -> `ShardIteratorType.AFTER_SEQUENCE_NUMBER` plus `startingSequenceNumber`
- `AtTimestamp` -> `ShardIteratorType.AT_TIMESTAMP` plus `timestamp`

- [ ] **Step 3: Define operations interface**

Create `KinesisOperations` with the exact API from the spec and English KDoc examples.

- [ ] **Step 4: Write failing template tests**

Use MockK to verify request mapping for:

- `createConfiguredStream` uses configured shard count.
- missing configured stream fails fast.
- `putRecord` maps stream, partition key, and bytes.
- `putRecords` rejects an empty entry list.
- `getShardIterator` maps iterator type and optional sequence number.
- `recordFlow` maps every `KinesisStartingPosition` variant to the expected Java SDK iterator request.
- `recordFlow` is cold and does not call AWS until collected.
- `recordFlow` stops cleanly when `nextShardIterator()` is null.
- `recordFlow` can be collected twice and refetches an iterator for each collection.
- `recordFlow` propagates cancellation.
- representative SDK future failures propagate without broad exception wrapping.

- [ ] **Step 5: Implement template**

Use existing Java SDK coroutine helpers where they fit:

```kotlin
class KinesisCoroutinesTemplate(
    private val kinesisAsyncClient: KinesisAsyncClient,
    private val properties: KinesisProperties,
) : KinesisOperations {
    override suspend fun createStream(streamName: String, shardCount: Int): CreateStreamResponse =
        kinesisAsyncClient.createStream(streamName, shardCount)

    override suspend fun createConfiguredStream(streamName: String): CreateStreamResponse {
        val stream = properties.streams[streamName]
            ?: throw IllegalArgumentException("Stream '$streamName' is not configured.")
        return createStream(streamName, stream.shardCount)
    }
}
```

For `recordFlow`, implement a Java SDK v2 version of the existing `aws-kotlin` loop:

- call `getShardIterator` only inside collection
- call `currentCoroutineContext().ensureActive()` at loop top
- emit each `software.amazon.awssdk.services.kinesis.model.Record`
- stop when `nextShardIterator()` is null
- catch `CancellationException` and rethrow immediately
- recover `ExpiredIteratorException` only when a last seen sequence number can preserve ordering
- retry throttling errors using bounded jittered backoff

- [ ] **Step 6: Run template tests**

Run:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisCoroutinesTemplateTest' --no-configuration-cache
```

Expected: all deterministic template tests pass.

## Task 3: Emulator Smoke

complexity: medium
sub-skill: `bluetape4k-code-patterns`, `ecc-kotlin-testing`
verification: `./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisCoroutinesTemplateAwsEmulatorTest' -Dbluetape4k.aws.emulator=floci --no-configuration-cache`

**Files:**
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisCoroutinesTemplateAwsEmulatorTest.kt`

- [ ] **Step 1: Write emulator smoke test**

Follow `SnsCoroutinesTemplateAwsEmulatorTest` and `aws-kotlin` `KinesisRecordFlowTest`.

The smoke should:

- create a unique stream
- wait for ACTIVE with `untilSuspending`
- put 3 records
- get the first shard id through `describeStream`
- collect 3 records from `recordFlow` with `TrimHorizon`
- delete the stream at the end

- [ ] **Step 2: Run Floci smoke**

Run:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisCoroutinesTemplateAwsEmulatorTest' -Dbluetape4k.aws.emulator=floci --no-configuration-cache
```

Expected: PASS. If Floci lacks Kinesis behavior, rerun once with LocalStack:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisCoroutinesTemplateAwsEmulatorTest' -Dbluetape4k.aws.emulator=localstack --no-configuration-cache
```

If both fail due to emulator support rather than code behavior, disable only the emulator test with a documented `@Disabled("#270 — Kinesis emulator support is unreliable in current local matrix")` reason and keep deterministic template coverage.

## Task 4: README and Chart

complexity: medium
sub-skill: `bluetape4k-code-patterns`, `bluetape4k-diagram`, `bluetape4k-blog`
verification: README source grep, `xmllint`, CairoSVG render, visual inspection, `git diff --check`

**Files:**
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `aws-spring-boot/README.md`
- Modify: `aws-spring-boot/README.ko.md`
- Modify: `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg`
- Modify: `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.png`

- [ ] **Step 1: Update source-verified README text**

Mention `KinesisOperations` and Spring Boot Kinesis auto-configuration in module descriptions and usage sections.

Code example must use real source names:

```kotlin
class StreamPublisher(private val kinesis: KinesisOperations) {
    suspend fun publish(streamName: String, payload: String) {
        kinesis.putRecord(
            KinesisPutRecordRequest(
                streamName = streamName,
                partitionKey = "orders",
                data = SdkBytes.fromUtf8String(payload),
            )
        )
    }
}
```

- [ ] **Step 2: Update chart**

Change only `aws-spring-boot × Kinesis` from empty `-` to stable `S`.

- [ ] **Step 3: Validate docs and assets**

Run:

```bash
xmllint --noout docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg
~/.local/bin/cairosvg docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg -o docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.png -s 2
rg -n "KinesisOperations|KinesisPutRecordRequest|KinesisAutoConfiguration" README.md README.ko.md aws-spring-boot/README.md aws-spring-boot/README.ko.md aws-spring-boot/src/main/kotlin
git diff --check
```

Expected: XML valid, PNG regenerated, source names present, no whitespace errors.

## Task 5: Full Verification, Review, and Commit

complexity: high
sub-skill: `verification-before-completion`, `bluetape4k-code-patterns`
verification: targeted tests, module test, warning compile, 7-tier review

**Files:**
- Modify: plan checkbox statuses as tasks complete.
- Create: `docs/lessons/2026-06-30-issue-270-spring-kinesis.md`
- Create: `docs/review/2026-06-30-issue-270-code-review.md`

- [ ] **Step 1: Run targeted verification**

Run:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*Kinesis*' --no-configuration-cache
./gradlew :bluetape4k-aws-spring-boot:test --no-configuration-cache
./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin --warning-mode all --no-configuration-cache
git diff --check
```

Expected: PASS. If a warning appears in touched code, fix it before review.

- [ ] **Step 2: Run Step 6-R local/native 7-tier code review**

Read `bluetape4k-full-feature/references/step-6r-code-review.md` and `references/step-4p-perf-scan.md`.

Record findings in `docs/review/2026-06-30-issue-270-code-review.md` and converge to:

- P0 = 0
- P1 = 0

- [ ] **Step 3: Add lesson**

Create `docs/lessons/2026-06-30-issue-270-spring-kinesis.md` with:

- context
- decision
- emulator evidence
- validation commands
- future listener/checkpoint follow-up note

- [ ] **Step 4: Commit**

Use Lore protocol:

```bash
git add aws-spring-boot README.md README.ko.md docs
git commit -m "feat: add Spring Boot Kinesis operations"
```

Commit body must include `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, and `Not-tested` trailers.

## Task 6: PR, PR Review, CI, and Merge Gate

complexity: medium
sub-skill: `verification-before-completion`
verification: live PR body, PR review threads, CI run, issue/PR metadata

**Files:**
- PR body temp file under `/tmp`.

- [ ] **Step 1: Push and create PR**

Push `feat/aws-spring-kinesis` and create PR against `develop`.

PR metadata:

- title: `feat(aws-spring-boot): add Kinesis auto-configuration and operations`
- linked issue: `Fixes #270`
- assignee: `debop`
- milestone: `0.5.0`
- labels mirrored from issue: `enhancement`, `aws-spring-boot`, `spring-boot`, `kinesis`
- final Markdown `##` section: `## DoD Status`

- [ ] **Step 2: Verify live PR body and metadata**

Run:

```bash
gh pr view <pr> --json body,assignees,milestone,labels,baseRefName,headRefName
```

Expected: body is non-empty and final `##` heading is `## DoD Status`.

- [ ] **Step 3: Post-PR review gate**

Reread reviews/comments/threads:

```bash
gh pr view <pr> --json reviews,comments,reviewDecision,mergeStateStatus
gh api graphql -f owner=bluetape4k -f name=bluetape4k-aws -F number=<pr> -f query='
query($owner: String!, $name: String!, $number: Int!) {
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) {
      reviewThreads(first: 100) {
        nodes {
          isResolved
          comments(first: 10) {
            nodes {
              author { login }
              body
            }
          }
        }
      }
    }
  }
}'
```

Expected: unresolved review threads = 0, P0/P1 = 0.

- [ ] **Step 4: CI gate**

Watch CI with `gh run view` when needed. Merge only after required jobs are `success` or non-blocking skipped.

- [ ] **Step 5: Merge only after explicit merge instruction**

Use rebase merge only when the user asks to merge after CI and review gates pass.

## Self-Review

- Spec coverage: Task 1 covers auto-configuration and dependency policy; Task 2 covers operations and Flow; Task 3 covers emulator smoke/fallback; Task 4 covers README/chart; Task 5 covers review/lessons/commit; Task 6 covers PR/CI/merge gate.
- Placeholder scan: no `TBD`, `TODO`, or undefined task ownership remains.
- Type consistency: API names use `KinesisOperations`, `KinesisCoroutinesTemplate`, `KinesisProperties`, `KinesisPutRecordRequest`, `KinesisShardIteratorRequest`, and `KinesisRecordFlowRequest` consistently.
- Concurrency helper rationale: no ad hoc thread/coroutine stress test is planned. Flow cancellation is covered deterministically; no race/stress helper fits this API slice.
