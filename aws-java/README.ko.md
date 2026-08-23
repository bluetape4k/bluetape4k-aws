# Module bluetape4k-aws-java

[English](./README.md) | 한국어

AWS Java SDK v2 기반 통합 모듈입니다. AWS SDK 모델 타입은 그대로 드러내고,
동기 helper, 비동기 `CompletableFuture` 확장, coroutine API를 더합니다.
DynamoDB, S3, 선택적 S3 Vectors, SES, SNS, SQS, KMS, CloudWatch, Kinesis,
EventBridge, Step Functions, Lambda, STS, Secrets Manager, Parameter Store 같은 주요
서비스를 대상으로 합니다.

## 다이어그램

아래 다이어그램은 모듈을 정적 경계, 런타임 호출 흐름, 코루틴 handoff 세 관점으로
나눠 보여줍니다. 애플리케이션이 AWS SDK 런타임 의존성을 직접 고르는 지점과
이 모듈이 제공하는 factory, request DSL, async extension, coroutine wrapper,
repository helper의 역할을 함께 확인할 수 있습니다.

### 모듈 아키텍처

![AWS Java architecture diagram](../docs/images/readme-diagrams/aws-java-architecture-01.png)

### 작업 흐름

![AWS Java operation flow diagram](../docs/images/readme-diagrams/aws-java-flow-02.png)

### 코루틴 시퀀스

![AWS Java coroutine sequence diagram](../docs/images/readme-diagrams/aws-java-sequence-03.png)

## 제공 서비스

| 서비스                 | 주요 기능                                                   |
|---------------------|---------------------------------------------------------|
| **DynamoDB**        | 테이블 CRUD, Enhanced Client, Coroutines 확장                |
| **S3**              | 객체 업로드/다운로드, TransferManager(대용량), Coroutines 확장        |
| **S3 Vectors**      | 선택적 vector bucket/index 조회와 vector put/get/list/query facade |
| **SES**             | 이메일 발송, Coroutines 확장                                   |
| **SNS**             | 토픽 발행, SMS, 푸시 알림, Coroutines 확장                        |
| **SQS**             | 메시지 발송/수신/삭제, Coroutines 확장                             |
| **KMS**             | 암호화 키 관리, 요청 DSL, Sync/Async 클라이언트 빌더                    |
| **CloudWatch**      | 메트릭 발행/조회, Coroutines 확장                                |
| **CloudWatch Logs** | 로그 그룹/스트림 관리, 이벤트 전송, Coroutines 확장                     |
| **Kinesis**         | 스트림 레코드 전송/조회, Coroutines 확장                            |
| **EventBridge**     | Event bus, rule, target, list, `PutEvents` helper           |
| **Step Functions**  | 실행 시작/중지/조회/목록, async coroutine `Flow` polling       |
| **Lambda**          | 동기, async `CompletableFuture`, coroutine 호출, typed payload codec |
| **Bedrock Runtime** | 모델 중립 `Converse`, `ConverseStream`, cold text-delta `Flow` |
| **STS**             | AssumeRole, CallerIdentity, SessionToken, Coroutines 확장 |
| **Secrets Manager** | Redacted secret value, 요청 DSL, sync/async/coroutine helper |
| **Parameter Store** | Parameter 읽기, SecureString wrapper, path query, 요청 DSL |

## Bedrock Runtime Converse와 스트리밍

![Amazon Bedrock Runtime 스트리밍 시퀀스](../docs/images/readme-diagrams/aws-bedrock-runtime-streaming-sequence-ko-01.png)

이 파사드는 AWS SDK v2의 요청·응답·이벤트·future·예외 타입을 그대로 사용합니다.
블로킹 `Converse`, 원본 `CompletableFuture`를 돌려주는 `converseAsync`, suspend
`converse` 확장, 모델 중립 `ConverseStream`을 cold `Flow`로 제공합니다. 특정 모델
제공자에 종속된 프롬프트 추상화는 만들지 않습니다.

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

