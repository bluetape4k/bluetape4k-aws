# Issue #314 AWS Lambda 호출 helper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `bluetape4k-aws-java`와 `bluetape4k-aws-kotlin`에 AWS Lambda 호출·수명주기·typed payload 계약을 추가하고 compileOnly consumer와 deterministic 검증으로 Issue #314를 완료한다.

**Architecture:** 두 모듈은 SDK 타입을 공유하지 않고 `LambdaPayloadCodec<T>`, `LambdaInvocationResult<T>`, request builder, client lifecycle, invocation extension을 대칭적으로 제공한다. Java SDK v2는 sync·`CompletableFuture`·coroutine 경로를, AWS Kotlin SDK는 native suspend 경로를 제공하며 raw SDK request/response와 예외는 보존한다. Spring Boot/Ktor facade, function deployment, retry/polling은 구현하지 않는다.

**Tech Stack:** Kotlin/JVM, AWS SDK for Java v2 Lambda, AWS SDK for Kotlin Lambda, Kotlin Coroutines, Jackson 3 `ObjectMapper` compileOnly adapter, JUnit 5, MockK, Testcontainers/Floci-first boundary, Gradle version catalog.

---

## 파일·책임 지도

- Java source: `aws-java/src/main/kotlin/io/bluetape4k/aws/lambda/` 아래 `LambdaPayloadCodec.kt`, `LambdaInvocationResult.kt`, `LambdaClientSupport.kt`, `LambdaAsyncClientSupport.kt`, `LambdaClientExtensions.kt`, `LambdaAsyncClientExtensions.kt`, `LambdaAsyncClientCoroutinesExtensions.kt`, `model/LambdaRequestSupport.kt`를 생성한다.
- Kotlin source: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/lambda/` 아래 `LambdaPayloadCodec.kt`, `LambdaInvocationResult.kt`, `LambdaClientSupport.kt`, `LambdaExtensions.kt`, `model/LambdaRequestSupport.kt`를 생성한다.
- Java tests: `aws-java/src/test/kotlin/io/bluetape4k/aws/lambda/` 아래 codec/client/async/extensions/smoke 테스트와 `model/LambdaRequestSupportTest.kt`를 생성한다.
- Kotlin tests: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/lambda/` 아래 codec/client/extensions/smoke 테스트와 `model/LambdaRequestSupportTest.kt`를 생성한다.
- Build/fixture: `gradle/libs.versions.toml`, 두 module `build.gradle.kts`, root `build.gradle.kts`, 두 consumer fixture를 수정한다.
- Docs/evidence: root/module README 6개, Java/Kotlin manual EN·KO 4개, `CHANGELOG.md`, readiness/plan evidence를 수정 또는 생성한다.

---

### Task 1: emulator와 실행 환경을 먼저 고정한다

**Files:** read-only `aws-java/src/test/kotlin/io/bluetape4k/aws/AbstractAwsTest.kt`, `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/AbstractAwsTest.kt`, migration spec; create `docs/review/evidence/2026-08-23-issue-314-lambda-readiness.md`.

- [x] **Step 1: Docker/Colima 상태를 확인한다**

```bash
colima status
docker context show
docker info --format 'Server={{.ServerVersion}} Containers={{.Containers}} Images={{.Images}}'
docker images --format '{{.Repository}}:{{.Tag}}' | rg -i 'floci|localstack|testcontainers' || true
```

Expected: healthy Colima, `default` context, Docker server evidence를 기록한다. 현재 확인값은 Colima running, Docker `29.2.1`, Floci `1.6.0` image다.

- [x] **Step 2: Lambda capability와 fixture 부재를 확인한다**

```bash
rg -n "Lambda|lambda|FlociServer|LocalStackServer" aws-java/src/test aws-kotlin/src/test ../../../bluetape4k-projects/docs/superpowers/specs/2026-04-26-aws-emulator-migration-design.md
```

Expected: 공용 test base에 Lambda function 생성·invoke fixture가 없음을 기록한다. function 생성·배포·IAM mutation은 하지 않는다.

