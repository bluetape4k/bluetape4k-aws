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

## SQS Extended Client

The Extended Client is opt-in. It keeps small messages inline and stores larger
payloads in S3 behind an authenticated pointer. The producer and consumer gates
are independent, but enable the consumer and drain it before enabling producer
offload during a rollout.

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

Use `SqsExtendedClientOperations` with an idempotency key for payloads above
the threshold. A received extended message must be acknowledged through the
same identity-bound `SqsExtendedReceivedMessage` instance. `delete-on-ack`
creates and verifies a marker before deleting the S3 payload; a failed delete
returns an opaque retry handle. The default keeps payloads for lifecycle
cleanup, so the S3 marker and payload must share a prefix and retention age.

The supported Jackson 3 module serializes only safe DTO fields. It does not
serialize raw AWS requests/responses, pointer bucket/key/signature, receipt
handles, encryption context, or cleanup handles. An ordinary `@SqsListener`
legacy consumer and the AWS Java Extended Client do not restore this pointer
format; do not attach either to an extended pointer queue.

Optional client-side encryption reuses the existing bounded S3 encryption
capability and requires an exact key identity/context match. Its wire format is
local to this module and is not interoperable with the AWS Java Extended Client.

For rollback, disable the producer, stop the legacy consumer, drain the
extended adapter, and wait for two empty visibility-window probes. Verify
`ApproximateReceiveCount`, `RedrivePolicy`, DLQ/quarantine counts, and the
global rollback deadline before rehydrating pointers into an inline legacy-safe
queue. A deadline or redrive-budget failure remains `ROLLBACK_BLOCKED` and does
not start the legacy consumer.

The Floci-first local check is:

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests '*SqsExtendedClientAwsEmulatorTest' \
  -Dbluetape4k.aws.emulator=floci --no-daemon
```

Use LocalStack only as an explicit fallback. The low-cardinality counters are
`bluetape4k.aws.sqs.extended.offload.total`, `...orphan.total`,
`...payload-read.failure`, and `...cleanup.failure`; queue URLs, bucket/key,
payload, and diagnostic codes are never tags. External publisher latency and
cleanup telemetry, plus heap/throughput measurements, are tracked separately
in follow-up issue #515 and are not completion evidence for this feature.

## SNS and SES

SNS publish helpers and HTTP parsing are separate concerns. Verify SNS signatures before processing callbacks. SES senders expose coroutine and JavaMail-style adapters; do not retry non-idempotent sends blindly.

### SNS topic ARN resolver and cache (Unreleased/develop)

`SnsOperations.findTopicArn` accepts a topic name or an explicit SNS ARN. Name
lookups use `SnsTopicArnResolver` with paginated `ListTopics`, a bounded
scope-aware TTL/LRU cache, and a per-topic single-flight. The default cache is
enabled with 256 entries and a five-minute TTL; `topic-arn-cache.enabled=false`
disables persistent entries but not duplicate lookup suppression. A successful
topic create invalidates the name entry so eventual consistency remains visible
as a documented null or SDK failure instead of a stale negative value.

Configure `account-id` to enforce same-account checks. Explicit ARN input fails
closed when the account is unknown unless
`allow-cross-account-topic-arn=true` is an intentional opt-in. `ListTopics`
results are still checked for SNS ARN shape and configured region/account; an
effective region is required for explicit ARN validation. A custom
`SnsTopicArnCache` or `SnsTopicArnResolver` bean takes precedence as a scoped
configuration override, but it is not a behavior-preserving rollback by itself.
For rollback, provide a custom `SnsOperations` implementation or redeploy the
last-known-good artifact; `bluetape4k.aws.sns.enabled=false` disables the
complete SNS auto-configuration. Terminal lookup failures emit only hashed
scope/topic dimensions and exception type; raw ARN, topic name, endpoint
credentials, and AWS error messages are not logged.

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

### SNS batch conversion (Unreleased/develop)

`SnsBatchMessageConverter` is an opt-in, no-network conversion boundary from
Spring `Message<*>` values to a typed `SnsPublishBatchRequest`. Its no-argument
constructor accepts only `String` payloads; the second constructor accepts an
explicit suspend `SnsPayloadSerializer` for structured payloads. The converter
uses only the allowlisted `SnsBatchMessageHeaders` constants
`MESSAGE_ID`, `SUBJECT`, `MESSAGE_ATTRIBUTES`, `MESSAGE_GROUP_ID`, and
`MESSAGE_DEDUPLICATION_ID`. Explicit IDs must be `UUID`; otherwise the
`MessageHeaders.ID` UUID is used. All entries are converted before the request
is built, input order is preserved, and a conversion error never invokes an
SNS client. Errors are cause-free and redact payloads, headers, ARNs, and
serializer exceptions; cancellation rethrows the original
`CancellationException` instance.

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

The converter requires applications to add
`org.springframework:spring-messaging` at runtime because this module keeps it
`compileOnly`. The guarded strategy port does not expose the AWS client or its
lifecycle and does not automatically retry an uncertain partial publish.
262,144-byte SNS byte-size preflight, a Jackson 3 adapter, and `ByteArray`
payload support are follow-up scope rather than current behavior.

## Spring Modulith SNS/SQS externalization (Unreleased/develop)

The optional adapter externalizes registered Spring Modulith events to SNS or
SQS and restores SQS messages as local application events. Import the root BOM
once and keep all coordinates versionless:

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    implementation("org.springframework.modulith:spring-modulith-events-jackson")

    runtimeOnly("software.amazon.awssdk:sns")                 // SNS producer
    runtimeOnly("software.amazon.awssdk:sqs")                 // SQS producer/consumer
    runtimeOnly("software.amazon.awssdk:sns-message-manager") // verified SNS consumer
}
```

