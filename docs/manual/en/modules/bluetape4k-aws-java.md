---
manualId: "bluetape4k-aws-java"
id: "bluetape4k-aws-java"
title: "AWS SDK for Java v2 Extensions"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-aws-java"
sourceDir: "aws-java"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-java
---

# AWS SDK for Java v2 Extensions

> Library manual grounded in the 0.5.0 release source.

## Problem {#problem}

Kotlin builders and sync, `CompletableFuture`, and coroutine APIs over AWS SDK for Java v2 clients.

## When to use it {#when-to-use}

Choose it when the application uses Java SDK v2 clients, needs both blocking and asynchronous paths, or integrates with Spring Boot.

## Coordinates {#coordinates}

Applications select one central BOM version.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
}
```

AWS service SDKs follow a `compileOnly` policy; add the services actually used as runtime dependencies.

## Core concepts {#concepts}

Factories build clients; extensions keep the AWS client type visible; async extensions expose futures; coroutine extensions await those futures. The application owns every created client and must close it.

## Quick start {#quick-start}

```kotlin
val s3 = S3AsyncClient.create()
try {
    s3.putAsByteArray(bucket, key, payload)
    val objects = s3.listAllObjects(bucket).toList()
} finally {
    s3.close()
}
```

## API by task {#api-by-task}

S3 object and transfer helpers, DynamoDB enhanced repositories and batch execution, DynamoDB Streams Flow/checkpoint consumption, SQS/SNS messaging, KMS, CloudWatch, Kinesis, Lambda, SES, STS, and request-model builders.

## DynamoDB Streams Flow and checkpoints {#dynamodb-streams}

> Unreleased/develop: this section describes the Issue #469 API and is not part of the `0.5.0` release source.

The Java SDK v2 extension exposes `DynamoDbStreamsAsyncClient.recordFlow` for one
shard and `shardRecordFlow` for a bounded shard graph. The consumer supports
`TrimHorizon`, `Latest`, `AtSequenceNumber`, and `AfterSequenceNumber`, and
returns the SDK `Record` type (or a `DynamoDbStreamsShardRecord` envelope for
multi-shard consumption).

```kotlin
val records = client.recordFlow(
    streamArn = streamArn,
    shardId = shardId,
    position = DynamoDbStreamsStartingPosition.Latest,
    checkpointStore = InMemoryDynamoDbStreamsCheckpointStore(),
).toList()
```

Checkpoints are saved only after downstream emission and are replayed
inclusively, which preserves at-least-once delivery and permits duplicates after
restart. Root shard trees are bounded by `maxShardConcurrency`; each child is
consumed after its parent completes. Use `withDynamoDbStreamsAsyncClient` for a
short-lived client and keep injected clients under caller ownership. Emulator
verification is Floci-first; production retention, throttling, and resharding
timing remain AWS-only gaps.

## Kinesis multi-shard consumer {#kinesis-consumer}

> Unreleased/develop: this section describes the Issue #470 API and is not part of the `0.5.0` release source.

`KinesisAsyncClient.consumerFlow` discovers `ListShards` pages continuously and
polls each shard in order. `maxShardConcurrency` bounds active shard jobs; a
child shard waits for durable `KinesisCheckpoint.ShardEnd` checkpoints from both
parents. The public Flow uses a rendezvous boundary, so its checkpoint is saved
only after the collector's `emit` returns.

```kotlin
client.consumerFlow(
    streamName = "orders",
    consumerGroup = "orders-api",
    streamIdentity = "orders-generation-1",
    position = KinesisStartingPosition.TrimHorizon,
    options = KinesisConsumerOptions(ownerId = "orders-worker-1"),
    checkpointStore = durableCheckpointStore,
    leaseStore = durableLeaseStore,
).collect { envelope -> handle(envelope.record) }
```

`Sequence` resumes inclusively and therefore provides an at-least-once boundary;
exactly-once side effects remain the caller's responsibility. The split
`KinesisCheckpointStore`/`KinesisLeaseStore` SPI is caller-owned. The supplied
`InMemory*` stores are process-local test doubles, while `Noop*` stores make the
same limitation explicit and do not survive restart or coordinate workers.
Lease counters fence stale saves, and metrics callbacks receive finite labels
plus redacted tokens rather than payloads or credentials. The client and probes
remain caller-owned; cancellation is the normal stop/drain signal.

Run the integration contract without AWS credentials by selecting Floci:

```bash
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-java:test --tests '*KinesisConsumerFlociTest'
```

The Floci run proves SDK/emulator compatibility only. Production rollout should
stop, drain, canary, and then scale; rollback reuses the last durable checkpoint
and never deletes or rewinds it.

## Lambda invocation helpers {#lambda}

> Unreleased/develop: this section describes the Issue #314 API and is not part of the `0.5.0` release source.

The Java SDK v2 module adds `io.bluetape4k.aws.lambda` helpers for sync,
`CompletableFuture`, and coroutine invocation. `LambdaInvocationResult<T>` keeps
the raw `InvokeResponse`, a copied payload, status code, optional
`FunctionError`, and a decoded `LogType.Tail` value. A function error is result
data; transport and SDK failures remain exceptions.

### Dependency and client boundary {#lambda-dependency}

The Lambda SDK remains `compileOnly`. Add it to the consumer's compile and
runtime classpaths, and close clients at the application boundary:

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:lambda")
}
```

