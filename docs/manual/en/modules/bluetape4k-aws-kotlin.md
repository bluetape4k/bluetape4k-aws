---
manualId: "bluetape4k-aws-kotlin"
id: "bluetape4k-aws-kotlin"
title: "AWS SDK for Kotlin Extensions"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-aws-kotlin"
sourceDir: "aws-kotlin"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-kotlin
---

# AWS SDK for Kotlin Extensions

> Library manual grounded in the 0.5.0 release source.

## Problem {#problem}

Builders, model conversions, Flow helpers, and client lifecycle utilities for the native suspend-based AWS SDK for Kotlin.

## When to use it {#when-to-use}

Choose it when the application is coroutine-first and does not need Java SDK v2 client interoperability.

## Coordinates {#coordinates}

Applications select one central BOM version.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-kotlin")
}
```

AWS service SDKs follow a `compileOnly` policy; add the services actually used as runtime dependencies.

## Core concepts {#concepts}

Service calls are suspend functions from the SDK itself. This module adds concise request builders, pagination/Flow patterns, and `with...Client` ownership helpers.

## Quick start {#quick-start}

```kotlin
withS3Client(region = region) { s3 ->
    s3.putFromByteArray(bucket, key, bytes)
    s3.getAsByteArray(bucket, key)
}
```

## API by task {#api-by-task}

DynamoDB model DSL and batch execution, DynamoDB Streams Flow/checkpoint consumption, S3 object operations, SQS/SNS, SES, KMS, CloudWatch, Kinesis record Flow, Lambda, STS, and HTTP engine providers.

## DynamoDB coordination {#dynamodb-coordination}

> Unreleased/develop: this section describes Issue #476 and is not part of the `0.5.0` release source.

`DynamoDbDistributedLock` and `DynamoDbMetadataStore` are coroutine-first
coordination adapters for a caller-owned DynamoDB table. They use a String
partition key only; the schema does not create tables, infer a sort key, or
manage the client lifecycle. Add the AWS Kotlin DynamoDB service SDK at runtime
because service SDKs in this module remain `compileOnly`.

Prepare a PK-only table whose hash key matches `partitionKeyAttributeName`
(`id` by default). Enable DynamoDB TTL only for the metadata
`ttlEpochSeconds` attribute; lock rows must not be deleted by TTL. A metadata
TTL and its logical `expiresAt` are written to the same epoch second.

```kotlin
val schema = DynamoDbCoordinationSchema(
    tableName = "coordination",
    namespace = "orders",
)
val lock = DynamoDbDistributedLock(client, schema)
val metadata = DynamoDbMetadataStore(client, schema)