The application owns the Spring Modulith publication repository choice. This
module keeps the Modulith and service SDK dependencies optional. Register every
external event with a stable type, version, final concrete JVM class, and event ID:

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

Spring Modulith routing must return a logical alias such as `order-events`, not
an ARN or URL. The alias maps to one service destination:

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

Producer-only applications leave `consumer.enabled=false`. A direct SQS
consumer receives the adapter envelope and requires a queue redrive policy by
default:

```yaml
bluetape4k.aws.modulith.events:
  enabled: true
  consumer:
    enabled: true
    queue: direct-order-events
    source-mode: direct
    redrive-required: true
```

An SNS fanout consumer receives an SNS notification through SQS. It additionally
requires `sns-message-manager`, a verifier bean, and an exact TopicArn allowlist:

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

One built-in listener handles one queue and one source mode per application
context. Deploy separate contexts when DIRECT and SNS sources must be consumed
together. For FIFO publication, configure an SQS destination ending in `.fifo`
and make Spring Modulith provide `RoutingTarget.key`; the adapter uses that key
as `messageGroupId` and the stable registered event ID as deduplication ID.
Standard destinations reject a routing key, while FIFO destinations require it.

The built-in in-memory idempotency store is application-scoped and loses its
claims on restart. For durable multi-instance processing, implement
`AwsModulithEventIdempotencyStore` and expose exactly one bean; auto-configuration
backs off when that bean exists. A successful handler or an already-completed
duplicate is acknowledged. An active claim, handler failure, claim renewal or
completion failure, and source verification failure are not acknowledged, so
SQS visibility, redelivery, and the queue redrive policy govern retry and DLQ
delivery. An uncertain claim mutation is left for lease-expiry takeover instead
of being released eagerly, which preserves fencing against duplicate dispatch.

Run the local transport contract with Floci:

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests 'io.bluetape4k.aws.spring.modulith.*' \
  -Dbluetape4k.aws.emulator=floci --no-daemon
```

This proves the local SQS path, SNS-to-SQS fanout transport, redrive preflight,
acknowledgement, and claim/fencing behavior supported by `FlociServer`. It does
not prove production SNS certificate/signature telemetry, IAM, cross-account
policies, or real AWS timing. Keep LocalStack as an explicit fallback for a
Floci API gap; no real AWS account is required for this local contract.

| Documented contract | Source-backed symbol |
| --- | --- |
| Stable event type, version, final concrete class, ID, allowed headers | `AwsModulithEventTypeRegistration`, `AwsModulithEventTypeRegistry` |
| Logical SNS/SQS target | `AwsModulithEventsProperties.Target`, `AwsModulithTargetService` |
| DIRECT or verified SNS source | `AwsModulithSourceMode`, `AwsModulithSqsEventConsumer` |
| Lease/fencing duplicate suppression | `AwsModulithEventIdempotencyStore` |
| Normal processing or completed duplicate | `AwsModulithConsumeOutcome` |

## Test what can fail

Test serialization, queue lookup, redelivery, duplicate delivery, DLQ behavior, S3 pagination, multipart cancellation, and DynamoDB partial batch success. A successful send-only test is insufficient.

## Sources

- [S3 operations](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3Operations.kt)
- [SQS listener annotation](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt)
- [DynamoDB repository](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/dynamodb/AbstractCoroutinesDynamoDbRepository.kt)
- [Modulith event registry](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithEventTypes.kt)
- [Modulith properties](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithEventsProperties.kt)
