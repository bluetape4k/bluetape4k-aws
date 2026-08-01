# Bedrock Runtime 최소 Facade 구현 계획

> **에이전트 작업자용:** 필수 하위 스킬: 이 계획을 작업별로 구현할 때 superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans를 사용한다. 단계 추적에는 체크박스(`- [ ]`) 구문을 사용한다.

**목표:** native SDK 타입, 취소, 제한된 streaming demand, 명시적인 client 소유권을 보존하면서 Java SDK v2 및 AWS Kotlin SDK 모듈에 모델 중립적인 Amazon Bedrock Runtime `Converse`와 `ConverseStream` 도우미를 추가한다.

**아키텍처:** 두 게시 모듈에 Bedrock Runtime을 `compileOnly` 서비스 의존성으로 추가하고, 각 SDK의 native 타입을 중심으로 작은 client, request, response, operation 확장을 구성한다. Java streaming은 `SdkPublisher`를 `asFlow().buffer(0)`과 generation 인식 terminal 상태 머신을 사용하는 cold `Flow`로 변환하며, Kotlin streaming은 SDK의 native Flow를 직접 수집한다. 영문/한글 모듈 README는 API 계약을 공유하되 언어별 sequence diagram asset을 사용하며, release에 고정된 manual page는 `0.5.0` manual 갱신 전까지 변경하지 않는다.

**기술 스택:** Kotlin 2.4, AWS SDK for Java v2 Bedrock Runtime, AWS SDK for Kotlin Bedrock Runtime, Kotlin Coroutines/Reactive, bluetape4k-coroutines Flow 확장, JUnit 5, MockK, Gradle, SVG, CairoSVG.

---

## 승인된 입력과 중단 조건

- 승인된 명세: `docs/superpowers/specs/2026-07-23-issue-312-bedrock-runtime-design.md`
- 저장소: `bluetape4k/bluetape4k-aws`
- Base/head: `develop` / `feat/issue-312-bedrock-runtime`
- 모든 Type A gate를 통과한 뒤 PR 생성은 승인되지만 merge는 승인되지 않는다.
- 이 계획이 승인되고 Step 3-R이 `P0=0`, `P1=0`인 경우에만 production 구현을 시작한다.
- 실제 Bedrock 호출은 opt-in으로 유지하며 기본 CI 요구 사항에 포함하지 않는다.

## 파일 구성

### 의존성과 build 소유권

- 수정: `gradle/libs.versions.toml`
- 수정: `build.gradle.kts`
- 수정: `aws-java/build.gradle.kts`
- 수정: `aws-kotlin/build.gradle.kts`

### Java SDK v2 facade

- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeClientSupport.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeAsyncClientSupport.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeClientExtensions.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeAsyncClientExtensions.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeAsyncClientCoroutinesExtensions.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/model/BedrockRuntimeRequestSupport.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/model/BedrockRuntimeResponseSupport.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeClientSupportTest.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeRequestSupportTest.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeResponseSupportTest.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeClientExtensionsTest.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeAsyncClientExtensionsTest.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeAsyncClientCoroutinesExtensionsTest.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensionsTest.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/RecordingSdkPublisher.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeSmokeTest.kt`
- 생성: `aws-java/src/consumerFixture/kotlin/io/bluetape4k/aws/bedrock/consumer/JavaBedrockConsumerFixture.kt`

### AWS Kotlin SDK facade

- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeClientSupport.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeClientExtensions.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeFlowExtensions.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/bedrock/model/BedrockRuntimeRequestSupport.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/bedrock/model/BedrockRuntimeResponseSupport.kt`
- 생성: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeClientSupportTest.kt`
- 생성: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeRequestSupportTest.kt`
- 생성: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeResponseSupportTest.kt`
- 생성: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeClientExtensionsTest.kt`
- 생성: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeFlowExtensionsTest.kt`
- 생성: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/bedrock/BedrockRuntimeSmokeTest.kt`
- 생성: `aws-kotlin/src/consumerFixture/kotlin/io/bluetape4k/aws/kotlin/bedrock/consumer/KotlinBedrockConsumerFixture.kt`

### 문서, diagram, review 및 lesson

- 수정: `aws-java/README.md`
- 수정: `aws-java/README.ko.md`
- 수정: `aws-kotlin/README.md`
- 수정: `aws-kotlin/README.ko.md`
- 생성: `docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-en-01.svg`
- 생성: `docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-en-01.png`
- 생성: `docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-ko-01.svg`
- 생성: `docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-ko-01.png`
- 재사용: `docs/assets/aws-icons/official-04302026/Architecture-Service-Icons_04302026/Arch_Artificial-Intelligence/48/Arch_Amazon-Bedrock_48.svg`
- 수정: `CHANGELOG.md`
- 수정: `WIP.md`
- 생성: `docs/review/2026-07-23-issue-312-plan-review.md`
- 생성: `docs/review/2026-07-23-issue-312-code-review.md`
- 생성: `docs/lessons/2026-07-23-issue-312-bedrock-runtime.md`
- 수정: `docs/lessons/README.md`

## 명세 추적성

| 명세 요구 사항 | 계획 작업 | 검증 |
|---|---|---|
| 기존 AWS version authority와 `compileOnly` 서비스 SDK | 작업 1 | dependency insight와 게시 metadata 검사 |
| 정확한 Java/Kotlin client 및 model signature | 작업 2, 3, 5 | 집중 compile/test와 KDoc |
| Java sync, future, suspend 호출 | 작업 3 | 위임/오류/취소 test |
| Java cold streaming Flow와 제한된 demand | 작업 4 | recording publisher ledger와 race test |
| Kotlin native suspend/Flow와 범위가 지정된 close | 작업 5 | mock client와 lifecycle test |
| `Flow.log()` 없이 `castNotNull`/`takeUntil` 사용 | 작업 4, 5, 7 | source assertion, Flow test, 예제 |
| 응답 text mapping과 single-pass join | 작업 2, 5 | 대용량/혼합 block mapping test |
| Endpoint/credential와 생성 결과의 trust boundary | 작업 2, 5, 7 | 부정 endpoint test와 문서 |
| fail-closed 기본값을 둔 opt-in smoke | 작업 6 | 일반 test 제외 및 property/env test |
| 영문/한글 문서와 별도 SVG/PNG | 작업 7 | locale parity와 diagram audit |
| Type A review, lesson, PR/DoD | 작업 8 | review 산출물, lesson commit, exact-head PR |

## Step 3-P 위험 예측

