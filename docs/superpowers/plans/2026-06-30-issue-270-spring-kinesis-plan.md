# Spring Boot Kinesis 구현 계획

> **에이전트 작업자용:** 필수 하위 스킬: 이 계획을 작업별로 구현할 때 superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans를 사용한다. 단계 추적에는 체크박스(`- [ ]`) 구문을 사용한다.

**목표:** `bluetape4k-aws-spring-boot`에 Spring Boot 4 Kinesis auto-configuration과 coroutine operation을 추가한다.

**아키텍처:** 기존 SNS/SQS pattern을 따라 Java SDK v2 `KinesisAsyncClient`를 구성하고 `KinesisOperations` interface를 노출하며 `KinesisCoroutinesTemplate`로 구현한다. listener/checkpoint runtime은 이 PR에서 제외하고 명시적인 operation과 cold single-shard `Flow`만 노출한다.

**기술 스택:** Kotlin 2.4, Spring Boot 4.1 auto-configuration, AWS SDK for Java v2 Kinesis, Kotlin coroutines Flow, JUnit 5, MockK, bluetape4k-assertions, ApplicationContextRunner, 선택적 Floci/LocalStack smoke test.

---

## 파일 구조

- `aws-spring-boot/build.gradle.kts` 수정: `libs.aws2.kinesis`를 `compileOnly`와 `testImplementation`으로 추가.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisProperties.kt` 생성: service property와 validation.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisRequests.kt` 생성: 같은 타입의 parameter 실수를 방지하는 이름 있는 request 값.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisStartingPosition.kt` 생성: Spring 전용 Java SDK v2 shard iterator position model.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisRecordFlowOptions.kt` 생성: Spring 전용 Flow polling 및 retry option.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisOperations.kt` 생성: public coroutine API.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisCoroutinesTemplate.kt` 생성: Java SDK v2 async-client 구현.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisAutoConfiguration.kt` 생성: 조건부 Spring Boot auto-configuration.
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 수정: Kinesis auto-configuration 등록.
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/NoopKinesisOperations.kt` 생성: test override 객체.
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisAutoConfigurationTest.kt` 생성: 조건부 bean/property/customizer test.
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisCoroutinesTemplateTest.kt` 생성: 결정론적 MockK request-mapping test.
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisCoroutinesTemplateAwsEmulatorTest.kt` 생성: 신뢰할 수 있는 경우 집중 emulator smoke.
- code/test 통과 후 root/module README locale set와 service coverage chart 수정.
- `docs/lessons/2026-06-30-issue-270-spring-kinesis.md` 생성.

## 작업 1: 의존성 및 auto-configuration slice

복잡도: 중간
하위 스킬: `bluetape4k-code-patterns`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`
검증: `./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisAutoConfigurationTest' --no-configuration-cache`

**파일:**
- Modify: `aws-spring-boot/build.gradle.kts`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisProperties.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisAutoConfiguration.kt`
- Modify: `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisAutoConfigurationTest.kt`
- Test helper: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/NoopKinesisOperations.kt`

- [ ] **1단계: Kinesis SDK 의존성 추가**

`aws-spring-boot/build.gradle.kts`에서 다른 AWS SDK v2 의존성 근처에 추가한다.

```kotlin
compileOnly(libs.aws2.kinesis)
testImplementation(libs.aws2.kinesis)
```

- [ ] **2단계: 실패하는 auto-configuration test 작성**

다음 이름의 test를 포함하는 `KinesisAutoConfigurationTest`를 생성한다.

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

`SqsAutoConfigurationTest` style에 따라 bluetape4k assertion만 사용한다. 이 slice의 나머지 test는 endpoint override validation, shared default binding, global/service-specific customizer 순서, SDK classpath 누락 시 backoff, 구성된 stream binding, 잘못된 consumer binding을 검증해야 한다.

- [ ] **3단계: 구현 전 test 실패 검증**

