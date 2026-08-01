# 이슈 #312 Bedrock Runtime 최소 Facade 설계

작성일: 2026-07-23
이슈: #312 `feat(aws): add Bedrock Runtime minimal facade`
마일스톤: 0.5.0
저장소: `bluetape4k-aws`

## 문제

`bluetape4k-aws`는 AWS Java SDK v2와 AWS Kotlin SDK의 주요 서비스에 대해
클라이언트 생성, 요청 생성, 비동기 호출의 suspend 변환, coroutine `Flow`
확장을 제공한다. 그러나 Amazon Bedrock Runtime의 모델 호출에는 대응하는
도우미가 없다.

사용자가 현재 직접 해결해야 하는 항목은 다음과 같다.

- Java SDK v2의 `CompletableFuture` 및 event-stream callback을 coroutine으로
  연결한다.
- Kotlin SDK의 `Converse`와 `ConverseStream` 호출을 기존
  `bluetape4k-aws-kotlin` 클라이언트 생명주기 방식에 맞춘다.
- 두 SDK에서 서로 다른 streaming 이벤트 API를 다루면서도 모델별 prompt
  프레임워크에 종속되지 않는다.
- 응답의 텍스트 블록과 streaming text delta를 반복적인 타입 분기 없이
  추출한다.

이 변경은 Bedrock용 고수준 AI 프레임워크를 만드는 작업이 아니다. 목표는
AWS SDK의 `Converse` 계약을 보존하면서 bluetape4k의 coroutine-first 사용
방식에 맞는 최소 facade를 제공하는 것이다.

## 현재 근거

- Issue #312는 모델 ID에 중립적인 `Converse`와 streaming 응답, 작은
  request/response mapping helper, 기존 AWS 기본 설정과 일치하는 client
  helper를 요구한다.
- `aws-java`의 서비스 SDK dependency는 `compileOnly`이며 async API는
  `CompletableFuture.await()`로 suspend 함수에 연결한다.
- `aws-kotlin`은 native suspend API를 직접 사용하고, caller-owned client와
  `withXxxClient { }` 생명주기 helper를 구분한다.
- `aws-java`는 이미 `bluetape4k-coroutines`와
  `kotlinx-coroutines-reactive`를 `compileOnly`로 사용한다.
- `aws-kotlin`은 `bluetape4k-coroutines`를 API dependency로 제공한다.
- AWS Java SDK v2의 `ConverseStream`은
  `SdkPublisher<ConverseStreamOutput>`을 response handler에 전달하며,
  consumer는 publisher를 구독하고 처리 가능한 만큼 요청해야 한다.
- AWS Kotlin SDK의 `ConverseStream` 응답은 native
  `Flow<ConverseStreamOutput>`을 제공한다.
- 변경 전 검증은 다음 환경 보정과 함께 통과했다.
  - `JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true`
  - `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`
  - `--no-configuration-cache`
  - `:bluetape4k-aws-java:test`와 `:bluetape4k-aws-kotlin:test`:
    통과 306건, 보류 14건

## 제약

- Java와 Kotlin 양쪽 모두 AWS SDK request, response, streaming event 타입을
  공개 계약으로 유지한다.
- Bluetape 전용 prompt DTO, conversation DTO, provider adapter 계층을 만들지
  않는다.
- Bedrock Runtime service dependency는 기존 정책대로 production에서
  `compileOnly`로 둔다.
- 일반 `Converse` 호출과 `ConverseStream`만 이번 범위에 포함한다.
- `InvokeModel`, Agents for Bedrock, Knowledge Bases, guardrail 오케스트레이션,
  tool execution loop, prompt persistence는 포함하지 않는다.
- 외부에서 전달받은 client는 닫지 않는다.
- Kotlin의 `withBedrockRuntimeClient { }`만 자신이 만든 client를 닫는다.
- AWS SDK 예외를 별도 domain exception으로 감싸지 않는다.
- facade는 자체 retry, replay, timeout, logging, metrics 또는 tracing을
  추가하지 않는다.
- prompt, message, content block, stream event, 생성 응답, credential,
  authorization header, raw SDK exception 전체를 로그에 기록하지 않는다.
- streaming `Flow`는 cold flow이며 collector마다 새 SDK 호출을 시작한다.
- Flow 경계 이후의 타입 선별과 종료 조합에는
  `bluetape4k-coroutines` 확장을 우선 사용한다.
- README 영문/한글 문서에는 각 언어에 맞는 별도 diagram을 제공한다.

## 설계 선택지

### 선택지 A: AWS SDK 타입을 보존하는 extension 중심 facade

Java와 Kotlin SDK 각각에 client/request/response/streaming extension을
추가한다. request와 response는 SDK 타입을 그대로 반환하고, 공통 facade
interface나 Bluetape DTO는 만들지 않는다.

장점:

- 기존 서비스 module 패턴과 일치한다.
- 새 SDK 기능이 추가되어도 wrapper model의 동기화 부담이 없다.
- Java/Kotlin SDK 고유의 builder 및 sealed event 장점을 유지한다.
- provider-specific prompt framework로 범위가 커지는 것을 막는다.

단점:

- Java SDK v2와 Kotlin SDK의 타입은 서로 호환되지 않는다.
- 두 모듈의 API 이름과 동작 계약을 테스트와 문서로 맞춰야 한다.

### 선택지 B: 공통 Bluetape request/response DTO

Java와 Kotlin 모듈이 공유하는 prompt, message, response, stream event DTO를
도입하고 양쪽 SDK 타입으로 변환한다.

