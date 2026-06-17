# Module bluetape4k-aws-java

English | [한국어](./README.ko.md)

A unified integration module built on AWS Java SDK v2. It keeps AWS SDK model
types visible while adding sync helpers, async `CompletableFuture` extensions,
and coroutine APIs for DynamoDB, S3, optional S3 Vectors, SES, SNS, SQS, KMS,
CloudWatch, Kinesis, and STS.

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
| **S3 Vectors**      | Optional vector bucket/index discovery and vector put/get/list/query facade  |
| **SES**             | Email sending, Coroutines extensions                                         |
| **SNS**             | Topic publishing, SMS, push notifications, Coroutines extensions             |
| **SQS**             | Message send/receive/delete, Coroutines extensions                           |
| **KMS**             | Encryption key management, request DSLs, sync/async client builders          |
| **CloudWatch**      | Metric publishing/querying, Coroutines extensions                            |
| **CloudWatch Logs** | Log group/stream management, event publishing, Coroutines extensions         |
| **Kinesis**         | Stream record send/receive, Coroutines extensions                            |
| **STS**             | AssumeRole, CallerIdentity, SessionToken, Coroutines extensions              |

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
    implementation("software.amazon.awssdk:sqs")
    implementation("software.amazon.awssdk:sns")
    implementation("software.amazon.awssdk:kms")
    implementation("software.amazon.awssdk:cloudwatch")
    implementation("software.amazon.awssdk:kinesis")
    implementation("software.amazon.awssdk:sts")
    // ... add other services as needed
}
```
