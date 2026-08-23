# Module bluetape4k-aws-java

English | [한국어](./README.ko.md)

A unified integration module built on AWS Java SDK v2. It keeps AWS SDK model
types visible while adding sync helpers, async `CompletableFuture` extensions,
and coroutine APIs for DynamoDB, S3, S3 Tables, optional S3 Vectors, SES, SNS, SQS, KMS,
CloudWatch, Kinesis, EventBridge, Step Functions, Lambda, STS, Secrets Manager, and
Parameter Store.

## Diagrams

The diagrams below split the module into three views: static boundaries,
runtime call flow, and coroutine handoff. Together they show where application
code owns AWS SDK runtime dependencies and where this module adds factories,
request DSLs, async extensions, coroutine wrappers, and repository helpers.

### Module Architecture

![AWS Java architecture diagram](../docs/images/readme-diagrams/aws-java-architecture-01.png)

### Operation Flow

![AWS Java operation flow diagram](../docs/images/readme-diagrams/aws-java-flow-02.png)

### Coroutine Sequence

![AWS Java coroutine sequence diagram](../docs/images/readme-diagrams/aws-java-sequence-03.png)

## Supported Services

| Service             | Key Features                                                                 |
|---------------------|------------------------------------------------------------------------------|
| **DynamoDB**        | Table CRUD, Enhanced Client, Coroutines extensions                           |
| **S3**              | Object upload/download, TransferManager (large files), Coroutines extensions |
| **S3 Tables**       | Table bucket, namespace, and table management with sync/async/coroutine extensions |
| **S3 Vectors**      | Optional vector bucket/index discovery and vector put/get/list/query facade  |
| **SES**             | Email sending, Coroutines extensions                                         |
| **SNS**             | Topic publishing, SMS, push notifications, Coroutines extensions             |
| **SQS**             | Message send/receive/delete, Coroutines extensions                           |
| **KMS**             | Encryption key management, request DSLs, sync/async client builders          |
| **CloudWatch**      | Metric publishing/querying, Coroutines extensions                            |
| **CloudWatch Logs** | Log group/stream management, event publishing, Coroutines extensions         |
| **Kinesis**         | Stream record send/receive, Coroutines extensions                            |
| **EventBridge**     | Event bus, rule, target, list, and `PutEvents` helpers                       |
| **Step Functions**  | Execution start/stop/describe/list, async coroutine `Flow` polling           |
| **Lambda**          | Sync, async `CompletableFuture`, coroutine invocation, typed payload codecs  |
| **Bedrock Runtime** | Model-neutral `Converse`, `ConverseStream`, and cold text-delta `Flow`       |
| **STS**             | AssumeRole, CallerIdentity, SessionToken, Coroutines extensions              |
| **Secrets Manager** | Redacted secret values, request DSLs, sync/async/coroutine helpers           |
| **Parameter Store** | Parameter reads, SecureString wrappers, path queries, request DSLs           |

## Bedrock Runtime Converse and Streaming

![Amazon Bedrock Runtime streaming sequence](../docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-en-01.png)

The facade keeps native AWS SDK v2 request, response, event, future, and
exception types. It supports blocking `Converse`, the original
`CompletableFuture` through `converseAsync`, a suspending `converse` extension,
and model-neutral `ConverseStream` as a cold `Flow`. It does not add a
provider-specific prompt abstraction.

```kotlin
import io.bluetape4k.aws.bedrock.bedrockRuntimeAsyncClientOf
import io.bluetape4k.aws.bedrock.converseStreamFlow
import io.bluetape4k.aws.bedrock.model.converseStreamRequestOf
import io.bluetape4k.aws.bedrock.model.userMessageOf
import io.bluetape4k.aws.bedrock.textDeltaFlow
import io.bluetape4k.coroutines.flow.extensions.takeUntil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

suspend fun streamReply(
    modelId: String,
    prompt: String,
    stopSignal: Flow<Any?>,
): List<String> =
    bedrockRuntimeAsyncClientOf().use { client ->
        withTimeout(30.seconds) {
            client.converseStreamFlow(
                converseStreamRequestOf(
                    modelId = modelId,
                    messages = listOf(userMessageOf(prompt)),
                ),
            )
                .textDeltaFlow()
                .takeUntil(stopSignal)
                .toList()
        }
    }
```

The application owns clients returned by `bedrockRuntimeClientOf` and
`bedrockRuntimeAsyncClientOf`; close them after the terminal stream collection.
Every collection is a new, potentially billable request. `takeUntil` checks its
stop state when the source produces the next event, so use `withTimeout` for a
hard deadline when the model may remain silent.

