# Module bluetape4k-aws-kotlin

English | [한국어](./README.ko.md)

A unified integration module built on the AWS Kotlin SDK. Provides native
`suspend` functions out of the box, so you can use it directly in coroutine environments without any
`.await()` conversion.

> For the AWS Java SDK v2 based module, use `bluetape4k-aws-java`.

## Diagrams

### Module Architecture

![AWS Kotlin architecture diagram](../docs/images/readme-diagrams/aws-kotlin-architecture-01.png)

### Operation Flow

![AWS Kotlin operation flow diagram](../docs/images/readme-diagrams/aws-kotlin-flow-02.png)

### Client Lifecycle Sequence

![AWS Kotlin client lifecycle sequence diagram](../docs/images/readme-diagrams/aws-kotlin-sequence-03.png)

## Supported Services

| Service             | Key Features                                            |
|---------------------|---------------------------------------------------------|
| **DynamoDB**        | Table CRUD, scan/query, DSL builders                    |
| **S3**              | Object upload/download, multipart, bucket management    |
| **SES / SESv2**     | Email sending, templated email                          |
| **SNS**             | Topic publishing, SMS, subscription management          |
| **SQS**             | Message send/receive/delete, FIFO queues                |
| **KMS**             | Encryption key management, data key generation          |
| **CloudWatch**      | Metric publishing/querying, DSL (`metricDatum {}`)      |
| **CloudWatch Logs** | Log event publishing, DSL (`inputLogEvent {}`)          |
| **Kinesis**         | Stream record publishing, `recordFlow {}` cold Flow per shard, DSL (`putRecordRequestOf {}`) |
| **EventBridge**     | Event bus, rule, target, list, and `PutEvents` suspend helpers |
| **STS**             | AssumeRole, CallerIdentity, DSL (`stsClientOf {}`)      |
| **Secrets Manager** | Redacted secret values, client lifecycle helpers, request DSLs |
| **Parameter Store** | Parameter reads, SecureString wrappers, path queries, request DSLs |

## Java SDK v2 vs Kotlin SDK Comparison

| Aspect      | `bluetape4k-aws-java` (Java SDK) | `bluetape4k-aws-kotlin` (Kotlin SDK) |
|-------------|--------------------------------|--------------------------------------|
| Coroutines  | requires `.await()` conversion | native `suspend` built in            |
| DSL support | limited                        | rich DSL builders                    |
| Performance | CRT/Netty NIO choice           | CRT / OkHttp choice                  |

## Client Creation Patterns

Each service provides two factory functions.

### `xxxClientOf` — Direct Client Creation

Use this for long-lived clients. **You must call `close()`** when done.

```kotlin
val client = sqsClientOf(
    endpointUrl = Url.parse("http://localhost:4566"),
    region = "us-east-1",
    credentialsProvider = credentialsProvider
)

try {
    client.sendMessage(queueUrl, "Hello!")
} finally {
    client.close()   // or use useSafe { }
}
```

### `withXxxClient` — One-Shot Usage (Recommended)

Uses `useSafe { }` internally to release resources safely even on coroutine cancellation or exceptions.

```kotlin
withSqsClient(endpointUrl, region, credentialsProvider) { client ->
    client.sendMessage(queueUrl, "Hello!")
}   // close() called automatically
```

> **[!NOTE]**
> AWS Kotlin SDK clients hold internal HTTP connection pools and threads, so `close()` must always be called after use.
> The
`withXxxClient { }` block ensures resources are released automatically even on coroutine cancellation or exceptions.
> If you create a long-lived client directly, call `close()` explicitly when the application shuts down.

## Usage Examples

### DynamoDB (native suspend)

```kotlin
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.getItem
import io.bluetape4k.aws.kotlin.dynamodb.*
import io.bluetape4k.aws.kotlin.dynamodb.model.toAttributeValue

// One-shot: use withDynamoDbClient (auto-close)
suspend fun getItem(tableName: String, userId: String) =
    withDynamoDbClient(region = "ap-northeast-2") { client ->
        client.getItem {
            this.tableName = tableName
            this.key = mapOf("userId" to userId.toAttributeValue())
        }
    }
```

### CloudWatch Metrics (DSL)

```kotlin
import io.bluetape4k.aws.kotlin.cloudwatch.*
import io.bluetape4k.aws.kotlin.cloudwatch.model.metricDatum
import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.cloudwatch.putMetricData
import aws.sdk.kotlin.services.cloudwatch.model.StandardUnit

val cw = CloudWatchClient { region = "ap-northeast-2" }

suspend fun publishMetric(namespace: String, value: Double) {
    cw.putMetricData {
        this.namespace = namespace
        metricData = listOf(
            metricDatum {           // bluetape4k DSL
                metricName = "RequestCount"
                this.value = value
                unit = StandardUnit.Count
            }
        )
    }
}
```

