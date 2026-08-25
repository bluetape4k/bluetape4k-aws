# SNS batch 실행 전략·메시지 변환기 확장 설계

> 대상 이슈: #541
> 관련 조사 이슈: #514 (완료)
> 기준 브랜치: `develop` (`fe24e60204d74d730bd189d2c67f260b1d834f79`)
> 작업 브랜치: `feat/issue-541-sns-batch-extensions`
> 설계 단계: 사용자 승인 완료, 구현 전 명세 검토 대기
> 작성일: 2026-08-24

## 문제와 목표

`aws-spring-boot`에는 이미 SNS batch 요청·결과 모델과 내부
`SnsBatchExecutor`가 있다. 현재 실행 정책은 `SnsCoroutinesTemplate` 안에
직접 연결되어 있어 애플리케이션이 순차 실행, bounded 실행, 다른 스케줄러
정책을 선택할 수 없다. 또한 Spring `Message<T>`를 기존 typed
`SnsPublishBatchEntry`로 바꾸는 SNS 전용 public 계약이 없어, 호출자가
header·payload·FIFO 필드를 직접 조립해야 한다.

이번 설계는 이 두 확장을 하나의 Type-A 이슈 안에서 두 단계로 고정한다.

1. **Phase 1 — public batch execution strategy**: 현재 executor의 안전 의미를
   public strategy 경계로 노출하고, 기존 생성자·`SnsOperations`·typed 결과
   계약을 보존한다.
2. **Phase 2 — `SnsBatchMessageConverter`**: Spring `Message<*>`를 typed
   `SnsPublishBatchEntry`로 바꾸는 converter와 명시적 payload serializer를
   추가한다. `spring-messaging`은 opt-in `compileOnly` 의존성으로만 둔다.

두 단계는 같은 이슈와 feature branch에서 추적하되, 구현과 검토는 **Strategy
PR → Converter PR** 순서의 두 개 독립 변경으로 나누는 것을 권장한다. 각 PR은
단독으로 컴파일·테스트·ABI 검증이 가능해야 하며, 현재 설계 단계에서는 PR
생성·merge·publish를 수행하지 않는다.

## 독자와 성공 기준

이 문서는 다음 독자가 구현 전에 같은 경계를 합의하도록 하는 기준 문서다.

- `aws-spring-boot` SNS API를 사용하는 Kotlin/Spring 애플리케이션 개발자
- public ABI와 compileOnly 의존성을 검토하는 유지보수자
- batch 실행·취소·부분 성공·민감 정보 비노출을 검증하는 테스트 담당자

설계의 성공 기준은 다음과 같다.

- 기존 `SnsCoroutinesTemplate(SnsAsyncClient, SnsProperties)` 생성자와 기존
  `SnsOperations` 구현체의 source/binary 호환성을 보존한다.
- strategy가 실행 정책을 바꾸어도 10개 제한, 입력 순서, typed partial
  result, protocol guard, transport redaction, cancellation identity와
  sibling cleanup을 우회할 수 없다.
- converter는 모든 entry를 네트워크 호출 전에 변환하며, 하나라도 실패하면
  SNS 호출이 0회다.
- 기존 SNS 사용자에게 Spring Messaging runtime 의존성을 강제하지 않는다.
- 테스트·ABI·클래스패스·문서 결과를 PR별로 재현 가능한 증거로 남긴다.

## 현재 근거와 책임 경계

### 저장소 근거

| 근거 | 현재 확인 | 설계 영향 |
| --- | --- | --- |
| `SnsOperations.kt` | batch default method는 기존 `publish`를 순차 호출하고 첫 실패에서 중단한다. cancellation identity를 보존하며 transport 예외를 redacted wrapper로 정규화한다. | 기존 구현체 호환 fallback을 유지하고 strategy로 대체하지 않는다. |
| `SnsCoroutinesTemplate.kt` | 2-인자 생성자와 `SnsAsyncClient` 기반 단건·batch 매핑이 public 사용 경로다. | 기존 JVM 생성자를 보존하고 명시적 3-인자 주입 경로만 추가한다. |
| `SnsBatchExecutor.kt` | 10개 chunk, bounded worker, input-relative order, 응답 ID 검증, sibling 취소가 내부에 구현되어 있다. | 기본 strategy는 이 executor를 재사용하거나 같은 경계 안에서 adapter로 감싼다. |
| `SnsBatchModels.kt` | request가 `topicArn`·entry를 단일 객체로 소유하고 ID 중복·FIFO 규칙을 조기 검증한다. | `topicArn`을 strategy 인자로 중복 전달하지 않는다. |
| `SnsBatchExceptions.kt` | transport/protocol 예외가 payload·ARN·원문 오류를 노출하지 않지만 `completedEntryIds`는 현재 entry ID를 bounded metadata로 보관한다. | converter는 UUID ID만 허용하고 새 strategy/converter 예외는 원시 ID·민감정보를 추가로 보관하지 않는다. |
| SQS converter 계층 | `io.bluetape4k.aws.spring.sqs`에 inbound SNS notification용 `SnsMessageConverter`가 이미 있다. | 이름 충돌을 피하기 위해 새 converter는 SNS package의 `SnsBatchMessageConverter`로 고정한다. |
| `aws-spring-boot/build.gradle.kts` | AWS SDK·Spring context·Jackson3는 compileOnly이고 `spring-messaging`은 없다. | `spring-messaging` catalog alias와 compileOnly opt-in만 추가한다. |
| consumer/ABI fixture | 기존 `SnsOperations` 구현체와 classpath fixture가 있다. | 새 public 타입이 기존 consumer classpath를 깨뜨리지 않는지 별도 검증한다. |