실행:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisAutoConfigurationTest' --no-configuration-cache
```

예상 결과: `KinesisAutoConfiguration`, `KinesisProperties`, `KinesisOperations`가 없으므로 compilation 실패.

- [ ] **4단계: `KinesisProperties` 구현**

`KinesisProperties`를 생성한다.

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

- [ ] **5단계: `KinesisAutoConfiguration` 구현**

`SnsAutoConfiguration`을 따라 다음을 사용한다.

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

global customizer에 전달하는 service name은 `"kinesis"`여야 한다.

- [ ] **6단계: auto-configuration import 등록**

추가:

```text
io.bluetape4k.aws.spring.kinesis.KinesisAutoConfiguration
```

`AutoConfiguration.imports`에서 다른 service configuration 근처에 추가한다.

- [ ] **7단계: auto-configuration test 실행**

실행:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisAutoConfigurationTest' --no-configuration-cache
```

예상 결과: 모든 Kinesis auto-configuration test 통과.

## 작업 2: operation API 및 template unit test

복잡도: 높음
하위 스킬: `bluetape4k-code-patterns`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`, `kotlin-coroutines-skill`
검증: `./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisCoroutinesTemplateTest' --no-configuration-cache`

**파일:**
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisRequests.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisStartingPosition.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisRecordFlowOptions.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisOperations.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisCoroutinesTemplate.kt`
- Test: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisCoroutinesTemplateTest.kt`

- [ ] **1단계: request value object 작성**

serializable request value를 생성한다.

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

validation에서 잘못된 `copy()` 값을 막아야 할 때만 private constructor/companion factory를 사용한다. 그 외에는 단순한 data carrier를 유지하도록 template 호출 경로에서 검증한다.

- [ ] **2단계: Spring 전용 Flow position 및 option 정의**

AWS Kotlin SDK 타입에 의존하지 않는 `KinesisStartingPosition`을 생성한다.

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

property conversion이 Spring-native로 유지되도록 Java `Duration` 값을 사용하는 `KinesisRecordFlowOptions`를 생성한다.

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

template에서 이 값을 Java SDK v2 `ShardIteratorType`으로 mapping한다.

- `TrimHorizon` -> `ShardIteratorType.TRIM_HORIZON`
- `Latest` -> `ShardIteratorType.LATEST`
- `AtSequenceNumber` -> `ShardIteratorType.AT_SEQUENCE_NUMBER` plus `startingSequenceNumber`
- `AfterSequenceNumber` -> `ShardIteratorType.AFTER_SEQUENCE_NUMBER` plus `startingSequenceNumber`
- `AtTimestamp` -> `ShardIteratorType.AT_TIMESTAMP` plus `timestamp`

- [ ] **3단계: operation interface 정의**

명세의 정확한 API와 영문 KDoc 예제를 포함하는 `KinesisOperations`를 생성한다.

- [ ] **4단계: 실패하는 template test 작성**

MockK로 다음 request mapping을 검증한다.

- `createConfiguredStream`이 구성된 shard count 사용
- 구성된 stream 누락 시 빠르게 실패
- `putRecord`가 stream, partition key, byte mapping
- `putRecords`가 빈 entry list 거부
- `getShardIterator`가 iterator type과 선택적 sequence number mapping
- `recordFlow`가 모든 `KinesisStartingPosition` variant를 예상 Java SDK iterator request로 mapping
- `recordFlow`가 cold이며 collect 전에는 AWS를 호출하지 않음
- `nextShardIterator()`가 null이면 `recordFlow`가 정상 종료
- `recordFlow`를 두 번 collect할 수 있고 collection마다 iterator를 다시 조회
- `recordFlow`가 취소를 전파
- 대표 SDK future failure가 포괄적인 exception wrapping 없이 전파

- [ ] **5단계: template 구현**

적합한 곳에서는 기존 Java SDK coroutine helper를 사용한다.

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

`recordFlow`에는 기존 `aws-kotlin` loop의 Java SDK v2 version을 구현한다.

