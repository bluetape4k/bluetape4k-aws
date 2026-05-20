# Module bluetape4k-aws-spring-boot

한국어 | [English](README.md)

AWS Java SDK v2 를 위한 Spring Boot 4 자동 설정 모듈. Coroutines 우선 템플릿과
SQS 리스너 컨테이너, 원격 Environment source 를 제공하며, `awspring` 런타임
의존성은 사용하지 않는다.

## Architecture

![aws spring boot Architecture diagram](../docs/images/readme-diagrams/aws-spring-boot-architecture-01.png)

## 주요 기능

- **S3** — `S3CoroutinesTemplate` 로 버킷 존재 확인, 업로드/다운로드(바이트·문자열),
  삭제, 페이지 단위 조회(`listPage`/`listFlow`), Spring `Resource` 뷰, presigned
  GET/PUT URL 발급을 지원한다.
- **SNS** — `SnsCoroutinesTemplate` 로 topic 생성/조회, topic publish, FIFO publish
  필드, 직접 SMS publish 옵션, HTTP(S) notification JSON 파싱과 token 기반 subscription
  confirmation 을 지원한다.
- **SQS** — `SqsCoroutinesTemplate` 로 큐 조회·생성, 송신, 수신, visibility 변경,
  cold `Flow<SqsReceivedMessage>` 스트림을 제공한다.
- **SQS 리스너** — `@SqsListener` 어노테이션 기반의 Coroutine 메시지 리스너 컨테이너.
  동시 처리 수, visibility/error-visibility 타임아웃을 속성으로 조정한다.
- **DynamoDB** — `CoroutinesDynamoDbRepository<T, ID>` 추상 베이스가
  `DynamoDbAsyncTable` 위에서 `save`/`findById`/`update`/`delete` 와
  `scan`/`query`/`queryIndex` 의 `Flow` 결과를 제공한다. 논리 테이블 이름은
  `DynamoDbTableNameResolver`(기본 구현은 `tablePrefix` 적용)로 해석된다.
- **KMS** — `KmsOperations` 로 coroutine 암호화/복호화와 data key 생성을
  제공하고, 선택적 Spring Security `TextEncryptor`, `String` 필드용 명시적
  `@KmsEncrypted` + `KmsEncryptedFieldCodec` 를 지원한다.
- **Secrets Manager / Parameter Store** — 원격 secret/parameter 를 시작 시점에
  Spring Environment 로 로드하고, 선택적 lazy refresh 및 Spring `@Value` 기반의
  `@SecretsValue` / `@ParameterStoreValue` 조합 어노테이션을 제공한다.
- **awspring 런타임 의존성 없음** — AWS SDK v2 서비스는 모두 `compileOnly` 로
  선언되어 있어, 사용자는 실제로 쓰는 서비스만 골라 추가할 수 있다.