| 위험 | 조기 신호 | 작업 내 완화책 | Rollback/재실행 지점 |
|---|---|---|---|
| Java future/publisher의 이중 terminal | 두 번째 완료, 유출된 subscription, 멈춘 test | 작업 4의 generation ledger와 first-terminal-wins test | 작업 4 commit을 revert하고 모든 Flow test 재실행 |
| SDK retry가 의미상 출력을 반복 | publisher 교체 후 text 반복 | native 동작을 보존하고 exactly-once 미지원 문서화, transactional delta 사용 차단 | 호출자를 non-streaming `Converse`로 전환하고 숨은 dedupe는 추가하지 않음 |
| 취소 시 request/subscription 유출 | collector 취소 후에도 future 활성 상태 유지 | cancel-once ledger와 timeout test | 작업 4/5에서 멈추고 문서 작성 전에 수정 |
| `compileOnly` 경계 유출 또는 consumer 설정 누락 | 게시 metadata에 서비스 SDK가 포함되거나 README에 필수 좌표 누락 | 작업 6–7의 runtimeClasspath/publication audit와 consumer dependency 예제 | alias/build 선언과 문서를 함께 revert |
| 인증 정보가 필요한 smoke가 실수로 실행 | 기본 test가 network/client 생성을 시도 | 기본 tag 제외와 client 생성 전 property/env gate | `-PbedrockSmoke`를 비활성화하고 smoke는 N/A 유지 |
| cold Flow 수집 전에 client 종료 | `withBedrockRuntimeClient`가 사용할 수 없는 Flow 반환 | lifecycle test와 block 내부 terminal collection 문서 | caller-owned client 경로 사용 |
| Diagram이 exactly-once/backpressure를 과장 | 시각 자료가 retry를 투명하거나 무제한이라고 표현 | source와 일치하는 retry alt frame 및 full-size 검사 | SVG source를 수정하고 PNG 재생성 후 audit 재실행 |

pre-release rollback이 필요하면 의존성 역순으로 되돌린다. 순서는
status/docs/diagram, smoke/publication/consumer guard, Kotlin facade, Java
streaming, Java non-streaming operation, Java 기반, module/root 의존성 선언과
catalog alias다. rollback 후 publication metadata를 다시 생성하고 consumer
fixture compile, module compile, 여전히 적용되는 manual/diagram 검사와
`git diff --check`를 재실행한다.

### 작업 1: Bedrock Runtime 의존성 등록

**복잡도:** 중간

**의존 대상:** 승인된 명세

**적용 항목:** `bluetape-kotlin-patterns`, `test-driven-development`

**쓰기 범위:** catalog와 Gradle build 파일 4개만

- [ ] **1단계: 기존 version authority 아래에 catalog alias 추가**

`gradle/libs.versions.toml`에서 다른 service alias 옆을 수정한다.

```toml
aws2-bedrock-runtime = { module = "software.amazon.awssdk:bedrockruntime" }
aws-kotlin-bedrock-runtime = { module = "aws.sdk.kotlin:bedrockruntime" }
```

새 version key를 추가하지 않는다. Java는 기존 AWS SDK v2 BOM을 통해 해석하고,
Kotlin은 root `aws-kotlin` constraint를 통해 해석한다.

- [ ] **2단계: root dependency-management 항목 추가**

`build.gradle.kts`에서 생성된 local-alias section을 수정한다.

```kotlin
dependency("aws.sdk.kotlin:bedrockruntime:${bt4kVersion("aws-kotlin")}")
dependency("software.amazon.awssdk:bedrockruntime:${bt4kVersion("aws2")}")
```

각 항목을 해당 SDK group 안에서 사전순으로 배치한다.

- [ ] **3단계: module 범위 compile 및 test 의존성 추가**

`aws-java/build.gradle.kts`를 수정한다.

```kotlin
compileOnly(libs.aws2.bedrock.runtime)
testImplementation(libs.aws2.bedrock.runtime)
```

`aws-kotlin/build.gradle.kts`를 수정한다.

```kotlin
compileOnly(libs.aws.kotlin.bedrock.runtime)
testImplementation(libs.aws.kotlin.bedrock.runtime)
```

- [ ] **4단계: 의존성 해석과 compile-only 배치 검증**

실행:

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

예상 결과: Java는 기존 `aws2` version을, Kotlin은 기존 `aws-kotlin` version을
해석하며 어느 command도 새 version key를 도입하지 않는다.

- [ ] **5단계: 의존성 경계 commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts aws-java/build.gradle.kts aws-kotlin/build.gradle.kts
git commit -m "Enable the Bedrock facade without widening runtime dependencies" \
  -m "Constraint: Bedrock service SDKs remain compileOnly and reuse existing AWS version authorities
Confidence: high
Scope-risk: narrow
Tested: Java and Kotlin dependencyInsight for bedrockruntime
Not-tested: Public facade code is introduced in later tasks"
```

### 작업 2: Java client, request 및 response 기반 구축

**복잡도:** 높음

**의존 대상:** 작업 1

**적용 항목:** `bluetape-kotlin-patterns`, `test-driven-development`

**쓰기 범위:** Java client support, model support 및 관련 집중 test

- [ ] **1단계: 실패하는 Java client lifecycle 및 endpoint test 작성**

다음 실행 가능한 case를 포함하는 `BedrockRuntimeClientSupportTest.kt`를 생성한다.

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

실행:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests '*BedrockRuntimeClientSupportTest' \
  --no-daemon --no-configuration-cache
```

예상 결과: Bedrock client helper가 없으므로 FAIL.

- [ ] **2단계: Java sync 및 async client factory 구현**

`BedrockRuntimeClientSupport.kt`와
`BedrockRuntimeAsyncClientSupport.kt`를 생성한다. 기존 STS/EventBridge
형식을 사용하고 새로 생성한 모든 client를 등록하며 non-loopback HTTP를 거부한다.

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

이는 DNS lookup이 아니라 명시적인 literal allowlist다.
`http://127.0.0.2`, `http://[::2]`, 누락된 host, non-HTTP scheme에 대한 부정
test와 `localhost`, `127.0.0.1`, `[::1]`에 대한 긍정 test를 추가한다. 작업 5의
Kotlin `Url` helper에도 동일한 정규화와 test matrix를 적용한다.

`BedrockRuntimeAsyncClientBuilder`, `SdkAsyncHttpClient`,
`SdkAsyncHttpClientProvider.defaultHttpClient`에도 동일한 계약을 적용한다.
raw builder factory와 `Of` factory 모두 `ShutdownQueue` 등록 전에 최종 생성된
client의 `serviceClientConfiguration().endpointOverride()`를 검증해야 한다.
검증이 실패하면 해당 임시 client를 정확히 한 번 닫고 아무것도 반환하지 않는다.
명시적 endpoint 인자는 `null`이지만 builder만 `http://example.com`을 설정하는
sync/async regression test를 추가한다. 두 경로 모두 등록 전에 거부하고 닫아야 한다.
모든 public factory에 caller-owned close 책임, 신뢰할 수 있는 endpoint 규칙,
consumer가 Java Bedrock Runtime SDK를 추가해야 한다는 사실을 포함한 영문 KDoc을 작성한다.