```kotlin
import io.bluetape4k.aws.lambda.invokeString
import io.bluetape4k.aws.lambda.withLambdaClient
import software.amazon.awssdk.regions.Region

fun invokeOrder(): String = withLambdaClient(region = Region.AP_NORTHEAST_2) { client ->
    val result = client.invokeString("orders-handler", "{\"id\":1}")
    check(!result.hasFunctionError)
    result.value.orEmpty()
}
```

For typed payloads, choose the mapper and codec at the application boundary:

```kotlin
data class OrderRequest(val id: Int)
data class OrderResponse(val accepted: Boolean)
val mapper = tools.jackson.databind.ObjectMapper()

withLambdaClient(region = Region.AP_NORTHEAST_2) { client ->
    val result = client.invokeTyped(
        "orders-handler",
        OrderRequest(1),
        LambdaPayloadCodecs.jackson(mapper, OrderResponse::class.java),
    )
    check(result.value != null)
}
```

Use `invokeBytes`, `invokeString`, or `invokeTyped` with an application-owned
`LambdaPayloadCodec`. Null payload and empty payload remain distinct. The
request builder validates blank function/qualifier values and rejects tail logs
unless the invocation type is `RequestResponse`; ARN, size, IAM, and function
existence remain AWS contracts.

Async cancellation is propagated to the AWS SDK future and coroutine
`CancellationException` is not translated. The helper does not add retries,
deployment, polling, logging, or IAM policy management. An opt-in smoke test
requires a pre-deployed function and explicit function/region inputs; the
repository records Floci Lambda as unsupported and skips before client creation
when those inputs are absent. Do not log or persist raw payloads, decoded tail
logs, or SDK response bodies by default.

## S3 Tables management {#s3-tables}

> Unreleased/develop: this section describes the Issue #311 API and is not part of the `0.5.0` release source.

The Java SDK v2 extension keeps the raw S3 Tables request, response, and
exception types visible for table bucket, namespace, and table
create/list/get/delete operations. A list helper returns one service page;
callers pass `continuationToken` when they need another page. `ListTables`
keeps `namespace` optional for bucket-level listing. `CreateTable` defaults to
`ICEBERG`, and `GetTable` accepts either a table ARN or the bucket/namespace/name
selector.

Add the service SDK to the consumer because it remains `compileOnly`:

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:s3tables")
}
```

```kotlin
withS3TablesClient(region = Region.AP_NORTHEAST_2) { client ->
    val bucketArn = client.createTableBucket("orders-tables").arn()
    client.createNamespace(bucketArn, listOf("analytics"))
    client.createTable(bucketArn, "analytics", "orders")
}
```

`s3TablesClient` and `s3TablesClientOf` create application-scoped clients and
register them with `ShutdownQueue`; callers still own early close and any
injected HTTP client. `withS3TablesClient` creates an unregistered short-lived
client and closes only the service client when the block ends. This module covers the S3 Tables management
surface only; it does not implement an Iceberg data plane, SQL, or Athena,
Glue, Redshift, or Apache Iceberg integration facade. Local emulator fidelity
for S3 Tables is not asserted.

## Step Functions execution helpers {#step-functions}

> Unreleased/develop: this section describes the Issue #313 API and is not part of the `0.5.0` release source.

The Java SDK v2 path adds thin helpers for `StartExecution`, `StopExecution`,
`DescribeExecution`, and `ListExecutions` under `io.bluetape4k.aws.sfn`. Sync
and one-shot async/coroutine operations return the raw AWS SDK response. Polling
is available only on `SfnAsyncClient` so a blocking `SfnClient` is not used from
an event loop or a Flow collector.

### Dependency boundary {#step-functions-dependency}

The module keeps the Step Functions SDK `compileOnly`. Add the service SDK to
the consumer's compile and runtime classpaths alongside the module:

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:sfn")
}
```

