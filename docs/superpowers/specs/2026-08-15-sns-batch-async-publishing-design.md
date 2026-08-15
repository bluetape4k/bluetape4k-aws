# SNS 배치·비동기 퍼블리싱 parity 설계

> 대상 이슈: #456
> Epic: #499 (SNS/SQS Spring integration)
> 기준 브랜치: `develop` (`bd97ef16357a5cea93c10c60916d9bd54138409f`)
> 설계 초안 검토일: 2026-08-15
> 구현 전 사용자 승인: 2026-08-15 사용자 승인 완료

## 문제와 목표

현재 `aws-kotlin`에는 SNS `publishBatch` 요청 모델과 호출 확장이 있지만,
`aws-java`에는 SNS 배치·비동기 퍼블리싱 확장이 없고
`aws-spring-boot`의 `SnsOperations`/`SnsCoroutinesTemplate`도 단일
메시지만 지원한다. 결과적으로 Java SDK v2를 사용하는 호출자는 SDK의
배치 응답과 `CompletableFuture`를 직접 조립해야 하며, Spring coroutine
호출자는 10개 제한·부분 실패·대량 입력 분할을 직접 처리해야 한다.

이번 변경은 다음을 제공한다.

1. `aws-java`에 SNS SDK v2 배치 요청 DSL과 저수준 동기·비동기 확장을
   추가한다.
2. `aws-kotlin`의 기존 `publishBatch`도 Java와 같은 batch size·중복 ID
   조기 검증을 사용하도록 보완한다.
3. `aws-spring-boot`에 호출자 ID를 보존하는 batch request/result 모델과
   coroutine-first `SnsOperations.publishBatch`를 추가한다.
4. Spring 계층에서 10개 단위 자동 분할, 입력 순서 보존, 항목별 성공·실패
   집계, 명시적 bounded concurrency를 제공한다.
5. 기존 단일 publish 계약과 auto-configuration 호환성을 유지하고
   `README.md`와 `README.ko.md`에 동일한 사용 흐름을 기록한다.

## 현재 근거와 책임 경계

### 저장소 근거