`bedrockRuntimeClientOf`와 `bedrockRuntimeAsyncClientOf`가 돌려준 클라이언트의
생명주기는 애플리케이션이 소유합니다. 스트림의 최종 수집이 끝난 뒤 클라이언트를
닫아야 합니다. Flow를 다시 수집하면 과금될 수 있는 새 요청이 실행됩니다.
`takeUntil`은 원본 Flow가 다음 이벤트를 내보낼 때 중단 상태를 확인하므로, 모델이 한동안
응답하지 않을 수 있다면 `withTimeout`으로 강제 제한 시간을 함께 두세요.

- `textDeltaFlow()`는 bluetape4k-coroutines의 `castNotNull`을 재사용해 네이티브
  텍스트 델타를 순서대로 고릅니다. 버퍼링·재생·병렬 매핑·로그 기록은 추가하지 않습니다.
- 빈 모델 ID, 비어 있는 메시지 컬렉션, `contentBlockOf` 또는
  `userMessageOf`에 전달한 빈 텍스트는 SDK 호출 전에
  `IllegalArgumentException`으로 거절합니다.
- 네이티브 SDK 오류와 코루틴 취소는 바꾸지 않고 호출자에게 전달합니다. 예외로 완료된
  future도 그대로 유지하며, 실패·시간 초과·취소 시점에 수집자에게는 이미 일부 텍스트가
  전달됐을 수 있습니다.
- AWS SDK 재시도는 의미가 같은 출력을 반복할 수 있습니다. 정확히 한 번
  (exactly-once), 중복 제거, 재생, 파사드 차원의 재시도는 제공하지 않습니다.
- 트랜잭션 성격의 작업에는 비스트리밍 `Converse`가 더 안전합니다.
- 자격 증명은 기본 AWS provider chain으로 공급하고, 루프백 주소를 직접 지정한 테스트가
  아니라면 HTTPS 엔드포인트만 사용하세요. 생성된 출력은 신뢰하지 말고 도구를 자동
  실행하지 마세요. 운영 로그에는 허용한 메타데이터만 남기며 원문 SDK 예외, 프롬프트,
  모델 출력은 기록하거나 애플리케이션 경계 밖에 그대로 노출하지 않습니다.

## 3단계 API 패턴

각 서비스는 3단계 API를 제공합니다:

```
sync (blocking) → async (CompletableFuture) → coroutines (suspend)
```

코루틴 확장이 제공되는 API는 `CompletableFuture`를 `.await()` 확장 함수로 래핑하므로, 코루틴 컨텍스트에서 스레드 블로킹 없이 사용할 수 있습니다.
서비스 SDK와 코루틴 헬퍼 아티팩트는 `compileOnly`로 유지되므로 애플리케이션은 실제 사용하는 AWS SDK 및 코루틴 런타임 모듈을 추가해야 합니다.

## 사용 예시

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

S3 Vectors는 별도 AWS SDK v2 `s3vectors` 서비스를 사용합니다. 이 모듈은 해당 의존성을
선택으로 유지하고 discovery, put/get/list, query 작업용 작은 suspend facade만 제공합니다.
파괴적 관리, tagging, policy 호출은 raw `S3VectorsAsyncClient` 로 그대로 사용할 수 있습니다.

### Secrets Manager와 Parameter Store

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