Use the central Bluetape4k BOM to align versions. The application also owns the
region, credentials provider, endpoint, HTTP client, and close boundary.

### Standard, Express, and Map Run {#step-functions-capabilities}

The helpers preserve AWS service semantics rather than mapping executions to a
new domain type.

| Helper/API | Standard | Express | Map Run child |
| --- | --- | --- | --- |
| `StartExecution` | Supported | Supported | Not a direct child-start API |
| `DescribeExecution` | Supported | Not supported for a normal Express execution | Conditional; use the AWS-supported child execution ARN |
| `ListExecutions` | State-machine ARN | Not supported for a normal Express execution | Conditional with `mapRunArn` |
| `StopExecution` | Supported | Not supported | Conditional for a Standard child; Express children remain unsupported |
| `describeExecutionFlow` | Same boundary as `DescribeExecution` | Not supported | Same conditional boundary as `DescribeExecution` |

For `StartExecution`, Standard executions are idempotent for the same name and
input, while Express executions are not. The caller must prevent duplicate
Express starts when it retries.

The Java request builder separates `listExecutionsByStateMachine` and
`listExecutionsByMapRun`, while the raw SDK paginator remains available for
callers that need all pages. `PENDING_REDRIVE` is a raw terminal response, not
a business-success result. Express execution observation through CloudWatch
Logs is outside this helper.

### Polling and cancellation {#step-functions-polling}

`SfnAsyncClient.describeExecutionFlow(...)` returns a cold
`Flow<DescribeExecutionResponse>`. Each collection starts with an immediate
`DescribeExecution` call, emits `RUNNING` responses, and ends after emitting a
known terminal response (`SUCCEEDED`, `FAILED`, `TIMED_OUT`, `ABORTED`, or
`PENDING_REDRIVE`). `SfnExecutionPollingOptions.pollInterval` defaults to and
must be at least one second. The one-second value is a per-collector lower
bound, not an account or Region quota guarantee.

```kotlin
import io.bluetape4k.aws.sfn.describeExecutionFlow
import io.bluetape4k.aws.sfn.withSfnAsyncClient
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withTimeout
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse
import kotlin.time.Duration.Companion.seconds

suspend fun awaitExecution(executionArn: String): DescribeExecutionResponse =
    withSfnAsyncClient(region = Region.AP_NORTHEAST_2) { client ->
        withTimeout(30.seconds) {
            client.describeExecutionFlow(executionArn).last()
        }
    }
```

The caller supplies the timeout or deadline. Cancellation is rethrown as
`CancellationException`; the Flow never calls `StopExecution` implicitly and
never closes a caller-owned client. Unknown SDK statuses fail closed without
emitting a response. Keep collection inside the `withSfnAsyncClient` block or
inside an application-owned client scope.

### IAM and KMS {#step-functions-iam-kms}

Create separate least-privilege statements for the operation and resource
kind. State-machine aliases and versions, Standard executions, Express
executions, labelled Map Run children, and Map Run resources have different ARN
shapes; do not combine them under one broad wildcard.

| Statement | IAM action | Resource boundary |
| --- | --- | --- |
| Start execution | `states:StartExecution` | Target state machine: unqualified `statemachine`, qualified `statemachinealias`, or `statemachineversion` ARN |
| Start execution's dependent describe | `states:DescribeExecution` | A separate statement for the resulting Standard `execution` or Express `express` execution ARN pattern |
| Describe execution | `states:DescribeExecution` | Standard `execution`, supported Express `express`, or a supported labelled Map Run child (`labelled execution`/`labelled express`) ARN |
| Stop execution | `states:StopExecution` | Standard `execution` or labelled Standard child ARN only; do not grant Express execution stop |
| List executions by state machine | `states:ListExecutions` | State-machine ARN only |
| List executions by Map Run | `states:ListExecutions` | Map Run `maprun` ARN only |

Keep the qualified state-machine, Standard execution, Express execution,
labelled Map Run child, and Map Run resource patterns in separate statements.
Do not collapse them into one wildcard, and keep the dependent
`states:DescribeExecution` grant separate from `states:StartExecution`.

For `StopExecution(error = null, cause = null)`, no error detail is sent for
encryption, which can avoid an additional execution-role KMS data-key grant.
When encrypted execution data is requested with `includedData = ALL_DATA`,
the caller's KMS key policy and `kms:Decrypt` permission remain part of the
trust boundary; the helper does not bypass them. Preserve KMS service errors
instead of translating them.

