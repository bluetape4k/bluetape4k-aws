# SQS batch listener와 partial acknowledgement 설계

날짜: 2026-08-11 KST  
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/454  
작업 트리: /Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/issue-454-sqs-batch-listener  
브랜치: feat/issue-454-sqs-batch-listener

## 목표

aws-spring-boot의 @SqsListener가 하나의 ReceiveMessage 응답을 하나의 coroutine-native
batch 호출로 처리하도록 확장한다. 기존 단건 listener와 SqsAcknowledgement 사용자는 변경 없이
동작하고, batch listener는 성공 항목만 삭제하거나 실패 항목을 재배달할 수 있어야 한다.

이번 설계는 이슈 #454만 다룬다. SQS visibility heartbeat(#453), SNS, Lambda, Step Functions,
Spring Cloud AWS 호환 계층은 포함하지 않는다.

## 현재 근거

- aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainer.kt는
  SqsOperations.receive 결과를 messages.forEach로 순차 단건 handler에 전달한다.
- SqsListenerMethodInvoker.kt의 ParameterPlan은 payload를 하나만 허용하고 List payload와
  batch acknowledgement를 알지 못한다.
- SqsAcknowledgement.kt는 한 receipt handle에 대해 delete 또는 changeVisibility를 수행한다.
- SqsOperations.kt는 단건 delete와 visibility 변경만 요구한다. MicrometerSqsOperations와 테스트용
  NoopSqsOperations가 이 계약을 구현한다.
- 기준선 검증에서 ./gradlew :bluetape4k-aws-spring-boot:test가 271개 테스트를 통과했다.
- AWS ReceiveMessage는 한 요청에서 1~10개 메시지를 반환하며, 요청한 수보다 적게 반환할 수 있다.
- AWS DeleteMessageBatch는 최대 10개를 삭제하고 HTTP 200이어도 응답의 Successful/Failed를
  항목별로 검사해야 한다.
- Spring Cloud AWS 3.x는 List<T>/List<Message<T>>를 batch listener 입력으로 사용하고,
  BatchAcknowledgement에서 전체 또는 부분 acknowledgement를 지원하지만, 본 저장소는 그
  API를 복제하지 않고 coroutine-native 계약을 유지한다.

## 선택한 접근

### 기존 listener 확장

SqsMessageListenerContainer, SqsListenerMethodInvoker, SqsOperations의 기존 경계를
확장한다. @SqsListener(batch = true)가 명시된 endpoint만 batch 경로를 사용한다. 단건 경로는
기존 호출·재시도·ack 흐름을 그대로 유지한다.

batch 경로의 책임은 다음과 같다.

1. poller가 받은 목록을 재정렬하거나 10개를 초과해 합치지 않는다.
2. batch handler를 한 번 호출하고, handler 재시도 시 아직 terminal acknowledgement가 되지
   않은 항목만 다음 시도에 전달한다.
3. handler 성공 후 deleteBatch 결과의 성공/실패를 보존한다.
4. 삭제 실패 항목은 삭제 성공 항목과 분리해 SQS redelivery 대상이 되도록 두고,
   errorVisibilityTimeoutSeconds가 있으면 해당 항목에만 visibility 정책을 적용한다.
5. CancellationException은 기존처럼 즉시 다시 던지고, 수신·handler·ack coroutine의 취소를
   삼키지 않는다.

### 선택하지 않은 접근

- 별도 batch container: 단건과 batch의 lifecycle, retry, interceptor 코드가 중복되고 한
  endpoint의 stop/재시작 semantics가 두 구현으로 갈라지므로 선택하지 않는다.
- Spring Cloud AWS API 복제: BatchAcknowledgement와 Spring Message<T> 전체 계약을 그대로
  복제하면 현재 coroutine-native API와 책임이 중복되고 Spring Integration 범위를 넓힌다. 필요한
  batch 결과와 partial acknowledgement만 현재 패키지의 타입으로 설계한다.

## 공개 API

아래 Kotlin 선언과 상태 규칙은 구현·KDoc·테스트가 따라야 하는 normative contract이다.
사용 예제는 동일 contract를 설명하는 illustrative code이며, 실제 프로젝트 import와
consumer fixture로 compile-check한다.

### @SqsListener

기존 속성은 유지하고 다음 두 속성을 추가한다.

~~~kotlin
annotation class SqsListener(
    val queue: String,
    val id: String = "",
    val maxMessages: Int = -1,
    val waitTimeSeconds: Int = -1,
    val visibilityTimeoutSeconds: Int = -1,
    val errorVisibilityTimeoutSeconds: Int = -1,
    val autoStartup: Boolean = true,
    val batch: Boolean = false,
    val acknowledgementMode: SqsAcknowledgementMode = SqsAcknowledgementMode.INHERIT,
)
~~~

~~~kotlin
enum class SqsAcknowledgementMode {
    /** 기존 동작: acknowledgement 매개변수가 있으면 MANUAL, 없으면 ON_SUCCESS. */
    INHERIT,
    /** handler가 정상 반환하면 framework가 성공 항목을 삭제한다. */
    ON_SUCCESS,
    /** framework가 자동으로 삭제/visibility 변경을 하지 않는다. */
    MANUAL,
}
~~~

batch 관측성의 correlation은 다음 opaque 값으로 고정한다. 값에는 queue URL, receipt handle,
message body 또는 message ID를 넣지 않는다.

