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

## SNS와 SES

SNS publish와 HTTP parsing은 서로 다른 작업입니다. callback을 처리하기 전에 SNS 서명을 검증해야 합니다. SES sender는 coroutine과 JavaMail 방식 adapter를 제공하지만 멱등하지 않은 전송을 무작정 재시도하면 안 됩니다.

## 실패 경로를 테스트한다

직렬화, queue 조회, redelivery, 중복 전달, DLQ, S3 pagination, multipart 취소, DynamoDB batch 일부 성공을 검증하세요. 성공적인 send만 확인하는 테스트로는 부족합니다.

## 근거 자료

- [S3 operations](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3Operations.kt)
- [SQS listener annotation](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt)
- [DynamoDB repository](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/dynamodb/AbstractCoroutinesDynamoDbRepository.kt)

