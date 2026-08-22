# Issue #313 AWS Step Functions 실행 helper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Java SDK v2와 AWS SDK for Kotlin에 Step Functions 실행 request·client·polling helper를 추가하고 compileOnly consumer, lifecycle, cancellation, emulator 증거를 고정한다.

**Architecture:** `aws-java`는 sync, `CompletableFuture`, `.await()`, cold Flow 계층을 분리하고 polling은 non-blocking `SfnAsyncClient`에만 둔다. `aws-kotlin`은 native suspend `SfnClient` 위에 같은 request/response 의미와 cold Flow를 제공한다. 두 모듈 모두 raw SDK model과 exception을 유지하며, state machine과 Map Run 목록 helper를 분리하고 client·HTTP engine의 소유권을 명시한다.

**Tech Stack:** Kotlin 2.x, JDK 25, Gradle Kotlin DSL, AWS SDK for Java v2, AWS SDK for Kotlin, kotlinx-coroutines `Flow`/`future.await`, JUnit 5, MockK, Kluent, Floci-first static guard와 Testcontainers LocalStack fallback.

---

## 실행 전 계약

- Worktree: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat-issue-313-step-functions`
- Branch: `feat/issue-313-step-functions`
- Base: `develop` `c9350bc1ae14cd72056fb358d8f3a427467848f9`
- 설계: `docs/superpowers/specs/2026-08-22-issue-313-step-functions-design.md`
- 설계 검토: `docs/review/2026-08-22-issue-313-step-functions-design-review.md`
- 설계 SHA-256: `627e5c6f73f2a8ebbfbf6109b28c5558855e47ae635e24e3c266cfee7aa2618c`
- 구현 방식: 각 task에서 RED를 먼저 확인한 뒤 최소 구현으로 GREEN을 만든다.
- 검증 경계: Floci skip과 LocalStack smoke는 기능 호환성만 증명한다. 실제 AWS IAM/KMS는 credential-gated 별도 증거 없이는 `UNVERIFIED`다.
- 이 계획은 commit까지 포함하지만 push, PR 생성, merge, branch 삭제, release는 포함하지 않는다.

## 파일 책임 맵

| 책임 | 파일 |
|---|---|
| SDK catalog·BOM·compileOnly 검증 | `gradle/libs.versions.toml`, `build.gradle.kts`, `aws-java/build.gradle.kts`, `aws-kotlin/build.gradle.kts` |
| Java client lifecycle | `aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/SfnClientSupport.kt`, `SfnAsyncClientSupport.kt` |
| Java request model | `aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/model/SfnRequestSupport.kt` |
| Java one-shot operation | `aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/SfnExtensions.kt` |
| Java polling | `aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/SfnExecutionFlow.kt` |
| Kotlin client lifecycle | `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/sfn/SfnClientSupport.kt` |
| Kotlin request model | `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/sfn/model/SfnRequestSupport.kt` |
| Kotlin one-shot operation | `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/sfn/SfnExtensions.kt` |
| Kotlin polling | `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/sfn/SfnExecutionFlow.kt` |
| Java tests·emulator | `aws-java/src/test/kotlin/io/bluetape4k/aws/sfn/` |
| Kotlin tests·emulator | `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sfn/` |
| 외부 consumer compile | `aws-java/src/consumerFixture/kotlin/io/bluetape4k/aws/consumer/JavaServiceConsumerFixture.kt`, `aws-kotlin/src/consumerFixture/kotlin/io/bluetape4k/aws/kotlin/consumer/KotlinServiceConsumerFixture.kt` |
| 사용자 문서 | root/module README EN·KO, manual EN·KO module/operations pages, `CHANGELOG.md` |
| 검증 증거 | `docs/review/evidence/2026-08-22-issue-313-step-functions.md` |

## Task 0: 승인된 설계·계획 기준선 기록

**Files:**

- Add: `docs/superpowers/specs/2026-08-22-issue-313-step-functions-design.md`
- Add: `docs/review/2026-08-22-issue-313-step-functions-design-review.md`
- Add: `docs/superpowers/plans/2026-08-22-issue-313-step-functions-plan.md`
- Add: `docs/review/2026-08-22-issue-313-step-functions-plan-review.md`
- Add: `docs/review/evidence/2026-08-22-issue-313-step-functions.md`

- [x] **Step 1: explicit plan approval과 pinned SHA를 확인한다**

사용자의 구현 계획 승인 메시지가 이 계획의 SHA-256 이후에 있는지 확인한다. 계획 검토 artifact에 기록된
design/plan SHA-256이 현재 파일과 일치하지 않으면 구현을 시작하지 않고 검토 gate를 다시 실행한다.

```bash
shasum -a 256 \
  docs/superpowers/specs/2026-08-22-issue-313-step-functions-design.md \
  docs/superpowers/plans/2026-08-22-issue-313-step-functions-plan.md
```

- [x] **Step 2: planning artifact 정적 검증을 실행한다**

```bash
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/superpowers/specs/2026-08-22-issue-313-step-functions-design.md \
  docs/review/2026-08-22-issue-313-step-functions-design-review.md \
  docs/superpowers/plans/2026-08-22-issue-313-step-functions-plan.md \
  docs/review/2026-08-22-issue-313-step-functions-plan-review.md
git diff --no-index --check /dev/null docs/superpowers/specs/2026-08-22-issue-313-step-functions-design.md || test "$?" = 1
git diff --no-index --check /dev/null docs/review/2026-08-22-issue-313-step-functions-design-review.md || test "$?" = 1
git diff --no-index --check /dev/null docs/superpowers/plans/2026-08-22-issue-313-step-functions-plan.md || test "$?" = 1
git diff --no-index --check /dev/null docs/review/2026-08-22-issue-313-step-functions-plan-review.md || test "$?" = 1
```

Expected: terminology findings 0, whitespace error output 0. `git diff --no-index`의 exit 1은 새 파일 diff를
뜻하므로 stderr/stdout에 whitespace finding이 없을 때만 허용한다.

- [x] **Step 3: 변경 전 baseline을 실행하고 evidence에 기록한다**

아직 production/build source를 수정하지 않은 pinned base에서 lifecycle regression, 양 module 전체 test,
detekt를 순차 실행한다. 각 command의 raw exit code와 final Gradle status를 즉시 기록하고, 전체 test XML의
tests/failures/errors/skipped aggregate를 evidence의 `Baseline` 절에 `apply_patch`로 남긴다. timeout, Docker,
dependency 또는 기존 제품 실패는 exact output과 함께 분리하며 PASS로 축약하지 않는다.

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests "io.bluetape4k.aws.kotlin.lifecycle.ClientLifecycleTest" --no-daemon --max-workers=1 --console=plain
./gradlew :bluetape4k-aws-java:test --no-daemon --max-workers=1 --console=plain
./gradlew :bluetape4k-aws-kotlin:test --no-daemon --max-workers=1 --console=plain
./gradlew detekt --no-daemon --max-workers=1 --console=plain
ruby -rrexml/document -e '
  ARGV.each do |dir|
    counts = Hash.new(0)
    Dir[File.join(dir, "TEST-*.xml")].each do |file|
      suite = REXML::Document.new(File.read(file)).root
      %w[tests failures errors skipped].each { |key| counts[key] += suite.attributes[key].to_i }
    end
    puts "#{dir}: #{counts.map { |key, value| "#{key}=#{value}" }.join(" ")}"
  end
' aws-java/build/test-results/test aws-kotlin/build/test-results/test
```

Expected: targeted lifecycle PASS. Full module tests와 detekt는 PASS가 목표다. 실패하면 baseline evidence를
고정한 뒤 원인이 Issue #313 범위인지 분류하고, final verification에서 동일 command/aggregate와 비교한다.

- [x] **Step 4: Task 0을 Lore commit으로 기록한다**

```text
Step Functions 구현 경계를 승인된 설계와 검증 계획에 고정한다

Constraint: Type A 구현은 설계와 계획의 독립 검토 및 사용자 승인 뒤에만 시작한다
Rejected: 검토 문서를 구현 commit에 혼합 | 승인된 기준선과 코드 변경의 추적성이 사라진다
Confidence: high
Scope-risk: narrow
Directive: pinned SHA가 달라지면 구현 전에 계획 검토 gate를 다시 실행한다
Tested: terminology, Markdown structure, whitespace, design and plan SHA, pinned-base lifecycle/full tests and detekt baseline
Not-tested: Step Functions production code
```