- `textDeltaFlow()` reuses bluetape4k-coroutines `castNotNull` to select native
  text deltas in order without buffering, replay, parallel mapping, or logging.
- Blank model IDs, empty message collections, and blank text passed to
  `contentBlockOf` or `userMessageOf` fail with `IllegalArgumentException`
  before an SDK call.
- Native SDK failures and coroutine cancellation reach the caller unchanged.
  Exceptional futures remain exceptional, and a streaming collector can
  already hold partial text when failure, timeout, or cancellation occurs.
- AWS SDK retries can repeat semantically equivalent output. There is no
  exactly-once delivery, deduplication, replay, or facade-level retry.
- Prefer non-streaming `Converse` when the operation must be transactional.
- Keep credentials on the default AWS provider chain, use HTTPS except for
  literal loopback tests, treat generated output as untrusted, and never
  execute tools from it automatically. Log only allowlisted operation
  metadata; never log or expose raw SDK exceptions, prompts, or model output
  beyond the application boundary.

## Three-Tier API Pattern

Each service provides three tiers of API:

```
sync (blocking) → async (CompletableFuture) → coroutines (suspend)
```

Where coroutine extensions are provided, the coroutines tier wraps
`CompletableFuture` with `.await()` extension functions, so no thread blocking
occurs in coroutine contexts. Service SDK artifacts remain `compileOnly`;
applications must add the AWS SDK and coroutine runtime modules they use.

## Usage Examples

### DynamoDB Enhanced Async Table

```kotlin
import io.bluetape4k.aws.dynamodb.enhanced.getItem
import kotlinx.coroutines.future.await
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable

suspend fun saveAndLoad(table: DynamoDbAsyncTable<UserDocument>, user: UserDocument): UserDocument? {
    table.putItem(user).await()
    return table.getItem(partitionValue = user.id)
}
```

### S3 Async Client

```kotlin
import io.bluetape4k.aws.s3.getAsString
import io.bluetape4k.aws.s3.listAllObjects
import io.bluetape4k.aws.s3.putAsString
import kotlinx.coroutines.flow.toList
import software.amazon.awssdk.services.s3.S3AsyncClient

suspend fun writeThenRead(client: S3AsyncClient, bucket: String, key: String): String {
    client.putAsString(bucket, key, "hello")
    return client.getAsString(bucket, key)
}

suspend fun listLogKeys(client: S3AsyncClient, bucket: String): List<String> =
    client.listAllObjects(bucket, prefix = "logs/")
        .toList()
        .mapNotNull { it.key() }
```

### S3 Vectors Coroutine Facade

```kotlin
import io.bluetape4k.aws.s3vectors.S3VectorsCoroutinesTemplate
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient
import software.amazon.awssdk.services.s3vectors.model.ListIndexesRequest

class SemanticIndexReader(
    client: S3VectorsAsyncClient,
) {
    private val s3Vectors = S3VectorsCoroutinesTemplate(client)

    suspend fun listIndexes(vectorBucketName: String) =
        s3Vectors.listIndexes(
            ListIndexesRequest.builder()
                .vectorBucketName(vectorBucketName)
                .build()
        )
}
```

S3 Vectors uses the separate AWS SDK v2 `s3vectors` service. This module keeps
the dependency optional and exposes a small suspend facade for discovery,
put/get/list, and query operations. Destructive administration, tagging, and
policy calls remain available through the raw `S3VectorsAsyncClient`.

### S3 Tables management (unreleased/develop)

S3 Tables helpers keep the AWS SDK v2 request and response types visible while
covering table bucket, namespace, and table create/list/get/delete operations.
Lists return one raw service page; pass `continuationToken` explicitly when a
caller needs the next page. `ListTables` keeps `namespace` optional for
bucket-level listing. `CreateTable` defaults to the SDK's `ICEBERG` format, and
`GetTable` accepts either a table ARN or the bucket/namespace/name selector.

Add the service SDK directly because it remains `compileOnly`:

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:s3tables")
}
```

```kotlin
import io.bluetape4k.aws.s3tables.createNamespace
import io.bluetape4k.aws.s3tables.createTable
import io.bluetape4k.aws.s3tables.createTableBucket
import io.bluetape4k.aws.s3tables.withS3TablesClient
import software.amazon.awssdk.regions.Region