| 근거 | 확인 내용 | 설계 영향 |
|---|---|---|
| `aws-java/src/main/kotlin/io/bluetape4k/aws/sns/SnsClientExtensions.kt` | 현재 동기 SNS 확장은 topic 생성 중심이며 batch publish가 없음 | SNS batch DSL을 같은 패키지에 추가 |
| `aws-java/src/main/kotlin/io/bluetape4k/aws/sns/SnsAsyncClientExtensions.kt` | `createTopicAsync` 등 `CompletableFuture` 명명 규칙이 존재 | 저수준 비동기 확장 이름을 `publishBatchAsync`로 통일 |
| `aws-java/src/main/kotlin/io/bluetape4k/aws/sns/model/Publish.kt` | `publishRequestOf`가 조기 검증과 SDK builder 매핑을 제공 | batch request/entry DSL도 같은 검증 경계 재사용 |
| `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/sns/SnsClientExtensions.kt` | `publishBatch`와 `publishBatchRequestEntryOf`가 이미 존재하며 현재 batch 함수는 `topicArn`만 조기 검증 | Java/Spring 모델의 필드명·ID 의미를 맞추고 batch size/중복 ID 검증을 함께 단일화 |
| `build.gradle.kts` | 현재 JVM interface default 모드가 `-jvm-default=enable`로 고정됨 | `SnsOperations` default method의 source/binary 호환성 검증을 이 모드에서 수행하고 컴파일러 모드는 변경하지 않음 |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsOperations.kt` | 단일 `publish`가 suspend API로 노출됨 | batch는 additive suspend 메서드로 확장 |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsCoroutinesTemplate.kt` | `SnsPublishRequest`를 AWS `PublishRequest`로 매핑하고 `.await()` 사용 | batch 호출도 기존 template와 coroutine lifecycle을 사용 |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsPublishRequest.kt` | topic suffix에 따른 FIFO 필드 검증과 message attribute 매핑이 있음 | batch entry의 검증·매핑 규칙을 단일 publish와 일치 |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchModels.kt` | 성공·실패 결과 모델, 10개 제한, 빈 입력 계약이 있음 | SNS 결과·배치 경계의 명명과 테스트 구조를 차용 |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/NoopSnsOperations.kt` | interface의 모든 메서드를 구현하는 테스트 double | interface 확장 시 compile-time parity를 보장하도록 갱신 |

AWS SDK v2.51.3의 `PublishBatchRequest`는 최대 10개 entry를 받고,
`PublishBatchResponse`는 `successful`과 `failed`를 별도 반환한다. HTTP
200이어도 일부 entry가 실패할 수 있으므로 전송 성공과 항목 성공을
분리한다.

공식 근거:

- AWS SNS `PublishBatch` API:
  <https://docs.aws.amazon.com/sns/latest/api/API_PublishBatch.html>
- Spring Cloud AWS SNS batch/async 참고 구현:
  <https://github.com/awspring/spring-cloud-aws/blob/main/docs/src/main/asciidoc/sns.adoc>

### 책임 경계

- `aws-java`: AWS SDK 호출 한 번을 감싸고 1~10개 요청을 검증한다. 입력을
  여러 AWS 요청으로 분할하지 않는다.
- `aws-spring-boot`: 애플리케이션 입력을 검증하고 10개 단위로 분할하며,
  bounded concurrency와 전체 결과 순서를 관리한다.
- AWS SDK: 네트워크 전송, 인증, 항목별 성공·실패 응답을 책임진다.
- 호출자: 각 entry의 고유 `id`와 메시지 내용을 제공한다. 라이브러리는
  ID를 생성하거나 재사용하지 않는다.

## 선택한 설계

### `aws-java` 저수준 API

기존 SNS 패키지에 다음 additive 확장을 추가한다.

- `publishBatchRequestEntryOf(id, message, messageAttributes?, messageDeduplicationId?, messageGroupId?, builder)`
- `publishBatchRequestOf(topicArn, entries, overrideConfiguration?, builder)`
- `SnsClient.publishBatch(topicArn, entries, builder)` DSL/파라미터 overload
- `SnsAsyncClient.publishBatchAsync(request)` → `CompletableFuture<PublishBatchResponse>`
- `SnsAsyncClient.publishBatchSuspend(request)` suspend 확장 → `.await()`

`publishBatchSuspend`는 AWS SDK의 `publishBatch(PublishBatchRequest)` 멤버와
동일한 Kotlin 시그니처를 피한다. 이는 기존 `listQueuesSuspend`와 같은
명시적 suspend 명명 규칙이며, SDK 멤버가 extension을 가리는 문제를
방지한다.

저수준 검증은 다음 순서로 조기에 수행한다.

1. `topicArn`은 공백이 아니어야 한다.
2. entry 목록은 비어 있지 않고 10개 이하여야 한다.
3. 모든 `id`와 `message`는 공백이 아니어야 한다.
4. entry ID는 요청 안에서 중복되지 않아야 한다.

로컬 검증은 위 구조·식별자·FIFO 계약과 batch 개수만 담당한다. SNS message
byte size, attribute key/type/value 제약, subject/group/dedup 서비스 제한,
topic ARN의 완전한 형식 검사는 AWS SDK/service에 위임하며, 해당 경계값은
SDK response/exception fixture로 회귀 검증한다. diagnostics에는 원문을
복사하지 않고 field 존재 여부·개수와 bounded/escaped ID fingerprint만 남긴다.
운영 로그에는 raw ID를 기록하지 않는다. `failureType`은
`SDK_SERVICE`, `CLIENT`, `TIMEOUT`, `UNKNOWN`의 유한 allowlist에서만 선택한다.

동일한 batch size·중복 ID 검증은 `aws-kotlin`의 기존 `publishBatch`에도
적용한다. 기존에는 SDK 호출 단계에서만 제한이 드러났지만, 이제 Java·Kotlin
양쪽이 호출자 입력을 같은 조기 검증 경계에서 거부한다.

SDK의 `PublishBatchResponse`는 저수준 API에서 변환하지 않는다. 따라서
SDK가 제공하는 `code`, `message`, `senderFault`, `messageId`,
`sequenceNumber`를 손실 없이 사용할 수 있다.

### Spring request/result 모델

다음 immutable `Serializable` data class를 `spring.sns` 패키지에 둔다.
각 data class와 `SnsBatchExecutionOptions`는 기존 `SnsPublishRequest`와
같이 `serialVersionUID = 1L`을 선언한다.

```kotlin
data class SnsPublishBatchEntry(
    val id: String,
    val message: String,
    val subject: String? = null,
    val messageAttributes: Map<String, MessageAttributeValue> = emptyMap(),
    val messageGroupId: String? = null,
    val messageDeduplicationId: String? = null,
): Serializable {
    companion object { private const val serialVersionUID: Long = 1L }

    override fun toString(): String =
        "SnsPublishBatchEntry(idPresent=${id.isNotEmpty()}, messagePresent=${message.isNotEmpty()}, " +
            "attributeCount=${messageAttributes.size}, fifo=${messageGroupId != null})"
}

