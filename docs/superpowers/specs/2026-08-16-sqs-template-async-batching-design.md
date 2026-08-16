# SQS template 비동기 배치·부분 실패 결과 설계

Issue: [#461](https://github.com/bluetape4k/bluetape4k-aws/issues/461)
Epic: [#499](https://github.com/bluetape4k/bluetape4k-aws/issues/499)
기준 브랜치: `develop@2ff6b957fee97ffbdca6ca842af3d98bdbeaddf5`
작업 브랜치: `feat/issue-461-sqs-template-batching`

## 문제와 목표

현재 `SqsOperations`와 `SqsCoroutinesTemplate`는 단건 `send`와 명시적인
`deleteBatch`/`changeVisibilityBatch`를 제공한다. 호출자가 짧은 시간에 발생한
여러 단건 요청을 자동으로 모으려면 직접 버퍼, 타이머, 수명 주기를 구현해야 한다.
이 방식은 애플리케이션마다 flush, 취소, 종료와 부분 실패 처리가 달라진다.

이번 변경은 다음 기능을 제공한다.

1. 여러 `send`와 `delete`를 AWS SDK `SqsAsyncBatchManager`로 자동 병합하는
   coroutine API를 제공한다.
2. 성공과 실패를 호출자 entry ID 기준으로 입력 순서대로 반환한다.
3. FIFO group/deduplication과 message attributes를 기존 `SqsSendRequest` 그대로
   보존한다.
4. 활성 SDK future 수를 제한하고, 취소와 애플리케이션 종료의 책임 경계를
   명시한다.
5. 기존 `SqsOperations`, listener, 기본 `SqsAsyncClient` 주입 경로는 변경하지
   않는다.

listener batch payload와 partial acknowledgement는 완료된 Issue #454의 범위다.
이번 변경은 outbound template 자동 배치만 다룬다.

## 현재 근거

### 저장소

| 근거 | 현재 계약 | 이번 설계의 적용 |
|---|---|---|
| `SqsOperations.kt` | 단건 `send`, 명시적 최대 10개 delete/visibility batch와 순차 fallback | 기존 인터페이스를 확장하지 않고 별도 outbound batching API를 둔다. |
| `SqsCoroutinesTemplate.kt` | `SqsAsyncClient` future를 `.await()`하고 `SqsSendRequest`의 FIFO·attributes를 그대로 매핑 | request 매핑을 재사용하고 cancellation을 future로 전파한다. |
| `SqsSendRequest.kt` | queue URL, body, delay, FIFO group/deduplication, message attributes를 검증하는 `Serializable` 모델 | payload converter를 추가하지 않고 caller가 만든 request를 그대로 받는다. |
| `SqsBatchModels.kt` | 부분 성공 결과, 입력 순서, redacted `toString()` 패턴 | 결과 모양과 출력 redaction을 차용하되 automatic manager에서 잃는 `senderFault`를 거짓 값으로 채우지 않는다. |
| `SqsProperties.kt` | listener 수명 주기와 retry 설정을 보유한 public `Serializable` data class | 기존 ABI를 바꾸지 않고 별도 `SqsBatchProperties`를 추가한다. |
| `SqsAutoConfiguration.kt` | 표준 `SqsAsyncClient`와 `SqsOperations`가 listener에도 사용됨 | batching client를 `SqsAsyncClient` bean으로 노출하지 않는다. |
| `MicrometerSqsOperations.kt` | low-cardinality operation/outcome 관측 패턴 | 별도 `SqsBatchOperations` decorator에서 같은 태그 정책을 사용한다. |

격리 worktree에서 다음 기존 테스트를 실행했다.

```text
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests 'io.bluetape4k.aws.spring.sqs.SqsOperationsBatchTest' \
  --tests 'io.bluetape4k.aws.spring.sqs.SqsAutoConfigurationTest'

28 passing, BUILD SUCCESSFUL
```

### 외부 구현

- AWS SDK Java v2 `2.51.3`의
  [`SqsAsyncBatchManager`](https://github.com/aws/aws-sdk-java-v2/blob/2.51.3/services/sqs/src/main/java/software/amazon/awssdk/services/sqs/batchmanager/SqsAsyncBatchManager.java)는
  단건 `sendMessage`와 `deleteMessage`를 queue URL별 batch request로 병합하고
  각 단건 요청에 `CompletableFuture`를 반환한다.
- [`BatchOverrideConfiguration`](https://github.com/aws/aws-sdk-java-v2/blob/2.51.3/services/sqs/src/main/java/software/amazon/awssdk/services/sqs/batchmanager/BatchOverrideConfiguration.java)은
  `maxBatchSize`를 1..10으로 제한하며 기본 flush 주기는 200ms다.
- SDK의 [`RequestBatchManager.close()`](https://github.com/aws/aws-sdk-java-v2/blob/2.51.3/services/sqs/src/main/java/software/amazon/awssdk/services/sqs/internal/batchmanager/RequestBatchManager.java#L163-L176)는
  buffer를 flush한 직후 pending batch/item future를 취소한다. 따라서 `close()`를
  안전한 drain으로 간주할 수 없다.
- Spring Cloud AWS의
  [`BatchingSqsClientAdapter`](https://github.com/awspring/spring-cloud-aws/blob/main/spring-cloud-aws-sqs/src/main/java/io/awspring/cloud/sqs/operations/BatchingSqsClientAdapter.java)는
  manager를 `SqsAsyncClient`로 감싸지만, 공식 문서는 listener 기반 구조에 이
  client를 주입하지 말고 이름이 지정된 template 전용 client로 사용하도록 한다.
- Spring Cloud AWS의 `SendBatchFailureHandlingStrategy`는 `THROW`와
  `DO_NOT_THROW`만 제공한다. 전송 상태가 불확실한 transport failure를 라이브러리가
  자동 재시도하면 중복 전달을 만들 수 있으므로 이 설계도 자동 retry를 제공하지
  않는다.

## 대안 비교

### 대안 A — 기존 `SqsOperations`와 `SqsCoroutinesTemplate` 확장

`sendMany`와 `deleteMany`를 기존 인터페이스의 default method로 추가하고 기존
template이 manager를 선택적으로 사용한다.

- 장점: bean과 사용 진입점이 하나다.
- 단점: listener가 사용하는 `SqsOperations` lifecycle에 batching buffer와 executor가
  들어간다. 기존 custom implementation의 ABI fallback도 복잡해지고, standard client와
  batching client의 책임이 섞인다.

선택하지 않는다.

### 대안 B — 전용 `SqsBatchOperations`와 opt-in transport

별도 `SqsBatchOperations` bean을 항상 제공한다. `batch.enabled=false`에서는 표준
`SqsAsyncClient` 단건 호출을 bounded concurrency로 실행하고,
`batch.enabled=true`에서만 전용 `SqsAsyncBatchManager`와 scheduler를 생성한다.

- 장점: listener와 기존 template의 injection/lifecycle을 보존한다.
- 장점: 같은 API로 fallback과 automatic batching을 검증할 수 있다.
- 장점: custom `SqsBatchOperations` bean 하나로 전체 구현을 교체할 수 있다.
- 단점: outbound 용도의 두 번째 operations 타입을 학습해야 한다.

이 대안을 선택한다.

### 대안 C — `SendMessageBatch`/`DeleteMessageBatch` chunking만 제공

입력 collection을 10개씩 잘라 명시적 batch API를 호출한다.

- 장점: lifecycle과 background scheduler가 단순하다.
- 단점: 서로 다른 coroutine이나 시간대에서 들어온 단건 요청을 합치지 못한다.
- 단점: flush interval 요구와 SDK manager parity를 충족하지 못한다.

선택하지 않는다.

## 선택한 공개 API

### operations

```kotlin
interface SqsBatchOperations {
    suspend fun sendMany(
        entries: Collection<SqsBatchSendEntry>,
        failureStrategy: SendBatchFailureStrategy = SendBatchFailureStrategy.RETURN,
    ): SqsSendManyResult

    suspend fun deleteMany(
        entries: Collection<SqsBatchDeleteEntry>,
    ): SqsDeleteManyResult
}
```

`SqsBatchOperations`는 `SqsOperations`를 상속하지 않는다. listener post processor와
container는 계속 `SqsOperations`만 주입받는다.

빈 collection은 외부 호출 없이 빈 성공 결과를 반환한다. entry ID는 1..80자의
`[A-Za-z0-9_-]`만 허용하고 한 호출 안에서 중복될 수 없다. 입력 수는
`maxEntriesPerCall` 이하여야 한다. 이 검증과 컬렉션 스냅샷은 첫 suspension과
외부 호출 전에 끝낸다. queue URL, body, receipt handle은 각 request 모델에서 기존과
같은 방식으로 조기 검증한다.

### request 모델

```kotlin
@ConsistentCopyVisibility
data class SqsBatchSendEntry private constructor(
    val entryId: String,
    val request: SqsSendRequest,
    @Suppress("UNUSED_PARAMETER") private val snapshotMarker: Boolean,
) : Serializable {
    init {
        validateEntryId(entryId)
    }

    constructor(entryId: String, request: SqsSendRequest) : this(
        entryId,
        request.copy(messageAttributes = request.messageAttributes.toMap()),
        true,
    )

    override fun toString(): String = "SqsBatchSendEntry(request=<redacted>)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@ConsistentCopyVisibility
data class SqsBatchDeleteEntry private constructor(
    val entryId: String,
    val queueUrl: String,
    val receiptHandle: String,
    @Suppress("UNUSED_PARAMETER") private val validatedMarker: Boolean,
) : Serializable {
    init {
        validateEntryId(entryId)
    }

    constructor(entryId: String, queueUrl: String, receiptHandle: String) : this(
        entryId,
        queueUrl.requireNotBlank { "queueUrl must not be blank." },
        receiptHandle.requireNotBlank { "receiptHandle must not be blank." },
        true,
    )

    override fun toString(): String = "SqsBatchDeleteEntry(<redacted>)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

public secondary constructor는 `request.messageAttributes.toMap()`으로 request를
복사해 admission 전에 snapshot을 고정한다. private primary constructor와
`@ConsistentCopyVisibility`로 generated `copy`가 snapshot 경계를 우회하지 못하게
한다. `sendMany`/`deleteMany`는 입력 iterator를 첫 suspension 전에 최대
`maxEntriesPerCall + 1`개까지만 읽는다. 초과 입력은 나머지를 materialize하지 않고
거부하며, 허용된 항목만 immutable list로 고정한다.

두 entry 타입의 public constructor는 entry ID 형식과 request 필수 값을 즉시 검증한다.
`SqsBatchDeleteEntry`는 queue URL과 receipt handle의 blank 여부를 생성 시점에 거부한다.
operations는 컬렉션 스냅샷 뒤 같은 검증을 다시 수행해 Java deserialization이나
custom 구현에서 들어온 잘못된 상태도 외부 호출 전에 차단한다. 오류 message는 field
name만 포함하고 실제 값을 출력하지 않는다.

모든 새 data class는 `Serializable`과 `serialVersionUID`를 제공한다. Java native
serialization은 저장소의 기존 모델 호환성을 위한 trusted-process 경계일 뿐, 신뢰할 수
없는 wire format으로 사용하지 않는다. 모든 request/result/item data class는 generated
`toString()`을 사용하지 않고 아래 redaction 계약을 직접 구현한다.

- request entry는 타입과 `<redacted>`만 출력한다.
- result와 exception은 status, kind, 성공/실패 개수만 출력한다.
- entry ID, body, queue URL, receipt handle, attribute, message ID, sequence number,
  error code는 출력하지 않는다.

caller entry ID는 결과 상관관계에만 사용한다. 테스트에서는
`Base58.randomString(16)`으로 생성한다. 라이브러리가 ID를 자동 생성하지 않으므로
호출자는 외부 요청과 결과를 안정적으로 연결할 수 있다.

### result 모델

```kotlin
enum class SqsBatchResultStatus { SUCCESS, PARTIAL_FAILURE, FAILURE }

enum class SqsBatchFailureKind { SERVICE, TRANSPORT }

@ConsistentCopyVisibility
data class SqsBatchEntryFailure private constructor(
    val entryId: String,
    val kind: SqsBatchFailureKind,
    val code: String?,
    @Suppress("UNUSED_PARAMETER") private val normalizedMarker: Boolean,
) : Serializable {
    init {
        validateEntryId(entryId)
        require(kind == SqsBatchFailureKind.SERVICE || code == null) {
            "code must be null for transport failure."
        }
    }

    constructor(entryId: String, kind: SqsBatchFailureKind, code: String?) : this(
        entryId,
        kind,
        if (kind == SqsBatchFailureKind.TRANSPORT) null else normalizeErrorCode(code),
        true,
    )

    override fun toString(): String = "SqsBatchEntryFailure(kind=$kind)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@ConsistentCopyVisibility
data class SqsBatchSendSuccess private constructor(
    val entryId: String,
    val messageId: String,
    val sequenceNumber: String?,
    @Suppress("UNUSED_PARAMETER") private val validatedMarker: Boolean,
) : Serializable {
    init {
        validateEntryId(entryId)
    }

    constructor(entryId: String, messageId: String, sequenceNumber: String?) : this(
        entryId,
        messageId.requireNotBlank { "messageId must not be blank." },
        sequenceNumber?.requireNotBlank { "sequenceNumber must not be blank." },
        true,
    )

    override fun toString(): String = "SqsBatchSendSuccess(<redacted>)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@ConsistentCopyVisibility
data class SqsSendManyResult private constructor(
    val successful: List<SqsBatchSendSuccess>,
    val failed: List<SqsBatchEntryFailure>,
    @Suppress("UNUSED_PARAMETER") private val snapshotMarker: Boolean,
) : Serializable {
    init {
        validateResultIds(successful.map { it.entryId }, failed.map { it.entryId })
    }

    val status: SqsBatchResultStatus get() = batchResultStatus(successful.size, failed.size)

    constructor(
        successful: List<SqsBatchSendSuccess>,
        failed: List<SqsBatchEntryFailure>,
    ) : this(successful.toList(), failed.toList(), true)

    override fun toString(): String =
        "SqsSendManyResult(status=$status, successful=${successful.size}, failed=${failed.size})"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@ConsistentCopyVisibility
data class SqsDeleteManyResult private constructor(
    val successfulEntryIds: List<String>,
    val failed: List<SqsBatchEntryFailure>,
    @Suppress("UNUSED_PARAMETER") private val snapshotMarker: Boolean,
) : Serializable {
    init {
        validateResultIds(successfulEntryIds, failed.map { it.entryId })
    }

    val status: SqsBatchResultStatus get() = batchResultStatus(successfulEntryIds.size, failed.size)

    constructor(
        successfulEntryIds: List<String>,
        failed: List<SqsBatchEntryFailure>,
    ) : this(successfulEntryIds.toList(), failed.toList(), true)

    override fun toString(): String =
        "SqsDeleteManyResult(status=$status, successful=${successfulEntryIds.size}, failed=${failed.size})"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

result의 public secondary constructor도 모든 list를 `toList()`로 snapshot한다.
`status`는 constructor 입력이 아니라 성공/실패 개수에서 계산한다. 실패가 없으면
`SUCCESS`, 성공과 실패가 함께 있으면 `PARTIAL_FAILURE`, 성공이 없으면 `FAILURE`다.
public constructor는 성공/실패 entry ID의 중복과 교집합을 거부한다. 내부
`BatchResultNormalizer.from(expectedEntryIds, outcomes)` factory는 caller가 전달한
expected ID snapshot을 별도 인자로 받아 누락·미지·중복 outcome을 거부한 뒤 public
result를 만든다. 이때 사용하는 protocol exception은 count만 보유하며 고정 message,
`cause=null`, 빈 `suppressed`를 사용하고 expected ID나 raw outcome을 property에 보관하지
않는다. 성공과 실패 목록은 각각 원래 입력 순서를 따르며, 두 목록을 합친 entry ID
집합은 transport가 받은 입력 집합과 정확히 일치해야 한다.

public result constructor도 성공/실패 내부 중복과 교집합을 직접 검증한다. expected 입력
집합과의 완전성 검증만 operations 전용 normalizer가 담당한다. 모든 public model의
companion object에는 명시적인 `private const val serialVersionUID = 1L`을 둔다.

SDK `SqsException.awsErrorDetails().errorCode()`는 최대 64자의
`[A-Za-z0-9._-]` token으로 정규화한 뒤 `code`에 보존하며, 이 계약을 벗어나면
`UNKNOWN`을 사용한다. SDK exception message, cause, request 객체는 결과에 보관하지
않는다. transport failure는 `kind=TRANSPORT`로 구분하되 raw cause는 결과나 exception
문자열에 남기지 않는다. `toString()`은 status, kind와 성공/실패 개수만 출력하고
`code`도 출력하지 않는다.

AWS manager는 failed delete entry의 `senderFault`를 `SqsException`으로 변환하면서
보존하지 않는다. 기존 `SqsBatchDeleteFailure.senderFault`에 임의의 값을 넣지 않고
automatic batching 전용 결과를 분리한다.

### send failure strategy

```kotlin
enum class SendBatchFailureStrategy {
    RETURN,
    THROW,
}
```

- `RETURN`: partial/failure 결과를 그대로 반환한다. 기존 SQS batch 결과 API와
  일관성을 위해 기본값으로 사용한다.
- `THROW`: 모든 entry outcome을 수집한 뒤 실패가 하나라도 있으면
  `SqsSendBatchFailedException(result)`를 던진다. exception은 정규화한 result만
  보유하고 raw SDK cause는 보유하지 않는다. exception message와 `toString()`은
  `status`와 실패 개수만 포함하고 `cause=null`, raw `suppressed`는 빈 배열이다.

자동 `RETRY_FAILED`는 제공하지 않는다. service entry failure는 재시도 가능 여부가
오류 코드와 queue 정책에 따라 다르고, transport failure는 AWS 도달 여부가 불확실하다.
caller가 재시도한다면 FIFO deduplication ID 또는 별도 idempotency 정책으로 중복을
제어해야 한다. 이 정책은 bluetape4k 계층의 추가 retry를 뜻하며, 기반
`SqsAsyncClient`에 설정된 AWS SDK retry policy는 그대로 적용된다. cancellation이나
transport failure 뒤 수동 retry 전에는 이미 전달됐을 가능성과 중복 위험을 평가해야
한다.

`deleteMany`는 entry 실패 전략 인자를 받지 않는다. service/transport entry 실패는 항상
`SqsDeleteManyResult`로 반환한다. 입력 validation, response protocol, lifecycle/close
위반처럼 정상적인 entry outcome을 만들 수 없는 계약 오류만 exception을 던진다.

공개 exception 계약은 다음과 같다.

- `SqsSendBatchFailedException`: public, 정규화한 `SqsSendManyResult`만 제공한다.
- `SqsBatchCloseException`: public, 실패한 cleanup component kind와 개수만 제공한다.
- `SqsBatchStartupException`: public, 실패한 startup component kind와 cleanup failure
  component kind/count만 제공한다.
- `SqsBatchProtocolException`: internal이며 count만 보유한다.

네 타입 모두 raw cause/suppressed를 보유하지 않는다. public exception의 message와
`toString()`은 고정 token과 안전한 kind/count만 출력하며 `cause=null`,
`suppressed.isEmpty()`다. `TRANSPORT` failure의 `code`는 항상 `null`이고, `SERVICE`
failure만 정규화한 service code 또는 `UNKNOWN`을 갖는다. 세 public exception은
`serialVersionUID=1L`을 명시한다.

`SqsBatchCloseException.components`는
`SqsBatchCleanupComponent { MANAGER, EXECUTOR, TIMEOUT }`의 immutable list다. 중복을
제거하고 `MANAGER`, `EXECUTOR`, `TIMEOUT` 순서로 정렬하며 `failureCount`는 list size에서
계산한다. 공유 `closeCompletion`은 같은 exception 인스턴스를 저장하므로 concurrent와
repeated `close()`는 wrapper 없이 같은 identity를 다시 관찰한다.

failure normalizer는 `CompletionException`/`ExecutionException`만 반복해서 unwrap하되
root Throwable을 보관하지 않는다. caller job cancellation이 최우선이고, active caller의
SDK future cancellation과 그 밖의 비-`SqsException` failure는 `TRANSPORT(code=null)`로
분류한다. 유효한 `awsErrorDetails`가 있는 `SqsException`만 `SERVICE`로 분류한다. 성공
response의 필수 `messageId`가 null/blank이거나 response ID가 맞지 않으면 entry failure가
아니라 internal protocol exception이다. direct/batch mode 모두 같은 normalizer를
사용한다.

### 결과 순서와 호출 간 순서

`successful`과 `failed`는 각각 입력에서의 상대 순서를 보존한다. 두 목록으로 분리된
항목을 다시 합친 전체 순서는 API가 제공하지 않는다. 서로 다른 `sendMany` 호출,
coroutine, queue URL 사이에도 전송 순서를 보장하지 않는다. 한 `sendMany` 호출 안에서도
child submit이 병렬이므로 같은 FIFO group entry의 transport/delivery 순서를 보장하지
않는다. 결과 목록의 순서 복원과 전송 순서는 별개다. 같은 FIFO group의 엄격한 순서가
필요하면 자동 배치 API를 사용하지 않는다. `SqsCoroutinesTemplate.send(request)`를 한 건씩
await하는 순차 경로나 caller가 entry 순서를 직접 소유하는 raw
`SqsAsyncClient.sendMessageBatch` 경로를 사용한다. `SqsOperations.send(request)`의 기본
구현은 FIFO 전용 필드를 보존하지 않을 수 있으므로 이 대체 경로로 안내하지 않는다.

### 사용자 예시와 migration

아래 코드는 API 모양을 고정하는 개념 예시다. 구현 단계에서 package/import와 실제
컴파일을 검증한 예제를 README/manual에 싣는다.

```yaml
bluetape4k:
  aws:
    sqs:
      batch:
        enabled: true
        max-batch-size: 10
        flush-interval: 200ms
        max-entries-per-call: 1000
        max-in-flight-entries: 100
        scheduler-threads: 1
        shutdown-timeout: 5s
```

```kotlin
import io.bluetape4k.aws.spring.sqs.SendBatchFailureStrategy
import io.bluetape4k.aws.spring.sqs.SqsBatchOperations
import io.bluetape4k.aws.spring.sqs.SqsBatchSendEntry
import io.bluetape4k.aws.spring.sqs.SqsSendRequest

class OrderEventPublisher(
    private val sqsBatchOperations: SqsBatchOperations,
) {
    suspend fun publish(queueUrl: String, bodies: List<String>) {
        val entries = bodies.mapIndexed { index, body ->
            SqsBatchSendEntry(
                entryId = "order-$index",
                request = SqsSendRequest(queueUrl = queueUrl, body = body),
            )
        }
        val result = sqsBatchOperations.sendMany(entries, SendBatchFailureStrategy.RETURN)
        if (result.failed.isNotEmpty()) {
            // service/transport failure를 분리해 caller 정책으로 재시도 여부를 결정한다.
        }
    }
}
```

`THROW`는 같은 result를 `SqsSendBatchFailedException.result`로 제공한다. `deleteMany`는
entry 실패를 항상 result로 반환한다. 기존 `SqsOperations.send(request)`는 설정과
무관하게 기존 단건 경로를 유지한다. 자동 배치를 사용하려는 코드만 주입 타입과 호출을
`SqsBatchOperations.sendMany(entries)`로 바꾼다. 즉 `batch.enabled`는 새
`SqsBatchOperations`에만 적용되며 기존 `SqsOperations`를 자동으로 바꾸지 않는다.

## 구성과 Spring bean 경계

기존 public `SqsProperties` data class에는 필드를 추가하지 않는다. 별도
`@ConfigurationProperties("bluetape4k.aws.sqs.batch")` 타입을 추가한다.

```kotlin
internal const val SQS_BATCH_PROPERTIES_PREFIX = "bluetape4k.aws.sqs.batch"

@ConfigurationProperties("bluetape4k.aws.sqs.batch")
data class SqsBatchProperties(
    val enabled: Boolean = false,
    val maxBatchSize: Int = 10,
    val flushInterval: Duration = Duration.ofMillis(200),
    val maxEntriesPerCall: Int = 1_000,
    val maxInFlightEntries: Int = 100,
    val schedulerThreads: Int = 1,
    val shutdownTimeout: Duration = Duration.ofSeconds(5),
) : Serializable {
    init {
        maxBatchSize.requireInRange(1, 10, "$SQS_BATCH_PROPERTIES_PREFIX.max-batch-size")
        flushInterval.requireInRange(
            Duration.ofMillis(1),
            Duration.ofMinutes(1),
            "$SQS_BATCH_PROPERTIES_PREFIX.flush-interval",
        )
        maxEntriesPerCall.requireInRange(1, 10_000, "$SQS_BATCH_PROPERTIES_PREFIX.max-entries-per-call")
        maxInFlightEntries.requireInRange(1, 10_000, "$SQS_BATCH_PROPERTIES_PREFIX.max-in-flight-entries")
        schedulerThreads.requireInRange(1, 16, "$SQS_BATCH_PROPERTIES_PREFIX.scheduler-threads")
        shutdownTimeout.requireInRange(
            Duration.ofMillis(1),
            Duration.ofMinutes(1),
            "$SQS_BATCH_PROPERTIES_PREFIX.shutdown-timeout",
        )
        require(!enabled || maxInFlightEntries >= maxBatchSize) {
            "$SQS_BATCH_PROPERTIES_PREFIX.max-in-flight-entries must cover max-batch-size"
        }
        require(!enabled || shutdownTimeout >= flushInterval) {
            "$SQS_BATCH_PROPERTIES_PREFIX.shutdown-timeout must cover flush-interval"
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

설정 prefix는 `bluetape4k.aws.sqs.batch`다. 이 분리는 기존 `SqsProperties` primary
constructor, `copy`, `copy$default`를 그대로 두어 precompiled consumer의 ABI를 보존한다.
구현 검증에는 변경 전 bytecode로 컴파일한 legacy fixture를 포함한다.

- `maxBatchSize`: 1..10
- `flushInterval`: 1ms..1m
- `maxEntriesPerCall`: 1..10,000
- `maxInFlightEntries`: 1..10,000. batch mode에서만 `maxBatchSize` 이상
- `schedulerThreads`: 1..16
- `shutdownTimeout`: 1ms..1m

configuration property는 operator-trusted 입력이지만 위 범위를 벗어나면 bean 생성 전에
거부한다. direct mode에서는 사용하지 않는 `maxBatchSize` 때문에 낮은
`maxInFlightEntries`를 거부하지 않는다. batch mode에서는 정상 flush 기회를 보장하도록
`shutdownTimeout >= flushInterval`도 요구한다.

manual은 다음 trade-off를 설정 표로 설명한다.

| 설정 | 높일 때 | 낮출 때 |
|---|---|---|
| `maxBatchSize` | request 수를 줄일 수 있으나 첫 entry 대기가 늘 수 있음 | 더 자주 전송해 latency가 낮아질 수 있으나 request 수 증가 |
| `flushInterval` | 병합 기회 증가, tail latency 증가 | 작은 batch 증가, latency 감소 |
| `maxInFlightEntries` | 처리량과 active future/메모리 증가 | backpressure 증가 |
| `maxEntriesPerCall` | 호출별 snapshot/result 메모리 증가 | caller가 더 작은 단위로 나눠야 함 |
| `schedulerThreads` | queue/operation timer contention 완화, thread 증가 | 고 cardinality queue에서 flush 지연 가능 |
| `shutdownTimeout` | 정상 drain·cleanup 기회 증가, 종료 대기 증가 | 빠른 반환, 전달·cleanup 불확실성과 timeout 증가 |

여러 queue URL은 같은 template의 permit과 scheduler를 공유한다.

`SqsAutoConfiguration`과 `SqsMicrometerAutoConfiguration`은 다음 bean 경계를
사용한다.

1. custom `SqsBatchOperations`가 없으면 property-exclusive direct configuration과
   enabled-manager-present configuration 중 하나가 concrete `SqsBatchCoroutinesTemplate`
   bean을 `destroyMethod="close"`로 등록한다. 이 raw bean이 manager/executor lifecycle을
   소유한다.
2. template은 표준 `SqsAsyncClient`를 사용하지만 이를 닫지 않는다.
3. `batch.enabled=true`일 때 template이 전용 `ScheduledExecutorService`와
   `SqsAsyncBatchManager`를 생성하고 소유한다.
4. batching adapter를 `SqsAsyncClient` bean으로 등록하지 않는다.
5. custom `SqsBatchOperations`가 있으면 template과 내부 manager/executor 생성을 모두
   back off한다.
6. 두 default raw configuration만 internal `DefaultSqsBatchOperationsMarker` bean을 함께
   등록한다. `MeterRegistry`, marker와 원본 template이 모두 있을 때만
   `MicrometerSqsBatchOperations`를 `@Primary`로 추가한다. custom concrete
   `SqsBatchCoroutinesTemplate`에는 marker가 없으므로 decorator가 생성되지 않는다.
   decorator가 수명 주기를 소유하거나 원본 template의 close를 대체하지 않는다.

일반 caller는 `SqsBatchOperations`를 주입해 Micrometer decorator를 받는다. raw
template이 필요한 infrastructure/test만 concrete `SqsBatchCoroutinesTemplate` 타입을
주입하므로 별도 qualifier가 필요 없다. custom `SqsBatchOperations`가 있으면 raw
template과 기본 decorator가 모두 back off하며 custom bean의 관측성도 caller가 소유한다.

manager 생성은 internal `SqsBatchTransportFactory` seam으로 격리한다. scheduler 생성
뒤 manager 생성이 실패하면 factory가 같은 stack에서 scheduler를 `shutdownNow()`하고
종료를 확인한다. manager 생성 뒤 transport나 template 조립이 실패하면 이미 만든 manager를
닫고 scheduler를 종료한다. 특히 batch resources 생성 뒤 template 조립이 실패하는 rollback은
정상 close와 같은 `shutdownTimeout` 및 internal `SqsBatchCloseRuntime`을 사용한다. manager
cleanup은 이름 있는 daemon thread에서 실행하고, 남은 monotonic 시간만 기다리며, executor
종료는 최상위 `finally`에서 정확히 한 번 수행한다. manager가 영구 block하거나 cleanup 중
예외를 던져도 startup caller는 deadline 안에 반환하고 표준 `SqsAsyncClient`는 닫지 않는다.
두 경로 모두 raw startup/cleanup `Throwable` graph를 버리고
`SqsBatchStartupException`을 한 번 만든 뒤 같은 safe exception identity만 상위 Spring
경계까지 전달한다. 이 exception은 `SqsBatchStartupComponent { MANAGER, TRANSPORT,
TEMPLATE }`와 deduplicated cleanup component kind/count만 보유하며 고정 message,
`cause=null`, 빈 `suppressed`, 안전한 `toString()`을 사용한다. rollback cleanup 실패도 raw
cause/message를 기록하지 않고 component kind/count만 운영 로그에 남긴다. fake
transport/manager와 deterministic scheduler도 이 internal seam으로 주입하며 공개
`BatchExecutionStrategy`는 만들지 않는다.

`batch.enabled=false`에서도 `SqsBatchOperations` bean은 존재한다. 이 모드는
`sendMessage`/`deleteMessage` 단건 future를 같은 bounded execution 경계에서 실행한다.
따라서 호출자 코드는 설정에 따라 바뀌지 않고, batching 사용 여부만 달라진다.

AWS SDK SQS의 지원 최소 버전은 `2.51.3`이다. manager 타입을 참조하는 구현은 internal
batch transport class와 `enabled=true` 전용 nested configuration에만 둔다. 상위
auto-configuration의 bean method signature, condition, field에는 manager 타입을 노출하지
않아 direct mode가 해당 class를 link하지 않게 한다. direct configuration은
`enabled=false` 또는 missing property에서 활성화된다. enabled-manager-present configuration은
`@ConditionalOnProperty(... havingValue="true")`와 manager `@ConditionalOnClass(name=...)`를
함께 사용한다. 별도 manager-missing guard는 같은 property 조건,
`@ConditionalOnMissingClass(name=...)`, `@ConditionalOnMissingBean(SqsBatchOperations::class)`를
사용해 자원이나 raw template을 만들지 않고 safe `SqsBatchStartupException`을 던진다.
direct mode classpath 테스트는 manager class를 숨긴 isolated classloader에서 전체
application context와 표준 transport가 시작되는지 검증하고, enabled mode는 같은 loader에서
명시적으로 실패하는지 검증한다. 중앙 catalog보다 낮은 service SDK는 지원 범위가 아니다.

현재 listener message converter는 inbound payload 변환 책임만 가진다. outbound
body converter나 공개 `BatchExecutionStrategy` SPI는 추가하지 않는다.

## Coordinator와 close 책임 경계

`SqsBatchCoordinator`는 entry admission과 accepted placeholder registry를 소유하고,
Task 5의 `SqsBatchCoroutinesTemplate`은 전체 close deadline과 manager/executor 정리를
소유한다. 두 책임은 다음 internal API로 연결한다.

```kotlin
internal class SqsBatchCoordinator(
    properties: SqsBatchProperties,
    transport: SqsBatchTransport,
) {
    suspend fun sendMany(
        entries: Collection<SqsBatchSendEntry>,
        strategy: SendBatchFailureStrategy = SendBatchFailureStrategy.RETURN,
    ): SqsSendManyResult

    suspend fun deleteMany(entries: Collection<SqsBatchDeleteEntry>): SqsDeleteManyResult

    fun beginClose(): SqsBatchCloseClaim

    fun finishClose(outcome: SqsBatchCloseOutcome)
}

internal sealed interface SqsBatchCloseClaim {
    val completion: CompletableFuture<SqsBatchCloseOutcome>

    data class Owner(
        val accepted: List<SqsAcceptedBatchEntry>,
        override val completion: CompletableFuture<SqsBatchCloseOutcome>,
    ) : SqsBatchCloseClaim

    data class Observer(
        override val completion: CompletableFuture<SqsBatchCloseOutcome>,
    ) : SqsBatchCloseClaim
}

internal interface SqsAcceptedBatchEntry {
    val completion: CompletableFuture<Unit>

    fun cancelIfIncomplete(): Boolean
}

internal sealed interface SqsBatchCloseOutcome {
    data object Success : SqsBatchCloseOutcome

    data class Failure(val exception: SqsBatchCloseException) : SqsBatchCloseOutcome
}
```

`beginClose()`만 lifecycle lock 아래에서 `OPEN -> CLOSING`, close signal 완료 소유권,
accepted placeholder 스냅숏과 shared completion owner를 함께 결정한다. lock 안에서는
`CompletableFuture.complete`, 외부 cancel, suspend 호출을 수행하지 않는다. `Owner`가
lock 밖에서 accepted completion을 기다리고 timeout이면 `cancelIfIncomplete()`를 호출한다.
`Observer`는 별도 deadline을 만들거나 owner deadline publication을 기다리지 않고 같은
shared completion만 기다린다. owner만 `beginClose()` claim을 얻은 직후 외부 대기 전에
operation-local monotonic deadline을 만들고, bounded cleanup과 최상위 `finally`로
shared completion을 반드시 완료한다. 따라서 `CLOSING` 전환 직후 owner cleanup이 아직
시작되지 않은 경합에서도 observer는 owner가 확정한 동일 결과만 관찰한다.
`finishClose()`만 `CLOSING -> CLOSED`로
전환하고 shared completion을 정확히 한 번 완료한다. 실패 outcome은 정규화한
`SqsBatchCloseException` 인스턴스를 그대로 보유하므로 concurrent/repeated close caller는
같은 exception identity를 관찰한다.

새 operation은 시작 시 caller job cancellation을 먼저 확인한다. 이미 취소됐다면 원래
`CancellationException` identity가 lifecycle 오류보다 우선한다. 그 다음 bounded snapshot과
validation을 수행하고 `OPEN`을 확인한다. empty 입력도 `CLOSING`이나 `CLOSED`에서는 성공으로
우회하지 않고 고정된 민감정보 없는 `IllegalStateException`으로 실패한다. `OPEN` 확인 뒤
close가 경합하면 permit/placeholder 선형화 결과가 승자를 결정한다.

## 실행 흐름과 backpressure

호출은 iterator에서 최대 `maxEntriesPerCall + 1`개까지만 읽는 bounded snapshot을
첫 suspension 전에 만든다. `maxEntriesPerCall`을 넘으면 즉시 거부하며 나머지 입력은
materialize하지 않고 외부 호출도 수행하지 않는다. 설정 상한은 `Int.MAX_VALUE`보다
작게 제한해 `+ 1` overflow를 막는다. 허용된 snapshot의 entry ID와 request를 검증한 뒤
`maxInFlightEntries` 이하의 admission window로 나눈다. 각
window는 `supervisorScope` 아래 bounded child만 만들고, 각 child는 template 전역
`Semaphore(maxInFlightEntries)` permit을 얻은 뒤 transport에 제출한다. child는 permit을
얻자마자 lifecycle lock 아래에서 `OPEN`을 확인하고 caller entry ID와 무관한 단조 증가
internal token으로 `AcceptedEntry` placeholder를 등록한다. 외부 submit과 await는 lock
밖에서 수행한다. close는 placeholder까지 snapshot하므로 register 뒤 future 연결 전의
race도 drain 대상에서 빠지지 않는다.

```text
caller collection
  -> validate count, entry IDs and requests; snapshot before suspension
  -> split into bounded admission windows
  -> supervisorScope launches at most one window of children
  -> each child acquires a global permit
  -> lifecycle lock: OPEN 확인 + internal token placeholder 등록
  -> lock 밖: batch manager 또는 direct client submit + await
  -> non-cancellation failure를 entry outcome으로 정규화
  -> finally: placeholder 완료/제거 + permit release
  -> await the whole window, then admit the next window
  -> restore input order
  -> RETURN result or THROW normalized exception
```

batch mode에서는 `maxInFlightEntries >= maxBatchSize`를 요구하므로 permit 제한 때문에
batch가 최대 크기에 도달하지 못하는 설정을 조기에 거부한다. direct mode는 이 관계를
강제하지 않는다. 여러 queue URL이 한 호출에 있어도 허용하며 manager는 queue URL별로
요청을 분리한다. 서로 다른 호출이 같은 caller entry ID를 사용해도 internal token이
다르므로 lifecycle registry에서 충돌하지 않는다.

결과 크기는 입력 크기에 비례하지만 `maxEntriesPerCall`로 호출별 snapshot/result entry
수의 상한을 둔다. 이 상한은 message body와 attribute의 aggregate byte 상한을 의미하지
않으며 payload byte validation은 기존 `SqsSendRequest`, AWS SDK와 SQS service limit에
위임한다. 따라서 이 API는 신뢰할 수 있는 애플리케이션 호출자를 전제로 한다. template
전역 permit은 활성 SDK future를 제한하고, admission window는 호출별 대기 child 수를
제한한다. accepted placeholder와 active future는 coordinator 전체에서
`maxInFlightEntries` 이하이고, resident child와 pending outcome map은 호출별로 같은 상한
이하다. 동시 호출 수 자체는 애플리케이션 coroutine scope가 소유한다.

## 취소와 lifecycle

### caller cancellation

- operation 입구에서 root caller cancellation을 `AtomicReference`로 한 번만 포착한다.
  caller job이 취소되면 suspend 함수는 그 원래 `CancellationException` 인스턴스를
  그대로 다시 던진다.
- internal cancellable await가 entry별 `cancelIfIncomplete()` atomic once guard를 소유한다.
  caller cancellation handler, child `finally`, close-timeout cleanup은 이 함수만 통해 아직
  완료되지 않은 future에 `cancel(false)`를 요청하므로 library가 시작하는 실제 호출은
  정확히 한 번이다. stock
  `CompletionStage.await()`와 별도 `finally` cancel을 함께 사용하지 않는다. lifecycle
  thread를 interrupt하지 않는다.
- caller job이 active인 상태에서 SDK future 자체가 `CancellationException`으로
  완료되면 caller cancellation로 오인하지 않고 해당 entry의 `TRANSPORT` failure로
  정규화한다. non-cancellation child failure도 sibling을 취소하지 않고 entry outcome으로
  바꾼다.
- caller cancellation cleanup은 각 child의 `finally`에서 non-suspending registry 제거,
  guarded future cancel 요청, permit release를 정확히 한 번 수행한다. 필요한 suspend
  cleanup은 `NonCancellable`에서 실행하되 원래 caller cancellation을 교체하지 않는다.
- future 취소는 대기 중단 요청이며 이미 manager가 batch에 포함한 메시지의 전달을
  취소하거나 회수한다는 보장이 아니다.
- 취소 경로는 partial result를 반환하지 않고 자동 retry나 보상 전송을 수행하지
  않는다.

### close

`SqsBatchCoroutinesTemplate`은 `AutoCloseable`이며 Spring bean destroy method로
`close()`를 사용한다. lifecycle state는 `OPEN`, `CLOSING`, `CLOSED`다.

시간과 blocking 대기를 결정적으로 검증하기 위해 template은 다음 internal seam을 받는다.
공개 API나 dependency는 늘리지 않는다.

```kotlin
internal interface SqsBatchCloseRuntime {
    fun nanoTime(): Long

    @Throws(InterruptedException::class, TimeoutException::class, ExecutionException::class)
    fun awaitCompletion(future: CompletableFuture<*>, remainingNanos: Long)

    fun awaitTermination(executor: ExecutorService, remainingNanos: Long): Boolean

    fun newManagerCleanupThread(task: Runnable): Thread
}
```

production 구현은 `System.nanoTime()`, nanosecond 단위 `CompletableFuture.get`,
`ExecutorService.awaitTermination`, 이름 있는 daemon thread를 사용한다. test 구현은 clock
증분, 각 wait에 전달된 `remainingNanos`, cleanup thread 시작·종료를 기록한다.

1. 각 caller는 먼저 `beginClose()`를 호출한다. 명시적 `ReentrantLock` 아래에서 최초 caller만
   `OPEN -> CLOSING`으로
   바꾸고 신규 admission을 차단한다. 이때 close signal을 완료하고 accepted placeholder
   스냅숏과 공유
   `closeCompletion`의 owner를 정한다. permit 획득 대기 child는 permit과 close signal을
   함께 기다리는 cancellable acquire gate를 사용한다. close가 먼저 선택되면 placeholder나
   외부 submit 없이 종료하고, permit을 먼저 얻었더라도 lifecycle lock의 `OPEN` 검사에서
   close와 선형화한다. `Owner`만 claim 직후 외부 대기 전에
   `SqsBatchCloseRuntime.nanoTime()`으로 canonical operation-local deadline을 만든다.
   `Observer`는 clock을 읽거나 별도 timeout을 만들지 않고 shared completion만 기다린다. owner의 모든
   경로가 bounded cleanup과 최상위 `finally`로 completion을 끝내므로 observer도 별도
   deadline publication 없이 같은 결과로 반환한다.
2. 최초 caller는 각 drain/cleanup 대기 직전에
   `max(0, deadlineNanos - SqsBatchCloseRuntime.nanoTime())`로 남은 시간을
   다시 계산하며 단계별 timeout을 새로 시작하지 않는다. lock 밖에서 placeholder가 future
   연결 또는 submit failure로 완료될 때까지 이 남은 시간만 사용한다.
3. 정상 drain이면 manager close를 시작하고 scheduler를 `shutdown()`한다. timeout이면
   남은 future의 유일한 `cancelIfIncomplete()` 경계를 호출한 뒤 manager close를 시작하고
   scheduler를 `shutdownNow()`한다.
4. manager close는 close 시점에 만든 이름 있는 daemon cleanup thread에서 실행한다.
   이 thread는 모든 `Throwable`을 내부에서 포착해 raw cause/message를 버리고 manager
   cleanup component failure만 공유 상태에 기록하므로 timeout 뒤 늦게 실패해도 uncaught
   exception이나 민감정보 로그를 만들지 않는다. owner는 deadline의 남은 시간만 기다린다.
   scheduler도 `awaitTermination`을 남은 시간으로 기다린다. deadline이 끝나면 cleanup
   thread를 interrupt하고 scheduler를 `shutdownNow()`한 뒤 기다리지 않는다. daemon
   cleanup thread는 process 종료를 막지 않지만 전달/cleanup 완료도 보장하지 않으므로
   timeout failure를 기록한다. deadline cutoff에서 현재까지 관찰한 cleanup component와
   `TIMEOUT`으로 canonical outcome을 한 번 확정한다. 그 뒤 manager thread가 늦게 실패해도
   확정된 outcome과 exception identity를 변경하지 않으며 raw detail 없는 telemetry/log만
   남긴다.
5. owner 전체 경로는 최상위 `try/finally`로 감싼다. interrupt를 받으면 interrupt status를
   복원하고 manager·executor cleanup을 각각 계속 시도한다. manager failure, executor
   failure, timeout 순으로 component kind/count만 가진 `SqsBatchCloseException`을 만들며
   raw cause/message/suppressed는 caller에게 노출하지 않는다.
6. 최상위 `finally`는 어떤 unexpected failure에서도 lock 아래 `CLOSED` 전환과 lock 밖
   `closeCompletion` 완료를 정확히 한 번 보장한다. `CLOSING` 중 들어온 다른 `close()`는
   별도 timeout 없이 같은 completion을 lock 밖에서 기다린 뒤 같은 성공 또는 정규화한 실패를
   관찰한다. `CLOSED` 뒤 호출은 이미 기록된 결과를 재관찰하고 새 cleanup을 시작하지
   않는다.

외부 future 대기, `manager.close()`, executor 종료는 lock 안에서 호출하지 않는다.
synchronization order는 permit 획득 후 짧은 lifecycle lock 임계 구역으로 한정한다.
lifecycle lock을 가진 채 permit을 기다리는 역순 경로는 없고, permit과 lock을 함께 가진
동안 suspension, completion signal, 외부 submit/cancel/close를 수행하지 않는다. 따라서
순환 대기 없이 `OPEN` 확인과 placeholder 등록만 원자화한다. 표준 `SqsAsyncClient`는 기존
auto-configuration 또는 caller가 소유하므로 template이 닫지 않는다.

SDK manager의 `close()`가 pending future를 취소하므로 반드시 wrapper drain 뒤에만
호출한다. `shutdownTimeout`은 drain, manager close wait와 scheduler termination을 합친
전체 caller-facing deadline이다. 외부 SDK close 자체를 강제로 끝내지는 못하지만 daemon
경계로 격리해 Spring destroy와 process 종료를 무기한 막지 않는다. timeout 뒤
manager-induced cancellation은 shutdown 결과일 뿐 caller operation 성공으로 바꾸지
않는다. 전달 여부가 불확실할 수 있으며 shutdown이 성공을 의미하지 않는다.

## 오류와 보안 경계

| 실패 모드 | 동작 | 검증 |
|---|---|---|
| 입력 상한·entry ID 형식·중복 ID·잘못된 request | 외부 호출 전 generic `IllegalArgumentException` | validation unit test |
| service entry failure | 해당 entry만 `SERVICE` failure로 정규화 | fake manager partial failure test |
| shared transport failure | 영향을 받은 각 entry를 `TRANSPORT` failure로 정규화 | failed batch future test |
| caller cancellation | 원래 `CancellationException` 보존, incomplete future 취소 | cancellation identity/future test |
| close 중 신규 요청 | 외부 호출 없이 거부 | admission/close race test |
| drain timeout | 남은 future 취소, manager/executor 강제 종료 | virtual/controlled clock lifecycle test |
| manager/executor cleanup failure | 모든 cleanup을 시도하고 component kind/count만 가진 민감정보 제거 예외 전달 | independent/double cleanup failure test |
| response 누락·미지·중복 ID | expected input과 대조해 generic protocol exception | normalizer contract test |
| FIFO/attribute 유실 | 기존 `SqsSendRequest`를 그대로 AWS request로 매핑 | request capture test |

다음 값은 로그, metric tag, 결과 `toString()`과 exception message에 포함하지 않는다.

- message body와 message attribute 값
- queue URL
- receipt handle
- entry ID
- raw SDK exception message와 cause

validation/protocol exception message는 field name과 count만 사용하고 실제 값은
보간하지 않는다. 운영 로그는 batching mode 시작/종료, drain timeout,
manager/executor cleanup failure kind/count만 남긴다. queue와 payload 대신 mode,
active count, timeout 같은 낮은 cardinality 값을 사용하며 raw throwable 자체를 logger에
전달하지 않는다.

## Micrometer

`MeterRegistry`가 있으면 `MicrometerSqsBatchOperations`를 등록해 원본 template을
감싼다. timer 이름은 `bluetape4k.aws.sqs.batch.operation`이며 `queueNameTag()`와 기존
exception tag helper를 재사용하지 않는다. tag key 집합은 정확히 다음으로 제한한다.

- service: 고정값 `sqs`
- operation: `send_many`, `delete_many`
- mode: `batch`, `direct`
- outcome: `success`, `partial_failure`, `failure`, `cancelled`

entry ID, queue URL, message ID, error message는 tag에 넣지 않는다. 성공/실패 entry
수는 counter 또는 summary로 기록하되 호출별 고유 식별자는 생성하지 않는다.
mode는 생성 시점의 transport mode를 그대로 사용한다. outcome은 다음처럼 고정한다.

| 동작 | 결과/예외 | outcome |
|---|---|---|
| `send_many` + `RETURN` | `SUCCESS` / `PARTIAL_FAILURE` / `FAILURE` | `success` / `partial_failure` / `failure` |
| `send_many` + `THROW` | success / mixed-result exception / all-failed exception | `success` / `partial_failure` / `failure` |
| `delete_many` | `SUCCESS` / `PARTIAL_FAILURE` / `FAILURE` | `success` / `partial_failure` / `failure` |
| 어느 동작이든 | caller cancellation / validation·protocol·lifecycle failure | `cancelled` / `failure` |

## 호환성과 migration

- 기존 `SqsOperations`, `SqsCoroutinesTemplate`, `SqsProperties` 공개 ABI는 변경하지
  않는다.
- 새 `SqsBatchProperties` configuration-properties 타입만 추가한다.
- batching 기본값은 `false`이므로 기존 application의 AWS 호출 방식은 바뀌지 않는다.
- 새 bean 타입이므로 `SqsOperations` 및 listener 주입 후보가 늘지 않는다.
- 새 공개 data class와 enum은 Korean KDoc, `Serializable`, `serialVersionUID`, 직접
  API 테스트를 함께 제공한다.
- 새 dependency는 추가하지 않는다. 중앙 catalog가 제공하는 AWS SDK SQS `2.51.3`을
  사용한다.

### 운영 fallback과 코드 rollback

- 즉시 운영 fallback은 `bluetape4k.aws.sqs.batch.enabled=false`다. 새 operations API는
  유지하면서 background manager/scheduler 없이 direct transport로 전환한다.
- 구현 rollback은 batch operations/model/properties/auto-configuration/metrics 변경을 한
  단위로 되돌린다. lifecycle과 cancellation 일부만 남기는 부분 rollback은 금지한다.
- rollback 뒤 기존 `SqsOperations`/listener baseline, legacy ABI fixture와 disabled-mode
  classpath 테스트를 다시 실행한다. 새 API를 이미 사용한 consumer가 있으면 source
  rollback은 breaking change이므로 운영 fallback을 우선한다.

## 테스트 전략

구현 계획은 모든 동작을 RED에서 먼저 고정한다. 새 임의 entry ID와 queue suffix는
`Base58.randomString(16)`을 사용한다. 단언은
`bluetape4k-projects/testing/assertions`의 실제 공개 API를 우선한다.

- 값 비교: `shouldBeEqualTo`
- 크기: `shouldHaveSize`
- 상한/하한: `shouldBeLessOrEqualTo`, `shouldBeGreaterOrEqualTo`
- 문자열: `shouldContain`, `shouldNotContain`
- collection: `shouldContain`, `shouldNotContain`, `shouldBeEmpty`

boolean으로 우회하는 `(...).shouldBeTrue()`/`shouldBeFalse()`는 해당 의미의 직접
matcher가 없을 때만 사용하고 fallback 이유를 테스트에 남긴다.

### deterministic unit tests

- batch size 도달 전에는 전송하지 않고 도달 시 즉시 flush
- flush interval 도달 시 작은 batch 전송
- 서로 다른 coroutine의 요청이 같은 AWS batch로 병합
- 서로 다른 queue URL은 별도 batch로 분리
- 많은 queue URL과 send/delete 혼합에서 flush interval과 pending buffer 상한 유지
- direct mode의 단건 future fallback
- send/delete 성공, partial failure, 전체 failure와 입력 순서
- `RETURN`과 `THROW` 전략
- FIFO group/deduplication과 attributes 보존
- mutable input collection과 message attribute map의 admission snapshot
- 입력/property validation 후 외부 호출과 resource 생성 0회
- max entries per call, admission window와 template 전역 max in-flight 상한
- input이 `maxInFlightEntries + 1`일 때 다음 window 진행
- caller cancellation identity, sibling outcome 보존과 incomplete future cancellation
- same ID concurrent calls와 lifecycle internal token 비충돌
- 각 result list의 상대적 입력 순서와 같은 호출/동시 호출의 전송 순서 비보장
- close admission placeholder race, 정상 drain, timeout, concurrent/repeated close barrier
- slow manager close와 blocked scheduler가 전체 shutdown deadline 안에 caller를 반환
- close owner interrupt/unexpected failure에도 `CLOSED`와 completion 완료
- manager construction 실패 시 scheduler rollback과 thread 0개 잔존
- manager/executor cleanup failure 독립성·순서·safe exception
- missing/unknown/duplicate outcome normalizer 거부
- redacted result/exception/log/metric
- 모든 새 공개 모델의 serialization round trip

실제 sleep이나 wall-clock timing에 의존하지 않고 fake transport, 제어 가능한 future,
virtual time 또는 deterministic scheduler를 사용한다.

### Spring tests

- `batch.enabled` 기본 false와 property validation
- 별도 `SqsBatchProperties`와 변경 전 `SqsProperties` precompiled ABI fixture
- direct/batch mode bean 구성
- manager class가 없는 isolated classloader의 direct mode와 enabled startup failure
- custom `SqsBatchOperations` backoff
- standard `SqsAsyncClient`/listener bean이 그대로 유지됨
- Micrometer decorator와 exact low-cardinality tag key/value allow-list
- SQS SDK 또는 global/SQS enabled 조건이 false일 때 기존 조건과 일치

### emulator tests

Floci에서 다음 검증을 순차 실행한다.

- 여러 send가 실제 queue에 모두 도착
- FIFO group/deduplication과 attributes round trip
- deleteMany 뒤 메시지가 다시 조회되지 않음
- cancellation/close 뒤 process가 종료되고 resource가 남지 않음

Floci가 batch response의 entry 단위 실패를 안정적으로 만들 수 없다면 partial failure는
fake manager contract test를 기준 증거로 사용하고 emulator capability gap을 명시한다.
지원하지 않는 동작을 성공으로 보고하지 않는다.

## 문서

- `README.md`, `README.ko.md`, `aws-spring-boot/README.md`,
  `aws-spring-boot/README.ko.md`의 SQS 절에 direct fallback, automatic batching opt-in,
  partial result, FIFO, cancellation/close 주의사항을 같은 구조로 추가한다.
- `docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`와
  `docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`에 같은 API와
  설정을 반영하고 locale 구조를 맞춘다. 전체 manual chapter를 README에 복제하지
  않는다.
- KDoc은 한국어로 작성하고 API name, 설정 key, exception type은 그대로 유지한다.
- README/manual의 주입, YAML, `RETURN`/`THROW`, `deleteMany`, before/after 예제는
  `SqsBatchDocumentationExampleTest`의 `sqs-batch-kotlin` source region과
  `src/test/resources/documentation/sqs-batch/application.yaml`을 기준으로 작성한다. 여섯
  Markdown 문서는 `<!-- sqs-batch-kotlin:start|end -->`과
  `<!-- sqs-batch-yaml:start|end -->` marker 사이에 fenced snippet을 둔다. manual contract
  script는 Kotlin region에서 marker·공통 들여쓰기만 제거하고 YAML은 UTF-8/LF·끝 newline로
  정규화해 각 fenced body와 exact compare한다. 검증 명령은
  `./gradlew :bluetape4k-aws-spring-boot:test --tests '*SqsBatchDocumentationExampleTest'`로
  컴파일 검증한다. `ruby scripts/manual/manual_contract_test.rb`로 locale/manual contract도
  검증한다.
- Issue #461 본문은 최종 설계의 `RETURN`/`THROW`, retry 제외, lifecycle 경계와
  `## DoD Status`를 반영한다.

## 제외 범위

- Issue #454의 listener batch payload와 partial acknowledgement
- receive/change-visibility automatic batching
- Spring Integration outbound adapter
- outbound JSON/message converter SPI
- 공개 `BatchExecutionStrategy` 추상화
- 자동 selective retry와 보상 전송
- exactly-once 전달 보장
- 새 dependency 또는 AWS SDK version 변경

## 수용 기준

- [ ] `SqsBatchOperations.sendMany`와 `deleteMany`가 direct/batch mode에서 같은 결과
      계약을 제공한다.
- [ ] `batch.enabled=true`에서 size/flush interval에 따라 실제 SDK batch request로
      병합된다.
- [ ] 성공·실패가 caller entry ID와 입력 순서로 재현되고 partial failure가 숨겨지지
      않는다.
- [ ] `SendBatchFailureStrategy.RETURN`과 `THROW`가 정규화한 동일 결과를 기준으로
      동작한다.
- [ ] FIFO group/deduplication과 attributes가 보존되고 mutable 입력은 첫 suspension
      전에 snapshot된다.
- [ ] active future 수가 설정 상한을 넘지 않는다.
- [ ] cancellation은 원래 `CancellationException`과 future cancel 요청을 보존하며
      delivery rollback을 주장하지 않는다.
- [ ] close는 신규 admission 차단, bounded drain, manager/executor cleanup을 순서대로
      수행한다.
- [ ] 기존 `SqsOperations`, standard `SqsAsyncClient`, listener와 binary/source
      compatibility를 보존한다.
- [ ] 구조화된 결과의 상관관계 entry ID는 유지하되 결과 `toString()`, exception
      message, 로그, metric에는 payload·queue URL·receipt handle·entry ID·raw cause가
      노출되지 않는다.
- [ ] Base58와 bluetape4k assertions 규칙을 포함한 unit/Spring/Floci 검증이 통과한다.
- [ ] README locale, manual 영향, KDoc과 Issue DoD가 구현과 일치한다.

## 설계 DoD

- [x] 문제, 범위, 책임 경계와 외부 근거를 고정했다.
- [x] 세 대안을 비교하고 전용 `SqsBatchOperations`를 선택했다.
- [x] public API, config, partial result, failure strategy와 redaction을 정의했다.
- [x] concurrency, cancellation, close와 resource ownership을 정의했다.
- [x] 호환성, failure modes, 테스트, 문서와 제외 범위를 정의했다.
- [x] 여섯 관점의 독립 spec review에서 P0=0/P1=0을 확인했다.
- [x] 사용자가 review 결과와 최종 명세를 승인했다.

Final status: DONE — 독립 spec review PASS와 사용자 설계 승인을 확인했다.