초기 사용은 단순해질 수 있으나 Bedrock의 content block, tool use, guardrail,
reasoning content, metrics가 확장될 때 wrapper가 SDK를 불완전하게 복제하게
된다. 사용자가 선택한 native SDK 타입 보존 원칙에도 어긋난다.

이번 변경에서는 제외한다.

### 선택지 C: Spring AI 또는 provider-specific prompt framework

Bedrock Converse를 Spring AI나 별도 provider abstraction 뒤에 숨긴다.

이는 dependency와 runtime 범위를 크게 늘리고 `aws-java` 및 `aws-kotlin`
core wrapper의 책임을 벗어난다. 이번 변경에서는 제외한다.

## 선택한 설계

선택지 A를 구현한다.

### Dependency 및 package

- Version catalog에 다음 service alias를 추가한다.
  - AWS Java SDK v2용 Bedrock Runtime
  - AWS Kotlin SDK용 Bedrock Runtime
- 새 version 상수를 만들지 않고 repository의 기존 `aws2` 및 `aws-kotlin`
  version authority를 그대로 사용한다.
- root `build.gradle.kts`의 dependency-management/constraint 목록에도 Java와
  Kotlin Bedrock Runtime artifact를 각각 등록한다.
- `aws-java`
  - 프로덕션: Java Bedrock Runtime SDK `compileOnly`
  - test: 같은 SDK `testImplementation`
  - 패키지: `io.bluetape4k.aws.bedrock`
  - 모델 도우미 패키지: `io.bluetape4k.aws.bedrock.model`
- `aws-kotlin`
  - 프로덕션: Kotlin Bedrock Runtime SDK `compileOnly`
  - test: 같은 SDK `testImplementation`
  - 패키지: `io.bluetape4k.aws.kotlin.bedrock`
  - 모델 도우미 패키지: `io.bluetape4k.aws.kotlin.bedrock.model`

Bedrock control-plane client가 아니라 Bedrock Runtime client만 추가한다.
`compileOnly` service SDK는 published runtime dependency가 아니므로, 이
facade를 사용하는 application은 자신이 선택한 SDK 계열의 Bedrock Runtime
dependency를 compile/runtime classpath에 직접 추가해야 한다. 영문/한글
README에는 `bluetape4k-dependencies`와 함께 이 consumer dependency 계약을
명시한다.

- `aws-java` streaming 사용자는 Bedrock Runtime SDK와 함께
  `bluetape4k-coroutines` 및 `kotlinx-coroutines-reactive` runtime을
  추가한다.
- `aws-kotlin`은 `bluetape4k-coroutines`를 API dependency로 전달하지만
  Kotlin Bedrock Runtime service SDK는 consumer가 직접 추가한다.

### Client 생명주기

`aws-java`에는 기존 service support 패턴과 같은 helper를 둔다.

- 동기 `BedrockRuntimeClient`
- 비동기 `BedrockRuntimeAsyncClient`
- endpoint, region, `AwsCredentialsProvider`, HTTP client, 추가 builder block
  지원
- helper가 생성한 Java client는 기존 `ShutdownQueue` 정책에 등록하고
  application-scoped 재사용을 기본으로 한다. caller가 명시적으로 먼저
  `close()`해도 되며 종료 시 중복 close는 안전해야 한다.
- extension receiver로 전달된 Java client는 항상 caller-owned이며 facade가
  닫지 않는다.

`aws-kotlin`에는 다음 계약을 둔다.

- `bedrockRuntimeClientOf(...)`는 caller-owned client를 반환한다.
- `withBedrockRuntimeClient(...)`는 client를 만들고 block 완료 후
  `useSafe`로 닫는다.
- `withBedrockRuntimeClient(...)` block 안에서 streaming Flow collection을
  완료해야 한다. block 밖으로 cold Flow를 반환해 나중에 collect하는 사용은
  client가 이미 닫히므로 지원하지 않는다.
- endpoint, region, `CredentialsProvider`, HTTP engine, 추가 builder block을
  지원한다.
- secret/access-key 문자열 overload를 만들지 않고 SDK credential provider만
  받는다.
- endpoint override는 명시적인 opt-in이다. 기본값은 AWS SDK endpoint와
  default credential provider chain이다.
- endpoint override와 credential provider 선택은 caller-owned trust
  boundary다. endpoint를 request parameter 같은 신뢰할 수 없는 입력에서
  만들지 않으며, credential과 결합하는 override는 caller가 명시적으로
  신뢰한 AWS-compatible endpoint여야 한다.
- endpoint helper는 HTTPS를 기본 허용하며 plain HTTP는 loopback 기반 test
  endpoint에서만 명시적으로 허용한다.

### 요청 도우미

request helper는 새로운 DTO를 만들지 않고 SDK model을 조립한다.

- 사용자 text content block 생성
- user role message 생성
- `modelId`, messages, 선택적 inference configuration을 받는
  `ConverseRequest` 생성
- 같은 입력 의미를 갖는 `ConverseStreamRequest` 생성
- blank `modelId`, 빈 message 목록, blank text를 조기에 거부
- SDK builder block으로 advanced field를 추가할 수 있게 유지

Java SDK의 text content block은 generated `ContentBlock.Builder`를 사용한다.
AWS Kotlin SDK의 `ContentBlock`은 builder가 없는 sealed class이므로 text
helper는 `ContentBlock.Text(text)`를 반환하고 별도 builder parameter를
노출하지 않는다.