### CloudWatch Logs (DSL)

```kotlin
import aws.sdk.kotlin.services.cloudwatchlogs.CloudWatchLogsClient
import aws.sdk.kotlin.services.cloudwatchlogs.putLogEvents
import io.bluetape4k.aws.kotlin.cloudwatch.*
import io.bluetape4k.aws.kotlin.cloudwatch.model.cloudwatchlogs.inputLogEvent

suspend fun sendLog(client: CloudWatchLogsClient, logGroup: String, logStream: String, message: String) {
    client.putLogEvents {
        logGroupName = logGroup
        logStreamName = logStream
        logEvents = listOf(
            inputLogEvent {         // bluetape4k DSL
                timestamp = System.currentTimeMillis()
                this.message = message
            }
        )
    }
}
```

### STS (DSL)

```kotlin
import aws.sdk.kotlin.services.sts.getCallerIdentity
import io.bluetape4k.aws.kotlin.sts.*

// Create StsClient using bluetape4k DSL
val stsClient = stsClientOf(region = "ap-northeast-2")

suspend fun getCallerIdentity() = stsClient.getCallerIdentity {}
```

### Kinesis (DSL)

```kotlin
import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.putRecord
import io.bluetape4k.aws.kotlin.kinesis.model.putRecordRequestOf

suspend fun putRecord(client: KinesisClient, streamName: String, data: ByteArray) {
    client.putRecord(
        putRecordRequestOf(streamName, data, partitionKey = "default")
    )
}
```

### Kinesis — `recordFlow` (cold `Flow<Record>` per shard)

`KinesisClient.recordFlow()` returns a cold `Flow<Record>` that continuously polls a single
shard and emits each record. The flow terminates naturally when the shard is closed (resharding).

```kotlin
import aws.sdk.kotlin.services.kinesis.KinesisClient
import io.bluetape4k.aws.kotlin.kinesis.KinesisStartingPosition
import io.bluetape4k.aws.kotlin.kinesis.KinesisRecordFlowOptions
import io.bluetape4k.aws.kotlin.kinesis.recordFlow
import kotlinx.coroutines.flow.take
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Basic usage — read from the beginning of a shard
kinesisClient.recordFlow(
    streamName = "my-stream",
    shardId    = "shardId-000000000000",
    position   = KinesisStartingPosition.TrimHorizon,
).collect { record ->
    println(record.data!!.decodeToString())
}

// Resume from a saved checkpoint
kinesisClient.recordFlow(
    streamName = "my-stream",
    shardId    = "shardId-000000000000",
    position   = KinesisStartingPosition.AfterSequenceNumber(lastSequenceNumber),
).collect { record -> /* ... */ }

// Custom tuning
val options = KinesisRecordFlowOptions(
    batchLimit             = 500,
    pollInterval           = 200.milliseconds,
    emptyBackoff           = 2.seconds,
    maxIteratorRetries     = 5,
    initialThrottleBackoff = 500.milliseconds,
    maxThrottleBackoff     = 30.seconds,
    maxThrottleRetries     = 5,
)
kinesisClient.recordFlow("my-stream", "shardId-000000000000", options = options)
    .take(1_000)
    .collect { /* ... */ }
```

#### Starting positions

| Position | Description |
|---|---|
| `TrimHorizon` | All records from the oldest available (default) |
| `Latest` | Records written after the iterator is obtained. **Caution:** if the iterator expires before the first record is processed, the flow throws immediately rather than silently skipping records. |
| `AtSequenceNumber(seq)` | The record with the given sequence number (inclusive) |
| `AfterSequenceNumber(seq)` | Records after the given sequence number (exclusive) |
| `AtTimestamp(instant)` | Records at or after the given `java.time.Instant` |

#### Error handling

| Error | Behaviour |
|---|---|
| Shard closed (`nextShardIterator == null`) | Flow completes normally |
| `ExpiredIteratorException` | Re-fetches the iterator using the last seen sequence number; throws after `maxIteratorRetries` attempts |
| `Latest` with no checkpoint + expiry | Throws immediately — re-fetching `Latest` would silently skip records |
| Retryable `KinesisException` | Exponential jitter backoff; throws after `maxThrottleRetries` attempts |
| Non-retryable `KinesisException` | Propagated immediately |
| `CancellationException` | Propagated immediately |

### EventBridge (native suspend)

```kotlin
import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import io.bluetape4k.aws.kotlin.eventbridge.putEvents
import io.bluetape4k.aws.kotlin.eventbridge.model.putEventsRequestEntryOf

suspend fun publishOrderEvent(client: EventBridgeClient) {
    val entry = putEventsRequestEntryOf(
        source = "orders",
        detailType = "OrderCreated",
        detail = """{"orderId":"o-1"}""",
        eventBusName = "orders-bus",
    )

    val response = client.putEvents(listOf(entry))
    // Inspect response.failedEntryCount and response.entries for partial failures.
}
```