~~~kotlin
data class SqsListenerBatchCorrelation(
    val generation: Long,
    val pollerId: Int,
    val batchSequence: Long,
)
~~~

`SqsListenerInterceptor`에는 `beforeReceive`/`afterReceive`와 acknowledgement 단계에
correlation-aware overload를 추가하고, 기존 메서드를 호출하는 Kotlin default bridge를 둔다.
기존 구현체는 재컴파일 없이 동작하며, built-in Micrometer는 overload에서 correlation을
사용하고 구 메서드의 per-message timer/span은 batch 경로에서 생성하지 않는다.

INHERIT가 기본값이므로 기존 애너테이션의 source/binary 사용 형태를 보존한다.
MANUAL은 batch에서는 SqsBatchAcknowledgement, 단건에서는 SqsAcknowledgement 매개변수를
요구한다. ON_SUCCESS와 수동 acknowledgement 매개변수의 조합, batch endpoint와
SqsAcknowledgement의 조합은 context 초기화 시 IllegalArgumentException으로 거부한다.
명시적 MANUAL에 acknowledgement 매개변수가 없거나 단건 endpoint에
SqsBatchAcknowledgement가 있으면 `MANUAL requires SqsBatchAcknowledgement` 또는
`SqsBatchAcknowledgement requires batch=true` fragment로 거부한다. `INHERIT` 무ack은
ON_SUCCESS, `INHERIT` + 단건 ack는 기존 MANUAL, `INHERIT` + batch ack는 batch MANUAL로
해석한다. `maxMessages`의 0·-2·11 이상과 property override도 동일한 context fail-fast
경계를 갖는다.
annotation의 `maxMessages = -1`은 `SqsProperties.Listener.maxMessages`를 상속한다. 유효한
batch 값은 최종 해석 후 1..10이며, 0·-2·11 이상은 context 초기화 시 거부한다. 따라서
ReceiveMessage의 AWS 상한을 annotation 값으로 우회하지 않는다.

### batch handler 매개변수

batch = true인 메서드는 payload 목록 하나와 선택적 SqsBatchAcknowledgement 하나만 받을 수
있다. 지원하는 payload는 다음과 같다.

~~~kotlin
import software.amazon.awssdk.services.sqs.model.Message

@SqsListener(queue = "orders", batch = true)
suspend fun handle(messages: List<SqsReceivedMessage>)

@SqsListener(queue = "orders", batch = true)
fun handle(messages: List<Message>)

@SqsListener(queue = "orders", batch = true)
suspend fun handle(messages: List<OrderPayload>, acknowledgement: SqsBatchAcknowledgement)
~~~

- List<SqsReceivedMessage>는 receipt handle과 queue metadata를 보존하므로 partial ack에
  권장된다.
- List<Message>는 AWS SDK 원본 메시지를 보존한다.
- 그 밖의 List<T>는 기존 SqsMessageConverter로 각 body를 변환한다. Kotlin `KType`의
  element classifier가 concrete non-null `Class<T>` 하나로 해석되는 invariant List만
  허용한다. raw Java List, star projection, wildcard, type variable, nullable element,
  nested generic(`List<List<T>>`)은 거부한다. converter에는 element Class만 전달하며
  임의 type metadata나 Java serialization을 해석하지 않는다.
- batch = false인 List payload, payload가 두 개 이상인 메서드, 단건
  SqsAcknowledgement를 batch 메서드에 선언한 경우는 명확한 오류로 빠르게 실패한다.
- 빈 ReceiveMessage 응답은 handler를 호출하지 않고 다음 poll을 진행한다.

동기 `fun` handler도 기존 단건 listener와 동일하게 지원하지만, 공개 예제와 권장 경로는
`suspend` 함수이다. 예를 들어 수동 partial acknowledgement는 다음처럼 작성한다.

~~~kotlin
@SqsListener(
    queue = "orders",
    batch = true,
    acknowledgementMode = SqsAcknowledgementMode.MANUAL,
)
suspend fun handle(
    messages: List<SqsReceivedMessage>,
    acknowledgement: SqsBatchAcknowledgement,
) {
    fun isAccepted(message: SqsReceivedMessage): Boolean = message.body.isNotBlank()
    val (accepted, rejected) = messages.partition(::isAccepted)
    val result = acknowledgement.acknowledge(accepted)
    check(result.operation == SqsBatchAcknowledgementOperation.ACKNOWLEDGE)
    check(result.status != SqsBatchAcknowledgementStatus.PARTIAL_FAILURE || result.failed.isNotEmpty())
    if (rejected.isNotEmpty()) {
        acknowledgement.nack(rejected, timeoutSeconds = 30)
    }
}
~~~

기존 단건 메서드는 애너테이션에 새 속성을 추가하지 않고 그대로 둔다. batch로 전환할 때는
`batch = true`와 `List<T>` payload를 추가하고, partial 처리가 필요할 때만
`SqsBatchAcknowledgement`를 추가한다. `INHERIT`는 acknowledgement 매개변수가 없으면
`ON_SUCCESS`, 있으면 `MANUAL`로 해석하므로 기존 source/binary 사용을 바꾸지 않는다.
JSON 배열 단건 payload를 받는 `OrderPayload`와 `List<OrderPayload>`는 reflection type이
다르며, 후자는 batch=true에서만 허용한다.