helper는 특정 모델 ID, provider 이름, prompt template 또는 기본 temperature를
강제하지 않는다. inference 기본값은 AWS SDK와 호출자가 소유한다. builder
block을 먼저 적용한 뒤 helper-owned 필수 입력인 text, user role, `modelId`,
messages를 마지막에 설정한다. `inferenceConfig` 인자가 non-null이면 builder
값을 마지막에 덮어쓰고, null이면 builder가 설정한 값을 보존한다. 따라서
advanced field는 추가할 수 있지만 helper 이름과 명시적 인자가 약속한 필수
의미는 덮어쓸 수 없다.

### 일반 Converse

raw request 호출은 AWS SDK member가 이미 제공하므로 같은 signature의
extension을 중복 정의하지 않는다. Bluetape extension은 검증된 편의 인자를
SDK request로 조립하는 overload만 제공한다.

`aws-java`는 기존 naming convention에 따라 세 경로를 제공한다.

- 동기 클라이언트: `converse(...)`
- 비동기 future: `converseAsync(...)`
- 비동기 클라이언트 코루틴 어댑터: suspend `converse(...)`

`aws-kotlin`은 native suspend SDK member를 호출하는 편의 인자
`converse(...)`만 추가한다. 결과는 모두 SDK `ConverseResponse` 그대로다.

### 공개 API sketch

아래 선언은 이번 변경의 호환성 기준이다. import는 각 module의 native SDK
package를 사용하며 생략된 visibility는 public이다.

Java SDK 모듈 클라이언트 API:

```kotlin
inline fun bedrockRuntimeClient(
    builder: BedrockRuntimeClientBuilder.() -> Unit,
): BedrockRuntimeClient

inline fun bedrockRuntimeClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: BedrockRuntimeClientBuilder.() -> Unit = {},
): BedrockRuntimeClient

inline fun bedrockRuntimeAsyncClient(
    builder: BedrockRuntimeAsyncClientBuilder.() -> Unit,
): BedrockRuntimeAsyncClient

inline fun bedrockRuntimeAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: BedrockRuntimeAsyncClientBuilder.() -> Unit = {},
): BedrockRuntimeAsyncClient
```

Java SDK module model 및 operation API:

```kotlin
inline fun contentBlockOf(
    text: String,
    builder: ContentBlock.Builder.() -> Unit = {},
): ContentBlock

inline fun userMessageOf(
    text: String,
    builder: Message.Builder.() -> Unit = {},
): Message

inline fun converseRequestOf(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseRequest

inline fun converseStreamRequestOf(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseStreamRequest.Builder.() -> Unit = {},
): ConverseStreamRequest

inline fun BedrockRuntimeClient.converse(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseResponse

inline fun BedrockRuntimeAsyncClient.converseAsync(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseRequest.Builder.() -> Unit = {},
): CompletableFuture<ConverseResponse>

suspend inline fun BedrockRuntimeAsyncClient.converse(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseResponse

fun BedrockRuntimeAsyncClient.converseStreamFlow(
    request: ConverseStreamRequest,
): Flow<ConverseStreamOutput>

inline fun BedrockRuntimeAsyncClient.converseStreamFlow(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseStreamRequest.Builder.() -> Unit = {},
): Flow<ConverseStreamOutput>
```

AWS Kotlin SDK module client, model 및 operation API:

```kotlin
inline fun bedrockRuntimeClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: BedrockRuntimeClient.Config.Builder.() -> Unit = {},
): BedrockRuntimeClient

suspend fun <R> withBedrockRuntimeClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    builder: BedrockRuntimeClient.Config.Builder.() -> Unit = {},
    block: suspend (BedrockRuntimeClient) -> R,
): R

fun contentBlockOf(text: String): ContentBlock

inline fun userMessageOf(
    text: String,
    crossinline builder: Message.Builder.() -> Unit = {},
): Message

inline fun converseRequestOf(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    crossinline builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseRequest

inline fun converseStreamRequestOf(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    crossinline builder: ConverseStreamRequest.Builder.() -> Unit = {},
): ConverseStreamRequest

suspend inline fun BedrockRuntimeClient.converse(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    crossinline builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseResponse

fun BedrockRuntimeClient.converseStreamFlow(
    request: ConverseStreamRequest,
): Flow<ConverseStreamOutput>

inline fun BedrockRuntimeClient.converseStreamFlow(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    crossinline builder: ConverseStreamRequest.Builder.() -> Unit = {},
): Flow<ConverseStreamOutput>
```

두 module에 다음 response extensions를 같은 의미로 둔다.

```kotlin
fun ConverseResponse.textContents(): List<String>
fun ConverseResponse.firstTextOrNull(): String?
fun ConverseResponse.textOrEmpty(separator: String = ""): String
fun ConverseStreamOutput.textDeltaOrNull(): String?
fun Flow<ConverseStreamOutput>.textDeltaFlow(): Flow<String>
```

raw request 호출은 native SDK member를 사용하고 같은 signature의 extension을
만들지 않는다. `converseStreamFlow` 이름은 Java response-handler overload와
Kotlin generated streaming function의 import/호출 혼동을 피한다.

### 스트리밍 Converse

#### Java SDK v2 구현

`BedrockRuntimeAsyncClient` extension은
`Flow<ConverseStreamOutput>`을 반환한다.

- collection 시점에 `converseStream`을 시작하는 cold flow다.
- response handler가 받은 `SdkPublisher<ConverseStreamOutput>`을
  `kotlinx-coroutines-reactive.asFlow().buffer(0)` 경계로 변환한다.