- [x] **Step 3: evidence를 검증한다**

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs docs/review/evidence/2026-08-23-issue-314-lambda-readiness.md
```

Expected: whitespace와 Korean terminology findings가 0이다.

### Task 2: catalog·compileOnly와 consumer fixture를 고정한다

**Files:** `gradle/libs.versions.toml`, 두 module `build.gradle.kts`, root `build.gradle.kts`; consumer fixture source는 Task 9에서 수정한다.

- [x] **Step 1: aliases를 추가한다**

```toml
aws2-lambda = { module = "software.amazon.awssdk:lambda" }
aws-kotlin-lambda = { module = "aws.sdk.kotlin:lambda" }
```

버전 숫자는 입력하지 않고 기존 service alias 정렬을 유지한다.

- [x] **Step 2: dependency 경계를 추가한다**

Java module에 `compileOnly(libs.aws2.lambda)`와 `testImplementation(libs.aws2.lambda)`, Kotlin module에 `compileOnly(libs.aws.kotlin.lambda)`와 `testImplementation(libs.aws.kotlin.lambda)`를 추가한다. Jackson/coroutine 기존 정책은 변경하지 않는다.

- [x] **Step 3: root fixture classpath와 source를 추가한다**

```kotlin
addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:lambda", libs.aws2.lambda)
addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:lambda", libs.aws.kotlin.lambda)
```

이 단계에서는 classpath registration만 추가하고 consumer fixture source는 아직 건드리지 않는다. 실제 fixture API surface는 구현 type이 존재하는 Task 9에서 추가한다.

- [x] **Step 4: 기존 source와 fixture를 컴파일한다**

```bash
./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-kotlin:compileKotlin --no-daemon
```

Expected: 기존 code가 compile되고 Lambda service가 module runtime 전이 없이 test/consumer classpath에만 명시된다.

### Task 3: Java codec·result를 TDD로 추가한다

**Files:** `aws-java/src/test/kotlin/io/bluetape4k/aws/lambda/LambdaPayloadCodecTest.kt`; `LambdaPayloadCodec.kt`; `LambdaInvocationResult.kt`.

- [x] **Step 1: 실패 테스트를 작성한다**

```kotlin
@Test fun `bytes codec copies input and decoded output`()
@Test fun `utf8 codec preserves unicode and empty string`()
@Test fun `jackson codec uses caller mapper and class`()
@Test fun `malformed json propagates and no unsafe typing is enabled`()
@Test fun `result copies payload and decodes function error and log tail`()
@Test fun `null payload is distinct from empty payload`()
@Test fun `large payload copy remains bounded to the codec boundary`()
```

`InvokeResponse.builder()`에 `SdkBytes`, `functionError("Handled")`, base64 `logResult`를 넣고 raw response·copied payload·decoded value·`hasFunctionError`·UTF-8 tail을 assertion한다. 대형 payload test는 4 MiB 배열에서 codec 경계 외의 반복 copy가 없는지 content equality와 단일 변환 횟수로 확인한다.

- [x] **Step 2: 실패를 확인한다**

```bash
./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.lambda.LambdaPayloadCodecTest' --no-daemon
```

Expected: 미정의 type/function으로 FAIL.

- [x] **Step 3: 최소 구현을 작성한다**

```kotlin
interface LambdaPayloadCodec<T> {
    fun encode(value: T): ByteArray
    fun decode(payload: ByteArray): T
}

object LambdaPayloadCodecs {
    val bytes: LambdaPayloadCodec<ByteArray>
    val utf8: LambdaPayloadCodec<String>
    fun <T> jackson(mapper: tools.jackson.databind.ObjectMapper, valueType: Class<T>): LambdaPayloadCodec<T>
}
```

`bytes` encode/decode는 `copyOf()`, `utf8`은 UTF-8, Jackson은 `writeValueAsBytes`/`readValue(payload, valueType)`만 사용한다. unsafe default typing이나 global mapper를 설치하지 않는다. result는 `SdkBytes.asByteArray().copyOf()`를 저장하고 base64 decode 실패를 wrapping하지 않는다.

- [x] **Step 4: targeted test를 통과시킨다**

```bash
./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.lambda.LambdaPayloadCodecTest' --no-daemon
```

### Task 4: Java request builder를 TDD로 추가한다

**Files:** `aws-java/src/test/kotlin/io/bluetape4k/aws/lambda/model/LambdaRequestSupportTest.kt`; `aws-java/src/main/kotlin/io/bluetape4k/aws/lambda/model/LambdaRequestSupport.kt`.

- [x] **Step 1: 실패 테스트를 작성한다**

```kotlin
@Test fun `request maps function ARN qualifier invocation log and payload`()
@Test fun `null payload is omitted and empty payload is retained`()
@Test fun `callback payload is final and invariant is rechecked`()
@Test fun `blank function or qualifier fails before SDK call`()
@Test fun `tail log is rejected for event and dry run`()
```

- [x] **Step 2: 실패를 확인한다**

```bash
./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.lambda.model.LambdaRequestSupportTest' --no-daemon
```

- [x] **Step 3: builder를 구현한다**

```kotlin
inline fun invokeRequestOf(
    functionName: String,
    payload: ByteArray? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    logType: LogType = LogType.NONE,
    builder: InvokeRequest.Builder.() -> Unit = {},
): InvokeRequest = InvokeRequest.builder()
    .functionName(functionName)
    .also { payload?.let { value -> it.payload(SdkBytes.fromByteArray(value)) } }
    .also { qualifier?.let(it::qualifier) }
    .invocationType(invocationType).logType(logType).apply(builder).build()
    .also(::validateInvokeRequest)
