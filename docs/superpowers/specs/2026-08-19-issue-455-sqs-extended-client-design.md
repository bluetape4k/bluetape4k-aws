# SQS Extended Client 설계

> 대상 이슈: [#455](https://github.com/bluetape4k/bluetape4k-aws/issues/455)
> Epic: [#499](https://github.com/bluetape4k/bluetape4k-aws/issues/499)
> 기준 브랜치: `develop` (`81a77815c971d2b0d5bc9306aca15b3245949b41`)
> 설계 승인: 2026-08-19 사용자 승인 완료

## 문제와 목표

현재 `SqsOperations`는 메시지 본문을 SQS `SendMessage` 요청에 그대로 넣고,
`S3Operations`는 S3 객체 작업만 제공합니다. 두 작업을 연결하는 payload
offload 계약이 없으므로 큰 메시지를 보내는 애플리케이션은 S3 객체 생성,
pointer envelope 작성, 수신 복원, ack 이후 객체 삭제를 직접 구현해야 합니다.

이번 변경은 다음 동작을 **명시적으로 선택한 호출자**에게 제공합니다.

1. UTF-8 byte size가 설정한 `offloadThresholdBytes`를 초과한 본문을 S3에
   저장하고 SQS에는 versioned pointer envelope만 보낸다.
2. pointer를 엄격하게 검증하고 S3 객체를 읽어 원래 본문과 content type을
   복원한다.
3. SQS ack가 성공한 뒤에만 `deleteOnAck` 정책에 따라 S3 객체를 삭제한다.
4. SQS/S3 부분 실패와 재배달을 호출자가 재시도할 수 있는 상태로 표현하고,
   SQS 전송이 불확실할 때 유효한 pointer를 삭제하지 않아 orphan cleanup은
   명시된 S3 lifecycle 정책으로 안전하게 처리한다.
5. 기존 `SqsOperations` 사용자와 작은 메시지의 wire format에는 영향을 주지
   않는다. 다만 offload producer를 켜기 전에는 모든 consumer를 extended
   consumer로 drain/migrate해야 하며, Spring Boot 자동 설정은 기본적으로
   비활성화한다.

AWS SQS의 현재 메시지 본문 상한은 1 MiB이다. AWS Extended Client 문서의
256 KiB 기준은 호환성과 운영에서 자주 사용하는 offload 기준이지 서비스의
하드 상한으로 해석하지 않는다.

- [Amazon SQS message quotas](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/quotas-messages.html)
- [Managing large Amazon SQS messages using Java and Amazon S3](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-s3-messages.html)
- [Amazon SQS Extended Client Library for Java](https://github.com/awslabs/amazon-sqs-java-extended-client-lib)

## 현재 근거와 책임 경계

| 근거 | 현재 계약 | 설계 영향 |
|---|---|---|
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsOperations.kt` | `send`, `receive`, `delete`, `receiveFlow`가 coroutine 계약으로 공개됨 | 기존 interface를 decorator로 몰래 바꾸지 않고 명시적 확장 interface를 추가한다 |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsCoroutinesTemplate.kt` | AWS SDK future를 `.await()`하고 SQS message attribute/system attribute를 보존함 | 기본 SQS 호출과 client lifecycle을 위임한다 |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3Operations.kt` | byte upload/download/delete와 `contentType`을 제공함 | 일반 payload 저장소로 재사용한다 |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionOperations.kt` | KMS data key + AES-GCM envelope encryption을 opt-in으로 제공함 | 암호화 정책이 켜진 pointer는 기존 암호화 작업 계약을 재사용한다 |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsProperties.kt` | SQS listener와 queue 설정이 있지만 offload 정책은 없음 | 별도 `SqsExtendedClientProperties`로 opt-in 경계를 분리한다 |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsAutoConfiguration.kt` | SQS client와 기본 `SqsOperations`를 조건부로 생성하고 `close`한다 | extended adapter는 기존 client bean을 주입받고 새 client를 만들거나 닫지 않는다 |
| `aws-spring-boot/build.gradle.kts` | S3/SQS/KMS SDK는 `compileOnly`, 테스트에서만 구현 dependency를 상속함 | 새 runtime dependency를 추가하지 않는다 |
| `S3AutoConfiguration` | `S3ClientSideEncryptionOperations`는 KMS bean과 명시적 property가 모두 필요함 | 암호화 설정이 유효하지 않으면 bean 생성/첫 offload에서 fail closed한다 |

GNO에서 `bluetape4k-aws`의 기존 Extended Client 설계·운영 기록은 확인되지
않았고, 현재 live GitHub Issue와 저장소 소스 및 AWS 공식 문서를 기준으로
설계했다. GNO 검색 결과가 비어 있다는 사실을 기능 계약의 근거로 사용하지
않는다.

## 범위와 제외 범위

### 포함

- `aws-spring-boot`의 coroutine-native SQS extended client API와 immutable
  request/result/pointer 모델
- `offloadThresholdBytes`(기본 `262_144`), `maxInlineBytes`(기본
  `1_048_576`), `maxOffloadPayloadBytes`(기본 `67_108_864`)의 byte-size 검증
- 명시적인 queue URL allowlist와 queue URL별 정책을 통한 endpoint/queue opt-in
- S3 일반 저장과 기존 `S3ClientSideEncryptionOperations`를 통한 선택적
  client-side encryption
- HMAC으로 queue/policy/bucket/key/content type/encryption context를 묶은
  authenticated pointer version/type/bucket/key/content type 검증
- S3 upload → SQS pointer send → receive restore → SQS ack → S3 cleanup의
  순서와 실패 상태
- low-cardinality offload/orphan/read/cleanup failure metrics와 redacted
  diagnostic code
- unit test, deterministic fake 기반 partial failure test, Floci 우선
  unencrypted round-trip smoke test, README/KDoc 비용·보안·lifecycle 문서

### 제외

- 기존 `SqsOperations`의 기본 동작이나 기존 메시지 body wire format 변경
- 기존 `SqsMessageListenerContainer`를 자동으로 extended client로 교체하는
  hidden wrapping
- AWS Java Extended Client library의 직접 의존·pointer format 상호운용
- 공개 `BatchExecutionStrategy`, converter SPI, 일반 retry framework
  ([#514](https://github.com/bluetape4k/bluetape4k-aws/issues/514) 후속)
- 외부 publisher latency/cleanup telemetry 및 실제 heap·throughput 측정
  ([#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515) 후속)
- SQS 전체 queue의 orphan object를 자동으로 스캔·삭제하는 위험한 batch job

## 선택한 설계

### 1. 명시적 coroutine-native API

기존 `SqsOperations`를 구현하거나 교체하지 않고 다음 additive 계약을
추가한다. 호출자는 이 interface를 주입받아 사용할 때만 extended path를
선택한다.

```kotlin
interface SqsExtendedClientOperations {
    suspend fun send(request: SqsExtendedSendRequest): SqsExtendedSendResult

    suspend fun receive(
        queueUrl: String,
        maxMessages: Int = 1,
        waitTimeSeconds: Int = 20,
        visibilityTimeoutSeconds: Int = 30,
    ): List<SqsExtendedReceivedMessage>

    suspend fun acknowledge(
        message: SqsExtendedReceivedMessage,
    ): SqsExtendedAcknowledgementResult

    suspend fun cleanup(handle: SqsExtendedCleanupHandle): SqsExtendedCleanupResult

    fun receiveFlow(
        queueUrl: String,
        maxMessages: Int = 1,
        waitTimeSeconds: Int = 20,
        visibilityTimeoutSeconds: Int = 30,
    ): Flow<SqsExtendedReceivedMessage>

    /**
     * 새 작업을 막고 이미 시작된 작업이 끝날 때까지 기다린다. AWS client의
     * 소유권은 Spring에 있으므로 이 drain을 context close보다 먼저 호출한다.
     */
    suspend fun drain(timeout: Duration? = null): SqsExtendedDrainResult
}

data class SqsExtendedDrainResult(
    val activeAtStart: Int,
    val completed: Int,
    val timedOut: Boolean,
) {
    init {
        require(activeAtStart >= 0)
        require(completed in 0..activeAtStart)
        require(timedOut || completed == activeAtStart)
    }
}

/**
 * FIFO/message-attribute를 포함한 전체 `SqsSendRequest`를 보존한다는
 * additive capability marker다. 기존 `SqsOperations`는 변경하지 않는다.
 */
interface SqsFullRequestOperations : SqsOperations {
    override suspend fun send(request: SqsSendRequest): SendMessageResponse
}

`SqsCoroutinesTemplate`는 `SqsFullRequestOperations`를 구현한다. 일반
`MicrometerSqsOperations`는 기존처럼 `SqsOperations`만 구현하고, marker
delegate를 받을 때만 사용하는 `MicrometerFullRequestSqsOperations` 변형이
`SqsFullRequestOperations`를 구현한다. Micrometer auto-configuration은
delegate capability를 판별해 **두 wrapper를 동시에 등록하지 않는다**. full
marker delegate가 있으면 full wrapper 하나만 `@Primary`로 노출하고 일반
wrapper 조건은 `SqsFullRequestOperations` bean 존재를 이유로 back-off한다.
markerless 사용자 delegate만 있는 context에서는 template-bound인 두
auto-configured wrapper 모두 back-off하고, 기존 markerless delegate를 그대로
주입한다. 사용자 정의 full delegate가 있으면 역시 두 auto-configured
wrapper 모두 back-off한다. 따라서 extended adapter는
반드시 `SqsFullRequestOperations`를 주입받고, marker가 없는 custom/default
delegate만 있는 context에서는 bean을 만들지 않거나
`SqsExtendedConfigurationException`으로 fail closed한다. inherited default
`SqsOperations.send(request)` fake와 full-capability fake를 각각 negative/
positive test로 검증해 FIFO group/deduplication id와 message attributes가
조용히 버려지지 않게 한다.
```

`acknowledge`가 `receiptHandle` 문자열만 받지 않고 수신 결과를 받는 이유는
SQS receipt handle과 S3 pointer의 관계를 호출자에게 다시 추적시키지 않기
위해서다. `SqsExtendedReceivedMessage`는 SQS 원본 wrapper, 복원한 body,
content type, pointer를 함께 보관한다. inline 메시지의 pointer는 `null`이며
ack는 기존 SQS delete만 수행한다.

```kotlin
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue

class SqsExtendedMessageAttribute private constructor(
    val dataType: String,
    val stringValue: String?,
    private val binaryBytes: ByteArray?,
) {
    val binaryValue: ByteArray?
        get() = binaryBytes?.clone()

    override fun toString(): String =
        "SqsExtendedMessageAttribute(dataTypePresent=${dataType.isNotBlank()}, stringValuePresent=${stringValue != null}, binaryValuePresent=${binaryBytes != null})"

    internal companion object {
        fun create(value: MessageAttributeValue): SqsExtendedMessageAttribute {
            val rawDataType = value.dataType()
            val rawStringValue = value.stringValue()
            require(rawDataType.isNotBlank() && rawDataType.length <= 256)
            require(rawDataType.all { it in '\u0020'..'\u007e' && it != '\r' && it != '\n' })
            require(rawStringValue == null || rawStringValue.none { it == '\r' || it == '\n' })
            return SqsExtendedMessageAttribute(
                dataType = rawDataType,
                stringValue = rawStringValue,
                binaryBytes = value.binaryValue()?.asByteArray()?.clone(),
            )
        }
    }
}

data class SqsExtendedSendRequest(
    val request: SqsSendRequest,
    val contentType: String? = null,
    val idempotencyKey: String? = null,
) {
    override fun toString(): String =
        "SqsExtendedSendRequest(contentTypePresent=${contentType != null}, idempotencyKeyPresent=${idempotencyKey != null})"
}

class SqsExtendedReceivedMessage private constructor(
    internal val rawMessage: SqsReceivedMessage,
    val body: String,
    val messageId: String,
    val messageAttributes: Map<String, SqsExtendedMessageAttribute>,
    val systemAttributes: Map<String, String>,
    val contentType: String?,
    val pointer: SqsExtendedClientPointer?,
    val duplicateAfterCleanup: Boolean,
    private val acknowledgement: SqsExtendedAcknowledgementToken,
) {
    internal fun acknowledgementToken(): SqsExtendedAcknowledgementToken = acknowledgement

    override fun toString(): String =
        "SqsExtendedReceivedMessage(contentTypePresent=${contentType != null}, pointerPresent=${pointer != null}, duplicateAfterCleanup=$duplicateAfterCleanup)"

    companion object {
        internal fun create(
            message: SqsReceivedMessage,
            body: String,
            contentType: String?,
            pointer: SqsExtendedClientPointer?,
            duplicateAfterCleanup: Boolean,
            acknowledgement: SqsExtendedAcknowledgementToken,
        ): SqsExtendedReceivedMessage =
            SqsExtendedReceivedMessage(
                rawMessage = message,
                body = body,
                messageId = message.messageId,
                messageAttributes = message.messageAttributes.mapValues { (_, value) ->
                    SqsExtendedMessageAttribute.create(value)
                },
                systemAttributes = message.attributes.mapKeys { (name, _) -> name.name },
                contentType = contentType,
                pointer = pointer,
                duplicateAfterCleanup = duplicateAfterCleanup,
                acknowledgement = acknowledgement,
            )
    }
}

data class SqsExtendedSendResponse(
    val messageId: String?,
    val sequenceNumber: String?,
    val md5OfMessageBody: String?,
    val md5OfMessageAttributes: String?,
) {
    override fun toString(): String =
        "SqsExtendedSendResponse(messageIdPresent=${messageId != null}, sequenceNumberPresent=${sequenceNumber != null}, bodyDigestPresent=${md5OfMessageBody != null}, attributesDigestPresent=${md5OfMessageAttributes != null})"
}

class SqsExtendedSendResult private constructor(
    val response: SqsExtendedSendResponse,
    val offloaded: Boolean,
    val pointer: SqsExtendedClientPointer?,
) {
    override fun toString(): String =
        "SqsExtendedSendResult(offloaded=$offloaded, pointerPresent=${pointer != null})"

    internal companion object {
        fun create(
            response: SqsExtendedSendResponse,
            offloaded: Boolean,
            pointer: SqsExtendedClientPointer?,
        ): SqsExtendedSendResult {
            require(offloaded == (pointer != null))
            return SqsExtendedSendResult(response, offloaded, pointer)
        }
    }
}

class SqsExtendedAcknowledgementResult private constructor(
    val sqsDeleted: Boolean,
    val payloadDeleted: Boolean,
    val cleanupRequired: Boolean,
    val pointer: SqsExtendedClientPointer?,
    val failureKind: SqsExtendedFailureKind? = null,
    val retryable: Boolean = false,
    val cleanupHandle: SqsExtendedCleanupHandle? = null,
) {
    override fun toString(): String =
        "SqsExtendedAcknowledgementResult(sqsDeleted=$sqsDeleted, payloadDeleted=$payloadDeleted, cleanupRequired=$cleanupRequired, failureKind=$failureKind, retryable=$retryable, cleanupHandlePresent=${cleanupHandle != null})"

    internal companion object {
        fun create(
            sqsDeleted: Boolean,
            payloadDeleted: Boolean,
            cleanupRequired: Boolean,
            pointer: SqsExtendedClientPointer?,
            failureKind: SqsExtendedFailureKind? = null,
            retryable: Boolean = false,
            cleanupHandle: SqsExtendedCleanupHandle? = null,
        ): SqsExtendedAcknowledgementResult {
            require(sqsDeleted)
            require(cleanupRequired == (cleanupHandle != null))
            require(!payloadDeleted || pointer != null)
            require(!cleanupRequired || (!payloadDeleted && pointer != null))
            require(!cleanupRequired || (failureKind == SqsExtendedFailureKind.S3_DELETE && retryable))
            require(cleanupRequired || (failureKind == null && !retryable && cleanupHandle == null))
            require(pointer != null || (!payloadDeleted && !cleanupRequired))
            return SqsExtendedAcknowledgementResult(
                sqsDeleted, payloadDeleted, cleanupRequired, pointer,
                failureKind, retryable, cleanupHandle,
            )
        }
    }
}

class SqsExtendedCleanupResult private constructor(
    val deleted: Boolean,
    val cleanupRequired: Boolean,
    val failureKind: SqsExtendedFailureKind? = null,
    val retryable: Boolean = false,
    val diagnosticCode: String? = null,
    val cleanupHandle: SqsExtendedCleanupHandle? = null,
) {

    override fun toString(): String =
        "SqsExtendedCleanupResult(deleted=$deleted, cleanupRequired=$cleanupRequired, failureKind=$failureKind, retryable=$retryable, cleanupHandlePresent=${cleanupHandle != null})"

    internal companion object {
        fun create(
            deleted: Boolean,
            cleanupRequired: Boolean,
            failureKind: SqsExtendedFailureKind? = null,
            retryable: Boolean = false,
            diagnostic: SqsExtendedDiagnosticCode? = null,
            cleanupHandle: SqsExtendedCleanupHandle? = null,
        ): SqsExtendedCleanupResult {
            require(cleanupRequired == (cleanupHandle != null))
            require(!deleted || (!cleanupRequired && failureKind == null && !retryable && cleanupHandle == null))
            require(!cleanupRequired || (!deleted && failureKind == SqsExtendedFailureKind.S3_DELETE && retryable))
            require(deleted || cleanupRequired)
            require(!deleted || diagnostic == null)
            require(!cleanupRequired || diagnostic == SqsExtendedDiagnosticCode.S3_DELETE)
            return SqsExtendedCleanupResult(
                deleted, cleanupRequired, failureKind, retryable, diagnostic?.value, cleanupHandle,
            )
        }
    }
}
```

pointer는 value equality와 strict parser를 위해 명시적으로 정의하며, public
constructor는 닫고 internal parser/factory만 생성 경로로 사용한다. 따라서
`copy()`로 signature·bucket·key를 위조하는 경로가 없고, public property는
검증된 값만 노출한다.

```kotlin
class SqsExtendedClientPointer private constructor(
    val bucket: String,
    val key: String,
    val contentType: String?,
    val encrypted: Boolean,
    val signatureBase64Url: String,
) {
    override fun toString(): String =
        "SqsExtendedClientPointer(encrypted=$encrypted, present=true)"

    override fun equals(other: Any?): Boolean =
        other is SqsExtendedClientPointer &&
            bucket == other.bucket && key == other.key && contentType == other.contentType &&
            encrypted == other.encrypted && signatureBase64Url == other.signatureBase64Url

    override fun hashCode(): Int =
        listOf(bucket, key, contentType, encrypted, signatureBase64Url).hashCode()

    internal companion object {
        fun create(
            bucket: String,
            key: String,
            contentType: String?,
            encrypted: Boolean,
            signatureBase64Url: String,
        ): SqsExtendedClientPointer {
            require(bucket.isNotBlank() && bucket.none { it == '\u0000' || it == '\r' || it == '\n' })
            require(key.isNotBlank() && key.none { it == '\u0000' || it == '\r' || it == '\n' })
            require(contentType == null || contentType.none { it == '\u0000' || it == '\r' || it == '\n' })
            require(signatureBase64Url.matches(Regex("[A-Za-z0-9_-]+")))
            return SqsExtendedClientPointer(bucket, key, contentType, encrypted, signatureBase64Url)
        }
    }
}
```

이 모듈은 Jackson 2 annotation이나 새 serialization dependency를 추가하지
않는다. 이미 `compileOnly`인 Jackson 3가 classpath에 있을 때만
`SqsExtendedClientJacksonModule`을 optional auto-configuration으로 등록하고,
그 module의 explicit serializer/DTO boundary가 bucket/key/signature,
receipt handle, raw AWS request/response/message, acknowledgement token,
encryption context를 출력하지 않는 safe field만 기록한다. Java serialization은
모든 extended public model에서 지원하지 않으며, ObjectOutputStream 시도는
계약상 실패한다. supported Jackson 3 module과
`ObjectOutputStream` negative test로 이 경계를 고정한다.

지원되는 JSON 경계는 다음 safe DTO만 허용한다: request는
`contentTypePresent`와 `idempotencyKeyPresent`, received message는 `body`,
`messageId`, `contentType`, `duplicateAfterCleanup`, send result는
`SqsExtendedSendResponse`의 message id·sequence·MD5 safe fields와 boolean,
ack/cleanup result는 boolean·bounded failure fields, pointer는 `contentType`과
`encrypted`만 기록한다. raw `SendMessageResponse`와 raw AWS request/response는
public result에 노출하지 않는다. `SqsExtendedClientJacksonModule`
외부에서 임의 `ObjectMapper`를 사용하는 경우에는 raw AWS model/secret을
직렬화하지 않는 것이 이 library의 보장이 아니며, 문서에서 supported module을
명시한다. module은 auto-configuration imports에서 Jackson 3 class가 있을 때만
활성화되고, 없는 classpath에서는 API/compile path를 바꾸지 않는다.

module auto-configuration은 `@AutoConfiguration(afterName = [
"org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
"io.github.bluetape4k.aws.spring.sqs.SqsExtendedClientAutoConfiguration",
])`를 사용하고, `@ConditionalOnClass(name =
["tools.jackson.databind.ObjectMapper"])`를 사용하고, `ObjectMapper` bean이
있을 때만 `SqsExtendedClientJacksonModule`을 등록한다. Jackson 3가 없는
consumer classpath에서 extended core API가 로드되는 데 영향을 주지 않도록
serializer implementation은 optional source set 또는 동일 모듈의
`compileOnly` 경계에 둔다.

정확한 auto-configuration 이름은 `SqsExtendedClientJacksonAutoConfiguration`으로
고정한다. 이 클래스는 위의 `@AutoConfiguration(afterName = [...])` 순서를
<!-- effective annotation is the complete afterName list above; no shorthand is normative. -->
`@AutoConfiguration(afterName = ["io.bluetape4k.aws.spring.sqs.SqsExtendedClientAutoConfiguration"])`,
`@ConditionalOnClass(name = ["tools.jackson.databind.ObjectMapper"])`,
`@ConditionalOnBean(ObjectMapper::class)`,
`@ConditionalOnMissingBean(SqsExtendedClientJacksonModule::class)`을 사용하고,
<!-- end legacy shorthand -->
지원되는 `ObjectMapper`에 module 하나만 등록한다. 기존
`SqsJacksonMessageConverterAutoConfiguration`과는 서로 다른 bean을 제공하며,
imports 파일의 기존 converter 행을 삭제하거나 재정렬하지 않는다. imports에는
`SqsExtendedClientJacksonAutoConfiguration`을
`SqsExtendedClientAutoConfiguration` 다음에 추가한다. Jackson 3가 없는
classpath와 사용자 module bean이 이미 있는 context에서 각각 class-loading
없음·back-off를 `ApplicationContextRunner`로 검증하고, positive context에서
Jackson bean 생성 후 module 등록 순서와 기존 SQS converter 공존을 검증한다.

pointer의 `bucket`·`key`·`signatureBase64Url`는 API에서 읽을 수 있지만
기본 로그/예외/serializer에는 표시하지 않는다. parser/factory로 생성된 값도
S3 호출 직전에 현재 queue policy와 HMAC을 다시 검증한다.

`acknowledge(message)`는 수신 시점의 acknowledgement token과 현재 인자의
관계를 다시 검증한다. queue URL, receipt handle, pointer digest, policy
fingerprint, marker key, encryption context가 token의 수신 시점 기준 데이터와
모두 일치해야
하며 하나라도 다르면 `SqsExtendedAcknowledgementException`으로 fail closed한다.
따라서 public message의 forged copy나 다른 queue에서 받은 foreign message를
acknowledge할 수 없고, 성공적인 receive 결과를 그대로 전달한 경우에만 SQS
delete와 cleanup이 진행된다. 이 경계는 forged-copy·foreign-message 회귀
테스트로 고정한다.

`SqsExtendedSendRequest`, `SqsExtendedReceivedMessage`,
`SqsExtendedSendResult`, `SqsExtendedAcknowledgementResult`,
`SqsExtendedCleanupResult`도 모두 명시적 safe `toString()`을 제공한다.
특히 nested `SqsSendRequest`, internal `SqsReceivedMessage`, AWS SDK response의
generated `toString()`을 호출하지 않으며, raw payload·receipt handle·message
attributes를 structured log serializer에 전달하지 않는다. public
`SqsExtendedReceivedMessage`는 복원한 `body`, `messageId`, 검증된 safe
attribute DTO와 system-attribute name/value만 제공하고 raw message와 raw
`MessageAttributeValue`는 internal ack 경계에만 둔다.

`SqsExtendedCleanupHandle`은 public 생성자를 제공하지 않는 opaque capability다.
`acknowledge`가 SQS delete 성공을 확인한 뒤에만 발급하며, caller는 S3 delete
실패 시 이 handle만 `cleanup`에 전달해 재시도할 수 있다. 임의의
`SqsExtendedClientPointer`로 cleanup할 수 없으므로 ACK 전 조기 삭제와 foreign
bucket/key 삭제를 API 수준에서 차단한다. 불확실한 SQS send·취소 경로에는
cleanup handle을 발급하지 않고 orphan lifecycle에만 위임한다.

public value model은 Java serialization을 계약으로 제공하지 않으며 collection은
불변 복사본으로 만든다. payload, bucket, key, content type, encryption
context, receipt handle, raw AWS `Message`와 raw `MessageAttributeValue`는 명시적 redacted `toString()`에서
제외한다. `data class` generated output에 의존하지 않고 모든 public model에
safe display를 구현한다. `SqsExtendedReceivedMessage`
의 internal acknowledgement token은 외부에 직렬화하지 않고 생성 시점의 queue policy,
policy fingerprint, encryption context와 receipt handle을 고정해 receive와 ack
사이의 설정 변경이 cleanup 의미를 바꾸지 않게 한다.

`SqsExtendedAcknowledgementResult`에서 `cleanupRequired=true`이면
`cleanupHandle`이 반드시 non-null이고, `failureKind`와 `retryable`이 함께
제공된다. `cleanup` 실패는 같은 handle로 재시도하며, handle의 safe display는
pointer 원문을 포함하지 않는다. 성공한 send/inline ack의 result에는 실패
상태를 넣지 않는다. 실패는 아래 typed exception 계약에 따라 throw한다.
`SqsExtendedAcknowledgementResult`와 `SqsExtendedCleanupResult`의 public
constructor는 private으로 두고 named factory만 제공한다. factory는
`cleanupRequired == (cleanupHandle != null)`, `deleted == true`이면
`failureKind == null && retryable == false`, `deleted == false`이면 typed
failure 정보가 있다는 불변식을 검증한다. 따라서 caller가
`cleanupRequired=true, cleanupHandle=null` 또는 성공 result에 failure를 넣은
값을 만들 수 없다.

구체적인 opaque 타입은 다음 경계를 갖는다.

```kotlin
class SqsExtendedCleanupHandle private constructor(
    internal val pointer: SqsExtendedClientPointer,
    internal val queueUrl: String,
    internal val policyFingerprint: String,
    internal val markerKey: String?,
) {
    override fun toString(): String = "SqsExtendedCleanupHandle(available=true)"
}
```

`SqsExtendedCleanupHandle`의 constructor/factory는 adapter 내부에만 있고,
지원되는 `SqsExtendedClientJacksonModule`의 DTO boundary는
`queueUrl`·`policyFingerprint`·pointer·markerKey를 외부 serializer에 노출하지
않는다. 동일한 module 경계를 `SqsExtendedSendRequest.request`,
`SqsExtendedSendResult.response`의 safe DTO, acknowledgement/cleanup result의 raw
pointer·handle에도 적용한다.
raw AWS response는 public result에 없다.
handle은 ACK 성공 이후에만 생성되므로 `cleanup`은 ACK 증명을 다시 확인하고
현재 정책과 생성 시점 fingerprint가 다르면 fail closed한다.

`SqsExtendedReceivedMessage`는 의도적으로 `data class`/`copy()`/구조적
`equals`를 제공하지 않는 identity-bound class다. caller가 body나 metadata를
바꾸어 ACK token과 결합한 새 값을 만들 수 없으며, 수신 결과를 보관·전달할
때도 동일 instance만 유효하다.

### 2. 설정과 opt-in

별도 `@ConfigurationProperties(prefix = "bluetape4k.aws.sqs.extended")`를
추가한다. 기본값은 disabled다.

```kotlin
data class SqsExtendedClientProperties(
    val enabled: Boolean = false,
    val producerEnabled: Boolean = false,
    val consumerEnabled: Boolean = false,
    val shutdownDrainTimeoutSeconds: Int = 20,
    val defaultPolicy: Policy? = null,
    val defaultQueueUrls: Set<String> = emptySet(),
    val queues: Map<String, QueuePolicy> = emptyMap(),
    val security: Security = Security(),
) {
    data class Policy(
        val bucket: String,
        val keyPrefix: String = "bluetape4k/sqs",
        val offloadThresholdBytes: Int = 262_144,
        val maxInlineBytes: Int = 1_048_576,
        val maxOffloadPayloadBytes: Int = 67_108_864,
        val deleteOnAck: Boolean = false,
        val orphanRetentionHours: Int = 168,
        val configuredSqsRetentionSeconds: Int? = null,
        val configuredMaxVisibilityRetryWindowSeconds: Int? = null,
        val rollbackDeadlineSeconds: Int? = null,
        val minimumVisibilityTimeoutSeconds: Int = 30,
        val pointerSigningKeyRef: String = "default",
        val encryption: Encryption = Encryption(),
    )

    data class QueuePolicy(
        val queueUrl: String,
        val policy: Policy,
    )

    data class Encryption(
        val enabled: Boolean = false,
        val encryptionContext: Map<String, String> = emptyMap(),
        val keyFingerprint: String? = null,
    )

    class Security(
        private val pointerSigningKeysBase64Url: Map<String, String> = emptyMap(),
    ) {
        override fun toString(): String =
            "SqsExtendedClientSecurity(keyCount=${pointerSigningKeysBase64Url.size})"

        internal fun resolveSigningKey(ref: String): ByteArray =
            requireNotNull(pointerSigningKeysBase64Url[ref]).decodeCanonicalBase64Url()
    }
}

`pointerSigningKeysBase64Url`은 `Policy` data class에서 분리해 별도 security
configuration holder로만 받고, `Policy`에는 opaque `pointerSigningKeyRef`만 둔다.
security holder는 Java serialization을 구현하지 않고 secret-safe `toString()`을
제공하며, `Policy`, `QueuePolicy`, `Encryption`, `SqsExtendedClientProperties`도
raw bucket/keyPrefix/encryption context를 `toString()`에 넣지 않는 명시적
redacted 구현을 제공한다. Spring Boot `/configprops`와 actuator 출력에서는
secret 값이 항상 `******`로 sanitize되어야 한다. adapter 생성 시 ref가 없는
키를 찾으면 configuration exception으로 fail closed한다. 따라서 public policy
model이나 생성된 configuration output이 HMAC secret을 직접 보관·출력하지 않는다.
```

구현은 다음 네 override를 생략하지 않는다(생성된 `data class.toString()`을
허용하지 않는다).

```kotlin
override fun toString(): String = "SqsExtendedClientProperties(enabled=$enabled, producerEnabled=$producerEnabled, consumerEnabled=$consumerEnabled, queueCount=${queues.size}, defaultQueueCount=${defaultQueueUrls.size})"
override fun toString(): String = "SqsExtendedPolicy(offloadThresholdBytes=$offloadThresholdBytes, maxInlineBytes=$maxInlineBytes, maxOffloadPayloadBytes=$maxOffloadPayloadBytes, deleteOnAck=$deleteOnAck, encryptionEnabled=${encryption.enabled})"
override fun toString(): String = "SqsExtendedQueuePolicy(queueConfigured=true)"
override fun toString(): String = "SqsExtendedEncryption(enabled=$enabled, contextEntryCount=${encryptionContext.size}, keyFingerprintPresent=${keyFingerprint != null})"
```

`SqsExtendedClientPropertiesRedactionTest`는 nested property, `/configprops`,
structured log에 sentinel bucket·prefix·queue URL·context·fingerprint가
나오지 않는지 `shouldNotContain`으로 검증한다.

실제 Kotlin 모델은 `Policy`와 `QueuePolicy`의 validation을 다음처럼 조기
거부한다.

- `bucket`은 blank가 아니다.
- `keyPrefix`는 blank가 아니며 `/`로 정규화한다.
- `offloadThresholdBytes`는 `1..maxInlineBytes` 범위다.
- `maxInlineBytes`는 `1..1_048_576` 범위다.
- `maxOffloadPayloadBytes`는 `maxInlineBytes..67_108_864` 범위다.
- `orphanRetentionHours`는 `1..336` 범위이며 배포된 SQS retention보다 길게
  lifecycle rule을 구성해야 한다.
- `configuredSqsRetentionSeconds`와 `configuredMaxVisibilityRetryWindowSeconds`는
  offload 정책에서 필수이며 양수여야 한다. 두 값과 최대 visibility/retry
  window가 `orphanRetentionHours * 3_600`보다 작지 않으면 adapter 생성과
  producer enable을 거부한다. `configuredMaxVisibilityRetryWindowSeconds`는
  `1..604_740` 범위다. 이는 startup에서 확인 가능한 deployment
  기준 데이터이며 AWS queue attribute를 추측하거나 자동 보정하지 않는다.
- `rollbackDeadlineSeconds`는 offload 정책에서 선택할 수 있는 전체 rollback
  deadline이며, 지정하면 `configuredMaxVisibilityRetryWindowSeconds` 이상인
  `1..604_800` 범위여야 한다. 미지정이면
  `min(configuredMaxVisibilityRetryWindowSeconds + 60초, 604800초)`로 derived
  deadline을 고정한다. effective rollback deadline은
  `orphanRetentionHours * 3_600`보다 작아야 하며, 이를 넘는 explicit/derived
  값은 payload가 lifecycle로 삭제되기 전에 rollback을 끝낼 수 없으므로
  `SQS_EXT_CONFIG_001`으로 거부한다. pointer 재등장으로 관찰 window를 다시
  시작하더라도 이 전체 deadline은 절대 연장하지 않는다.
- `minimumVisibilityTimeoutSeconds`는 `1..43_200` 범위다.
- `shutdownDrainTimeoutSeconds`는 `1..25` 범위이며 기본값은 20초다. Spring의
  기본 lifecycle phase timeout(30초)보다 짧은 상한을 사용해 동기 bridge가
  phase timeout을 선점하지 않게 한다. 배포자가 Spring phase timeout을 더 짧게
  설정하면 startup validation에서 이 값을 거부한다. context stop은 이 값을
  `drain()` deadline으로 사용하며, timeout을 강제 취소나 조용한 close 성공으로
  바꾸지 않는다.
- auto-configuration은 `spring.lifecycle.timeout-per-shutdown-phase`를
  `Duration`으로 해석하고, 미설정 시 Spring 기본값 30초를 사용한다.
  `phaseTimeout >= shutdownDrainTimeoutSeconds + 5초`가 아니면 extended
  auto-configuration 전체(operations·producer·consumer·lifecycle)를 만들지
  않고 `SQS_EXT_CONFIG_001`로 fail closed한다. 이 5초 여유는 callback
  선형화·event 기록·Spring phase 반환을 위한 고정 budget이다.
- `pointerSigningKeyRef`는 blank가 아니며
  `security.pointerSigningKeysBase64Url[ref]`로 해석되는 canonical base64url
  32바이트 이상 secret을 가리켜야 한다.
- `security.pointerSigningKeysBase64Url`의 각 값은 binding·검증 시에만 사용하고 public
  policy/result/log/serializer에는 절대 복사하지 않는다.
- `queues`의 map key는 logical queue name이고 `QueuePolicy.queueUrl`은 실제
  canonical queue URL이다. URL은 blank가 아니고 서로 중복되지 않는다.
- `defaultPolicy`를 사용할 때 `defaultQueueUrls`는 비어 있지 않은 exact URL
  allowlist여야 하며, queue-specific 항목이 없는 allowlist 밖 queue에는
  절대 적용하지 않는다.
- encryption context의 key는 blank가 아니다.
- encryption context는 UTF-8 key/value를 key와 value 순으로 정렬한
  length-prefixed canonical form으로 만들고, NUL/CR/LF와 길이 초과를 거부한다.
- `encryption.enabled=true`이면 `keyFingerprint`가 non-blank여야 하며,
  producer와 consumer가 같은 CMK identity를 사용하지 않으면 fail closed한다.
- `encryption.enabled=true`인데 `S3ClientSideEncryptionOperations` bean이
  없으면 adapter는 생성 시점 또는 해당 queue의 첫 send 전에 명확한
  configuration exception을 반환한다.

암호화 identity는 임의 property 문자열로 신뢰하지 않는다.
`S3ClientSideEncryptionIdentity`라는 additive marker를 정의하고
`S3ClientSideEncryptionTemplate`이 이를 구현한다. marker는 secret이나 data
key를 노출하지 않고 `canonicalKeyIdentity`와
`keyFingerprint = SHA-256("bluetape4k.s3.cse.identity/v1\u0000" +
canonicalKeyIdentity + "\u0000" + canonicalEncryptionContext)`의
padding 없는 base64url 값을 제공한다. `canonicalKeyIdentity`는 configured KMS key ARN만
허용하고 alias·wildcard·blank는 거부한다. extended adapter는 encrypted
policy가 켜진 경우 encryption operations가 이 marker를 함께 구현하는지,
`properties.encryption.keyFingerprint == S3ClientSideEncryptionIdentity.keyFingerprint`
인지와 pointer fingerprint·현재 marker fingerprint가 같은지, S3 encrypted object
metadata의 `bt4k-cek-key-id`를 동일 canonical identity로 재검증한다. marker가
없거나 fingerprint/metadata가 다르면 encrypt/decrypt를 수행하지 않고
`SQS_EXT_CONFIG_001`로 fail closed한다. 이 identity derivation과 runtime
binding을 fake delegate 및 encrypted emulator contract test로 고정한다.

`policyFingerprint`도 임의 문자열 property로 받지 않고 adapter가 canonical
정책에서 계산한다. 입력 tuple은
`domain="bluetape4k.sqs.extended.policy/v1"`, canonical queue URL, normalized
bucket/keyPrefix, offload threshold/max bounds, `deleteOnAck`, orphan age,
visibility bounds, pointer signing key ref, encrypted flag, canonical
encryption context와 encryption key fingerprint를 이 순서로 length-prefix
인코딩한다. 이 tuple은 정확히 16개 field이며 먼저 field count
`uint32(16)`을 big-endian으로 기록한다. 각 field는
`typeTag:uint8 + byteLength:uint32(big-endian) + valueBytes`로 구성하고,
UTF-8/string은 `S`, Int는 `I`와 signed `int32`, Boolean은 `B`와 `0` 또는
`1`, nullable 값은 `N`과 presence byte 뒤에 해당 typed value를 기록한다.
`SHA-256` 결과는 unpadded base64url 문자열이며 policy map
순서와 입력 map iteration 순서에 의존하지 않는다. queue URL·policy field를
바꾸면 fingerprint가 반드시 달라지고, 동일 canonical tuple은 동일 값을
내야 한다. `SqsExtendedPolicyFingerprintTest`가 field mutation·map reorder·
foreign queue와 exact byte fixture를 검증한다.

ack marker metadata를 기록하려면 additive
`S3ObjectMetadataOperations : S3Operations` capability가 필요하다. 이 marker는
다음의 bounded metadata/conditional-write API를 제공한다.

```kotlin
data class S3HeadMetadata(
    val sizeBytes: Long,
    val etag: String?,
    val contentType: String?,
    val userMetadata: Map<String, String>,
)

sealed interface S3PutIfAbsentResult {
    data object Created : S3PutIfAbsentResult
    data class AlreadyExists(val metadata: S3HeadMetadata) : S3PutIfAbsentResult
}

interface S3ObjectMetadataOperations : S3Operations {
    suspend fun headObjectWithMetadata(bucket: String, key: String): S3HeadMetadata

    suspend fun putObjectIfAbsentWithMetadata(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutIfAbsentResult
}
```

`putObjectIfAbsentWithMetadata`는 S3 `If-None-Match: *` 조건부 생성만 사용하며
overwrite를 허용하지 않는다. 200/created는 `Created`, 412 또는 409 race는
`AlreadyExists`로 표현한 뒤 caller가 `headObjectWithMetadata`를 호출해 marker
version·pointer digest·policy fingerprint·queue URL digest 네 값을 비교한다.
기본 `S3CoroutinesTemplate`과 Micrometer/observability wrapper는 이 capability와
두 method의 metadata를 그대로 보존한다. `deleteOnAck=true`인 어떤 resolved
policy라도 이 capability가 없으면 auto-configuration/client creation 또는
해당 queue의 첫 acknowledgement에서 `SqsExtendedConfigurationException`
(`SQS_EXT_CONFIG_001`)으로 fail closed한다. `deleteOnAck=false` 정책은
기존 `S3Operations`만으로 동작하며 marker metadata I/O를 시도하지 않는다.
capability-less delegate와 marker-capable delegate의 조건·주입 타입을
negative/positive context test로 고정하고, conditional-create 412/409 race와
foreign metadata mismatch에서 payload delete가 0회임을 검증한다.

평문 offloaded payload 수신의 bounded capability는 다음 additive 계약으로
고정한다. `S3Operations.downloadBytes`의 unbounded `ByteArray` API는 extended
pointer path에서 호출하지 않는다.

```kotlin
interface S3BoundedObjectReadOperations : S3Operations {
    suspend fun downloadBytesBounded(
        bucket: String,
        key: String,
        maxBytes: Int,
    ): ByteArray
}
```

`maxBytes`는 `1..67_108_864` 범위만 허용한다. 구현은
`maxBytes.toLong() + 1L`로 probe limit을 계산한 뒤 bounded stream scratch
용량으로 변환하므로 `Int.MAX_VALUE` overflow가 없다. 0·음수·상한 초과·
`Int.MAX_VALUE`는 S3 호출 전에 configuration/read exception으로 거부한다.

기본 `S3CoroutinesTemplate`은 `S3BoundedObjectReadOperations`를 함께 구현한다.
`downloadBytesBounded`는 response stream을 `maxBytes + 1`까지 고정 scratch
buffer로 읽고 초과를 발견하는 즉시 중단·`S3_READ`로 실패하며, 초과 payload를
완성한 `ByteArray`나 decode 결과를 만들지 않는다. Micrometer/observability
wrapper는 delegate가 이 capability를 구현할 때만
`MicrometerBoundedS3Operations`로 capability와 bounded method를 그대로
전달하고, markerless delegate에는 bounded wrapper를 만들지 않는다. 따라서
기본 template과 metrics-enabled context는 positive path, custom bounded
delegate는 preserved path, custom markerless delegate는 fail-closed path로
검증한다.

암호화 수신의 bounded capability는 다음 additive 계약으로 고정한다.

```kotlin
interface S3BoundedEncryptedReadOperations : S3ClientSideEncryptionOperations {
    suspend fun downloadEncryptedBytesBounded(
        bucket: String,
        key: String,
        encryptionContext: Map<String, String>,
        maxCiphertextBytes: Int,
    ): ByteArray
}
```

`maxCiphertextBytes`는 AES-GCM tag 16바이트를 포함해
`1..67_108_880`(`67_108_864 + 16`) 범위만 허용하며 `Long` 기반 `+1` guard를
적용한다. encryption policy는 fixed envelope overhead를 이 상한 안에서
검증하며, 허용된 configured bound를 넘는 ciphertext는 decrypt 전에 중단한다.

기본 `S3ClientSideEncryptionTemplate`은 이 계약을 구현하고 custom delegate가
구현하지 않으면 encrypted extended policy를 생성하지 않는다. metrics-enabled
context의 wrapper도 `S3BoundedEncryptedReadOperations`를 구현하는 delegate일
때만 `MicrometerBoundedEncryptedS3Operations`로 이 method와
`S3ClientSideEncryptionIdentity`를 보존한다.

`enabled`는 auto-configuration bean 자체의 전역 gate이며,
`producerEnabled`와 `consumerEnabled`는 서로 독립적인 runtime gate다.
producer를 끄더라도 consumer는 pointer drain이 끝날 때까지 켜 둔다.
consumer를 끄는 것은 pointer queue를 legacy consumer에 넘기는 동작이 아니며,
pointer를 받을 수 있는 extended consumer가 없는 동안 producer를 켤 수 없도록
configuration validation이 fail closed한다.

`defaultPolicy`가 있으면 `defaultQueueUrls`에 exact match하는 queue만
적용하고, 없으면 `queues`에 등록된 URL만 offload한다. 정책이 없는 queue는
**기존 SQS inline 전송과 수신을 그대로 위임**하며 body를 pointer로 해석하지
않는다. 따라서 전역 property를 켜도 allowlist 밖 queue에 숨은 offload가
발생하지 않는다. queue-specific 항목은 명시적 opt-in이므로 default allowlist와
독립적으로 적용한다. 정책 resolution은 다음 표 하나로 고정한다.

| queue URL 상태 | 적용 정책 |
|---|---|
| `queues`에 exact canonical URL 항목 존재 | 해당 queue-specific policy (default allowlist 무관) |
| queue-specific 항목 없음 + `defaultPolicy` 존재 + `defaultQueueUrls` exact match | default policy |
| 그 밖의 모든 경우 | policy 없음, opaque inline 위임 |

동일 URL 중복, non-canonical URL, default allowlist 밖의 queue-specific 항목은
각각 validation에서 명시적으로 처리하며, resolution test는 overlap/outside/no-
default 조합을 모두 검증한다.

`maxMessages`는 extended receive에서 `1`만 허용한다. S3 GET이 순차 복원되는
동안 SQS visibility lease가 만료되어 partial batch와 중복 비용이 생기는 것을
피하기 위한 초기 계약이다. `visibilityTimeoutSeconds`는 null을 허용하지 않고
`1..43_200` 범위의 양수로 검증하며, 정책의
`minimumVisibilityTimeoutSeconds`(기본 30초) 이상이어야 한다. 호출자는
S3 read/decrypt/decode의 예상 최악 시간보다 긴 값을 선택해야 하며, adapter는
heartbeat 없이 lease를 갱신하지 않는다. 배치 복원·heartbeat·streaming은 별도
후속 범위다.
offload payload는 `maxOffloadPayloadBytes`를 초과하면 S3 호출 전에
configuration/payload-limit exception으로 거부한다. 이 버전은 ByteArray 기반
API이므로 streaming을 약속하지 않는다.

`SqsExtendedClientAutoConfiguration`은 다음 import와 조건을 사용한다.

```kotlin
@AutoConfiguration(after = [
SqsAutoConfiguration::class, S3AutoConfiguration::class,
S3MicrometerAutoConfiguration::class, SqsMicrometerAutoConfiguration::class,
])
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.services.sqs.SqsAsyncClient",
        "software.amazon.awssdk.services.s3.S3AsyncClient",
        "software.amazon.awssdk.services.s3.S3Client",
        "software.amazon.awssdk.services.s3.presigner.S3Presigner",
    ]
)
@ConditionalOnAwsEnabled
@ConditionalOnProperty(prefix = "bluetape4k.aws.sqs", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "bluetape4k.aws.s3", name = ["enabled"], havingValue = "true", matchIfMissing = true)
```

extended bean 자체의 조건도 prose에만 두지 않고 다음 method-level annotation으로
고정한다. `SqsExtendedResolvedCapabilityCondition`은 resolved policy마다
metadata·bounded-object-read·bounded-encrypted-read·identity capability를
검사하고, `SqsExtendedLifecycleBudgetCondition`은 phase margin을 검사한다.

```kotlin
@Bean
@ConditionalOnProperty(prefix = "bluetape4k.aws.sqs.extended", name = ["enabled"], havingValue = "true")
@ConditionalOnBean(SqsFullRequestOperations::class, S3Operations::class)
@ConditionalOnMissingBean(SqsExtendedClientOperations::class)
@Conditional(SqsExtendedAnyRuntimeGateCondition::class)
@Conditional(SqsExtendedResolvedCapabilityCondition::class)
@Conditional(SqsExtendedLifecycleBudgetCondition::class)
fun sqsExtendedClient(
    sqsOperations: SqsFullRequestOperations,
    s3Operations: S3Operations,
    boundedS3Operations: S3BoundedObjectReadOperations?,
    s3MetadataOperations: S3ObjectMetadataOperations?,
    encryptedS3Operations: S3BoundedEncryptedReadOperations?,
    encryptionIdentity: S3ClientSideEncryptionIdentity?,
    properties: SqsExtendedClientProperties,
): SqsExtendedClient = SqsExtendedClient(
    sqsOperations,
    s3Operations,
    boundedS3Operations,
    s3MetadataOperations,
    encryptedS3Operations,
    encryptionIdentity,
    properties,
)
```

세 optional capability parameter는 실제 구현에서 `ObjectProvider`/nullable
주입으로 받아 capability가 필요 없는 inline-only policy가 정상적으로
생성되게 한다. `SqsExtendedResolvedCapabilityCondition`이 resolved policy와
capability 존재를 먼저 판정하므로, required capability가 없는 offload/encrypted
policy는 method invocation 전에 fail closed한다.

`@EnableConfigurationProperties(SqsExtendedClientProperties::class)`를
사용하고, `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
에 다음 순서로 등록한다: `S3AutoConfiguration`,
`S3MicrometerAutoConfiguration`, `S3TransferAutoConfiguration`,
`SqsAutoConfiguration`,
`SqsMicrometerAutoConfiguration`, `SqsJacksonMessageConverterAutoConfiguration`,
`SqsExtendedClientAutoConfiguration`, `SqsExtendedClientJacksonAutoConfiguration`.
이 목록은 기존 S3/SQS auto-configuration을 삭제하거나 재정렬하지 않고
추가하는 기준 목록이며, Spring의 `@AutoConfiguration(before/after)` sorter가
계산한 effective order도 별도 assertion으로 고정한다. 이 순서를 보장해 base
client·operations와 capability-preserving Micrometer wrapper가 먼저 평가되도록
한다. 다음 조건에서만
`SqsExtendedClientOperations`를 생성한다. public `drain(timeout)`의
`timeout`은 nullable이며 `null`(무인자 호출)이면 현재 properties의
`shutdownDrainTimeoutSeconds`를 동적으로 사용한다. 명시한 timeout은 positive
duration이어야 하고 configured 값을 초과할 수 없다. 따라서 설정값이 10초인
context의 무인자 drain도 10초 budget을 사용하며, 더 긴 timeout으로 Spring
phase margin을 우회하는 API는 제공하지 않는다. `drainUsesConfiguredDefault`
및 `drainRejectsTimeoutBeyondConfiguredBudget` 회귀 테스트로 고정한다.

- global AWS/SQS/S3 auto-configuration이 활성화됨
- `bluetape4k.aws.sqs.extended.enabled=true`
- `SqsFullRequestOperations`와 `S3Operations` bean이 존재함
- resolved policy가 `deleteOnAck=true`이면 `S3ObjectMetadataOperations` bean이
  존재함
- resolved policy가 offloaded plaintext를 수신하면
  `S3BoundedObjectReadOperations` capability가 존재함
- resolved policy가 encryption을 사용하면 `S3BoundedEncryptedReadOperations`
  및 `S3ClientSideEncryptionIdentity` capability가 존재함
- `spring.lifecycle.timeout-per-shutdown-phase`가
  `shutdownDrainTimeoutSeconds + 5초` 이상임. 이 lifecycle phase margin은
  operations/producer/consumer와 lifecycle bridge에 공통으로 적용되는
  global gate다. margin을 만족하지 않으면 extended auto-configuration이
  전체적으로 fail closed되어 extended operations·producer·consumer·bridge를
  어느 것도 생성하지 않고 `SQS_EXT_CONFIG_001` 진단만 남긴다.
- 같은 타입의 사용자 정의 extended bean이 없음
- `producerEnabled=true` 또는 `consumerEnabled=true` 중 하나 이상

`producerEnabled=false`이면 `send`는 offload를 하지 않고 기존 inline 위임만
허용한다. `consumerEnabled=false`이면
`receive`/`receiveFlow`/`acknowledge`는 `SqsExtendedConfigurationException`
(`diagnosticCode=SQS_EXT_CONFIG_001`)으로 거부하며 pointer queue에 대해 호출할
수 없고 receive/ack admission 자체를 열지 않으며,
consumer가 drain되지 않은 상태에서 producer를 활성화하는 설정은 거부한다.

auto-configuration test는 실제 `ApplicationContextRunner`로 imports 경로를
통해 bean이 생성되는지, SQS/S3 SDK classpath 누락, `enabled=false`, phase
margin 부족 시 전체 extended context가 비어 있는지, missing
SqsFullRequestOperations/S3 bean, optional bounded-read/encryption bean 부재, capability
없는 custom/default delegate, full-capability fake, user bean back-off를 각각
검증한다. Micrometer가 활성화된 context에서는 extended bean이
`MicrometerFullRequestSqsOperations`라는 `@Primary` marker wrapper를 통해
전체 `SqsSendRequest`를 전달하는지도 검증한다.

`MicrometerFullRequestSqsOperations`는
`@ConditionalOnBean(name = ["sqsCoroutinesTemplate"])`과 `@Primary`를 사용해
오직 auto-configured `SqsCoroutinesTemplate`을 감쌀 때만 생성한다. 사용자
정의 `SqsFullRequestOperations`가 있으면 SQS template 자체가 back-off되어
이 wrapper도 생성되지 않는다. 일반
`MicrometerSqsOperations`에는 반대로
`@ConditionalOnBean(name = ["sqsCoroutinesTemplate"])`과
`@ConditionalOnMissingBean(SqsFullRequestOperations::class)`를 적용해 full
template context에서 back-off시킨다. 사용자 정의 full 또는 markerless
delegate context에서는 기존 delegate를 그대로 사용하고 두 auto-configured
wrapper 모두 back-off한다. 따라서 각 context에서 `SqsOperations` 주입이
모호하지 않고 `@Primary` bean도 하나뿐이다. markerless/full/custom 세
context와 custom full `@Primary` context에서 실제 bean 수·주입 타입·FIFO/message attributes 보존을
`ApplicationContextRunner`로 고정한다.

`SqsExtendedClientLifecycle`은 concrete 기본 구현의 lifecycle 소유권을 명확히
하기 위해 `@ConditionalOnBean(SqsExtendedClient::class)`,
`@ConditionalOnMissingBean(SqsExtendedClientLifecycle::class)`,
`@Conditional(SqsExtendedLifecycleBudgetCondition::class)` 아래에 등록한다.
따라서 interface만 구현한 사용자 정의 `SqsExtendedClientOperations`는
lifecycle bridge를 유발하지 않으며, 사용자 정의 concrete `SqsExtendedClient`도
phase margin이 유효할 때에만 bridge를 얻는다. margin이 부족하면 custom
interface/concrete bean 자체는 유지하되 bridge와 extended auto-configuration의
기본 bean은 생성하지 않고 `SQS_EXT_CONFIG_001` 진단만 남긴다. 이 경계를 다음
실행 가능한 형태로 고정한다.

```kotlin
@Bean
@ConditionalOnBean(SqsExtendedClient::class)
@ConditionalOnMissingBean(SqsExtendedClientLifecycle::class)
@Conditional(SqsExtendedLifecycleBudgetCondition::class)
@Conditional(SqsExtendedLifecycleOrderCondition::class)
fun sqsExtendedClientLifecycle(
    client: SqsExtendedClient,
    properties: SqsExtendedClientProperties,
): SqsExtendedClientLifecycle = SqsExtendedClientLifecycle(client, properties)
```

`SqsExtendedClientLifecycle`은 기존 AWS client destroy보다 먼저 실행되는 고정
`phase`를 사용한다. bridge의 `stopForSpring`은
`NonCancellable` 경계에서 producer gate와 drain을 순서대로 처리하며,
timeout 시 `SqsExtendedDrainTimeoutException`을 bounded diagnostic event로
기록하고 callback을 호출하지 않는다. lifecycle bridge는 public operation
scope를 취소하거나 AWS client를 직접 닫지 않는다. 이 상태에서 애플리케이션이
`ApplicationContext.close()`를 계속 진행하면 Spring의 자체 lifecycle timeout
뒤에 `destroyMethod = "close"`가 실행될 수 있다. 이는 bridge가 막을 수 없는
명시적 force-close 경로이며, 정상 drain의 “close 전에 callback” 계약과
혼동하지 않는다. 운영자는 timeout event를 확인한 뒤 context close를 중단하고
새 deadline으로 stop을 재시도해야 한다.

adapter는 기존 `SqsAsyncClient`, `S3AsyncClient`, `S3Presigner`를 새로 만들지
않으며 close도 호출하지 않는다. Spring이 관리하는 기존 client bean의
소유권과 `destroyMethod = "close"`를 그대로 유지한다.

adapter는 background scope나 shutdown hook을 생성하지 않는다. in-flight
작업은 caller scope가 소유하지만 adapter는 operation admission counter를
기록한다. `drain(timeout)`은 새 send/receive/ack/cleanup을 거부하고 현재
counter가 0이 될 때까지 기다린다. drain이 시작된 뒤 admission을 시도한
작업은 `SqsExtendedConfigurationException`(`retryable=true`,
`diagnosticCode=SQS_EXT_DRAIN_002`)으로 즉시 거부한다. timeout이면 기존
작업을 강제 취소하지
않는다. `receiveFlow`는 cold flow이지만 `collect`가 시작되는 순간 admission을
선형화한다. collect가 성공적으로 끝나거나 caller가 취소할 때까지 동일한
operation counter를 유지하고, 두 경로 모두 `finally`에서 정확히 한 번
감소시킨다. drain 선형화 이후 시작된 새 collect는 flow body를 실행하지 않고
동일한 `SQS_EXT_DRAIN_002`로 즉시 거부한다. 이미 admission된 collect는
drain의 `activeAtStart`와 `completed`에 포함된다. 이 규칙은
`receiveFlowCollectAdmissionIsCountedAndPostDrainCollectIsRejected` 테스트로
검증한다. `timedOut=true`를 반환하며, caller는 AWS client close를 수행하지 않고
재시도하거나 명시적인 취소 정책을 선택한다. 정상 close 순서는
`producerEnabled=false` → `drain()` → `consumerEnabled=false` → Spring이
관리하는 SQS/S3 client close이며, 이 순서를 지키기 전에는 context를 닫지
않는다. runtime manual과 in-flight shutdown test가 이 bridge를 검증한다.

Spring context가 이 순서를 자동으로 지키도록 auto-configuration은
`SqsExtendedClientLifecycle` `SmartLifecycle` bridge를 함께 등록한다. bridge는
AWS client나 coroutine scope를 만들지 않고, `stop(callback)` 안에서 다음을
원자적으로 수행한다.

1. producer admission을 닫는다.
2. `shutdownDrainTimeoutSeconds`로 `client.drain()`을 호출한다.
3. `timedOut=false`일 때만 consumer admission을 닫고 `callback.run()`을
   호출한다. Spring의 SQS/S3 client destroy보다 앞선 lifecycle phase를
   사용한다.
4. `timedOut=true`이면 consumer와 AWS client를 닫지 않고
   `SqsExtendedDrainTimeoutException` 진단을 기록하며 callback을 호출하지
   않는다. context가 계속 살아 있는 동안에는 이 소유권을 유지한다. Spring이
   자체 lifecycle timeout으로 다음 phase와 `destroyBeans()`를 강제로 진행하면
   AWS client destroy는 force-close로 분류되고, timeout event에
   `forcedContextClose=true`를 기록한다. 운영자는 그 경로를 정상 성공으로
   간주하지 않고 새 deadline으로 stop을 재시도하거나 명시적으로 in-flight
   작업을 취소한 뒤 다시 stop해야 한다.

직접 만든 client bean이나 test context도 동일 bridge를 사용해야 하며,
bridge 없이 `SqsAsyncClient`/`S3AsyncClient`를 먼저 닫는 것은 지원하지 않는다.
shutdown test는 producer gate off → drain admission reject → in-flight 완료 →
consumer gate off → AWS client close 순서와 timeout 시 close 미호출을 모두
기록한다. 추가적인 `ApplicationContext.close()` timeout 통합 테스트는 Spring
force-close가 별도 상태임과 `forcedContextClose=true` 표시를 검증한다.

`stopForSpring`의 선형화 지점은 producer gate를 닫는 시점이다. 그 이후
admission counter에 들어온 작업은 모두 `SQS_EXT_DRAIN_002`로 거부되고,
이미 counter에 들어온 작업만 `drain()`의 `activeAtStart`/`completed`에
포함된다. `timedOut=false` 결과를 받은 뒤에만 consumer gate와
`SmartLifecycle` running 상태를 닫고 callback을 실행한다. 이 규칙은
동시 send/receive와 context stop race 테스트에서 gate·counter·client close의
관찰 가능한 순서로 검증한다.

### 3. Pointer envelope

새로운 pointer는 AWS Java Extended Client의 내부 JSON을 복제하지 않는다.
새 dependency 없이 엄격한 parser를 제공하고, 상호운용이 필요한 경우에는
별도 migration 설계를 요구하기 위해 다음의 versioned text envelope를
사용한다.

```text
BT4K-SQS-S3/2/<bucket-base64url>/<key-base64url>/<content-type-base64url-or-empty>/<encrypted-0-or-1>/<signature-base64url>
```

규칙:

1. prefix와 version은 exact match한다. 현재 version은 `2` 하나만 지원한다.
2. 각 값은 `Base64.getUrlEncoder().withoutPadding()`으로 인코딩하므로 `/`,
   `|`, CR/LF가 envelope에 들어가지 않는다.
3. bucket과 key는 decode 후 blank가 아니어야 한다.
4. content type이 없으면 빈 segment이고, 있으면 원래 문자열을 보존한다.
5. encrypted flag는 `0` 또는 `1`만 허용한다.
6. `signature`는 policy의 `pointerSigningKeyRef`로 선택한 32바이트 이상
   secret과 아래의 length-prefixed canonical byte tuple을 HMAC-SHA-256한 값이다.
   producer와 consumer는 동일한 byte sequence를 사용하고 constant-time
   compare를 한다.
7. receive 전에 queue policy의 exact bucket·normalized keyPrefix·encrypted mode·
   signature를 검증한다. policy resolution 결과가 없으면 body를 항상
   opaque inline 문자열로 반환하고 S3를 호출하지 않는다.
8. 알 수 없는 추가 segment, 잘못된 base64, 잘못된 version/type/signature는
   `SqsExtendedPointerFormatException`으로 거부한다.
9. decoded content type은 control character/CR/LF가 없고 RFC media-type
   길이 상한을 만족해야 한다. 모든 segment는 decode 전에 bounded length를
   통과해야 한다.
10. envelope byte size가 선택한 정책의 `maxInlineBytes`를 넘으면 S3 upload
   전에 send를 거부한다.

pointer 서명 입력은 문자열을 단순히 `|`로 이어 붙이지 않는다. 다음 고정
domain과 version을 포함한 length-prefixed UTF-8 binary tuple을
HMAC-SHA-256한다.

```text
domain = "bluetape4k.sqs.extended.pointer"
fields = [
  domain, "2", canonicalQueueUrl, normalizedKeyPrefix, bucket, key,
  contentType-or-empty, encrypted-flag, canonicalEncryptionContext,
  encryption.keyFingerprint-or-empty,
]
signingBytes = fieldCount(10) + forEach(field) { uint32(byteLength) + utf8(field) }
```

각 field에는 NUL이 들어갈 수 없고 byte length는 unsigned big-endian으로
기록한다. `canonicalEncryptionContext`는 UTF-8 byte 순으로 정렬한
`keyLength/key/valueLength/value` tuple이며, `keyFingerprint`는 configured
CMK ARN identity와 canonical encryption context에서 계산한 stable non-secret
digest다. producer와 consumer는 이 동일한
tuple을 사용하고 signature 비교는 constant-time으로 수행한다.

작은 메시지는 이 prefix를 포함하지 않고 기존 본문을 그대로 보낸다. 따라서
기존 consumer는 일반 메시지를 계속 읽을 수 있고, extended consumer가
아닌 consumer는 offloaded 메시지를 일반 pointer 문자열로 보게 된다. producer
offload를 켜기 전 consumer drain → extended consumer 배포 → consumer health
check → producer enable 순서를 지킨다. rollback은 다음 상태 기계를 통과해야
한다.

```text
RUNNING_EXTENDED
  -> PRODUCER_DISABLED
  -> LEGACY_CONSUMER_STOPPED
  -> EXTENDED_DRAINING
  -> DRAIN_VERIFIED(pointerCount=0, inFlight=0, drained=true)
  -> QUARANTINE_REHYDRATING(source=quarantine, destination=legacy-safe-queue)
  -> LEGACY_REDRIVE_VERIFIED(rehydratedCount=quarantinedPointerCount,
     destinationPointerCount=0, pointerRemaining=0)
  -> LEGACY_CONSUMER_STARTED

실패 경로: 어느 단계에서든 `DEADLINE_EXCEEDED` 또는
`REDRIVE_BUDGET_EXHAUSTED`이면 `ROLLBACK_BLOCKED`로 고정하고 legacy start를
허용하지 않는다.
```

각 전이는 producer/consumer gate 기준 데이터, queue attributes, SQS receive
count/redrive budget, extended `drain()` 결과, pointer count, in-flight count,
source·destination queue URL, DLQ/quarantine count, idempotency 결과를 immutable
deployment evidence에 기록한다.
`drained=true`, `inFlight=0`, `pointerCount=0`가 모두 아니면 redrive와 legacy
start를 금지하고 rollback을 중단한다. `QUARANTINE_REHYDRATING`은 native
SQS redrive가 아니라 extended consumer가 quarantine의 pointer를 복원해
body·content type·message/system attributes·FIFO metadata를
`legacy-safe-queue`에 inline body로 재발행하고, publish 성공 후에만
quarantine receipt를 delete하는 migration worker다. 이 단계의
`rehydratedCount == quarantinedPointerCount`, `destinationPointerCount == 0`,
`pointerRemaining == 0`, idempotency 결과가 모두 확인되지 않으면
`LEGACY_REDRIVE_VERIFIED`와 legacy start를 금지한다. legacy
`@SqsListener`와 AWS Java Extended Client는 이 envelope를 복원하지 않으며,
extended consumer가 pointer를 quarantine/drain하기 전에 legacy consumer를
시작하거나 같은 queue에 붙일 수 없다. legacy consumer가 pointer를 handler
성공으로 ack할 수 있는 구성과 pointer를 native redrive하는 구성은 validation에서
거부한다. `quarantineRehydrationRestoresInlinePayloadBeforeLegacyStart` 통합
테스트는 quarantine pointer를 extended consumer로 읽고, 원본 body·content
type·message/system attributes·FIFO metadata를 inline으로 재발행한 뒤에만
원본 receipt를 삭제하는 순서를 검증한다. 같은 test는 재시작/idempotent
재실행에서 중복 inline message가 생기지 않는지, native SQS redrive가
`SqsExtendedConfigurationException`(`diagnosticCode=SQS_EXT_CONFIG_001`)으로
거부되는지, 그리고 네 count gate가 모두 충족될 때만
`LEGACY_CONSUMER_STARTED`로 전이되는지를 확인한다.

`DRAIN_VERIFIED`의 `pointerCount=0`은 adapter counter만으로 판정하지 않는다.
extended queue에 대해 configured maximum visibility/retry window 동안
bounded empty-receive probe를 수행하고, 서로 다른 두 관찰 구간에서 연속
empty 결과를 얻어야 한다. 각 probe에는 queue URL, receive count, visible·inflight
attribute 기준 데이터, pointer count, observation deadline을 immutable evidence로
기록한다. probe 중 pointer 또는 in-flight가 다시 보이면 상태를
`EXTENDED_DRAINING`으로 되돌리고 window를 새로 시작한다. 따라서 queue-level
quiescence가 확인되기 전에는 `QUARANTINE_REHYDRATING` 또는 legacy start를
허용하지 않으며, `rollbackRequiresVisibilityWindowQuiescence` 테스트가
hidden pointer 재등장과 두 번의 연속 empty probe를 검증한다.
probe는 extended adapter admission을 우회하는 rollback-controller 전용 raw
SQS `ReceiveMessage(maxNumberOfMessages=1, visibilityTimeout=0,
waitTimeSeconds=0, messageSystemAttributeNames=["ApproximateReceiveCount"])`
호출이다. probe는 receipt를 delete하거나 visibility를 변경하지 않고 pointer
body와 receive count만 세며, operation counter·`drain().completed`를 증가시키지
않는다. rollback 시작 시 queue `RedrivePolicy`에서 `maxReceiveCount`와 DLQ URL을
확정하고, receive count가 그 budget에 도달했거나 DLQ count가 예상 밖으로
증가하면 즉시 `ROLLBACK_BLOCKED(reason=REDRIVE_BUDGET_EXHAUSTED)`로 중단한다.
`RedrivePolicy`가 없으면 “native redrive 없음”을 immutable evidence로 기록하고,
malformed/unknown policy는 budget을 추측하지 않고 같은 blocked 상태로 처리한다.
quarantine과 DLQ의 pointer count는 각각 source/destination count gate로
기록하며 native redrive로 암묵적으로 합산하지 않는다. 첫 empty 결과 뒤
`probeInterval = min(30s, max(1s, configuredMaxVisibilityRetryWindowSeconds / 2))`
만큼 기다려 두 번째 empty 결과를 얻는다. 관찰 window deadline은
`min(now + configuredMaxVisibilityRetryWindowSeconds, rollbackDeadline)`로
계산하며, non-empty 또는 in-flight 재등장 시 empty streak를 0으로 되돌리되
새 window만 시작하고 전체 `rollbackDeadline`은 연장하지 않는다. 전체 deadline
전에 window가 끝나지 않거나 redrive/DLQ gate가 안정되지 않으면
`ROLLBACK_BLOCKED(reason=DEADLINE_EXCEEDED)`로 전이하고 추가 probe·rehydration·
legacy start를 모두 금지한다. `DRAIN_VERIFIED` 전이는 두 번째 empty만으로
충분하지 않으며 반드시 해당 observation window가 끝나고 receive count/redrive
budget·DLQ/quarantine count gate가 통과된 뒤에만 허용한다. 따라서 window 끝
직전에 pointer가 visibility로 돌아오는 경우도 관찰하며, near-deadline
재등장은 global deadline까지 bounded하게 재관찰한다.
`rollbackProbeDoesNotAdmitOrDeleteMessages` 테스트가 raw 호출 경계,
visibility 0, delete 0회, counter 오염 0회를 검증하고,
`rollbackWaitsUntilVisibilityWindowDeadline` 테스트가 deadline 전 전이 거부와
3590초 pointer 재등장을 재현한다. `rollbackProbeGuardsReceiveCountAndDlq`
테스트는 `ApproximateReceiveCount`/`maxReceiveCount`, DLQ·quarantine count
gate와 native redrive 거부를 검증하고, `rollbackBlocksAfterGlobalDeadline`
테스트는 pointer 재등장이 관찰 window를 반복해서 시작해도 전체 deadline을
넘은 뒤 `ROLLBACK_BLOCKED`로 고정되는지 검증한다.

### 4. Send state machine

`send`는 policy를 queue URL로 먼저 결정한 뒤 본문 byte size를 계산한다.
String을 UTF-8 byte로 바꿀 때는 `CharsetEncoder`를
`CodingErrorAction.REPORT`로 구성해 malformed surrogate를 거부한다. 먼저
고정 크기 scratch buffer를 사용하는 bounded preflight pass로 byte count를
`maxOffloadPayloadBytes + 1`까지만 계산하고, 상한을 넘으면 payload-sized
`ByteArray`를 만들지 않고 즉시 중단한다. 허용된 경우에만 exact-size 단일
`ByteArray`를 할당해 size 검사·hash·upload에 재사용한다. 따라서 adapter는
payload를 무제한으로 복제하거나 oversize 임시 buffer를 만들지 않는다.
receive의 S3 adapter도 bounded object read를 사용해
`maxOffloadPayloadBytes`보다 큰 object는 `HeadObject`/bounded stream probe로
전체 payload를 materialize하기 전에 거부한다. 암호화 경로는 plaintext와 fixed envelope overhead를 각각 같은
상한으로 검증하며, send upload와 receive decrypt의 CPU 구간 모두
`withContext(Dispatchers.Default)` 안에서 실행한다.
기존 `S3Operations.headObject`가 `UnsupportedOperationException`인 custom
delegate에는 list/resource fallback을 사용하지 않고
`SqsExtendedPayloadReadException`(`retryable=false`)으로 fail closed한다.

| 단계 | 조건 | 동작 | 실패 시 계약 |
|---|---|---|---|
| inline | policy 없음, `producerEnabled=false`, 또는 `bytes <= offloadThresholdBytes` | 기존 `SqsOperations.send` 위임 | policy/admission 오류는 configuration exception; SQS send 오류는 `SqsExtendedSendException.inlineSqs()`를 throw |
| offload 준비 | `bytes > threshold` | idempotency key 검증, deterministic key와 authenticated pointer를 만들고 envelope size 검증 | pointer/configuration exception, S3 미호출 |
| S3 저장 | pointer 검증 성공 | 일반 `S3Operations.upload` 또는 encryption operation으로 1회 저장. encrypted upload의 CPU 구간은 `Dispatchers.Default` | S3 upload 실패는 `SqsExtendedSendException.upload()`를 throw |
| SQS 제출 | S3 저장 성공 | pointer envelope를 body로 `SqsFullRequestOperations.send`에 제출 | SQS 제출 실패는 `SqsExtendedSendException.offloadedSqs()`를 throw하고, 결과가 불확실해도 S3 객체를 자동 삭제하지 않는다 |
| 완료 | SQS 응답 수신 | `offloaded=true`와 pointer를 포함한 result 반환 | S3 object는 ack 전까지 유지 |

extended adapter는 message attributes, FIFO group/deduplication id와 모든
기존 request field를 보존할 수 있는 `SqsFullRequestOperations` delegate만
사용한다.
`SqsCoroutinesTemplate`는 이 capability를 제공하며, custom
`SqsOperations`가 capability를 제공하지 않으면 extended bean은 생성되지
않고 configuration exception을 반환한다. 기존 `SqsOperations`의 default
단순 send 구현이 field를 버리는 경우를 extended path에서 묵묵히 사용하지
않는다.

offloaded send에는 `idempotencyKey`가 필수다. key는
`<normalized-keyPrefix>/<sha256(queueUrl|idempotencyKey|payloadSha256)>`로
결정해 응답 유실 후 재시도에서도 같은 S3 object와 같은 pointer를 사용한다.
표준 queue에서 SQS duplicate delivery 자체를 제거한다고 약속하지 않으며,
FIFO queue에서는 기존 `messageGroupId`·`messageDeduplicationId`를 full-request
delegate가 그대로 보존한다.

SQS `send`가 명확한 client validation으로 SQS 요청 전에 실패한 경우에도
pointer 삭제를 자동으로 시도하지 않는다. SDK 예외만으로 서버가 메시지를
받지 않았다고 단정할 수 없기 때문이다. inline SQS 제출 실패는
`SqsExtendedSendException.inlineSqs()`를 throw하고, offload 후 SQS 제출 실패는
`SqsExtendedSendException.offloadedSqs()`를 throw한다. 두 factory의 invariant는
각각 `orphanCleanupRequired=false/pointerPresent=false`와
`orphanCleanupRequired=true/pointerPresent=true`로 고정한다. S3 upload 후
caller cancellation은 일반 send exception으로 변환하지 않고
`SqsExtendedCancellationException`(원래 `CancellationException`의 subtype)을
throw하며 `failureKind=SQS_SEND`, `orphanCleanupRequired=true`,
`pointerPresent=true`와 고정 `diagnosticCode`만 제공한다. pointer/payload
원문은 예외·cause·suppressed·stack trace에 넣지 않는다. caller의 정리 작업은
공개 pointer cleanup이 아니라 S3 lifecycle rule에 위임한다.

### 5. Receive와 restore

수신은 먼저 `queueUrl`의 exact policy를 해석한 뒤 기존
`SqsOperations.receive`를 호출하고 각 `SqsReceivedMessage.body`를 검사한다.

- policy가 없으면 body를 prefix와 관계없이 opaque inline payload로 취급해
  `pointer=null`, `contentType=null`인 message를 그대로 반환한다.
- policy가 있고 prefix가 없으면 `pointer=null`, `contentType=null`인 inline
  message를 그대로 반환한다.
- policy가 있고 prefix가 있으면 parser로 authenticated pointer를 검증하고,
  `encrypted=0`이면 `S3BoundedObjectReadOperations.downloadBytesBounded`,
  `encrypted=1`이면 `S3BoundedEncryptedReadOperations.downloadEncryptedBytesBounded`
  만 호출한다. 기존 `S3Operations.downloadBytes`와
  `S3ClientSideEncryptionOperations.downloadEncryptedBytes`는 pointer path에서
  금지한다.
- 복원한 byte는 `CharsetDecoder`에 `CodingErrorAction.REPORT`를 설정해
  strict UTF-8로 decode한다. 현재 adapter의 payload 계약은 UTF-8 String이며,
  pointer의 `contentType`은 결과 model에 보존한다.
- 암복호화의 동기 CPU 구간은 `withContext(Dispatchers.Default)`로 격리하고,
  caller의 event-loop dispatcher를 차단하지 않는다.
- S3 read/decrypt/UTF-8 decode가 실패하면
  `SqsExtendedPayloadReadException`을 던지고 해당 receipt handle을
  delete하지 않는다. visibility/retry는 호출자의 기존 SQS 정책에 맡긴다.
- pointer가 유효하지만 object가 존재하지 않으면 payload read failure로
  분류한다. 자동으로 pointer를 고치거나 다른 object를 검색하지 않는다.

encrypted receive는 기존 unbounded `downloadEncryptedBytes`만으로 구현하지
않는다. `S3BoundedEncryptedReadOperations`라는 additive capability를 정의해
`downloadEncryptedBytesBounded(..., maxCiphertextBytes)`가 response stream을
`maxCiphertextBytes`에서 중단하고, 허용된 plaintext ByteArray만 반환하게
한다. 현재 AES-GCM wire contract의 ciphertext bound는
`maxOffloadPayloadBytes + 16`(GCM tag 16바이트)로 고정하며, metadata와
encrypted key는 S3 object body가 아닌 HEAD metadata이므로 bound에 포함하지
않는다. encrypted policy에서는 이 capability가 반드시 있어야 하며,
`headObject`는 bounded GET 전에 early reject를 수행하는 선택적 최적화일
뿐이다. capability가 없으면 S3 GET 전에
`SqsExtendedPayloadReadException(S3_READ)`으로 fail closed한다. HEAD가
작다고 보고해도 bounded stream이 실제 응답 초과를 발견하면 decrypt
전에 중단하며, dishonest-HEAD/oversize ciphertext 회귀 테스트는 전체
payload materialization과 decrypt 호출이 없음을 검증한다. custom
`S3ClientSideEncryptionOperations`가 이 capability를 제공하지 않는 경우
자동으로 기존 unbounded API로 fallback하지 않는다. 평문 custom
`S3Operations`도 `S3BoundedObjectReadOperations`를 제공하지 않으면 동일하게
`S3_READ`로 fail closed하며, dishonest-HEAD/oversize plaintext stream 테스트는
전체 payload materialization과 decode 호출이 없음을 검증한다.

복원 시에는 AWS `Message` builder로 body만 바꾼 복사본을 만들고 message ID,
receipt handle, system/message attributes는 그대로 유지한다. queue URL과
acknowledgement token은 원본 수신 결과를 참조한다.

### 6. Ack와 cleanup

`acknowledge(message)`의 순서는 고정한다.

1. 먼저 기존 `SqsOperations.delete(queueUrl, receiptHandle)`을 호출한다.
2. SQS delete가 실패하면 S3 delete를 호출하지 않고 원래 receipt handle이
   재처리될 수 있는 상태로 `SqsExtendedAcknowledgementException`을
   throw한다. `CancellationException`은 삼키지 않고 그대로 재전파한다.
3. `duplicateAfterCleanup=true`이면 SQS delete 성공만 확정하고
   `payloadDeleted=false`, `cleanupRequired=false`를 반환한다. marker는
   lifecycle ownership으로 남긴다.
4. SQS delete가 성공했고 pointer가 없거나 `deleteOnAck=false`이면
   `sqsDeleted=true`, `payloadDeleted=false`, `cleanupRequired=false`를
   반환한다. object cleanup은 S3 lifecycle이 담당한다.
5. SQS delete가 성공했고 `deleteOnAck=true`이면 먼저
   `<normalized-keyPrefix>/.ack-marker/<sha256(bucket|key)>`에 zero-byte
   marker를 idempotently 생성한 뒤 pointer의 encryption flag와
   acknowledgement token의 context로 S3 object를 삭제한다. marker에는
   `bt4k-marker-version=1`, `bt4k-pointer-digest=<sha256(canonical envelope)>`,
   `bt4k-policy-fingerprint=<policy fingerprint>`,
   `bt4k-queue-url-digest=<sha256(canonical queue URL)>` metadata를 함께
   기록하며 payload 본문이나 secret은 넣지 않는다. marker key가 이미 있으면
   새로 overwrite하지 않는다. 먼저 HEAD metadata를 읽어 네 값이 모두 현재
   acknowledgement token과 일치할 때만 기존 marker를 idempotent 성공으로
   간주하고, 불일치하면 stale/foreign marker configuration failure를
   반환하며 payload delete를 금지한다. marker가 없을 때만 conditional
   create를 한 번 시도하고, create race에서 이미 존재한다는 응답을 받으면
   동일한 HEAD 비교를 수행한다.
   marker와 payload delete는 `withContext(NonCancellable)` 경계 안에서 수행해
   SQS ack 이후 cancellation이 S3 cleanup을 중단시키지 않게 한다.
6. marker와 S3 delete까지 성공하면 `payloadDeleted=true`를 반환한다. marker
   생성 또는 payload delete가 실패하면 `cleanupRequired=true`와 marker key를
   포함한 opaque handle을 반환하고, `cleanup(handle)`은 marker 존재를 보장한
   뒤 payload delete를 idempotently 재시도한다.
7. S3 delete가 실패하면 SQS ack는 이미 완료된 상태이므로
   `sqsDeleted=true`, `payloadDeleted=false`, `cleanupRequired=true`,
   `failureKind=S3_DELETE`, `retryable=true`와 ACK 성공으로 발급된
   `cleanupHandle`을 반환한다. 호출자는 같은 opaque handle로 `cleanup`을
   재시도할 수 있고, S3 `DeleteObject`의 idempotent 특성을 사용한다.

`cleanup(handle)`은 ACK 성공으로 발급된 handle의 policy fingerprint와
현재 queue policy를 다시 비교한 뒤에만 S3 delete를 수행한다. handle이 없거나
fingerprint가 다르면 `SqsExtendedCleanupException(handlePresent=false,
failureKind=CONFIGURATION)`으로 fail closed하며, pointer를 직접 받는 overload는
없다. 같은 handle의 S3 delete failure는 `cleanupRequired=true`,
`failureKind=S3_DELETE`, `retryable=true`, `cleanupHandle=handle`인
`SqsExtendedCleanupResult`로 반환한다. `cleanup(handle)`도 marker metadata를
먼저 검증하며, metadata가 검증되지 않으면 payload `DeleteObject`를 호출하지
않는다.
SQS가 같은 메시지를 재배달하거나 같은 pointer가 동시에 여러 번 수신되어도
각 acknowledgement는 같은 S3 key에 대해 idempotent delete를 수행한다.
`deleteOnAck=true`에서 payload GET이 missing이면 adapter는 marker를 HEAD로
확인하고 version·pointer digest·policy fingerprint·queue URL digest를 모두
constant-time으로 비교한다. 값이 모두 일치하면
`duplicateAfterCleanup=true`인 message를 반환하고, 그 message의 ack는 SQS
receipt만 삭제해 poison retry를 막는다. marker가 없거나 metadata가
불일치하면 stale/foreign marker로 분류해 `S3_READ` 또는 `CONFIGURATION`
failure를 던지고 receipt를 delete하지 않는다. marker 존재만으로 terminal
duplicate를 판정하지 않는다.
marker와 payload는 orphan lifecycle rule에서 동일 retention age를 사용한다.
중복 delivery를 cleanup 전에 허용해야 하는 애플리케이션은 기본값인
`deleteOnAck=false`와 lifecycle retention을 사용한다.
adapter는 in-memory reference count나 무제한 pointer cache를 두지 않는다.

send/receive/ack 어느 단계에서든 caller cancellation이 발생하면
`CancellationException`을 재전파한다. 단, S3 upload가 이미 완료된 send 취소는
위의 redacted `SqsExtendedCancellationException` subtype으로 변환해 orphan
후보임을 전달한다. SQS delete가 완료된 ack 취소는 `NonCancellable` cleanup
결과를 끝까지 계산한 뒤 원래 cancellation을 재전파하며, cancellation 경로에는
cleanup handle result를 발급하지 않는다. cleanup handle은 SQS delete 성공
이후 정상적인 S3 delete 실패에서만 발급한다.

operation별 observable outcome은 다음 표로 고정한다.

| operation | 정상 | 외부 실패 | caller cancellation |
|---|---|---|---|
| `send` | `SqsExtendedSendResult` | typed send/configuration/pointer exception throw | upload 후 `SqsExtendedCancellationException`, 그 전에는 원래 `CancellationException` |
| `receive` | list/flow message | typed pointer/read exception throw, SQS delete 0회 | 원래 `CancellationException` 재전파 |
| `acknowledge` | `SqsExtendedAcknowledgementResult` | SQS delete 실패는 typed ack exception throw; S3 delete 실패는 retryable result | `NonCancellable` cleanup 후 원래 `CancellationException` |
| `cleanup` | `SqsExtendedCleanupResult(deleted=true)` | invalid/fingerprint mismatch는 typed cleanup exception; S3 delete 실패는 retryable result+same handle | 원래 `CancellationException` 재전파 |
| `receiveFlow` | cold flow collect와 item 순차 변환 | collect admission 이후 drain은 기다리고, drain 이후 새 collect는 `SqsExtendedConfigurationException(diagnosticCode=SQS_EXT_DRAIN_002)` | counter를 `finally`에서 감소하고 원래 `CancellationException` 재전파 |
| `drain` | `SqsExtendedDrainResult(timedOut=false)` | deadline 만료는 `timedOut=true` 반환과 `SqsExtendedDrainTimeoutException` lifecycle event 기록; caller operation은 강제 취소하지 않음 | lifecycle bridge는 callback/client close를 수행하지 않고 명시적 재시도를 기다림 |

### 7. 실패와 보안 계약

public exception은 다음 bounded kind만 노출한다.

```kotlin
enum class SqsExtendedFailureKind {
    CONFIGURATION,
    POINTER_FORMAT,
    S3_UPLOAD,
    S3_READ,
    S3_DELETE,
    SQS_SEND,
    SQS_ACK,
    DRAIN_TIMEOUT,
}

enum class SqsExtendedDiagnosticCode(val value: String) {
    CONFIGURATION("SQS_EXT_CONFIG_001"),
    POINTER_FORMAT("SQS_EXT_POINTER_001"),
    S3_UPLOAD("SQS_EXT_S3_UPLOAD_001"),
    S3_READ("SQS_EXT_S3_READ_001"),
    S3_DELETE("SQS_EXT_S3_DELETE_001"),
    SQS_SEND("SQS_EXT_SQS_SEND_001"),
    SQS_ACK("SQS_EXT_SQS_ACK_001"),
    CANCEL("SQS_EXT_CANCEL_001"),
    DRAIN_TIMEOUT("SQS_EXT_DRAIN_001"),
    DRAIN_ADMISSION("SQS_EXT_DRAIN_002"),
}
```

send/receive/ack/cleanup 실패는 다음 sealed hierarchy의 구체 타입으로
구분한다. 모든 client exception은
`RuntimeException(message, null, false, false)` 형태로 만들어 raw SDK cause,
suppressed throwable와 stack trace를 보관하지 않는다.

```kotlin
sealed class SqsExtendedClientException(
    val failureKind: SqsExtendedFailureKind,
    val retryable: Boolean,
    val diagnosticCode: String,
) : RuntimeException(diagnosticCode, null, false, false)

class SqsExtendedConfigurationException private constructor(
    retryable: Boolean,
    diagnostic: SqsExtendedDiagnosticCode,
) : SqsExtendedClientException(
    SqsExtendedFailureKind.CONFIGURATION,
    retryable,
    diagnostic.value,
) {
    internal companion object {
        fun create(retryable: Boolean = false): SqsExtendedConfigurationException =
            SqsExtendedConfigurationException(retryable, SqsExtendedDiagnosticCode.CONFIGURATION)

        fun drainAdmission(): SqsExtendedConfigurationException =
            SqsExtendedConfigurationException(true, SqsExtendedDiagnosticCode.DRAIN_ADMISSION)
    }
}

class SqsExtendedPointerFormatException private constructor() : SqsExtendedClientException(
    SqsExtendedFailureKind.POINTER_FORMAT,
    false,
    SqsExtendedDiagnosticCode.POINTER_FORMAT.value,
) {
    internal companion object {
        fun create(): SqsExtendedPointerFormatException = SqsExtendedPointerFormatException()
    }
}

class SqsExtendedPayloadReadException private constructor(
    val pointerPresent: Boolean,
    retryable: Boolean,
) : SqsExtendedClientException(SqsExtendedFailureKind.S3_READ, retryable, SqsExtendedDiagnosticCode.S3_READ.value) {
    internal companion object {
        fun create(pointerPresent: Boolean, retryable: Boolean): SqsExtendedPayloadReadException =
            SqsExtendedPayloadReadException(pointerPresent, retryable)
    }
}

class SqsExtendedSendException private constructor(
    val pointerPresent: Boolean,
    val orphanCleanupRequired: Boolean,
    failureKind: SqsExtendedFailureKind,
    retryable: Boolean,
    diagnostic: SqsExtendedDiagnosticCode,
) : SqsExtendedClientException(failureKind, retryable, diagnostic.value) {
    init {
        require(orphanCleanupRequired || !pointerPresent)
        require(failureKind == SqsExtendedFailureKind.S3_UPLOAD || failureKind == SqsExtendedFailureKind.SQS_SEND)
    }

    internal companion object {
        fun upload(): SqsExtendedSendException =
            SqsExtendedSendException(false, false, SqsExtendedFailureKind.S3_UPLOAD, true, SqsExtendedDiagnosticCode.S3_UPLOAD)

        fun inlineSqs(): SqsExtendedSendException =
            SqsExtendedSendException(false, false, SqsExtendedFailureKind.SQS_SEND, true, SqsExtendedDiagnosticCode.SQS_SEND)

        fun offloadedSqs(): SqsExtendedSendException =
            SqsExtendedSendException(true, true, SqsExtendedFailureKind.SQS_SEND, true, SqsExtendedDiagnosticCode.SQS_SEND)
    }
}

class SqsExtendedAcknowledgementException private constructor(
    val sqsDeleted: Boolean,
    val cleanupRequired: Boolean,
    retryable: Boolean,
) : SqsExtendedClientException(
    SqsExtendedFailureKind.SQS_ACK,
    retryable,
    SqsExtendedDiagnosticCode.SQS_ACK.value,
) {
    internal companion object {
        fun create(sqsDeleted: Boolean, cleanupRequired: Boolean, retryable: Boolean = true): SqsExtendedAcknowledgementException =
            SqsExtendedAcknowledgementException(sqsDeleted, cleanupRequired, retryable)
    }
}

class SqsExtendedCleanupException private constructor(
    val handlePresent: Boolean,
    failureKind: SqsExtendedFailureKind,
    retryable: Boolean,
    diagnostic: SqsExtendedDiagnosticCode,
) : SqsExtendedClientException(failureKind, retryable, diagnostic.value) {
    internal companion object {
        fun configuration(handlePresent: Boolean = false): SqsExtendedCleanupException =
            SqsExtendedCleanupException(handlePresent, SqsExtendedFailureKind.CONFIGURATION, false, SqsExtendedDiagnosticCode.CONFIGURATION)

        fun delete(handlePresent: Boolean = true): SqsExtendedCleanupException =
            SqsExtendedCleanupException(handlePresent, SqsExtendedFailureKind.S3_DELETE, true, SqsExtendedDiagnosticCode.S3_DELETE)
    }
}

class SqsExtendedDrainTimeoutException(
    val activeOperations: Int,
) : SqsExtendedClientException(SqsExtendedFailureKind.DRAIN_TIMEOUT, true, SqsExtendedDiagnosticCode.DRAIN_TIMEOUT.value) {
    init {
        require(activeOperations >= 0)
    }
}

class SqsExtendedCancellationException private constructor(
    val failureKind: SqsExtendedFailureKind,
    val pointerPresent: Boolean,
    val orphanCleanupRequired: Boolean,
    val diagnosticCode: String,
) : CancellationException() {
    init {
        require(!orphanCleanupRequired || pointerPresent)
        require(failureKind in setOf(SqsExtendedFailureKind.S3_UPLOAD, SqsExtendedFailureKind.SQS_SEND))
    }

    override fun fillInStackTrace(): Throwable = this

    internal companion object {
        fun create(
            failureKind: SqsExtendedFailureKind,
            pointerPresent: Boolean,
            orphanCleanupRequired: Boolean,
        ): SqsExtendedCancellationException =
            SqsExtendedCancellationException(
                failureKind,
                pointerPresent,
                orphanCleanupRequired,
                SqsExtendedDiagnosticCode.CANCEL.value,
            )
    }
}
```

`SqsExtendedClientException` 계열의 `message`, `toString()`, suppressed list에는
다음 값을 넣지 않는다.

- payload body, S3 bucket/key, queue URL, receipt handle
- message attributes, encryption key ID/context value
- SDK raw exception message, cause, suppressed throwable와 stack trace
- CR/LF를 포함한 외부 문자열

예외는 `failureKind`, `retryable`, `pointerPresent`, `orphanCleanupRequired`,
`sqsDeleted`, `cleanupRequired` 같은 low-cardinality 사실과 secret-safe
`diagnosticCode`만 제공한다. `diagnosticCode`는
`SQS_EXT_CONFIG_001`, `SQS_EXT_POINTER_001`, `SQS_EXT_S3_UPLOAD_001`,
`SQS_EXT_S3_READ_001`, `SQS_EXT_S3_DELETE_001`, `SQS_EXT_SQS_SEND_001`,
`SQS_EXT_SQS_ACK_001`, `SQS_EXT_CANCEL_001`, `SQS_EXT_DRAIN_001`,
`SQS_EXT_DRAIN_002` 중 하나로
고정한다. SDK 원본
예외는 운영 로그에 재출력하지 않고,
structured log에도 위 bounded field만 기록한다. CR/LF와 nested
`CompletionException`도 동일하게 scrub한다.

구체 예외를 만들 때 `failureKind`와 `diagnosticCode`의 조합도 고정한다.
S3 upload 실패는 `SqsExtendedSendException(failureKind=S3_UPLOAD,
diagnosticCode=SQS_EXT_S3_UPLOAD_001)`, SQS send 실패는
`SQS_SEND`/`SQS_EXT_SQS_SEND_001`, invalid cleanup handle·fingerprint는
`SqsExtendedCleanupException(failureKind=CONFIGURATION,
diagnosticCode=SQS_EXT_CONFIG_001)`, S3 delete 실패는
`S3_DELETE`/`SQS_EXT_S3_DELETE_001`을 사용한다. 기본 인자에 의한
kind/code 불일치는 허용하지 않고 constructor/factory validation으로 거부한다.

재시도 규칙은 다음과 같다.

| 실패 | 호출자가 재시도할 수 있는 작업 | 금지 사항 |
|---|---|---|
| S3 upload 실패 | 동일 `idempotencyKey`로 upload/send를 재시도 | 실패한 upload를 성공으로 간주 |
| SQS send 실패 | `orphanCleanupRequired=true`를 기록하고 lifecycle에 위임 | timeout을 이유로 object를 즉시 삭제 |
| S3 read 실패 | visibility를 유지하고 다음 delivery에서 재시도 | read 실패 후 SQS delete |
| SQS ack 실패 | 같은 received message의 ack를 재시도 | S3 object 선삭제 |
| S3 delete 실패 after ack | 발급된 `cleanupHandle`로 cleanup 재시도 또는 lifecycle에 위임 | SQS message 재전송을 성공 ack로 오인 |

### 8. 동시성과 backpressure

기본 API는 extended receive에서 `maxMessages=1`만 받아 단일 payload의
visibility lease와 실패 경계를 명확히 한다. pointer별 S3 read를 무제한
`launch`하지 않고 첫 구현은 순차 복원으로 시작한다. `receiveFlow`도 기존
`SqsOperations.receiveFlow`를 cold flow로 위임하되 adapter가 요청 batch를
collect admission을 먼저 확보하고 요청 batch를 1개로 제한하며 각 item을
순차 변환한다. collect 취소 시 caller의 `CancellationException`을 그대로
재전파하고 counter만 정리한다. 따라서 이번 범위에는 새로운 worker
pool, unbounded channel, 자동 retry loop, 대량 in-memory queue가 없다.

`acknowledge` 역시 한 메시지에 대해 SQS delete 후 S3 delete를 순차로
수행한다. 호출자가 여러 acknowledgement를 병렬화할 수 있지만 adapter가
숨은 concurrency를 만들지 않으므로 backpressure와 cancellation은
호출자의 coroutine scope에 귀속된다. 부분 성공 batch를 반환하지 않는 대신
단일 메시지 단위로 성공/실패를 확정한다.

### 9. Client lifecycle와 Spring wiring

`SqsExtendedClient`는 다음을 생성자로 받는다.

```kotlin
class SqsExtendedClient(
    private val sqsOperations: SqsFullRequestOperations,
    private val s3Operations: S3Operations,
    private val boundedS3Operations: S3BoundedObjectReadOperations?,
    private val s3MetadataOperations: S3ObjectMetadataOperations?,
    private val encryptedS3Operations: S3BoundedEncryptedReadOperations?,
    private val encryptionIdentity: S3ClientSideEncryptionIdentity?,
    private val properties: SqsExtendedClientProperties,
)

class SqsExtendedClientLifecycle(
    private val client: SqsExtendedClient,
    private val properties: SqsExtendedClientProperties,
) : SmartLifecycle {
    @Volatile
    private var running: Boolean = true

    companion object {
        // Spring stops higher phases before lower phases and destroys ordinary
        // singleton beans only after the lifecycle processor has returned.
        const val PHASE: Int = Int.MAX_VALUE - 100
    }

    override fun getPhase(): Int = PHASE

    override fun isAutoStartup(): Boolean = true

    override fun isRunning(): Boolean = running

    override fun start() {
        running = true
    }

    override fun stop() {
        stopInternal(callback = null)
    }

    override fun stop(callback: Runnable) {
        stopInternal(callback)
    }

    private fun stopInternal(callback: Runnable?) {
        if (!running) {
            callback?.run()
            return
        }
        runBlocking(Dispatchers.Default) {
            withContext(NonCancellable) {
                client.stopForSpring(
                    timeout = Duration.ofSeconds(properties.shutdownDrainTimeoutSeconds.toLong()),
                    onDrained = {
                        running = false
                        callback?.run()
                    },
                    onTimeout = { active ->
                        client.recordLifecycleFailure(SqsExtendedDrainTimeoutException(active))
                    },
                )
            }
        }
    }
}
```

`PHASE`는 구현·테스트에서 숨은 기본값으로 추론하지 않는 명시적 계약이다.
`SqsExtendedLifecycleOrderCondition`은 managed AWS client가 `SmartLifecycle`인
경우 adapter phase가 그 phase보다 큰지 검증하고, 같거나 큰 managed phase가
발견되면 bridge를 만들지 않고 `SQS_EXT_CONFIG_001`으로 fail closed한다.
lifecycle을 구현하지 않는 일반 client bean이면 Spring lifecycle processor가
먼저 반환된 뒤 singleton destroy가 실행되는 순서를
`lifecyclePhasePrecedesManagedClientClose` 테스트에서 recording bean으로
고정한다. custom concrete client도 같은 bridge phase를 사용하며, invalid
phase margin에서는 bridge를 만들지 않고 사용자 lifecycle 책임으로 남긴다.

`runBlocking(Dispatchers.Default)`는 Spring의 동기식 `SmartLifecycle` callback과
suspend `drain` 사이의 유일한 경계이며, application dispatcher나 client
scope를 사용하지 않는다. `stopForSpring`은 `NonCancellable` 안에서
producer gate를 한 번 닫고 `drain`을 호출하는 idempotent suspend 함수다.
정상 drain이면 consumer gate를 닫고 callback을 정확히 한 번 실행한다.
timeout이면 `SqsExtendedDrainTimeoutException(activeOperations)`을 기록하고
running 상태·consumer gate·AWS client ownership을 context가 살아 있는 동안
그대로 유지하며 callback을 실행하지 않는다. 이후의 명시적 stop 재시도는 같은
gate와 새 deadline으로 다시 drain할 수 있고, 이미 성공한 stop은 callback을
중복 실행하지 않는다. `ApplicationContext.close()`를 timeout 직후 호출하는
통합 테스트는 Spring force-close가 별도 상태임과 timeout event의
`forcedContextClose` 표시를 검증한다. 이 동기 경계와 timeout 재시도는
`lifecycleStopBridgesSuspendDrainWithoutClosingClients` 및
`lifecycleTimeoutLeavesRetryableRunningState` 테스트로 검증한다.

`SqsExtendedClientAutoConfiguration`은 위의 exact `@ConditionalOnClass`와
`@ConditionalOnAwsEnabled`, `@ConditionalOnProperty`(extended enabled),
`@ConditionalOnBean(SqsFullRequestOperations::class, S3Operations::class)`와
`@ConditionalOnMissingBean(SqsExtendedClientOperations::class)`를 사용한다.
새 client bean을 만들지 않으므로 기존 SQS/S3 `destroyMethod = "close"`와
사용자 정의 client/customizer가 그대로 적용된다.

자동 설정이 비활성화되거나 property가 false이면 extended bean과 S3 호출이
생기지 않는다. property가 true여도 정책이 없는 queue는 기존 inline path로
위임한다. 일반 `SqsOperations` bean은 항상 우선순위를 유지하고, extended
path는 full-request capability가 있는 marker bean만 사용한다.

## 테스트와 문서 수용 기준

### Unit/contract test

- `SqsExtendedClientPropertiesTest`: disabled 기본값, `256 KiB` default,
  `1 MiB` max, `64 MiB` offload payload max, threshold/max 관계, logical-name
  map과 canonical queue URL 중복, default allowlist, signing-key, orphan
  retention, producer/consumer gate, visibility minimum, shutdown drain timeout,
  encryption context와
  key fingerprint validation.
- `SqsExtendedClientPropertiesRedactionTest`: nested policy/queue/encryption와
  `/configprops`·structured log 출력이 sentinel bucket/keyPrefix/queue URL/
  context/fingerprint를 포함하지 않고 bounded count만 노출하는지 검증한다.
- `SqsExtendedClientDelegateCapabilityTest`: inherited default
  `SqsOperations.send(request)` fake는 extended bean에서 거부되고,
  `SqsFullRequestOperations` fake와 `MicrometerFullRequestSqsOperations`는
  FIFO group/deduplication id·message attributes를 그대로 전달하는지 검증한다.
  custom full `@Primary` delegate context에서는 auto-configured Micrometer
  wrapper가 0개이고 `SqsOperations` 주입이 모호하지 않은지도 검증한다.
- `SqsExtendedClientPolicyResolutionTest`: queue-specific exact URL이 default
  allowlist 밖에서도 명시적 opt-in으로 적용되는 경우, allowlist 안의 default
  policy가 적용되는 경우, overlap 및 no-default/unknown queue가 policy 없음으로
  귀결되는 경우를 하나의 matrix로 검증한다.
- `SqsExtendedClientPointerTest`: version/type exact match, base64url round
  trip, HMAC signature/queue-policy binding, bucket/keyPrefix/encryption mode
  mismatch, content type 보존, invalid segment/version/base64/encrypted flag,
  strict UTF-8/content-type control character, pointer envelope byte size,
  internal factory invalid bucket/key/signature rejection과 public constructor/
  `copy()` 부재를 검증한다.
- `SqsExtendedClientTest`: inline path에서 S3 호출 0회, threshold 경계의
  `<=` inline/`>` offload, Unicode UTF-8 byte size, deterministic key와
  required idempotency key, S3 upload 후 pointer send, S3 upload failure,
  `rejectsOversizeBeforeS3Call`, `rejectsMalformedUtf8BeforeS3Call`,
  `oversizePreflightAllocatesNoPayloadBuffer`,
  inline SQS send failure가 `SqsExtendedSendException.inlineSqs()`인지,
  offload 후 SQS send failure가 `SqsExtendedSendException.offloadedSqs()`인지와
  각 pointer/orphan invariant, S3 upload failure가
  `SqsExtendedSendException.upload()`인지,
  ambiguous SQS send와 `SqsExtendedCancellationException`의
  `orphanCleanupRequired`,
  policy 없는 queue의 forged prefix opaque 처리, receive restore와
  system/message attribute 보존, full-request FIFO/attribute 보존,
  `receiveRejectsObjectBeyondConfiguredPayloadBound`, plaintext bounded-read
  capability 부재 fail-closed, dishonest-HEAD/oversize plaintext stream에서
  전체 ByteArray materialization·decode 0회를 검증한다.
- `SqsExtendedClientAcknowledgementTest`: SQS delete 전 S3 delete 금지,
  ack 성공 후 `NonCancellable` delete-on-ack, `deleteOnAck=false`, S3 cleanup
  failure의 retryable state와 opaque cleanup handle, handle 없는 조기 cleanup
  거부, `markerMakesPostCleanupDuplicateTerminal`, duplicate
  pointer/idempotent cleanup, cancellation 이후 결과, read failure 후 delete
  0회, result factory 불변식(ack/cleanup 성공·실패·handle·pointer 관계와
  음수 `SqsExtendedDrainResult`/`SqsExtendedDrainTimeoutException` count 거부,
  `timedOut=false`일 때 `completed == activeAtStart` invariant),
  `drainWaitsForInflightAndRejectsNewWork`,
  `receiveFlowCollectAdmissionIsCountedAndPostDrainCollectIsRejected`.
- `SqsExtendedClientMetadataCapabilityTest`: `headObjectWithMetadata`와
  `putObjectIfAbsentWithMetadata`의 metadata 보존, `If-None-Match: *` 조건,
  412/409 conditional-create race의 `AlreadyExists` 후 HEAD 재검증,
  Micrometer wrapper의 capability 보존, marker metadata 불일치 시 payload
  delete 0회를 검증한다.
- `SqsExtendedClientBoundedReadCapabilityTest`: 기본
  `S3CoroutinesTemplate`, metrics-enabled `MicrometerBoundedS3Operations`,
  custom bounded delegate가 capability를 보존하고, markerless delegate는
  extended bean을 만들지 않는지 검증한다. `maxBytes + 1` probe가 초과 stream을
  즉시 중단하며 oversize `ByteArray`·decode를 만들지 않는지도 검증한다.
  plaintext bound와 ciphertext bound(`67_108_864`/`67_108_880`) 각각의
  0·음수·상한 초과·`Int.MAX_VALUE` 입력을 S3 호출 전에 거부하고,
  ciphertext의 `maxOffloadPayloadBytes + 16` 경계에서 `Long` 기반 `+1` probe가
  overflow 없이 동작하는지도 고정한다.
- `SqsExtendedClientRedactionTest`: `toString()`·exception·structured log
  appender에 payload body·bucket/key·queue URL·receipt handle·encryption
  context·raw AWS attribute/response·cause/stack trace/CR/LF가 나타나지 않음을
  검증한다. supported Jackson test는 public safe `body`와 safe attribute DTO가
  허용되는 별도 경계임을 확인하고, `SqsExtendedMessageAttribute`의 CR/LF
  `dataType` 거부와 `binaryValue` copy-on-read가 caller mutation을 원본에
  반영하지 않는지 검증한다.
- `SqsExtendedClientJacksonAutoConfigurationTest`: Jackson 3 classpath 부재,
  supported module 등록 순서, 기존 `SqsJacksonMessageConverter` 공존,
  사용자 module back-off, safe DTO field만 직렬화되는지 검증한다.
- `SqsExtendedClientSecurityTest`: supported Jackson 3
  `SqsExtendedClientJacksonModule`이 raw pointer/handle/AWS
  request·response·message를 serialize하지 않고 `ObjectOutputStream`은
  unsupported public model에서 실패하는지, forged-copy/foreign-message,
  cleanup fingerprint mismatch가 모두 fail closed하는지 검증한다.
- `SqsExtendedClientEncryptionIdentityTest`: additive identity marker의
  canonical KMS ARN fingerprint derivation, alias/wildcard 거부, pointer·S3
  metadata·현재 delegate identity·configured `keyFingerprint` equality 불일치와 bounded encrypted read 부재의
  fail-closed 동작, dishonest-HEAD/oversize ciphertext를 검증한다.
- `SqsExtendedClientRollbackTest`: `quarantineRehydrationRestoresInlinePayloadBeforeLegacyStart`,
  `rollbackRequiresVisibilityWindowQuiescence`,
  `rollbackWaitsUntilVisibilityWindowDeadline`,
  `rollbackProbeGuardsReceiveCountAndDlq`,
  `rollbackBlocksAfterGlobalDeadline`, marker metadata mismatch, duplicate-safe
  rehydration, native redrive rejection,
  `rollbackDeadlineCannotOutliveOrphanRetention`, retention보다 긴 deadline
  거부 및 네 count gate를 검증한다.
- `SqsExtendedClientAutoConfigurationTest`: imports 등록, after-order,
  SDK classpath 누락, disabled/no-policy/missing optional encryption bean,
  capability-less delegate, full-capability delegate, lifecycle bridge와 user
  bean back-off, custom interface bean과 invalid phase margin 조합에서 bridge가
  생성되지 않고 context가 정상적으로 유지되는지, custom concrete client와
  invalid phase margin 조합에서도 lifecycle bridge가 생성되지 않는지,
  metadata-capability/ bounded-object-read/ bounded-encrypted-read/
  identity-capability
  누락 시 policy별 fail-closed.
- `SqsExtendedClientLifecycleTest`: suspend drain을 동기 lifecycle callback에서
  격리하고 정상 callback 1회, timeout 시 running/client 유지와 명시적 재시도를
  검증한다. `lifecyclePhasePrecedesManagedClientClose`로 명시적 `PHASE`,
  `SqsExtendedLifecycleOrderCondition`, managed client destroy 순서를 고정하고,
  max 25초 budget과
  `ApplicationContext.close()`의 phase timeout margin, force-close event를 실제
  context에서 검증한다.
- `SqsExtendedClientApiCompatibilityTest`: 기존 `SqsOperations`/`S3Operations`
  legacy source와 ABI fixture가 모두 pre-change bytecode/source 기준 데이터와
  unchanged인지,
  optional AWS SDK classpath isolation에서 extended classes가 로드되지 않는지
  검증한다. 구현 시 `src/abi-fixtures/sqs-pre-change`와
  `src/abi-fixtures/s3-pre-change`의 fixture source/bytecode를 각각 고정하고,
  `:aws-spring-boot:verifySqsExtendedLegacyAbi`와
  `:aws-spring-boot:verifyS3ExtendedLegacyAbi` Gradle task가 clean checkout에서
  pre-change artifact checksum·`javap` public signature·source compile 결과를
  모두 비교한다. 두 task는 extended implementation classpath를 제외한
  optional-SDK isolation도 기록하고, 결과 JSON은 `build/reports/abi/issue-455/`
  아래에 남긴다.
- `SqsExtendedClientMetricsTest`: offloaded/orphan/payload-read/cleanup
  failure와 bounded failure kind가 고정 metric name/tag enum으로 기록되고
  payload, bucket/key, queue URL, diagnosticCode가 tag에 들어가지 않음을
  검증한다.

metric은 Micrometer `Counter`를 사용하고 다음 네 이름만 등록한다. 모든
tag는 표의 enum에서만 선택하며 queue URL/name, bucket, key,
`diagnosticCode`는 tag로 사용하지 않는다. `failureKind` 전체 enum은 8개로
고정되고 각 metric name은 표에 적힌 부분집합만 사용하므로 한 client의 series
수는 bounded vocabulary를 넘지 않는다.

| Metric name | 허용 tag | 값 |
|---|---|---|
| `bluetape4k.aws.sqs.extended.offload.total` | `outcome` | `inline`, `offloaded`, `rejected` |
| `bluetape4k.aws.sqs.extended.orphan.total` | `reason` | `sqs-send`, `cancelled` |
| `bluetape4k.aws.sqs.extended.payload-read.failure` | `failureKind` | `S3_READ`, `POINTER_FORMAT`, `CONFIGURATION` |
| `bluetape4k.aws.sqs.extended.cleanup.failure` | `failureKind` | `S3_DELETE`, `CONFIGURATION` |

`diagnosticCode`는 고정 enum이지만 metric tag로는 기록하지 않고 structured
event field로만 남긴다. 기본 alert 예시는 orphan counter 증가, payload-read
failure의 연속 증가, cleanup failure의 age 초과이며 threshold 값은 배포자가
SQS traffic과 S3 lifecycle에 맞춰 명시한다.

모든 Kotlin 테스트는 기존 `io.bluetape4k.assertions` assertion과
`Base58.randomString(16)`을 사용하고, boolean 비교를 직접
`shouldBeTrue`/`shouldBeFalse`로 쓰지 않는다. 관계 assertion은
`shouldBeLessThanOrEqualTo`, `shouldBeEqualTo`, 문자열은
`shouldContain`/`shouldNotContain` 등 `bluetape4k-assertions` API를 사용한다.

### Emulator smoke

Floci 우선 `SqsExtendedClientAwsEmulatorTest`에서 다음을 검증한다.

1. 정책이 적용된 queue에서 256 KiB 경계 아래/위 payload의 send/receive
   round-trip.
2. ack 후 S3 object가 삭제되는 unencrypted 경로.
3. pointer의 content type과 original SQS attributes가 결과에 남는다.

Floci가 S3 또는 KMS client-side encryption을 지원하지 않는 경우 encrypted
경로는 deterministic fake unit test로 유지하고, capability와 fallback 사유를
`docs/manual/en/guides/testing-and-operations.md`와
`docs/manual/ko/guides/testing-and-operations.md`에 기록한다. 실제 AWS
encrypted smoke 필요성과 IAM/KMS prerequisite를 함께 문서화한다. LocalStack
fallback도 같은 capability-gap schema와 명령·image/version·exit 증거를 남기며,
권위 증거로 승격하지 않는다. 실행 전 `docker info`와 exact image inspect,
Floci test command를 기록하고, Floci 미지원이면 명시적으로
`-Dbluetape4k.aws.emulator=localstack`을 재실행한다. 자동 fallback은 금지한다.

### 문서

`aws-spring-boot/README.md`와 `aws-spring-boot/README.ko.md`, canonical manual의
`docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`,
`docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`,
`docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md`,
`docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md`,
`docs/manual/en/modules/aws-spring-boot-sqs-examples.md`,
`docs/manual/ko/modules/aws-spring-boot-sqs-examples.md`,
`docs/manual/en/guides/testing-and-operations.md`,
`docs/manual/ko/guides/testing-and-operations.md`,
`examples/aws-spring-boot-sqs-examples/README.md`,
`examples/aws-spring-boot-sqs-examples/README.ko.md`,
`examples/aws-spring-boot-sqs-examples/src/test/resources/application-extended.yml`
및 example smoke test에 같은 정책을 기록한다. `application-extended.yml`을
example configuration의 단일 source of truth로 삼고 README에는 이 파일과
동일한 최소 설정만 발췌한다. 두 README와 모든 관련 manual에는 다음 canonical snippet을 그대로
컴파일 가능한 import/package 경계로 포함한다.

```kotlin
import io.bluetape4k.aws.spring.sqs.SqsExtendedClientOperations
import io.bluetape4k.aws.spring.sqs.SqsExtendedSendRequest
import io.bluetape4k.aws.spring.sqs.SqsSendRequest

suspend fun publishAndAcknowledge(
    client: SqsExtendedClientOperations,
    payload: String,
    idempotencyKey: String,
) {
    val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/extended"
    val request = SqsExtendedSendRequest(
        request = SqsSendRequest(queueUrl = queueUrl, body = payload),
        contentType = "text/plain; charset=utf-8",
        idempotencyKey = idempotencyKey,
    )
    client.send(request)
    val message = client.receive(queueUrl = queueUrl, maxMessages = 1).single()
    require(message.body.isNotEmpty())
    val ack = client.acknowledge(message)
    if (ack.cleanupRequired) client.cleanup(requireNotNull(ack.cleanupHandle))
}
```

`@SqsListener`/기존 `SqsMessageListenerContainer`는 pointer를 복원하지 않는
legacy consumer이므로 extended queue에 연결하지 않는다는 경고를 snippet 바로
옆에 둔다. 문서 acceptance는 이 snippet의 compile test와 example README
source-link 검사를 모두 요구하며, 둘 중 하나를 선택하는 `OR` gate를 두지
않는다. 다음 항목을 포함한다.

- `bluetape4k.aws.sqs.extended.enabled`와 queue/default policy 예시
- `producerEnabled`, `consumerEnabled`, `shutdownDrainTimeoutSeconds`의 독립
  runtime gate와 Spring stop 재시도 의미, `rollbackDeadlineSeconds`의 명시값
  범위·미지정 derived 값(`configuredMaxVisibilityRetryWindowSeconds + 60초`,
  hard maximum 604800초), `orphanRetentionHours`보다 긴 deadline 거부 및
  bounded probe cost
- 256 KiB는 default offload threshold이고 SQS hard limit은 1 MiB라는 구분
- S3 저장·GET 비용, SQS pointer consumer 전환 주의사항
- IAM `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`와 KMS 권한
- `deleteOnAck`, ambiguous send, orphan object와 S3 lifecycle rule
- encryption wire format이 AWS Extended Client library와 호환되지 않는다는
  점과 실제 AWS smoke 필요성
- `pointerSigningKeyRef`와 secret map, exact queue allowlist, logical-name map 규칙,
  `idempotencyKey`, `maxOffloadPayloadBytes`, `maxMessages=1`
- `S3BoundedObjectReadOperations`와 `S3BoundedEncryptedReadOperations`의 기본
  구현·custom delegate capability 보존, markerless delegate의 `S3_READ`
  fail-closed, `maxBytes`의 `1..67_108_864` 및 `maxCiphertextBytes`의
  `1..67_108_880`(`67_108_864 + 16`) 경계, `Long` 기반 `max + 1`
  overflow guard, oversize `ByteArray`/decode를 만들지 않는 경계를 EN/KO
  manual과 example guidance에 동일하게 기록
- producer disable → consumer drain/redrive → extended consumer 배포 → producer
  enable 순서와 rollback 절차
- `offloaded`, `orphan`, `payload-read`, `cleanupRequired`, `failureKind`의
  low-cardinality metrics/alert와 raw payload 금지
- S3 lifecycle prefix, minimum age(`orphanRetentionHours`), SQS retention/
  visibility보다 긴 보존 조건, IAM resource ARN·KMS context 예시

문서 acceptance는 manual EN/KO structural parity, README/manual/source link
검사, canonical snippet compile test, example configuration smoke를 모두
포함한다. structural parity만으로 의미 누락을 숨기지 않도록 다음 semantic
parity matrix를 사용한다.

| EN/KO artifact pair | 양쪽에 동일하게 보장할 의미 필드 | 증거 |
|---|---|---|
| `aws-spring-boot/README.*` | property prefix, policy/allowlist, 256 KiB·1 MiB·64 MiB 경계, IAM/KMS, pointer 비호환 경고 | Markdown link/source scan + parity report |
| `storage-and-messaging.md` | send/receive/ack/cleanup API, body·metadata, bounded plaintext/encrypted read capability, `deleteOnAck`, orphan/retention | manual contract test + snippet compile |
| `runtime-operations.md` | producer disable → drain → rehydrate rollback, drain timeout, rollback deadline·retention cross-check, metrics/alerts | manual contract test + rollback checklist |
| `aws-spring-boot-sqs-examples.md`와 example `README.*` | exact config path, Floci command, `@SqsListener` 금지, inline rehydration smoke | source-link scan + example smoke |
| `testing-and-operations.md` | SQS retention/visibility와 S3 lifecycle prefix·age 대조, bounded-read capability와 markerless fail-closed, Floci capability gap, optional AWS encrypted smoke | manual contract test + retention evidence |

acceptance 실행은 `docs/manual/manifest.yaml`의 `releaseRef`와 peeled
`releaseCommit`을 source-link 기준으로 고정한다. 다음 명령과 산출물을 모두
남긴다.

1. `ruby scripts/manual/manual_contract_test.rb` → EN/KO manifest·heading·link
   결과.
2. `ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml
   docs/manual/generated/manifest.json --check` → manifest parity 결과.
3. `TAG=0.5.0; SHA=$(git rev-parse "$TAG^{}"); ruby
   scripts/manual/validate_release_manuals.rb "$TAG" "$SHA"` → releaseRef
   source-link와 peeled commit 결과.
4. `./gradlew :aws-spring-boot-sqs-examples:test --tests
   "*SqsExtendedClientExampleTest" -Dbluetape4k.aws.emulator=floci` → exact
   example configuration 및 unencrypted smoke 결과. Floci capability gap이면
   명시적 LocalStack fallback 증거와 smoke 결과를 별도로 보관한다.
5. `./gradlew :bluetape4k-aws-spring-boot:test --tests
   "*SqsExtendedClientSnippetCompileTest"` → canonical snippet compile 결과.
6. `./gradlew :aws-spring-boot-sqs-examples:test --tests
   "*SqsExtendedClientRetentionEvidenceTest" -Dbluetape4k.aws.emulator=floci`
   → SQS `MessageRetentionPeriod`/`VisibilityTimeout`, S3 lifecycle rule의
   prefix·minimum age, configured policy 값의 대조 JSON. emulator가 lifecycle
   API를 지원하지 않으면 exact capability-gap와 다음 실제 AWS 확인 명령을
   같은 산출물에 기록한다: `aws sqs get-queue-attributes --queue-url "$QUEUE_URL"
   --attribute-names MessageRetentionPeriod VisibilityTimeout` 및
   `aws s3api get-bucket-lifecycle-configuration --bucket "$BUCKET"`.
   명령 결과에는 queue URL/bucket secret redaction, command exit code, retrieval
   timestamp, lifecycle prefix·minimum age와 configured policy comparison을
   포함한다.

각 결과는 `.bluetape/issue-455-docs-acceptance.json`에 command, exit code,
releaseRef/commit, 산출물 경로를 기록하며, EN/KO semantic parity matrix의
모든 행이 PASS가 아니면 문서 gate를 통과시키지 않는다.

실제 AWS KMS encrypted smoke는 IAM/KMS 자격과 비용이 필요한 optional
acceptance다. 이번 Issue #455의 필수 gate는 deterministic fake encryption
contract와 Floci unencrypted smoke이며, 실제 AWS encrypted smoke를 실행하지
않았다는 사실과 capability/권한 사유를 위 JSON에 명시한다. 운영자가 실제 AWS
검증을 선택한 경우에만 별도 `SqsExtendedClientAwsEncryptedSmokeTest` 증거를
추가하며 Floci 결과를 그 증거로 대체하지 않는다.

운영 lifecycle 계약은 다음처럼 고정한다. offload object key와 ack marker key는 policy의
normalized `keyPrefix` 아래에만 생성하고, S3 lifecycle rule은 이 prefix에
`orphanRetentionHours` 이상의 minimum age를 적용한다. application은 SQS
retention과 최대 visibility/retry window가 이 age보다 짧도록 배포하며,
ack marker가 payload보다 먼저 만료되지 않도록 동일 age를 적용한다. rule이
없으면 ambiguous send/cancellation object가 자동 삭제된다고 가정하지
않는다. S3 lifecycle 설정은 startup에서 추측하지 않고 deployment checklist와
runtime manual에서 확인한다.

IAM 문서는 `sqs:SendMessage/DeleteMessage/ReceiveMessage`를 대상 queue ARN,
S3 `PutObject/GetObject/DeleteObject`를 대상 bucket·prefix ARN으로 제한하고,
암호화 사용 시 `kms:GenerateDataKey/Decrypt`를 지정 CMK ARN과 exact
encryption-context 조건으로 제한하는 예시를 제공한다. `keyFingerprint`는
그 CMK ARN과 canonical encryption context에서 계산한 stable non-secret digest이며
pointer signature와 IAM checklist가 동일 값을 사용한다. wildcard resource와
foreign bucket/key/CMK는 거부한다.

## Stacked PR train 경계

Epic #499의 다음 train은 Issue #455 하나를 다음 세 개의 의존 PR로 나눈다.

| 순서 | 내용 | 선행 조건 | 독립 검증 |
|---|---|---|---|
| SQS-5a | pointer 모델/codec, properties, redacted exceptions, unit RED→GREEN | 없음 | pointer/properties/redaction tests, compileKotlin |
| SQS-5b | coroutine adapter의 inline/offload send·receive·flow | SQS-5a | adapter unit tests, module targeted tests |
| SQS-5c | ack cleanup, optional encryption wiring, auto-config, Floci smoke, README/manual/example/KDoc | SQS-5b | ack/auto-config/emulator/docs checks |

각 PR은 이전 PR의 head를 base로 삼고, merge 후 다음 PR을 rebase하지 않고
stacked base를 갱신한다. 최종 PR만 `#455`를 `Closes`로 연결하며, 세 PR의
CI가 모두 성공하고 최종 exact head를 재확인한 뒤 별도 merge 승인을 받는다.
1인 개발자 저장소이므로 human review gate는 N/A지만 설계 승인, 계획 승인,
CI, exact-head merge 승인은 각각 유지한다.

## 수용 기준과 후속 경계

### 완료 조건

- 기본 설정에서 기존 SQS/S3 동작과 dependency graph가 변하지 않는다.
- 작은 메시지와 `offloadThresholdBytes` 이하 payload는 SQS body가 원문이다.
- threshold 초과 payload는 필수 `idempotencyKey`로 deterministic key를 사용해
  S3 upload 후 authenticated pointer를 전송하고, receive가 strict UTF-8
  body/content type/attributes를 복원한다.
- policy 없는 queue는 pointer prefix를 opaque inline로 유지하고 S3를 호출하지
  않는다. policy queue는 bucket/keyPrefix/encryption/signature를 검증한 뒤에만
  S3를 호출한다.
- `maxOffloadPayloadBytes` 초과와 `maxMessages != 1`은 외부 호출 전에 거부한다.
- SQS ack 성공 전에는 S3 object를 삭제하지 않는다.
- ack 후 cleanup 실패는 retryable `cleanupRequired`와 opaque cleanup handle로
  남고 pointer 원문을 진단에 노출하지 않는다. `cleanup`은 ACK handle 없이
  실행할 수 없다.
- pointer format/version/HMAC 검증, duplicate delivery, cancellation, partial
  failure, orphan lifecycle 정책이 unit test와 문서에 모두 있다.
- low-cardinality offload/orphan/read/cleanup metrics와 IAM resource/KMS
  context, shutdown drain, mixed-consumer rollback 절차가 canonical manual과
  runnable example에 있다.
- Spring `SmartLifecycle` bridge가 producer gate off → drain → consumer gate off
  → AWS client destroy 순서를 지키고, drain timeout에서는 close를 진행하지
  않는다는 lifecycle test와 운영 절차가 있다.
- Floci 우선 smoke와 module/detekt/targeted validation이 fresh evidence로
  기록된다.

### 후속 이슈

- [#514](https://github.com/bluetape4k/bluetape4k-aws/issues/514): Spring
  Cloud AWS식 public `BatchExecutionStrategy`·converter SPI, 일반 retry와
  backend capability 비교. 이번 구현에서는 공개 추상화를 추가하지 않는다.
- [#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515): 외부
  publisher latency/cleanup telemetry 및 실제 heap·throughput 측정. 이번
  구현은 직접 보관하는 pointer/root reference와 호출 순서만 검증한다.
  후속 issue의 acceptance는 payload size(256 KiB 경계/1 MiB/64 MiB),
  concurrency(1/4/16), inline/offloaded/encrypted 비율, emulator/AWS 환경과
  SDK/JVM/GC 버전을 matrix로 고정하고, publisher p50/p95/p99 latency,
  cleanup age/error rate, allocation/retained root reference, heap/GC,
  throughput를 동일 workload에서 기록해야 한다. 재현 가능한 Gradle
  command와 raw result artifact, baseline 대비 threshold·warm-up·반복 횟수를
  issue에 먼저 승인한 뒤에만 성능 수치를 제품 계약으로 승격한다. #455의
  bounded preflight/streaming 구조 테스트를 이 실측으로 대체하지 않는다.

## 설계 게이트 기록

- **SPW-01 PASS** — 독자는 `aws-spring-boot` 구현자·리뷰어이며, Issue #455,
  현재 develop 소스, AWS 공식 quota/Extended Client 문서를 source ledger로
  고정했다. 256 KiB와 1 MiB의 의미 차이, AWS library sync-only와 pointer
  상호운용 제외를 명시했다.
- **SPW-02 PASS** — 문제, 책임 경계, API, 설정, pointer contract, 실패·보안,
  lifecycle, 동시성, 테스트, 문서, stacked train, 후속 이슈를 포함했다.
- **SPW-03 PASS** — Korean technical register를 적용하고 API·property key,
  command, URL, 숫자, issue ID를 그대로 보존했다. `through`식 번역투,
  홍보성 표현, 근거 없는 성능 주장을 사용하지 않았다.
- **SPW-04 PASS** — 로컬 `SqsOperations`, `S3Operations`, 기존 S3 client-side
  encryption, auto-config, compileOnly 경계와 공식 AWS 문서를 대조했다.
  불확실한 Floci/KMS capability는 unit fallback과 문서 gap으로 남겼다.
- **SPW-05 PASS** — 파일을 Markdown context로 재독해해 heading/table/code
  fence, link, stacked train 순서와 acceptance traceability를 확인했다.
  구현 전 review lane의 fresh 판정을 기다린다.

## 자연스러운 한국어 점검

- **KO-01 PASS** — source-backed facts, identifiers, URLs, numbers, uncertainty를
  보존했다.
- **KO-02 PASS** — `중요하다`, `강력하다`, `효율적이다` 같은 hollow claim 대신
  호출 순서·실패 상태·검증 결과를 적었다.
- **KO-03 PASS** — English sentence skeleton과 기계적인 `첫째/둘째/셋째`
  나열을 피하고 기술 흐름에 맞춰 문장을 구성했다.
- **KO-04 PASS** — `pointer`, `payload`, `ack`, `cleanup`, `offload`를 같은
  의미에 일관되게 사용하고 API token은 번역하지 않았다.
- **KO-05 PASS** — 비유·홍보 문구·과장된 성능 표현을 사용하지 않았다.
- **KO-06 PASS** — 제목, 표, 코드 블록, 링크와 후속 이슈를 재검토했다.
- **KO-07 PASS** — terminology audit 대상 후보를 확인했고, 코드 token인
  property/API/URL과 설명 문장의 한국어 용어를 혼동하지 않았다.

구현 단계에서 spec의 기술 의미가 바뀌면 이 artifact의 SPW/KO gate를 새로
실행하고, 설계 review와 사용자 승인을 다시 받는다.