- rendezvous capacity는 reactive subscription의 `request(1)`로 이어져
  최대 outstanding demand를 1개로 제한한다.
- event는 publisher 순서대로 방출한다.
- operation future 완료를 기다렸다가 일괄 방출하지 않고 publisher event가
  도착하는 즉시 incremental emission을 시작한다.
- collector cancellation은 reactive subscription과 진행 중 future를
  취소한다.
- facade 자체는 SDK 호출을 retry하거나 재수집하지 않는다. SDK client에
  설정된 retry가 새 publisher callback을 전달할 수는 있다.
- 전체 operation에는 active publisher collection을 최대 하나만 둔다. 새
  callback이 오면 generation token을 증가시키고 이전 attempt를 먼저
  cancel-once한 뒤 교체한다. 이전 generation의 late event/error/complete는
  버린다.
- 이미 downstream에 방출한 이전 attempt의 partial event는 회수하지 않는다.
  SDK retry가 partial emission 뒤 새 publisher를 제공하면 새 attempt가 같은
  의미의 event를 다시 방출할 수 있으므로 exactly-once나 semantic deduplication을
  보장하지 않는다. facade는 attempt를 전부 버퍼링하거나 SDK event를
  추론해 제거하지 않는다.
- attempt publisher 오류만으로 전체 Flow를 조기 종료하지 않는다. 최종
  operation future의 결과가 성공 또는 최종 SDK 오류를 결정한다.
- complete, 최종 error, collector cancel은 원자적 first-terminal-wins
  상태로 처리한다. terminal 이후 callback, event, complete, error는
  무시하고 새 publisher는 즉시 취소한다.
- collector cancellation이 유발한 future failure나
  `CancellationException`은 downstream SDK failure로 다시 방출하지 않는다.

상태는 operation future와 최신 publisher generation을 함께 추적한다.

| 현재 상태 | 신호 | 동작 | 다음 상태 |
|---|---|---|---|
| operation active, publisher 없음 | 첫 `onEventStream` | generation 1을 collect하고 `request(1)` 시작 | operation active, generation 1 active |
| operation active, generation N | retry의 새 `onEventStream` | N을 cancel-once하고 N+1로 교체 | operation active, generation N+1 active |
| operation active, generation N | N의 complete | publisher 완료를 기록하고 future를 기다림 | publisher-completed, operation active |
| operation active, generation N | N의 error | attempt error를 기록하고 retry callback 또는 future를 기다림 | publisher-failed, operation active |
| publisher-completed/failed, operation active | retry의 새 `onEventStream` | 이전 generation의 terminal을 attempt-local로 폐기하고 N+1을 collect | operation active, generation N+1 active |
| publisher active | operation future 성공 | publisher의 정상 terminal을 기다림 | operation-succeeded, publisher active |
| operation-succeeded, generation N active | 새 `onEventStream` | 새 publisher를 즉시 취소하고 현재 generation을 유지 | operation-succeeded, generation N active |
| operation-succeeded, generation N active | N의 complete | Flow 정상 완료 | completed |
| operation-succeeded, generation N active | N의 error | N의 publisher 오류로 Flow 실패 | failed |
| publisher-completed | operation future 성공 | Flow 정상 완료 | completed |
| publisher 없음 | operation future 성공 | content 없는 성공으로 Flow 완료 | completed |
| publisher-failed | operation future 성공 | 기록한 publisher 오류로 Flow 실패 | failed |
| terminal 전 상태 | operation future 최종 실패 | active subscription을 cancel-once하고 SDK 오류로 Flow 실패 | failed |
| terminal 전 상태 | collector cancel/timeout | active subscription과 operation future를 cancel-once | cancelled |
| completed/failed/cancelled | callback, event, complete, error | late signal을 무시하고 새 subscription은 즉시 취소 | 동일 terminal 상태 |

operation future 성공은 active publisher를 즉시 정상 완료시키지 않는다.
최신 generation의 정상 complete와 future 성공이 모두 관찰돼야 하며, publisher
callback이 전혀 없으면 성공 future를 empty stream으로 정규화한다. publisher의
attempt-local error는 retry 가능성 때문에 단독 terminal 신호가 아니지만,
새 generation 없이 future가 성공하면 기록한 publisher 오류를 보존한다.
future 최종 실패와 collector 취소는 즉시 terminal authority다. terminal
신호가 경쟁하면 원자적 first-terminal-wins 규칙을 적용하고 모든 terminal
transition은 남은 active subscription을 cancel-once한다.

#### AWS Kotlin SDK 구현

`BedrockRuntimeClient` extension은
`Flow<ConverseStreamOutput>`을 반환한다.

- collection 시점에 native suspend `converseStream`을 호출한다.
- response block 안의 SDK `response.stream`을 그대로 collect한다.
- stream이 없으면 정상적인 empty flow로 완료한다.
- collector cancellation은 SDK 호출과 stream collection에 전파된다.
- 별도 hot flow, external scope 또는 buffering layer를 만들지 않는다.

Kotlin SDK의 nullable `response.stream`과 Java handler의 “content가 없으면
publisher callback이 없을 수 있음” 계약은 모두 최종 SDK operation이
성공한 경우 empty flow로 정규화한다. startup 또는 최종 operation 오류는
empty flow로 바꾸지 않는다.

### bluetape4k-coroutines 활용

AWS SDK interop에 필요한 최소 경계만 SDK별 방식으로 처리하고, 이후 Flow
조합은 `bluetape4k-coroutines`를 우선 사용한다.