EventBridge helpers keep one SDK request per call and return raw SDK responses.
Add `aws.sdk.kotlin:eventbridge` at runtime. Scheduler, framework integrations,
global endpoints, cross-account target orchestration, and target-specific
validation beyond SDK model types are outside this module.

### Secrets Manager and Parameter Store

```kotlin
import io.bluetape4k.aws.kotlin.secretsmanager.getSecretString
import io.bluetape4k.aws.kotlin.secretsmanager.withSecretsManagerClient
import io.bluetape4k.aws.kotlin.ssm.getParameter
import io.bluetape4k.aws.kotlin.ssm.getParametersByPath
import io.bluetape4k.aws.kotlin.ssm.getSecureParameter
import io.bluetape4k.aws.kotlin.ssm.withSsmClient

data class DatabaseCredential(
    val apiKey: String,
    val password: String,
    val database: String,
)

suspend fun loadApiCredential(secretId: String): DatabaseCredential {
    val apiKey = withSecretsManagerClient(region = "ap-northeast-2") { client ->
        client.getSecretString(secretId)
    }
    val dbPassword = withSsmClient(region = "ap-northeast-2") { client ->
        client.getSecureParameter("/app/db/password")
    }
    val dbName = withSsmClient(region = "ap-northeast-2") { client ->
        client.getParameter("/app/db/name").parameter?.value.orEmpty()
    }

    return DatabaseCredential(
        apiKey = apiKey.reveal(),
        password = dbPassword.reveal(),
        database = dbName,
    )
}

suspend fun loadAppParameters() =
    withSsmClient(region = "ap-northeast-2") { client ->
        client.getParametersByPath(
            path = "/app",
            recursive = true,
            maxResults = 10,
        ).parameters
    }
```

Keep secret values inside `AwsSecretValue` until the consumer boundary that
requires plaintext. Do not print, log, or include revealed values in exception
messages.

## Not Provided by This Module

This module does not provide Spring Environment loading, JSON flattening,
cache/refresh policies, rotation orchestration, IAM/KMS policy management, or a
hidden all-pages collection abstraction. Use the Spring/Exposed modules or
application code for those concerns.

For hot paths, keep caller-owned caches at the application boundary and define
explicit refresh/error policy there. Create and put helpers mutate AWS-side
state; keep their use deliberate and audited.

## Test Environment

Integration tests default to Floci through Testcontainers. LocalStack remains
available as an explicit fallback with `-Dbluetape4k.aws.emulator=localstack`
for emulator coverage gaps.

```kotlin
abstract class AbstractAwsTest {
    companion object {
        val awsEmulator: AwsEmulatorServer by lazy { FlociServer.Launcher.floci }
    }

    suspend fun buildSqsClient(): SqsClient = SqsClient {
        endpointUrl = Url.parse(awsEmulator.awsEndpoint.toString())
        region = awsEmulator.regionName
        credentialsProvider = StaticCredentialsProvider {
            accessKeyId = awsEmulator.awsAccessKey
            secretAccessKey = awsEmulator.awsSecretKey
        }
    }
}
```

Run module tests:

```bash
./gradlew :bluetape4k-aws-kotlin:test
./gradlew :bluetape4k-aws-kotlin:test -Dbluetape4k.aws.emulator=localstack
```

## Adding the Dependency

AWS Kotlin SDK services are declared as
`compileOnly` dependencies, so you need to add the runtime dependencies for the services you use.
`bluetape4k-aws-kotlin` exposes common bluetape4k coroutine utilities, but it does not force every
AWS service client onto consumers that do not use that service.

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-kotlin:${bluetape4kVersion}")

    // Add only the services you need
    implementation("aws.sdk.kotlin:dynamodb:${awsKotlinSdkVersion}")
    implementation("aws.sdk.kotlin:s3:${awsKotlinSdkVersion}")
    implementation("aws.sdk.kotlin:secretsmanager:${awsKotlinSdkVersion}")
    implementation("aws.sdk.kotlin:sqs:${awsKotlinSdkVersion}")
    implementation("aws.sdk.kotlin:ssm:${awsKotlinSdkVersion}")
    implementation("aws.sdk.kotlin:sns:${awsKotlinSdkVersion}")
    implementation("aws.sdk.kotlin:kms:${awsKotlinSdkVersion}")
    implementation("aws.sdk.kotlin:cloudwatch:${awsKotlinSdkVersion}")
    implementation("aws.sdk.kotlin:cloudwatchlogs:${awsKotlinSdkVersion}")
    implementation("aws.sdk.kotlin:kinesis:${awsKotlinSdkVersion}")
    implementation("aws.sdk.kotlin:eventbridge:${awsKotlinSdkVersion}")
    implementation("aws.sdk.kotlin:sts:${awsKotlinSdkVersion}")
    // ... add other services as needed
}
```