## Task 1: SDK catalog와 compileOnly consumer 경계

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify: `aws-java/build.gradle.kts`
- Modify: `aws-kotlin/build.gradle.kts`
- Modify: `aws-java/src/consumerFixture/kotlin/io/bluetape4k/aws/consumer/JavaServiceConsumerFixture.kt`
- Modify: `aws-kotlin/src/consumerFixture/kotlin/io/bluetape4k/aws/kotlin/consumer/KotlinServiceConsumerFixture.kt`

- [ ] **Step 1: raw SDK consumer fixture를 먼저 추가한다**

Java fixture에 `SfnClient::class.java`, Kotlin fixture에 Kotlin SDK `SfnClient::class.java`를 추가한다. 아직 classpath alias를 추가하지 않는다.

```kotlin
// JavaServiceConsumerFixture.kt
import software.amazon.awssdk.services.sfn.SfnClient

fun javaServiceConsumerFixture(): List<Any> = listOf(
    // 기존 항목 유지
    SfnClient::class.java,
)
```

```kotlin
// KotlinServiceConsumerFixture.kt
import aws.sdk.kotlin.services.sfn.SfnClient

fun kotlinServiceConsumerFixture(): List<Any> = listOf(
    // 기존 항목 유지
    SfnClient::class.java,
)
```

- [ ] **Step 2: SDK 누락으로 RED인지 확인한다**

Run:

```bash
./gradlew compileAwsJavaServiceConsumerFixture compileAwsKotlinServiceConsumerFixture --no-daemon --console=plain
```

Expected: 두 fixture 중 해당 SDK import가 `Unresolved reference 'sfn'`으로 실패한다.

- [ ] **Step 3: catalog, module dependency, root consumer/BOM 검증을 추가한다**

```toml
# gradle/libs.versions.toml
aws2-sfn = { module = "software.amazon.awssdk:sfn" }
aws-kotlin-sfn = { module = "aws.sdk.kotlin:sfn" }
```

```kotlin
// aws-java/build.gradle.kts
compileOnly(libs.aws2.sfn)
testImplementation(libs.aws2.sfn)
```

```kotlin
// aws-kotlin/build.gradle.kts
compileOnly(libs.aws.kotlin.sfn)
testImplementation(libs.aws.kotlin.sfn)
```

`build.gradle.kts`에는 다음 네 계약을 모두 추가한다.

```kotlin
addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:sfn", libs.aws2.sfn)
addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:sfn", libs.aws.kotlin.sfn)

// verifyAwsConsumerFixturePublication.forbiddenDependencies
"software.amazon.awssdk" to listOf(/* 기존 항목 */, "sfn")
"aws.sdk.kotlin" to listOf(/* 기존 항목 */, "sfn")

// central BOM constraint block
dependency("aws.sdk.kotlin:sfn:${bt4kVersion("aws-kotlin")}")
dependency("software.amazon.awssdk:sfn:${bt4kVersion("aws2")}")
```

- [ ] **Step 4: 정상 consumer와 negative omission을 검증한다**

Run:

```bash
./gradlew compileAwsJavaServiceConsumerFixture compileAwsKotlinServiceConsumerFixture verifyAwsConsumerFixturePublication --no-daemon --console=plain
./gradlew compileAwsJavaServiceConsumerFixture -PconsumerFixtureOmit=aws-java:sfn --no-daemon --console=plain
./gradlew compileAwsKotlinServiceConsumerFixture -PconsumerFixtureOmit=aws-kotlin:sfn --no-daemon --console=plain
```

Expected: 첫 명령은 PASS. 두 omission 명령은 각각 Sfn SDK type 미해결로 FAIL하며 compileOnly 소비자 계약을 증명한다.

- [ ] **Step 5: Task 1을 Lore commit으로 기록한다**

```text
Step Functions 소비자가 SDK 의존성을 명시하도록 경계를 고정한다

Constraint: 서비스 SDK는 publication에서 compileOnly 상태를 유지해야 한다
Rejected: sfn SDK의 api 전이 노출 | 모든 소비자에게 불필요한 서비스 SDK를 강제한다
Confidence: high
Scope-risk: narrow
Directive: Java와 Kotlin consumer omission 검증을 함께 유지한다
Tested: consumer fixture 정상 컴파일과 sfn omission 실패, publication metadata 검증
Not-tested: production Step Functions helper
```

## Task 2: Java SDK v2 request builder

**Files:**

- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/model/SfnRequestSupport.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/sfn/model/SfnRequestSupportTest.kt`

- [ ] **Step 1: local invariant의 실패 테스트를 작성한다**

테스트 이름과 입력을 다음 표 그대로 사용한다.

| 테스트 | 입력 | 기대 |
|---|---|---|
| null input | `input = null` | `"{}"` |
| blank input | `""`, `"   "` | `IllegalArgumentException` |
| callback null input | `builder = { input(null) }` | `"{}"` |
| callback invalid input | `builder = { input(" ") }` | `IllegalArgumentException` |
| required ARN | start/stop/describe의 빈 ARN | field-specific `IllegalArgumentException` |
| name boundary | 1자, 80자, 81자 | 앞 둘 PASS, 81자 FAIL |
| valid callback override | ARN/name/input을 다른 유효한 값으로 변경 | callback 값 보존 |
| raw input | 유효 JSON 문자열 | 변환 없이 원문 보존 |
| stop KMS boundary | `error = null`, `cause = null` | 두 field 미설정 |
| list exact-one | none, both | `IllegalArgumentException` |
| pending redrive | state machine + `PENDING_REDRIVE` | `IllegalArgumentException` |
| redrive filter | state machine + filter | `IllegalArgumentException` |
| page size | `-1`, `1001` | `IllegalArgumentException` |
| next token | blank | `IllegalArgumentException` |
| next page | source/filter + non-blank token | source/filter 보존, token만 전달 |

대표 테스트는 다음 형태로 작성한다.

```kotlin
@Test
fun `callback 이후 null input은 빈 JSON으로 정규화한다`() {
    val request = startExecutionRequestOf(STATE_MACHINE_ARN, input = "{\"id\":1}") {
        input(null)
    }
    request.input() shouldBeEqualTo "{}"
}

@Test
fun `state machine 목록에 pending redrive를 허용하지 않는다`() {
    invoking {
        listExecutionsRequestOf(
            stateMachineArn = STATE_MACHINE_ARN,
            statusFilter = ExecutionStatus.PENDING_REDRIVE,
        )
    } shouldBeInstanceOf<IllegalArgumentException>()
}
```

- [ ] **Step 2: request test RED를 확인한다**

```bash
./gradlew :bluetape4k-aws-java:test --tests "io.bluetape4k.aws.sfn.model.SfnRequestSupportTest" --no-daemon --console=plain
```

Expected: `SfnRequestSupport` 함수가 없어 compilation FAIL.

- [ ] **Step 3: Java request builder를 최소 구현한다**

`SfnRequestSupport.kt`에 다음 public API를 구현한다.

```kotlin
fun startExecutionRequestOf(
    stateMachineArn: String,
    name: String? = null,
    input: String? = null,
    traceHeader: String? = null,
    builder: StartExecutionRequest.Builder.() -> Unit = {},
): StartExecutionRequest

fun stopExecutionRequestOf(
    executionArn: String,
    error: String? = null,
    cause: String? = null,
    builder: StopExecutionRequest.Builder.() -> Unit = {},
): StopExecutionRequest

fun describeExecutionRequestOf(
    executionArn: String,
    builder: DescribeExecutionRequest.Builder.() -> Unit = {},
): DescribeExecutionRequest