- [ ] **3단계: 실패하는 Java request-builder test 작성**

`BedrockRuntimeRequestSupportTest.kt`를 생성한다. 모든 helper-owned field와
builder precedence를 검증한다.

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

빈 text/model ID와 빈 message가 client 호출 전에 실패하는지도 검증한다.

실행:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests '*BedrockRuntimeRequestSupportTest' \
  --no-daemon --no-configuration-cache
```

예상 결과: request helper가 없으므로 FAIL.

- [ ] **4단계: Java request helper 구현**

다음 전체 계약을 포함하는 `model/BedrockRuntimeRequestSupport.kt`를 생성한다.

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

`ConverseStreamRequest.Builder`를 사용해 `converseStreamRequestOf`도 동일하게
구현한다. 네 public model builder 모두에 helper-owned field precedence와
모델 중립 동작을 포함한 영문 KDoc을 작성한다.

- [ ] **5단계: 실패하는 Java response-mapping test 작성**

`BedrockRuntimeResponseSupportTest.kt`를 생성하고 text/tool-use가 혼합된 content를
구성한다. 다음을 검증한다.

```kotlin
response.textContents() shouldBeEqualTo listOf("hello", " world")
response.firstTextOrNull() shouldBeEqualTo "hello"
response.textOrEmpty() shouldBeEqualTo "hello world"
response.textOrEmpty("|") shouldBeEqualTo "hello| world"
nonTextResponse.textContents().shouldBeEmpty()
textDeltaOutput.textDeltaOrNull() shouldBeEqualTo "delta"
metadataOutput.textDeltaOrNull().shouldBeNull()
```

text block 1,000개를 포함하고 시간 임계값 없이 순서와 content를 검증한다.

실행:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests '*BedrockRuntimeResponseSupportTest' \
  --no-daemon --no-configuration-cache
```

예상 결과: response helper가 없으므로 FAIL.

- [ ] **6단계: Java response helper 구현**

`model/BedrockRuntimeResponseSupport.kt`를 생성한다.

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

이 accessor는 AWS SDK for Java v2 `2.47.1`에 고정된다. non-text Java union의
`text()`는 `null`을 노출하고 stream delta event는 `ConverseStreamOutput`의 구체적인
`ContentBlockDeltaEvent` subtype이다. synthetic discriminator를 추가하거나 SDK
exception을 catch하지 않는다.

Kotlin helper 계약과 일치하도록 누락된 output, message variant가 없는 output,
누락된 content를 빈 text content로 처리하는지 test한다. count 기능이 있는
`AbstractList<ContentBlock>`을 기반으로 mock native response/output/message
객체를 사용해 `firstTextOrNull()`이 첫 text block에서 중단하고 `textOrEmpty()`가
iterator를 한 번만 순회함을 입증한다. 어느 helper도 `textContents()`에 위임하지
않는다는 source-contract assertion을 추가한다. 전체 join은 중간 text list를
할당하지 않아야 한다. 모든 response helper에 non-text content를 건너뛰되 raw
native SDK 타입은 계속 사용할 수 있음을 명시한 영문 KDoc을 작성한다.

- [ ] **7단계: Java 기반 GREEN 검증**

실행:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests '*BedrockRuntimeClientSupportTest' \
  --tests '*BedrockRuntimeRequestSupportTest' \
  --tests '*BedrockRuntimeResponseSupportTest' \
  --no-daemon --no-configuration-cache
```

예상 결과: network 호출 없이 PASS.

- [ ] **8단계: Java 기반 commit**

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

### 작업 3: Java sync, future 및 suspend Converse operation 추가

**복잡도:** 중간

**의존 대상:** 작업 2

**적용 항목:** `bluetape-kotlin-patterns`, `kotlin-coroutines-skill`, `test-driven-development`

**쓰기 범위:** Java operation extension과 관련 test

- [ ] **1단계: 실패하는 sync 및 future 위임 test 작성**

`BedrockRuntimeClientExtensionsTest.kt`와
`BedrockRuntimeAsyncClientExtensionsTest.kt`를 생성한다.

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

실행:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests '*BedrockRuntimeClientExtensionsTest' \
  --tests '*BedrockRuntimeAsyncClientExtensionsTest' \
  --no-daemon --no-configuration-cache
```

예상 결과: 편의 operation이 없으므로 FAIL.

- [ ] **2단계: sync 및 future 편의 operation 구현**

extension 파일 2개를 생성한다.

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

raw-request overload는 추가하지 않는다. 이미 native SDK member가 제공한다.
sync/future helper에 exactly-one-call과 native response/error 소유권을 설명하는
영문 KDoc을 작성한다.

- [ ] **3단계: 실패하는 suspend 성공, 실패 및 취소 test 작성**

`BedrockRuntimeAsyncClientCoroutinesExtensionsTest.kt`를 생성한다.

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

future를 SDK exception으로 exceptional completion하고 동일한 exception 타입이
caller에 도달하는지도 검증한다.

- [ ] **4단계: `await()`를 사용한 suspend operation 구현**

`BedrockRuntimeAsyncClientCoroutinesExtensions.kt`를 생성한다.

```kotlin
suspend inline fun BedrockRuntimeAsyncClient.converse(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseResponse =
    converseAsync(modelId, messages, inferenceConfig, builder).await()
```

KDoc에는 coroutine 취소가 SDK future로 전달되며 extension이 외부 client를 닫거나
retry/timeout을 추가하지 않는다고 명시해야 한다.

- [ ] **5단계: Java operation GREEN 검증**

실행:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests '*BedrockRuntime*ExtensionsTest' \
  --no-daemon --no-configuration-cache
