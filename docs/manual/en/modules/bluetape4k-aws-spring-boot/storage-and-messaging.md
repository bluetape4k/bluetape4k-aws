---
title: Storage and messaging
description: Operate S3, DynamoDB, SQS, SNS, and SES with explicit delivery semantics.
manualId: bluetape4k-aws-spring-boot
chapterId: storage-and-messaging
---

# Storage and messaging

Spring-facing operations wrap AWS async clients with suspend APIs and framework lifecycle. They do not remove the service's delivery and consistency rules.

## S3 paths

Use `S3Operations` for common object work and presigned URLs. Use `S3TransferOperations` for large or multipart transfers; it activates only when Transfer Manager is present. Treat copy-then-delete moves and presigned URL expiry as explicit application decisions.

## DynamoDB repositories

`AbstractCoroutinesDynamoDbRepository` provides typed enhanced-client access. Resolve table names through `DynamoDbTableNameResolver` so environment naming stays outside entity code. Batch and query operations still need pagination, unprocessed-item, index, and capacity handling.

## SQS listeners

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

Successful completion acknowledges according to the configured policy. On failure, visibility and redelivery rules decide the next attempt. Set processing timeout below visibility or enable an extension/heartbeat strategy.

### Batch listeners and partial acknowledgement

Batch delivery is explicit:

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

The payload list may be `List<SqsReceivedMessage>`, `List<software.amazon.awssdk.services.sqs.model.Message>`,
or one concrete non-null `List<T>`. Raw, nullable, wildcard, nested, and broad element types are
rejected during context initialization. SQS accepts at most ten messages per receive or batch
delete; `SqsBatchAcknowledgementResult` reports `operation`, `status`, successful message IDs, and
item failures. `nack` defaults to visibility timeout `0`; `changeVisibility` accepts `0..43_200`.
`ON_SUCCESS` deletes pending items after a normal return, while `MANUAL` deletes or changes
visibility only when the handler calls the acknowledgement API. FIFO groups keep a contiguous
successful prefix, and only unconfirmed items remain eligible for retry/redelivery. Delivery is
at-least-once, so side effects need idempotency or message-id deduplication. Receipt handles,
bodies, and raw message IDs are not included in `toString()`, logs, metric tags, or
`SqsListenerBatchCorrelation`.

`SqsBatchDeleteProtocolException`, `SqsBatchVisibilityProtocolException`, and
`SqsMessageConversionException` indicate an untrusted or incomplete response; callers should keep
the affected items pending and apply the retry/DLQ policy. The optimized AWS SDK path uses one
batch request; old `SqsOperations` implementations use a sequential fallback.

For canary rollback, stop receiving, drain in-flight work, and wait for
`STOPPING_RECEIVE -> DRAINING -> STOPPED` before deploying the last known-good single-message
handler. Re-drive the DLQ at a bounded rate only after the control-plane response reports
`drained=true` and `inFlight=0`, then verify idempotency. Stop the canary when partial failures
exceed `1%/5m`, retry exhaustion exceeds `0.1%/5m`, redelivery age p95 exceeds `80%` of visibility,
or DLQ visible count is non-zero. The on-call owner is `bluetape4k-sqs-oncall` and release approval
belongs to `bluetape4k-release-approvers`.

## SNS and SES

SNS publish helpers and HTTP parsing are separate concerns. Verify SNS signatures before processing callbacks. SES senders expose coroutine and JavaMail-style adapters; do not retry non-idempotent sends blindly.

## Test what can fail

Test serialization, queue lookup, redelivery, duplicate delivery, DLQ behavior, S3 pagination, multipart cancellation, and DynamoDB partial batch success. A successful send-only test is insufficient.

## Sources

- [S3 operations](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3Operations.kt)
- [SQS listener annotation](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt)
- [DynamoDB repository](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/dynamodb/AbstractCoroutinesDynamoDbRepository.kt)