잘못된 endpoint/매개변수 조합은 context 초기화 시 `IllegalArgumentException`으로 fail-fast
하며, 오류 메시지는 다음 stable fragment를 포함한다: `batch=true requires a List payload`,
`batch=false does not accept List payload`, `raw List payload is not supported`,
`SqsBatchAcknowledgement requires batch=true`, `ON_SUCCESS cannot declare SqsAcknowledgement`,
`MANUAL requires SqsBatchAcknowledgement`, `batch delete supports at most 10 messages`.
`List<T>` generic 오류는 `unsupported batch element type` fragment와 parameter name/type을
포함한다. 변환 오류는 `SqsMessageConversionException(index, targetType, cause)`로 감싸고
message body와 receipt handle은 예외 문자열에 포함하지 않는다.

### batch acknowledgement

~~~kotlin
interface SqsBatchAcknowledgement {
    val pending: List<SqsReceivedMessage>
    val completed: Boolean
    suspend fun acknowledge(): SqsBatchAcknowledgementResult
    suspend fun acknowledge(messages: Collection<SqsReceivedMessage>): SqsBatchAcknowledgementResult
    suspend fun nack(
        messages: Collection<SqsReceivedMessage> = pending,
        timeoutSeconds: Int = 0,
    ): SqsBatchAcknowledgementResult
    suspend fun changeVisibility(
        messages: Collection<SqsReceivedMessage> = pending,
        timeoutSeconds: Int,
    ): SqsBatchAcknowledgementResult
}
~~~

실제 공개 시그니처는 Kotlin 기본 인자와 read-only pending 목록을 포함하며, 위 예시는 동작
계약을 나타낸다. acknowledge()는 아직 terminal 상태가 아닌 전체 항목을 대상으로 하고,
부분 함수는 전달한 항목만 대상으로 한다. `pending`은 호출 시점의 read-only snapshot이며,
각 항목은 `PENDING -> IN_FLIGHT -> ACKED` 또는 `PENDING -> IN_FLIGHT -> DEFERRED` 상태를
가진다. Delete/visibility의 항목별 실패와 미확인 응답은 `PENDING`으로 되돌아간다.
`ACKED`/`DEFERRED` 항목에 대한 반복 호출은 AWS 요청 없이 기존 결과를 반환하고, 동시에
호출된 acknowledgement 함수는 내부 Mutex로 선형화한다. 따라서 handle당 한 번만 AWS
작업을 수행하며 `completed`는 전체 항목이 `ACKED` 또는 `DEFERRED`가 되어 `pending`이
비었을 때만 true이다. 결과의 `status`는 이번 acknowledgement 호출이 대상으로 삼은
항목의 결과만 나타낸다.

각 입력 collection은 호출 전에 최대 10개인지, 중복 receipt handle이 없는지, 현재 batch에
속한 항목인지, queue URL이 같은지 검증한다. 검증 실패는 AWS 호출 전
`IllegalArgumentException`으로 종료한다. FIFO에서 같은 `MessageGroupId`의 앞 항목이
`PENDING`이면 뒤 항목의 acknowledge는 `fifo_predecessor_pending` 실패로 남기며, 앞 항목을
건너뛰는 delete를 허용하지 않는다. visibility 변경도 항목별 결과를 반환하고 실패 항목은
재시도 가능한 `PENDING`으로 보존한다.

`timeoutSeconds`는 AWS SQS가 허용하는 0..43_200 범위만 허용하며, 범위를 벗어나면
`timeoutSeconds must be between 0 and 43200` fragment와 함께 AWS 호출 전에
`IllegalArgumentException`을 던진다. `nack`의 기본 timeout은 0이다.

~~~kotlin
data class SqsBatchAcknowledgementResult(
    val operation: SqsBatchAcknowledgementOperation,
    val status: SqsBatchAcknowledgementStatus,
    val successfulMessageIds: List<String>,
    val failed: List<SqsBatchAcknowledgementFailure>,
)

enum class SqsBatchAcknowledgementOperation {
    ACKNOWLEDGE,
    NACK,
    CHANGE_VISIBILITY,
}

enum class SqsBatchAcknowledgementStatus {
    SUCCESS,
    PARTIAL_FAILURE,
    FAILURE,
}

data class SqsBatchAcknowledgementFailure(
    val messageId: String,
    val code: String?,
    val detail: String?,
    val senderFault: Boolean,
)
~~~

SqsBatchAcknowledgementResult는 AWS DeleteMessageBatch와 visibility 변경의 항목별 결과를
숨기지 않는다. `successfulMessageIds`는 receipt handle 대신 message id만 노출하며, 실패
결과에도 bearer capability인 receipt handle을 넣지 않는다. FAILURE는 대상 항목이 모두
실패한 경우이고, PARTIAL_FAILURE는 성공·실패가 섞인 경우이다. transport exception 또는
응답의 entry ID 불일치·중복·누락은 결과로 위장하지 않고 typed exception으로 다시 던져
현재 batch의 미확인 항목을 `PENDING`으로 남기고 기존 retry 정책을 적용한다. 이 결과 타입은
애플리케이션 직렬화 계약이 아니므로 `Serializable`을 구현하지 않고, 로그·trace·metric
label에는 body와 receipt handle을 절대 기록하지 않는다. 결과·실패·예외 타입의 `toString()`도
body, receipt handle, message ID, queue URL, SDK detail/cause를 포함하지 않으며, API 조회 필드는
명시적 accessor로만 사용한다.
대상이 빈 snapshot이면 AWS 호출 없이 해당 operation의 `SUCCESS`와 빈 목록을 반환하고, 이미
같은 operation에서 terminal인 항목만 지정한 호출도 동일한 cached result를 반환한다. 다른
operation의 terminal 결과를 재사용하지 않으며, `pending`과 result 내부 컬렉션은 방어적
read-only snapshot으로 반환해 caller mutation이 내부 상태를 바꾸지 못한다.
`successfulMessageIds`와 `failed`의 순서는 호출 입력의 batch 순서를 따른다.