val lease = lock.tryAcquire("orders", "worker-1")
if (lease != null) {
    // Protect the downstream write with lease.fencingToken as well.
    val renewed = lock.heartbeat(lease)
    lock.release(renewed ?: lease)
}
metadata.put("config", "v1", ttl = 60.seconds)
```

Acquire, renew, heartbeat, and release use conditional writes without an
adapter retry loop or pre-read. `tryAcquire` can return `null` when another
owner wins or when the result is indeterminate; callers must not treat a
transport failure as a successful lease. A takeover increments the monotonic
`LockLease.fencingToken`. `release` returns `false` for a stale lease and
removes only the owner while preserving the fencing counter. Downstream state
changes must check the current token, because a lease alone cannot fence an
already-started side effect.

`MetadataStore.get` returns `null` for a missing or logically expired value.
`putIfAbsent`, `remove`, and `removeIfValue` use bounded compare-and-set
operations. An expired item may be replaced or cleaned up, but an expired
cleanup still reports `false` to the caller. A caller that needs ABA protection
should store a unique version or fencing token in the value. Malformed
`AllOld`/`AllNew` responses and missing required `AllOld` capability fail
closed; they are not converted into a normal miss.

Use `withDynamoDbClient` for short-lived clients or close an application-scoped
client explicitly. If a coroutine is cancelled while a test or application
owns a table cleanup, run the cleanup in `NonCancellable` with a bounded
`withTimeout`; do not launch an unscoped background cleanup task. Credentials,
IAM policy, and downstream fencing conditions remain application concerns.

The local contract test uses FlociServer and does not contact real AWS:

```bash
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-kotlin:test --tests '*DynamoDbCoordinationFlociTest'
```

The Floci lane proves PK-only schema, conditional `AllOld`, fencing and logical
expiry behavior. It does not prove AWS throttling, asynchronous TTL deletion,
clock skew, production quotas, or credentials.

## DynamoDB Streams Flow and checkpoints {#dynamodb-streams}

> Unreleased/develop: this section describes the Issue #469 API and is not part of the `0.5.0` release source.

The native AWS SDK for Kotlin extension exposes `DynamoDbStreamsClient.recordFlow`
for one shard and `shardRecordFlow` for a bounded shard graph. It supports
`TrimHorizon`, `Latest`, `AtSequenceNumber`, and `AfterSequenceNumber`, while
keeping the SDK `Record` type visible and adding a stream/shard envelope for
multi-shard collection.

```kotlin
withDynamoDbStreamsClient(region = "ap-northeast-2") { client ->
    client.shardRecordFlow(
        streamArn = streamArn,
        checkpointStore = InMemoryDynamoDbStreamsCheckpointStore(),
    ).collect { envelope ->
        handle(envelope.record)
    }
}
```

The checkpoint is saved after downstream emission and replayed inclusively, so
the contract is at-least-once and a restart may deliver a duplicate. Root shard
trees are bounded by `maxShardConcurrency`; children start after their parent
completes. Use `withDynamoDbStreamsClient` for short-lived clients and keep
injected HTTP engines caller-owned. Floci is the first emulator lane; production
retention, throttling, and resharding timing remain AWS-only gaps.

## Kinesis multi-shard consumer {#kinesis-consumer}

> Unreleased/develop: this section describes the Issue #470 API and is not part of the `0.5.0` release source.

The native `KinesisClient.consumerFlow` discovers shards continuously, polls
each shard sequentially, and limits active shard jobs with
`maxShardConcurrency`. Parent and adjacent-parent dependencies are retained in
the graph; a child starts only after every parent has a durable
`KinesisCheckpoint.ShardEnd`. The single outer emitter provides rendezvous
backpressure and saves a checkpoint only after downstream emission returns.

```kotlin
withKinesisClient(endpointUrl = flociEndpoint, region = "us-east-1") { client ->
    client.consumerFlow(
        streamName = "orders",
        consumerGroup = "orders-api",
        streamIdentity = "orders-generation-1",
        position = KinesisStartingPosition.TrimHorizon,
        options = KinesisConsumerOptions(ownerId = "orders-worker-1"),
        checkpointStore = durableCheckpointStore,
        leaseStore = durableLeaseStore,
    ).collect { envelope -> handle(envelope.record) }
}
```

`Sequence` resumes inclusively, so the contract is at-least-once and a restart
may duplicate the last delivered record. `KinesisCheckpointStore` and
`KinesisLeaseStore` are caller-owned SPIs; `InMemory*` implementations are
intended for tests and emulator runs, and `Noop*` implementations are explicit
process-local choices without restart recovery or worker coordination. Lease
counters fence stale saves. Metrics expose finite labels and redacted tokens;
payloads, credentials, and request tokens are not emitted. The client and health
probes remain caller-owned; cancellation performs the stop/drain path.

Use Floci for local verification, with LocalStack reserved for an explicit
coverage gap:

```bash
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-kotlin:test --tests '*KinesisConsumerFlociTest'
```

Floci proves the emulator contract, not production retention or throttling.
Rollout should stop, drain, canary, and scale; rollback reuses the last durable
checkpoint and never deletes or rewinds it.

## Lambda invocation helpers {#lambda}

> Unreleased/develop: this section describes the Issue #314 API and is not part of the `0.5.0` release source.

The native AWS SDK for Kotlin module adds suspend `Invoke` helpers under
`io.bluetape4k.aws.kotlin.lambda`. `LambdaInvocationResult<T>` keeps the raw
SDK response, a copied payload, status, optional `FunctionError`, and decoded
tail log together. Function errors are returned data; transport and SDK
failures remain exceptions.

### Dependency and client boundary {#lambda-dependency}

The Lambda SDK remains `compileOnly`. Add it to the consumer classpath and use
the scoped client helper for short-lived calls:

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-kotlin")
    implementation("aws.sdk.kotlin:lambda")
}
```