```

`validateInvokeRequest`는 blank function/qualifier와 `Tail + non-RequestResponse`만 검사하고 ARN 형식·payload 크기·IAM·function existence는 검사하지 않는다.

- [x] **Step 4: targeted request test를 통과시킨다**

```bash
./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.lambda.model.LambdaRequestSupportTest' --no-daemon
```

### Task 5: Java client lifecycle을 TDD로 추가한다

**Files:** Java lifecycle tests 2개와 `LambdaClientSupport.kt`, `LambdaAsyncClientSupport.kt`.

- [x] **Step 1: 실패 테스트를 작성한다**

기존 `SfnClientSupportTest` 패턴으로 explicit endpoint/region/credentials/http client와 callback override, `ShutdownQueue` registration, `with...` success/exception/cancellation close, external HTTP client 미종료를 검증한다. async에는 block 안에서 future를 await한 뒤 client가 닫히는 계약을 추가한다.

- [x] **Step 2: 실패를 확인한다**

```bash
./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.lambda.LambdaClientSupportTest' --tests 'io.bluetape4k.aws.lambda.LambdaAsyncClientSupportTest' --no-daemon
```

- [x] **Step 3: factory를 구현한다**

`SfnClientSupport.kt`와 같은 explicit argument → callback → build 순서를 사용한다. application factory는 `ShutdownQueue.register(client)`, bounded helper는 `try/finally { client.close() }`를 사용한다. async helper는 `suspend (LambdaAsyncClient) -> R` block을 받아 미완료 future가 client close 뒤로 나가지 않게 한다.

- [x] **Step 4: lifecycle test를 통과시킨다**

```bash
./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.lambda.LambdaClientSupportTest' --tests 'io.bluetape4k.aws.lambda.LambdaAsyncClientSupportTest' --no-daemon
```

Expected: service client close exactly once, external HTTP client close 0회.

### Task 6: Java sync·async·coroutine invocation을 TDD로 추가한다

**Files:** `LambdaExtensionsTest.kt`, `LambdaClientExtensions.kt`, `LambdaAsyncClientExtensions.kt`, `LambdaAsyncClientCoroutinesExtensions.kt`.

- [x] **Step 1: 실패 테스트를 작성한다**

```kotlin
@Test fun `sync bytes and string preserve final request and raw response`()
@Test fun `typed invocation decodes success and function error payload`()
@Test fun `function error is result data and transport error is unchanged`()
@Test fun `null payload yields null value while empty payload decodes`()
@Test fun `invalid log tail raises decode error without fallback`()
@Test fun `async future maps response exactly once`()
@Test fun `cancel before response cancels sdk future`()
@Test fun `response after cancellation cannot resurrect result`()
@Test fun `await overload propagates CancellationException`()
```

- [x] **Step 2: 실패를 확인한다**

```bash
./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.lambda.LambdaExtensionsTest' --no-daemon
```

- [x] **Step 3: extension을 구현한다**

sync는 `invoke(invokeRequestOf(...)).toLambdaInvocationResult(codec)`로 만들고 async는 SDK future와 result future를 분리한다. `whenComplete`는 error를 그대로 exceptional completion하고 response를 한 번만 complete한다. result future cancellation은 `sdkFuture.cancel(true)`를 호출하며 `await()`는 `kotlinx.coroutines.future.await`를 사용한다. raw `invoke(InvokeRequest)`는 가리지 않는다.

- [x] **Step 4: targeted test를 통과시킨다**

```bash
./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.lambda.LambdaExtensionsTest' --no-daemon
```

### Task 7: Kotlin SDK codec·result·request를 TDD로 추가한다

**Files:** Kotlin codec/result/request tests와 `LambdaPayloadCodec.kt`, `LambdaInvocationResult.kt`, `model/LambdaRequestSupport.kt`.

- [x] **Step 1: 실패 테스트를 작성한다**

Kotlin `InvokeResponse`/`InvokeRequest.Builder` property DSL로 bytes/string/Jackson, null/empty, FunctionError/log tail, callback override, blank fields, Tail + Event/DryRun를 Java 테스트와 대칭 의미로 검증한다.

- [x] **Step 2: 실패를 확인한다**

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.lambda.LambdaPayloadCodecTest' --tests 'io.bluetape4k.aws.kotlin.lambda.model.LambdaRequestSupportTest' --no-daemon
```