- raw stream은 SDK `ConverseStreamOutput`을 그대로 방출한다.
- text delta helper는
  `io.bluetape4k.coroutines.flow.extensions.castNotNull`을 이용해 content
  block delta event만 선택한다.
- 시간 또는 외부 종료 신호를 사용하는 문서 예제와 테스트는
  `takeUntil(...)`을 사용한다.
- 동일한 파이프라인을 숨기는 새 Bedrock 전용 Flow framework는 만들지
  않는다.

다음 확장은 의도적으로 사용하지 않는다.

- `FlowEvent`: SDK 예외를 값으로 바꾸지 않고 terminal failure로 보존해야
  한다.
- `Flow.log()`: raw Bedrock event에는 prompt 또는 생성 응답이 포함될 수
  있으므로 기본 helper에서 로깅하지 않는다.
- `mapParallel`/`async`: 모델 event 순서가 공개 계약이므로 기본 text delta
  처리에 병렬 변환을 적용하지 않는다.

### 응답 매핑

Java/Kotlin 양쪽에 같은 의미의 작은 response helper를 추가한다.

| Helper | Return | Empty/non-text 계약 |
|---|---|---|
| `ConverseResponse.textContents()` | `List<String>` | text block만 순서대로 반환, 없으면 empty list |
| `ConverseResponse.firstTextOrNull()` | `String?` | 첫 text, 없으면 `null` |
| `ConverseResponse.textOrEmpty(separator = "")` | `String` | 모든 text를 delimiter로 연결, 없으면 empty string |
| `ConverseStreamOutput.textDeltaOrNull()` | `String?` | text delta가 아니면 `null`; 빈 text delta는 빈 문자열로 보존 |
| `Flow<ConverseStreamOutput>.textDeltaFlow()` | `Flow<String>` | `castNotNull`로 delta event를 선별하고 non-text만 제외 |

helper는 non-text block을 오류로 취급하지 않는다. text 전용 helper에서는
non-text block을 건너뛰며, 사용자는 raw SDK event flow를 통해 tool use,
metadata, stop reason, usage 정보를 직접 처리할 수 있다.
전체 text 연결은 반복 `String +` 또는 누적 `fold`를 사용하지 않고
`joinToString`이나 `StringBuilder` 계열의 단일 pass로 수행한다.

## 오류, 취소, 생명주기

- blank 필수 입력은 `IllegalArgumentException`으로 조기 거부한다.
- AWS service/client 예외는 SDK 타입을 유지한다.
- Java async extension은 exceptional completion을 그대로 보존한다.
- Java suspend extension의 `.await()`는 coroutine cancellation을 future에
  전달한다.
- stream collector cancellation은 active subscription 및 진행 중 SDK
  호출을 취소한다.
- Kotlin client helper는 자신이 만든 client만 닫는다.
- external client extension은 client lifecycle을 소유하지 않는다.
- facade는 retry, replay 또는 기본 deadline을 추가하지 않는다. retry와
  HTTP/API attempt timeout은 AWS SDK client configuration이 소유하고,
  호출 전체 deadline은 caller의 coroutine timeout이 소유한다.
- timeout/cancellation 시 이미 받은 partial output은 되돌리거나 재방출하지
  않는다.
- broad `catch`가 필요한 구현에서는 `CancellationException`을 먼저 다시
  던진다.
- 정상 subscription 취소는 stream failure로 재포장하지 않는다.
- facade는 자동 telemetry를 만들지 않는다. 호출자는 SDK exception의 error
  code와 request ID를 이용해 관측할 수 있으며, operation, outcome, latency,
  region, exception class, request ID 같은 allowlist metadata만 기록한다.
- raw SDK exception은 호출자에게 보존하지만 외부 HTTP 응답이나 로그에
  그대로 노출하지 않도록 README에서 경고한다.

## Diagram 설계

문서에는 Bedrock Runtime streaming lifecycle을 설명하는 sequence diagram을
추가한다.

### 독자 질문

“Collector가 Bedrock `ConverseStream`을 수집할 때 SDK event stream이 어떻게
Flow로 전달되고, 완료·오류·취소·backpressure는 어디까지 전파되는가?”

### 참여자와 흐름

- 애플리케이션 collector
- Bedrock 코루틴 확장
- `bluetape4k-coroutines` Flow 파이프라인
- Java `SdkPublisher` bridge 또는 Kotlin native SDK Flow
- Bedrock Runtime 클라이언트
- Amazon Bedrock 서비스

sequence는 다음을 표현한다.

1. collector가 cold Flow를 수집한다.
2. extension이 `ConverseStreamRequest`를 전달한다.
3. SDK가 response 및 event stream을 연다.
4. event는 순서대로 text delta pipeline에 전달된다.
5. Java bridge는 collector 처리에 맞춰 한 번에 event 하나를 요청하고,
   Kotlin은 native SDK Flow의 backpressure를 유지한다.
6. Java SDK retry가 publisher를 교체하면 이전 generation을 취소하되 이미
   방출된 partial event는 남고 새 attempt에서 의미상 중복될 수 있음을
   retry frame으로 표현한다.
7. 정상 완료와 SDK 오류를 각각 전파한다.
8. collector 취소 시 subscription 및 SDK 호출을 취소한다.

diagram에는 “각 collection은 새 Bedrock invocation이며 비용과 서로 다른 생성
결과가 발생할 수 있다”는 reader-facing 경고를 포함한다.

### 산출물

- 영문:
  - 편집 가능한 SVG
  - CairoSVG `-s 2` 기준 PNG
- 한국어:
  - 별도 reader-facing text를 갖는 editable SVG
  - CairoSVG `-s 2` 기준 PNG
