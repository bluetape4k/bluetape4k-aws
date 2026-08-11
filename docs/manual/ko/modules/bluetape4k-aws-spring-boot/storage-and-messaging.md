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

## SNS와 SES

SNS publish와 HTTP parsing은 서로 다른 작업입니다. callback을 처리하기 전에 SNS 서명을 검증해야 합니다. SES sender는 coroutine과 JavaMail 방식 adapter를 제공하지만 멱등하지 않은 전송을 무작정 재시도하면 안 됩니다.

## 실패 경로를 테스트한다

직렬화, queue 조회, redelivery, 중복 전달, DLQ, S3 pagination, multipart 취소, DynamoDB batch 일부 성공을 검증하세요. 성공적인 send만 확인하는 테스트로는 부족합니다.

## 근거 자료

- [S3 operations](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3Operations.kt)
- [SQS listener annotation](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt)
- [DynamoDB repository](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/dynamodb/AbstractCoroutinesDynamoDbRepository.kt)
