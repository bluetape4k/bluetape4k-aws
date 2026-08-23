# 이슈 #313 AWS Step Functions 실행 helper 설계

## 설계 상태

- 상태: 수정 설계 승인됨, 6개 관점 독립 재검토 완료
- 범위: Epic #501의 첫 번째 실행 항목인 Issue #313
- 기준일: 2026-08-22
- 사용자 승인: `Flow<DescribeExecutionResponse>` 기반 polling 초안을 승인했고, 2026-08-22 “계속해” 지시로 운영·호환성 보강안의 진행을 승인함.
- 구현 전제: 이 문서와 후속 구현 계획을 먼저 검토하고, 구현은 TDD로 진행한다.

## SPW 게이트

### SPW-01 — 요구사항과 경계 확인

- Issue #313의 목표는 workflow orchestration 사용 사례를 위한 실행 helper다.
- 제공 대상은 `start`, `stop`, `describe`, `list executions`다.
- 실행 상태를 반복 조회하는 cold `Flow`를 제공한다.
- state machine 생성·수정·배포와 실행 이력 이벤트(`GetExecutionHistory`) 전용 API는 이번 범위에 포함하지 않는다. Issue의 “실행 이력 polling”은 `DescribeExecutionResponse` 상태 조회 응답 시퀀스를 뜻하며, 각 상태 응답의 raw response 매핑을 테스트한다. 실제 이벤트 이력이 필요하면 후속 이슈에서 `GetExecutionHistory`를 별도로 다룬다.
- 공개 GitHub issue의 언어·라벨·마일스톤과 현재 저장소 규칙을 보존한다.

### SPW-02 — 저장소 및 외부 근거 확인

- `aws-java`는 서비스 SDK를 `compileOnly`로 두고 sync, `CompletableFuture`, `CompletableFuture.await()` 계층을 분리한다.
- `aws-kotlin`은 AWS Kotlin SDK의 native `suspend` API를 직접 호출한다.
- EventBridge Scheduler의 extension·request builder 구조를 두 모듈의 가장 가까운 로컬 패턴으로 사용한다.
- Kinesis `recordFlow`의 cold `flow {}`, `ensureActive()`, `CancellationException` 재전파, 지연 루프를 polling 구현의 동작 기준으로 사용한다.
- AWS 공식 문서는 Start/Stop/Describe/List Execution의 필수 필드, 상태, eventual consistency, 결과 제한을 정의한다.

### SPW-03 — 대안 비교

| 대안 | 장점 | 문제 | 결정 |
|---|---|---|---|
| Describe 한 번만 제공 | 가장 단순하고 SDK와 거의 같다 | 실행 완료를 기다리는 workflow 사용 사례를 해결하지 못함 | 제외 |
| `Flow<DescribeExecutionResponse>` polling | raw SDK 응답 보존, 호출자가 `withTimeout`·`take`로 제어 가능, Kotlin Flow 관용구와 일치 | polling 간격과 eventual consistency를 문서화해야 함 | 채택 |
| 별도 상태/결과 도메인 타입 | 호출자가 다루기 쉬운 상태 모델을 얻음 | AWS SDK 필드와 오류를 중복 정의하고 raw response 규칙을 깨뜨림 | 제외 |
| cancellation 시 자동 `StopExecution` | 실행 중인 workflow를 즉시 중단할 수 있음 | 수명·권한·비용·재시도 정책을 helper가 임의로 결정하는 외부 부작용 | 제외 |

### SPW-04 — 위험·호환성·검증 기준

- Describe/List API는 eventual consistency를 가질 수 있으므로 polling은 즉시 완료를 보장하지 않는다.
- `RUNNING`과 known terminal set(`SUCCEEDED`, `FAILED`, `TIMED_OUT`, `ABORTED`, `PENDING_REDRIVE`)을 구분한다. known terminal response만 raw response로 방출하고, SDK가 새로 반환한 status는 response를 방출하지 않고 `IllegalStateException`으로 종료한다. Java SDK v2는 nullable `ExecutionStatus`와 `UNKNOWN_TO_SDK_VERSION`을 각각 처리하고, AWS SDK for Kotlin은 non-null sealed `ExecutionStatus`의 `SdkUnknown(value)`를 처리한다.
- cancellation은 caller가 소유한다. Flow는 `CancellationException`을 삼키거나 `StopExecution`을 호출하지 않고 그대로 재전파한다.
- 내부 timeout은 두지 않는다. 호출자는 `withTimeout` 또는 `withTimeoutOrNull`을 사용한다.
- library module의 service SDK는 계속 `compileOnly`이며 consumer fixture는 외부 consumer와 동일하게 compile/runtime dependency를 별도로 선언한다.
- 구성된 emulator가 Step Functions를 지원할 때만 smoke test를 실행한다. Floci는 현재 정적 guard로 skip하고 LocalStack fallback smoke가 실패하거나 미지원이면 `live integration unverified`와 정확한 로그·test result XML 근거를 보고하고 unit test와 dependency 검증을 수행한다.

### SPW-05 — 승인 및 진행 조건

- 사용자 승인 전에는 구현 파일을 변경하지 않는다.
- 2026-08-22 사용자 “계속해” 지시로 운영 guardrail과 capability matrix를 포함한 수정 설계의 진행을 승인받았다.
- 다음 게이트는 이 문서의 자체 검토와 별도 구현 계획 승인이다.

### 설계 문서 writer gate 결과

| Gate | 상태 | 근거 |
|---|---|---|
| SPW-01 | PASS | Issue #313 live 요구사항, 범위, 비목표를 문서화함 |
| SPW-02 | PASS | Scheduler/Kinesis 로컬 패턴과 AWS 공식 API 출처를 연결함 |
| SPW-03 | PASS | polling·domain mapping·자동 stop 대안을 비교하고 선택함 |
| SPW-04 | PASS | 실패 모드, 호환성, IAM·logging·emulator 검증 경계를 기록함 |
| SPW-05 | PASS | 초안 polling 승인 후 운영·호환성 보강안을 설명했고 사용자 “계속해” 지시를 받음 |

## 문제와 목표

현재 저장소에는 AWS Step Functions 실행 API를 감싸는 helper가 없다. Java SDK v2와 AWS Kotlin SDK는 각각 raw client API를 제공하지만, 모듈의 기존 호출 규칙과 request builder 관용구를 재사용할 수 있는 얇은 facade가 필요하다.

이번 변경의 목표는 다음과 같다.

1. Java SDK v2와 AWS Kotlin SDK에서 실행 시작·중지·조회·목록 API를 같은 개념으로 노출한다.
2. Java async API를 `CompletableFuture`와 coroutine 계층으로 연결한다.
3. Java async coroutine caller와 AWS Kotlin SDK coroutine caller가 실행 상태를 cold `Flow`로 polling하고 terminal response를 받을 수 있게 한다.
4. AWS SDK request/response 타입과 예외를 보존하여 상위 abstraction의 손실을 피한다.
5. state machine 배포 수명주기나 실행 취소 정책을 helper가 대신 결정하지 않는다.

## 현재 근거

### 저장소 근거

- `aws-java/src/main/kotlin/io/bluetape4k/aws/scheduler/`에 sync·async·coroutine extension과 request builder가 있다.
- `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/scheduler/`에 native suspend extension과 동일한 개념의 model builder가 있다.
- `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlow.kt`는 polling성 cold Flow의 cancellation·delay 기준을 제공한다.
- 두 모듈의 service SDK dependency는 `compileOnly`이며 테스트에서만 명시적인 SDK artifact를 추가한다.