- [x] **Step 3: Kotlin SDK-specific 구현을 작성한다**

```kotlin
inline fun invokeRequestOf(
    functionName: String,
    payload: ByteArray? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.RequestResponse,
    logType: LogType = LogType.None,
    crossinline builder: InvokeRequest.Builder.() -> Unit = {},
): InvokeRequest = InvokeRequest {
    this.functionName = functionName
    payload?.let { this.payload = it.copyOf() }
    this.qualifier = qualifier
    this.invocationType = invocationType
    this.logType = logType
    builder()
}.also(::validateInvokeRequest)
```

Kotlin codec/result는 Java 타입을 import하지 않고 caller mapper, `copyOf`, base64 decode, raw response 보존을 동일한 의미로 구현한다.

- [x] **Step 4: Kotlin targeted test를 통과시킨다**

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.lambda.LambdaPayloadCodecTest' --tests 'io.bluetape4k.aws.kotlin.lambda.model.LambdaRequestSupportTest' --no-daemon
```

### Task 8: Kotlin lifecycle와 native suspend invocation을 TDD로 추가한다

**Files:** `LambdaClientSupportTest.kt`, `LambdaExtensionsTest.kt`, `LambdaClientSupport.kt`, `LambdaExtensions.kt`.

- [x] **Step 1: 실패 테스트를 작성한다**

`SfnClientSupportTest`/`SfnExtensionsTest`의 `coEvery`/`coVerify` 패턴으로 success/failure/cancellation close, caller-owned `HttpClientEngine` 미종료, request mapping, FunctionError result, codec error, suspend cancellation을 검증한다.

- [x] **Step 2: 실패를 확인한다**

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.lambda.LambdaClientSupportTest' --tests 'io.bluetape4k.aws.kotlin.lambda.LambdaExtensionsTest' --no-daemon
```

- [x] **Step 3: lifecycle와 native suspend API를 구현한다**

`lambdaClientOf`는 endpoint/region/credentials/http engine을 설정하고 callback을 마지막에 실행한다. `withLambdaClient`는 `useSafe`로 service client만 닫는다. `invokeBytes`/`invokeString`/`invokeTyped`는 native suspend `invoke`를 직접 호출하며 dispatcher를 강제하지 않는다.