```

예상 결과: PASS. MockK가 helper 호출마다 SDK 호출이 정확히 한 번임을 검증한다.

- [ ] **6단계: Java non-streaming operation commit**

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

### 작업 4: 제한된 demand를 사용하는 Java streaming 구현

**복잡도:** 높음

**의존 대상:** 작업 2와 3

**적용 항목:** `bluetape-kotlin-patterns`, `kotlin-coroutines-skill`, `test-driven-development`

**쓰기 범위:** Java Flow extension, 결정론적 test publisher 및 streaming test

- [ ] **1단계: 결정론적 reactive-streams test publisher 생성**

`RecordingSdkPublisher.kt`를 생성한다. reactive-streams demand를 준수하면서 모든
request, outstanding demand, emit된 item, cancel 호출을 기록해야 한다.

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

race test가 안전하지 않게 공유된 mutable state에 의존하지 않도록 ledger 변경에
synchronization 또는 atomic을 사용한다. 일반 `emitOne`, `complete`, `fail` method는
cancel/terminal 이후 signal을 억제한다. 잘못 동작하는 publisher case가 일반
assertion에 섞이지 않도록 late-event/error/complete test에는 별도의 명시적
adversarial method를 제공한다.

- [ ] **2단계: coldness, incrementality 및 demand에 대한 RED test 작성**

`BedrockRuntimeFlowExtensionsTest.kt`를 생성한다. mock async client에 전달되는
`ConverseStreamResponseHandler`를 capture하고 수동으로 구동한다.

필수 test:

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

기록된 모든 request가 `1`이고 `maxOutstanding == 1`이며 순서가 보존되고 요청하지
않은 값 때문에 publisher 측 queue가 증가하지 않음을 검증하는 slow collector
test를 추가한다. early-terminal collector가 활성 subscription과 operation future를
정확히 한 번 취소함을 입증하는 별도 `first()` test를 추가한다. `first()`는 첫
element 이후 upstream을 의도적으로 취소하므로 incrementality test에는 사용하지 않는다.

실행:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests '*BedrockRuntimeFlowExtensionsTest' \
  --no-daemon --no-configuration-cache
```

예상 결과: streaming Flow가 없으므로 FAIL.

- [ ] **3단계: generation 인식 coordinator 정의**

`BedrockRuntimeFlowExtensions.kt`에서 state를 하나의 collection 내부에만 둔다.

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

다음 정확한 invariant를 만족하도록 coordinator method를 구현한다.

- `replaceFromCallback(publisher)`는 callback 수신 시 단조 증가 sequence를
  동기적으로 할당한 뒤 `replace(sequence,
  publisher)`를 collection scope의 child로 시작한다.
- `replace(sequence, publisher)`는 `Mutex` 안에서 최신 generation을 점유한다.
  이미 점유한 generation보다 오래된 sequence는 immediate-cancel subscriber로
  subscribe하며 기존 generation을 교체할 수 없다.
- 점유 후 이전 `StreamAttempt`를 분리하고 atomic `cancelOnce()`를 호출한 뒤
  mutex 밖에서 join한다. subscribe 전에 generation과 terminal state를 다시
  확인한다. 취소/join 중 더 최신 callback이 이겼다면 이 publisher를 즉시 취소한다.
- 승리한 generation은 `publisher.asFlow().buffer(0)`을 collect한다.
  `StreamAttempt.cancelOnce()`가 모든 replacement/finally/terminal 경로를 보호해
  동시 replacement가 같은 subscription을 두 번 취소하지 못하게 한다.
- emit/terminal signal마다 `Mutex` 안에서 capture한 generation을 확인하며 이전
  generation의 signal은 무시한다.
- mutex는 suspend하지 않는 state snapshot과 transition만 보호한다. mutex를
  보유한 상태에서 `emit`/`send`, `join`, subscribe, cancel을 호출하지 않는다.
  attempt child가 suspend 가능성이 있는 rendezvous `send`를 소유하므로, 최신
  callback은 slow collector를 기다리지 않고 이전 attempt를 취소하고 replacement를 활성화할 수 있다.
- `futureSucceeded()`는 최신 publisher가 완료된 후에만 완료하고 publisher가
  도착하지 않으면 빈 결과로 완료하며 최신 publisher error를 보존한다. 이는
  suspend terminal barrier다. operation future의 성공 경로는 바깥 `finally`가
  활성 state를 취소하기 전에 승리한 attempt를 기다려야 한다.
- `futureFailed(cause)`와 `cancel()`은 atomic하게 한 번만 승리하고 활성 attempt를
  정확히 한 번 취소한다.
- future 성공 후 도착한 publisher callback은 immediate-cancel subscriber로만
  subscribe하며 현재 generation을 교체하지 않는다.
- dispatcher 전환, 외부 scope, retry, replay, logging, content deduplication을
  도입하지 않는다.

- [ ] **4단계: AWS response handler와 operation future 연결**

raw-request overload를 cold `channelFlow`로 구현한다.

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

protected generic parent interface가 아니라 생성된 `ConverseStreamResponseHandler`를
capture하고 mock한다. `responseReceived`는 새 state를 보존하지 않으며 raw event가
public output으로 유지된다. `exceptionOccurred`는 최신 handler-attempt failure를
기록하지만 retry 중 호출될 수 있으므로 operation future보다 먼저 확정하지 않는다.
`complete`는 handler 완료를 기록하지만 필수인 최신 publisher terminal과
operation-future 성공 barrier를 대체하지 않는다. `replaceFromCallback`은 현재
`channelFlow` scope의 child job만 시작하며 `GlobalScope`를 사용해서는 안 된다.
바깥 `.buffer(0)`은 필수다. 안쪽 reactive bridge가 subscription demand를 제한하고,
바깥 rendezvous boundary는 `channelFlow`의 기본 capacity가 slow collector보다 앞서
queueing하는 것을 막는다. 두 public `converseStreamFlow` overload에는 cold/billable
재수집, caller-owned client lifetime, `request(1)`, collector 취소, SDK retry의
의미상 중복, exactly-once/deduplication 부재를 설명하는 영문 KDoc을 작성한다.

`converseStreamRequestOf(modelId, messages, inferenceConfig, builder)`를 생성하는
편의 overload를 추가한다.

- [ ] **5단계: race, retry 및 terminal RED/GREEN test 추가**

wall-clock sleep 대신 결정론적 barrier를 사용하도록
`BedrockRuntimeFlowExtensionsTest.kt`를 확장한다.

- future 성공 전 publisher 완료
- publisher 완료 전 future 성공
- publisher 도착 전 future 실패
- publisher error 후 replacement generation 도착
- publisher error 후 replacement 없이 future 성공
- replacement가 이전 subscription을 한 번 취소
- 이전 generation의 late event/error/complete 무시
- generation N의 partial event는 계속 보이고 generation N+1은 의미상 중복 text를 emit 가능
- publisher callback 전 collector 취소 시 future를 취소하고 늦게 도착한 publisher를 즉시 취소
- 취소와 경합하는 callback도 하나의 terminal 결과만 생성
- scheduler 진행 전에 callback A와 B를 받아도 B를 활성화하고 A를 즉시 취소하며 A를 B 뒤로 재정렬하지 않음
- A가 cancel/join 중일 때 받은 callback B가 유일한 활성 generation이 되고 A를 이중 취소하지 않음
- A가 바깥 rendezvous `send`에서 suspend된 동안 받은 callback B가 A를 정확히 한 번 취소하고 collector barrier가 풀리기 전에 B를 활성화하며 A는 late item을 전달할 수 없음
- `onEventStream` 호출 후 동기 `converseStream` 실패가 발생해도 `finally`에 진입해 subscription을 한 번 취소하고 원래 exception을 보존
- `withTimeout`이 다른 호출을 시작하지 않고 future와 subscription을 취소