### SqsOperations.deleteBatch

기존 구현체의 source/binary 호환성을 위해 새 메서드는 단건 delete를 순차 호출하는 기본
구현을 제공한다. SqsCoroutinesTemplate은 AWS SDK DeleteMessageBatch를 직접 호출해 한 번에
최대 10개를 처리한다.

~~~kotlin
suspend fun deleteBatch(
    queueUrl: String,
    receiptHandles: Collection<String>,
): SqsBatchDeleteResult
~~~

~~~kotlin
data class SqsBatchDeleteResult(
    val successfulEntryIds: List<String>,
    val failed: List<SqsBatchDeleteFailure>,
)

data class SqsBatchDeleteFailure(
    val entryId: String,
    val code: String?,
    val detail: String?,
    val senderFault: Boolean,
)
~~~

receiptHandles가 비어 있으면 AWS 호출 없이 빈 성공 결과를 반환하고, 10개를 초과하거나
중복 handle이 있으면 `IllegalArgumentException`을 던진다. `entry-0`부터 `entry-9`까지의
entry ID는 입력 순서로 생성되는 공개 결과 correlation key이며, `entryId -> receiptHandle`
mapping은 내부에만 보존한다. `successfulEntryIds`와 `failed.entryId`는 입력 순서를 유지하고,
호출자는 entry ID를 통해서만 결과를 상관시킨다. SDK 응답의 Successful/Failed ID가 제출한 ID와 정확히 한 번씩 일치하지 않으면
`SqsBatchDeleteProtocolException`을 던져 어떤 항목도 terminal 처리하지 않는다.
기본 구현은 기존 구현체가 반환하는 단건 `DeleteMessageResponse`를 성공으로 보고, 명시적인
AWS item-level 오류만 entry ID별 failed 항목으로 모은다. 인증·권한·네트워크·알 수 없는
예외와 `CancellationException`은 삼키지 않고 즉시 재전파하며, 이미 처리한 앞 항목의
성공 결과를 보존한다. 이 기본 구현은 source/binary 호환성용 fallback이며 현재 저장소의
Kotlin JVM default ABI 설정과 precompiled 구현체 fixture로 검증한다. AWS template 경로는
DeleteMessageBatch 1회만 호출한다. MicrometerSqsOperations는 delete_batch 작업으로
위임·계측하되 queue URL, receipt handle, body를 tag나 log에 넣지 않는다. optimized SDK
경로와 ABI fallback은 bounded `implementation.path`(`optimized`/`fallback`)으로만 구분한다.

### SqsOperations.changeVisibilityBatch

partial acknowledgement와 error visibility가 10개 항목마다 단건 round-trip을 만들지 않도록
`SqsOperations`는 다음 batch visibility 계약도 제공한다.

~~~kotlin
suspend fun changeVisibilityBatch(
    queueUrl: String,
    requests: Collection<SqsChangeVisibilityRequest>,
): SqsBatchVisibilityResult
~~~

~~~kotlin
data class SqsChangeVisibilityRequest(
    val messageId: String,
    val receiptHandle: String,
    val timeoutSeconds: Int,
)

data class SqsBatchVisibilityResult(
    val successfulMessageIds: List<String>,
    val failed: List<SqsBatchAcknowledgementFailure>,
)
~~~

기존 구현체를 위한 기본 구현은 입력 순서대로 단건 `changeVisibility`를 호출하고, AWS
template은 `ChangeMessageVisibilityBatch`를 최대 10개에 대해 한 번 호출한다. entry ID는
`entry-0..entry-9`로 bounded하게 만들며 Successful/Failed ID 집합이 입력과 정확히 일치하지
않으면 `SqsBatchVisibilityProtocolException`을 던져 결과를 terminal로 처리하지 않는다.
requests가 비어 있으면 AWS 호출 없이 빈 성공 결과를 반환하고, 10개 초과·중복 message ID/
receipt handle은 `batch visibility supports at most 10 messages` 또는
`duplicate batch visibility request` fragment로 호출 전에 거부한다. 각
`timeoutSeconds`는 0..43_200만 허용하며 범위 오류는
`timeoutSeconds must be between 0 and 43200` fragment를 사용한다.
transport·unknown exception과 `CancellationException`은 재전파하고, SDK의 명시적 item
failure만 결과에 담아 실패 항목을 `PENDING`으로 보존한다. metric operation은
`change_visibility_batch`로 구분하되 receipt handle/body/message ID/queue URL을 raw tag로
사용하지 않는다. optimized SDK 경로와 ABI fallback은 bounded `implementation.path`
(`optimized`/`fallback`)으로만 구분해 fallback round-trip degradation을 운영에서 식별한다.

## 런타임 흐름