- collection 내부에서만 `getShardIterator` 호출
- loop 시작 시 `currentCoroutineContext().ensureActive()` 호출
- 각 `software.amazon.awssdk.services.kinesis.model.Record` emit
- `nextShardIterator()`가 null이면 중단
- `CancellationException`을 catch한 뒤 즉시 다시 throw
- 마지막으로 확인한 sequence number가 순서를 보존할 수 있을 때만 `ExpiredIteratorException` 복구
- 제한된 jittered backoff로 throttling error retry

- [ ] **6단계: template test 실행**

실행:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisCoroutinesTemplateTest' --no-configuration-cache
```

예상 결과: 모든 결정론적 template test 통과.

## 작업 3: emulator smoke

복잡도: 중간
하위 스킬: `bluetape4k-code-patterns`, `ecc-kotlin-testing`
검증: `./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisCoroutinesTemplateAwsEmulatorTest' -Dbluetape4k.aws.emulator=floci --no-configuration-cache`

**파일:**
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/kinesis/KinesisCoroutinesTemplateAwsEmulatorTest.kt`

- [ ] **1단계: emulator smoke test 작성**

`SnsCoroutinesTemplateAwsEmulatorTest`와 `aws-kotlin`의 `KinesisRecordFlowTest`를 따른다.

smoke test는 다음을 수행한다.

- 고유한 stream 생성
- `untilSuspending`으로 ACTIVE 상태 대기
- record 3개 저장
- `describeStream`으로 첫 shard id 조회
- `TrimHorizon`을 사용하는 `recordFlow`에서 record 3개 collect
- 마지막에 stream 삭제

- [ ] **2단계: Floci smoke 실행**

실행:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisCoroutinesTemplateAwsEmulatorTest' -Dbluetape4k.aws.emulator=floci --no-configuration-cache
```

예상 결과: PASS. Floci가 Kinesis 동작을 지원하지 않으면 LocalStack으로 한 번 재실행한다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*KinesisCoroutinesTemplateAwsEmulatorTest' -Dbluetape4k.aws.emulator=localstack --no-configuration-cache
```

두 emulator가 code 동작이 아닌 emulator 지원 문제로 실패하면 문서화된 `@Disabled("#270 — Kinesis emulator support is unreliable in current local matrix")` 사유로 emulator test만 비활성화하고 결정론적 template coverage는 유지한다.

## 작업 4: README 및 chart

복잡도: 중간
하위 스킬: `bluetape4k-code-patterns`, `bluetape4k-diagram`, `bluetape4k-blog`
검증: README source grep, `xmllint`, CairoSVG render, visual inspection, `git diff --check`

**파일:**
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `aws-spring-boot/README.md`
- Modify: `aws-spring-boot/README.ko.md`
- Modify: `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg`
- Modify: `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.png`

- [ ] **1단계: source로 검증한 README text 갱신**

module 설명과 사용 section에 `KinesisOperations` 및 Spring Boot Kinesis auto-configuration을 언급한다.

code 예제는 실제 source 이름을 사용해야 한다.

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

- [ ] **2단계: chart 갱신**

`aws-spring-boot × Kinesis`만 빈 `-`에서 stable `S`로 변경한다.

- [ ] **3단계: 문서 및 asset 검증**

실행:

```bash
xmllint --noout docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg
~/.local/bin/cairosvg docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg -o docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.png -s 2
rg -n "KinesisOperations|KinesisPutRecordRequest|KinesisAutoConfiguration" README.md README.ko.md aws-spring-boot/README.md aws-spring-boot/README.ko.md aws-spring-boot/src/main/kotlin
git diff --check
```

예상 결과: XML 유효, PNG 재생성, source 이름 존재, whitespace error 없음.

## 작업 5: 전체 검증, review 및 commit

복잡도: 높음
하위 스킬: `verification-before-completion`, `bluetape4k-code-patterns`
검증: targeted test, module test, warning compile, 7-tier review

**파일:**
- 수정: 작업 완료에 따라 plan checkbox 상태 갱신.
- 생성: `docs/lessons/2026-06-30-issue-270-spring-kinesis.md`
- 생성: `docs/review/2026-06-30-issue-270-code-review.md`

- [ ] **1단계: targeted 검증 실행**

실행:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*Kinesis*' --no-configuration-cache
./gradlew :bluetape4k-aws-spring-boot:test --no-configuration-cache
./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin --warning-mode all --no-configuration-cache
git diff --check
```