data class SnsPublishBatchRequest(
    val topicArn: String,
    val entries: List<SnsPublishBatchEntry>,
): Serializable {
    companion object { private const val serialVersionUID: Long = 1L }

    override fun toString(): String =
        "SnsPublishBatchRequest(topicPresent=${topicArn.isNotEmpty()}, entryCount=${entries.size})"
}

data class SnsPublishBatchResult(
    val successful: List<SnsPublishBatchSuccess>,
    val failed: List<SnsPublishBatchFailure>,
): Serializable {
    companion object { private const val serialVersionUID: Long = 1L }

    val isFullySuccessful: Boolean get() = failed.isEmpty()

    override fun toString(): String =
        "SnsPublishBatchResult(successfulCount=${successful.size}, failedCount=${failed.size})"
}

data class SnsPublishBatchSuccess(
    val entryId: String,
    val messageId: String,
    val sequenceNumber: String? = null,
): Serializable {
    companion object { private const val serialVersionUID: Long = 1L }

    override fun toString(): String = "SnsPublishBatchSuccess(entryIdPresent=${entryId.isNotEmpty()})"
}

data class SnsPublishBatchFailure(
    val entryId: String,
    val code: String?,
    val message: String?,
    val senderFault: Boolean,
): Serializable {
    companion object { private const val serialVersionUID: Long = 1L }

    override fun toString(): String =
        "SnsPublishBatchFailure(entryIdPresent=${entryId.isNotEmpty()}, codePresent=${!code.isNullOrEmpty()}, " +
            "senderFault=$senderFault)"
}

