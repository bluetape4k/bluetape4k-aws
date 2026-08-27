---
title: Storage와 messaging
description: S3, DynamoDB, SQS, SNS, SES를 명시적인 전달 의미와 함께 사용합니다.
manualId: bluetape4k-aws-spring-boot
chapterId: storage-and-messaging
---

# Storage와 messaging

Spring용 operation은 AWS async client를 suspend API와 프레임워크 수명 주기로 감쌉니다. 그렇다고 각 서비스의 전달 보장과 일관성 규칙까지 사라지지는 않습니다.

## S3 경로

일반 객체 작업과 presigned URL에는 `S3Operations`를 사용합니다. 대용량·multipart 전송은 Transfer Manager가 있을 때만 활성화되는 `S3TransferOperations`가 맡습니다. copy 후 delete 방식의 이동과 presigned URL 만료는 애플리케이션이 명시적으로 결정해야 합니다.

## DynamoDB repository

`AbstractCoroutinesDynamoDbRepository`가 typed enhanced-client 접근을 제공합니다. 환경별 테이블 이름은 `DynamoDbTableNameResolver`로 분리하세요. batch와 query에는 여전히 pagination, unprocessed item, index, capacity 처리가 필요합니다.

## SQS listener

```kotlin
@SqsListener(
    queue = "${orders.queue-url}",
    maxMessages = 10,
    waitTimeSeconds = 20,
    visibilityTimeoutSeconds = 60,
)
suspend fun receive(order: OrderMessage) {
    orderService.process(order)
}
```

처리가 성공하면 설정된 정책에 따라 acknowledge합니다. 실패하면 visibility와 redelivery 규칙이 다음 시도를 결정합니다. 처리 timeout을 visibility보다 짧게 두거나 연장·heartbeat 전략을 사용하세요.

### Batch listener와 partial acknowledgement

배치 전달은 명시적으로 활성화합니다.

```kotlin
@SqsListener(queue = "orders", batch = true, acknowledgementMode = SqsAcknowledgementMode.MANUAL)
suspend fun receive(
    messages: List<SqsReceivedMessage>,
    acknowledgement: SqsBatchAcknowledgement,
) {
    val accepted = messages.filter(::isAccepted)
    if (accepted.isNotEmpty()) {
        acknowledgement.acknowledge(accepted)
    }
    val rejected = messages - accepted.toSet()
    if (rejected.isNotEmpty()) {
        acknowledgement.nack(rejected, timeoutSeconds = 0)
    }
}
```

payload 목록은 `List<SqsReceivedMessage>`, `List<software.amazon.awssdk.services.sqs.model.Message>`,
또는 하나의 concrete non-null `List<T>`일 수 있습니다. raw·nullable·wildcard·nested·broad
element type은 context 초기화에서 거부합니다. SQS는 receive와 batch delete마다 최대 10개를
허용하며 `SqsBatchAcknowledgementResult`가 `operation`, `status`, 성공 message ID, 항목별 실패를
반환합니다. `nack` 기본 visibility timeout은 `0`이고 `changeVisibility`는 `0..43_200`을
허용합니다. `ON_SUCCESS`는 정상 반환 뒤 pending 항목을 삭제하고, `MANUAL`은 handler가
acknowledgement API를 호출한 경우에만 삭제하거나 visibility를 변경합니다. FIFO group은 연속
성공 prefix를 유지하며 확인되지 않은 항목만 retry/redelivery 대상입니다. 전달은
at-least-once이므로 side effect에는 멱등성 또는 message-id deduplication이 필요합니다.
receipt handle, body, raw message ID는 `toString()`, 로그, metric tag,
`SqsListenerBatchCorrelation`에 기록하지 않습니다.

`SqsBatchDeleteProtocolException`, `SqsBatchVisibilityProtocolException`,
`SqsMessageConversionException`은 신뢰할 수 없거나 불완전한 응답을 뜻합니다. 해당 항목을
pending으로 유지하고 retry/DLQ 정책을 적용하세요. 최적화된 AWS SDK 경로는 batch 요청 1회를
사용하고, 기존 `SqsOperations` 구현은 순차 fallback을 사용합니다.

canary rollback은 receive 중지, in-flight drain, `STOPPING_RECEIVE -> DRAINING -> STOPPED` 확인,
마지막 정상 단건 handler 배포 순서로 수행합니다. control-plane 응답에서 `drained=true`,
`inFlight=0`을 확인한 뒤에만 DLQ를 제한된 속도로 redrive하고 idempotency를 검증하세요. partial
failure가 `1%/5m`, retry exhaustion이 `0.1%/5m`, redelivery age p95가 visibility의 `80%`,
또는 DLQ visible count가 `0/5m` 기준을 넘으면 canary를 중단합니다. 온콜 owner는
`bluetape4k-sqs-oncall`, release approval은 `bluetape4k-release-approvers`입니다.