~~~text
poller
  -> receive(maxMessages <= 10)
  -> empty list이면 다음 poll
  -> batch endpoint이면 pending = received messages
       -> converter / handler 호출
       -> ON_SUCCESS이면 성공 시 deleteBatch(pending)
       -> MANUAL이면 handler가 호출한 acknowledgement 결과만 반영
       -> 항목별 성공은 terminal 처리
       -> 항목별 실패/미확인은 pending으로 남기고 visibility 정책 적용
       -> handler/ack/visibility 실패 시 retry.maxAttempts까지 pending만 재호출
  -> 단건 endpoint이면 기존 handle(message) 경로
~~~

batch handler는 하나의 poll 결과를 하나의 invocation으로 받는다. 같은 batch 안에서 내부
메시지를 병렬 실행하지 않으며, 다음 poll은 현재 batch handler와 그 ack 처리가 끝난 후 시작한다.
기존 concurrency는 poller 수로 유지하고 poller 하나당 in-flight batch를 하나로 제한한다.
FIFO 큐에서는 수신 순서와 batch 경계를 보존한다. 같은 message group에서 앞 항목이 pending인
동안 뒤 항목은 삭제하지 않는 연속 성공 prefix 규칙을 적용한다. group 간 병렬화나 별도
group scheduler는 도입하지 않으며, visibility가 만료된 뒤의 최종 순서는 SQS 계약에 맡긴다.

처음 전달된 batch를 attempt 1로 세고, 기존 `retry.maxAttempts`가 handler, converter, delete
transport, visibility transport 실패에 공통으로 적용된다. receive transport failure는 이
batch budget에 포함하지 않고 기존 retry 설정의 initial/max backoff, multiplier, jitter를
사용해 bounded poll retry를 수행한다. `CancellationException`은 즉시 전파하고, `Error`를
포함한 fatal throwable은 retry하지 않고 listener를 중지한 뒤 상위로 전파한다. retry 시에는 아직 `ACKED`/`DEFERRED`
되지 않은 항목만 재변환·재호출한다. MANUAL handler가 정상 반환했지만 acknowledgement를
호출하지 않은 항목은 자동 삭제하지 않고 현재 attempt를 성공으로 끝내며 visibility 만료 후
재배달된다. 삭제 응답이 유실된 경우에도 서버에서 이미 삭제되었을 가능성을 숨기지 않는
at-least-once 계약을 따르므로, handler의 외부 side effect와 소비자 DTO는 idempotent 또는
message-id deduplication을 전제로 한다.

`CancellationException`과 `Error`는 receive·converter·handler·delete·visibility의 모든
phase에서 fatal이다. 이 두 종류는 retry/backoff, error visibility, 보상 acknowledgement를
시작하지 않고 원래 throwable을 보존해 전파한다. listener는 `STOPPING_RECEIVE`로 전환한 뒤
in-flight operation을 drain하고, 같은 batch에 대해 duplicate handler/AWS call을 만들지 않는다.
일반 `Exception`만 공통 batch attempt budget을 소비하며, fatal phase 테스트는 no-retry·no-new-AWS-call·
no-visibility·no-duplicate-ack를 각각 확인한다.

interceptor는 기존 SqsListenerInvocationContext를 메시지별로 생성해 기존 metric/trace
계약을 유지한다. batch에는 수신 response마다 `generation + pollerId + batchSequence` 내부
`SqsListenerBatchCorrelation`을 만들고, 기존 interceptor 메서드를 보존한 default bridge와
correlation-aware overload를 추가한다. metric tag는 `listener.id`, bounded `queue.name`,
`operation`, `outcome`, `batch.size.bucket`, bounded `implementation.path`처럼 허용 목록의
값만 사용한다. 기본 계측 단위는 poll response invocation 1회와
public acknowledgement call 1회이며, built-in Micrometer의 per-message timer/span allocation은
batch에서 생성하지 않는다. custom interceptor의 기존 per-message callback은 호환성 목적으로
호출하되, per-message observation은 해당 interceptor가 명시적으로 선택할 때만 수행한다.
batch handler/ack latency, retry count, partial failure count, visibility failure,
cancellation은 별도 counter/timer로 기록하되 body, message id, receipt handle, queue URL을
tag·trace attribute·로그에 넣지 않는다. batch span은 per-message span의 parent로 추가하지
않으며, correlation-aware batch span 하나만 만든다. metric 이름과 집계 단위는 아래
canonical 표로 고정하고, `batch.size.bucket` 경계(`0`, `1`, `2-5`, `6-10`)와 alert
owner/threshold는 다음 runbook 값으로 고정한다: `stopTimeoutMillis=30_000`, partial failure
`>1%/5m`, retry exhaustion `>0.1%/5m`, redelivery-age p95 `>80% of visibility timeout/5m`,
DLQ visible count `>0/5m`; owner `bluetape4k-sqs-oncall`, approver
`bluetape4k-release-approvers`.

## 실패와 복구

1. **converter 실패**: 실패한 index와 target type을 포함한
SqsMessageConversionException을 발생시킨다. raw message id는 로그에 기록하지 않고 bounded
redacted reference만 structured log correlation으로 사용하며, 원문 body·receipt handle은 기록하지 않는다. 삭제하지 않고
   pending 전체에 error visibility 정책을 적용하며, 설정된 retry 횟수 동안 같은 batch 경로를
   재시도한다. 기존 `SqsMessageConverter`가 허용한 concrete target type만 사용하며 Java
   serialization, 임의 polymorphic type metadata, 압축 해제 후 무제한 payload를 도입하지 않는다.