data class SnsBatchExecutionOptions(
    val maxInFlightBatches: Int = 1,
): Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}
```

각 data class에는 `companion object { private const val serialVersionUID: Long = 1L }`
을 두고, 생성자 필드에 메시지 본문·topic ARN·attribute 값을 직접 노출하는
기본 `toString()`을 사용하지 않는다. 위 예시는 공개 필드와 관계를 보여 주며,
실제 구현은 기존 값 객체의 KDoc·검증 형식과 redacted diagnostics 규칙을 따른다.

`SnsPublishBatchEntry`, `SnsPublishBatchRequest`, `SnsPublishBatchResult`,
`SnsPublishBatchFailure`의 `toString()`은 ID·ARN·본문·attribute 값·SDK 오류
원문을 출력하지 않고 존재 여부와 개수만 출력한다. `SnsBatchProtocolException`과
`SnsBatchTransportException`도 같은 규칙을 따르며, CR/LF를 포함한 원문 예외
메시지를 로그·예외 문자열에 재출력하지 않는다. 운영 로그는 lifecycle, chunk
개수, 성공·실패 개수, bounded concurrency처럼 low-cardinality 정보만 기록한다.

`SnsPublishBatchEntry`는 `id`·`message`를 검증한다. request는 topic ARN과
전체 ID의 고유성을 검증하고, 기존 `SnsPublishRequest`와 동일한 규칙으로
standard topic의 FIFO 필드를 거부하며 `.fifo` topic의
`messageGroupId` 누락을 거부한다. `subject`, attributes, FIFO 필드는
단일 publish와 같은 AWS SDK builder 필드로 매핑한다.

성공·실패 결과는 다음 정보를 보존한다.

- `SnsPublishBatchSuccess`: `entryId`, `messageId`, `sequenceNumber`
- `SnsPublishBatchFailure`: `entryId`, `code`, `message`, `senderFault`
- `SnsPublishBatchResult.isFullySuccessful`: `failed`가 비어 있는지 여부

`successful`과 `failed`는 각각 원본 입력 순서로 정렬하며 두 목록을 위치로
짝지을 수 없다. 호출자는 `entryId`로 원본 entry와 결합한다. AWS 응답에 알 수 없는 ID, 중복 ID,
입력에 있었지만 응답에서 누락된 ID가 있으면 `SnsBatchProtocolException`을
던져 조용한 결과 유실을 막는다.

`SnsBatchTransportException`은 batch 전송 경계에서 결과를 반환할 수 없을 때
사용한다. `completedEntryIds`는 **transport terminal response를 받은 entry의
집합**이다. 따라서 응답을 받은 chunk 안에서는 항목별 성공 ID와 실패 ID를
구분하지 않으며, item-level 성공 ID나 재시도 가능 ID를 뜻하지 않는다. 순차
fallback에서는 성공한 단건 prefix만 이 집합에 들어간다. 병렬 경로에서는
응답을 받은 전체 chunk의 모든 entry ID가 들어가며, 그 목록 밖 entry가
발행되지 않았다는 뜻도 아니다. 원본 payload·ARN·attribute·예외
message는 보관하거나 `toString()`에 포함하지 않는다. 원 transport 예외는
`cause`/suppressed exception으로 공개하지 않고, 제한된 `failureType`만 남긴다.
따라서 stack trace·`getMessage()`·로그에도 비밀·페이로드·CR/LF가 재출력되지
않는다. `CancellationException`에는 이 wrapper를 사용하지 않는다. 저수준
`aws-java` `.await()` 경계는 SDK 예외를 호출자에게 그대로 전달하지만, Spring
batch 경계에서는 이 안전한 wrapper로 정규화한다. 이 예외는 "이미 성공한
prefix가 있을 수 있으므로 전체 request를 자동 재시도하지 말라"는 호출자
계약을 표현한다. 특히 병렬 chunk 중 하나가 실패하면 취소된 sibling과 현재
chunk의 일부 entry가 이미 SNS에 도달했을 수 있으므로, `completedEntryIds` 밖의
entry를 선택적으로 재시도하지 않는다. caller가 재시도해야 한다면 FIFO
deduplication 또는 별도 idempotency 키를 사용해 전체 request를 중복 안전하게
만들 책임이 있다. 순차 fallback에서만 성공 prefix 뒤의 미시도 suffix를
재처리할 수 있다.

### Spring 실행과 backpressure

`SnsOperations`에 다음 additive API를 추가한다.

```kotlin
suspend fun publishBatch(
    request: SnsPublishBatchRequest,
    options: SnsBatchExecutionOptions = SnsBatchExecutionOptions(),
): SnsPublishBatchResult
```

기존 외부 `SnsOperations` 구현체의 source compatibility를 지키기 위해
interface 메서드는 순차 단건 publish fallback을 기본 구현으로 제공한다.
현재 root `build.gradle.kts`의 `-jvm-default=enable`을 유지해 JVM default
method로 컴파일하고, precompiled 외부 구현체 fixture를 추가해 기존 바이너리가
새 interface를 로드·호출할 수 있는지 검증한다. 이 fixture가 실패하면 구현을
진행하지 않고 ABI 경계를 재설계한다.

기본 구현은 각 entry를 기존 `publish(SnsPublishRequest)`로 순차 처리한다.
이는 원자적 batch가 아니며, 첫 non-cancellation 예외에서 즉시 중단한다. 이미
성공한 prefix는 `SnsBatchTransportException.completedEntryIds`에 기록하고,
부분 결과를 반환하거나 자동 재시도하지 않는다. `CancellationException`은
그대로 재전파한다. 호출자는 `completedEntryIds`를 확인한 뒤 남은 ID만 별도로
재처리해야 하며, 전체 request 재시도는 금지한다. `maxInFlightBatches`는 이
fallback에서는 항상 1로 해석한다.

`SnsCoroutinesTemplate`는 AWS `PublishBatch`를 사용하도록 override하여
chunking과 bounded concurrency를 구현한다. chunk transport 실패도
`SnsBatchTransportException`으로 정규화하고, 이미 완료된 chunk의 entry ID와
안전한 `failureType`만 남긴다. 원본 exception은 공개 예외·suppressed 목록·로그에
복사하지 않는다. 항목별 AWS 실패는 정상 결과로 반환한다.

`SnsBatchExecutionOptions(maxInFlightBatches: Int = 1)`은 1 이상인 값만
허용한다. `SnsCoroutinesTemplate`는 다음 순서로 실행한다.

1. 빈 입력은 SDK 호출 없이 빈 `SnsPublishBatchResult`를 반환한다.
2. 입력을 원본 index와 함께 lazy iterator로 10개 단위 chunk에 공급한다.
   `N == 0`이면 호출은 0회이고, 그 밖에는 정확히 `ceil(N / 10)`회 호출한다.
3. `maxInFlightBatches == 1`이면 chunk를 순서대로 처리한다.
4. 더 큰 값이면 `coroutineScope` 안에 `min(maxInFlightBatches, chunkCount)`개의
   고정 worker만 만든다. worker는 공용 `Mutex`로 iterator에서 다음 chunk를
   하나씩 가져온 뒤 외부 호출을 수행하며, chunk마다 `launch`하거나 무제한
   queue를 만들지 않는다. worker가 `Semaphore` credit을 먼저 확보하고
   ordered collector가 해당 sequence를 최종 반영한 뒤 credit을 반환한다.
   결과 채널은 rendezvous(`capacity = 0`)로 두어 collector가 순서를 확인하기
   전 결과를 추가로 적재하지 않으며, 이 credit과 채널 조합이 외부 호출·pending
   window를 worker 수 이내로 제한한다.
5. 각 worker는 자신이 처리한 chunk 결과만 반환하고, 최종 조립 단계에서
   원본 index와 entry ID로 검증·변환한 뒤 입력 순서로 재조합한다. 이 결과
   조립은 호출자에게 반환하는 O(N) 출력이며 pending task/queue와 분리한다.

항목별 실패는 결과에 담지만, SDK 호출 자체의 transport/service 예외는
결과로 숨기지 않고 안전한 `SnsBatchTransportException`으로 던진다. 하나의
chunk가 transport 예외로 실패하면 structured concurrency가 sibling chunk를
취소하고 raw cause를 노출하지 않는 wrapper를 전파한다.
`publishBatchSuspend`는 SDK `CompletableFuture.await()`를 사용하므로 caller가
취소하면 underlying future도 취소한다. Spring template도 같은 await 경계를
사용한다. coroutine 취소 시에는 `CancellationException`을 먼저 전파하고 child 작업과
underlying `CompletableFuture`를 취소한다. worker와 rendezvous result channel은
구조화된 scope 종료 시 정리한다. 정상 경로에서는 ordered collector가 각
semaphore credit을 반환하고, 빈 claim은 worker가 자신의 credit을 반환한 뒤
종료한다. 취소·transport failure에서는 operation-local scope가 worker와
collector를 함께 종료하므로 남은 credit은 재사용 대상이 아니다. 별도의 재시도나
unbounded parallelism은 이번 범위에 포함하지 않는다.

취소는 best-effort이며, 취소 시점에 이미 SNS에 도달한 in-flight publish를
rollback하거나 보상할 수 없다. `CancellationException`에는 완료 ID 집합을
추가하지 않으므로 caller가 재시도할 때도 FIFO deduplication 또는 별도
idempotency key를 사용해 중복 발행을 방지해야 한다.

SNS batch에는 rollback이나 보상 트랜잭션이 없다. 이미 SNS에 도달한 메시지는
라이브러리가 회수하지 않으므로, mixed success·partial send 뒤의 reconciliation과
중복 방지는 caller가 FIFO deduplication 또는 별도 idempotency key로 처리한다.

고정 worker가 동시에 보유하는 중간 chunk/entry resident 수는
`10 * maxInFlightBatches` 이하이며, worker 수·pending task 수·iterator
대기열도 같은 상한으로 제한한다. 최종 `SnsPublishBatchResult`의 O(N) 목록은
호출자에게 반환하는 출력이며, 중간 작업 메모리 상한과 분리해 문서화한다.

### 자동 구성·호환성

- 기존 `SnsOperations` 단일 publish 메서드와 기존 constructor/default를
  변경하지 않는다.
- 새 메서드만 interface에 추가하고 `NoopSnsOperations`와 모든 구현체를
  컴파일 가능하게 갱신한다.
- interface default fallback은 기존 구현체가 새 메서드를 재정의하지 않아도
  동작하게 하며, `NoopSnsOperations`는 deterministic noop 성공 결과를
  명시적으로 반환한다. source compatibility와 binary compatibility를
  구분해 기록하고, 전자는 기존 구현체 compile 테스트, 후자는 precompiled
  consumer fixture와 `javap`/실행 테스트로 검증한다.
- 새 Spring bean이나 새 외부 dependency를 추가하지 않는다.
- AWS SNS SDK 의존성은 기존 `compileOnly(libs.aws2.sns)`와 catalog를
  그대로 사용한다.
- 현재 단일 publish에 converter 추상화가 없으므로 JSON converter를
  새로 만들지 않는다. 문자열 message와 `MessageAttributeValue` 매핑을
  유지하고, JSON 직렬화는 호출자 책임으로 명시한다.
- `SnsAsyncClient`의 생성·소유·close는 기존 caller/auto-configuration 책임을
  유지한다. 이번 변경은 client를 닫지 않으며 timeout·retry 정책도 SDK/client
  설정 범위로 남긴다.

## 제외한 대안

1. **호출자가 10개 단위로 직접 분할**: AWS API의 단일 호출만 감싸면
   구현은 작지만, 이슈의 11개 이상 자동 분할·결과 집계 조건을 만족하지
   못하므로 제외한다.
2. **Spring Cloud AWS와 동일한 공개 `BatchExecutionStrategy` 및 converter
   계층 도입**: 참고 구현의 방향은 확인했지만 현재 저장소에는 해당
   converter 계약이나 전략 확장 요구가 없다. 이번 변경에 공개 추상화를
   추가하면 API·문서·호환성 표면이 불필요하게 커지므로
   `maxInFlightBatches` 옵션으로 bounded concurrency만 제공한다.
3. **ID 자동 생성**: 호출자가 결과를 외부 요청과 연결할 수 없고 partial
   failure 재처리가 불명확해진다. 따라서 ID는 호출자 입력으로 고정한다.

## 실패 모드와 대응

| 실패 모드 | 대응 | 검증 |
|---|---|---|
| 빈 Spring 입력 | SDK 호출 없이 빈 결과 반환 | Spring template 테스트 |
| 10개 초과 저수준 요청 | 조기 `IllegalArgumentException` | Java DSL 단위 테스트 |
| 중복·공백 entry ID | request/entry 생성 시 조기 거부 | Spring 모델 테스트 |
| 11개 이상 입력 | 10개 chunk로 분할하고 전체 순서 재조합 | 1/10/11+ MockK 테스트 |
| AWS 항목별 mixed success/failure | 성공·실패 결과에 각각 보존 | response fixture 테스트 |
| FIFO 필드 불일치 | 단일 publish와 같은 topic suffix 검증 | standard/FIFO 음성 테스트 |
| 응답 ID 누락·중복·미지 ID | `SnsBatchProtocolException` | protocol guard 테스트 |
| transport 예외 | 결과로 숨기지 않고 Spring 경계에서 안전한 `SnsBatchTransportException`으로 전파 | failed future/redaction 테스트 |
| 단건 fallback 중간 실패 | 성공 prefix를 기록하고 즉시 중단, 자동 재시도 금지 | prefix failure/retry safety 테스트 |
| coroutine cancellation | `CancellationException` 보존 및 child 취소 | `runTest` 취소 테스트 |
| underlying future/worker 정리 | 취소·transport failure 뒤 future 취소와 worker/collector 종료 | cancellation/race 테스트 |
| sibling chunk transport failure | sibling 취소 후 safe wrapper 전파, 완료 목록 밖 selective retry 금지 | structured concurrency/retry safety 테스트 |
| partial send 후 rollback 요청 | rollback/보상 트랜잭션을 제공하지 않고 caller reconciliation으로 전환 | rollback boundary 문서·테스트 |
| emulator의 PublishBatch 미지원 | 결정성 mock 테스트를 필수로 하고 real smoke는 opt-in | 테스트 분류/skip 증거 |

## 수용 기준과 테스트

### 필수 테스트

테스트 ID와 FIFO topic 이름에는 저장소의 `io.bluetape4k.codec.Base58`을
사용하고, batch entry ID는 `Base58.randomString(16)`으로 생성한다.

- Java request DSL: 1개·10개 성공, 빈 입력·11개·중복 ID 거부
- Kotlin `publishBatch`: Java와 같은 1..10개·중복 ID 조기 검증
- `SnsOperations` default fallback: 성공 prefix 후 첫 예외에서 중단하고
  `SnsBatchTransportException.completedEntryIds`·`failureType`을 보존하며
  raw cause/message를 노출하지 않음
- Spring template: `N=0`은 0회, `N>0`은 정확히 `ceil(N/10)`회 호출
  (`N=1/9/10/11/20/21/large` 경계 포함)
- chunk 결과를 원본 입력 순서로 재조합
- mixed success/failure와 각 필드 보존
- `successful`/`failed` 각 목록의 입력 순서와 `entryId` 결합 규칙
- 여섯 public model(`Entry`, `Request`, `Result`, `Success`, `Failure`, `Options`)의
  Java serialization round-trip과 `serialVersionUID` 고정
- payload·ARN·attribute·raw SDK error가 `toString()`·로그·예외 문자열에
  노출되지 않고 stack trace에도 CR/LF가 재출력되지 않는 redaction 회귀
- precompiled `SnsOperations` consumer의 `-jvm-default=enable` binary ABI
  로드/호출
- message attributes, subject, FIFO group/dedup 매핑
- standard/FIFO 검증과 protocol ID 검증
- `maxInFlightBatches`가 실제 동시 호출 수를 넘지 않음
- 중간 chunk/entry resident 수가 `10 * maxInFlightBatches`를 넘지 않음
- cancellation이 원래 `CancellationException`을 보존
- cancellation 시 underlying future 취소와 operation-local worker/collector 종료
- transport 실패가 항목별 결과로 숨겨지지 않음
- sibling chunk 취소와 원 transport 예외 전파
- 병렬 실패에서 완료 목록 밖 entry를 재시도하지 않는 sibling partial-send 안전성
- mixed-success 선행 chunk의 성공·실패 ID가 모두 terminal-response 집합에
  포함되고 selective retry가 거부되는지 검증
- low-level `CompletableFuture` cancellation이 underlying future에 전달됨
- `NoopSnsOperations`와 auto-configuration compile/test parity

emulator 테스트는 기존 `-Dbluetape4k.aws.emulator=floci` 기본 정책을
따르되, backend capability가 확인되지 않으면 결정성 mock 테스트만
필수 증거로 사용한다. Testcontainers/emulator 검증은 다른 모듈과
동시에 실행하지 않는다.

### 문서·예제

- `README.md` SNS 절에 low-level async와 Spring coroutine batch 사용법,
  10개 분할, 부분 실패, backpressure, FIFO 주의사항을 추가한다. Java
  `publishBatchAsync`/`publishBatchSuspend`, Spring batch, partial failure,
  default fallback의 prefix 중단·재시도 금지, 지원하지 않는 converter/strategy
  확장을 함께 보여 주는 복사 가능한 예제를 포함한다. mixed-success 선행
  chunk와 sibling transport failure가 함께 발생하면 전체 request를 재처리하지
  않고, FIFO deduplication 또는 외부 idempotency가 없을 때 수동 reconciliation을
  수행하며 rollback/보상 트랜잭션은 제공되지 않는다는 절차도 기록한다.
  `aws-kotlin`의
  `publishBatch`가 1~10개·중복 ID를 SDK 호출 전에 거부한다는 계약도 함께
  기록한다.
- `README.ko.md`에 같은 구조와 기술 의미를 자연스러운 한국어로 반영한다.
- 기존 `examples/aws-spring-boot-sqs-examples`의 SNS fanout 예제에
  batch 호출을 억지로 섞지 않고, 독립적인 deterministic unit fixture와
  opt-in emulator 경계를 문서에서 설명한다.

## 범위 밖 후속 작업

- 외부 publisher latency/cleanup telemetry와 실제 heap·throughput 측정은
  이번 구현에서 절대 성능을 주장하기 위한 수용 기준이 아니다. 측정
  환경·반복·기준값·목표값·stop condition은 후속 이슈
  [#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)에서
  고정한다. 이번 구현은 중간 resident 상한과 cleanup 계약만 검증한다.
- 재시도 정책, JSON converter SPI, 공개 `BatchExecutionStrategy`,
  emulator backend별 PublishBatch capability 확장은 후속 이슈
  [#514](https://github.com/bluetape4k/bluetape4k-aws/issues/514)에서
  조사·결정한다.

## 설계 DoD

- [ ] `aws-java` 저수준 DSL·동기·`CompletableFuture`·suspend 경계가 현재
      SNS 확장 규칙과 일치한다.
- [ ] `aws-spring-boot` request/result/operations/template/no-op 구현이
      ID·FIFO·attributes·부분 실패·취소 계약을 보존한다.
- [ ] `aws-kotlin` 기존 `publishBatch`도 Java와 같은 batch size·중복 ID
      조기 검증을 수행한다.
- [ ] 기존 `SnsOperations` 구현체가 default fallback으로 source-compatible하고
      precompiled consumer fixture가 binary-compatible하며, template은 AWS
      batch 경로를 사용한다.
- [ ] 여섯 public model(`Entry`, `Request`, `Result`, `Success`, `Failure`,
      `Options`)이 `Serializable`과 `serialVersionUID = 1L`을 실제로 선언하고
      serialization round-trip을 통과한다.
- [ ] payload·ARN·attribute·raw SDK error redaction과 CR/LF-safe diagnostics가
      테스트로 증명된다.
- [ ] 10개 제한, 11개 이상 자동 분할, 순서 재조합, bounded concurrency가
      RED/GREEN 테스트로 증명된다.
- [ ] 중간 resident 상한·underlying future 취소·permit 반환·sibling 실패가
      결정성 테스트로 증명된다.
- [ ] `README.md`와 `README.ko.md`가 API·예제·제약을 서로 대응한다.
- [ ] 승인된 설계와 구현 계획이 커밋되고, Type-A 리뷰에서 P0/P1이 0이다.
- [ ] 후속 측정 이슈가 latency/cleanup telemetry 및 heap/throughput 범위를
      잊지 않도록 [#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)에
      기준값·목표값·중단 조건과 함께 명시한다.