### 외부 근거

- [AWS Step Functions StartExecution API](https://docs.aws.amazon.com/step-functions/latest/apireference/API_StartExecution.html)는 `stateMachineArn`을 필수로 하고 `name`, JSON `input`, `traceHeader`를 선택적으로 받는다.
- [AWS Step Functions StopExecution API](https://docs.aws.amazon.com/step-functions/latest/apireference/API_StopExecution.html)는 `executionArn`을 필수로 하고 `error`, `cause`를 선택적으로 받는다. EXPRESS state machine 실행에는 지원되지 않는다.
- [AWS Step Functions DescribeExecution API](https://docs.aws.amazon.com/step-functions/latest/apireference/API_DescribeExecution.html)는 실행 상태와 결과 필드를 반환하며 eventual consistency를 가진다. EXPRESS 실행은 Map Run이 dispatch한 child가 아니면 지원되지 않는다.
- [AWS Step Functions ListExecutions API](https://docs.aws.amazon.com/step-functions/latest/apireference/API_ListExecutions.html)는 `stateMachineArn` 또는 `mapRunArn` 기준 목록, 상태 필터, `maxResults`, `nextToken`을 지원한다.
- [AWS Step Functions service quotas](https://docs.aws.amazon.com/step-functions/latest/dg/service-quotas.html)는 API action별 account/Region bucket과 refill rate를 정의하므로, polling collector 수를 aggregate quota 안에서 관리해야 한다.
- [AWS Step Functions IAM authorization reference](https://docs.aws.amazon.com/service-authorization/latest/reference/list_stepfunctions.html)는 action별 resource ARN과 조건 키를 정의한다.
- [AWS SDK for Kotlin SfnClient](https://docs.aws.amazon.com/sdk-for-kotlin/api/latest/sfn/aws.sdk.kotlin.services.sfn/-sfn-client/)는 native suspend operation을 제공하고, Kotlin SDK의 [ListExecutions paginator](https://docs.aws.amazon.com/sdk-for-kotlin/api/latest/sfn/aws.sdk.kotlin.services.sfn.paginators/list-executions-paginated.html)는 별도 Flow를 제공한다.
- AWS SDK for Kotlin의 [ExecutionStatus](https://docs.aws.amazon.com/sdk-for-kotlin/api/latest/sfn/aws.sdk.kotlin.services.sfn.model/-execution-status/)는 `Running`, `Succeeded`, `Failed`, `TimedOut`, `Aborted`, `PendingRedrive`, `SdkUnknown(value)` sealed subtype를 제공한다.
- Java SDK v2의 [SfnAsyncClient](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/sfn/SfnAsyncClient.html)는 `CompletableFuture` 기반 async operation을 제공한다.

## 선택한 설계

### 패키지와 dependency

| 모듈 | 공개 패키지 | SDK artifact | 의존성 정책 |
|---|---|---|---|
| `aws-java` | `io.bluetape4k.aws.sfn` | `software.amazon.awssdk:sfn` | module `compileOnly`, test `testImplementation` |
| `aws-kotlin` | `io.bluetape4k.aws.kotlin.sfn` | `aws.sdk.kotlin:sfn` | module `compileOnly`, test `testImplementation` |

Version은 기존 immutable central catalog와 AWS BOM/`aws-kotlin` 버전 관리에 맡긴다. 모듈 build file에 숫자 버전을 직접 작성하지 않는다.

공개 타입과 source 경계는 모듈별로 다음처럼 고정한다.

| 모듈 | source 경계 | polling options FQCN | AWS client/status type |
|---|---|---|---|
| `aws-java` | `io/bluetape4k/aws/sfn/SfnClientSupport.kt`, `SfnAsyncClientSupport.kt`, `SfnExtensions.kt`, `SfnExecutionFlow.kt`, `model/SfnRequestSupport.kt` | `io.bluetape4k.aws.sfn.SfnExecutionPollingOptions` | `software.amazon.awssdk.services.sfn.SfnClient`(one-shot), `SfnAsyncClient`(Flow), Java `software.amazon.awssdk.services.sfn.model.ExecutionStatus` |
| `aws-kotlin` | `io/bluetape4k/aws/kotlin/sfn/SfnClientSupport.kt`, `SfnExtensions.kt`, `SfnExecutionFlow.kt`, `model/SfnRequestSupport.kt` | `io.bluetape4k.aws.kotlin.sfn.SfnExecutionPollingOptions` | `aws.sdk.kotlin.services.sfn.SfnClient`(native suspend와 Flow), sealed `aws.sdk.kotlin.services.sfn.model.ExecutionStatus` |

각 모듈의 README와 KDoc에는 위 FQCN, consumer의 compile/runtime classpath에 추가해야 하는 SDK artifact, caller-owned client 수명 예제를 함께 싣는다. 두 모듈의 `SfnExecutionPollingOptions`는 이름과 기본값만 대칭이고 서로 다른 SDK 모듈의 타입이므로 import를 혼용하지 않는다.

### Client lifecycle support

`aws-java`에는 기존 Kinesis/STS pattern과 같은 `sfnClient`, `sfnClientOf`, `sfnAsyncClient`, `sfnAsyncClientOf` factory를 제공하고 생성한 client를 기존 `ShutdownQueue`에 등록한다. 이 factory는 application-scoped client용이며 operation마다 생성하지 않는다. `ShutdownQueue`는 등록 해제 API가 없고 강한 참조를 유지하므로, short-lived client에는 별도 `withSfnClient`와 `withSfnAsyncClient`를 제공한다. 두 helper는 공통 internal builder로 미등록 client를 만들고 `.use { }` 안에서 block을 완료한 뒤 즉시 닫는다. Flow는 client를 닫지 않는다.

`aws-kotlin`에는 `sfnClientOf`와 `withSfnClient`를 제공하고 기존 `useSafe` pattern으로 short-lived client를 닫는다. application-scoped client는 caller가 명시적으로 `close()`한다. `withSfnClient` block 밖으로 cold Flow만 반환하지 않으며, block 안에서 terminal 또는 timeout까지 collect를 완료한다.

Public lifecycle signature는 다음으로 고정한다. Java SDK v2에서 `sfnClient*`/`sfnAsyncClient*` factory는 `ShutdownQueue`에 등록하고, `withSfn*Client`는 같은 설정을 적용한 미등록 client를 만든다.

```kotlin
// aws-java
inline fun sfnClient(builder: SfnClientBuilder.() -> Unit): SfnClient

inline fun sfnClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: SfnClientBuilder.() -> Unit = {},
): SfnClient

inline fun <R> withSfnClient(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: SfnClientBuilder.() -> Unit = {},
    block: (SfnClient) -> R,
): R

inline fun sfnAsyncClient(builder: SfnAsyncClientBuilder.() -> Unit): SfnAsyncClient

inline fun sfnAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: SfnAsyncClientBuilder.() -> Unit = {},
): SfnAsyncClient

suspend inline fun <R> withSfnAsyncClient(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: SfnAsyncClientBuilder.() -> Unit = {},
    crossinline block: suspend (SfnAsyncClient) -> R,
): R
```

```kotlin
// aws-kotlin
inline fun sfnClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: SfnClient.Config.Builder.() -> Unit = {},
): SfnClient

suspend fun <R> withSfnClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    builder: SfnClient.Config.Builder.() -> Unit = {},
    block: suspend (SfnClient) -> R,
): R
```

Lifecycle helper는 명시 파라미터를 먼저 적용하고 `builder`를 마지막에 실행하므로 유효한 builder override가 최종 설정이 된다. parameter 또는 builder로 전달한 `SdkHttpClient`, `SdkAsyncHttpClient`, `HttpClientEngine`은 모두 외부 소유다. `withSfn*Client`는 성공·실패·cancellation 모두에서 service client만 닫고 외부 HTTP client는 닫지 않으며, 기본 provider의 공유 HTTP client도 service client 종료와 별도 수명이다. KDoc와 consumer fixture는 기본 설정, custom builder override, custom HTTP client를 각각 컴파일하고 block의 suspend/non-suspend 경계와 close ownership을 고정한다.

### Compile-tested 호출 예제 계약

README와 consumer fixture는 다음 두 사용 형태를 실제 컴파일 대상으로 유지한다. public signature가 SDK type을 노출하므로 consumer는 compile/runtime classpath에 Java SDK v2 사용 시 `software.amazon.awssdk:sfn`, Kotlin SDK 사용 시 `aws.sdk.kotlin:sfn`을 직접 추가한다. 일반적인 Gradle consumer는 `implementation(...)`으로 두 classpath를 함께 충족할 수 있다. 예제 ARN은 Standard execution을 전제로 하며 Express execution에는 polling helper를 사용하지 않는다.

```kotlin
// aws-java: Java SDK v2 async client + coroutine Flow
runBlocking {
    withSfnAsyncClient(region = Region.AP_NORTHEAST_2) { client ->
        withTimeout(30.seconds) {
            client.describeExecutionFlow(executionArn).last()
        }
    }
}
```

```kotlin
// aws-kotlin: AWS SDK for Kotlin native suspend client
withSfnClient(region = "ap-northeast-2") { client ->
    withTimeout(30.seconds) {
        client.describeExecutionFlow(executionArn).last()
    }
}
```

두 예제 모두 timeout 안에서 terminal response까지 수집한 뒤 client를 닫는다. application-scoped client 예제는 `close()`를 lifecycle hook에서 호출하며, cold Flow만 client scope 밖으로 반환하는 예제는 허용하지 않는다.

### Request builder

두 모듈에 `model/SfnRequestSupport.kt`를 추가하고 다음 builder를 제공한다. 각 builder는 ARN·`name`·`nextToken`처럼 비어 있으면 의미가 없는 필드만 `requireNotBlank`로 검사하고, AWS가 빈 문자열을 허용하는 선택 필드는 그대로 전달한다. SDK builder callback을 마지막 인자로 남겨 향후 필드를 추가해도 API 확장을 줄인다. 명시적 인자는 callback보다 먼저 적용하고 callback이 같은 필드를 다른 유효한 값으로 덮어쓰면 callback 값을 최종 값으로 사용한다. callback 적용 후 최종 request의 local invariant를 다시 검사하므로 빈 required 값, 잘못된 list source 조합, 범위 밖 값은 거부한다. 즉 callback은 valid override와 고급 필드 확장을 허용하지만 invariant를 우회할 수 없다.

```kotlin
// aws-java: software.amazon.awssdk.services.sfn.model.*
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

Kotlin SDK builder는 같은 의미를 `StartExecutionRequest { ... }` DSL로 구현한다. AWS API는 `stateMachineArn`과 `mapRunArn`을 둘 다 optional로 정의하되 동시에 지정하는 것은 금지한다. 이 helper는 target 없는 전체 목록 호출을 의도적으로 허용하지 않는 더 강한 local policy로 정확히 하나를 요구한다. 둘 다 지정하거나 둘 다 생략하면 local `IllegalArgumentException`이며, target 없는 raw SDK request가 필요한 caller는 SDK builder/member를 직접 사용할 수 있다. `redriveFilter`는 Java의 `software.amazon.awssdk.services.sfn.model.ExecutionRedriveFilter`와 Kotlin의 `aws.sdk.kotlin.services.sfn.model.ExecutionRedriveFilter`를 명시적 인자로 전달하고 enum 값은 SDK 타입에 맡긴다. AWS 계약에 따라 `statusFilter=PENDING_REDRIVE` 또는 `redriveFilter`를 사용하면 `mapRunArn`이 필수이며 `stateMachineArn`과 함께 사용할 수 없으므로 callback 적용 후 local invariant로 검사한다. `maxResults`는 AWS API 범위인 `0..1000`을 검사한다. `traceHeader`, `error`, `cause`는 AWS가 빈 문자열을 허용하므로 지정하지 않은 경우에만 생략하고, 값이 있으면 그대로 전달한다. 명시 인자와 callback 중 어느 경로에서든 최종 `input == null`이면 AWS 문서의 빈 JSON 입력 규칙에 맞춰 `"{}"`로 정규화하고, 빈 문자열·공백은 거부한다. JSON을 파싱·재직렬화하지 않고 caller 원문을 그대로 전달하며 JSON 구조의 유효성은 AWS 서비스 책임으로 둔다. ARN, `name`, `nextToken`처럼 비어 있으면 의미가 없는 필드만 `requireNotBlank`로 검사한다. `name`은 AWS 허용 길이인 1–80 범위만 검사하고 서비스의 추가 문자 규칙을 임의로 복제하지 않는다.

### Java SDK v2 API

`SfnClient`에는 request builder를 사용하는 얇은 sync overload를 제공한다.

```kotlin
fun SfnClient.startExecution(
    stateMachineArn: String,
    name: String? = null,
    input: String? = null,
    traceHeader: String? = null,
    builder: StartExecutionRequest.Builder.() -> Unit = {},
): StartExecutionResponse

fun SfnClient.stopExecution(
    executionArn: String,
    error: String? = null,
    cause: String? = null,
    builder: StopExecutionRequest.Builder.() -> Unit = {},
): StopExecutionResponse

fun SfnClient.describeExecution(
    executionArn: String,
    builder: DescribeExecutionRequest.Builder.() -> Unit = {},
): DescribeExecutionResponse

fun SfnClient.listExecutionsByStateMachine(
    stateMachineArn: String,
    statusFilter: ExecutionStatus? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsResponse

fun SfnClient.listExecutionsByMapRun(
    mapRunArn: String,
    statusFilter: ExecutionStatus? = null,
    redriveFilter: ExecutionRedriveFilter? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsResponse
```

`SfnAsyncClient`에는 `CompletableFuture` extension을 `*Async` 이름으로 제공한다.

```kotlin
fun SfnAsyncClient.startExecutionAsync(
    stateMachineArn: String,
    name: String? = null,
    input: String? = null,
    traceHeader: String? = null,
    builder: StartExecutionRequest.Builder.() -> Unit = {},
): CompletableFuture<StartExecutionResponse>

fun SfnAsyncClient.stopExecutionAsync(
    executionArn: String,
    error: String? = null,
    cause: String? = null,
    builder: StopExecutionRequest.Builder.() -> Unit = {},
): CompletableFuture<StopExecutionResponse>

fun SfnAsyncClient.describeExecutionAsync(
    executionArn: String,
    builder: DescribeExecutionRequest.Builder.() -> Unit = {},
): CompletableFuture<DescribeExecutionResponse>

fun SfnAsyncClient.listExecutionsByStateMachineAsync(
    stateMachineArn: String,
    statusFilter: ExecutionStatus? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): CompletableFuture<ListExecutionsResponse>

fun SfnAsyncClient.listExecutionsByMapRunAsync(
    mapRunArn: String,
    statusFilter: ExecutionStatus? = null,
    redriveFilter: ExecutionRedriveFilter? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): CompletableFuture<ListExecutionsResponse>
```

같은 async client에 coroutine overload를 제공하고 내부에서 `kotlinx.coroutines.future.await()`만 사용한다.

```kotlin
suspend fun SfnAsyncClient.startExecution(
    stateMachineArn: String,
    name: String? = null,
    input: String? = null,
    traceHeader: String? = null,
    builder: StartExecutionRequest.Builder.() -> Unit = {},
): StartExecutionResponse

suspend fun SfnAsyncClient.stopExecution(
    executionArn: String,
    error: String? = null,
    cause: String? = null,
    builder: StopExecutionRequest.Builder.() -> Unit = {},
): StopExecutionResponse

suspend fun SfnAsyncClient.describeExecution(
    executionArn: String,
    builder: DescribeExecutionRequest.Builder.() -> Unit = {},
): DescribeExecutionResponse

suspend fun SfnAsyncClient.listExecutionsByStateMachine(
    stateMachineArn: String,
    statusFilter: ExecutionStatus? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsResponse

suspend fun SfnAsyncClient.listExecutionsByMapRun(
    mapRunArn: String,
    statusFilter: ExecutionStatus? = null,
    redriveFilter: ExecutionRedriveFilter? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsResponse
```

SDK request overload와 충돌하지 않도록 parameterized helper는 `...Async`를 거치고, public coroutine overload는 기존 service extension 관례에 맞춰 operation 이름을 사용한다. SDK 예외와 `CancellationException`은 변환하지 않는다.

### AWS Kotlin SDK API

`SfnClient`의 native suspend operation 위에 다음 overload를 제공한다.

```kotlin
suspend fun SfnClient.startExecution(
    stateMachineArn: String,
    name: String? = null,
    input: String? = null,
    traceHeader: String? = null,
    builder: StartExecutionRequest.Builder.() -> Unit = {},
): StartExecutionResponse

suspend fun SfnClient.stopExecution(
    executionArn: String,
    error: String? = null,
    cause: String? = null,
    builder: StopExecutionRequest.Builder.() -> Unit = {},
): StopExecutionResponse

suspend fun SfnClient.describeExecution(
    executionArn: String,
    builder: DescribeExecutionRequest.Builder.() -> Unit = {},
): DescribeExecutionResponse

suspend fun SfnClient.listExecutionsByStateMachine(
    stateMachineArn: String,
    statusFilter: ExecutionStatus? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsResponse

suspend fun SfnClient.listExecutionsByMapRun(
    mapRunArn: String,
    statusFilter: ExecutionStatus? = null,
    redriveFilter: ExecutionRedriveFilter? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsResponse
```

`listExecutionsByStateMachine`과 `listExecutionsByMapRun`은 SDK의 `listExecutions { ... }` member와 이름을 분리하여 Kotlin member 우선 해석으로 local validation이 우회되지 않게 한다. dedicated helper의 source 인자는 callback보다 강한 invariant다. callback 적용 후 state-machine helper는 최종 `stateMachineArn`이 explicit 인자와 같고 `mapRunArn == null`이어야 하며 `PENDING_REDRIVE`와 `redriveFilter`를 거부한다. map-run helper는 최종 `mapRunArn`이 explicit 인자와 같고 `stateMachineArn == null`이어야 한다. callback으로 source를 바꾸려 하면 local `IllegalArgumentException`이며, source 자체를 callback으로 선택해야 하는 고급 caller는 generic `listExecutionsRequestOf` 또는 raw SDK member를 사용한다. 두 helper는 SDK paginator를 다시 감싸지 않고 한 페이지의 raw `ListExecutionsResponse`를 반환한다. 전체 페이지 순회는 AWS Kotlin SDK의 `listExecutionsPaginated`를 caller가 직접 선택한다. Java SDK caller도 `SfnClient.listExecutionsPaginator(...)` 또는 async paginator를 직접 사용한다. 전체 목록을 순회할 때는 서비스가 허용하는 범위에서 `maxResults`를 크게 잡고, 여러 consumer가 동일 목록을 중복 순회하지 않도록 호출 수명과 공유 정책을 caller가 관리한다.

### 실행 상태 polling Flow

Java polling은 blocking `SfnClient`가 아니라 `SfnAsyncClient`에서만 제공하고, AWS Kotlin SDK polling은 native suspend `SfnClient`에서 제공한다. Java sync `SfnClient`는 one-shot start/stop/describe/list helper만 제공한다. 이 경계를 두어 blocking AWS 호출을 event-loop나 collector dispatcher에서 실행하지 않는다.

Java `aws-java` 공개 API:

```kotlin
import io.bluetape4k.aws.sfn.SfnExecutionPollingOptions
import kotlinx.coroutines.flow.Flow
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse

fun SfnAsyncClient.describeExecutionFlow(
    request: DescribeExecutionRequest,
    options: SfnExecutionPollingOptions = SfnExecutionPollingOptions(),
): Flow<DescribeExecutionResponse>

fun SfnAsyncClient.describeExecutionFlow(
    executionArn: String,
    options: SfnExecutionPollingOptions = SfnExecutionPollingOptions(),
): Flow<DescribeExecutionResponse>
```

Kotlin `aws-kotlin` 공개 API:

```kotlin
import aws.sdk.kotlin.services.sfn.SfnClient
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionRequest
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionResponse
import io.bluetape4k.aws.kotlin.sfn.SfnExecutionPollingOptions
import kotlinx.coroutines.flow.Flow

fun SfnClient.describeExecutionFlow(
    request: DescribeExecutionRequest,
    options: SfnExecutionPollingOptions = SfnExecutionPollingOptions(),
): Flow<DescribeExecutionResponse>

fun SfnClient.describeExecutionFlow(
    executionArn: String,
    options: SfnExecutionPollingOptions = SfnExecutionPollingOptions(),
): Flow<DescribeExecutionResponse>
```

위 `SfnAsyncClient` overload는 `aws-java`에, `SfnClient` overload는 `aws-kotlin`에 각각 구현한다. `aws-java`에는 동기 `SfnClient` Flow overload를 추가하지 않는다. 모든 `startExecution` helper에서 명시 인자와 callback 적용 후의 `input == null`은 동일하게 `"{}"`로 정규화하고, 빈 문자열·공백은 local validation으로 거부한다. 따라서 호출자는 input 없이도 안전한 기본 request를 얻고, 명시적인 JSON 원문은 그대로 전달한다.

`SfnExecutionPollingOptions`는 양 모듈에 같은 개념으로 두고 기본 간격과 최소 간격을 1초로 한다. API 호출 폭주를 막기 위해 1초 미만, 무한대, 비유한 duration은 거부한다. 이 값은 단일 Flow의 하한일 뿐 AWS account/Region quota나 전체 동시 실행 수를 보장하지 않으므로, caller가 동시 collector 수와 polling 예산을 관리한다. 여러 consumer가 같은 execution을 관찰해야 하면 `shareIn` 또는 `stateIn`으로 하나의 polling source를 공유한다. 고정 간격은 재현 가능한 동작과 테스트를 위해 유지하며 jitter/backoff는 후속 범위로 둔다. 고동시성 caller는 Flow 시작 시점을 분산하거나 별도 jitter를 적용해 동기화된 burst를 피한다.

```kotlin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class SfnExecutionPollingOptions(
    val pollInterval: Duration = 1.seconds,
) {
    init {
        require(pollInterval.isFinite() && pollInterval >= 1.seconds) {
            "pollInterval must be finite and at least 1s"
        }
    }
}
```

1. Flow collect 시점과 각 반복의 SDK 호출 직전에 `currentCoroutineContext().ensureActive()`를 확인한다. 그 뒤 immutable `DescribeExecutionRequest`로 첫 `DescribeExecution`을 즉시 호출한다. ARN 편의 overload는 동일 request overload로 위임한다.
2. 응답 상태를 먼저 분류한다. `RUNNING`이면 raw response를 emit한 뒤 `options.pollInterval`만큼 `delay`한다.
3. 상태가 알려진 terminal set인 `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `ABORTED`, `PENDING_REDRIVE`이면 raw response를 emit한 뒤 정상 종료한다.
4. Java SDK status가 `null`이거나 `UNKNOWN_TO_SDK_VERSION`, 또는 Kotlin SDK status가 `SdkUnknown(value)`이면 응답을 emit하지 않고 `IllegalStateException`으로 종료한다. 예외 타입은 고정하고 메시지는 `Unsupported Step Functions execution status: <status-name>` 형식으로 status 이름/값만 포함하며 raw payload는 포함하지 않는다. Java는 `DescribeExecutionResponse.statusAsString()`으로 future server status 원문을 보존하고 null이면 `<null>`을 사용한다. Kotlin은 `SdkUnknown(value)`의 value를 사용하며 `DescribeExecutionResponse.status`가 non-null이므로 별도 null branch를 만들지 않는다.
5. delay 전후에 coroutine 취소 상태를 확인하고, 다음 Describe 호출을 반복한다.
6. caller가 취소하면 `CancellationException`을 그대로 재전파하며 `StopExecution`을 자동 호출하지 않는다.

Flow는 `flow {}`로 구현하여 cold semantics를 보장하고, 내부 timeout·재시도·backoff·상태 도메인 매핑을 추가하지 않는다. 각 `collect`는 독립적인 AWS polling을 생성하므로 같은 Flow를 여러 번 collect하면 호출량도 collector 수만큼 늘어난다. 호출자는 production에서 반드시 `withTimeout` 또는 명시적인 deadline을 사용하고, `take`, `first` 등 표준 Flow 연산으로 추가 종료 조건을 조정한다. AWS의 eventual consistency 때문에 첫 응답이 즉시 최신 상태라는 가정을 문서화하지 않는다. `PENDING_REDRIVE`는 polling의 알려진 terminal 상태일 뿐 business success 판정이 아니며, caller가 redrive 정책을 별도로 결정한다.

Java async coroutine helper는 `.await()`를 사용한다. coroutine cancellation 또는 `withTimeout`으로 collect가 끝나면 underlying `CompletableFuture`/SDK request도 취소되는지 검증하고, 별도 future를 남기지 않는다. Flow는 Java의 caller-owned `SfnAsyncClient`와 Kotlin의 caller-owned `SfnClient`를 닫지 않으며, 각 client가 살아 있는 `withXxxClient { }` 범위 또는 명시적 application scope 안에서 collect를 완료해야 한다.

### 응답과 오류 정책

- 모든 operation은 AWS SDK raw response를 반환한다.
- SDK 예외, service error code, throttling, eventual-consistency 오류를 별도 wrapper로 바꾸지 않는다.
- validation은 명백한 local precondition에만 적용하며 서비스의 전체 입력 규칙을 복제하지 않는다.
- Flow 내부에서 `runCatching`으로 cancellation을 삼키지 않는다.
- client 생성·종료는 caller가 소유한다. 짧은 수명 사용은 repository의 `withXxxClient { }` 관례를 따른다.
- helper는 request/response 전체를 로그로 남기지 않는다. `input`, `output`, `cause`, `traceHeader`와 raw response의 payload 노출은 caller의 관측성 계층에서도 redaction을 거쳐야 한다.
- 인증·인가, 계정·리전·리소스 범위는 caller가 구성한 AWS client와 IAM 정책이 소유한다. helper는 ARN ownership이나 `states:*` 권한을 추론하거나 제한하지 않는다.

### 호출자 운영 계약

#### Standard·Express·Map Run capability matrix

아래 표는 helper가 호출하는 AWS API의 지원 경계를 나타낸다. “조건부”는 Map Run이 dispatch한 child execution 또는 mapRunArn 조회처럼 AWS가 명시한 경우에만 해당한다.

| helper/API | Standard | Express | Map Run child |
|---|---|---|---|
| StartExecution | 지원, 같은 name+input 실행은 idempotent | 지원, idempotent하지 않으므로 retry 중복 실행을 caller가 방지 | child를 직접 시작하는 API가 아니며 Map Run dispatch 범위 밖 |
| DescribeExecution | 지원 | 미지원 | 조건부 지원 |
| ListExecutions | state machine ARN으로 지원 | 미지원 | mapRunArn으로 child 목록 조건부 지원 |
| StopExecution | 지원 | 미지원 | child type이 Express이면 미지원; Standard child만 서비스 계약에 따름 |
| describeExecutionFlow | DescribeExecution 지원 범위와 동일 | 미지원 | DescribeExecution 조건부 지원 범위와 동일 |

Express execution event history를 이 helper가 대신 복원하지 않는다. Express 관찰이 필요하면 CloudWatch Logs 기반 경로를 별도 설계해야 하며, 이는 Issue #313 범위 밖이다. 따라서 README 예제는 Standard execution 또는 Map Run child를 대상으로 하고 Express를 Standard처럼 polling하지 않는다.

#### Quota·deadline·throttling

AWS quota는 account/Region aggregate 기준이며 AWS가 bucket/refill 값을 변경할 수 있으므로 운영 배포 전 [Service quotas](https://docs.aws.amazon.com/step-functions/latest/dg/service-quotas.html)를 다시 확인한다. 현재 문서 기준 주요 refill rate는 다음과 같다.

| API | 미국 동부(버지니아 북부)·미국 서부(오리건)·유럽(아일랜드) | 그 외 Region |
|---|---:|---:|
| StartExecution Standard / Express | 300 / 6,000 TPS | 150 / 6,000 TPS |
| StopExecution | 200 TPS | 25 TPS |
| DescribeExecution | 15 TPS | 10 TPS |
| ListExecutions | 5 TPS | 2 TPS |

1초 최소 간격도 collector가 많으면 quota를 보장하지 않는다. 구현은 내부 rate limiter나 retry를 추가하지 않는다.

- production collector는 반드시 withTimeout 또는 외부 deadline으로 bounded lifetime을 갖는다.
- caller는 동일 execution에 대한 중복 collect를 shareIn/stateIn으로 합치고, 전체 collector 수·동시성·계정/Region별 polling budget을 명시적으로 제한한다.
- burst를 피하려면 caller가 시작 시점을 분산하거나 jitter/rate limiter를 적용한다.
- ThrottlingException, latency, timeout, terminal outcome, retry count를 service/operation별로 관찰하고, quota 초과 시 collector 수를 줄이거나 간격을 늘리는 대응을 수행한다.
- Step Functions 사용량에 따른 billing과 API throttling/request pressure는 별도 지표로 기록한다.

#### IAM·KMS runbook

[Step Functions IAM authorization reference](https://docs.aws.amazon.com/service-authorization/latest/reference/list_stepfunctions.html)에 맞춰 최소 권한을 consumer가 구성한다.

| API | 최소 action | resource 범위 |
|---|---|---|
| StartExecution | states:StartExecution + operation mapping의 states:DescribeExecution | 대상 state machine ARN과 생성될 execution/express ARN pattern을 분리 |
| DescribeExecution | states:DescribeExecution | 대상 execution ARN 또는 지원되는 Map Run child express ARN |
| StopExecution | states:StopExecution | 대상 execution ARN |
| ListExecutions | states:ListExecutions | state machine ARN 또는 Map Run ARN |

`statemachine`, `statemachinealias`, `statemachineversion`, `execution`, `express`, `labelled execution`, `labelled express`, `maprun`은 서로 다른 ARN 형태이므로 한 wildcard statement로 합치지 않고 action별 statement와 state-machine 이름 범위가 드러나는 ARN pattern으로 제한한다. alias/version으로 시작하는 caller와 Map Run child caller는 qualified state-machine 및 labelled execution/express pattern을 별도 statement로 둔다. `StartExecution` operation mapping에 포함된 `states:DescribeExecution`도 별도 execution/express resource statement로 둔다.

`DescribeExecution.includedData`는 `ALL_DATA` 또는 `METADATA_ONLY`를 raw request로 전달한다. 암호화된 state-machine definition을 조회하는 경로에서는 `kms:Decrypt`가 필요할 수 있고, `KmsAccessDeniedException`, `KmsInvalidStateException`, `KmsThrottlingException`을 service error로 보존한다. AWS 문서가 `METADATA_ONLY` 대안을 설명하는 대상은 `DescribeStateMachine`이므로, 이 helper가 `DescribeExecution`에서 KMS 검사를 우회한다고 가정하지 않는다.

`StartExecution`도 KMS 관련 service error를 그대로 반환한다. 암호화된 execution의 `StopExecution`에서 non-null `error` 또는 `cause`를 보내면 Step Functions가 execution role의 KMS key로 값을 암호화하므로 key policy, execution role 권한, key state와 throttling을 함께 확인한다. 두 값을 모두 null로 두면 암호화할 데이터가 없어 execution role의 KMS 권한 없이 중지할 수 있으므로, 오류 상세가 필요 없는 운영 중단에는 이 least-privilege 경로를 우선한다.

#### Observability와 데이터 보호

helper 자체는 telemetry exporter나 logger를 만들지 않는다. caller 관측 계층은 최소한 `service=stepfunctions`, operation, outcome/status, retry count, latency, throttle count, SDK response metadata의 AWS request ID를 기록한다. state-machine/execution identity는 application의 bounded logical name을 우선한다. ARN만 있을 때 UTF-8 전체 ARN의 SHA-256 앞 12 hex를 쓰는 방식은 비밀 redaction이 아니라 안정적인 pseudonymous correlation key일 뿐이며, ARN 자체가 민감하거나 dictionary 공격을 방지해야 하면 caller가 관리하는 secret을 사용한 HMAC-SHA-256을 쓴다. 전체 ARN, execution name, input, output, cause, traceHeader와 raw response payload는 기본 redaction 대상으로 둔다. X-Ray 전파가 필요하면 AWS가 정의한 traceHeader와 HTTP trace header 우선순위를 따른다.

운영 manual은 환경별 quota와 error budget에 맞게 threshold를 조정할 수 있음을 전제로, 기본 경보 예시로 5분간 `service=stepfunctions AND outcome=throttled` 비율 1% 초과 또는 같은 logical identity에서 timeout 3회 연속을 제시한다. runbook은 request ID와 digest identity로 관련 호출을 조회하고, collector 수·poll interval·Region quota·KMS 상태를 순서대로 확인한다.

#### Emulator·release·소유권

- Floci-first check: `./gradlew :bluetape4k-aws-java:test --tests "*Sfn*SmokeTest" -Dbluetape4k.aws.emulator=floci --no-daemon --max-workers=1` 및 동등한 Kotlin task를 먼저 실행한다. 두 모듈의 Sfn 전용 `AbstractSfnTest`는 현재 Floci에서 정확한 `live integration unverified: Floci does not support Step Functions` assumption message로 skip한다. 결과 XML(`aws-java/build/test-results/test/*.xml`, `aws-kotlin/build/test-results/test/*.xml`)에서 해당 문구와 skipped count를 읽어 verification artifact에 남기며, skip을 PASS로 승격하지 않는다. Floci가 Step Functions 지원을 추가하면 이 guard를 제거하고 동일 smoke를 활성화하는 것이 명시적인 후속 점검 조건이다.
- LocalStack fallback: 전역 `AbstractAwsTest.services`는 변경하지 않는다. 두 모듈의 Sfn 전용 `AbstractSfnTest`가 LocalStack 선택 시 `LocalStackServer.Launcher.getLocalStack("stepfunctions")`로 독립 service fixture를 시작하여 unrelated test startup을 늘리지 않는다. `./gradlew :bluetape4k-aws-java:test --tests "*Sfn*SmokeTest" -Dbluetape4k.aws.emulator=localstack --no-daemon --max-workers=1`와 동등한 Kotlin task를 순차 실행하고 start/describe/stop/list 최소 동작을 검증한다. LocalStack도 unsupported 오류로 실패하면 unit/dependency 결과, test result XML, 정확한 `live integration unverified: LocalStack Step Functions smoke failed: <error>` 근거를 보고하며 live smoke를 PASS로 표시하지 않는다.
- emulator smoke는 endpoint 호환성과 request/response 직렬화·기본 실행 흐름만 검증한다. 성공하더라도 실제 AWS IAM resource/action, KMS key policy, 암호화 데이터 접근 제어를 검증한 것으로 표시하지 않으며, 별도 opt-in real AWS 정책 테스트나 운영 증거가 없으면 IAM/KMS integration은 `UNVERIFIED`로 남긴다.
- additive public API의 rollback은 consumer 사용 코드를 revert하거나 이전 artifact로 pin하는 방식이며, 이미 배포된 state machine migration은 수행하지 않는다.
- library maintainer는 API, compileOnly POM, consumer fixture, CHANGELOG, EN/KO manual parity를 관리하고, consumer operator는 compile/runtime SDK, IAM/KMS, quota/deadline, client shutdown과 실행 중복 정책을 관리한다.

## 주요 실패 모드와 대응

| 실패 모드 | 관찰 가능한 결과 | 대응 |
|---|---|---|
| 필수 ARN 누락, 잘못된 `name` 길이, list source 동시 지정 | client 호출 전에 `IllegalArgumentException` | request builder의 명시적 local validation으로 조기에 차단 |
| AWS API의 eventual consistency 또는 일시적인 service 오류 | stale 상태 응답 또는 SDK 예외 | raw response/예외를 보존하고 caller가 timeout·재시도 정책을 결정 |
| polling 중 caller cancellation | collect가 `CancellationException`으로 종료 | 예외를 재전파하고 자동 `StopExecution`을 호출하지 않음 |
| 너무 짧은 poll interval로 인한 API throttling | SDK throttling 예외와 request pressure가 발생하며 billing 지표와 별개로 관찰됨 | 1초 미만·비유한 interval을 거부하고 production deadline, aggregate concurrency, caller rate limiter/jitter를 요구하며 내부 retry/backoff는 제공하지 않음 |
| null 또는 SDK unknown execution status | terminal로 오인하면 조기 완료로 보일 수 있음 | Java의 null/`UNKNOWN_TO_SDK_VERSION`, Kotlin의 `SdkUnknown(value)`를 emit하지 않고 고정된 `IllegalStateException`으로 종료 |
| Express execution에 Standard polling 적용 | `DescribeExecution`/`StopExecution`/전체 `ListExecutions`가 즉시 unsupported 오류 | capability matrix를 먼저 확인하고 Map Run child 예외 또는 CloudWatch Logs 별도 경로를 사용 |
| 구성된 emulator의 Step Functions 미지원 | smoke test가 skip되거나 API가 unsupported 오류를 반환 | Floci 정적 skip guard → LocalStack fallback 순서와 test result XML/정확한 `live integration unverified` 근거를 남김 |

## 비목표

- state machine 생성·삭제·업데이트·배포
- `GetExecutionHistory` 이벤트를 합성하거나 도메인 이벤트 타입으로 변환하는 API
- polling 중 자동 `StopExecution`, 재시도, exponential backoff, circuit breaker
- Spring Boot/Ktor integration, listener runtime, workflow orchestration DSL
- AWS SDK paginator의 재포장 또는 공통 `ExecutionResult` 모델
- emulator가 지원하지 않는 Step Functions smoke test를 억지로 추가하는 것

## 검증 설계

### 단위 테스트

`aws-java`:

- `SfnRequestSupportTest`: required/optional field, name 길이, list source exact-one 조건, `redriveFilter` 전달, `maxResults` 범위
- `statusFilter=PENDING_REDRIVE` 또는 `redriveFilter`가 `mapRunArn` 없이 사용되거나 `stateMachineArn`과 함께 사용되면 최종 local validation이 차단하는지 확인
- builder callback이 빈 필수 ARN, 80자 초과 `name`, list source 둘 다 지정·둘 다 생략, 범위 밖 `maxResults`를 설정해도 최종 request 재검증이 `IllegalArgumentException`으로 차단하는지 확인
- builder callback이 명시 인자를 다른 유효한 ARN/name/source 값으로 덮어쓰면 callback 값이 최종 request에 보존되는지 확인
- `input = null`은 `"{}"`로 정규화하고, `input = ""`·공백은 거부하며, 유효 JSON은 원문 그대로 보존하는지 확인
- builder callback이 `input`을 null로 덮어쓰면 `"{}"`로 정규화하고, 빈 문자열·공백으로 덮어쓰면 최종 field-specific validation이 차단하는지 확인
- builder callback이 빈 `nextToken`을 설정해도 최종 field-specific validation이 차단하는지 확인
- `nextToken`을 쓰는 후속 page request가 source/filter 값을 그대로 유지하고, token 만료는 service의 `InvalidToken` 예외로 보존하는지 확인
- sync extension이 정확한 request를 SDK client에 전달하고, `listExecutionsByStateMachine`/`listExecutionsByMapRun`이 각각 허용된 source와 filter만 구성하는지 확인
- dedicated list helper callback이 explicit source를 다른 ARN으로 덮어쓰거나 반대 source를 추가하는 경우, state-machine helper에서 `PENDING_REDRIVE`/`redriveFilter`를 추가하는 경우를 최종 source-specific validation이 차단하는지 확인
- async/coroutine extension의 response 전달과 `CancellationException` 전파
- `aws-java`에서는 `SfnAsyncClient.describeExecutionFlow`가 `DescribeExecution` 상태 응답으로 RUNNING 응답을 반복하고 known terminal raw 응답에서 끝나는지 확인한다. blocking `SfnClient` Flow가 노출되지 않는지 API surface도 확인한다.
- `PENDING_REDRIVE`가 raw response를 emit하고 종료하되 success wrapper로 변환되지 않는지 확인
- `runTest` virtual time에서 poll interval만큼만 다음 호출이 발생하고, `take(1)`·cancellation 직후 추가 SDK 호출이 없는지 `coVerify(exactly = ...)`로 확인
- 느린 collector가 다음 `DescribeExecution` 호출을 선행시키지 않는 backpressure를 확인
- initial/반복 호출 직전 `ensureActive()`가 동작하고, 취소된 future가 남지 않는지 확인
- Java `null` status와 `UNKNOWN_TO_SDK_VERSION`는 응답을 emit하지 않고 `statusAsString()`의 future server status 원문 또는 `<null>`을 포함한 정확한 `IllegalStateException` 메시지만 발생시키며 후속 호출이 없는지 각각 확인
- `SfnExecutionPollingOptions`의 `999.milliseconds`, `1.seconds`, `Duration.INFINITE`, `-Duration.INFINITE` 경계값을 각각 검증
- Java `SfnAsyncClient` timeout/cancellation 중 client를 닫지 않고 caller-owned lifetime 안에서 collect가 끝나는지 확인
- `SfnClientSupportTest`/`SfnAsyncClientSupportTest`에서 application-scoped factory의 `ShutdownQueue` 1회 등록을 확인하고, `withSfnClient`/`withSfnAsyncClient`는 등록하지 않은 client를 block 종료 시 닫아 반복 호출에도 queue 보유량을 늘리지 않는지 확인
- lifecycle 명시 파라미터를 builder의 다른 유효한 값이 덮어쓰는 우선순위와 service client 종료 시 parameter/builder로 전달한 외부 HTTP client를 닫지 않는지 확인
- consumer compile fixture에서 lifecycle helper의 기본/custom builder/custom HTTP client signature, `listExecutionsByStateMachine(..., builder = { ... })`, `listExecutionsByMapRun(..., builder = { ... })`가 helper로 해석되는지 확인하고 SDK member `listExecutions { ... }`는 raw escape hatch로만 별도 호출한다

`aws-kotlin`:

- `SfnRequestSupportTest`: Java와 같은 validation, `redriveFilter` 전달 및 raw input 보존
- `statusFilter=PENDING_REDRIVE` 또는 `redriveFilter`가 `mapRunArn` 없이 사용되거나 `stateMachineArn`과 함께 사용되면 최종 local validation이 차단하는지 확인
- builder callback 이후 빈 필수 ARN, 80자 초과 `name`, list source 둘 다 지정·둘 다 생략, 범위 밖 `maxResults`를 다시 검사하는지 확인
- builder callback이 명시 인자를 다른 유효한 ARN/name/source 값으로 덮어쓰면 callback 값이 최종 request에 보존되는지 확인
- `input = null`은 `"{}"`로 정규화하고, `input = ""`·공백은 거부하며, 유효 JSON은 원문 그대로 보존하는지 확인
- builder callback이 `input`을 null로 덮어쓰면 `"{}"`로 정규화하고, 빈 문자열·공백으로 덮어쓰면 최종 field-specific validation이 차단하는지 확인
- builder callback이 빈 `nextToken`을 설정해도 최종 field-specific validation이 차단하는지 확인
- `nextToken`을 쓰는 후속 page request가 source/filter 값을 그대로 유지하고, token 만료는 service의 `InvalidToken` 예외로 보존하는지 확인
- native suspend extension이 request와 raw response를 그대로 연결하고, `listExecutionsByStateMachine`/`listExecutionsByMapRun`이 각각 허용된 source와 filter만 구성하는지 확인
- dedicated list helper callback이 explicit source를 다른 ARN으로 덮어쓰거나 반대 source를 추가하는 경우, state-machine helper에서 `PENDING_REDRIVE`/`redriveFilter`를 추가하는 경우를 최종 source-specific validation이 차단하는지 확인
- `describeExecutionFlow`의 초기 즉시 조회, interval delay, `DescribeExecution` 상태 응답 raw mapping 보존
- `PENDING_REDRIVE`가 raw response를 emit하고 종료하되 business success로 변환되지 않는지 확인
- collect coroutine 취소 시 `CancellationException`이 재전파되고 `StopExecution` side effect가 없는지 확인
- virtual time, `take(1)`, 느린 collector에서 호출 수와 backpressure를 확인
- immutable `DescribeExecutionRequest` overload가 `includedData` 같은 SDK field를 모든 poll에 전달하는지 확인
- client를 Flow가 닫지 않고 caller-owned lifetime 안에서만 수집하는지 확인
- Kotlin `ExecutionStatus.SdkUnknown(value)`는 응답을 emit하지 않고 정확한 `IllegalStateException` 메시지만 발생시키며 후속 호출이 없는지 확인하고, 존재하지 않는 null branch는 테스트하지 않음
- `SfnExecutionPollingOptions`의 `999.milliseconds`, `1.seconds`, `Duration.INFINITE`, `-Duration.INFINITE` 경계값을 각각 검증
- Kotlin `SfnClient` timeout/cancellation 중 client를 닫지 않고 caller-owned lifetime 안에서 collect가 끝나는지 확인
- `SfnClientSupportTest`에서 factory 설정, `withSfnClient`의 `useSafe` 종료와 block 밖 cold Flow 반환 금지 예제를 확인
- lifecycle 명시 파라미터를 builder의 다른 유효한 값이 덮어쓰는 우선순위와 service client 종료 시 parameter/builder로 전달한 외부 `HttpClientEngine`을 닫지 않는지 확인
- consumer compile fixture에서 lifecycle helper의 기본/custom builder/custom `HttpClientEngine` signature, `listExecutionsByStateMachine(..., builder = { ... })`, `listExecutionsByMapRun(..., builder = { ... })`가 helper로 해석되는지 확인하고 SDK member `listExecutions { ... }`는 raw escape hatch로만 별도 호출한다

테스트는 기존 JUnit 5, MockK, `runTest`, Kluent/assertions 관례를 사용한다. 테스트가 먼저 실패하는 것을 확인한 뒤 production code를 작성한다.

### 통합·smoke 검증

- `./gradlew :bluetape4k-aws-java:test :bluetape4k-aws-kotlin:test --no-daemon --max-workers=1`
- 변경 범위에 맞는 targeted test와 `./gradlew detekt`를 추가 실행한다.
- Gradle consumer fixture가 `sfn`을 compileOnly로 유지하는지 검증한다.
- `git diff --check`를 실행한다.
- Floci 우선 설정에서는 현재 정적 guard의 skip 결과를 확인하고 LocalStack fallback을 순차 실행한다. LocalStack에서 start/describe/stop/list 최소 smoke를 실행하며, 미지원이면 test result XML과 정확한 `live integration unverified` 근거를 결과에 남긴다.
- README/KDoc의 Java async, Kotlin suspend, `withTimeout`, cancellation, caller-owned client 수명 예제를 consumer fixture 또는 compile test로 검증한다.

## 변경 파일 예상

- `gradle/libs.versions.toml`
- `build.gradle.kts`
- `aws-java/build.gradle.kts`
- `aws-kotlin/build.gradle.kts`
- `aws-java/src/consumerFixture/kotlin/io/bluetape4k/aws/consumer/JavaServiceConsumerFixture.kt`
- `aws-kotlin/src/consumerFixture/kotlin/io/bluetape4k/aws/kotlin/consumer/KotlinServiceConsumerFixture.kt`
- `aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/`
- `aws-java/src/test/kotlin/io/bluetape4k/aws/sfn/`
- `aws-java/src/test/kotlin/io/bluetape4k/aws/sfn/AbstractSfnTest.kt`
- `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/sfn/`
- `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sfn/`
- `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sfn/AbstractSfnTest.kt`
- `README.md`, `README.ko.md`
- `aws-java/README.md`, `aws-java/README.ko.md`
- `aws-kotlin/README.md`, `aws-kotlin/README.ko.md`
- `docs/manual/en/modules/bluetape4k-aws-java.md`, `docs/manual/ko/modules/bluetape4k-aws-java.md`
- `docs/manual/en/modules/bluetape4k-aws-kotlin.md`, `docs/manual/ko/modules/bluetape4k-aws-kotlin.md`
- `docs/manual/en/guides/testing-and-operations.md`, `docs/manual/ko/guides/testing-and-operations.md`
- `CHANGELOG.md`
- 필요할 때 `WIP.md`의 Issue #313 진행 항목

README는 두 언어 페이지의 구조를 유지하고, consumer compile/runtime classpath에 `software.amazon.awssdk:sfn` 또는 `aws.sdk.kotlin:sfn`을 추가해야 한다는 compileOnly 정책과 polling/cancellation 의미를 함께 설명한다. 상세 운영 계약은 EN/KO manual에 구조를 맞춰 기록하고 README에서는 요약과 manual link만 제공한다.
실행 권한 예시는 `StartExecution` operation mapping의 `states:StartExecution`과 `states:DescribeExecution`, 그리고 helper별 `states:StopExecution`, `states:DescribeExecution`, `states:ListExecutions`로 제한한다. `statemachine`·alias/version, `execution`·`express`·labelled child, `maprun` ARN pattern별 resource statement를 분리하고 실제 ARN 범위는 consumer IAM 정책이 결정한다. Java async와 Kotlin native suspend 예제는 모두 `withTimeout`, caller-owned client 종료, Express 제한, `input == null`의 `"{}"` 정규화를 보여 준다.

## 구현 전 체크리스트

- [x] 이 문서 SPW-01~05 자체 검토와 6개 관점 독립 검토 완료
- [ ] 구현 계획 문서 작성 및 사용자 승인
- [ ] 계획 문서의 SPW-01~05 검토 완료
- [ ] 테스트를 먼저 추가하고 실패를 확인
- [ ] Java sync/async/coroutine와 Kotlin suspend API의 raw response 계약 확인
- [ ] Flow cancellation에서 자동 StopExecution이 없음을 테스트
- [ ] consumer fixture와 dependency catalog 검증
- [ ] 대상 모듈 테스트, detekt, diff-check, emulator 지원 여부 결과 수집

## 출처·결정 ledger

| 항목 | 근거 | 결정 |
|---|---|---|
| package/dependency | Scheduler 모듈과 Gradle compileOnly 정책 | `sfn` 패키지와 compileOnly service SDK |
| async bridge | Java SDK `SfnAsyncClient` + 기존 `.await()` 패턴 | `CompletableFuture` → coroutine adapter |
| polling | Kinesis cold Flow/cancellation 패턴 + 사용자 승인 | immutable request를 받는 `Flow<DescribeExecutionResponse>`와 ARN 편의 overload |
| list pagination | AWS Kotlin native paginator | raw page 반환, paginator 재포장 안 함 |
| cancellation | structured concurrency와 외부 side effect 경계 | 예외 재전파, 자동 stop 안 함 |
| emulator | 저장소 Floci-first 정책, Step Functions 지원 미확인 | Floci 정적 skip guard → Sfn 전용 LocalStack fallback, 미지원 시 `live integration unverified` 근거 보고 |