- [x] **Step 4: Kotlin test를 통과시킨다**

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.lambda.LambdaClientSupportTest' --tests 'io.bluetape4k.aws.kotlin.lambda.LambdaExtensionsTest' --no-daemon
```

### Task 9: consumer fixture와 Lambda smoke boundary를 완성한다

**Files:** 두 consumer fixture, 두 `build.gradle.kts`, 두 `LambdaSmokeTest.kt`, readiness evidence.

- [x] **Step 1: fixture compile proof를 실행한다**

```bash
./gradlew compileAwsJavaServiceConsumerFixture compileAwsKotlinServiceConsumerFixture --no-daemon
```

Expected: 외부 runtime service dependency를 명시한 fixture가 PASS한다.

- [x] **Step 2: smoke input/gate를 구현한다**

필수 입력은 `-PlambdaSmoke`, `LAMBDA_SMOKE_FUNCTION_NAME`, `LAMBDA_SMOKE_REGION`이고,
emulator selector `LAMBDA_SMOKE_EMULATOR`는 선택 입력으로 기본값 `floci`를 사용하며
`localstack`을 explicit fallback으로 둔다. optional qualifier를 지원하고,
capability/function/credential가 없으면 client 생성 전에 skip한다. XML/logger에
`lambda-smoke: SKIP before client creation; missing=...`를 기록한다.

- [x] **Step 3: opt-in smoke를 순차 실행한다**

```bash
./gradlew :bluetape4k-aws-java:test -PlambdaSmoke --tests '*LambdaSmokeTest' --no-daemon
./gradlew :bluetape4k-aws-kotlin:test -PlambdaSmoke --tests '*LambdaSmokeTest' --no-daemon
```

function 생성·배포·삭제는 하지 않는다. unsupported 결과는 `live integration unverified: Floci does not support Lambda invoke` 또는 `live integration unverified: LocalStack Lambda smoke failed: <error>`로 기록하고 PASS로 확대하지 않는다.

### Task 10: README·manual·CHANGELOG를 양 언어로 갱신한다

**Files:** root/module README 6개, Java/Kotlin manual EN·KO 4개, `CHANGELOG.md`.

- [x] **Step 1: service matrix와 runtime dependency 예제를 추가한다**

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:lambda")
}
```

Kotlin 예제는 `aws.sdk.kotlin:lambda`를 사용한다. root/module matrix에 Lambda를 추가한다.

- [x] **Step 2: manual에 호출·운영 경계를 추가한다**

develop/unreleased 장에 bytes/string/Jackson mapper, FunctionError result semantics, raw SDK escape hatch, `with...Client` scope, no retry/deployment, sensitive payload/log 비기록, Floci/LocalStack N/A를 설명한다. 다음 실제 호출 예제를 양 언어에 각각 넣는다.

```kotlin
withLambdaClient(region = Region.AP_NORTHEAST_2) { client ->
    val result = client.invokeString("orders-handler", "{\"id\":1}")
    check(!result.hasFunctionError)
}
```

```kotlin
data class OrderRequest(val id: Int)
data class OrderResponse(val accepted: Boolean)
val mapper = tools.jackson.databind.ObjectMapper()

withLambdaClient(region = "ap-northeast-2") { client ->
    val result = client.invokeTyped("orders-handler", OrderRequest(1), LambdaPayloadCodecs.jackson(mapper, OrderResponse::class.java))
    check(result.value != null)
}
```

예제에는 consumer가 `software.amazon.awssdk:lambda` 또는 `aws.sdk.kotlin:lambda` runtime dependency를 직접 추가해야 한다는 문장도 포함한다. EN/KO heading·anchor·API link 구조를 정렬한다.

- [x] **Step 3: CHANGELOG와 writer 계약을 검증한다**