예상 결과: PASS. 변경한 code에 warning이 있으면 review 전에 수정한다.

- [ ] **2단계: Step 6-R local/native 7-tier code review 실행**

`bluetape4k-full-feature/references/step-6r-code-review.md`와 `references/step-4p-perf-scan.md`를 읽는다.

finding을 `docs/review/2026-06-30-issue-270-code-review.md`에 기록하고 다음 상태로 수렴한다.

- P0 = 0
- P1 = 0

- [ ] **3단계: lesson 추가**

`docs/lessons/2026-06-30-issue-270-spring-kinesis.md`에 다음을 작성한다.

- context
- 결정
- emulator evidence
- validation command
- 향후 listener/checkpoint 후속 작업 참고

- [ ] **4단계: commit**

Lore protocol 사용:

```bash
git add aws-spring-boot README.md README.ko.md docs
git commit -m "feat: add Spring Boot Kinesis operations"
```

commit body에는 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested` trailer를 포함해야 한다.

## 작업 6: PR, PR review, CI 및 merge gate

복잡도: 중간
하위 스킬: `verification-before-completion`
검증: live PR body, PR review thread, CI 실행, issue/PR metadata

**파일:**
- `/tmp` 아래 PR body 임시 파일.

- [ ] **1단계: push 및 PR 생성**

`feat/aws-spring-kinesis`를 push하고 `develop` 대상 PR을 생성한다.

PR metadata:

- title: `feat(aws-spring-boot): add Kinesis auto-configuration and operations`
- 연결 issue: `Fixes #270`
- assignee: `debop`
- milestone: `0.5.0`
- issue에서 반영한 label: `enhancement`, `aws-spring-boot`, `spring-boot`, `kinesis`
- 마지막 Markdown `##` section: `## DoD Status`

- [ ] **2단계: live PR body 및 metadata 검증**

실행:

```bash
gh pr view <pr> --json body,assignees,milestone,labels,baseRefName,headRefName
```

예상 결과: body가 비어 있지 않고 마지막 `##` heading이 `## DoD Status`다.

- [ ] **3단계: PR 생성 후 review gate**

review/comment/thread를 다시 읽는다.

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

예상 결과: unresolved review thread = 0, P0/P1 = 0.

- [ ] **4단계: CI gate**

필요하면 `gh run view`로 CI를 관찰한다. 필수 job이 `success` 또는 non-blocking skipped 상태가 된 뒤에만 merge한다.

- [ ] **5단계: 명시적인 merge 지시 후에만 merge**

CI와 review gate를 통과한 후 사용자가 merge를 요청한 경우에만 rebase merge를 사용한다.

## 자체 검토

- 명세 coverage: 작업 1은 auto-configuration 및 dependency 정책, 작업 2는 operation 및 Flow, 작업 3은 emulator smoke/fallback, 작업 4는 README/chart, 작업 5는 review/lesson/commit, 작업 6은 PR/CI/merge gate를 다룬다.
- Placeholder 검사: `TBD`, `TODO`, 정의되지 않은 작업 소유권이 남아 있지 않다.
- 타입 일관성: API 이름으로 `KinesisOperations`, `KinesisCoroutinesTemplate`, `KinesisProperties`, `KinesisPutRecordRequest`, `KinesisShardIteratorRequest`, `KinesisRecordFlowRequest`를 일관되게 사용한다.
- Concurrency helper 근거: ad hoc thread/coroutine stress test는 계획하지 않는다. Flow 취소는 결정론적으로 검증하며 이 API slice에 맞는 race/stress helper는 없다.