- 저장 위치: `docs/images/readme-diagrams/`
- 영문 README는 영문 asset, 한글 README는 한글 asset을 참조한다.
- Amazon Bedrock card에는 repository에 포함된 AWS 공식 architecture icon을
  사용한다.

### Diagram 검증

- 기존 sequence family 두 개를 full-size PNG로 확인하고 palette와 message
  표현을 맞춘다.
- XML parse 및 CairoSVG render
- connector, geometry, endpoint, mixed-corner, sequence-style 감사
- visible numbered message, marker color, branch frame, label collision 검증
- 영문 및 한글 PNG 각각 full-size 육안 검사
- SVG와 PNG의 canonical path 및 README link 확인

## 테스트

### Dependency 및 client

- Java/Kotlin Bedrock Runtime dependency가 production에서 `compileOnly`인지
  확인한다.
- Java sync/async client helper가 region, endpoint, credentials, HTTP client,
  builder customization을 적용하는지 검증한다.
- Kotlin client helper가 같은 설정을 적용하는지 검증한다.
- caller-owned client와 `withBedrockRuntimeClient` close ownership을 검증한다.
- `withBedrockRuntimeClient` block 내부 collect의 성공, 오류, 취소에서
  close-once를 검증하고, cold Flow를 block 밖으로 반환하는 사용이 지원되지
  않음을 문서 예제로 고정한다.
- default AWS endpoint/credential chain과 explicit endpoint override를
  구분하고, non-loopback HTTP endpoint 거부를 검증한다.

### Request 및 response

- 모델 ID, 사용자 message, content block, inference config 매핑
- 빈 모델 ID, 빈 text, 빈 message 검증
- advanced builder field 보존 및 helper-owned text/role/model/messages의
  builder 재정의 차단
- Java/Kotlin 모두 non-null `inferenceConfig` 인자가 builder 값을
  덮어쓰고, null 인자는 builder 값을 보존
- text-only response 추출
- 여러 text block의 순서 보존
- 여러 큰 text block도 단일-pass 방식으로 순서를 보존해 연결
- non-text block 무시
- streaming text delta 추출
- Java/Kotlin helper 의미의 parity

### 일반 호출

- Java sync request 전달 및 raw response 반환
- Java async future 전달 및 exceptional completion
- Java suspend `.await()` 결과와 cancellation
- Kotlin native suspend request 전달 및 raw response 반환

### 스트리밍 Flow

- collection 전에는 SDK 호출이 시작되지 않음
- collector마다 새 SDK 호출
- 재수집이 별도 invocation을 시작하고 facade가 결과를 cache/replay하지 않음
- event 순서 보존
- text delta의 순서와 non-text filtering
- empty stream 정상 완료
- SDK/publisher 오류 전파
- 정상 terminal completion
- final future가 완료되기 전에 첫 event를 즉시 수신
- fake publisher ledger 기준 `request(1)`, 최대 outstanding demand 1,
  느린 collector에서도 bounded queue 유지
- active subscription 최대 1개, retry publisher 교체 시 이전 subscription
  cancel-once, 이전 generation의 late event 중복 방출 없음
- partial emission 뒤 SDK retry가 동일 의미의 event를 다시 보낼 수 있으며
  exactly-once/deduplication을 보장하지 않음
- future 성공과 latest publisher complete의 순서가 바뀌어도 두 신호를 모두
  기다리고, publisher error 뒤 retry 유무에 따라 최종 상태를 단 한 번 결정
- complete/error/cancel first-terminal-wins 및 terminal 이후 callback/event
  무시
- cancellation-before-publisher와 callback-during-cancellation에서 late
  publisher 즉시 취소
- collector cancellation이 subscription과 active call에 전달됨
- caller coroutine timeout이 subscription과 active call에 전달되며 facade가
  별도 retry/recollection을 시작하지 않음
- `takeUntil(...)` 예제는 종료 신호 뒤 다음 upstream event에서 수집을
  끝내는 해당 extension 계약을 검증
- client를 extension이 임의로 닫지 않음

기본 테스트는 fake/stub client 및 publisher로 수행한다. Bedrock은 account,
model access, region 가용성이 필요하므로 real AWS smoke test는 opt-in으로
제공하며 기본 CI gate로 요구하지 않는다. ordinary test task는 항상 JUnit
`bedrock-smoke` tag를 제외한다. `-PbedrockSmoke`와
`BEDROCK_REGION`/`BEDROCK_MODEL_ID`가 모두 있을 때만 tag를 포함하며, 입력이
하나라도 없으면 client 생성 전에 명시적인 skip reason을 남긴다. 두 SDK
lane은 다음 aggregate command로 실행한다.

```bash
BEDROCK_REGION=us-east-1 BEDROCK_MODEL_ID=... \
./gradlew \
  :bluetape4k-aws-java:test \
  :bluetape4k-aws-kotlin:test \
  -PbedrockSmoke \
  --no-daemon \
  --no-configuration-cache
```

smoke는 normal credential provider chain을 사용한다. 요청·출력·timeout을
작게 제한하고 raw prompt/response를 기록하지 않는다. evidence에는 pass/fail,
duration, request ID, 승인된 region/model 식별자 또는 skip reason만 남긴다.
local emulator는 mandatory gate로 두지 않는다.

## 문서

- `aws-java/README.md`와 `README.ko.md`
  - Bedrock Runtime 의존성
  - 동기, 비동기, suspend `Converse`
  - streaming raw event 및 text delta Flow
  - 호출자 소유 클라이언트 생명주기