`CompletableDeferred`/`Channel` barrier를 사용하고 `cancelCount`, invocation count,
terminal count, emit된 identity를 검증한다. slow-collector case에서는 collector를
barrier 뒤에 멈추고 이전 event가 바깥 rendezvous boundary를 통과할 때까지 publisher가
다음 item을 request/deliver할 수 없음을 입증한다. test cluster마다 targeted command를
실행하며 최종 예상 결과는 PASS다.

- [ ] **6단계: bluetape4k-coroutines를 사용한 text-delta Flow mapping 추가**

추가:

```kotlin
fun Flow<ConverseStreamOutput>.textDeltaFlow(): Flow<String> =
    map(ConverseStreamOutput::textDeltaOrNull).castNotNull<String>()
```

text delta 순서, 빈 문자열 보존, non-text filtering, SDK error 전파를 test한다.
signal을 받은 뒤 다음 upstream event 이후 repository extension이 종료됨을 확인하는
`takeUntil(stopSignal)` test를 하나 추가한다. 조용한 upstream이 즉시 취소된다고
주장하지 않는다. KDoc에는 non-text event를 filtering하고 빈 text를 보존하며 logging,
parallel mapping, retry, replay를 추가하지 않는다고 명시해야 한다.

- [ ] **7단계: Java streaming 및 compile diagnostic 검증**

실행:

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

예상 결과: PASS. ledger에 `request(1)`, 최대 outstanding demand 1,
cancel-once, terminal 결과 1개가 표시된다.

- [ ] **8단계: Java streaming 경계 commit**

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

### 작업 5: AWS Kotlin SDK facade 구현

**복잡도:** 높음

**의존 대상:** 작업 1. SDK 타입을 공유하지 않고 작업 2–4의 동작을 반영

**적용 항목:** `bluetape-kotlin-patterns`, `kotlin-coroutines-skill`, `test-driven-development`

**쓰기 범위:** AWS Kotlin Bedrock source와 unit test만

- [ ] **1단계: 실패하는 Kotlin client lifecycle test 작성**

`BedrockRuntimeClientSupportTest.kt`를 생성한다. caller-owned 생성, 명시적 close,
성공/오류/취소 시 block-owned close, builder/HTTP engine forwarding, endpoint
거부를 검증한다.

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

2단계에서 정의한 internal factory overload를 통해 close 소유권을 구동한다. mock
`BedrockRuntimeClient`를 반환하고 seam을 통해 public lifecycle body를 실행한 뒤
성공, 실패, 취소 각각에서 `close()`가 정확히 한 번 호출되는지 검증한다.

- [ ] **2단계: Kotlin client 소유권 구현**

`BedrockRuntimeClientSupport.kt`를 생성한다.

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

public overload는 소유권을 internal factory overload에 위임한다. unit test는 seam을
통해 mock `BedrockRuntimeClient`를 전달하고 성공, 실패, 취소 후 `close()`가 정확히
한 번 호출되는지 검증한다. production caller는 factory를 교체할 수 없다. 두 public
lifecycle helper에 caller-owned client와 block-owned client를 명확히 구분하고
범위 block이 닫힌 뒤 빠져나온 cold Flow를 collect하는 행위를 금지하는 영문 KDoc을 작성한다.

DNS resolution 없이 `Url.scheme.protocolName`과 `Url.host.toString()`에서
HTTPS/loopback HTTP를 검증한다. builder가 설정할 수 있으므로 명시적인
`endpointUrl` 인자가 `null`이어도 build 후 `client.config.endpointUrl` 검증은
필수다. builder만 `http://example.com`을 설정하는 regression test를 추가하고
exception이 빠져나가기 전에 임시 client가 정확히 한 번 닫히는지 검증한다.

- [ ] **3단계: 실패하는 Kotlin request 및 response test 작성**

model test 파일 2개를 생성하고 native sealed-union constructor를 사용한다.

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

Java와 동일한 inference-config precedence, blank/empty 거부, 혼합 content
filtering, 빈 delta 보존, 1,000 block single-pass join을 검증한다.

- [ ] **4단계: Kotlin model helper 구현**

`model/BedrockRuntimeRequestSupport.kt`를 생성한다.

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

request builder에서는 builder를 먼저 적용한 뒤 `modelId`, `messages`, non-null
`inferenceConfig`를 설정한다.

`model/BedrockRuntimeResponseSupport.kt`를 생성한다.

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

모든 public Kotlin model/response helper에 helper-owned precedence, sealed-union
filtering, 빈 값, native SDK 타입 보존을 다루는 영문 KDoc을 작성한다. Java의
counting-list와 source-contract test를 반영한다. `firstTextOrNull()`은 첫 text
block에서 중단하고 `textOrEmpty()`는 content를 한 번 순회하며, 어느 것도
`textContents()`에 위임하지 않고 join은 중간 text list를 할당하지 않는다.

- [ ] **5단계: 실패하는 native suspend 및 Flow test 작성**

`BedrockRuntimeClientExtensionsTest.kt`와 `BedrockRuntimeFlowExtensionsTest.kt`를
생성한다. native suspend operation을 mock하고 다음을 검증한다.

- 편의 `converse`가 한 번 위임하고 response identity를 보존
- SDK exception과 coroutine 취소가 변경 없이 전파
- Flow가 cold이고 collection마다 `converseStream`을 한 번 호출
- `response.stream == null`은 operation 성공 후에만 빈 결과로 완료
- 추가 buffering 없이 native event 순서/오류/취소 보존
- `withBedrockRuntimeClient` 예제가 owned client 종료 전에 collect
- barrier 기반 active stream 취소가 `stream-finally -> client-close-once -> caller cancellation`을 기록해 close가 upstream structured cancellation보다 앞서 경합하지 않음을 입증
- `textDeltaFlow()`가 `castNotNull`을 사용하고 빈 text를 보존하며 non-text event를 filtering
- `takeUntil` test가 다음 upstream event에서 종료되는 계약을 사용

- [ ] **6단계: Kotlin operation 및 Flow 구현**