suspend fun createOrdersTable() = withS3TablesClient(region = Region.AP_NORTHEAST_2) { client ->
    val bucketArn = client.createTableBucket("orders-tables").arn()
    client.createNamespace(bucketArn, listOf("analytics"))
    client.createTable(bucketArn, "analytics", "orders")
}
```

`s3TablesClient` and `s3TablesClientOf` create application-scoped clients and
register them with `ShutdownQueue`; callers still own early close and any
injected HTTP client. `withS3TablesClient` creates an unregistered short-lived
client and closes only the service client when the block ends. This is a management API surface, not an
Iceberg data-plane or SQL engine. Athena, Glue, Redshift, and Apache Iceberg
integration remain application concerns, and local emulator fidelity for S3
Tables is not asserted by this module.

### Secrets Manager and Parameter Store

```kotlin
import io.bluetape4k.aws.secretsmanager.getSecretString
import io.bluetape4k.aws.ssm.getParameter
import io.bluetape4k.aws.ssm.getParametersByPath
import io.bluetape4k.aws.ssm.getSecureParameter
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.ssm.SsmClient

data class DatabaseCredential(
    val apiKey: String,
    val password: String,
    val database: String,
)

fun loadApiCredential(
    secrets: SecretsManagerClient,
    ssm: SsmClient,
    secretId: String,
): DatabaseCredential {
    val apiKey = secrets.getSecretString(secretId)
    val dbPassword = ssm.getSecureParameter("/app/db/password")
    val dbName = ssm.getParameter("/app/db/name").parameter().value()

    return DatabaseCredential(
        apiKey = apiKey.reveal(),
        password = dbPassword.reveal(),
        database = dbName,
    )
}

fun loadAppParameters(ssm: SsmClient) =
    ssm.getParametersByPath(
        path = "/app",
        recursive = true,
        maxResults = 10,
    ).parameters()
```

Keep secret values inside `AwsSecretValue` until the consumer boundary that
requires plaintext. Do not print, log, or include revealed values in exception
messages.

### SQS Coroutine Extensions

```kotlin
import io.bluetape4k.aws.sqs.receiveMessages
import io.bluetape4k.aws.sqs.send
import software.amazon.awssdk.services.sqs.SqsAsyncClient

suspend fun sendMessage(client: SqsAsyncClient, queueUrl: String, body: String) =
    client.send(queueUrl, body)

suspend fun receiveMessages(client: SqsAsyncClient, queueUrl: String) =
    client.receiveMessages(queueUrl, maxResults = 10).messages()
```

### SNS Coroutine Extensions

```kotlin
import io.bluetape4k.aws.sns.createTopic
import software.amazon.awssdk.services.sns.SnsAsyncClient

suspend fun createTopic(client: SnsAsyncClient, topicName: String) =
    client.createTopic(topicName)
```

### KMS Request DSL

```kotlin
import io.bluetape4k.aws.kms.model.encryptRequestOf
import software.amazon.awssdk.core.SdkBytes

val request = encryptRequestOf(
    keyId = "alias/my-key",
    plainText = SdkBytes.fromUtf8String("plain-text"),
)
```

### CloudWatch Coroutine Extensions

```kotlin
import io.bluetape4k.aws.cloudwatch.putMetricData
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit

suspend fun publishMetric(client: CloudWatchAsyncClient, namespace: String, value: Double) =
    client.putMetricData(
        namespace = namespace,
        metricDatum = MetricDatum.builder()
            .metricName("RequestCount")
            .value(value)
            .unit(StandardUnit.COUNT)
            .build()
    )
```

### Kinesis Coroutine Extensions

```kotlin
import io.bluetape4k.aws.kinesis.putRecord
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient

suspend fun putRecord(client: KinesisAsyncClient, streamName: String, data: ByteArray) =
    client.putRecord(
        streamName = streamName,
        partitionKey = "default",
        data = SdkBytes.fromByteArray(data),
    )
```

### EventBridge Core Helpers

```kotlin
import io.bluetape4k.aws.eventbridge.putEvents
import io.bluetape4k.aws.eventbridge.model.putEventsRequestEntryOf
import software.amazon.awssdk.services.eventbridge.EventBridgeClient

fun publishOrderEvent(client: EventBridgeClient) {
    val entry = putEventsRequestEntryOf(
        source = "orders",
        detailType = "OrderCreated",
        detail = """{"orderId":"o-1"}""",
        eventBusName = "orders-bus",
    )

    val response = client.putEvents(listOf(entry))
    // Inspect response.failedEntryCount() and response.entries() for partial failures.
}
```

EventBridge helpers keep one SDK request per call and return raw SDK responses.
Add `software.amazon.awssdk:eventbridge` at runtime. Scheduler, framework
integrations, global endpoints, cross-account target orchestration, and
target-specific validation beyond SDK model types are outside this module.

### Step Functions Execution Helpers (unreleased/develop)

The develop line adds thin `StartExecution`, `StopExecution`,
`DescribeExecution`, and `ListExecutions` extensions. Sync and one-shot async
operations return raw AWS SDK responses. Polling is available only on
`SfnAsyncClient` and returns a cold `Flow<DescribeExecutionResponse>`; the
caller owns the client, timeout, and cancellation policy.

```kotlin
import io.bluetape4k.aws.sfn.withSfnAsyncClient
import io.bluetape4k.aws.sfn.describeExecutionFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse
import kotlin.time.Duration.Companion.seconds