## 의존성

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot:${bluetape4kAwsVersion}")

    // 런타임에서 사용할 AWS SDK v2 서비스만 선택적으로 추가
    implementation(platform("software.amazon.awssdk:bom:${awsSdkVersion}"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:sns")
    implementation("software.amazon.awssdk:sqs")
    implementation("software.amazon.awssdk:dynamodb-enhanced")
    implementation("software.amazon.awssdk:kms")
    implementation("software.amazon.awssdk:secretsmanager")
    implementation("software.amazon.awssdk:ssm")
}
```

> Maven Central Snapshots:
> ```kotlin
> repositories { maven("https://central.sonatype.com/repository/maven-snapshots/") }
> ```

## 설정

```yaml
bluetape4k:
  aws:
    s3:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:4566   # LocalStack
      path-style-access-enabled: true
      presign:
        duration: PT15M
    sqs:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      listener:
        max-messages: 10              # 1..10
        wait-time-seconds: 20         # 0..20
        visibility-timeout-seconds: 60
        error-visibility-timeout-seconds: 0
        concurrency: 2
        stop-timeout-millis: 25000
      queues:
        orders:
          url: http://localhost:4566/000000000000/orders
    sns:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      topics:
        orders.fifo:
          fifo: true
          content-based-deduplication: true
          fifo-throughput-scope: message-group
    dynamodb:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      table-prefix: local-
    kms:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      key-id: alias/app
      encryption-context:
        service: order-api
      field-encryption:
        enabled: true
    secrets-manager:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      refresh-interval: 30s
      sources:
        - name: app-secret
          secret-id: /config/order-api
          prefix: app
          format: json
    parameter-store:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      refresh-interval: 30s
      sources:
        - name: app-parameters
          path: /config/order-api
          prefix: app
          recursive: true
          with-decryption: true
```

`endpoint-override` 를 지정하면 반드시 `region` 도 설정해야 한다. 각 Properties
클래스의 `init` 블록에서 시작 시점에 강제한다.
`sns.topics.<name>` 은 `SnsOperations.createConfiguredTopic("<name>")` 에서 사용하는
topic 생성 기본값이다.
`sqs.queues.<name>.url` 은 `@SqsListener(queue = "<name>")` 에서 논리 큐 이름을
실제 URL로 바꾸는 alias 설정이다. `SqsOperations.getQueueUrl("<name>")` 은 여전히
AWS SQS `GetQueueUrl` 요청을 수행한다.
Secrets Manager 와 Parameter Store source 는 `EnvironmentPostProcessor` 로 일반 bean
binding 전에 로드된다. `refresh-interval` 을 설정하면 interval 이 지난 뒤 property
접근 시점에 lazy reload 하며, reload 실패 시에는 이전 값을 유지한다. 여러 원격
source 가 같은 key 를 제공하면 먼저 설정된 source 가 더 높은 Spring property-source
우선순위를 가진다.

## 사용 예제

### Secrets Manager / Parameter Store — Environment 값

```kotlin
import io.bluetape4k.aws.spring.parameterstore.ParameterStoreValue
import io.bluetape4k.aws.spring.secretsmanager.SecretsValue
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.db")
data class DatabaseProperties(
    val username: String,
    val password: String,
)

class DatabaseTokenReader(
    @SecretsValue("\${app.db.password}") private val password: String,
    @ParameterStoreValue("\${app.db.username}") private val username: String,
)
```

JSON secret 은 dot notation 으로 flatten 된다. 설정한 path 아래의 parameter 이름은
dot-separated key 로 매핑되고, 두 source 모두 `prefix` 를 앞에 붙인다.
`@SecretsValue` 와 `@ParameterStoreValue` 는 일반 Spring `@Value` placeholder 문법을
사용한다.

### S3 — Coroutines 템플릿

```kotlin
import io.bluetape4k.aws.spring.s3.S3Operations
import io.bluetape4k.aws.spring.s3.S3TransferOperations
import java.nio.file.Path

class DocumentStorage(
    private val s3: S3Operations,
    private val transfer: S3TransferOperations,
) {
    suspend fun save(bucket: String, key: String, contents: String) {
        s3.upload(bucket, key, contents)
    }

    suspend fun load(bucket: String, key: String): String =
        s3.downloadText(bucket, key)

    fun presignedUpload(bucket: String, key: String) =
        s3.presignPut(bucket, key, contentType = "application/json")

    suspend fun saveLargeFile(bucket: String, key: String, source: Path) {
        transfer.uploadFile(bucket, key, source)
    }
}
```

`S3Operations` 는 upload/download, resource, list, presigned URL 을 위한 기본
small-object API 다. `S3TransferOperations` 는
`software.amazon.awssdk:s3-transfer-manager` 가 classpath 에 있고
`bluetape4k.aws.s3.transfer.enabled=true`(기본값)일 때만 활성화된다. 내부에서는
`aws` 모듈의 coroutine `S3TransferManager` 확장을 사용해 multipart file/byte
transfer 를 수행한다. CRT-backed transfer 가 필요하면 CRT-backed `S3AsyncClient`
bean 을 제공하면 된다. transfer manager auto-configuration 은 그 bean 을 재사용하므로
기본 S3 사용자에게 CRT dependency 를 강제하지 않는다.

### SQS — 송수신

```kotlin
import io.bluetape4k.aws.spring.sqs.SqsOperations

class OrderQueue(private val sqs: SqsOperations) {
    suspend fun publish(json: String) {
        val url = sqs.getQueueUrl("orders")
        sqs.send(url, json)
    }

    suspend fun drain(): List<String> {
        val url = sqs.getQueueUrl("orders")
        return sqs.receive(url, maxMessages = 10).map { it.body }
    }
}
```

### SNS — Publish, SMS, HTTP endpoint message

```kotlin
import io.bluetape4k.aws.spring.sns.SnsHttpMessageParser
import io.bluetape4k.aws.spring.sns.SnsHttpMessageType
import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sns.SnsPublishRequest
import io.bluetape4k.aws.spring.sns.SnsSmsRequest
import io.bluetape4k.aws.spring.sns.SnsSmsType

class OrderNotifications(private val sns: SnsOperations) {
    suspend fun publishOrder(topicArn: String, json: String) {
        sns.publish(SnsPublishRequest(topicArn = topicArn, message = json))
    }

    suspend fun sendSms(phoneNumber: String, text: String) {
        sns.publishSms(
            SnsSmsRequest(
                phoneNumber = phoneNumber,
                message = text,
                smsType = SnsSmsType.TRANSACTIONAL,
                senderId = "BLUETAPE",
            )
        )
    }

    suspend fun handleHttpEndpoint(body: String, messageTypeHeader: String?) {
        val message = SnsHttpMessageParser.parse(body, messageTypeHeader)
        // 여기서 Signature, SigningCertURL, SignatureVersion, expected TopicArn 검증.
        when (message.type) {
            SnsHttpMessageType.SUBSCRIPTION_CONFIRMATION,
            SnsHttpMessageType.UNSUBSCRIBE_CONFIRMATION -> sns.confirmSubscription(message)
            SnsHttpMessageType.NOTIFICATION -> processNotification(message.message)
        }
    }

    private fun processNotification(message: String) = Unit
}
```

SNS 는 queue policy 가 topic ARN 의 `sqs:SendMessage` 를 허용하면 SQS subscription 으로
fanout 할 수 있다. `SnsHttpMessageParser` 는 SNS HTTP JSON 과 선택적
`x-amz-sns-message-type` header 를 매핑하고, HTTPS가 아니거나 SNS host가 아닌
`SigningCertURL` 은 거부한다. Signature 검증은 수행하지 않으므로 notification 처리나
subscription confirmation 전에 certificate chain, signature, signature version, 기대한
`TopicArn` 을 검증해야 한다.

### SQS — `@SqsListener` 어노테이션

```kotlin
import io.bluetape4k.aws.spring.sqs.SqsListener
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import org.springframework.stereotype.Component

@Component
class OrderListener {
    @SqsListener(queue = "orders", maxMessages = 10, waitTimeSeconds = 20)
    suspend fun onMessage(message: SqsReceivedMessage) {
        // 처리. 예외 throw 시 재배달.
    }
}
```

리스너 메서드는 `String`, AWS SDK `Message`, `SqsReceivedMessage` 중 하나를
인자로 받을 수 있다. `queue` 에는 SpEL 을 지원하지 않으며 `${...}` 플레이스홀더는
지원한다.
`bluetape4k.aws.sqs.queues.orders.url` 을 설정하면 `queue = "orders"` 는 해당 URL을
직접 사용한다.
리스너 ack 는 성공 시 삭제 방식이다. 리스너 메서드가 정상 반환된 뒤에만 메시지를
삭제하고, 예외가 발생하면 삭제하지 않는다. `error-visibility-timeout-seconds` 를
설정하면 실패 메시지의 visibility 를 명시적으로 바꿔 재시도 타이밍을 제어한다.
`stop-timeout-millis` 는 poller 취소 후 컨테이너 종료 대기 시간을 제한한다.

FIFO 큐 메타데이터는 수신 시 `SqsReceivedMessage` 에 유지된다. FIFO 메시지는
`SqsSendRequest` 로 group/deduplication ID 를 지정해 발송한다.

```kotlin
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.aws.spring.sqs.SqsSendRequest

suspend fun publishOrder(sqs: SqsOperations, queueUrl: String, body: String) {
    sqs.send(
        SqsSendRequest(
            queueUrl = queueUrl,
            body = body,
            messageGroupId = "orders",
            messageDeduplicationId = "order-123",
        )
    )
}
```

`SqsReceivedMessage.messageGroupId`, `messageDeduplicationId`, `sequenceNumber`,
`approximateReceiveCount`, `messageAttributes` 로 FIFO 및 재시도 처리에 필요한 SQS
메타데이터를 읽을 수 있다.

### DynamoDB — Coroutines Repository

```kotlin
import io.bluetape4k.aws.spring.dynamodb.AbstractCoroutinesDynamoDbRepository
import io.bluetape4k.aws.spring.dynamodb.DynamoDbTableNameResolver
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.enhanced.dynamodb.Key

class OrderRepository(
    enhancedClient: DynamoDbEnhancedAsyncClient,
    tableNameResolver: DynamoDbTableNameResolver,
): AbstractCoroutinesDynamoDbRepository<Order, String>(
    enhancedClient = enhancedClient,
    tableNameResolver = tableNameResolver,
    entityClass = Order::class.java,
) {
    override val tableName: String = "orders"

    override fun keyFromId(id: String): Key =
        Key.builder().partitionValue(id).build()
}
```

`aws-spring-boot` 은 DynamoDB 테이블을 자동 생성하지 않는다. 마이그레이션,
배포 자동화, 또는 테스트 셋업에서 명시적으로 테이블을 만들어야 한다.

### KMS — 명시적 필드 암호화

`@KmsEncrypted` 는 mapper/converter 경계에서 사용하는 metadata 이다. DTO,
entity, configuration properties, 기존 plaintext 데이터를 투명하게 변경하지 않는다.
첫 지원 타입은 `String`/`String?` 이다.

```kotlin
import io.bluetape4k.aws.spring.kms.KmsEncrypted
import io.bluetape4k.aws.spring.kms.KmsEncryptedFieldCodec

data class CustomerSecret(
    @field:KmsEncrypted(encryptionContext = ["field=ssn"])
    val ssn: String?,
)

class CustomerSecretMapper(private val codec: KmsEncryptedFieldCodec) {
    private val ssnField = CustomerSecret::class.java.getDeclaredField("ssn")
    private val ssnEncryption = ssnField.getAnnotation(KmsEncrypted::class.java)

    suspend fun toStored(secret: CustomerSecret): String? {
        codec.validate(ssnField)
        return codec.encrypt(secret.ssn, ssnEncryption)
    }

    suspend fun fromStored(ciphertext: String?): CustomerSecret =
        CustomerSecret(codec.decrypt(ciphertext, ssnEncryption))
}
```

Ciphertext 문자열은 `b4k-kms:v1:` prefix 를 사용한다. 잘못된 ciphertext, 지원하지
않는 field type, 누락된 key id, KMS 복호화 실패는 결정적인 예외로 실패한다. 서비스
단위 payload 나 envelope encryption 은 직접 `KmsOperations` 를 사용하고, 필드
암호화는 짧은 단일 `String` 이 안정적인 persistence/serialization 경계를 가져야 할 때
사용한다.

## 테스트

`src/test/...` 에 LocalStack 기반 통합 테스트가 포함되어 있다. opt-in 으로 실행:

```bash
./gradlew :aws-spring-boot:test -Dbluetape4k.aws.emulator=localstack
```
