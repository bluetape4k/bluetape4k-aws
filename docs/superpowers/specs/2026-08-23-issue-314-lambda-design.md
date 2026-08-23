# 이슈 #314 AWS Lambda 호출 helper 설계

## 설계 상태

- 상태: 사용자 승인 완료, 6개 관점 검토 PASS (P0=0, P1=0)
- 범위: Epic #501의 다음 실행 항목인 Issue #314
- 기준일: 2026-08-23
- 기준 브랜치: `origin/develop` / `502bee2ea7e864fd8a7ed0b7e923961843a7bf30`
- 사용자 승인: codec 중심 설계와 명시된 제외 범위를 2026-08-23 `승인`으로 확정함
- 구현 전제: 이 문서와 후속 구현계획을 먼저 검토하고, 구현은 TDD로 진행함

## SPW 게이트

### SPW-01 — 요구사항·독자·근거 고정

- 독자는 `bluetape4k-aws-java` 또는 `bluetape4k-aws-kotlin`을 사용하는 애플리케이션 개발자와 해당 모듈의 유지보수자다.
- Issue #314의 목표는 함수 이름/ARN과 typed payload를 받아 AWS Lambda를 호출하는 얇은 core helper다.
- Java SDK v2에는 sync, `CompletableFuture`, coroutine 경로를 제공하고, AWS Kotlin SDK에는 native `suspend` 경로를 제공한다.
- payload 입력은 `ByteArray`, UTF-8 `String`, 호출자가 제공하는 generic codec으로 고정한다. Jackson은 compile-only mapper adapter로만 제공한다.
- 응답은 AWS 원본 response와 raw payload를 보존하면서 decoded value, `FunctionError`, decoded log tail을 함께 노출한다.
- 서비스 SDK 의존성은 기존 정책대로 모듈에서 `compileOnly`, 외부 consumer fixture와 테스트에서 명시적인 runtime/test dependency로 둔다.
- 독립적인 Spring Boot·Ktor facade, 함수 생성·배포·삭제, event source mapping, retry 정책, polling, S3 Tables는 범위에 포함하지 않는다.

### SPW-02 — 저장소·외부 근거