fun awaitExecution(executionArn: String): DescribeExecutionResponse = runBlocking {
    withSfnAsyncClient(region = Region.AP_NORTHEAST_2) { client ->
        withTimeout(30.seconds) {
            client.describeExecutionFlow(executionArn).last()
        }
    }
}
```

The example targets a Standard execution. The helper does not call
`StopExecution` when collection is cancelled, and it does not close a client
that the caller supplied. Add `software.amazon.awssdk:sfn` directly at runtime
because service SDKs remain `compileOnly`. See the [Step Functions Java module
manual](../docs/manual/en/modules/bluetape4k-aws-java.md) for dependency,
Standard/Express/Map Run, IAM/KMS, quota, and emulator boundaries.

### Lambda invocation helpers (unreleased/develop)

The develop line adds sync, async, and coroutine `Invoke` helpers under
`io.bluetape4k.aws.lambda`. The helpers preserve the raw `InvokeResponse`, copy
the response payload, expose `functionError` as result data, and decode optional
tail logs. `LambdaPayloadCodecs.jackson(...)` is available when the consumer
chooses Jackson.

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

Add `software.amazon.awssdk:lambda` directly at runtime because the service SDK
is `compileOnly`. For async callers, `invokeStringAsync` returns a future and
the coroutine overload uses `.await()`; cancelling the result future also
cancels the underlying AWS SDK future. The helper does not add retry,
deployment, polling, logging, or IAM policy management. Use an application
owned codec for typed payloads and keep invocation inside the client scope.

## Not Provided by This Module

This module does not provide Spring Environment loading, JSON flattening,
cache/refresh policies, rotation orchestration, IAM/KMS policy management, or a
hidden all-pages collection abstraction. Use the Spring/Exposed modules or
application code for those concerns.

For hot paths, keep caller-owned caches at the application boundary and define
explicit refresh/error policy there. Create and put helpers mutate AWS-side
state; keep their use deliberate and audited.

## Test Environment

Integration tests default to Floci through the shared AWS test base. LocalStack
remains available as an explicit fallback with
`-Dbluetape4k.aws.emulator=localstack` for emulator coverage gaps.

```kotlin
abstract class AbstractAwsTest {
    companion object {
        val awsEmulator: AwsEmulatorServer by lazy { FlociServer.Launcher.floci }
    }

    fun buildS3Client(): S3Client = S3Client.builder()
        .endpointOverride(awsEmulator.endpoint)
        .credentialsProvider(awsEmulator.credentialsProvider)
        .region(Region.of(awsEmulator.region))
        .build()
}
```

Run the `bluetape4k-aws-java` module tests:

```bash
./gradlew :bluetape4k-aws-java:test
./gradlew :bluetape4k-aws-java:test -Dbluetape4k.aws.emulator=localstack
```

## Adding the Dependency

AWS SDK services and coroutine helpers are declared as `compileOnly`
dependencies, so consumers add the runtime dependencies for the APIs they use.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:${bluetape4kVersion}"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java:${bluetape4kVersion}")

    // Coroutine extensions and Flow adapters used by public APIs
    implementation("io.github.bluetape4k:bluetape4k-coroutines:${bluetape4kVersion}")
    implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:${coroutinesVersion}"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive")

    // Add only the AWS services you need
    implementation(platform("software.amazon.awssdk:bom:${awsSdkVersion}"))
    implementation("software.amazon.awssdk:dynamodb-enhanced")
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:s3-transfer-manager")
    implementation("software.amazon.awssdk:s3vectors")
    implementation("software.amazon.awssdk:secretsmanager")
    implementation("software.amazon.awssdk:sqs")
    implementation("software.amazon.awssdk:ssm")
    implementation("software.amazon.awssdk:sns")
    implementation("software.amazon.awssdk:kms")
    implementation("software.amazon.awssdk:cloudwatch")
    implementation("software.amazon.awssdk:kinesis")
    implementation("software.amazon.awssdk:eventbridge")
    implementation("software.amazon.awssdk:sfn")
    implementation("software.amazon.awssdk:lambda")
    implementation("software.amazon.awssdk:bedrockruntime")
    implementation("software.amazon.awssdk:sts")
    // ... add other services as needed
}
```