2. **handler 예외**: `CancellationException`과 `Error`는 즉시 전파하고 listener를 중지한다.
   retry/backoff, error visibility, 새 acknowledgement를 시작하지 않는다. 그 밖의 예외는
   pending을 삭제하지 않고 기존 backoff/retry를 사용한다. 최종 실패 시 pending에만 error
   visibility를 적용한다.
3. **converter/ack/visibility fatal**: converter, acknowledgement, visibility phase의
   `CancellationException`/`Error`도 동일한 fatal taxonomy를 적용한다. 이미 성공한 delete는
   되돌리지 않되, 남은 항목에 대한 추가 AWS call과 visibility 보상을 시작하지 않고 원래
   throwable을 전파한다.
4. **partial delete**: AWS 응답이 200이어도 Failed entry를 result에 기록한다. 성공 entry는
   재배달되지 않으며 실패 entry만 SQS에 남긴다. error visibility가 설정되면 실패 entry에만
   적용하고, visibility 호출의 명시적 SDK item failure만 typed per-item failure로 보존한다.
   visibility transport/unknown exception과 cancellation은 재전파하며, 성공 삭제는 되돌리지
   않는다. 실패 visibility는 pending과 retry/backoff 대상으로 남긴다.
5. **중복 receipt/빈 batch**: batch 내부 중복 receipt handle은 AWS 호출 전에 거부하여
   entry mapping ambiguity를 없앤다. 빈 목록은 no-op이며 handler와 AWS batch delete를
   호출하지 않는다.
6. **중지/취소**: `RUNNING -> STOPPING_RECEIVE -> DRAINING -> STOPPED` 상태를 사용한다.
   stop은 receive coroutine을 먼저 취소하고, generation의 handler/ack/visibility operation을
   registry로 추적해 `stopTimeoutMillis`까지 기다린 뒤 강제 취소한다. operation-start fence는
   stop 이후 새 AWS call을 금지하고, 강제 취소된 작업은 원래 CancellationException을 보존한다.
   generation token이 다른 완료 callback은 결과·metric·visibility를 갱신하지 않는다. 모든
   receive/handler/ack/visibility job과 interceptor timing map은 `finally`에서 정리한다.
   `STOPPING_RECEIVE` 또는 `DRAINING` 중 `start()`는 `listener is stopping`으로 거부하고,
   callback 이후 `STOPPED`가 된 뒤에만 새 generation으로 시작하며 이전 generation의 작업을
   재사용하지 않는다.

   container registry도 동일한 phase gate를 원자적으로 적용한다. registry의 `start(id)`는
   해당 container가 `STOPPING_RECEIVE`/`DRAINING`이거나 stop callback이 아직 완료되지 않은
   동안 `listener is stopping`으로 거부한다. registry `stop(id)` callback은 정확히 한 번만
   실행되고, `running` 조회는 `STOPPED` callback 이후에만 false가 된다. `start`와 `stop`을
   동시에 호출하는 테스트는 STOPPED 이전 새 generation·중복 callback·old AWS call이 모두
   0회인지, STOPPED 이후 한 번의 새 generation만 생성되는지 확인한다.

## 호환성·경계

- 기존 단건 @SqsListener에 새 속성 기본값을 적용하지 않는다.
- 기존 SqsAcknowledgement, SqsListenerInterceptor, SqsOperations 구현체는 새 batch API를
  사용하지 않아도 컴파일·동작해야 한다. SqsOperations.deleteBatch와
  SqsOperations.changeVisibilityBatch는 기본 구현으로 보호한다.
- `SqsOperations.deleteBatch`/`changeVisibilityBatch`의 JVM default method ABI와 precompiled 구버전 구현체 호출을
  compatibility fixture로 검증한다. annotation trailing default와 `INHERIT` 해석도 기존
  consumer bytecode/source fixture로 검증한다.
- 기존 `SqsAutoConfiguration`의 `AutoConfiguration.imports`, `@ConditionalOnClass`,
  property 조건, phase ordering, listener bean post-processor 등록 경계를 유지한다.
  AWS SDK `Message`가 없는 classpath에서는 negative `ApplicationContextRunner`가 SQS
  auto-configuration을 활성화하지 않음을 검증하고, 정상 classpath에서는 batch endpoint가
  기존 registry lifecycle에 연결되는지 검증한다.
- awspring 또는 spring-integration 의존성을 추가하지 않는다.
- AWS SDK service dependency는 현재 모듈의 compileOnly 정책을 유지한다.
- batch 결과에는 본문·receipt handle을 자동 로그로 남기지 않고, message id도 metric/trace
  label에서 제외한다. 운영 로그에는 bounded batch correlation과 redacted message reference,
  operation/outcome만 사용한다. batch는 `batch=true` 명시 endpoint의 opt-in이며, 장애 시
  receive 중지와 in-flight drain 뒤 이전 단건 handler를 재배포하거나 별도 canary endpoint로
  전환하는 구성 변경을 rollback 절차로 사용한다. 이미 삭제된 메시지는 rollback으로 복구되지
  않으므로 redrive/DLQ와 consumer idempotency를 함께 운영한다.
- 이번 작업은 README/README.ko, 영문·국문 manual, 관련 KDoc과 승인된 연구/lesson artifact를
  갱신한다. release note, PR, merge는 별도 gate다.

## 검증 수용 기준

### 단위/fake