## SQS Extended Client

Extended Client는 opt-in 기능입니다. 작은 메시지는 SQS 본문에 그대로 두고,
threshold를 넘는 payload만 인증된 pointer 뒤의 S3 객체로 offload합니다.
producer와 consumer gate는 분리되어 있지만, 배포 순서는 consumer 활성화와
drain을 먼저 수행한 뒤 producer offload를 켜는 방식으로 고정하세요.

```yaml
bluetape4k:
  aws:
    sqs:
      extended:
        enabled: true
        producer-enabled: true
        consumer-enabled: true
        default-queue-urls:
          - https://sqs.ap-northeast-2.amazonaws.com/123456789012/orders
        default-policy:
          bucket: orders-extended-payloads
          key-prefix: bluetape4k/sqs/orders
          offload-threshold-bytes: 262144
          max-inline-bytes: 1048576
          max-offload-payload-bytes: 67108864
          orphan-retention-hours: 168
          delete-on-ack: false
          pointer-signing-key-ref: default
```

threshold를 넘는 payload는 `SqsExtendedClientOperations`에 idempotency key와
함께 전달하세요. 수신한 extended message는 같은 identity-bound
`SqsExtendedReceivedMessage` instance로만 acknowledge해야 합니다.
`delete-on-ack`은 marker를 조건부로 생성·검증한 뒤 S3 payload를 삭제하며,
삭제 실패 시 불투명한 retry handle을 반환합니다. 기본값은 lifecycle cleanup을
위해 payload를 보존하므로 marker와 payload가 같은 prefix와 retention age를
사용해야 합니다.

지원되는 Jackson 3 module은 safe DTO 필드만 직렬화합니다. raw AWS
request/response, pointer bucket/key/signature, receipt handle, encryption
context, cleanup handle은 직렬화하지 않습니다. 일반 `@SqsListener` legacy
consumer와 AWS Java Extended Client는 이 pointer 형식을 복원하지 않으므로
extended pointer queue에 연결하지 마세요.

선택적 client-side encryption은 기존 bounded S3 encryption capability를
재사용하며 key identity와 context가 정확히 일치해야 합니다. 이 wire format은
이 모듈 전용이며 AWS Java Extended Client와 상호운용되지 않습니다.

rollback은 producer 비활성화, legacy consumer 중지, extended adapter drain,
두 번의 visibility-window empty probe 순서로 수행합니다. `ApproximateReceiveCount`,
`RedrivePolicy`, DLQ/quarantine count, 전체 rollback deadline을 확인한 뒤에만
pointer를 inline legacy-safe queue로 rehydrate합니다. deadline 또는 redrive
budget 실패는 `ROLLBACK_BLOCKED`로 고정하고 legacy consumer를 시작하지 않습니다.

Floci 우선 로컬 검증 명령은 다음과 같습니다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests '*SqsExtendedClientAwsEmulatorTest' \
  -Dbluetape4k.aws.emulator=floci --no-daemon
```

LocalStack은 명시적인 fallback으로만 사용합니다. 저카디널리티 counter는
`bluetape4k.aws.sqs.extended.offload.total`, `...orphan.total`,
`...payload-read.failure`, `...cleanup.failure` 네 개이며 queue URL,
bucket/key, payload, diagnostic code는 tag에 넣지 않습니다. 외부 publisher
latency·cleanup telemetry와 heap/throughput 측정은 후속 이슈 #515에서 추적하며
이번 기능의 완료 근거로 주장하지 않습니다.

## SNS와 SES

SNS publish와 HTTP parsing은 서로 다른 작업입니다. callback을 처리하기 전에 SNS 서명을 검증해야 합니다. SES sender는 coroutine과 JavaMail 방식 adapter를 제공하지만 멱등하지 않은 전송을 무작정 재시도하면 안 됩니다.

### SNS topic ARN resolver와 cache (Unreleased/develop)

`SnsOperations.findTopicArn`은 topic name 또는 명시적 SNS ARN을 받습니다. name
조회는 pagination을 사용하는 `SnsTopicArnResolver`, scope를 포함한 bounded
TTL/LRU cache, topic별 single-flight를 통과합니다. 기본 cache는 활성화되어
256 entry와 5분 TTL을 사용하며 `topic-arn-cache.enabled=false`로 영속 entry만
끄고 중복 조회 억제는 유지할 수 있습니다. topic 생성이 성공하면 name entry를
무효화하므로 eventual consistency에 따른 null 또는 SDK 오류가 stale negative
값으로 숨겨지지 않습니다.

같은 계정 검증을 적용하려면 `account-id`를 설정하세요. account를 모르는
explicit ARN은 `allow-cross-account-topic-arn=true`를 의도적으로 opt-in하지
않는 한 fail-closed 됩니다. explicit ARN 검증에는 wildcard와 미확인 region을
막기 위한 유효한 `region`도 필요합니다. `ListTopics` 결과도 SNS ARN 형식과
설정된 region/account를 검증합니다. 사용자 정의 `SnsTopicArnCache` 또는
`SnsTopicArnResolver` bean은 범위를 좁힌 구성 override로 우선하지만, 그 자체로
동작을 보존하는 rollback을 제공하지는 않습니다. rollback에는 custom
`SnsOperations` 구현을 제공하거나 last-known-good artifact를 재배포하세요.
전체 SNS 자동 설정을 끄려면 `bluetape4k.aws.sns.enabled=false`를 사용합니다.
terminal 조회 실패는 hash 처리한 scope/topic 차원과 exception type만 기록하고
raw ARN, topic name, endpoint credential, AWS error message는 로그에 남기지
않습니다.

```yaml
bluetape4k:
  aws:
    sns:
      enabled: true
      region: ap-northeast-2
      account-id: 123456789012
      allow-cross-account-topic-arn: false
      topic-arn-cache:
        enabled: true
        max-size: 256
        ttl: 5m