| 근거 | 확인 내용 | 설계 반영 |
|---|---|---|
| `aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/` | Java SDK v2의 client factory, sync/async/coroutine extension, `ShutdownQueue` lifecycle 패턴 | Lambda client support와 3단계 API의 기준 |
| `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/sfn/` | AWS Kotlin SDK native suspend client와 `useSafe` 범위 패턴 | Kotlin client lifecycle과 suspend API의 기준 |
| `aws-java/src/main/kotlin/io/bluetape4k/aws/core/SdkBytesSupport.kt` | Java SDK v2 `SdkBytes`의 복사/UTF-8 변환 helper | Java byte payload encode/decode와 배열 수명 경계 |
| `aws-java/build.gradle.kts`, `aws-kotlin/build.gradle.kts` | 서비스 SDK와 Jackson이 compile-only이고 test configuration이 이를 상속 | alias·dependency·consumer fixture 변경 범위 |
| `aws-java/src/consumerFixture/.../JavaServiceConsumerFixture.kt`, `aws-kotlin/src/consumerFixture/.../KotlinServiceConsumerFixture.kt` | 외부 consumer 관점의 compile 계약 | Lambda client와 typed invocation compile fixture |
| `aws-java/src/test/kotlin/io/bluetape4k/aws/AbstractAwsTest.kt`, `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/AbstractAwsTest.kt` | Floci 우선, LocalStack 명시 fallback, Lambda 서비스 등록은 없음 | deterministic unit test를 기본 DoD로 두고 emulator smoke를 조건부로 분리 |
| [AWS SDK for Java Lambda example](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-lambda.html) | Java `LambdaClient.invoke`와 `InvokeRequest`/`SdkBytes` 사용 | Java request와 sync/async response mapping |
| [AWS SDK for Kotlin Lambda Invoke](https://docs.aws.amazon.com/sdk-for-kotlin/api/latest/lambda/aws.sdk.kotlin.services.lambda/invoke.html) | `RequestResponse`, `Event`, `DryRun` 호출과 payload/log/error 응답 경계 | Kotlin suspend API와 invocation type 계약 |
| [AWS SDK for Kotlin `InvokeRequest`](https://docs.aws.amazon.com/sdk-for-kotlin/api/latest/lambda/aws.sdk.kotlin.services.lambda.model/-invoke-request/) | function name, invocation type, log type, qualifier, payload 필드 | request builder와 local invariant |
| [AWS Lambda Invoke API](https://docs.aws.amazon.com/lambda/latest/api/API_Invoke.html) | `FunctionError`, base64 `LogResult`, invocation type와 response payload의 서비스 의미 | SDK raw response를 보존하는 result와 log/error 경계 |

### SPW-03 — 대안 비교

| 대안 | 장점 | 문제 | 결정 |
|---|---|---|---|
| SDK request/response만 노출 | 구현량이 가장 적고 AWS 필드가 모두 보임 | typed payload, log tail, function error 해석이 호출자에게 분산됨 | 제외 |
| Jackson mapper를 public invocation API에 직접 노출 | JSON 호출이 짧고 타입 추론이 쉬움 | Jackson ABI와 runtime dependency가 core API에 강하게 결합되고 codec 교체가 어려움 | 제외 |
| `LambdaPayloadCodec<T>`와 `LambdaInvocationResult<T>`를 얇은 facade로 제공 | byte/string/custom/Jackson 경로를 같은 계약으로 묶고 raw AWS response를 보존함 | 모듈별 codec/result 타입이 대칭으로 중복됨 | 채택 |
| facade가 `FunctionError`를 예외로 변환 | 실패를 즉시 감지하기 쉬움 | AWS가 반환한 error payload와 `Handled`/`Unhandled` 상태를 잃고 transport failure와 함수 실행 실패를 섞음 | 제외 |

### SPW-04 — 위험·호환성·검증 기준

- AWS Lambda의 `FunctionError`는 HTTP/SDK 호출 자체의 transport failure와 다르므로 예외로 변환하지 않는다. result에서 원본 문자열과 raw payload를 보존한다.
- typed decoder는 성공 응답뿐 아니라 함수 오류 응답의 payload에도 적용한다. 따라서 호출자는 오류 payload를 같은 schema로 읽을 수 있고, decoder 실패는 원인 예외 그대로 관찰한다.
- 각 SDK의 `LogType.Tail`에 해당하는 값은 `RequestResponse` 호출에서만 허용한다. `Event` 또는 `DryRun`과 함께 요청하면 네트워크 호출 전에 거부한다.
- log tail은 AWS response의 base64 문자열을 UTF-8 문자열로 decode한다. 원본 base64 값은 SDK response에 남겨 invalid/비표준 값이 조용히 손실되지 않게 한다.
- Java typed async mapping은 변환된 `CompletableFuture`의 cancellation을 원본 SDK future에 전달한다. coroutine `await()`와 Kotlin native suspend는 caller cancellation을 그대로 전파한다.
- service SDK 예외, 인증·권한 오류, timeout, cancellation에는 facade 재시도나 wrapping을 추가하지 않는다.
- `functionName`과 `qualifier`는 blank만 local validation하고 ARN 형식·service별 문자 규칙·payload 크기는 AWS 서비스에 맡긴다.
- payload가 `null`이면 request field를 생략하고, 빈 배열/빈 문자열은 실제 빈 payload로 보낸다. Jackson codec은 빈 payload를 유효한 JSON으로 보정하지 않는다.
- Lambda client는 connection pool/thread를 소유하므로 Java factory는 `ShutdownQueue` 등록, Kotlin one-shot helper는 `useSafe` close를 적용한다. 외부 HTTP client는 helper가 닫지 않는다.
- 현재 공용 `AwsEmulatorServer` 서비스 목록에는 Lambda가 없고, 함수 배포/실행을 포함하는 deterministic fixture도 없다. 따라서 emulator smoke는 capability probe와 외부 설정된 function/권한이 모두 있을 때만 별도 tag로 실행하고, 그렇지 않으면 미지원 근거와 함께 `N/A`로 기록한다.

### SPW-05 — 승인·진행 조건

- 사용자는 2026-08-23 codec 중심 설계와 Spring Boot/Ktor·배포 lifecycle 제외 범위를 승인했다.
- 이 문서의 자체 점검과 6-lane spec review에서 P0/P1이 0이어야 구현계획으로 이동한다.
- spec review에서 public API, error/log/cancellation 의미가 바뀌면 해당 설계 부분을 다시 사용자에게 승인받는다.

## 문제와 목표

현재 저장소에는 Lambda service alias, client lifecycle helper, request DSL, typed payload 변환, response/error/log 해석이 없다. AWS SDK의 raw `Invoke`를 직접 사용하는 애플리케이션은 Java SDK v2와 AWS Kotlin SDK 사이에서 다음 계약을 중복 구현해야 한다.

1. service SDK를 외부 runtime dependency로 유지하면서 client를 안전하게 생성·종료한다.
2. `ByteArray`와 문자열을 매번 수동으로 SDK payload 타입으로 변환하지 않는다.
3. Jackson 같은 serialization library를 선택적으로 연결하되 core module이 runtime dependency를 전이하지 않게 한다.
4. 함수 실행 실패(`FunctionError`)와 호출 자체의 transport/permission failure를 구분한다.
5. `qualifier`, `InvocationType`, `LogType.Tail`을 request에 일관되게 매핑하고 원본 응답을 잃지 않는다.

목표는 두 모듈에서 동일한 개념을 제공하되 SDK-specific request/response 타입과 예외를 그대로 보존하는 것이다. 함수 생성·배포와 상위 framework integration은 이번 목표에 필요하지 않다.

## 선택한 설계

### 패키지와 dependency

| 모듈 | 공개 패키지 | SDK alias | dependency 정책 |
|---|---|---|---|
| `aws-java` | `io.bluetape4k.aws.lambda` | `libs.aws2.lambda` → `software.amazon.awssdk:lambda` | module `compileOnly`, test/consumer fixture 명시 dependency |
| `aws-kotlin` | `io.bluetape4k.aws.kotlin.lambda` | `libs.aws.kotlin.lambda` → `aws.sdk.kotlin:lambda` | module `compileOnly`, test/consumer fixture 명시 dependency |

버전은 현재 catalog와 AWS BOM/`aws-kotlin` version authority에서만 관리한다. module build script와 문서에 숫자 버전을 직접 넣지 않는다. root `build.gradle.kts`의 consumer fixture classpath에도 두 alias를 등록한다.

### Payload codec

두 모듈은 SDK 타입이 다르므로 각 public package에 대칭적인 `LambdaPayloadCodec<T>`와 `LambdaPayloadCodecs`를 제공한다. 두 타입은 source-level 개념과 이름만 대칭이며 서로 다른 module의 타입을 import하지 않는다.

```kotlin
interface LambdaPayloadCodec<T> {
    fun encode(value: T): ByteArray
    fun decode(payload: ByteArray): T
}

object LambdaPayloadCodecs {
    val bytes: LambdaPayloadCodec<ByteArray>
    val utf8: LambdaPayloadCodec<String>

    fun <T> jackson(
        objectMapper: tools.jackson.databind.ObjectMapper,
        valueType: Class<T>,
    ): LambdaPayloadCodec<T>
}
```

- `bytes`는 입력과 결과 배열을 copy하여 호출자 배열 변경이 request/result를 바꾸지 않게 한다.
- `utf8`은 `Charsets.UTF_8`을 고정하고 빈 문자열을 그대로 encode/decode한다.
- `jackson`은 caller가 제공한 `ObjectMapper`와 `Class<T>`를 사용한다. mapper 설정(예: Kotlin module), polymorphic typing, `TypeReference<T>`가 필요한 generic collection은 호출자 책임이며 이번 API는 `Class<T>` 범위만 보장한다.
- Jackson adapter는 untrusted Lambda 응답을 역직렬화할 때 unsafe default typing이나 검증되지 않은 polymorphic subtype 허용을 강제하지 않는다. 호출자는 mapper를 명시적으로 구성하고 성공·오류 payload의 schema와 허용 타입을 검증한다.
- Jackson adapter는 `compileOnly` classpath에만 존재하며 default global mapper를 설치하거나 serialization library를 runtime에 끌어오지 않는다.
- codec이 던지는 serialization/deserialization 예외는 그대로 호출자에게 전달한다. payload가 함수 오류 응답이어도 codec을 호출하므로 오류 body를 읽을 수 있다.

### Invocation result

각 모듈은 해당 SDK의 `InvokeResponse`를 포함하는 `LambdaInvocationResult<T>`를 제공한다.

```kotlin
data class LambdaInvocationResult<T>(
    val response: InvokeResponse,
    val value: T?,
    val payload: ByteArray?,
    val logTail: String?,
) {
    val statusCode: Int?
    val functionError: String?
    val hasFunctionError: Boolean
}
```

- `response`는 알 수 없는 새 SDK 필드와 원본 base64 log result를 보존하는 escape hatch다.
- `payload`는 response에서 복사한 raw bytes이며, `value`는 payload가 `null`이 아닌 경우 codec으로 decode한 값이다. response에 payload가 없으면 `value`와 `payload`는 `null`이다. 빈 payload는 `null`이 아니므로 codec에 전달한다.
- `functionError`는 SDK response의 원본 문자열이고 `hasFunctionError`는 blank가 아닌 경우 `true`다. 함수 오류는 result로 반환하며 facade가 예외로 바꾸지 않는다.
- `logTail`은 response의 base64 `logResult`를 UTF-8로 decode한 값이다. log result가 없으면 `null`이다. decode가 실패하면 `IllegalArgumentException`을 전달하고 raw response는 호출자가 별도 raw API로 확인한다.
- `statusCode`와 `functionError`는 response accessor를 그대로 노출한다. 값의 허용 범위와 `Handled`/`Unhandled`의 의미는 AWS SDK/service 계약을 따른다.

### Request builder와 local invariant

각 모듈의 `model/LambdaRequestSupport.kt`는 다음 builder를 제공한다.

```kotlin
fun invokeRequestOf(
    functionName: String,
    payload: ByteArray? = null,
    qualifier: String? = null,
    invocationType: InvocationType = RequestResponse,
    logType: LogType = None,
    builder: InvokeRequest.Builder.() -> Unit = {},
): InvokeRequest
```

실제 enum 상수명과 request builder 타입은 각 SDK에 맞춘다. 명시 인자를 먼저 적용하고 callback을 마지막에 실행한 뒤 최종 request를 검증한다. callback은 `clientContext` 등 issue 범위 밖의 AWS 필드를 확장할 수 있지만 다음 invariant를 우회할 수 없다.

- `functionName`은 blank가 아니어야 한다.
- `qualifier`가 지정되면 blank가 아니어야 한다.
- `logType == Tail`이면 `invocationType == RequestResponse`여야 한다.
- `payload`는 null이면 생략하고, 비어 있으면 그대로 전송한다. callback은 명시 인자와 codec이 만든 payload 뒤에 실행되며 최종 SDK builder 값을 결정한다. 따라서 callback이 payload를 덮어쓰는 경우 그 값이 실제 전송 값이고, typed helper는 응답 payload를 codec으로 decode한다.
- ARN 형식, payload 크기, IAM permission, 함수의 실제 존재 여부는 local validation으로 복제하지 않는다.

### Java SDK v2 client lifecycle

`aws-java`에는 기존 Step Functions/Kinesis 패턴에 맞춰 다음을 제공한다.

- `lambdaClient { }`, `lambdaClientOf(...)`: application-scoped `LambdaClient`를 만들고 `ShutdownQueue`에 등록한다.
- `withLambdaClient(...) { }`: 미등록 client를 생성하고 성공·예외·cancellation 모두에서 service client만 닫는다.
- `lambdaAsyncClient { }`, `lambdaAsyncClientOf(...)`: `LambdaAsyncClient`를 만들고 `ShutdownQueue`에 등록한다.
- `withLambdaAsyncClient(...) { }`: 미등록 async client를 block 종료 시 닫는다.

명시적인 endpoint, region, credentials provider, sync/async HTTP client를 먼저 적용하고 builder callback을 마지막에 실행한다. 전달한 HTTP client는 caller-owned이며 helper가 닫지 않는다.

`withLambdaAsyncClient { }` 블록에서 반환하는 `CompletableFuture`는 블록 안에서 `join`, `await`, 또는 동등한 완료 대기로 소비해야 한다. 블록이 반환된 뒤 helper가 client를 닫으므로, 미완료 future를 밖으로 넘기는 사용은 지원하지 않는다. 이 lifecycle 계약은 성공·예외·cancellation 테스트로 고정한다.

### Java SDK v2 invocation API

`LambdaClient`에는 다음 convenience extension을 제공한다.

```kotlin
fun LambdaClient.invokeBytes(
    functionName: String,
    payload: ByteArray? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    logType: LogType = LogType.NONE,
    builder: InvokeRequest.Builder.() -> Unit = {},
): LambdaInvocationResult<ByteArray>

fun LambdaClient.invokeString(
    functionName: String,
    payload: String? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    logType: LogType = LogType.NONE,
    builder: InvokeRequest.Builder.() -> Unit = {},
): LambdaInvocationResult<String>

fun <T> LambdaClient.invokeTyped(
    functionName: String,
    payload: T,
    codec: LambdaPayloadCodec<T>,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    logType: LogType = LogType.NONE,
    builder: InvokeRequest.Builder.() -> Unit = {},
): LambdaInvocationResult<T>
```

`LambdaAsyncClient`에는 같은 입력 계약에 `invokeBytesAsync`, `invokeStringAsync`, `invokeTypedAsync`를 추가하고 반환 타입을 `CompletableFuture<LambdaInvocationResult<T>>`로 한다. 각 async method에는 `.await()` 기반의 suspend overload를 같은 이름으로 추가한다. SDK가 제공하는 raw `invoke(InvokeRequest)`는 가리지 않으며, 고급 호출자는 `invokeRequestOf`와 raw SDK method를 직접 사용할 수 있다.

typed async 변환은 SDK future가 완료되면 response/result를 만들고, 변환 future가 취소될 때 원본 SDK future도 취소한다. 이미 완료된 response의 codec 실패는 exceptional completion으로 유지한다. `await()`는 `CancellationException`을 삼키지 않는다.

변환 future의 cancellation과 SDK future 완료가 동시에 발생하는 경계는 어느 쪽 결과도 재실행하지 않도록 단일 completion 경로로 처리한다. cancel-before-response와 response-after-cancel을 각각 테스트해 caller cancellation이 늦은 response나 codec 작업으로 되살아나지 않음을 입증한다.

### AWS Kotlin SDK client lifecycle와 invocation API

`aws-kotlin`에는 `lambdaClientOf(...)`와 `withLambdaClient(...)`를 제공한다. `lambdaClientOf`가 반환한 application-scoped client는 caller가 `close()`하고, `withLambdaClient`는 `useSafe`로 block 종료 시 service client만 닫는다. `HttpClientEngine`은 caller-owned다.

`SfnClient`와 구분되는 native suspend extension은 다음 의미를 가진다.

```kotlin
suspend fun LambdaClient.invokeBytes(
    functionName: String,
    payload: ByteArray? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.RequestResponse,
    logType: LogType = LogType.None,
    builder: InvokeRequest.Builder.() -> Unit = {},
): LambdaInvocationResult<ByteArray>

suspend fun LambdaClient.invokeString(...): LambdaInvocationResult<String>

suspend fun <T> LambdaClient.invokeTyped(
    functionName: String,
    payload: T,
    codec: LambdaPayloadCodec<T>,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.RequestResponse,
    logType: LogType = LogType.None,
    builder: InvokeRequest.Builder.() -> Unit = {},
): LambdaInvocationResult<T>
```

각 함수는 AWS Kotlin SDK의 native suspend `invoke`를 직접 호출한다. 별도의 dispatcher를 강제로 설치하거나 blocking call을 `withContext(Dispatchers.IO)`로 감싸지 않는다.

### Error, log, cancellation semantics

| 상황 | helper 계약 |
|---|---|
| blank function name/qualifier, Tail + Event/DryRun | request 생성 전에 `IllegalArgumentException` |
| credentials/permission/endpoint/transport failure | AWS SDK 예외를 그대로 전달 |
| `FunctionError` 존재 | 예외로 변환하지 않고 `LambdaInvocationResult.functionError`와 raw/decoded payload로 반환 |
| error payload codec 실패 | codec 원인 예외로 완료; raw SDK 호출 경로는 별도로 사용 가능 |
| base64 log result decode 실패 | `IllegalArgumentException`; 원본 `response.logResult()`는 보존 |
| Java async future cancellation | 변환 future와 SDK future를 함께 취소하고 `CancellationException`을 보존 |
| Java/Kotlin suspend cancellation | caller cancellation을 재전파하고 client close를 lifecycle `finally`/`useSafe`에 맡김 |
| SDK가 새 enum/response field를 반환 | raw SDK type을 보존하며 facade가 임의의 fallback 상태를 만들지 않음 |

facade는 재시도, dead-letter, invocation polling, idempotency, function deployment, payload redaction을 제공하지 않는다. helper 자체도 payload, decoded error, log tail을 자동으로 기록하지 않는다. payload와 function error를 로그로 남기는 일은 caller 책임이며 운영 문서는 민감한 입력과 응답을 기록하지 않도록 안내한다.

## 검증 설계

### Unit/contract tests

두 모듈 모두 다음 범위를 가진다.

1. request builder가 function name/qualifier/invocation type/log type/payload를 정확히 매핑하는지 확인한다.
2. callback override 후 최종 invariant가 재검증되는지 확인한다.
3. blank 입력, Tail + 비동기 invocation, null/empty payload 경계를 확인한다.
4. bytes/string/Jackson codec의 round trip, 배열 복사, malformed JSON 예외를 확인한다.
5. success response, empty response, `FunctionError` 응답, error payload, base64 log tail을 확인한다.
6. sync/async/suspend extension이 같은 request/result 계약을 사용하는지 확인한다.
7. Java typed future cancellation이 underlying future에 전달되는지, coroutine cancellation이 그대로 전파되는지 확인한다.
8. lifecycle helper가 성공·예외·cancellation에서 service client를 닫고 외부 HTTP client는 닫지 않는지 확인한다.
9. consumer fixture가 Lambda SDK alias를 외부 runtime dependency로 추가한 상태에서 client factory와 typed invocation을 컴파일하는지 확인한다.

### Emulator/real AWS boundary

구현계획의 첫 검증 task는 Floci와 명시적 LocalStack capability를 현재 환경에서 확인한다. Lambda function을 생성하거나 배포하는 side effect는 이 이슈의 권한 범위에 포함하지 않는다.

- capability와 외부 function/권한이 모두 준비된 경우에만 `lambda-smoke` tag를 opt-in 실행한다.
- capability가 없거나 function/권한 입력이 없으면 client를 만들기 전에 테스트를 제외하고, 로그와 test result XML에 이유를 남긴다.
- smoke가 없더라도 request/codec/response/error/cancellation/lifecycle unit 및 consumer compile proof가 이 이슈의 필수 DoD다.
- smoke 결과를 전체 AWS fidelity나 함수 배포 성공으로 확대 해석하지 않는다.

## 호환성·마이그레이션

- 새 alias와 helper는 additive change이며 기존 service API를 변경하지 않는다.
- 기존 consumer는 Lambda SDK를 사용하지 않는 한 추가 dependency가 필요 없다.
- Lambda SDK를 사용하는 consumer는 `software.amazon.awssdk:lambda` 또는 `aws.sdk.kotlin:lambda`를 runtime classpath에 직접 추가해야 한다.
- Jackson typed 호출자는 mapper와 `Class<T>`를 직접 전달하므로 global mapper 설치나 implicit serialization 정책에 의존하지 않는다.
- `invokeBytes`/`invokeString`은 raw request 대신 result wrapper를 반환한다. raw SDK response가 필요한 호출자는 기존 SDK `invoke(InvokeRequest)`를 사용하거나 helper가 제공하는 request builder만 재사용한다.
- Spring Boot/Ktor facade가 필요하면 이 core API가 안정화된 뒤 별도 이슈로 설계한다.

## 수용 기준과 DoD

1. `libs.aws2.lambda`와 `libs.aws.kotlin.lambda`가 catalog, 두 module dependency, root consumer fixture classpath에 등록된다.
2. Java SDK v2의 sync/async/coroutine와 AWS Kotlin SDK native suspend client lifecycle helper가 기존 close/ownership 규칙을 따른다.
3. bytes/string/custom/Jackson codec과 typed result가 두 모듈에서 대칭적으로 동작한다.
4. function name/ARN, qualifier, invocation type, log tail, `FunctionError`, error payload가 request/result 단위 테스트로 고정된다.
5. Java async cancellation, Kotlin suspend cancellation, client close 경계가 테스트로 입증된다.
6. 외부 consumer fixture가 compile-only 계약과 runtime service dependency 요구를 확인한다.
7. Floci/LocalStack Lambda capability를 확인하고, 지원하지 않으면 정확한 `N/A` 근거를 남긴다. 지원하는 경우에만 opt-in smoke를 실행한다.
8. `README.md`, `README.ko.md`, `aws-java/README*`, `aws-kotlin/README*`, 관련 manual/CHANGELOG의 service matrix와 compileOnly 사용 예제가 양 언어로 정렬된다.
9. public KDoc, design/plan/review/lesson 문서가 한국어 기술 문체와 source link를 유지한다.
10. targeted tests, consumer compile, `git diff --check`, detekt/compile 범위 검증이 통과하고 PR body는 `## DoD Status`로 끝난다.

## 미해결이 아닌 명시적 보류 사항

- `TypeReference<T>`/generic collection용 Jackson codec은 `Class<T>` contract가 안정화된 뒤 별도 additive API로 검토한다.
- Lambda function 생성·배포·삭제와 IAM policy provisioning은 이 helper의 caller-owned 경계로 남긴다.
- Spring Boot/Ktor client facade와 event source mapping은 core API 사용 사례가 확인된 후 별도 이슈로 분리한다.
- 실제 AWS smoke는 자격 증명, region, deployed function, IAM 권한이 제공되는 실행에서만 수행하며 기본 PR 검증으로 강제하지 않는다.

## 설계 문서 writer gate 결과

| Gate | 상태 | 근거 |
|---|---|---|
| SPW-01 | PASS | Issue #314 live 요구사항, 독자, 범위, source ledger와 미지원 영역을 고정함 |
| SPW-02 | PASS | 현재 Java/Kotlin Sfn·SdkBytes·consumer fixture·emulator 패턴과 AWS 공식 API 링크를 연결함 |
| SPW-03 | PASS | SDK raw, Jackson 직접 결합, codec/result facade 대안을 비교하고 선택함 |
| SPW-04 | PASS | error/log/cancellation/close/compileOnly/emulator 경계를 acceptance와 test 항목에 trace함 |
| SPW-05 | PASS | 승인된 설계 문서를 다시 읽고 제목·표·코드 fence·링크·한국어 기술 문체를 확인함 |