- 10개 수신 목록이 단일 batch invocation으로 전달되고, 빈 목록은 invocation 없이 종료된다.
- List<SqsReceivedMessage>, List<Message>, List<T> 변환과 raw/잘못된 generic,
  converter 실패의 message id/index가 검증된다.
- 전체 성공, 전체 실패, partial delete 성공/실패가 result와 completed에 반영된다.
- 기본 deleteBatch의 단건 fallback과 실제 template의 SDK batch mapping이 검증된다.
- 기본 changeVisibilityBatch의 단건 fallback과 실제 template의 SDK batch mapping이 검증된다.
- entry ID 순서 변경·unknown·duplicate·missing response가 protocol exception으로 fail-closed
  되고, raw receipt handle이 result/log/metric/trace에 노출되지 않는지 검증한다.
- 동일 group의 앞 항목 실패·뒤 항목 성공에서 prefix 규칙이 지켜지고, 중복·foreign batch·
  11개 입력은 AWS 호출 전에 거부되는지 검증한다.
- ack/nack/changeVisibility의 동시·반복 호출이 handle당 한 번만 AWS 작업을 수행하고
  pending/completed 상태를 단조롭게 유지하는지 검증한다.
- retry, error visibility, cancellation, stop timeout에서 성공 삭제가 되돌아가지 않는지 검증한다.
- receive/handler/delete/visibility 중지 시 generation state machine과 stale callback 차단을
  검증한다.
- `nack`/`changeVisibility`의 성공·부분 실패·취소 결과, manual no-ack, 반복 terminal 호출과
  `completed` truth table을 검증한다.
- 기존 단건 `@SqsListener`, `SqsAcknowledgement`, JSON array DTO, `INHERIT` 동작이 batch
  opt-in 도입 후 회귀하지 않는지 검증한다.

### Floci/Testcontainers

- Floci 우선 SQS에서 같은 batch의 성공·실패를 섞어 보낸 뒤 성공 message는 중복 없이 사라지고
  실패 message만 visibility 만료 후 재배달되는지 검증한다.
- FIFO queue에서 수신 순서와 batch 경계가 handler에 전달되고, concurrent poller의 lifecycle이
  새 batch 내부 병렬 실행을 만들지 않는지 검증한다.
- 에뮬레이터가 DeleteMessageBatch 항목별 실패를 제공하지 않으면 fake 테스트를 authoritative
  partial-failure proof로 유지하고, capability gap을 기록한다.
- 실행한 Floci image/version, 명령, capability 결과를
  `.bluetape/evidence/issue-454/floci/capability-gap.json`에 기록한다. 이 JSON의 schema는
  `issue`, `status`, `retrievedAt`, `emulator.name`, `emulator.image`, `emulator.version`,
  `command`, `capabilities[]`를 필수로 하며 root `status`는 `PASS`, `PENDING`, `FAIL` 중
  하나로 고정한다. 각 capability에 `operation`, `status`, `authoritativeProof`,
  `unsupportedBehavior`, `owner`, `trackingIssue`, `expiryDate`, `recheckDate`,
  `releaseBlocking`을 요구한다. 이슈 값은 `454`, 운영 owner alias는
  `bluetape4k-sqs-oncall`, 승인자 alias는 `bluetape4k-release-approvers`로 고정한다.
  미지원 항목은 fake authoritative proof와 후속 owner를 연결한다. capability gap 판정은
  stdout/stderr 정규식이나 임의의 Docker 오류 코드로 추론하지 않는다. 각 preflight/scenario는
  `capability-marker.json`을 생성해야 하며, marker는
  `kind=floci-capability-gap`, `status=UNAVAILABLE|UNSUPPORTED`, 정확한 `operation`,
  현재 실행의 `runNonce`, 현재 명령의 `commandSha256`, 현재 실행 evidence 파일을 가리키는
  `authoritativeProof`와 그 파일의 `proofSha256`, `unsupportedBehavior`, `owner`,
  `trackingIssue`, `expiryDate`, `recheckDate`,
  `releaseBlocking`을 포함해야 한다. 실행 시작 시 이전 marker는 삭제하지 않고
  `stale/`로 이동하며, nonce·명령 hash가 현재 실행과 일치하지 않는 marker는 없는 것과
  동일하게 처리한다. marker가 없거나 schema·operation·nonce·command hash·proof 경로·proof hash가
  일치하지 않는 실패는 assertion/auth/permission을 포함한 일반 실패로 분류해 exit 1로
  fail-closed한다. 따라서 `docker image inspect` 실패도 유효한 preflight marker가 없는 한
  PENDING/LocalStack fallback으로 우회하지 않는다. capture command는
  `: "${FLOCI_IMAGE:?set exact Floci image reference}"`, `docker info`,
  `docker image inspect "$FLOCI_IMAGE"`, 그리고 각 Floci test command를 실행하고
  `template|listener|batch.stdout`, `.stderr`, `.exit`와 대응하는
  `template|listener|batch.capability-marker.json`을 같은 evidence directory에 보존한다.
  Floci 미설치·image pull 실패·operation 미지원으로 실행하는 LocalStack fallback도
  `localstack.stdout`, `localstack.stderr`, `localstack.exit`,
  `localstack-capability-gap.json`을 같은 schema와 상태 필드로 성공·실패 모두 기록하되
  권위 증거로 승격하지 않는다. `command`는 실제 재현 가능한 전체 launcher/Gradle 명령을
  shell-escaped 문자열로 보존하고, exit 0이면 `status=PASS`, 그 밖에는 `status=FAIL`로
  기록한 뒤 원래 exit code를 재전파한다. metric/trace contract assertion은
  `listener.id`만 허용하고 `listenerId`는 금지한다.