`[미출시] > 추가`에 #314 링크와 Java sync/async/coroutine, Kotlin suspend, codec/result, compileOnly, emulator boundary를 한국어로 기록한다.

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs README.md README.ko.md aws-java/README.md aws-java/README.ko.md aws-kotlin/README.md aws-kotlin/README.ko.md docs/manual/en/modules/bluetape4k-aws-java.md docs/manual/ko/modules/bluetape4k-aws-java.md docs/manual/en/modules/bluetape4k-aws-kotlin.md docs/manual/ko/modules/bluetape4k-aws-kotlin.md CHANGELOG.md
ruby scripts/manual/manual_contract_test.rb
```

Expected: audit/manual contract PASS, manual `releaseRef: 0.5.0`는 변경하지 않는다.

### Task 11: 공식 근거를 sibling wiki에 보존한다

**File:** `/Users/debop/work/bluetape4k/bluetape4k-wiki/research/2026-08-23-aws-lambda-invoke-helper.md` (worktree-relative path: `../../../bluetape4k-wiki/research/2026-08-23-aws-lambda-invoke-helper.md`).

- [x] **Step 1: 공식 URL과 retrieval date를 Korean decision note로 요약한다**

Java example, Kotlin Invoke API, Lambda Invoke API의 URL, FunctionError/base64 LogResult/invocation type, compileOnly·raw response·emulator implications, Assets section을 저작권 안전하게 기록한다.

- [x] **Step 2: wiki index와 search를 검증한다**

```bash
cd /Users/debop/work/bluetape4k/bluetape4k-wiki
git diff --check
gno update
gno embed --collection bluetape4k-wiki
gno search "AWS Lambda Invoke FunctionError bluetape4k" -c bluetape4k-wiki
```

Expected: note가 collection search에 나타난다. AWS branch와 wiki branch commit을 섞지 않는다.

### Task 12: 통합 검증·plan evidence·DoD를 닫는다

**Files:** create `docs/review/evidence/2026-08-23-issue-314-lambda-plan.md`; all implementation files are read-only inputs.

- [x] **Step 1: targeted tests와 compile proof를 순차 실행한다**

```bash
./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.lambda.*' --no-daemon
./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.lambda.*' --no-daemon
./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-kotlin:compileKotlin compileAwsJavaServiceConsumerFixture compileAwsKotlinServiceConsumerFixture detekt --no-daemon
```

- [x] **Step 2: repository build와 manual contract를 실행한다**

```bash
./gradlew build -x test --parallel --no-daemon
./gradlew build --no-daemon
ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
ruby scripts/manual/manual_contract_test.rb
git diff --check
```

Expected: build/detekt/manual/diff check PASS. real AWS IAM/function fidelity는 `UNVERIFIED`로 evidence에 남긴다.

- [x] **Step 3: DoD evidence를 작성한다**

evidence에 alias/compileOnly, public API, error/log/cancellation/lifecycle tests, emulator exact N/A/opt-in XML, bilingual docs/audit, compile/detekt/build 결과와 SHA를 연결한다. unchecked DoD 없이 known gaps를 별도 표로 둔다.

### Task 13: commit·PR·merge를 별도 gate로 종료한다

- [x] **Step 1: atomic Lore commit을 확인한다**

설계/검토 commit 이후 dependency, Java API, Kotlin API, docs/evidence를 기능 단위로 분리하고 각 commit에 intent line, `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested`를 넣는다.

- [x] **Step 2: branch/head를 확인한다**

```bash
git status --short
git log -1 --oneline
git rev-parse HEAD
git merge-base --is-ancestor origin/develop HEAD
```

Expected: feature branch clean, base ancestor 유지.

- [x] **Step 3: PR 전 live issue/metadata와 exact head를 fresh-read한다**

```bash
gh issue view 314 --repo bluetape4k/bluetape4k-aws --json number,state,title,body,labels,milestone,assignees
```

PR body는 한국어이고 마지막은 정확히 `## DoD Status` 표다. target은 repository `bluetape4k/bluetape4k-aws`, base `develop`, head `feat/issue-314-lambda`로 고정한다.

- [ ] **Step 4: merge는 fresh explicit approval 뒤에만 실행한다**

merge 직전 exact head SHA, required checks, review threads, mergeability, metadata, DoD를 다시 읽는다. auto-merge, merge, branch deletion은 별도 승인 없이는 실행하지 않는다.

---

## 계획 자체 점검

- [x] **Spec coverage:** codec bytes/string/Jackson, copy/null/empty, FunctionError/error payload/log tail, function/ARN/qualifier/invocation, callback final override, Java sync/async/coroutine, Kotlin suspend, lifecycle, compileOnly, consumer, emulator N/A, docs, no retry/deployment를 Task 2~12에 매핑했다.
- [x] **Placeholder scan:** unresolved placeholder, 임의 파일, 미정 명령 없이 실제 경로·signature·명령·expected result를 적었다.
- [x] **Type consistency:** Java는 `software.amazon.awssdk.services.lambda.*`, Kotlin은 `aws.sdk.kotlin.services.lambda.*`만 사용하고 codec/result 타입을 공유하지 않는다.
- [x] **Order:** dependency → codec/result → request → lifecycle → invocation → consumer/smoke → docs/wiki → verification → PR gate; 구현 task는 실패 test → 최소 구현 → PASS 순서다.
- [x] **Known gaps:** real AWS IAM/function 및 production fidelity는 `UNVERIFIED`; Spring Boot/Ktor/deployment/retry/polling은 별도 이슈다.

## 승인 후 실행 정지선

이 계획과 6-lane plan review가 사용자 승인되기 전에는 코드·dependency·GitHub PR mutation을 수행하지 않는다. 승인 후 `$executing-plans` 또는 `$subagent-driven-development`로 Task 1부터 실행하고 각 checkbox·명령 출력·commit SHA를 plan evidence에 갱신한다.