- `aws-kotlin/README.md`와 `README.ko.md`
  - 네이티브 suspend `Converse`
  - native streaming Flow와 text delta pipeline
  - `withBedrockRuntimeClient`
- 공개 API에는 영어 KDoc을 작성한다.
- 각 module README는 consumer가 Bedrock Runtime SDK dependency를 직접
  추가해야 함을 명시한다.
- Java/Kotlin별 복사 가능한 Gradle dependency snippet과
  dependency → client → request → 단일 terminal collection 순서의 예제를
  제공한다. Java 예제에는 coroutine/reactive runtime dependency를 포함한다.
- cold Flow는 collection마다 새 원격 추론과 비용을 발생시킬 수 있으므로
  재수집, `first()` 후 두 번째 collection, 암묵적 retry 예제를 사용하지
  않는다.
- Java SDK 내부 retry 뒤에는 이미 전달된 partial event와 의미상 중복되는
  event가 올 수 있다. facade가 exactly-once/deduplication을 제공하지 않음을
  README와 KDoc에 명시한다. Streaming delta를 transactional side effect나
  자동 tool 실행의 exactly-once 입력으로 사용하지 않는다. 원자적인 단일
  결과가 필요하면 non-streaming `Converse`를 사용하고, application이 retry
  정책을 소유해야 한다.
- `withBedrockRuntimeClient` 예제는 block 안에서 collection을 완료하고,
  block 밖에서 collection하려면 caller-owned client를 사용하는 경로를
  기본 예제로 제시한다.
- 생성 text와 tool-use block은 신뢰할 수 없는 입력으로 취급한다. HTML,
  command, query 또는 tool argument로 사용할 때 output-context escaping,
  schema/allowlist validation, 별도 tool authorization이 필요하며 facade는
  prompt-injection 방어, output sanitization, model allowlist 또는 자동 tool
  실행을 제공하지 않는다.
- application은 허용된 model/inference-profile ID, region, account access와
  최소 IAM 권한을 소유하고 제한해야 한다.
- endpoint override와 credentials는 하나의 caller-owned trust boundary다.
  신뢰할 수 없는 요청 값으로 endpoint를 만들지 않고, credentials는
  명시적으로 신뢰한 AWS-compatible HTTPS endpoint에만 전송해야 한다.
- root README의 service coverage 또는 module summary가 실제 구현 범위와
  어긋나는 경우에만 최소 변경한다.
- `WIP.md`의 issue 상태는 live GitHub/PR 상태와 일치시킨다.
- English/Korean sequence diagram을 각 locale README에 연결한다.
- Type A lesson에는 Java event-stream bridge, Kotlin native Flow,
  `bluetape4k-coroutines` 재사용 경계, JDK 25/Colima baseline 보정을 기록한다.

## 호환성, release 및 rollback

- 기존 공개 API는 변경하지 않는 additive change이며 별도 migration은 없다.
- Bedrock helper를 사용하지 않는 consumer에는 새 service SDK가 runtime
  transitive dependency로 추가되지 않는다.
- helper를 사용하는 consumer는 Java 또는 Kotlin Bedrock Runtime SDK를
  직접 추가해야 하며, 양쪽 SDK 타입 간 source compatibility를 약속하지
  않는다.
- published metadata inspection과 최소 consumer compile smoke로 Java의
  service/coroutine/reactive dependency 및 Kotlin의 service dependency
  계약을 검증한다.
- `0.5.0` release 전에는 alias, source, tests, docs, diagram을 함께 revert해
  rollback할 수 있다.
- release 후 artifact는 변경하지 않는다. 결함은 호환 가능한 patch로
  수정하고, 공개 API 제거가 필요하면 이후 release에서 deprecation 절차를
  거친다.
- durable evidence에는 dependency resolution, targeted/full test 명령,
  detekt/diff check, diagram audit, optional smoke 결과 또는 skip reason,
  exact commit/PR head를 남긴다.

## 위험과 완화

1. **Event-stream bridge의 취소 누락**
   - 위험: collector가 취소되어도 Java subscription 또는 SDK future가 계속
     실행될 수 있다.
   - 완화: subscription과 future를 명시적으로 추적하고 cancellation
     테스트를 blocking gate로 둔다.

2. **무제한 buffering 또는 backpressure 손실**
   - 위험: 생성 속도가 collector 처리 속도보다 빠르면 memory 사용이
     증가한다.
   - 완화: Java bridge를 `asFlow().buffer(0)`으로 고정해 request size와
     outstanding demand를 1로 제한하고 fake publisher ledger로 검증한다.

3. **SDK event model 축소**
   - 위험: text helper만 노출하면 tool use, metrics, stop reason을 잃는다.
   - 완화: raw SDK event Flow를 주 API로 두고 text delta는 선택적 mapping
     helper로 둔다.

4. **Java/Kotlin facade 불일치**
   - 위험: 같은 이름의 helper가 다른 validation 또는 lifecycle 의미를 가질
     수 있다.
   - 완화: 의미 기반 parity 테스트와 README 예제를 함께 검토한다.

5. **민감 데이터 logging**
   - 위험: prompt, 생성 결과, raw event, credential 또는 raw exception이
     debug log나 외부 응답에 남을 수 있다.
   - 완화: `Flow.log()`와 자동 telemetry를 사용하지 않고 allowlist metadata
     경계를 문서화한다.

6. **Diagram과 구현 불일치**
   - 위험: diagram이 실제 subscription/cancellation 경계를 과장할 수 있다.
   - 완화: 구현 완료 후 source를 다시 읽고 participant 및 message를 확정한
     뒤 asset을 제작한다.