### 문서/품질

- aws-spring-boot/README.md, README.ko.md, 공개 KDoc 및 대응하는
  `docs/manual/en|ko/modules/` 페이지에 payload 유형, partial result, AWS 10개 제한,
  FIFO/재배달 의미와 실제 coroutine 예제를 기록한다. 상세 페이지에는 at-least-once,
  idempotency/DLQ, receive 중지·drain·old handler 재배포·canary/rollback runbook을 포함한다.
- metric/trace는 아래 canonical 계약을 사용한다. `queue.name`은 설정된 논리 queue 이름을
  정규화한 bounded identity이며 queue URL은 허용하지 않는다.

  | Metric | 집계 단위 | 허용 tag |
  |---|---|---|
  | `bluetape4k.sqs.batch.invocations` | poll response 1회 | `listener.id`, `queue.name`, `operation`, `outcome`, `batch.size.bucket` |
  | `bluetape4k.sqs.batch.acknowledgements` | public ack/nack/visibility call 1회 | 위 tags + `implementation.path` |
  | `bluetape4k.sqs.batch.handler.duration` | handler invocation 1회 | `listener.id`, `queue.name`, `outcome`, `batch.size.bucket` |
  | `bluetape4k.sqs.batch.retry` | batch attempt 1회 | `listener.id`, `queue.name`, `outcome`, `batch.size.bucket` |
  | `bluetape4k.sqs.batch.partial.failures` | partial result 1회 | `listener.id`, `queue.name`, `operation`, `batch.size.bucket`, `implementation.path` |
  | `bluetape4k.sqs.batch.visibility.failures` | explicit item failure 1회 | `listener.id`, `queue.name`, `operation`, `outcome`, `implementation.path` |
  | `bluetape4k.sqs.batch.cancellations` | cancellation 1회 | `listener.id`, `queue.name`, `operation`, `batch.size.bucket` |
  | `bluetape4k.sqs.batch.redelivery.age` | redelivery age histogram observation 1회 | `listener.id`, `queue.name`, `outcome`, `batch.size.bucket` |

  `batch.size.bucket`은 `0`, `1`, `2-5`, `6-10`, `implementation.path`는 `optimized` 또는
  `fallback`만 허용한다. redelivery age는 SQS `ApproximateFirstReceiveTimestamp`를 이용한
  애플리케이션 histogram `bluetape4k.sqs.batch.redelivery.age`로 기록하고, DLQ visible count는
  DLQ CloudWatch/SQS `ApproximateNumberOfMessagesVisible`를 source로 사용한다. 두 값 모두
  message id, receipt handle, body, queue URL을 tag·trace attribute·로그에 넣지 않는다.

  metric/trace 검증은 위 allowlist와 cancellation metric, redelivery-age/DLQ source를 모두
  확인한다.
- 운영 rollback runbook은 테스트 명령과 control-plane 명령을 분리한다. deployment adapter는
  `POST $CONTROL_PLANE_URL/v1/listeners/$LISTENER_ID/stop`에
  `{"timeoutMillis":30000,"waitFor":"STOPPED"}`를 보내고
  `state=STOPPED`, `drained=true`, `inFlight=0`, `generation`을 반환해야 한다. 응답이
  이 contract를 만족하지 않으면 receive 중지→drain 단계에서 rollback을 중단한다. 이후
  old handler 배포, DLQ redrive, idempotency 확인을 순서대로 수행하며, partial failure
  `>1%/5m`, retry exhaustion `>0.1%/5m`, redelivery-age p95 `>80% of visibility timeout/5m`,
  DLQ visible count `>0/5m`을 canary 중단 기준으로 삼는다. evidence는
  `.bluetape/evidence/issue-454/rollback/`에 명령·응답·threshold snapshot으로 보존한다.
- README 예제와 Kotlin 공개 KDoc 예제는 실제 API surface로 compile-check하고, 영문/국문
  README 및 manual module 페이지의 heading·예제·migration/오류/partial ack 항목 parity를
  비교한다. 공개 KDoc와 consumer fixture에는 `SqsListenerBatchCorrelation` 및
  correlation-aware interceptor overload/default bridge 예제를 포함하고, correlation에는
  generation·poller·sequence 외 metadata를 넣지 않는다.
- 대상 테스트, detekt, 전체 모듈 compile/test, git diff --check를 순차 실행한다.
- Kotlin checklist에서 validation/exception, cancellation/dispatcher, API compatibility,
  Spring test, 문서 drift를 모두 확인하고 P0/P1을 0으로 수렴한다.

## DoD

- #454에 명시된 batch listener와 partial acknowledgement가 단건 호환성을 유지하며 구현된다.
- AWS 항목별 batch delete 결과와 retry/redelivery semantics가 테스트로 증명된다.
- Floci 검증 또는 명시적인 emulator capability gap이 기록된다.
- README/KDoc/manual과 테스트가 현재 API와 일치하고, 롤아웃·rollback·DLQ/idempotency
  운영 절차가 문서화된다.
- P0/P1 미해결 사항이 없고, PR/merge 없이 이슈 단위 로컬 DoD를 보고한다.