Do not log or persist raw ARNs, execution names, input, output, error, cause,
trace headers, or raw SDK response payloads by default. If a stable correlation
key is required, use a caller-managed HMAC rather than a plain hash when the
identifier is sensitive. Custom `endpoint` overrides, credentials providers,
and HTTP clients are caller-owned trust boundaries; use them only for trusted emulator
or private endpoints and never send production credentials or payloads to an
untrusted or non-TLS endpoint.

### Quotas and observability {#step-functions-operations}

Step Functions API quotas apply at the account and Region level and can vary by
Region or AWS service policy. Recheck the [current service
quotas](https://docs.aws.amazon.com/step-functions/latest/dg/service-quotas.html)
before setting a production polling budget. The helper does not add a rate
limiter, retry policy, jitter, or internal timeout.

- Bound every collector with `withTimeout` or an external deadline.
- Limit collector count and aggregate polling rate per account and Region;
  distribute start times or add caller-owned jitter/rate limiting to avoid a
  synchronized burst.
- Share one polling source with `shareIn` or `stateIn` when several consumers
  observe the same execution.
- Record service, operation, outcome/status, latency, retry count, throttle
  count, and the AWS request ID from SDK response metadata. Do not record the
  request or response payload.
- Use CloudWatch execution and throttling metrics with a bounded logical
  identity, not a raw execution ARN.

### Emulator evidence {#step-functions-emulator}

Run the module's Step Functions smoke checks with the repository's Floci-first
selector, then select LocalStack explicitly for a fallback. If Floci lacks the
service, retain the exact assumption message
`live integration unverified: Floci does not support Step Functions` and the
skipped test result. If LocalStack is unsupported or fails, report
`live integration unverified: LocalStack Step Functions smoke failed: <error>`
with the test result XML; do not turn either result into a live-integration
PASS. Unit, consumer compile, and dependency-publication checks still provide
useful local evidence.

An emulator smoke proves endpoint compatibility and basic request/response
serialization only. It does not prove production IAM resource policies, KMS
key policies, quotas, latency, or managed-service behavior. Until a separate
credential-gated AWS check exists, IAM/KMS integration remains `UNVERIFIED`.

## Recommended patterns {#patterns}

Put client and background-job ownership at one application boundary. Configure region, credentials, and endpoints once instead of rebuilding them per call.

## Integrations {#integrations}

Spring Boot and Ktor modules build on this library. Add only the Java SDK v2 services used at runtime, for example `software.amazon.awssdk:s3`.

## Configuration {#configuration}

Set region, credentials, endpoint override, HTTP implementation, retry policy, and timeouts on the AWS client builder before sharing the client.

## Failure modes {#failures}

Blank queue URLs, incomplete S3 pagination, missing service jars, blocking calls on coroutine threads, and leaked async clients are the common failure modes.

## Operations {#operations}

Reuse thread-safe clients, bound concurrency, monitor retries and throttling, and make copy-then-delete S3 move semantics explicit.

## Testing {#testing}

Use Floci first with endpoint override and static test credentials; use LocalStack only for service behavior that Floci does not cover.

## Workshops and learning path {#workshops}

Read `client-lifecycle`, then `sync-async-coroutines`, then `service-patterns`; continue with the released S3, DynamoDB, and SQS examples.

## Limitations {#limitations}

The artifact does not bring every AWS service SDK at runtime. Coroutine helpers do not turn synchronous clients into non-blocking clients.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### AWS Java architecture diagram

[![AWS Java architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-java-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-java-architecture-01.svg)

_Release README: [`aws-java/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-java/README.md)_

### AWS Java operation flow diagram

[![AWS Java operation flow diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-java-flow-02.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-java-flow-02.svg)

_Release README: [`aws-java/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-java/README.md)_

### AWS Java coroutine sequence diagram

[![AWS Java coroutine sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-java-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-java-sequence-03.svg)

_Release README: [`aws-java/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-java/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Release source: `aws-java/src/main/kotlin/io/bluetape4k/aws/s3/S3AsyncClientCoroutinesExtensions.kt`](../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/s3/S3AsyncClientCoroutinesExtensions.kt)
- [Release source: `aws-java/src/main/kotlin/io/bluetape4k/aws/dynamodb/repository/DynamoDbCoroutineRepository.kt`](../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/dynamodb/repository/DynamoDbCoroutineRepository.kt)
- [Release test: emulator selection](../../../../aws-java/src/test/kotlin/io/bluetape4k/aws/AbstractAwsTest.kt)