fun listExecutionsRequestOf(
    stateMachineArn: String? = null,
    mapRunArn: String? = null,
    statusFilter: ExecutionStatus? = null,
    redriveFilter: ExecutionRedriveFilter? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsRequest
```

각 Java SDK builder에는 explicit 값을 적용하고 callback을 실행한 뒤 아래 validator를 호출한다.

```kotlin
private fun StartExecutionRequest.Builder.validateAndBuild(): StartExecutionRequest {
    val initial = build()
    initial.stateMachineArn().requireNotBlank("stateMachineArn")
    initial.name()?.let {
        require(it.isNotBlank() && it.length <= 80) { "name must contain 1..80 non-blank characters" }
    }
    val normalizedInput = initial.input() ?: "{}"
    require(normalizedInput.isNotBlank()) { "input must not be blank" }
    return if (initial.input() == null) initial.toBuilder().input(normalizedInput).build() else initial
}

private fun ListExecutionsRequest.validate(): ListExecutionsRequest = apply {
    require((stateMachineArn() != null) xor (mapRunArn() != null)) {
        "Exactly one of stateMachineArn or mapRunArn is required"
    }
    stateMachineArn()?.requireNotBlank("stateMachineArn")
    mapRunArn()?.requireNotBlank("mapRunArn")
    nextToken()?.requireNotBlank("nextToken")
    maxResults()?.let { require(it in 0..1000) { "maxResults must be in 0..1000" } }
    require(stateMachineArn() == null || statusFilter() != ExecutionStatus.PENDING_REDRIVE) {
        "PENDING_REDRIVE requires mapRunArn"
    }
    require(stateMachineArn() == null || redriveFilter() == null) {
        "redriveFilter requires mapRunArn"
    }
}
```

- [ ] **Step 4: Java request tests를 GREEN으로 만든다**

```bash
./gradlew :bluetape4k-aws-java:test --tests "io.bluetape4k.aws.sfn.model.SfnRequestSupportTest" --no-daemon --console=plain
```

Expected: PASS, 0 failed.

- [ ] **Step 5: Task 2를 Lore commit으로 기록한다**

```text
Step Functions 요청의 모호한 입력을 호출 전에 차단한다

Constraint: raw AWS model과 callback 확장성은 유지해야 한다
Rejected: AWS의 모든 문자 규칙 복제 | SDK와 서비스 진화에 따라 drift가 생긴다
Confidence: high
Scope-risk: narrow
Directive: callback 후 최종 request를 반드시 다시 검증한다
Tested: Java request builder boundary와 callback override 테스트
Not-tested: SDK client 호출
```

## Task 3: Java client lifecycle과 one-shot API

**Files:**

- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/SfnClientSupport.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/SfnAsyncClientSupport.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/SfnExtensions.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/sfn/SfnClientSupportTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/sfn/SfnAsyncClientSupportTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/sfn/SfnExtensionsTest.kt`

- [ ] **Step 1: lifecycle과 request 전달의 실패 테스트를 작성한다**

다음을 MockK로 고정한다.

```kotlin
@Test
fun `state machine 전용 helper는 callback source switch를 차단한다`() {
    val client = mockk<SfnClient>()
    val error = shouldThrow<IllegalArgumentException> {
        client.listExecutionsByStateMachine(STATE_MACHINE_ARN) {
            stateMachineArn(null)
            mapRunArn(MAP_RUN_ARN)
        }
    }
    error.message shouldBeEqualTo
        "listExecutionsByStateMachine must retain stateMachineArn=$STATE_MACHINE_ARN and must not set mapRunArn; " +
        "actual stateMachineArn=null, mapRunArn=$MAP_RUN_ARN"
    verify(exactly = 0) { client.listExecutions(any<ListExecutionsRequest>()) }
}

@Test
fun `map run 전용 helper는 callback source switch를 분석적으로 보고한다`() {
    val client = mockk<SfnClient>()
    val error = shouldThrow<IllegalArgumentException> {
        client.listExecutionsByMapRun(MAP_RUN_ARN) {
            mapRunArn(null)
            stateMachineArn(STATE_MACHINE_ARN)
        }
    }
    error.message shouldBeEqualTo
        "listExecutionsByMapRun must retain mapRunArn=$MAP_RUN_ARN and must not set stateMachineArn; " +
        "actual mapRunArn=null, stateMachineArn=$STATE_MACHINE_ARN, " +
        "statusFilter=null, redriveFilter=null"
    verify(exactly = 0) { client.listExecutions(any<ListExecutionsRequest>()) }
}

@Test
fun `async response는 await 계층까지 그대로 전달한다`() = runTest {
    val expected = StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build()
    val client = mockk<SfnAsyncClient> {
        every { startExecution(any<StartExecutionRequest>()) } returns CompletableFuture.completedFuture(expected)
    }
    client.startExecution(STATE_MACHINE_ARN) shouldBeEqualTo expected
}
```

Lifecycle test는 factory가 `ShutdownQueue.register`를 한 번 호출하고 `withSfn*Client`는 등록하지 않는지, block 실패/cancellation에도 service client만 닫고 외부 HTTP client는 닫지 않는지 확인한다.
명시적 endpoint/region/credentials/HTTP client를 builder의 다른 유효한 값이 덮어쓰는지도 sync/async 각각 검증한다.
Operation test는 Start/Stop/Describe/List의 exact request와 raw response/SDK exception을 그대로 전달하고,
`StopExecution(error=null, cause=null)`이 두 field를 설정하지 않으며, 다음 page token이 source/filter를
유지하는지 확인한다. Async coroutine cancellation은 `CancellationException`을 재전파하고 자동
`StopExecution`을 호출하지 않아야 한다.

- [ ] **Step 2: Java client test RED를 확인한다**

```bash
./gradlew :bluetape4k-aws-java:test --tests "io.bluetape4k.aws.sfn.Sfn*Test" --no-daemon --console=plain
```

Expected: lifecycle/extension symbol 미정의로 compilation FAIL.

- [ ] **Step 3: lifecycle helper를 구현한다**

두 support 파일은 explicit 설정 후 builder를 마지막에 실행한다. `sfnClient`/`sfnAsyncClient`와
각 `*Of` application factory만 `ShutdownQueue`에 등록하고, `withSfn*Client`는 같은 internal builder로
미등록 client를 만들어 범위 종료 시 service client만 닫는다.

```kotlin
inline fun sfnClient(
    builder: SfnClientBuilder.() -> Unit,
): SfnClient = SfnClient.builder().apply(builder).build()
    .apply { ShutdownQueue.register(this) }

inline fun sfnClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: SfnClientBuilder.() -> Unit = {},
): SfnClient = buildSfnClient(endpoint, region, credentialsProvider, httpClient, builder)
    .apply { ShutdownQueue.register(this) }

inline fun <R> withSfnClient(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: SfnClientBuilder.() -> Unit = {},
    block: (SfnClient) -> R,
): R = buildSfnClient(endpoint, region, credentialsProvider, httpClient, builder).use(block)
```

Async support에는 `sfnAsyncClient(builder)`와 `sfnAsyncClientOf(...)`를 같은 구조로 추가한다.
`SdkAsyncHttpClient`와 `SfnAsyncClientBuilder`를 사용하고 `withSfnAsyncClient`는
`try/finally { client.close() }`로 suspend block을 감싼다.

- [ ] **Step 4: sync/future/coroutine extension을 구현한다**

Start/Stop/Describe는 request builder로 위임한다. 목록은 target별 이름을 사용하고 callback 후 source를 pin한다.

```kotlin
fun SfnClient.listExecutionsByStateMachine(
    stateMachineArn: String,
    statusFilter: ExecutionStatus? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsResponse {
    val request = listExecutionsRequestOf(
        stateMachineArn = stateMachineArn,
        statusFilter = statusFilter,
        maxResults = maxResults,
        nextToken = nextToken,
        builder = builder,
    )
    require(request.stateMachineArn() == stateMachineArn && request.mapRunArn() == null) {
        "listExecutionsByStateMachine must retain stateMachineArn=$stateMachineArn and must not set mapRunArn; " +
            "actual stateMachineArn=${request.stateMachineArn()}, mapRunArn=${request.mapRunArn()}"
    }
    require(request.statusFilter() != ExecutionStatus.PENDING_REDRIVE && request.redriveFilter() == null) {
        "listExecutionsByStateMachine does not support PENDING_REDRIVE or redriveFilter; " +
            "actual statusFilter=${request.statusFilter()}, redriveFilter=${request.redriveFilter()}"
    }
    return listExecutions(request)
}
```

Map Run, async future, async coroutine도 동일 source validator를 공유한다. Coroutine은 future helper만 호출하고 `.await()`한다.
Map Run validator도 기대 helper/source와 실제 `stateMachineArn`, `mapRunArn`, `statusFilter`, `redriveFilter`를
포함한 field-specific message를 사용한다. Dedicated helper의 모든 invalid callback test는 예외 타입뿐 아니라
exact field name과 기대 source를 포함한 message를 assertion한다.

```kotlin
suspend fun SfnAsyncClient.startExecution(
    stateMachineArn: String,
    name: String? = null,
    input: String? = null,
    traceHeader: String? = null,
    builder: StartExecutionRequest.Builder.() -> Unit = {},
): StartExecutionResponse = startExecutionAsync(stateMachineArn, name, input, traceHeader, builder).await()
```

- [ ] **Step 5: Java lifecycle/operation tests를 GREEN으로 만든다**

```bash
./gradlew :bluetape4k-aws-java:test --tests "io.bluetape4k.aws.sfn.Sfn*Test" --no-daemon --console=plain
```

Expected: PASS. Sync/future/coroutine raw response가 동일하고 callback source-switch가 client 호출 전에 실패한다.

- [ ] **Step 6: Task 3을 Lore commit으로 기록한다**

```text
Step Functions 호출 계층의 수명과 source를 예측 가능하게 만든다

Constraint: application client와 short-lived client의 종료 경계가 달라야 한다
Rejected: SDK listExecutions 이름 재사용 | member 우선 해석이 local invariant를 우회한다
Confidence: high
Scope-risk: moderate
Directive: target별 list helper와 외부 HTTP client non-close 계약을 보존한다
Tested: Java lifecycle, sync, future, coroutine request/response와 source pinning
Not-tested: polling과 emulator
```

## Task 4: Java async cold Flow polling

**Files:**

- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/SfnExecutionFlow.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/sfn/SfnExecutionFlowTest.kt`

- [ ] **Step 1: polling state machine의 실패 테스트를 작성한다**

```kotlin
@Test
fun `running 뒤 terminal raw response를 방출하고 끝난다`() = runTest {
    val running = response(ExecutionStatus.RUNNING)
    val succeeded = response(ExecutionStatus.SUCCEEDED)
    val client = mockk<SfnAsyncClient> {
        every { describeExecution(any<DescribeExecutionRequest>()) } returnsMany listOf(
            CompletableFuture.completedFuture(running),
            CompletableFuture.completedFuture(succeeded),
        )
    }

    client.describeExecutionFlow(EXECUTION_ARN).toList() shouldBeEqualTo listOf(running, succeeded)
}
```

추가 RED cases: immediate first call, 1초 virtual delay, `take(1)` 추가 호출 0, slow collector backpressure,
cancellation 후 future 취소와 client non-close, `PENDING_REDRIVE` terminal, null status,
`UNKNOWN_TO_SDK_VERSION`의 `statusAsString()`, poll interval 999ms/1s/±infinite를 포함한다.
Java null/unknown status는 별도 mutable emission list로 collect하고 exception 뒤 `emissions.isEmpty()`,
`describeExecution` exact 1회, 후속 describe/stop 0회를 함께 assertion한다.
Request overload는 `includedData=METADATA_ONLY`와 `ALL_DATA` 각각에 대해 첫 호출과 반복 호출 모두
동일한 immutable `DescribeExecutionRequest`를 전달하는지 검증한다.
같은 cold Flow를 두 collector가 수집하면 collector별 SDK 호출이 발생하고, caller가 `shareIn`/`stateIn`으로
공유한 경우 upstream polling 호출이 하나로 합쳐지는지 virtual time과 exact invocation count로 검증한다.

- [ ] **Step 2: Flow test RED를 확인한다**

```bash
./gradlew :bluetape4k-aws-java:test --tests "io.bluetape4k.aws.sfn.SfnExecutionFlowTest" --no-daemon --console=plain
```

Expected: `SfnExecutionPollingOptions`와 `describeExecutionFlow` 미정의로 compilation FAIL.

- [ ] **Step 3: options와 cold Flow를 최소 구현한다**

```kotlin
data class SfnExecutionPollingOptions(
    val pollInterval: Duration = 1.seconds,
) {
    init {
        require(pollInterval.isFinite() && pollInterval >= 1.seconds) {
            "pollInterval must be finite and at least 1s"
        }
    }
}

fun SfnAsyncClient.describeExecutionFlow(
    request: DescribeExecutionRequest,
    options: SfnExecutionPollingOptions = SfnExecutionPollingOptions(),
): Flow<DescribeExecutionResponse> = flow {
    while (true) {
        currentCoroutineContext().ensureActive()
        val response = describeExecution(request).await()
        currentCoroutineContext().ensureActive()
        when (response.status()) {
            ExecutionStatus.RUNNING -> {
                emit(response)
                delay(options.pollInterval)
            }
            ExecutionStatus.SUCCEEDED,
            ExecutionStatus.FAILED,
            ExecutionStatus.TIMED_OUT,
            ExecutionStatus.ABORTED,
            ExecutionStatus.PENDING_REDRIVE -> {
                emit(response)
                return@flow
            }
            null,
            ExecutionStatus.UNKNOWN_TO_SDK_VERSION -> error(
                "Unsupported Step Functions execution status: ${response.statusAsString() ?: "<null>"}",
            )
        }
    }
}
```

ARN overload는 immutable `describeExecutionRequestOf(executionArn)`를 한 번 만들어 request overload로 위임한다. Flow는 timeout, retry, jitter, client close, `StopExecution`을 추가하지 않는다.

- [ ] **Step 4: Java Flow tests를 GREEN으로 만든다**

```bash
./gradlew :bluetape4k-aws-java:test --tests "io.bluetape4k.aws.sfn.SfnExecutionFlowTest" --no-daemon --console=plain
```

Expected: PASS. Virtual time로 delay를 검증하고 cancellation 이후 추가 describe/stop 호출이 없다.

- [ ] **Step 5: Task 4를 Lore commit으로 기록한다**

```text
Step Functions 완료 대기를 구조화된 취소 안에 둔다

Constraint: blocking client와 자동 stop side effect를 polling에 넣지 않는다
Rejected: 내부 retry와 timeout | 호출자의 quota와 deadline 정책을 숨긴다
Confidence: high
Scope-risk: moderate
Directive: unknown status는 raw 응답을 방출하지 말고 fail closed한다
Tested: Java cold Flow 상태, backpressure, cancellation, future 정리
Not-tested: LocalStack
```

## Task 5: AWS SDK for Kotlin request builder

**Files:**

- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/sfn/model/SfnRequestSupport.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sfn/model/SfnRequestSupportTest.kt`

- [ ] **Step 1: Java와 대칭인 Kotlin SDK 실패 테스트를 작성한다**

Task 2 표의 모든 case를 Kotlin SDK enum/type으로 반복한다. Unknown status는 request builder 범위가 아니므로 제외한다.

```kotlin
@Test
fun `callback 이후 null input은 빈 JSON으로 정규화한다`() {
    val request = startExecutionRequestOf(STATE_MACHINE_ARN, input = "{\"id\":1}") {
        input = null
    }
    request.input shouldBeEqualTo "{}"
}

@Test
fun `state machine과 map run을 함께 지정할 수 없다`() {
    invoking {
        listExecutionsRequestOf(stateMachineArn = STATE_MACHINE_ARN, mapRunArn = MAP_RUN_ARN)
    } shouldBeInstanceOf<IllegalArgumentException>()
}
```

- [ ] **Step 2: Kotlin request test RED를 확인한다**

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests "io.bluetape4k.aws.kotlin.sfn.model.SfnRequestSupportTest" --no-daemon --console=plain
```

Expected: request helper 미정의로 compilation FAIL.

- [ ] **Step 3: Kotlin SDK DSL builder를 구현한다**

Java와 같은 네 public signature를 Kotlin SDK model로 제공한다. `StartExecutionRequest {}` 내부에서 explicit field, callback, final validation, null-to-`{}` 정규화 순으로 처리한다.

```kotlin
inline fun startExecutionRequestOf(
    stateMachineArn: String,
    name: String? = null,
    input: String? = null,
    traceHeader: String? = null,
    crossinline builder: StartExecutionRequest.Builder.() -> Unit = {},
): StartExecutionRequest = StartExecutionRequest {
    this.stateMachineArn = stateMachineArn
    this.name = name
    this.input = input
    this.traceHeader = traceHeader
    builder()
    this.stateMachineArn?.requireNotBlank("stateMachineArn")
        ?: throw IllegalArgumentException("stateMachineArn is required")
    this.name?.let { require(it.isNotBlank() && it.length <= 80) }
    this.input = this.input?.also { require(it.isNotBlank()) } ?: "{}"
}
```

`listExecutionsRequestOf`는 `ExecutionStatus.PendingRedrive`와 Kotlin `ExecutionRedriveFilter`를 사용하고 callback 뒤 exact-one/source/filter/page invariant를 검사한다.

- [ ] **Step 4: Kotlin request tests를 GREEN으로 만든다**

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests "io.bluetape4k.aws.kotlin.sfn.model.SfnRequestSupportTest" --no-daemon --console=plain
```

Expected: PASS, Java request test와 같은 behavior matrix.

- [ ] **Step 5: Task 5를 Lore commit으로 기록한다**

```text
Kotlin SDK 요청도 Java facade와 같은 경계로 제한한다

Constraint: Kotlin SDK native model과 suspend API를 그대로 유지한다
Rejected: 공통 cross-SDK request model | SDK 고유 필드와 진화를 가린다
Confidence: high
Scope-risk: narrow
Directive: Java와 Kotlin validation matrix를 대칭으로 유지한다
Tested: Kotlin request builder boundary와 callback override 테스트
Not-tested: Kotlin client와 Flow
```

## Task 6: Kotlin client lifecycle, one-shot API, cold Flow

**Files:**

- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/sfn/SfnClientSupport.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/sfn/SfnExtensions.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/sfn/SfnExecutionFlow.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sfn/SfnClientSupportTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sfn/SfnExtensionsTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sfn/SfnExecutionFlowTest.kt`

- [ ] **Step 1: lifecycle, operation, Flow 실패 테스트를 작성한다**

Java와 같은 request/source/response/cancellation/lifecycle matrix에 Kotlin 전용
`ExecutionStatus.SdkUnknown("FUTURE")`를 추가한다. request overload의 `includedData` 보존,
`take(1)`, slow collector backpressure, client non-close, poll interval 999ms/1s/±infinite도 각각 검증한다.
Unknown test는 별도 mutable emission list에 `onEach(emissions::add)`로 기록하고 exception 뒤
`emissions.isEmpty()`, `describeExecution` exact 1회, 후속 describe/stop 0회를 assertion한다.
State-machine/Map Run source-switch 두 concrete test도 Java snippet과 같은 expected/actual field 값의 exact
message를 Kotlin property syntax로 assertion하고 native client 호출이 0회인지 `coVerify`한다.

```kotlin
@Test
fun `SdkUnknown은 raw response를 방출하지 않는다`() = runTest {
    val unknown = response(ExecutionStatus.SdkUnknown("FUTURE_STATUS"))
    val client = mockk<SfnClient> {
        coEvery { describeExecution(any<DescribeExecutionRequest>()) } returns unknown
    }
    val emissions = mutableListOf<DescribeExecutionResponse>()
    val error = shouldThrow<IllegalStateException> {
        client.describeExecutionFlow(EXECUTION_ARN)
            .onEach(emissions::add)
            .collect()
    }
    error.message shouldBeEqualTo "Unsupported Step Functions execution status: FUTURE_STATUS"
    emissions shouldBeEqualTo emptyList()
    coVerify(exactly = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }
    coVerify(exactly = 0) { client.stopExecution(any<StopExecutionRequest>()) }
}
```

`withSfnClient`는 success/failure/cancellation마다 service client를 닫고 caller의 `HttpClientEngine`은 닫지 않는지 확인한다.
명시적 endpoint/region/credentials/HTTP engine을 builder의 다른 유효한 값이 덮어쓰는지도 검증한다.
Java/Kotlin lifecycle test는 지원되는 사용법으로 Flow collection이 `withSfn*Client` block 안에서 terminal 또는
caller timeout까지 완료된 뒤 client가 닫히는 순서를 검증한다. Generic `R` lifecycle contract를 바꾸거나
terminal 전용 API를 추가하지 않으며, block 밖으로 반환된 cold Flow는 닫힌 client를 참조하므로 지원하지
않는다는 KDoc/README compile example을 고정한다.

- [ ] **Step 2: Kotlin client/Flow RED를 확인한다**

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests "io.bluetape4k.aws.kotlin.sfn.Sfn*Test" --no-daemon --console=plain
```

Expected: lifecycle/extension/Flow symbol 미정의로 compilation FAIL.

- [ ] **Step 3: native client lifecycle과 one-shot API를 구현한다**

```kotlin
inline fun sfnClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: SfnClient.Config.Builder.() -> Unit = {},
): SfnClient = SfnClient {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }
    builder()
}

suspend fun <R> withSfnClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    builder: SfnClient.Config.Builder.() -> Unit = {},
    block: suspend (SfnClient) -> R,
): R = sfnClientOf(endpointUrl, region, credentialsProvider, httpClient, builder).useSafe(block)
```

Start/Stop/Describe와 target별 List는 request builder를 native suspend member에 전달한다. Callback source pinning은 Java와 동일하게 client 호출 전에 확인한다.

- [ ] **Step 4: Kotlin cold Flow를 구현한다**

```kotlin
fun SfnClient.describeExecutionFlow(
    request: DescribeExecutionRequest,
    options: SfnExecutionPollingOptions = SfnExecutionPollingOptions(),
): Flow<DescribeExecutionResponse> = flow {
    while (true) {
        currentCoroutineContext().ensureActive()
        val response = describeExecution(request)
        currentCoroutineContext().ensureActive()
        when (val status = response.status) {
            ExecutionStatus.Running -> {
                emit(response)
                delay(options.pollInterval)
            }
            ExecutionStatus.Succeeded,
            ExecutionStatus.Failed,
            ExecutionStatus.TimedOut,
            ExecutionStatus.Aborted,
            ExecutionStatus.PendingRedrive -> {
                emit(response)
                return@flow
            }
            is ExecutionStatus.SdkUnknown -> error(
                "Unsupported Step Functions execution status: ${status.value}",
            )
        }
    }
}
```

- [ ] **Step 5: Kotlin lifecycle/operation/Flow tests를 GREEN으로 만든다**

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests "io.bluetape4k.aws.kotlin.sfn.Sfn*Test" --no-daemon --console=plain
```

Expected: PASS. Java와 같은 raw response/cancellation behavior이며 Kotlin에는 null status branch가 없다.

- [ ] **Step 6: Task 6을 Lore commit으로 기록한다**

```text
Kotlin Step Functions 호출을 native suspend 수명 안에 둔다

Constraint: AWS SDK for Kotlin client와 sealed status를 그대로 사용한다
Rejected: Java facade 공유 계층 | Kotlin native suspend와 engine ownership을 흐린다
Confidence: high
Scope-risk: moderate
Directive: SdkUnknown은 terminal 성공으로 간주하지 않는다
Tested: Kotlin lifecycle, one-shot API, cold Flow와 cancellation
Not-tested: emulator
```

## Task 7: 외부 consumer public API compile 검증

**Files:**

- Modify: `aws-java/src/consumerFixture/kotlin/io/bluetape4k/aws/consumer/JavaServiceConsumerFixture.kt`
- Modify: `aws-kotlin/src/consumerFixture/kotlin/io/bluetape4k/aws/kotlin/consumer/KotlinServiceConsumerFixture.kt`
- Modify: `build.gradle.kts`

- [ ] **Step 1: 실제 public helper 사용을 fixture에 추가한다**

Java fixture는 `sfnClient(builder)`, `sfnClientOf`, `withSfnClient`, `sfnAsyncClient(builder)`,
`sfnAsyncClientOf`, `withSfnAsyncClient`, custom sync/async HTTP client, 그리고
`listExecutionsByStateMachine(..., builder = {})`, `listExecutionsByMapRun(..., builder = {})`의
callable reference 또는 미실행 lambda를 포함한다. Kotlin fixture도 `sfnClientOf`, `withSfnClient`,
custom `HttpClientEngine`, 두 list helper를 포함한다.

```kotlin
// Java fixture의 미실행 lambda 예
{ sfnClientOf(region = Region.AP_NORTHEAST_2) },
suspend {
    withSfnAsyncClient(region = Region.AP_NORTHEAST_2) { client ->
        client.listExecutionsByStateMachine(STATE_MACHINE_ARN, builder = { maxResults(10) })
    }
},
```

```kotlin
// Kotlin fixture의 미실행 lambda 예
{ sfnClientOf(region = "ap-northeast-2") },
suspend {
    withSfnClient(region = "ap-northeast-2") { client ->
        client.listExecutionsByMapRun(MAP_RUN_ARN, builder = { maxResults = 10 })
    }
},
```

상수 ARN은 compile-only fixture 내부 `const val`로 두고 lambda는 실행하지 않는다.

- [ ] **Step 2: public API fixture와 omission을 검증한다**

```bash
./gradlew compileAwsJavaServiceConsumerFixture compileAwsKotlinServiceConsumerFixture verifyAwsConsumerFixturePublication --no-daemon --console=plain
./gradlew compileAwsJavaServiceConsumerFixture -PconsumerFixtureOmit=aws-java:sfn --no-daemon --console=plain
./gradlew compileAwsKotlinServiceConsumerFixture -PconsumerFixtureOmit=aws-kotlin:sfn --no-daemon --console=plain
```

Expected: 정상 fixture PASS. SDK omission은 각각 public SDK type 미해결로 FAIL.

- [ ] **Step 3: Task 7을 Lore commit으로 기록한다**

```text
외부 소비자가 Step Functions API를 정확히 해석하도록 보장한다

Constraint: SDK member와 extension overload가 함께 보이는 classpath를 검증해야 한다
Rejected: 모듈 내부 컴파일만 검증 | compileOnly 소비자 누락과 member 우선 문제를 놓친다
Confidence: high
Scope-risk: narrow
Directive: builder named argument와 target별 list helper fixture를 유지한다
Tested: Java/Kotlin public consumer compile, omission, publication metadata
Not-tested: emulator
```

## Task 8: Sfn 전용 emulator smoke와 증거 경계

**Files:**

- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/sfn/AbstractSfnTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/sfn/SfnSmokeTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sfn/AbstractSfnTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sfn/SfnSmokeTest.kt`
- Modify: `docs/review/evidence/2026-08-22-issue-313-step-functions.md`

- [ ] **Step 1: Floci exact skip의 실패 테스트를 작성한다**

두 `AbstractSfnTest`는 `bluetape4k.aws.emulator=floci`에서 다음 exact assumption message를 사용한다.

```text
live integration unverified: Floci does not support Step Functions
```

Java/Kotlin smoke test는 이 guard를 호출한 뒤에만 client를 만든다. 현재 guard가 없으므로 첫 targeted run은 unsupported call 또는 assertion mismatch로 RED여야 한다.

- [ ] **Step 2: Sfn 전용 fixture를 구현한다**

전역 `AbstractAwsTest.services`를 바꾸지 않는다. LocalStack 선택 시에만 전용 service를 시작한다.

```kotlin
abstract class AbstractSfnTest: AbstractAwsTest() {
    companion object {
        private fun configuredSfnEmulatorName(): String =
            System.getProperty("bluetape4k.aws.emulator", "floci").trim().lowercase()

        val sfnEmulator: AwsEmulatorServer by lazy {
            when (configuredSfnEmulatorName()) {
                "floci" -> FlociServer.Launcher.floci
                "localstack" -> LocalStackServer.Launcher.getLocalStack("stepfunctions")
                else -> error("Unsupported AWS emulator")
            }
        }

        fun assumeSfnSupported() {
            assumeFalse(
                configuredSfnEmulatorName() == "floci",
                "live integration unverified: Floci does not support Step Functions",
            )
        }
    }
}
```

Kotlin module도 같은 service selection과 exact message를 사용하고 SDK별 endpoint/credentials 변환만 다르게 둔다.

- [ ] **Step 3: LocalStack smoke를 구현한다**

각 SDK에서 다음 순서를 한 테스트 class 안에서 공유 client로 실행한다.

1. Pass state machine 생성
2. `StartExecution` 입력 `{"source":"issue-313"}`
3. `DescribeExecution`이 terminal이 될 때까지 bounded polling
4. `ListExecutionsByStateMachine`에 execution이 포함됨 확인
5. Wait state machine execution 생성 후 `StopExecution(error=null, cause=null)`
6. test-created state machine 삭제

각 SDK operation/wait/delete는 30초 `withTimeout`을 사용하고 Java smoke도 async client `.await()` 경로를
사용한다. Testcontainers startup을 포함한 test 전체에는 JUnit `@Timeout(120, unit = TimeUnit.SECONDS)`을
적용한다. 생성된 state machine ARN과 execution ARN을 모두 `try/finally`로 추적한다. `finally`는
`withContext(NonCancellable)` 안에서 resource별 별도 30초 timeout과 `runCatching`을 사용해 outstanding RUNNING
execution의 `StopExecution(error=null, cause=null)`을 먼저 시도하고, 이어 모든 test-created state machine을
삭제한다. cleanup 오류는 모아 원래 test 실패에 suppressed exception으로 붙인다. service client는 test scope에서
닫고, 외부 emulator는 공유 test lifecycle 소유이므로 개별 smoke test가 종료하지 않는다.

LocalStack 호출이 HTTP 501 또는 error code `NotImplemented`/`NotImplementedException`을 반환한 경우에만
`live integration unverified: LocalStack does not support Step Functions: <status/code>` exact prefix로 assumption
skip한다. 다른 `SfnException`, timeout, assertion, container 오류는 그대로 실패시켜 제품/인프라 문제를 숨기지 않는다.

- [ ] **Step 4: evidence 골격을 만들고 Floci skip과 LocalStack fallback을 순차 검증한다**

먼저 Step 5 표와 security boundary를 가진 evidence 파일을 `apply_patch`로 만든다. 아래 함수는 각 Gradle
process 직후 raw exit code를 잡고, fresh XML 여부·XML 사본·count·exact message·SHA-256을 backend별 receipt에
영속화한다. 한 case가 실패해도 나머지 독립 case의 bounded evidence 수집은 계속하지만, code/docs downstream
task로는 진행하지 않는다. evidence 수집 완전성과 live integration 결과는 서로 다른 status로 기록한다.

```bash
bash -euo pipefail <<'ISSUE313'
run_sfn_smoke_case() {
  local label=$1 module=$2 test_pattern=$3 backend=$4 xml=$5
  local evidence_dir=build/test-results/issue-313
  local snapshot="$evidence_dir/$label.xml"
  local receipt="$evidence_dir/$label.receipt.txt"
  local rc sha

  mkdir -p "$evidence_dir"
  rm -f "$xml" "$snapshot" "$receipt"
  set +e
  ./gradlew "${module}:test" --tests "$test_pattern" \
    "-Dbluetape4k.aws.emulator=$backend" --rerun-tasks \
    --no-daemon --max-workers=1 --console=plain
  rc=$?
  set -e

  if test ! -f "$xml"; then
    printf 'label=%s\nexit_code=%s\nxml=missing\n' "$label" "$rc" > "$receipt"
    cat "$receipt"
    return 1
  fi
  cp "$xml" "$snapshot"
  sha=$(shasum -a 256 "$snapshot" | awk '{print $1}')
  {
    printf 'label=%s\nexit_code=%s\nxml=%s\nsha256=%s\n' "$label" "$rc" "$snapshot" "$sha"
    rg -n 'tests="[0-9]+"|failures="[0-9]+"|errors="[0-9]+"|skipped="[0-9]+"|live integration unverified|<failure|<error' "$snapshot" || true
  } > "$receipt"
  cat "$receipt"
  set +e
  ruby -rrexml/document -e '
    suite = REXML::Document.new(File.read(ARGV.fetch(0))).root
    backend = ARGV.fetch(1)
    tests = suite.attributes["tests"].to_i
    failures = suite.attributes["failures"].to_i
    errors = suite.attributes["errors"].to_i
    skipped = suite.attributes["skipped"].to_i
    text = File.read(ARGV.fetch(0))
    raise "tests must be positive" unless tests.positive?
    raise "failures/errors must be zero" unless failures.zero? && errors.zero?
    if backend == "floci"
      raise "Floci must skip all smoke tests" unless skipped == tests
      raise "missing exact Floci reason" unless text.include?("live integration unverified: Floci does not support Step Functions")
    elsif skipped.positive?
      raise "LocalStack skip must cover all smoke tests" unless skipped == tests
      raise "missing exact LocalStack reason" unless text.include?("live integration unverified: LocalStack does not support Step Functions:")
    end
  ' "$snapshot" "$backend"
  xml_rc=$?
  set -e
  if test "$xml_rc" -ne 0; then rc=1; fi
  return "$rc"
}

overall=0
run_sfn_smoke_case floci-java :bluetape4k-aws-java '*SfnSmokeTest' floci \
  aws-java/build/test-results/test/TEST-io.bluetape4k.aws.sfn.SfnSmokeTest.xml || overall=1
run_sfn_smoke_case floci-kotlin :bluetape4k-aws-kotlin '*SfnSmokeTest' floci \
  aws-kotlin/build/test-results/test/TEST-io.bluetape4k.aws.kotlin.sfn.SfnSmokeTest.xml || overall=1

run_sfn_smoke_case localstack-java :bluetape4k-aws-java '*SfnSmokeTest' localstack \
  aws-java/build/test-results/test/TEST-io.bluetape4k.aws.sfn.SfnSmokeTest.xml || overall=1
run_sfn_smoke_case localstack-kotlin :bluetape4k-aws-kotlin '*SfnSmokeTest' localstack \
  aws-kotlin/build/test-results/test/TEST-io.bluetape4k.aws.kotlin.sfn.SfnSmokeTest.xml || overall=1
for receipt in build/test-results/issue-313/{floci-java,floci-kotlin,localstack-java,localstack-kotlin}.receipt.txt; do
  test -s "$receipt" || overall=1
done
if test "$overall" -eq 0; then
  printf 'PASS\n' > build/test-results/issue-313/evidence-collection.status
else
  printf 'FAIL\n' > build/test-results/issue-313/evidence-collection.status
fi
if test "$overall" -ne 0; then
  printf 'FAIL\n' > build/test-results/issue-313/live-integration.status
elif rg -q 'skipped="0"' build/test-results/issue-313/localstack-java.xml \
  && rg -q 'skipped="0"' build/test-results/issue-313/localstack-kotlin.xml; then
  printf 'PASS\n' > build/test-results/issue-313/live-integration.status
else
  printf 'UNVERIFIED\n' > build/test-results/issue-313/live-integration.status
fi
cat build/test-results/issue-313/evidence-collection.status
cat build/test-results/issue-313/live-integration.status
ISSUE313
```

Expected: Floci 실행 직후 각 XML에는 skipped count와 exact `live integration unverified` 문구가 있다.
LocalStack 실행 뒤 같은 XML 경로를 다시 읽어 tests/failures/errors/skipped count를 기록한다. 두 smoke가 PASS하지
않으면 unsupported exact error와 XML을 evidence에 기록하고 `live integration unverified`로 남긴다.
Skip/unsupported를 live PASS로 쓰지 않는다.
각 Gradle process의 exit code와 XML 사본 SHA-256·경로는 다음 case 실행 전에 backend별 receipt에 기록된다.
Step 5에서 네 receipt의 값을 evidence Markdown에 `apply_patch`로 옮기고 read-back한다.
`build/test-results/issue-313/` XML 사본과 receipt는 로컬 증거이며 commit하지 않는다.

- [ ] **Step 5: evidence artifact를 최종화한다**

```markdown
# Issue #313 Step Functions 검증 증거

## Emulator

| SDK | Backend | 결과 | XML/근거 |
|---|---|---|---|
| Java v2 | Floci | UNVERIFIED | exact skip message와 XML path |
| Kotlin | Floci | UNVERIFIED | exact skip message와 XML path |
| Java v2 | LocalStack | PASS 또는 UNVERIFIED | 실제 command/result |
| Kotlin | LocalStack | PASS 또는 UNVERIFIED | 실제 command/result |

## Security boundary

- 실제 AWS IAM/KMS: UNVERIFIED
- emulator 성공은 IAM resource policy 또는 KMS key policy 증거가 아님
```

실제 작성에서는 실행한 command, exit code, XML test/skipped/failure count와 exact error를 기록한다.
네 receipt와 evidence Markdown을 read-back한 뒤
`test "$(cat build/test-results/issue-313/evidence-collection.status)" = "PASS"`를 실행한다.
`live-integration.status`는 LocalStack 두 SDK가 모두 실행되면 `PASS`, exact unsupported skip이면 `UNVERIFIED`,
그 밖의 실패면 `FAIL`이어야 한다. evidence collection 또는 live integration이 FAIL이면 증거는 보존하되
Task 8은 완료 처리하지 않고 제품·인프라를 분리한다. `UNVERIFIED`는 기능 미검증 상태로 명시하며 PASS로 쓰지 않는다.

- [ ] **Step 6: Task 8을 Lore commit으로 기록한다**

```text
Step Functions emulator 한계를 검증 결과에 드러낸다

Constraint: Floci-first 정책과 LocalStack fallback을 순차 적용해야 한다
Rejected: 전역 LocalStack service 목록 변경 | 무관한 테스트의 startup과 실패면을 넓힌다
Confidence: medium
Scope-risk: moderate
Directive: emulator 결과를 IAM/KMS 실환경 증거로 승격하지 않는다
Tested: Floci exact skip, Sfn 전용 LocalStack smoke 또는 exact unsupported evidence
Not-tested: 실제 AWS IAM/KMS
```

## Task 9: README, manual, CHANGELOG와 전체 검증

**Files:**

- Modify: `README.md`, `README.ko.md`
- Modify: `aws-java/README.md`, `aws-java/README.ko.md`
- Modify: `aws-kotlin/README.md`, `aws-kotlin/README.ko.md`
- Modify: `docs/manual/en/modules/bluetape4k-aws-java.md`, `docs/manual/ko/modules/bluetape4k-aws-java.md`
- Modify: `docs/manual/en/modules/bluetape4k-aws-kotlin.md`, `docs/manual/ko/modules/bluetape4k-aws-kotlin.md`
- Modify: `docs/manual/en/guides/testing-and-operations.md`, `docs/manual/ko/guides/testing-and-operations.md`
- Modify: `CHANGELOG.md`
- Inspect only: `WIP.md`

- [ ] **Step 1: README와 KDoc compile 예제를 추가한다**

문서에는 다음 Gradle dependency와 bounded lifecycle 예제를 정확히 넣는다.

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:sfn")
}
```

```kotlin
runBlocking {
    withSfnAsyncClient(region = Region.AP_NORTHEAST_2) { client ->
        withTimeout(30.seconds) {
            client.describeExecutionFlow(executionArn).last()
        }
    }
}
```

Kotlin 문서는 `aws.sdk.kotlin:sfn`, `withSfnClient`, native suspend Flow 예제를 사용한다. README는 요약과 manual link만 두고 상세 quota/IAM/KMS는 manual에 둔다.

- [ ] **Step 2: EN/KO manual을 구조적으로 맞춘다**

두 locale에 같은 anchor 순서를 사용한다.

```markdown
## Step Functions execution helpers {#step-functions}
### Dependency boundary {#step-functions-dependency}
### Standard, Express, and Map Run {#step-functions-capabilities}
### Polling and cancellation {#step-functions-polling}
### IAM and KMS {#step-functions-iam-kms}
### Quotas and observability {#step-functions-operations}
### Emulator evidence {#step-functions-emulator}
```

Manual front matter의 `releaseRef: "0.5.0"`와 기존 release source link는 바꾸지 않는다. 새 절은 unreleased/develop API임을 명시하고 새 파일을 0.5.0 release source라고 링크하지 않는다. 다음 release 작업에서 releaseRef가 갱신될 때 source link를 추가한다.
Capability 표에는 Standard/Express/Map Run 차이와 Express에서 Describe/Stop/List를 일반 실행처럼 사용할 수
없는 경계를 명시한다. 운영 절에는 polling 최소 간격, caller-owned timeout/rate-limit/jitter, AWS service quota
확인, CloudWatch execution/throttling 지표와 AWS request ID 기반 관측을 기록한다. 1초는 collector별 하한일
뿐 account/Region aggregate quota를 보장하지 않으므로 collector 수·동시성·polling budget을 caller가 제한하고,
동일 execution의 다중 구독은 `shareIn`/`stateIn`으로 합친다. 전체 request/response를 운영 로그에 기록하지 않는다.

보안 절은 다음 acceptance criteria를 모두 포함한다.

- IAM 표를 `StartExecution`(`states:StartExecution`과 dependent `states:DescribeExecution`),
  `DescribeExecution`, `StopExecution`, `ListExecutions`로 나누고 state machine alias/version,
  Standard execution, Express execution, labelled Map Run child, Map Run resource를 statement별로 구분한다.
- `StopExecution(error=null,cause=null)`이 execution role의 추가 KMS data-key 권한을 피하는 조건과,
  암호화된 execution data를 요청하는 `includedData=ALL_DATA`의 caller KMS 권한 경계를 설명한다.
- ARN, execution name, input, output, error, cause, traceHeader, raw response payload는 기본적으로 기록하지 않거나
  redaction한다. 상관관계가 필요하면 비밀키가 관리되는 HMAC만 사용하고, plain hash는 사용하지 않는다.
- custom endpoint/endpointUrl은 caller가 신뢰하는 emulator 또는 private endpoint에만 사용한다. production
  credential이나 payload를 untrusted 또는 non-TLS endpoint로 보내지 않으며, custom credentials provider와
  외부 HTTP client/engine도 caller-owned trust boundary임을 KDoc와 manual에 명시한다.
- emulator 기능 smoke는 IAM resource policy, KMS key policy, production credential 경계를 증명하지 않는다.

- [ ] **Step 3: CHANGELOG 미출시 항목을 추가한다**

```markdown
## [미출시]