7. **환경 특이 baseline 실패**
   - 위험: JDK 25 attach 정책 또는 Colima socket 경로 때문에 회귀와 무관한
     test failure가 발생할 수 있다.
   - 완화: 검증 명령에 확인된 환경 보정을 사용하고 lesson 및 DoD에 근거를
     남긴다.

8. **Streaming retry의 의미상 중복**
   - 위험: Java SDK가 partial emission 뒤 새 publisher로 retry하면 이전
     partial output과 새 attempt output이 의미상 겹칠 수 있다.
   - 완화: generation별 late signal만 차단하고 exactly-once를 주장하지
     않으며, diagram/README/KDoc에서 소비자에게 명시한다.

## Step 2-R 설계 검토

Developer/API, stability, operator/ops, security, user/caller, performance의
6개 lens로 독립 검토하고 main session에서 통합했다.

첫 검토에서 나온 P1은 정확한 공개 signature, 소비자 의존성, backpressure,
future/publisher terminal 경쟁, retry generation, 클라이언트 소유권, Flow 이탈,
endpoint 신뢰, timeout 소유권, collection별 비용과 diagram/사용자 경고에 집중됐다. P2/P3는 observability allowlist,
opt-in smoke, release/rollback, prerequisites, compatibility까지 포함했다.

수정 후 affected lens를 다시 검토한 결과는 다음과 같다.

| Lens | P0 | P1 | 처리 |
|---|---:|---:|---|
| Developer/API | 0 | 0 | exact signature, builder precedence, generation state 확정 |
| Stability | 0 | 0 | first-terminal-wins, cancel-once, future/publisher race 확정 |
| Operator/Ops | 0 | 0 | dependency, smoke, release/rollback evidence 확정 |
| Security | 0 | 0 | endpoint/credential trust 및 untrusted output 경계 확정 |
| User/Caller | 0 | 0 | lifecycle, cost, retry duplicate, non-streaming 대안 확정 |
| Performance | 0 | 0 | `buffer(0)`, outstanding demand 1, single-pass join 확정 |

첫 검토의 P2/P3는 모두 본문, 테스트, 문서, 수용 기준에 반영했으며 구현
단계로 미룬 미해결 항목은 없다.

### 2026-07-23 Kotlin SDK 계약 정정

계획 작성 중 공식 AWS Kotlin SDK API를 다시 대조해 `ContentBlock`이
builder를 노출하지 않는 sealed class임을 확인했다. Kotlin text helper를
`contentBlockOf(text: String): ContentBlock`으로 좁히고
`ContentBlock.Text(text)`를 반환하도록 정정했다. Java SDK의 generated
builder 계약은 그대로 유지한다. 영향받는 Developer/API 및 User/Caller
lens를 재검토했으며 두 lens 모두 P0=0, P1=0으로 수렴했다.

## 수용 기준

- `aws-java`와 `aws-kotlin`에 Bedrock Runtime client/request/response helper가
  추가된다.
- 두 모듈 모두 model-neutral `Converse`와 `ConverseStream` 경로를 제공한다.
- raw SDK request, response, streaming event 타입이 공개 계약으로 유지된다.
- Java streaming Flow는 publisher backpressure, completion, error,
  cancellation을 보존한다.
- Kotlin streaming Flow는 native SDK Flow와 structured cancellation을
  보존한다.
- text mapping은 `bluetape4k-coroutines`의 타입 선별 확장을 재사용한다.
- service SDK dependency는 production에서 `compileOnly`다.
- service SDK alias는 기존 version authority를 사용하고 published metadata에
  runtime-transitive service SDK를 추가하지 않는다.
- README는 consumer의 Bedrock Runtime SDK dependency와 IAM/model/region
  책임을 명시한다.
- README는 endpoint override/credential trust boundary와 non-loopback
  plain HTTP 거부를 경고한다.
- README/KDoc/diagram은 cold Flow 재수집이 별도 billable invocation임을
  경고하고 단일 terminal collection 예제를 제공한다.
- Java bridge는 `request(1)`, active subscription 최대 1개,
  first-terminal-wins, incremental first event, retry callback 교체,
  generation 격리, cancel-once 계약을 검증한다.
- Java bridge는 SDK retry 전 이미 방출된 partial event를 회수하거나
  exactly-once/deduplication을 보장하지 않는다.
- Java README/KDoc와 sequence diagram은 retry generation 교체, 이전
  subscription cancel, late signal 폐기, 이미 방출된 partial output의
  semantic duplicate 가능성 및 non-streaming 대안을 함께 설명한다.
- facade는 자체 retry/replay/timeout을 추가하지 않으며 caller timeout이
  upstream cancellation으로 전파된다.
- facade는 generated output을 신뢰하거나 자동 tool 실행하지 않는다.
- targeted module tests, coroutine cancellation tests, detekt 및
  `git diff --check`가 통과한다.
- real AWS smoke test는 opt-in이며 기본 완료 조건이 아니다.
- 영문/한글 README와 각 locale의 SVG/PNG sequence diagram이 구현과
  일치한다.
- diagram XML/render/audit/full-size inspection이 통과한다.
- Type A lesson과 Step DoD에 재사용 가능한 학습 및 검증 근거가 기록된다.
- Step 2-R, Step 3-R, Step 6-R, Step 7-R review gate가 P0/P1 = 0으로
  수렴한다.
- PR 본문의 마지막 `##` section은 `## DoD Status`다.
