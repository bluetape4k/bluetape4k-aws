# aws-ktor-sqs-examples

[English](./README.md) | 한국어

`aws-ktor` SQS consumer 플러그인을 사용하는 Ktor 3 예제입니다.
`SqsConsumer`를 설치하고, `SqsAsyncClient`로 메시지를 전송하며, 수신한 메시지를
메모리에 기록하고 queue 관리 route를 제공합니다.

## 아키텍처

![aws ktor sqs examples Architecture diagram](../../docs/images/readme-diagrams/examples-aws-ktor-sqs-examples-architecture-01.png)

## Consumer 설정

`sqsExampleModule`은 `SqsAsyncClient`와 queue URL을 받아 Jackson content
negotiation을 설치하고 consumer를 설정합니다.

```kotlin
install(SqsConsumer) {
    sqsAsyncClient = sqsClient
    this.queueUrl = queueUrl
    coroutines = 2
    maxMessages = 10
    waitTimeSeconds = 1
    visibilityTimeoutSeconds = 30
}
```

`onMessage<String>`은 수신한 message body를 in-memory list에 추가하므로 테스트와
샘플 client가 listener 출력을 확인할 수 있습니다.

## 서버 Route

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/sqs/messages` | plain text body를 설정된 queue로 전송합니다. |
| `GET` | `/sqs/messages/received` | `SqsConsumer`가 수신한 message body를 반환합니다. |
| `POST` | `/sqs/queues/{name}` | SQS queue를 생성하고 URL을 반환합니다. |
| `DELETE` | `/sqs/queues?url={queueUrl}` | query queue URL을 삭제합니다. 생략하면 설정된 queue를 삭제합니다. |
| `GET` | `/sqs/queues/attributes?url={queueUrl}` | `approximateMessageCount`를 반환합니다. |

## 설정

테스트는 `SqsClientFactory.Async`로 LocalStack 기반 `SqsAsyncClient`를 만들고,
생성한 queue URL을 Ktor 애플리케이션에 전달합니다.

```kotlin
application { sqsExampleModule(sqsClient, queueUrl) }
```

## 테스트

```bash
./gradlew :aws-ktor-sqs-examples:test
```