### 추가

- Java SDK v2와 AWS SDK for Kotlin에 Step Functions 실행 시작·중지·조회·목록 및
  coroutine `Flow` polling helper를 추가했습니다. compileOnly SDK, caller-owned
  client 수명, 명시적 cancellation, Floci/LocalStack 검증 경계를 포함합니다
  ([#313](https://github.com/bluetape4k/bluetape4k-aws/issues/313)).
```

`WIP.md`의 #313 Backlog 행은 issue/PR이 아직 병합되지 않았으므로 이 작업 범위에서는 유지한다.
PR 병합 뒤 별도 canonical sync에서 제거 또는 완료 이력 반영 여부를 판단한다.

- [ ] **Step 4: 문서 계약을 검증한다**

```bash
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs README.ko.md aws-java/README.ko.md aws-kotlin/README.ko.md docs/manual/ko/modules/bluetape4k-aws-java.md docs/manual/ko/modules/bluetape4k-aws-kotlin.md docs/manual/ko/guides/testing-and-operations.md CHANGELOG.md
ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
ruby scripts/manual/manual_contract_test.rb
test "$(rg -l '^releaseRef: "0\.5\.0"$' docs/manual/{en,ko}/modules/bluetape4k-aws-{java,kotlin}.md | wc -l | tr -d ' ')" = "4"
test "$(rg -o '664e4dfb544a3c19db484b0f9a8e023a73774b49' docs/manual/{en,ko}/modules/bluetape4k-aws-{java,kotlin}.md | wc -l | tr -d ' ')" = "36"
! git diff --unified=0 -- docs/manual/{en,ko}/modules/bluetape4k-aws-{java,kotlin}.md | rg '^[+-].*(releaseRef:|664e4dfb544a3c19db484b0f9a8e023a73774b49)'
git diff --check
```

Expected: terminology finding 0, manifest check PASS, manual contract PASS, whitespace 오류 0.

- [ ] **Step 5: targeted test와 정적 분석을 실행한다**

```bash
./gradlew :bluetape4k-aws-java:test --tests "io.bluetape4k.aws.sfn.*" --no-daemon --max-workers=1 --console=plain
./gradlew :bluetape4k-aws-kotlin:test --tests "io.bluetape4k.aws.kotlin.sfn.*" --no-daemon --max-workers=1 --console=plain
./gradlew compileAwsJavaServiceConsumerFixture compileAwsKotlinServiceConsumerFixture verifyAwsConsumerFixturePublication --no-daemon --console=plain
./gradlew detekt --no-daemon --console=plain
```

Expected: all PASS. Raw process exit code와 전체 output의 final status를 evidence에 기록한다.

- [ ] **Step 6: 양 모듈 전체 테스트를 순차 실행한다**

```bash
./gradlew :bluetape4k-aws-java:test --no-daemon --max-workers=1 --console=plain
./gradlew :bluetape4k-aws-kotlin:test --no-daemon --max-workers=1 --console=plain
```

Expected: 두 task 모두 PASS. timeout/infra failure가 나면 제품 실패와 분리하고 partial PASS로 축약하지 않는다.
Task 0의 동일 command·XML aggregate와 비교해 새 failure/error/skipped 증가를 회귀로 분류한다. baseline에 있던
실패도 exact 상태를 다시 기록하며, 새 targeted/public consumer/detekt/manual failure는 completion을 차단한다.

- [ ] **Step 7: 최종 diff와 worktree 상태를 검증한다**

```bash
git diff --stat
git diff --check
git status --short
bash -euo pipefail -c '
while IFS= read -r entry; do
  path=${entry:3}
  case "$path" in
    gradle/libs.versions.toml|build.gradle.kts|aws-java/build.gradle.kts|aws-kotlin/build.gradle.kts|CHANGELOG.md|README.md|README.ko.md|aws-java/README.md|aws-java/README.ko.md|aws-kotlin/README.md|aws-kotlin/README.ko.md|docs/manual/en/modules/bluetape4k-aws-java.md|docs/manual/ko/modules/bluetape4k-aws-java.md|docs/manual/en/modules/bluetape4k-aws-kotlin.md|docs/manual/ko/modules/bluetape4k-aws-kotlin.md|docs/manual/en/guides/testing-and-operations.md|docs/manual/ko/guides/testing-and-operations.md|docs/superpowers/specs/2026-08-22-issue-313-step-functions-design.md|docs/superpowers/plans/2026-08-22-issue-313-step-functions-plan.md|docs/review/2026-08-22-issue-313-step-functions-design-review.md|docs/review/2026-08-22-issue-313-step-functions-plan-review.md|docs/review/evidence/2026-08-22-issue-313-step-functions.md|aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/*|aws-java/src/test/kotlin/io/bluetape4k/aws/sfn/*|aws-java/src/consumerFixture/kotlin/io/bluetape4k/aws/consumer/JavaServiceConsumerFixture.kt|aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/sfn/*|aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sfn/*|aws-kotlin/src/consumerFixture/kotlin/io/bluetape4k/aws/kotlin/consumer/KotlinServiceConsumerFixture.kt) ;;
    *) echo "unexpected Issue #313 path: $path" >&2; exit 1 ;;
  esac
done < <(git status --porcelain=v1)
while IFS= read -r file; do
  output=$(git diff --no-index --check /dev/null "$file" 2>&1) || code=$?
  code=${code:-0}
  test "$code" -le 1
  test -z "$output" || { printf "%s\n" "$output" >&2; exit 1; }
done < <(git ls-files --others --exclude-standard)
'
```

Expected: Issue #313 범위 파일만 변경, whitespace 오류 0, 예상하지 않은 generated/credential 파일 0.

- [ ] **Step 8: Task 9를 Lore commit으로 기록한다**

```text
Step Functions helper를 소비자와 운영자가 안전하게 채택하도록 설명한다

Constraint: stable manual의 0.5.0 releaseRef와 source link는 보존해야 한다
Rejected: README에 전체 운영 매뉴얼 복제 | 문서 소유권과 EN/KO parity가 깨진다
Confidence: high
Scope-risk: moderate
Directive: 실제 AWS IAM/KMS는 별도 증거 전까지 UNVERIFIED로 유지한다
Tested: targeted/full tests, consumer/publication, detekt, manual, terminology, diff check
Not-tested: 실제 AWS IAM/KMS
```

## 실패 격리와 rollback 절차

- 각 Task는 자신의 RED→GREEN과 Lore commit을 완료하기 전 다음 Task로 진행하지 않는다. 실패한 command의
  raw exit/XML을 evidence에 기록하고 제품, baseline, Docker/Testcontainers, external service 경계를 분리한다.
- emulator test가 중단되면 Task 8의 `NonCancellable` cleanup으로 outstanding execution을 중지하고 test-created
  state machine을 삭제한다. cleanup 실패도 숨기지 않고 suppressed exception과 evidence에 남긴다.
- 구현을 계속할 수 있으면 같은 Task 안에서 최소 수정 후 동일 command를 재실행한다. baseline과 동일한 외부
  실패는 별도 gap으로 유지하되 새 targeted test, compile fixture, static analysis 실패를 baseline으로 축약하지 않는다.
- Issue #313 변경을 폐기하는 rollback은 자동 실행하지 않는다. 사용자가 rollback을 명시하면 현재 branch와
  exact Issue #313 commit SHA를 read-back하고, 다른 변경이 없는지 확인한 뒤 reverse order로
  `git revert --no-commit <exact-sha>`를 적용한다. 결과는 별도 한국어 Lore commit과 targeted/full 검증으로 기록한다.
  `git reset --hard`, branch 삭제, worktree 강제 삭제는 사용하지 않는다.
- 이미 배포된 consumer의 운영 rollback은 새 API 호출을 제거하고 도입 전 사용하던 artifact version을 다시 pin하는
  방식이다. 이 저장소의 publish/tag/release는 현재 범위 밖이며 별도 승인 없이 실행하지 않는다.

## 계획 자체 검토 체크리스트

- [x] 설계의 start/stop/describe/list, request builder, Flow, cancellation, lifecycle, compileOnly를 각 task에 매핑했다.
- [x] Java nullable/unknown status와 Kotlin `SdkUnknown(value)`의 차이를 테스트에 매핑했다.
- [x] `PENDING_REDRIVE`, `ExecutionRedriveFilter`, state-machine/Map Run source pinning을 양 SDK에 매핑했다.
- [x] consumer compile/runtime dependency와 publication non-transitivity를 검증한다.
- [x] Floci skip, LocalStack 전용 fixture, 실제 AWS IAM/KMS `UNVERIFIED` 경계를 보존한다.
- [x] README/manual EN·KO와 stable releaseRef 제약을 검증한다.
- [x] 미완성 표식 없이 command, expected result, commit decision record를 제공했다.

## 구현 완료의 stop condition

다음이 모두 충족될 때만 구현 완료로 판정한다.

1. Task 1~9 checkbox가 모두 완료되고 각 RED→GREEN evidence가 있다.
2. Java/Kotlin targeted tests, full module tests, consumer/publication, detekt, manual, terminology, diff-check가 fresh PASS다.
3. Floci와 LocalStack 결과가 XML·exact output으로 기록되고 unsupported를 PASS로 표시하지 않는다.
4. 실제 AWS IAM/KMS 미검증은 `UNVERIFIED`로 명시된다.
5. production/test/docs diff가 Issue #313 범위와 일치한다.
6. 계획 후속 code review에서 P0/P1이 0이다.