### 외부 계약 근거

다음 공식 문서를 2026-08-24에 확인해 AWS 서비스·Spring 타입의 경계를
고정했다. 문서가 정의하지 않는 retry, idempotency, serializer 정책은 이
설계에서 새로 약속하지 않는다.

- [Amazon SNS `PublishBatch` API](https://docs.aws.amazon.com/sns/latest/api/API_PublishBatch.html): 한 요청의 최대 10개 entry, 성공·실패 항목 응답, 개별·전체 payload 262,144-byte 제한
- [AWS SDK for Java v2 `PublishBatchRequest`](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/sns/model/PublishBatchRequest.html): typed request와 `topicArn`·entry 모델
- [Spring Framework `Message` Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/messaging/Message.html): payload와 immutable header 접근 경계

현재 모듈은 `spring-messaging`을 runtime에 노출하지 않는다. 따라서 converter를
사용하는 애플리케이션만 해당 Spring artifact를 직접 추가하며, converter를
사용하지 않는 기존 SNS 호출자는 기존 classpath 그대로 동작한다.

### 책임 경계

- **typed model**: 입력 검증, 10개 제한의 단위, FIFO/standard 규칙, ID 고유성,
  redacted 결과·예외 모델을 소유한다.
- **strategy**: guarded port를 어떤 순서와 coroutine 구조로 호출할지 선택하지만
  chunk 크기·worker 상한·typed model과 executor의 안전 불변식을 변경하지 않는다.
- **template/SDK adapter**: AWS SDK request 매핑과 suspend lifecycle만 담당한다.
- **converter**: Spring `Message`의 allowlisted metadata와 payload를 typed
  entry로 변환한다. 네트워크 전송이나 재시도는 담당하지 않는다.
- **호출자**: topic ARN, batch entry의 고유 ID, serializer 정책, 재시도·중복
  처리 결정을 제공한다.

## 승인된 결정

### 1. Strategy 주입: 기존 생성자 보존 + 명시적 constructor

사용자가 승인한 1번 선택을 채택한다. 기존 주 생성자와 기존 default 경로를
그대로 둔 채, 별도 public strategy 인터페이스와 **명시적 3-인자 secondary
constructor**로 주입한다. factory는 추가하지 않는다. `SnsOperations.publishBatch`의
새 overload에 strategy를 끼워 넣지 않는다. 그러면 호출자 API가 실행 정책과
요청 데이터를 동시에 알아야 하고, 기존 default method·consumer fixture의
호환 표면이 불필요하게 넓어진다.

strategy는 전체 AWS 실행 권한을 외부에 넘기는 함수가 아니다. library-owned
`SnsBatchExecutionPort`만 호출할 수 있고, 실제 chunking·SDK 매핑·응답 검증·
redaction은 port와 내부 executor가 소유한다. 설계 API는 다음 형태를 기준으로
한다.

```kotlin
public interface SnsBatchExecutionPort {
    public suspend fun publishChunk(
        entries: List<SnsPublishBatchEntry>,
    ): SnsPublishBatchResult
}

public fun interface SnsBatchExecutionStrategy {
    public suspend fun execute(
        request: SnsPublishBatchRequest,
        options: SnsBatchExecutionOptions,
        port: SnsBatchExecutionPort,
    ): SnsPublishBatchResult
}

public enum class SnsBatchExecutionContractError {
    INVALID_CHUNK,
    DUPLICATE_CLAIM,
    TOO_MANY_IN_FLIGHT,
    INVALID_RESULT,
    STRATEGY_FAILURE,
}

public class SnsBatchExecutionContractException(
    public val error: SnsBatchExecutionContractError,
) : IllegalStateException("SNS batch execution contract failed: error=$error")
```

`request.topicArn`이 topic ARN의 유일한 source of truth다. 초안에 있던
`execute(topicArn, request, options)`처럼 같은 값을 두 번 전달하는 형태는
불일치·검증 우회를 만들 수 있으므로 채택하지 않는다.

`SnsCoroutinesTemplate`는 다음 JVM 생성자 의미를 보장한다.

- 기존 `(SnsAsyncClient, SnsProperties)` 생성자는 그대로 로드·호출된다.
- 새 `(SnsAsyncClient, SnsProperties, SnsBatchExecutionStrategy)` 경로는
  명시적 정책 주입을 제공한다.
- 2-인자 경로는 기존 executor를 감싼 `DefaultSnsBatchExecutionStrategy`를
  내부에서 선택한다. 구현은 이 adapter를 guarded port에 연결한다.

구현 시 Kotlin default parameter를 public 생성자에 추가하지 않는다. 기존
JVM descriptor는 `(SnsAsyncClient, SnsProperties)`로 유지하고, 새 descriptor는
`(SnsAsyncClient, SnsProperties, SnsBatchExecutionStrategy)`로 고정한다. 구체적인
reflection/API dump와 consumer fixture로 이 두 descriptor를 검증한다.

기본 strategy는 현재 `SnsBatchExecutor`를 단일 실행 경계로 재사용한다. guarded
port는 다음을 **호출 시점에 강제**한다.

1. `entries`가 현재 request의 ID 집합에 속하고 1~10개인지 검사한다.
2. 이미 claim된 ID나 중복 ID를 네트워크 전에 거부한다.
3. `maxInFlightBatches`를 넘는 호출은 대기 작업 목록(queue)에 넣지 않고 bounded contract
   오류로 거부해 무제한 pending coroutine을 만들지 않는다.
4. AWS SDK 매핑, 응답 ID의 unknown·duplicate·missing 검증, 입력 순서 정규화,
   redacted transport/protocol 예외를 내부 executor가 수행한다.
5. caller `CancellationException` identity와 structured child cleanup을
   보존한다.
6. raw `SnsAsyncClient`, `CompletableFuture`, credential, retry/backoff를
   strategy에 노출하지 않는다.

strategy가 반환한 aggregate result도 template이 request ID 집합과 대조한다.
누락·중복·unknown result는 성공으로 허용하지 않고 contract/protocol 오류로
거부한다. 따라서 사용자 strategy는 scheduling 순서와 chunk 호출 시점만
선택할 수 있고, 핵심 안전 불변식은 구현체의 선의에 의존하지 않는다.

strategy 자체가 `CancellationException` 이외의 예외를 던지면 template은 원인
예외를 보존하지 않고 `STRATEGY_FAILURE` contract error로 정규화한다. guarded
port가 만든 redacted transport/protocol 예외와 cancellation만 각자의 계약대로
전파한다.

### 2. Converter 선택: SNS package + `spring-messaging` compileOnly

두 번째 승인 선택은 다음과 같다.

- 이름: `io.bluetape4k.aws.spring.sns.SnsBatchMessageConverter`
- 입력: Spring `org.springframework.messaging.Message<*>`
- 출력: typed `SnsPublishBatchEntry` 또는 전체 `SnsPublishBatchRequest`
- dependency: version catalog의 `spring-messaging` alias를
  `aws-spring-boot`에 `compileOnly`로 추가
- runtime: converter 사용자가 `spring-messaging`을 직접 제공하고, converter를
  사용하지 않는 기존 SNS 사용자에게는 어떤 runtime dependency도 추가하지 않음

새 타입은 SQS의 inbound `SnsMessageConverter`와 package·역할을 분리한다.
일반 Spring `MessageConverter`의 임의 대상 타입 변환을 그대로 노출하기보다,
SNS batch entry와 allowlist header만 다루는 좁은 계약으로 둔다. 이 경계는
한 메시지를 SNS entry로 변환한다는 목적과 typed validation을 명확히 하며,
SQS notification 역직렬화와 혼동하지 않게 한다.

```kotlin
public fun interface SnsPayloadSerializer {
    public suspend fun serialize(payload: Any?): String
}

public data class SnsBatchMessageConversionOptions(
    public val maxMessages: Int = 10_000,
)

public class SnsBatchMessageConverter(
    private val serializer: SnsPayloadSerializer,
) {
    public constructor() : this(SnsPayloadSerializer { payload ->
        require(payload is String) { "SNS batch payload must be String or use an explicit serializer." }
        payload
    })

    public suspend fun convert(message: Message<*>): SnsPublishBatchEntry

    public suspend fun convertAll(
        topicArn: String,
        messages: Collection<Message<*>>,
        options: SnsBatchMessageConversionOptions = SnsBatchMessageConversionOptions(),
    ): SnsPublishBatchRequest
}
```

위 API의 public constructor와 descriptor는 이 명세에서 고정한다. `Message`는
`spring-messaging`의 compileOnly 타입이며 converter를 참조하는 consumer만
해당 runtime을 제공한다. `SnsPayloadSerializer`와 변환 메서드는 `suspend`라서
항목 사이 cancellation을 관찰할 수 있다. serializer 자체도 bounded하고
blocking 호출을 수행하지 않는다는 KDoc 계약을 둔다. 핵심은 다음 두 가지다.

converter constructor descriptor는 `()`와 `(SnsPayloadSerializer)` 두 개로
고정한다. default constructor는 String-only serializer를 사용하고, 구조화
payload는 두 번째 constructor에서 명시적 serializer를 주입한다.

- 기본 payload는 `String`만 허용한다. 구조화 객체는 호출자가
  `SnsPayloadSerializer`를 명시한다.
- 첫 도입 범위에서 `ByteArray`와 mandatory Jackson/Jackson3 자동 직렬화는
  제외한다. Jackson3 adapter가 필요하면 별도 opt-in 후속 이슈로 분리한다.

`convertAll`은 유한한 `Collection`만 받고, `maxMessages`를 serializer 호출
전에 검사한다. 기본 상한은 10,000개이며 초과 입력은 conversion error로
거부한다. 각 항목 전후에 `ensureActive()`를 수행하고, 모든 메시지를 변환한
뒤 전체 ID 고유성·topic/FIFO 규칙을 검증해 하나의
`SnsPublishBatchRequest`를 만든다. strategy나 template에 network callback을
넘기지 않으며 converter 자체는 SNS 호출을 수행하지 않는다. 따라서 batch
변환 중 어느 하나라도 실패하거나 취소되면 request가 생성되지 않고 network
call count는 0이다. 무한 iterable은 API 타입에서 허용하지 않는다. 보유 비용은
항목 수와 직렬화된 payload 총 길이에 비례하며, `maxMessages`는 항목 수만
제한한다. `maxMessages`는 1 이상이어야 하며, 잘못된
옵션은 serializer를 호출하기 전에 `INVALID_OPTIONS`로 거부한다.

SNS가 요구하는 개별·전체 batch payload 262,144-byte 제한의 정확한 wire-size
계산은 이번 converter 범위에서 새로 구현하지 않는다. serializer는 큰 payload를
스스로 제한해야 하고, executor가 AWS 응답의 size 오류를 기존 redacted
transport/protocol 경계로 반환한다. byte-size preflight는 구조화 payload와 함께
별도 후속 이슈로 분리하며, 이번 설계는 해당 성능·처리량을 주장하지 않는다.

#### Header allowlist

Spring header 전체를 SNS attribute로 복사하지 않는다. 허용 목록은 typed model이
지원하는 값에 한정한다.

| 목적 | 허용 source | typed 대상 |
| --- | --- | --- |
| entry ID | `SnsBatchMessageHeaders.MESSAGE_ID`의 `UUID` 또는 `MessageHeaders.ID`의 `UUID` fallback | `SnsPublishBatchEntry.id` |
| subject | `SnsBatchMessageHeaders.SUBJECT`의 `String` | `subject` |
| message attributes | `SnsBatchMessageHeaders.MESSAGE_ATTRIBUTES`의 `Map<String, MessageAttributeValue>` | `messageAttributes` |
| FIFO group | `MessageGroupId` | `messageGroupId` |
| FIFO deduplication | `MessageDeduplicationId` | `messageDeduplicationId` |

converter가 제공하는 exact key는 다음 상수로 고정한다. key 비교는
case-sensitive이며 별칭이나 대소문자 정규화를 하지 않는다.

```kotlin
public object SnsBatchMessageHeaders {
    public const val MESSAGE_ID: String = "bluetape4k.sns.messageId"
    public const val SUBJECT: String = "bluetape4k.sns.subject"
    public const val MESSAGE_ATTRIBUTES: String = "bluetape4k.sns.messageAttributes"
    public const val MESSAGE_GROUP_ID: String = "MessageGroupId"
    public const val MESSAGE_DEDUPLICATION_ID: String = "MessageDeduplicationId"
}
```

`SnsBatchMessageHeaders.MESSAGE_ID`가 있으면 fallback보다 우선하며 값은
`UUID` 타입만 허용한다. 두 ID가 모두 없거나 `UUID`가 아닌 값이면
`MISSING_ID` 또는 `INVALID_ID_TYPE` 오류로 거부하고 `String.valueOf()`나
임의 `toString()`으로 변환하지 않는다. `MessageHeaders.ID`는 entry 식별자에만
사용하고 SNS message attribute로 복사하지 않는다. `timestamp`, tracing
header, arbitrary application header, credential, Spring 내부 header도 자동
전달하지 않는다. 허용 key는 case-sensitive 상수로 고정하며 allowlist 밖의
header는 무시한다.

Message attributes의 값은 이미 `software.amazon.awssdk.services.sns.model.MessageAttributeValue`
로 준비된 map만 허용한다. converter는 map과 각 entry를 defensive copy하고,
빈 key·잘못된 value type·null entry를 `INVALID_ATTRIBUTES` 오류로 거부한다.
임의 객체를 attribute 값으로 직렬화하거나 header를 문자열로 추측하는 동작은
이번 범위에 포함하지 않는다.

변환 오류는 다음 public safe contract를 사용한다.

```kotlin
public enum class SnsBatchMessageConversionError {
    INVALID_TOPIC,
    INVALID_OPTIONS,
    MISSING_ID,
    INVALID_ID_TYPE,
    INVALID_HEADER_TYPE,
    INVALID_ATTRIBUTES,
    INVALID_FIFO,
    SERIALIZATION_FAILED,
    ITERATION_FAILED,
    DUPLICATE_ID,
    TOO_MANY_MESSAGES,
}

public enum class SnsBatchMessageConversionField {
    MESSAGE_ID,
    SUBJECT,
    MESSAGE_ATTRIBUTES,
    MESSAGE_GROUP_ID,
    MESSAGE_DEDUPLICATION_ID,
    PAYLOAD,
}

public class SnsBatchMessageConversionException(
    public val entryIndex: Int?,
    public val error: SnsBatchMessageConversionError,
    public val field: SnsBatchMessageConversionField?,
) : IllegalArgumentException(
    "SNS batch message conversion failed: error=$error, entryIndex=$entryIndex, field=$field",
) {
    override fun toString(): String =
        "SnsBatchMessageConversionException(error=$error, entryIndex=$entryIndex, field=$field)"
}
```

topic 검증·serializer/header/iterator 예외는 `CancellationException`만 동일
instance로 재전파하고, 그 밖의 원인은 `SnsBatchMessageConversionException`으로
감싼다. `INVALID_TOPIC`은 원본 ARN을 포함하지 않으며, iterator에서 발생한
예외는 `ITERATION_FAILED`로 정규화한다. 원인 예외·payload·header map·topic
ARN·credential을 cause, message, `toString()`에 보존하지 않는다.

#### 원자적 preflight와 오류 비노출

변환 단계는 다음 순서를 지킨다.

1. 유한 `Collection`의 크기와 `topicArn`을 검증한다.
2. 각 항목 전후에 cancellation을 확인하고 serializer·allowlist header를
   변환한다.
3. 각 entry의 ID·message·header type을 검증하고 defensive copy를 만든다.
4. 전체 ID 중복과 request의 topic/FIFO 규칙을 검증한다.
5. 모든 검증이 끝난 뒤에만 `SnsPublishBatchRequest`를 반환한다.
6. 반환된 request만 strategy에 전달한다.

serializer 또는 header 오류는 payload 본문, topic ARN, credential, 전체 header
dump를 포함하지 않는다. 오류는 `entryIndex`, 허용된 field 이름, safe type
presence처럼 low-cardinality 정보만 남긴다. 변환 예외가 transport 예외로
포장되지 않도록 network 전 단계의 typed validation/conversion failure로
구분한다.

## 데이터 흐름

```text
Message<*> 목록
    │
    ├─ SnsBatchMessageConverter.convertAll(topicArn, messages)
    │    ├─ serializer + allowlist header 매핑
    │    ├─ entry 검증·ID 고유성·FIFO 검증
    │    └─ 실패 시 request 미생성, SNS 호출 0회
    │
    └─ SnsPublishBatchRequest (topicArn 단일 출처)
         │
         └─ SnsBatchExecutionStrategy.execute(request, options, guardedPort)
              ├─ 10개 단위 chunk
              ├─ bounded port / 입력 순서 집계
              ├─ AWS SDK PublishBatch 매핑
              └─ typed result 또는 redacted protocol/transport 예외
```

Strategy는 converter를 호출하지 않는다. 이 분리는 converter의 atomic
preflight를 보장하고, typed request를 직접 만드는 기존 호출자와 Spring
Message 호출자를 같은 실행 경계로 합친다.

## 실패·취소·부분 성공 계약

### 입력·변환 실패

- 빈 입력은 `SnsPublishBatchRequest(topicArn, emptyList())`로 변환한다. 기존
  executor가 empty request를 네트워크 없이 empty result로 처리하므로, converter
  경계에서도 네트워크 호출은 0회다.
- serializer, ID, header type, FIFO 필드, 중복 ID 검증 실패는 AWS 호출 전에
  발생한다.
- 오류에는 payload·ARN·credential·raw header를 포함하지 않는다.
- 변환되지 않은 suffix를 partial network publish로 흘려보내지 않는다.

### 항목별 SNS 실패

- HTTP 200 안의 `failed` 항목은 기존 `SnsPublishBatchFailure`로 보존한다.
- `successful`·`failed` 각각은 입력 순서로 정렬하고 `entryId`로 원본과 결합한다.
- 항목별 실패는 transport failure가 아니므로 결과를 반환한다.

### Transport failure

- AWS response를 받지 못한 chunk는 `SnsBatchTransportException`으로 정규화한다.
- `completedEntryIds`는 terminal response를 받은 entry ID 집합이라는 현재 의미를
  유지하며, 성공 ID 또는 재시도 가능 ID라는 뜻으로 재해석하지 않는다.
- 병렬 경로에서 sibling을 취소할 때 이미 SNS에 도달한 entry가 있을 수 있다.
  자동 retry를 하지 않는다.

### Protocol mismatch

응답의 unknown·duplicate·missing ID를 각각 count하고
`SnsBatchProtocolException`을 발생시킨다. 원본 entry·응답 payload를 예외나
로그에 복사하지 않는다. 이 오류는 조용한 결과 유실을 막는 terminal guard다.

### Caller cancellation

호출자가 취소하면 동일한 `CancellationException` instance를 재전파하고,
in-flight sibling publisher와 결과 collector를 정리한다. cancellation을
transport wrapper로 포장하지 않는다. converter preflight 중 cancellation이
발생하면 serializer 이후의 network 호출은 시작되지 않는다.

### Strategy misuse와 복구

guarded port는 over-10 chunk, 다른 topic의 ID, duplicate claim, malformed
aggregate result를 각각 safe contract/protocol 오류로 거부한다. strategy가
`options`를 무시해도 port의 active limit과 no-queue 정책이 먼저 적용된다.
detached child/job이 발견되거나 SDK future drain이 끝나지 않으면 해당 strategy
경로를 실패로 분류하고 default strategy로 자동 전환하지 않는다. caller는
`completedEntryIds`가 없는 ID도 이미 SNS에 도달했을 수 있다고 보고 전체
request 재시도를 피해야 하며, 필요한 경우 FIFO deduplication 또는 별도
idempotency 키를 준비한 뒤 명시적으로 재처리한다.

## ABI·source compatibility와 dependency 경계

1. `SnsOperations`에 strategy 인자를 추가하지 않고 현재 default
   `publishBatch(request, options)`를 유지한다.
2. `SnsCoroutinesTemplate`의 기존 JVM constructor descriptor
   `(SnsAsyncClient, SnsProperties)`를 유지하고 새 strategy 주입 descriptor
   `(SnsAsyncClient, SnsProperties, SnsBatchExecutionStrategy)`만 추가한다.
3. 기존 `NoopSnsOperations`, legacy implementation fixture, consumer classpath가
   새 public type 때문에 재컴파일·runtime load에서 깨지지 않아야 한다.
4. `spring-messaging`은 `compileOnly`이며 BOM/catalog alias를 통해 버전을
   관리한다. 기본 SNS artifact의 runtime dependency graph에는 추가하지 않는다.
5. public API에서 Jackson `ObjectMapper`나 Spring `MessageConverter` 구현체를
   mandatory constructor type으로 노출하지 않는다. converter constructor는
   dependency-neutral한 `SnsPayloadSerializer`만 받고 `spring-messaging` 타입은
   converter method 경계에만 둔다.
6. converter를 사용하지 않는 consumer가 `spring-messaging` 없이 기존 API를
   load할 수 있어야 한다. converter consumer fixture는 `spring-messaging`을
   명시적으로 추가한 별도 classpath에서만 load한다.

ABI 검증은 Kotlin public API dump, JVM constructor reflection/descriptor 확인,
기존 precompiled consumer fixture load, dependency insight를 모두 사용한다.
어느 하나라도 실패하면 구현을 계속하지 않고 public boundary를 다시 설계한다.

### Client와 coroutine lifecycle

`SnsCoroutinesTemplate`가 받은 `SnsAsyncClient`는 호출자가 소유한다. template와
strategy는 client를 생성·교체·close하지 않으며, 애플리케이션 scope client의
`close()`는 애플리케이션 lifecycle이 담당한다. guarded port는 template의
coroutine scope에서만 AWS future를 await하고, 별도 `GlobalScope`·detached
executor·raw thread를 만들지 않는다. cancellation 또는 transport failure가
발생하면 child publisher와 SDK future의 취소/정리 완료를 기다린 뒤 원래
예외를 반환한다.

`SnsBatchExecutionOptions.maxInFlightBatches`는 양수여야 하며, guarded port가
허용하는 active claim의 상한이다. port는 내부 queue를 제공하지 않으므로 상한을
넘는 strategy 호출은 queue에 쌓지 않고 `SnsBatchExecutionContractException`으로
즉시 실패한다. 따라서 수용된 `active chunk + pending claim`의 합은 항상
`min(maxInFlightBatches, chunkCount)` 이하이며, custom strategy가 옵션을
무시해도 무제한 pending 작업이 늘지 않는다. 구현은 이 경계를 port 내부의
원자적 claim으로 보장하고, 거부된 호출은 AWS SDK를 실행하지 않는다.

## 대안과 기각 사유

### 대안 A — `SnsOperations.publishBatch`에 strategy 인자 추가

`publishBatch(request, options, strategy)` 형태는 호출 지점에서 정책을 고를 수
있지만 interface method와 모든 구현체·fixture의 ABI 표면을 늘린다. 기존
default fallback과 public SPI가 뒤섞이고, 잘못된 구현이 safety invariant를
우회할 위험이 커서 기각한다.

### 대안 B — `SnsCoroutinesTemplate` 내부 strategy만 private로 유지

현재 구현 변경은 작지만 애플리케이션이 실행 정책을 주입할 수 없다. issue
목표인 public extension contract를 해결하지 못하므로 기각한다.

### 대안 C — `spring-messaging`을 api/runtime dependency로 강제

Spring `Message`를 편하게 노출할 수 있지만 typed SNS batch만 사용하는
기존 소비자까지 Spring Messaging classpath와 전이 의존성을 요구한다. 모듈의
기존 compileOnly 원칙과 ABI 최소화에 어긋나므로 기각한다.

### 대안 D — Jackson3 자동 직렬화와 ByteArray를 첫 converter에 포함

구조화 payload 편의성은 높지만 serializer 버전·byte size·media type·ABI를
동시에 고정하게 된다. 현재 typed model이 String 중심이고 mandatory JSON을
추가하지 않기로 했으므로 별도 후속 이슈로 미룬다.

## 구현 단계와 PR 경계

### Phase 1 PR — Strategy

- `SnsBatchExecutionStrategy`와 default adapter를 추가한다.
- 기존 template constructor와 `SnsOperations` fallback을 보존한다.
- 명시적 3-인자 constructor와 기존 2-인자 descriptor를 reflection/API dump와
  consumer fixture로 검증한다.
- guarded port가 over-10, 다른 topic, duplicate claim, malformed result,
  over-limit in-flight 호출을 네트워크 전에 거부하는지 검증한다.
- 10개 chunk, bounded worker, ordered result, mixed result, transport/no-retry,
  cancellation identity, sibling cleanup, protocol guard, redaction, detached
  coroutine 금지 테스트를 기존 테스트와 함께 통과시킨다.

### Phase 2 PR — Converter

- `spring-messaging` catalog alias와 compileOnly 선언을 추가한다.
- `SnsPayloadSerializer`와 `SnsBatchMessageConverter`를 SNS package에 추가한다.
- exact header constants·UUID ID 우선순위·typed attributes·String 기본 payload·
  명시적 suspend serializer·atomic preflight를 테스트한다.
- serializer/header/iterator 예외의 cause·message·`toString()`에서 payload,
  header, ARN, credential이 사라지는지와 cancellation identity를 테스트한다.
- finite `Collection`, `maxMessages`, large input allocation과 preflight
  cancellation 경계를 테스트한다.
- `spring-messaging` 유무별 consumer classpath와 converter 사용 classpath를
  분리 검증한다.

PR topology는 다음으로 고정한다. Strategy PR은 현재
`feat/issue-541-sns-batch-extensions`를 head, `develop`을 base로 한다.
Converter PR은 별도 `feat/issue-541-sns-batch-converter`를 Strategy PR head에서
분기해 검토하고, Strategy merge 후 base를 `develop`으로 retarget한 뒤 exact
diff를 다시 확인한다. 두 PR은 각각 독립적으로 revert 가능해야 한다. Phase 2가
중단되어도 Phase 1 strategy와 기존 typed API는 정상 동작해야 한다. 자동 구성,
retry/backoff, Jackson adapter, ByteArray 지원은 후속 이슈 후보로 기록하되
이번 구현에 섞지 않는다.

## 테스트·검증 매트릭스

| 영역 | 검증 내용 | 증거 |
| --- | --- | --- |
| baseline | 기존 `SnsOperationsBatchCompatibilityTest`, `SnsBatchExecutorTest` 회귀 | 기준선 12개 통과, 구현 후 fresh rerun |
| strategy API | 고정 2-/3-인자 template constructor descriptor, 고정 0/1-인자 converter constructor, guarded port, default adapter | reflection/API dump + consumer fixture |
| 실행 안전성 | 10개 제한, 수용된 `active chunk + pending claim <= min(maxInFlightBatches, chunkCount)`, resident entry 상한 `10 * maxInFlightBatches`, 내부 queue 없음, 순서, mixed result, no retry, sibling cleanup | deterministic fake-publisher conformance test |
| strategy misuse | over-10, topic mismatch, duplicate claim, malformed result, over-limit 호출, detached job, raw strategy exception 거부 | adversarial strategy test |
| cancellation | caller cancellation 전파, 동일 instance, child/future drain 완료 | cancellation identity/lifecycle test |
| protocol | unknown/duplicate/missing ID count와 redaction | protocol fixture tests |
| converter mapping | exact key, UUID ID 우선순위, subject, typed attributes, FIFO group/dedup | message fixture tests |
| converter preflight | serializer/header/duplicate ID 오류 시 SNS 호출 0회 | fake publisher call-count test |
| converter bounds | finite `Collection`, `maxMessages`, 항목 수·총 payload 길이에 비례하는 resident memory와 cancellation | bounded input/large fixture test |
| payload policy | String 기본, explicit serializer, ByteArray/Jackson 비지원 경계 | contract tests |
| ABI | 기존 2-인자 constructor와 legacy consumer load | reflection + consumer fixture |
| dependency | `spring-messaging` 없는 기존 classpath, 있는 converter classpath | Gradle dependency/classloader fixture |
| emulator | Floci 우선 SNS publish batch smoke, capability gap은 명시 기록 | 순차 emulator test |
| performance boundary | fake publisher에서 `ceil(N/10)` 호출, 수용된 active chunk/pending claim 합의 상한, 내부 queue 없음, default executor와 동등한 결과 | controlled stress/conformance receipt; AWS latency benchmark는 N/A |
| static/docs | `detekt`, `git diff --check`, README/manual 양국어 parity | command receipts |

Docker-backed emulator 테스트는 공유 자원을 고려해 순차 실행한다. Floci가
지원하지 않는 기능을 LocalStack으로 조용히 대체하지 않고, capability gap을
테스트 결과에 명시한다.

성능 경계 fixture는 fake publisher와 `N=1_000`,
`maxInFlightBatches` 값 `1`, `2`, `8`을 각각 최소 3회 반복한다. 각 반복에서
호출 수는 `ceil(N / 10)`, 수용된 active chunk와 pending claim의 합은
`min(maxInFlightBatches, chunkCount)` 이하, resident entry는
`10 * maxInFlightBatches` 이하이어야 한다. port에 대기 queue가 없으므로 상한을
넘는 호출은 즉시 contract error가 되고 fake publisher 호출 수에 포함되지
않는다. 이 fixture는 allocation·queue 상한과 default executor 동등성만
검증하며, 실제 AWS latency/throughput 순위를 주장하지 않는다.

## 문서·운영 변경

public API가 구현되면 다음 문서를 같은 변경 train에서 갱신한다.

- `README.md`와 `README.ko.md`: strategy 주입, converter opt-in dependency,
  String/serializer 정책, atomic preflight 예제를 양국어 구조로 맞춘다.
- `docs/manual/en`과 `docs/manual/ko`: SNS batch execution과 Message converter
  계약, migration·class path 경계를 상세히 기록한다.
- API가 추가되는 PR의 KDoc은 payload·credential을 출력하지 않는 redaction과
  cancellation/partial result 의미를 설명한다.

문서에는 실제 구현·검증이 끝나기 전에 완료 표현을 쓰지 않는다. benchmark나
throughput 개선은 측정 receipt가 있을 때만 주장한다.

현재 manual `releaseRef`는 `0.5.0` release tree에 고정되어 있다. 이번
develop 기반 변경은 manual에 `Unreleased/develop` 경계를 표시하고
`releaseRef`를 바꾸지 않는다. 구현 후 `manual_contract_test.rb`,
`export_manifest.rb --check`, 영어/한국어 구조 parity와 release-tag source
link를 검증한다. 실제 release tag에 API가 존재하지 않는 동안 안정 release
문서처럼 링크하지 않는다.

운영 전환은 explicit constructor opt-in으로 제한한다. canary 애플리케이션은
새 strategy를 주입한 template만 사용하고, 기존 2-인자 template는 계속
default strategy를 사용한다. 문제가 생기면 publish를 중단하고 in-flight
drain을 기다린 뒤 새 strategy constructor 주입을 제거해 default 경로로
되돌린다. 로그·메트릭에는 strategy name, chunk count, success/failure count,
protocol/transport error code만 남기며 payload·ARN·credential·raw ID를 tag로
사용하지 않는다. IAM/permission 오류는 redacted failure type으로 확인하고,
부분 전송은 idempotency 정책 없이 재시도하지 않는다.

## 제외 범위와 후속 이슈 후보

- mandatory Jackson/Jackson3 dependency 또는 자동 JSON 정책
- `ByteArray` payload와 media type 협상
- retry/backoff, idempotency store, deduplication 자동화
- 새로운 auto-configuration이나 기존 SQS converter 동작 변경
- AWS 계정 기반 비용·quota·heap 실측과 benchmark 결과 주장
- emulator 자체 기능 확장
- 이 설계 단계의 PR 생성, merge, release, publish

도입 가능성이 확인되면 후속 이슈로 분리할 후보는 다음과 같다.

1. Jackson3 opt-in `SnsPayloadSerializer` adapter와 content-type 정책
2. ByteArray/large payload와 SNS byte-size preflight
3. strategy별 benchmark/telemetry 및 운영 메트릭
4. retry/idempotency 정책의 별도 안전 설계

후속 이슈는 이번 구현의 public API가 실제로 안정화되고 dependency/ABI
검증이 끝난 뒤, 각각 독립된 acceptance criteria와 함께 생성한다.

## 롤백과 중단 조건

- Phase 1에서 기존 constructor/legacy consumer ABI가 깨지면 strategy public
  API를 merge하지 않고 branch를 revert할 수 있어야 한다.
- Phase 2에서 `spring-messaging` 없는 consumer load가 깨지면 converter
  dependency 경계를 즉시 제거하거나 별도 optional artifact로 이동한다.
- converter preflight가 network call 0회를 보장하지 못하면 구현을 중단하고
  batch orchestration과 conversion을 다시 분리한다.
- protocol/cancellation/redaction 회귀가 있으면 성능·편의성보다 기존 안전
  계약을 우선해 해당 변경을 되돌린다.

## 승인 기준과 DoD

### 설계 단계 DoD

- [x] 사용자 승인 1번 선택을 명시적 3-인자 secondary constructor strategy 주입으로 기록
- [x] converter 이름·package·compileOnly dependency·serializer 경계를 고정
- [x] `topicArn` 중복 전달을 제거하고 request 단일 source를 명시
- [x] strategy 불변식, converter atomic preflight, failure/cancellation/redaction 계약 기록
- [x] ABI, dependency, consumer classpath, 문서·테스트·emulator 검증 매트릭스 기록
- [ ] 독립 관점 review와 사용자 written-spec 재승인

### 구현 완료 DoD (향후 단계)

- [ ] Phase 1 strategy API와 default adapter 구현·검증
- [ ] Phase 2 converter API와 compileOnly classpath 경계 구현·검증
- [ ] 기존 typed API·executor·legacy consumer 회귀 없음
- [ ] targeted/full Gradle test, detekt, API/ABI, dependency/classloader 검증 통과
- [ ] README와 manual 영어/한국어 parity 갱신 및 writer audit 통과
- [ ] PR metadata·checks·reviews·DoD fresh read-back 후 별도 merge 승인

## 현재 상태

**PENDING —** 사용자 승인된 설계를 이 문서로 고정했으며, 다음 gate는 독립
관점 review와 사용자의 written-spec 승인이다. 그 승인이 있기 전에는
implementation plan과 운영 코드·테스트·Gradle 변경을 시작하지 않는다.

## DoD Status

- 설계 명세: **IN PROGRESS** — 본 문서 작성 완료, 독립 review/사용자 승인 대기
- 운영 코드: **PENDING** — 변경하지 않음
- 테스트: **BASELINE PASS** — 기준선 SNS batch 테스트 12개 통과
- PR/merge/release: **PENDING** — 현재 단계 권한 밖이며 수행하지 않음