```kotlin
import io.bluetape4k.aws.kotlin.lambda.invokeString
import io.bluetape4k.aws.kotlin.lambda.withLambdaClient

suspend fun invokeOrder(): String =
    withLambdaClient(region = "ap-northeast-2") { client ->
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

withLambdaClient(region = "ap-northeast-2") { client ->
    val result = client.invokeTyped(
        "orders-handler",
        OrderRequest(1),
        LambdaPayloadCodecs.jackson(mapper, OrderResponse::class.java),
    )
    check(result.value != null)
}
```

Use `invokeBytes`, `invokeString`, or `invokeTyped` with an application-owned
`LambdaPayloadCodec`. Null and empty payloads remain distinct. The request DSL
validates blank function/qualifier values and only permits tail logs for
`RequestResponse`; ARN, size, IAM, and function existence remain AWS contracts.

Native suspend cancellation is preserved and `withLambdaClient` closes only the
service client; an injected HTTP engine remains caller-owned. No retry,
deployment, polling, logging, or IAM policy management is added. The opt-in
smoke lane requires a pre-deployed function and explicit function/region inputs;
Floci Lambda is recorded as unsupported and missing inputs skip before client
creation. Do not log or persist raw payloads, decoded tail logs, or SDK response
bodies by default.

## S3 Tables management {#s3-tables}

> Unreleased/develop: this section describes the Issue #311 API and is not part of the `0.5.0` release source.

The native AWS SDK for Kotlin extension keeps S3 Tables request, response, and
exception types visible for table bucket, namespace, and table
create/list/get/delete operations. Lists return one raw service page, so the
caller supplies `continuationToken` for the next page. `ListTables` keeps
`namespace` optional for bucket-level listing. `CreateTable` defaults to
`OpenTableFormat.Iceberg`, and `GetTable` accepts either a table ARN or the
bucket/namespace/name selector.

Add the service SDK to the consumer because it remains `compileOnly`:

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-kotlin")
    implementation("aws.sdk.kotlin:s3tables")
}
```

```kotlin
withS3TablesClient(region = "ap-northeast-2") { client ->
    val bucketArn = client.createTableBucket("orders-tables").arn
    client.createNamespace(bucketArn, listOf("analytics"))
    client.createTable(bucketArn, "analytics", "orders")
}
```

`s3TablesClientOf` returns an application-scoped client that the caller must
close. `withS3TablesClient` closes only its service client when the block ends;
an injected HTTP engine remains caller-owned. This module covers management operations only. It does
not implement an Iceberg data plane, SQL, or an Athena, Glue, Redshift, or
Apache Iceberg integration facade. Local emulator fidelity for S3 Tables is
not asserted.

## Step Functions execution helpers {#step-functions}

> Unreleased/develop: this section describes the Issue #313 API and is not part of the `0.5.0` release source.

The native AWS SDK for Kotlin path adds suspend helpers for
`StartExecution`, `StopExecution`, `DescribeExecution`, and `ListExecutions`
under `io.bluetape4k.aws.kotlin.sfn`. The helpers return raw AWS Kotlin SDK
responses and keep the SDK's exception and status types visible.

### Dependency boundary {#step-functions-dependency}

The module keeps the Step Functions SDK `compileOnly`. Add the service SDK to
the consumer's compile and runtime classpaths alongside the module:

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-kotlin")
    implementation("aws.sdk.kotlin:sfn")
}
```