`BedrockRuntimeClientExtensions.kt`를 생성한다.

```kotlin
suspend inline fun BedrockRuntimeClient.converse(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    crossinline builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseResponse =
    converse(converseRequestOf(modelId, messages, inferenceConfig, builder))
```

`BedrockRuntimeFlowExtensions.kt`를 생성한다.

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

exception을 catch하거나 dispatcher를 전환하지 않는다. 모든 Kotlin operation/Flow
helper에 native structured cancellation, cold/billable 재수집, scoped-client
collection, retry/replay/logging을 추가하지 않음을 설명하는 영문 KDoc을 작성한다.

- [ ] **7단계: Kotlin facade GREEN 검증**

실행:

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

예상 결과: PASS. 실제 AWS 호출 없음.

- [ ] **8단계: Kotlin facade commit**

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

### 작업 6: opt-in smoke 및 publication 경계 입증

**복잡도:** 중간

**의존 대상:** 작업 1–5

**적용 항목:** `bluetape-kotlin-patterns`, `test-driven-development`

**쓰기 범위:** module test configuration, Bedrock smoke test, root consumer
fixture task, 격리된 consumer source

- [ ] **1단계: 인증 정보가 필요한 smoke test를 기본적으로 제외**

`aws-java/build.gradle.kts`와 `aws-kotlin/build.gradle.kts`에 동일한 JUnit tag
정책을 추가한다.

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

`-PbedrockSmoke`, 비어 있지 않은 `BEDROCK_REGION`, 비어 있지 않은
`BEDROCK_MODEL_ID`를 모두 만족할 때만 smoke tag를 포함한다. property가 없으면
tag를 제외하고 일반 test를 실행한다. property가 있지만 어느 environment 값이든
누락되면 위의 명시적인 pre-client 사유로 task를 건너뛴다.

- [ ] **2단계: SDK별 모델 중립 smoke test 하나 추가**

두 `BedrockRuntimeSmokeTest.kt` 파일을 `@Tag("bedrock-smoke")`와 함께 생성한다.
직접 test runner를 사용해도 fail closed하도록 client 생성 전에 두 environment 값을
다시 확인한다. user message 하나, `maxTokens = 8`, native `Converse` response를
사용하고 provider-specific 문구/model assertion은 사용하지 않는다.

Java는 30초 `ClientOverrideConfiguration.apiCallTimeout`과
`bedrockRuntimeClientOf(region = Region.of(region)).use { ... }`를 사용한다.
Kotlin은 scoped builder에서 `callTimeout = 30.seconds`를 설정하고 호출을
`withTimeout(35.seconds)`로 감싼다. 둘 다 최소 한 개의 text content block을 검증한다.

allowlist에 포함된 evidence line만 기록한다. 항목은 lane, pass/fail, 경과
millisecond, 승인된 region, 승인된 model ID, request ID다. Java는
`response.responseMetadata().requestId()`를 읽는다. AWS Kotlin SDK `1.8.0`은
`ConverseResponse`에서 request metadata를 노출하지 않으므로 성공 evidence에는
`requestId=not-exposed-by-sdk-1.8.0`을 기록한다. service failure에서는 가능할 때
SDK exception request ID를 기록할 수 있다. prompt, 생성 output, credential,
endpoint secret, raw exception body는 절대 log하지 않는다.

JUnit이 원래 throwable을 render하기 전에 각 smoke test가 SDK/service 및 transport
failure를 catch해야 한다. `CancellationException`은 변경 없이 다시 던진다. 다른
failure는 exception class, 가능한 경우 SDK error code와 request ID, lane, 경과
시간, 승인된 region과 model ID만 추출한다. 원래 cause나 suppressed exception이
없는 새 sanitized `AssertionError`로 실패시킨다. `message`, stack trace, endpoint,
header, response body는 포함하지 않는다. message/cause에 sentinel secret이 든 fake
SDK exception을 사용하는 offline unit test를 추가하고, sanitized failure에 allowlist
field만 있으며 두 sentinel은 없음을 검증한다.

- [ ] **3단계: 기본 test가 offline으로 유지됨을 입증**

opt-in property 없이 실행:

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

예상 결과: 모든 unit test가 통과하고 실행된 test report에 tag가 지정된 smoke
method가 없다. XML report count를 DoD evidence로 보존한다.

- [ ] **4단계: 인증 정보가 필요한 command를 문서화하되 필수로 요구하지 않음**

operator command:

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

두 variable과 사용 가능한 AWS credential을 의도적으로 제공했을 때만 실행한다.
그렇지 않으면 `-PbedrockSmoke`와 누락된 것으로 확인된 variable로 한 번 실행해
명시적인 pre-client `SKIP` line을 수집하고, 일반 completion gate를 약화하지 않은 채
`N/A: credentialed Bedrock invocation is opt-in; <skip reason>`을 기록한다.

- [ ] **5단계: 게시 metadata가 service-SDK-neutral로 유지됨을 검증**

실행:

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

예상 결과: publication audit가 통과하고 게시된 POM이나 Gradle module metadata
어느 것도 Bedrock service SDK를 runtime-transitive하게 만들지 않는다. 두
runtimeClasspath report 모두 일치하는 dependency를 찾지 못했다고 표시해야 한다.

- [ ] **6단계: 격리된 consumer fixture compile**

각 `src/consumerFixture` path 아래에 Kotlin source 하나를 생성한다. Java fixture는
`bedrockRuntimeAsyncClientOf`, `userMessageOf`, `converseStreamFlow`,
`textDeltaFlow`를 import하고 Kotlin fixture는 이에 해당하는 native-client helper를
import한다. 각 function은 client를 실행하지 않고 `Flow<String>`을 반환해 public
facade와 native SDK 타입이 compile됨을 입증한다.

root `build.gradle.kts`에 해석 가능한 configuration 2개를 생성한다. Java fixture
classpath에는 다음을 명시적으로 추가해야 한다.

```kotlin
project(":bluetape4k-aws-java")
libs.aws2.bedrock.runtime
bt4k.bluetape4k.coroutines
libs.kotlinx.coroutines.core
libs.kotlinx.coroutines.reactive
```

Kotlin fixture classpath에는 다음을 명시적으로 추가해야 한다.

```kotlin
project(":bluetape4k-aws-kotlin")
libs.aws.kotlin.bedrock.runtime
bt4k.bluetape4k.coroutines
libs.kotlinx.coroutines.core
```

`org.gradle.api.artifacts.Configuration`, `org.jetbrains.kotlin.gradle.dsl.JvmTarget`,
`org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile`을 import한다. 기존
consumer-fixture pattern에 따라 격리된 configuration과 compile task를 등록한다.

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

