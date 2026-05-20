# aws-ktor-sqs-examples

English | [한국어](./README.ko.md)

Ktor 3 examples for the `aws-ktor` SQS consumer plugin. The module installs
`SqsConsumer`, sends messages through `SqsAsyncClient`, records consumed
messages in memory, and exposes queue management routes.

## Architecture

![aws ktor sqs examples Architecture diagram](../../docs/images/readme-diagrams/examples-aws-ktor-sqs-examples-architecture-01.png)

## Consumer Setup

`sqsExampleModule` receives an `SqsAsyncClient` and a queue URL, installs
Jackson content negotiation, and configures the consumer:

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

`onMessage<String>` appends consumed message bodies to an in-memory list so tests
and sample clients can inspect listener output.

## Server Routes

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/sqs/messages` | Send a plain text body to the configured queue. |
| `GET` | `/sqs/messages/received` | Return message bodies consumed by `SqsConsumer`. |
| `POST` | `/sqs/queues/{name}` | Create an SQS queue and return its URL. |
| `DELETE` | `/sqs/queues?url={queueUrl}` | Delete the query queue URL, or the configured queue when omitted. |
| `GET` | `/sqs/queues/attributes?url={queueUrl}` | Return `approximateMessageCount`. |

## Configuration

Tests create a LocalStack-backed `SqsAsyncClient` with `SqsClientFactory.Async`
and pass the generated queue URL into the Ktor application:

```kotlin
application { sqsExampleModule(sqsClient, queueUrl) }
```

## Test

```bash
./gradlew :aws-ktor-sqs-examples:test
```