Use the central Bluetape4k BOM to align versions. The application also owns the
region, credentials provider, endpoint, HTTP engine, and close boundary.

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

`listExecutionsByStateMachine` and `listExecutionsByMapRun` keep the source
invariant explicit. The AWS Kotlin SDK's `listExecutionsPaginated` paginator
remains available to callers that need all pages. `PENDING_REDRIVE` is a raw
terminal response, not a business-success result. Express execution observation
through CloudWatch Logs is outside this helper.

### Polling and cancellation {#step-functions-polling}

`SfnClient.describeExecutionFlow(...)` returns a cold
`Flow<DescribeExecutionResponse>`. Each collection starts with an immediate
`DescribeExecution` call, emits `Running` responses, and ends after emitting a
known terminal response (`Succeeded`, `Failed`, `TimedOut`, `Aborted`, or
`PendingRedrive`). `SfnExecutionPollingOptions.pollInterval` defaults to and
must be at least one second. The one-second value is a per-collector lower
bound, not an account or Region quota guarantee.

```kotlin
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionResponse
import io.bluetape4k.aws.kotlin.sfn.describeExecutionFlow
import io.bluetape4k.aws.kotlin.sfn.withSfnClient
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

suspend fun awaitExecution(executionArn: String): DescribeExecutionResponse =
    withSfnClient(region = "ap-northeast-2") { client ->
        withTimeout(30.seconds) {
            client.describeExecutionFlow(executionArn).last()
        }
    }
```

The caller supplies the timeout or deadline. Cancellation is rethrown and the
Flow never calls `StopExecution` implicitly. `withSfnClient` closes the service
client after the block, while an injected HTTP engine remains caller-owned. The
Flow must be collected inside the client scope; do not return a cold Flow from a
closed `withSfnClient` block. Unknown SDK statuses fail closed without emitting
a response.

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
identifier is sensitive. Custom `endpointUrl` overrides, credentials providers,
and HTTP engines are caller-owned trust boundaries; use them only for trusted emulator
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

Add the selected `aws.sdk.kotlin:<service>` modules explicitly. Ktor integrations can use this library where native Kotlin SDK clients are appropriate.

## Configuration {#configuration}

Choose the region, credential provider, endpoint, retry strategy, and CRT or OkHttp engine when creating the client.

## Failure modes {#failures}

Do not mix Java SDK v2 and Kotlin SDK models accidentally. Watch for unbounded Flow collection, missing service modules, and clients created outside a closeable scope.

## Operations {#operations}

Share long-lived clients when call volume is high; use `with...Client` for bounded jobs. Record the chosen HTTP engine and timeout policy.

## Testing {#testing}

Run service tests against Floci first and switch the emulator explicitly when a native Kotlin SDK feature is unsupported.

## Workshops and learning path {#workshops}

Begin with S3 request builders, then DynamoDB model conversion, and finally Kinesis or pagination Flow handling.

## Limitations {#limitations}

The module is not a compatibility wrapper around Java SDK v2; types and some service coverage differ.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### AWS Kotlin architecture diagram

[![AWS Kotlin architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-kotlin-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-kotlin-architecture-01.svg)

_Release README: [`aws-kotlin/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-kotlin/README.md)_

### AWS Kotlin operation flow diagram

[![AWS Kotlin operation flow diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-kotlin-flow-02.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-kotlin-flow-02.svg)

_Release README: [`aws-kotlin/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-kotlin/README.md)_

### AWS Kotlin client lifecycle sequence diagram

[![AWS Kotlin client lifecycle sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-kotlin-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-kotlin-sequence-03.svg)

_Release README: [`aws-kotlin/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-kotlin/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Release source: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/s3/S3ClientSupport.kt`](../../../../aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/s3/S3ClientSupport.kt)
- [Release source: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlow.kt`](../../../../aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlow.kt)
- [Release test: emulator selection](../../../../aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/AbstractAwsTest.kt)