RED compile 중 Kotlin Gradle plugin이 task property를 거부하면 facade code를
작성하기 전에 plugin의 기존 task API를 확인하고 등록을 수정한다. 격리된 classpath를
약화하지 않는다. 그다음 실행:

```bash
./gradlew \
  compileBedrockJavaConsumerFixture \
  compileBedrockKotlinConsumerFixture \
  --no-daemon \
  --no-configuration-cache
```

예상 결과: consumer configuration이 게시된 runtime metadata에서 제외된 Bedrock과
coroutine/reactive dependency를 명시적으로 제공하기 때문에 두 fixture가 compile된다.

- [ ] **7단계: smoke, consumer 및 metadata guard commit**

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

### 작업 7: 이중 언어 안내와 streaming diagram 게시

**복잡도:** 높음

**의존 대상:** 작업 2–6과 최종 source 형태

**적용 항목:** `bluetape-writer`, `bluetape-diagram`

**쓰기 범위:** module README 4개, locale diagram pair 2개, `CHANGELOG.md`, `WIP.md`

- [ ] **1단계: source와 일치하는 Java 및 Kotlin 안내 추가**

각 module의 영문 및 한글 README를 갱신한다. 자연스러운 한국어 문장을 작성하면서
locale 구조를 일치시킨다. release에 고정된 `docs/manual/` page는 수정하지 않는다.
manifest가 `0.4.0`에 고정되어 있고 새 Bedrock source/diagram asset은 해당 peeled
release tree에 없기 때문이다. 상세 manual 갱신은 `0.5.0` release-manual refresh
범위다. 유효하지 않은 release link를 만들지 말고 이 경계를 PR DoD에 기록한다.

각 README pair에는 다음을 포함해야 한다.

- `io.github.bluetape4k:bluetape4k-dependencies:<version>`를 import한 뒤 facade module과 consumer-owned service/runtime dependency를 선언하는 복사 가능한 Gradle snippet
- `bluetape4k-aws-java`, `software.amazon.awssdk:bedrockruntime`, `bluetape4k-coroutines`, `kotlinx-coroutines-core`, `kotlinx-coroutines-reactive`를 포함한 Java snippet
- `bluetape4k-aws-kotlin`과 `aws.sdk.kotlin:bedrockruntime`을 포함한 Kotlin snippet
- native SDK 타입을 사용하는 모델 중립 `Converse` 및 `ConverseStream` 예제
- Java sync/future/suspend 선택지와 Kotlin native suspend 동작
- caller-owned client와 Kotlin `withBedrockRuntimeClient` 비교
- scoped client block 내부의 terminal collection
- `castNotNull`을 사용하는 `textDeltaFlow()`와 signal 이후 다음 upstream event에서만 종료되고 조용한 stream을 강제 종료하지 않는 cooperative `takeUntil(stopSignal)` 예제
- caller-owned hard deadline을 위한 별도 `withTimeout` 예제와 timeout이 SDK call/subscription을 취소해도 이미 emit된 partial output은 유지된다는 설명
- collection마다 새 billable invocation이 발생하고 결과가 달라질 수 있다는 cold-flow 비용 경고
- 이미 emit된 partial text가 의미상 중복될 수 있고 exactly-once나 deduplication을 보장하지 않는다는 Java SDK retry 경고
- transactional consumption에는 non-streaming `Converse`가 더 안전하다는 안내
- helper validation은 `IllegalArgumentException`을 사용하고 AWS failure는 native SDK exception 타입을 유지하며 exceptional future는 exceptional 상태를 유지하고 coroutine/collector 취소는 facade가 retry를 추가하지 않은 채 upstream으로 전파되며 streamed partial output은 rollback되지 않는다는 public failure/cancellation 계약
- 외부에서 전달한 client는 caller-owned로 유지하고 raw SDK exception 전체를 log하거나 반환하지 않는다는 lifecycle/error 안전 규칙
- endpoint/credential trust, 신뢰할 수 없는 생성 output, 자동 tool 실행 금지, allowlist 전용 운영 logging

code sample에서 `Flow.log`, `FlowEvent`, parallel mapping, provider-specific
prompt DTO 또는 prompt framework를 사용하지 않는다. 5단계의 locale parity 검사는
README 4개에서 cooperative 종료와 hard 종료의 차이 및 위의 모든
error/cancellation/lifecycle 설명을 검증해야 한다.

- [ ] **2단계: locale별 sequence diagram family 설계**

그리기 전에 local image viewer로 `aws-java-sequence-03.png`와
`aws-kotlin-sequence-03.png`를 full size로 열고 확인한 palette, participant-card,
numbered-message, alt-frame, lifeline, typography, warning-card 규칙을 기록한다.
그다음 SVG source를 편집 가능한 reference로 사용해 다음을 생성한다.

```text
docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-en-01.svg
docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-en-01.png
docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-ko-01.svg
docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-ko-01.png
```

다음 공식 Amazon Bedrock icon을 재사용한다.

```text
docs/assets/aws-icons/official-04302026/Architecture-Service-Icons_04302026/Arch_Artificial-Intelligence/48/Arch_Amazon-Bedrock_48.svg
```

sequence에는 collector, facade, `bluetape4k-coroutines`, Java `SdkPublisher` 또는
Kotlin native Flow, Bedrock client, Amazon Bedrock을 표시한다. 번호가 지정된
request/event message, Java `request(1)`, retry publisher replacement, 이전
generation 취소, late-signal 폐기, 의미상 중복 경고, normal/error branch,
collector 취소, collection별 비용 경고를 눈에 띄게 포함한다. 영문 및 한글 asset은
각각 별도의 독자 대상 text를 사용한다.

- [ ] **3단계: 두 SVG 정규화, render 및 audit**

실행:

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

그다음 `sips`로 각 PNG가 SVG viewport의 정확히 2배임을 입증하고 local image
viewer에서 두 PNG를 full size로 검사한다. 계속하기 전에 label 충돌, arrow
endpoint, branch frame, retry/cancel 의미, 한글 glyph, 비용 경고를 확인한다.

다음 command로 재현 가능한 dimension을 기록한다.

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

review 산출물에 두 SVG viewport, 두 PNG dimension, 2x 비교 결과를 기록한다.

- [ ] **4단계: repository status surface 정렬**

`CHANGELOG.md`의 `Unreleased / Added` 아래에 issue #312를 추가한다. 편집 직전에
live GitHub state에서 `WIP.md`를 갱신한다.