Secret 값은 plaintext가 꼭 필요한 consumer boundary까지 `AwsSecretValue` 안에
유지하세요. Revealed value를 출력, 로그, 예외 메시지에 포함하지 않습니다.

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
    // 일부 항목 실패 여부는 response.failedEntryCount(), response.entries()로 확인합니다.
}
```

EventBridge helper는 호출 한 번당 SDK 요청 한 번만 수행하며 SDK 응답을 그대로 반환합니다.
런타임에는 `software.amazon.awssdk:eventbridge`를 추가해야 합니다. Scheduler, framework
integration, global endpoint, cross-account target orchestration, SDK model 타입을
넘어서는 target별 검증은 이 모듈 범위에 포함하지 않습니다.

### Step Functions 실행 helper (미출시/develop)

develop 개발선에는 `StartExecution`, `StopExecution`, `DescribeExecution`,
`ListExecutions`를 위한 얇은 extension이 추가됩니다. 동기와 단발성 async 연산은 AWS
SDK raw 응답을 반환합니다. Polling은 `SfnAsyncClient`에서만 제공하며
`Flow<DescribeExecutionResponse>` cold Flow로 동작합니다. client, timeout과 cancellation
정책은 호출자가 소유합니다.

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

이 예제는 Standard execution을 대상으로 합니다. 수집이 취소되어도 helper가
`StopExecution`을 자동 호출하지 않으며 호출자가 제공한 client를 닫지 않습니다. 서비스 SDK는
`compileOnly`로 유지되므로 런타임에 `software.amazon.awssdk:sfn`을 직접 추가하세요.
의존성, Standard/Express/Map Run, IAM/KMS, quota와 emulator 경계는
[Step Functions Java 모듈 매뉴얼](../docs/manual/ko/modules/bluetape4k-aws-java.md)에서
확인할 수 있습니다.

### Lambda 호출 helper (미출시/develop)

develop 개발선에는 `io.bluetape4k.aws.lambda` 아래에 동기, async, coroutine
`Invoke` helper가 추가됩니다. Raw `InvokeResponse`를 보존하고 response payload를
복사하며, `functionError`를 결과 데이터로 노출하고 선택적 tail log를 디코드합니다.
소비자가 Jackson을 선택한 경우 `LambdaPayloadCodecs.jackson(...)`으로 typed payload를
디코드할 수 있습니다.

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

서비스 SDK는 `compileOnly`이므로 런타임에 `software.amazon.awssdk:lambda`를 직접
추가하세요. Async 호출의 `invokeStringAsync`는 future를 반환하고 coroutine overload는
`.await()`를 사용합니다. 결과 future를 취소하면 AWS SDK future에도 취소가 전달됩니다.
이 helper는 retry, 배포, polling, 로깅, IAM policy 관리를 추가하지 않습니다. Typed
payload에는 애플리케이션이 소유한 codec을 사용하고 client 범위 안에서 호출하세요.

## 이 모듈이 제공하지 않는 것

이 모듈은 Spring Environment 로딩, JSON flattening, 캐시/refresh 정책,
rotation orchestration, IAM/KMS policy 관리, 숨겨진 전체 페이지 수집 abstraction을
제공하지 않습니다. 해당 책임은 Spring/Exposed 모듈이나 애플리케이션 코드에서 다룹니다.

Hot path에서는 애플리케이션 경계에서 caller-owned cache를 두고 refresh/error 정책을
명시하세요. Create/put helper는 AWS-side state를 변경하므로 의도적으로 사용하고
감사 가능하게 유지해야 합니다.

## 테스트 환경

공유 AWS 테스트 베이스에서 Floci를 기본 emulator로 사용합니다. Floci coverage gap은
`-Dbluetape4k.aws.emulator=localstack` 로 명시 실행해 LocalStack에서 검증합니다.

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

`bluetape4k-aws-java` 모듈 테스트 실행:

```bash
./gradlew :bluetape4k-aws-java:test
./gradlew :bluetape4k-aws-java:test -Dbluetape4k.aws.emulator=localstack
```

## 설치

AWS SDK 서비스와 코루틴 헬퍼는 `compileOnly`로 선언되어 있으므로, 사용할 API에 필요한 런타임 의존성을 애플리케이션에서 추가해야 합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:${bluetape4kVersion}"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java:${bluetape4kVersion}")

    // 공개 코루틴 확장과 Flow 어댑터 사용 시 필요
    implementation("io.github.bluetape4k:bluetape4k-coroutines:${bluetape4kVersion}")
    implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:${coroutinesVersion}"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive")

    // 사용할 AWS 서비스만 선택적으로 추가
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
    // ... 필요한 서비스 추가
}
```