```

### SNS batch 변환 (Unreleased/develop)

`SnsBatchMessageConverter`는 Spring `Message<*>`를 typed
`SnsPublishBatchRequest`로 바꾸는 opt-in·무네트워크 변환 경계입니다. 인자가
없는 생성자는 `String` payload만 허용하고, 두 번째 생성자는 구조화
payload를 위한 명시적 suspend `SnsPayloadSerializer`를 받습니다. converter는
허용 목록인 `SnsBatchMessageHeaders`의 `MESSAGE_ID`, `SUBJECT`,
`MESSAGE_ATTRIBUTES`, `MESSAGE_GROUP_ID`, `MESSAGE_DEDUPLICATION_ID`만
읽습니다. 명시적 ID는 `UUID`여야 하며, 없으면 `MessageHeaders.ID`의 UUID를
사용합니다. 모든 entry를 변환한 뒤 request를 만들고 입력 순서를 유지하며,
변환 오류가 SNS client를 호출하지 않도록 합니다. 오류는 cause-free로
payload, header, ARN, serializer exception을 숨기고 취소 시 원래
`CancellationException` instance를 다시 던집니다.

```kotlin
val converter = SnsBatchMessageConverter(SnsPayloadSerializer { payload ->
    "{\"orderId\":\"${(payload as Order).id}\"}"
})
val request = converter.convertAll(
    topicArn = topicArn,
    messages = orders.map { order ->
        MessageBuilder.withPayload(order)
            .setHeader(SnsBatchMessageHeaders.SUBJECT, "order-created")
            .build()
    },
)
```

이 모듈은 `compileOnly`를 유지하므로 converter를 사용하는 애플리케이션이
런타임에 `org.springframework:spring-messaging`를 직접 추가해야 합니다.
Guarded strategy port는 AWS client와 lifecycle을 노출하지 않고 상태가
불확실한 partial publish를 자동 재시도하지 않습니다. 262,144-byte SNS
byte-size preflight, Jackson 3 adapter, `ByteArray` payload 지원은 현재
동작이 아니라 후속 범위입니다.

## Spring Modulith SNS/SQS 외부화 (미출시/develop)

선택적 adapter는 등록한 Spring Modulith event를 SNS 또는 SQS로 외부화하고,
SQS message를 local application event로 복원합니다. root BOM을 한 번 가져오고
개별 좌표에는 버전을 쓰지 않습니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    implementation("org.springframework.modulith:spring-modulith-events-jackson")

    runtimeOnly("software.amazon.awssdk:sns")                 // SNS producer
    runtimeOnly("software.amazon.awssdk:sqs")                 // SQS producer/consumer
    runtimeOnly("software.amazon.awssdk:sns-message-manager") // 검증된 SNS consumer
}
```

Spring Modulith publication repository 선택은 애플리케이션이 소유합니다. 이 모듈은
Modulith와 서비스 SDK 의존성을 선택 사항으로 유지합니다. 외부 event마다 안정적인 type,
version, final concrete JVM class, event ID를 등록하세요.

```kotlin
data class OrderCreated(val orderId: String, val tenant: String)

@Bean
fun awsModulithEventTypes(): AwsModulithEventTypeRegistry =
    AwsModulithEventTypeRegistry.of(
        AwsModulithEventTypeRegistration(
            type = "order.created",
            version = 1,
            eventClass = OrderCreated::class.java,
            eventId = OrderCreated::orderId,
            allowedHeaderNames = setOf("tenant"),
            headers = { mapOf("tenant" to it.tenant) },
        )
    )
```