```bash
gh issue view 312 --repo bluetape4k/bluetape4k-aws \
  --json number,title,state,milestone,labels,url
gh pr list --repo bluetape4k/bluetape4k-aws --state open \
  --json number,title,headRefName,baseRefName,state,url
gh api 'repos/bluetape4k/bluetape4k-aws/milestones?state=all&per_page=100' \
  --jq '.[] | select(.title == "0.5.0") | {title,state,open_issues,closed_issues}'
```

`Backlog`에서 #312를 제거하고 확인한 milestone 및 branch와 함께 `Active Queue`에
추가한다. 관련 없는 backlog 항목은 다시 작성하지 않고 해당 결과에서
snapshot/date/count를 갱신한다.

- [ ] **5단계: document 및 asset 계약 검증**

실행:

```bash
./gradlew exportManualModuleInventory --no-daemon --no-configuration-cache
ruby scripts/manual/manual_contract_test.rb
ruby scripts/manual/validate_manuals.rb
ruby scripts/manual/export_manifest.rb \
  docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
git diff --check
```

각 새 Markdown image/link를 포함한 파일 기준으로 해석하고 영문 파일은 영문
diagram만, 한글 파일은 한글 diagram만 참조하는지 검증한다.

- [ ] **6단계: documentation 및 authoritative asset commit**

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

### 작업 8: Type A review 실행, lesson 기록 및 PR 생성

**복잡도:** 높음

**의존 대상:** 작업 1–7

**적용 항목:** `requesting-code-review`, `verification-before-completion`,
`finishing-a-development-branch`, `bluetape-workflow`

**쓰기 범위:** review/lesson 산출물과 review에서 요구한 수정

- [ ] **1단계: 전체 local verification matrix 실행**

targeted test를 먼저 실행한 뒤 emulator test가 Docker resource를 공유하므로 전체
module을 순차 실행한다.

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

task 이름, count, 결과와 evidence 재현에 필요한 Colima socket/JDK attach
environment를 기록한다. 실패한 prerequisite는 수정할 때까지 downstream review를 중단한다.

- [ ] **2단계: Step 6-R 독립 code review 실행**

정확한 branch diff를 Developer/API, stability, operator/ops, security,
user/caller, performance 관점에서 review한다. file/line evidence와 P0–P3 severity를
요구한다. finding을 `docs/review/2026-07-23-issue-312-code-review.md`에 통합하고
모든 P0/P1을 수정한 뒤 영향받은 관점과 validation을 재실행하며 P0=0/P1=0일 때만 진행한다.

review에서는 다음을 검토해야 한다.

- 고정된 Java/Kotlin SDK source와 public signature의 일치
- Java generation/terminal race, `request(1)`, cancel-once, late signal
- Kotlin 취소와 scoped close 소유권
- `compileOnly` publication/runtime 동작
- prompt/output logging과 endpoint trust
- collection별 비용, retry의 의미상 중복, diagram 정확성
- 대용량 text response의 allocation/order 동작

- [ ] **3단계: 필수 Type A lesson 기록**

`docs/lessons/2026-07-23-issue-312-bedrock-runtime.md`를 한국어로 생성하고
`docs/lessons/README.md`에 추가한다. 다음에 대한 재사용 가능한 evidence를 포함한다.

- machine workflow 초기화 전에 worktree가 생성된 경우 workflow state 복구
- Colima/socket/JDK attach environment failure와 code regression 구분
- public API를 확정하기 전 고정된 source artifact에서 생성된 SDK union 형태 검증
- exactly-once stream output을 잘못 약속하지 않으면서 SDK retry 보존
- `castNotNull`과 `takeUntil`을 재사용하고 `Flow.log`, `FlowEvent`, parallel mapping을 제외한 이유
- 편집 가능한 SVG와 authoritative 2x PNG asset 동기화

- [ ] **4단계: Step 7-R 최종 integration review 실행**

승인된 명세, 구현 계획, 최종 diff, test evidence, review 산출물, lesson, README,
변경하지 않은 release-bound manual 계약, diagram, `CHANGELOG.md`, `WIP.md`를 다시
읽는다. 모든 명세 추적성 row에 구체적인 proof가 있고 모든 새 public declaration에
영문 KDoc이 있으며 public contract drift가 남지 않고 P0/P1 count가 모두 0이며
관련 없는 변경이 branch에 들어오지 않았음을 확인한다.

- [ ] **5단계: review 및 lesson evidence commit**

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

- [ ] **6단계: 승인된 pull request 생성**

먼저 branch와 exact head를 검증한다.

```bash
repo-status
repo-diff origin/develop...HEAD
git log --oneline --decorate origin/develop..HEAD
git push -u origin feat/issue-312-bedrock-runtime
```

base `develop`, head `feat/issue-312-bedrock-runtime`으로
`bluetape4k/bluetape4k-aws`에 PR을 생성한다. 영문 body는 native SDK facade,
Flow/cancellation 동작, dependency 소유권, documentation/diagram asset, test
evidence를 정확히 요약해야 한다. 마지막 level-two section은 `## DoD Status`여야 한다.

사용:

```bash
gh pr create \
  --repo bluetape4k/bluetape4k-aws \
  --base develop \
  --head feat/issue-312-bedrock-runtime \
  --title 'feat(aws): add Bedrock Runtime minimal facade' \
  --body-file build/issue-312-pr-body.md
```

- [ ] **7단계: 새로 생성한 PR 기준으로 WIP 갱신**

생성한 PR과 issue를 즉시 조회한다.

```bash
gh pr view --repo bluetape4k/bluetape4k-aws \
  feat/issue-312-bedrock-runtime \
  --json number,title,state,headRefName,headRefOid,baseRefName,url
gh issue view 312 --repo bluetape4k/bluetape4k-aws \
  --json number,state,milestone,url
```

`WIP.md`의 오래된 `Open PRs: None` row를 확인한 PR 번호, branch, status로
교체하고 merge 전까지 #312를 active 상태로 유지한다. document contract와
`git diff --check`를 실행한 뒤 commit하고 push한다.

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

이 WIP 전용 commit을 새 exact head로 취급한다. delta를 독립적으로 review하고 head
SHA가 변경되면 PR body/DoD 또는 review provenance를 갱신하며 code나 diagram
artifact가 변경되지 않았음을 다시 확인한다.

- [ ] **8단계: exact PR head를 검증하고 merge-ready에서 중단**

현재 check, review, unresolved thread를 poll한다. PR head SHA가 local `HEAD`와
같은지 확인하고 correction commit마다 review provenance를 갱신하며 exact PR/head와
merge-ready evidence를 보고한다. 새롭고 명시적인 사용자 승인 없이 auto-merge를
활성화하거나 merge, publish, tag, release, branch/worktree 삭제를 수행하지 않는다.