Spring Modulith routing은 ARN이나 URL이 아니라 `order-events` 같은 논리 alias를
반환해야 합니다. alias는 하나의 서비스 destination에 대응합니다.

```yaml
bluetape4k:
  aws:
    modulith:
      events:
        enabled: true
        producer:
          enabled: true
        targets:
          order-events:
            service: sns
            destination: order-events
```

producer-only 애플리케이션은 `consumer.enabled=false`를 유지합니다. DIRECT SQS
consumer는 adapter envelope를 받고, 기본적으로 queue redrive policy를 요구합니다.

```yaml
bluetape4k.aws.modulith.events:
  enabled: true
  consumer:
    enabled: true
    queue: direct-order-events
    source-mode: direct
    redrive-required: true
```

SNS fanout consumer는 SQS를 통해 SNS notification을 받습니다. 이 경로에는
`sns-message-manager`, verifier bean, 정확한 TopicArn allowlist가 추가로 필요합니다.

```yaml
bluetape4k.aws.sns:
  region: ap-northeast-2
bluetape4k.aws.modulith.events:
  enabled: true
  consumer:
    enabled: true
    queue: sns-order-events
    source-mode: sns
    expected-topic-arns:
      - arn:aws:sns:ap-northeast-2:123456789012:order-events
    redrive-required: true
```

기본 listener 하나는 application context 하나에서 queue 하나와 source mode 하나만
처리합니다. DIRECT와 SNS source를 함께 소비해야 한다면 context를 분리하세요. FIFO
외부화는 `.fifo`로 끝나는 SQS destination을 설정하고 Spring Modulith가
`RoutingTarget.key`를 제공해야 합니다. adapter는 이 key를 `messageGroupId`로, 등록한
안정적 event ID를 deduplication ID로 사용합니다. standard destination은 routing key를
거부하고 FIFO destination은 routing key를 요구합니다.

기본 in-memory idempotency store는 application scope이며 재시작하면 claim을 잃습니다.
여러 instance에서 durable하게 처리하려면 `AwsModulithEventIdempotencyStore`를 구현해
bean 하나로 노출하세요. 해당 bean이 있으면 auto-configuration은 기본 store를 만들지
않습니다. handler 성공과 이미 완료된 중복은 acknowledge합니다. active claim, handler
실패, claim 갱신·완료 실패, source 검증 실패는 acknowledge하지 않으므로 SQS visibility,
redelivery, queue redrive policy가 retry와 DLQ 전달을 결정합니다. 결과가 불확실한 claim
mutation은 즉시 release하지 않고 lease 만료 후 takeover에 맡겨 중복 dispatch fencing을
유지합니다.

로컬 transport 계약은 Floci로 실행합니다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests 'io.bluetape4k.aws.spring.modulith.*' \
  -Dbluetape4k.aws.emulator=floci --no-daemon
```

이 검증은 `FlociServer`가 지원하는 local SQS 경로, SNS-to-SQS fanout transport,
redrive 사전 검증, acknowledgement, claim/fencing 동작을 증명합니다. production SNS
certificate/signature telemetry, IAM, cross-account policy, 실제 AWS timing은 증명하지
않습니다. Floci API 공백에만 LocalStack을 명시적 fallback으로 사용하며 이 local
계약에는 실제 AWS 계정이 필요하지 않습니다.

| 문서 계약 | 소스 근거 symbol |
| --- | --- |
| 안정적인 event type, version, final concrete class, ID, 허용 header | `AwsModulithEventTypeRegistration`, `AwsModulithEventTypeRegistry` |
| 논리 SNS/SQS target | `AwsModulithEventsProperties.Target`, `AwsModulithTargetService` |
| DIRECT 또는 검증된 SNS source | `AwsModulithSourceMode`, `AwsModulithSqsEventConsumer` |
| lease/fencing 기반 중복 억제 | `AwsModulithEventIdempotencyStore` |
| 정상 처리 또는 완료된 중복 | `AwsModulithConsumeOutcome` |

## 실패 경로를 테스트한다

직렬화, queue 조회, redelivery, 중복 전달, DLQ, S3 pagination, multipart 취소, DynamoDB batch 일부 성공을 검증하세요. 성공적인 send만 확인하는 테스트로는 부족합니다.

## 근거 자료

- [S3 operations](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3Operations.kt)
- [SQS listener annotation](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt)
- [DynamoDB repository](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/dynamodb/AbstractCoroutinesDynamoDbRepository.kt)
- [Modulith event registry](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithEventTypes.kt)
- [Modulith properties](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithEventsProperties.kt)
