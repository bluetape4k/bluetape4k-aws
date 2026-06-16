# Module bluetape4k-aws-spring-boot

[English](README.md) | 한국어

AWS Java SDK v2를 Spring Boot 4에서 바로 쓰기 위한 자동 설정 모듈입니다.
Coroutine 우선 템플릿, SQS 리스너 컨테이너, CloudWatch metric/log helper,
EC2 IMDS metadata operation, 원격 Environment source, AWS-backed Exposed
데이터베이스 연결을 제공합니다. `awspring` 런타임 의존성은 사용하지 않습니다.

## 다이어그램

### 모듈 아키텍처

![AWS Spring Boot architecture diagram](../docs/images/readme-diagrams/aws-spring-boot-architecture-01.png)

### 설정 흐름

![AWS Spring Boot configuration flow diagram](../docs/images/readme-diagrams/aws-spring-boot-flow-02.png)

### SQS 리스너 시퀀스

![AWS Spring Boot SQS listener sequence diagram](../docs/images/readme-diagrams/aws-spring-boot-sequence-03.png)

## 주요 기능

- **S3** — `S3CoroutinesTemplate`로 버킷 존재 확인, 업로드/다운로드(바이트·문자열),
  삭제, 페이지 단위 조회(`listPage`/`listFlow`), Spring `Resource` 뷰, presigned
  GET/PUT URL 발급을 지원합니다.
- **S3 Vectors** — 선택적 `S3VectorsOperations`로 vector bucket/index 조회와
  vector put/get/list/query 호출을 지원합니다.
- **SNS** — `SnsCoroutinesTemplate`로 topic 생성/조회, topic publish, FIFO publish
  필드, 직접 SMS publish 옵션, HTTP(S) notification JSON 파싱과 token 기반 subscription
  confirmation을 지원합니다.
- **SES** — `SesCoroutinesMailSender`로 simple, template, raw, attachment,
  custom-header email send를 지원하고, 선택적 Spring `JavaMailSender` adapter를 제공합니다.
- **SQS** — `SqsCoroutinesTemplate`로 큐 조회·생성, 송신, 수신, visibility 변경,
  cold `Flow<SqsReceivedMessage>` 스트림을 제공합니다.
- **SQS 리스너** — `@SqsListener` 어노테이션 기반 Coroutine 메시지 리스너 컨테이너입니다.
  동시 처리 수와 visibility/error-visibility 타임아웃을 속성으로 조정합니다.
- **DynamoDB** — `CoroutinesDynamoDbRepository<T, ID>` 추상 베이스가
  `DynamoDbAsyncTable` 위에서 `save`/`findById`/`update`/`delete`와
  `scan`/`query`/`queryIndex`의 `Flow` 결과를 제공합니다. 논리 테이블 이름은
  `DynamoDbTableNameResolver`(기본 구현은 `tablePrefix` 적용)로 해석되며,
  async client는 선택적으로 DynamoDB Accelerator(DAX)로 구성할 수 있습니다.
- **CloudWatch / CloudWatch Logs** — `CloudWatchCoroutinesTemplate`과
  `CloudWatchLogsCoroutinesTemplate`로 coroutine metric/log publishing을 제공하고,
  Micrometer가 있을 때 application `MeterRegistry`를 읽는 선택적
  `CloudWatchMeterPublishingOperations` helper를 제공합니다.
- **EC2 IMDS** — `ImdsOperations`가 AWS SDK v2 IMDS 호출을 coroutine method와
  operation timeout으로 감싸 EC2 instance metadata 조회를 제공합니다.
- **KMS** — `KmsOperations`로 coroutine 암호화/복호화와 data key 생성을
  제공하고, 선택적 Spring Security `TextEncryptor`, `String` 필드용 명시적
  `@KmsEncrypted` + `KmsEncryptedFieldCodec`를 지원합니다.
- **S3 / Secrets Manager / Parameter Store config** — S3 object, 원격 secret/parameter를
  시작 시점에 Spring Environment로 로드합니다. 선택적 lazy refresh와 Spring `@Value` 기반
  `@SecretsValue` / `@ParameterStoreValue` 조합 어노테이션도 제공합니다.
- **Exposed 데이터베이스** — 명시적 속성 또는 Secrets Manager / Parameter Store 로
  로드한 Environment 값으로 AWS-backed `AwsExposedDatabaseRegistry`, 기본 Exposed
  `Database`, 기본 `DataSource`를 자동 설정합니다.
- **awspring 런타임 의존성 없음** — AWS SDK v2 서비스는 모두 `compileOnly`로
  선언되어 있어, 애플리케이션은 실제로 쓰는 서비스만 골라 추가하면 됩니다.

## 의존성

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot:${bluetape4kAwsVersion}")

    // AWS-backed Exposed 데이터베이스 자동 설정을 사용할 때만 추가
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-exposed:${bluetape4kAwsVersion}")

    // 런타임에서 사용할 AWS SDK v2 서비스만 선택적으로 추가
    implementation(platform("software.amazon.awssdk:bom:${awsSdkVersion}"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:s3vectors")
    implementation("software.amazon.awssdk:sesv2")
    implementation("software.amazon.awssdk:sns")
    implementation("software.amazon.awssdk:sqs")
    implementation("software.amazon.awssdk:sts") // 선택적 web-identity credentials 지원
    implementation("software.amazon.awssdk:dynamodb-enhanced")
    implementation("software.amazon.awssdk:cloudwatch")
    implementation("software.amazon.awssdk:cloudwatchlogs")
    implementation("software.amazon.awssdk:imds")
    implementation("software.amazon.awssdk:kms")
    implementation("software.amazon.awssdk:secretsmanager")
    implementation("software.amazon.awssdk:ssm")

    // Spring JavaMailSender adapter 를 사용할 때만 필요
    implementation("org.eclipse.angus:angus-mail")
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
    enabled: true
    region: ap-northeast-2
    endpoint-override: http://localhost:4566   # 공유 local AWS emulator 기본값
    credentials:
      web-identity:
        enabled: false                          # software.amazon.awssdk:sts 필요
        role-arn: arn:aws:iam::123456789012:role/order-api
        role-session-name: order-api
        token-file: /var/run/secrets/eks.amazonaws.com/serviceaccount/token
    s3:
      enabled: true
      region: ap-northeast-2                   # 공유 기본값보다 우선
      endpoint-override: http://localhost:4566 # 공유 기본값보다 우선
      path-style-access-enabled: true
      presign:
        duration: PT15M
      config:
        enabled: true
        region: ap-northeast-2
        endpoint-override: http://localhost:4566
        path-style-access-enabled: true
        refresh-interval: 30s
        sources:
          - name: app-s3-config
            bucket: order-config
            key: application.properties
            prefix: app
            format: properties
      client-side-encryption:
        enabled: true
        key-id: alias/app-s3
        encryption-context:
          service: order-api
    s3-vectors:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
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
        retry:
          max-attempts: 2
          initial-backoff: 100ms
          max-backoff: 2s
          multiplier: 2.0
          jitter-ratio: 0.2
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
    ses:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      default-from: no-reply@example.com
      configuration-set-name: app-prod
      java-mail-sender:
        enabled: true
    dynamodb:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      table-prefix: local-
    cloudwatch:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      namespace: OrderApi
      batch-size: 1000
      micrometer:
        enabled: true
    cloudwatch-logs:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      log-group-name: /aws/app/order-api
      log-stream-name: local
      batch-size: 10000
    imds:
      enabled: true
      endpoint-mode: ipv4
      token-ttl: PT6H
      request-timeout: 1s
      retries: 0
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
    exposed:
      enabled: true
      default-database:
        url: jdbc:postgresql://localhost:5432/orders
        driver-class-name: org.postgresql.Driver
        username: order_app
        password: ${app.db.password}
        pool:
          maximum-pool-size: 10
          minimum-idle: 1
      named-databases:
        analytics:
          url: jdbc:postgresql://localhost:5432/analytics
          driver-class-name: org.postgresql.Driver
          username: analytics_app
          password: ${app.analytics.password}
```

`bluetape4k.aws.region`과 `bluetape4k.aws.endpoint-override`는 자동 설정되는
AWS SDK v2 client의 공유 기본값입니다. `bluetape4k.aws.s3.region`이나
`bluetape4k.aws.sqs.endpoint-override` 같은 서비스별 속성이 공유 기본값보다
우선합니다. 실제 적용되는 `endpoint-override`가 있으면 region도 함께 필요합니다.
`bluetape4k.aws.credentials.web-identity.enabled=true`는 런타임 classpath에
`software.amazon.awssdk:sts`가 있을 때 선택적으로
`WebIdentityTokenFileCredentialsProvider`를 등록합니다. 조건이 맞지 않으면 AWS SDK
기본 credentials provider chain을 사용합니다.
생성되는 AWS SDK v2 builder를 조정하려면 ordered `AwsSyncClientCustomizer`,
`AwsAsyncClientCustomizer`, 또는 typed
`AwsClientCustomizer<S3ClientBuilder>` / `AwsClientCustomizer<SqsAsyncClientBuilder>`
bean을 등록합니다.
`sns.topics.<name>`은 `SnsOperations.createConfiguredTopic("<name>")`에서 사용하는
topic 생성 기본값입니다.
`sqs.queues.<name>.url`은 `@SqsListener(queue = "<name>")`에서 논리 큐 이름을
실제 URL로 바꾸는 alias 설정입니다. `SqsOperations.getQueueUrl("<name>")`은 여전히
AWS SQS `GetQueueUrl` 요청을 수행합니다.
`cloudwatch.namespace`는 기본 namespace metric publishing method에서 사용됩니다.
Micrometer helper는 application `MeterRegistry` bean이 있을 때만 등록되며,
명시적 method 호출 시점에 meter snapshot을 읽습니다. 활성 registry를 대체하지는 않습니다.
`cloudwatch-logs.log-group-name`과 `cloudwatch-logs.log-stream-name`은 기본 log-event
publishing method에서 사용됩니다.
`imds.request-timeout`은 각 metadata operation을 제한합니다. IMDS bean 생성은 metadata
endpoint를 호출하지 않으므로 EC2가 아닌 환경에서도 startup probe 비용이 없습니다.
credential 조회는 AWS SDK default provider chain 또는 STS web identity에 맡기고,
`ImdsOperations`는 안전한 metadata helper만 노출합니다.
S3 config, Secrets Manager, Parameter Store source는 `EnvironmentPostProcessor`로
일반 bean binding 전에 로드됩니다. S3 config source는 단일 object를 `properties`,
`yaml`, `json` 형식으로 읽습니다. `auto` 형식은 object key 확장자로 parser를 고르고,
알 수 없으면 `properties`로 처리합니다. `refresh-interval`을 설정하면 interval이
지난 뒤 property 접근 시점에 lazy reload하며, reload 실패 시에는 이전 값을 유지합니다.
여러 원격 source가 같은 key를 제공하면 먼저 설정된 source가 더 높은 Spring
property-source 우선순위를 가집니다.
`bluetape4k.aws.exposed.default-database.url`이 있을 때 Exposed registry가
활성화됩니다. URL이 없으면 Exposed auto-configuration은 property binding만 제공하고
registry나 database pool은 만들지 않습니다.

## 사용 예제

### S3 / Secrets Manager / Parameter Store — Environment 값

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

JSON secret은 dot notation으로 flatten됩니다. 설정한 path 아래의 parameter 이름은
dot-separated key로 매핑되고, 두 source 모두 `prefix`를 앞에 붙입니다.
`@SecretsValue`와 `@ParameterStoreValue`는 일반 Spring `@Value` placeholder 문법을
사용합니다.
S3 config JSON object도 같은 방식으로 flatten됩니다. S3 `.properties`와 YAML object는
Spring Boot property-source loader로 읽은 뒤 설정된 `prefix`를 앞에 붙입니다.

### Exposed — AWS-backed database registry

```kotlin
import io.bluetape4k.aws.exposed.AwsExposedDatabaseRegistry
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import javax.sql.DataSource

class OrderQueryService(
    private val database: Database,
    private val dataSource: DataSource,
    private val registry: AwsExposedDatabaseRegistry,
) {
    fun countOrders(): Long =
        transaction(database) {
            // 여기서 bluetape4k-exposed repository 또는 Exposed DSL 을 사용한다.
            // Orders 는 애플리케이션의 Exposed Table object 다.
            Orders.selectAll().count()
        }

    fun analyticsDatabase(): Database =
        registry.get("analytics").database

    fun defaultDataSource(): DataSource =
        dataSource
}
```

Spring adapter는 `bluetape4k-aws-exposed`를 통해 registry를 만들고, 애플리케이션이
직접 제공한 bean이 없을 때 default handle을 Spring `DataSource`와 Exposed
`Database`로 노출합니다. Named database는 `AwsExposedDatabaseRegistry`로 조회합니다.
Secrets Manager나 Parameter Store 값을 데이터베이스 설정으로 사용하려면 source의
`prefix`를 `bluetape4k.aws.exposed.default-database`처럼 지정하면 됩니다. 원격에서
로드된 key는 registry 생성 전에 Spring Environment로 들어와 동일한 설정 prefix에
binding됩니다. Pool lifecycle은 registry가 소유하므로 alias bean은 pool을 별도로
닫지 않습니다.

### S3 — Coroutines 템플릿

```kotlin
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionOperations
import io.bluetape4k.aws.spring.s3.S3Operations
import io.bluetape4k.aws.spring.s3.S3TransferOperations
import java.nio.file.Path

class DocumentStorage(
    private val s3: S3Operations,
    private val transfer: S3TransferOperations,
    private val encryptedS3: S3ClientSideEncryptionOperations,
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

    suspend fun saveSecret(bucket: String, key: String, contents: String) {
        encryptedS3.uploadEncrypted(
            bucket = bucket,
            key = key,
            bytes = contents.encodeToByteArray(),
            contentType = "text/plain",
        )
    }
}
```

`S3Operations`는 upload/download, resource, list, presigned URL을 위한 기본
small-object API입니다. `S3TransferOperations`는
`software.amazon.awssdk:s3-transfer-manager`가 classpath에 있고
`bluetape4k.aws.s3.transfer.enabled=true`(기본값)일 때만 활성화됩니다. 내부에서는
`aws` 모듈의 coroutine `S3TransferManager` 확장을 사용해 multipart file/byte
transfer를 수행합니다. CRT-backed transfer가 필요하면 CRT-backed `S3AsyncClient`
bean을 제공하면 됩니다. transfer manager auto-configuration은 그 bean을 재사용하므로
기본 S3 사용자에게 CRT dependency를 강제하지 않습니다.

`S3ClientSideEncryptionOperations`는
`bluetape4k.aws.s3.client-side-encryption.enabled=true`이고 `KmsOperations`
bean이 있을 때 활성화됩니다. AWS KMS data key를 생성하고 object byte를 로컬에서
AES-GCM으로 암호화한 뒤 encrypted data key와 nonce를 S3 metadata에 저장합니다.
이 helper는 byte-array object용입니다. multipart 또는 streaming client-side
encryption은 지원하지 않으며, metadata format은 AWS Encryption SDK와 호환되지 않습니다.

### S3 Vectors — Spring Boot operations

S3 Vectors 지원은 기본적으로 비활성화되어 있고 별도 AWS SDK v2 `s3vectors` 서비스를
사용합니다. 활성화하는 애플리케이션은 runtime service dependency를 추가해야 합니다.

```kotlin
runtimeOnly("software.amazon.awssdk:s3vectors")
```

```yaml
bluetape4k:
  aws:
    s3-vectors:
      enabled: true
      region: us-east-1
```

```kotlin
import io.bluetape4k.aws.s3vectors.S3VectorsOperations
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsRequest

class SemanticSearch(
    private val s3Vectors: S3VectorsOperations,
) {
    suspend fun query(request: QueryVectorsRequest) =
        s3Vectors.queryVectors(request)
}
```

자동설정은 `bluetape4k.aws.s3-vectors.enabled=true`이고 `s3vectors` SDK가 있을 때만
`S3VectorsAsyncClient`와 `S3VectorsOperations`를 등록합니다. 이 설정은 LocalStack,
Floci, Ministack의 S3 Vectors 동작을 보장하지 않습니다.

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

### SES — Simple, Template, Raw, JavaMail 발송

```kotlin
import io.bluetape4k.aws.spring.ses.SesEmailAddressSet
import io.bluetape4k.aws.spring.ses.SesEmailAttachment
import io.bluetape4k.aws.spring.ses.SesEmailBody
import io.bluetape4k.aws.spring.ses.SesEmailRequest
import io.bluetape4k.aws.spring.ses.SesOperations
import io.bluetape4k.aws.spring.ses.SesTemplateEmailRequest
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

class OrderEmail(
    private val ses: SesOperations,
    private val javaMailSender: JavaMailSender,
) {
    suspend fun sendReceipt(orderId: String, pdf: ByteArray) {
        ses.sendEmail(
            SesEmailRequest(
                destination = SesEmailAddressSet(to = listOf("customer@example.com")),
                subject = "Receipt $orderId",
                body = SesEmailBody(html = "<p>Your receipt is attached.</p>"),
                headers = mapOf("X-Order-Id" to orderId),
                attachments = listOf(
                    SesEmailAttachment(
                        fileName = "receipt-$orderId.pdf",
                        content = pdf,
                        contentType = "application/pdf",
                    )
                ),
            )
        )
    }

    suspend fun sendWelcomeTemplate() {
        ses.sendTemplateEmail(
            SesTemplateEmailRequest(
                destination = SesEmailAddressSet(to = listOf("customer@example.com")),
                templateName = "welcome",
                templateData = """{"name":"Bluetape"}""",
            )
        )
    }

    fun sendWithSpringMail() {
        javaMailSender.send(
            SimpleMailMessage().apply {
                setTo("customer@example.com")
                subject = "Hello"
                text = "Welcome."
            }
        )
    }
}
```

`SesOperations`는 convenience request에 `bluetape4k.aws.ses.default-from`과
`configuration-set-name` 기본값을 적용합니다. 하위 수준 `send(SendEmailRequest)`는
AWS SDK request를 그대로 전송합니다. JavaMail adapter는 Spring `JavaMailSender`,
Jakarta Mail, Angus Mail provider가 런타임 classpath에 있을 때만 등록됩니다.

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

SNS는 queue policy가 topic ARN의 `sqs:SendMessage`를 허용하면 SQS subscription으로
fanout할 수 있습니다. `SnsHttpMessageParser`는 SNS HTTP JSON과 선택적
`x-amz-sns-message-type` header를 매핑하고, HTTPS가 아니거나 SNS host가 아닌
`SigningCertURL`은 거부합니다. Signature 검증은 수행하지 않으므로 notification 처리나
subscription confirmation 전에 certificate chain, signature, signature version, 기대한
`TopicArn`을 검증해야 합니다.

### SQS — `@SqsListener` 어노테이션

```kotlin
import io.bluetape4k.aws.spring.sqs.SqsListener
import io.bluetape4k.aws.spring.sqs.SqsAcknowledgement
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import org.springframework.stereotype.Component

data class OrderEvent(val id: String, val total: Long)

@Component
class OrderListener {
    @SqsListener(queue = "orders", maxMessages = 10, waitTimeSeconds = 20)
    suspend fun onMessage(message: SqsReceivedMessage) {
        // 처리. 예외 throw 시 재배달.
    }

    @SqsListener(queue = "orders-json")
    suspend fun onTypedMessage(event: OrderEvent, acknowledgement: SqsAcknowledgement) {
        process(event)
        acknowledgement.acknowledge()
    }
}
```

리스너 메서드는 `String`, AWS SDK `Message`, `SqsReceivedMessage`, 또는
`SqsMessageConverter` bean이 있을 때 typed payload를 인자로 받을 수 있습니다. Jackson 3
`ObjectMapper`가 있으면 converter가 자동 등록됩니다. `SqsAcknowledgement`를 선언하면
manual acknowledgement 모드가 되어 handler가 `acknowledge()`를 호출할 때만 메시지를
삭제합니다. `queue`에는 SpEL을 지원하지 않으며 `${...}` 플레이스홀더는 지원합니다.
`bluetape4k.aws.sqs.queues.orders.url`을 설정하면 `queue = "orders"`는 해당 URL을
직접 사용합니다.
리스너 ack는 성공 시 삭제 방식입니다. 리스너 메서드가 정상 반환된 뒤에만 메시지를
삭제하고, 예외가 발생하면 삭제하지 않습니다. `error-visibility-timeout-seconds`를
설정하면 실패 메시지의 visibility를 명시적으로 바꿔 재시도 타이밍을 제어합니다.
`listener.retry`는 최종 실패 처리 전에 in-process retry를 수행하며 linear/exponential
backoff와 optional jitter를 지원합니다. `SqsListenerInterceptor` bean을 등록하면
receive, handler, ack/nack, failure 단계를 Micrometer나 logging/tracing library로
관찰할 수 있습니다. `stop-timeout-millis`는 poller 취소 후 컨테이너 종료 대기 시간을 제한합니다.

FIFO 큐 메타데이터는 수신 시 `SqsReceivedMessage`에 유지됩니다. FIFO 메시지는
`SqsSendRequest`로 group/deduplication ID를 지정해 발송합니다.

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
`approximateReceiveCount`, `messageAttributes`로 FIFO 및 재시도 처리에 필요한 SQS
메타데이터를 읽을 수 있습니다.

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

`aws-spring-boot`는 DynamoDB 테이블을 자동 생성하지 않습니다. 마이그레이션,
배포 자동화, 또는 테스트 셋업에서 명시적으로 테이블을 만들어야 합니다.

DynamoDB Accelerator(DAX)는 사용하는 애플리케이션에 DAX runtime dependency가 있을
때만 활성화한다.

```kotlin
runtimeOnly("software.amazon.dax:amazon-dax-client:2.0.9")
```

```yaml
bluetape4k:
  aws:
    dynamodb:
      region: us-east-1
      dax:
        enabled: true
        url: dax://orders-cache.abc123.dax-clusters.us-east-1.amazonaws.com
        connect-timeout: 1s
        request-timeout: 1s
        read-retries: 2
        write-retries: 2
```

DAX가 활성화되면 auto-configuration은 DAX-backed `DynamoDbAsyncClient`를
제공하고 기존 `DynamoDbEnhancedAsyncClient`와 repository base class는 그대로
사용됩니다. DAX는 실제 AWS cluster cache이며 emulator 기능이 아닙니다. LocalStack,
Floci, DynamoDB Local 테스트는 일반 DynamoDB client 경로를 유지하고, DAX cache
consistency 가정은 애플리케이션 boundary에 문서화합니다.

### CloudWatch — Metrics, Logs, Micrometer

```kotlin
import io.bluetape4k.aws.cloudwatch.model.metricDatumOf
import io.bluetape4k.aws.cloudwatch.model.cloudwatchlogs.inputLogEventOf
import io.bluetape4k.aws.spring.cloudwatch.CloudWatchLogsOperations
import io.bluetape4k.aws.spring.cloudwatch.CloudWatchMeterPublishingOperations
import io.bluetape4k.aws.spring.cloudwatch.CloudWatchOperations
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit

class OrderObservability(
    private val cloudWatch: CloudWatchOperations,
    private val cloudWatchLogs: CloudWatchLogsOperations,
    private val meters: CloudWatchMeterPublishingOperations,
) {
    suspend fun publishOrderProcessed(orderId: String) {
        cloudWatch.putMetricDatum(
            metricDatumOf("OrderProcessed", 1.0, StandardUnit.COUNT)
        )
        cloudWatchLogs.putLogEvents(
            listOf(inputLogEventOf(System.currentTimeMillis(), "processed order=$orderId"))
        )
        meters.publishMeter("orders.processed")
    }
}
```

`bluetape4k-aws-spring-boot`는 Spring Boot 애플리케이션의 관측성 baseline에 맞춰
`micrometer-core`를 일반 의존성으로 포함합니다. 단,
`micrometer-registry-cloudwatch`를 자동 설정하지는 않습니다. registry 수준의
scheduled publishing이 필요하면 애플리케이션에서 해당 registry를 추가합니다. 내장
helper는 현재 `MeterRegistry`를 읽어 명시적으로 한 번 publish하는 snapshot
publisher입니다.

`MeterRegistry` bean이 있으면 low-cardinality SQS/S3 operation timer도 자동으로
등록됩니다. SQS instrumentation은 send, receive, listener handler, acknowledgement,
failure phase를 다룹니다. S3 instrumentation은 upload, download, delete, list,
resource, presign operation 을 다룹니다. Queue URL, message ID, receipt handle,
object key, raw exception message 는 기본 tag 로 사용하지 않습니다.

### EC2 IMDS — Metadata Operations

```kotlin
import io.bluetape4k.aws.spring.imds.ImdsOperations

class InstanceMetadataReporter(
    private val imds: ImdsOperations,
) {
    suspend fun snapshot(): Map<String, String> =
        mapOf(
            "instanceId" to imds.instanceId(),
            "instanceType" to imds.instanceType(),
            "region" to imds.region(),
            "availabilityZone" to imds.availabilityZone(),
        )

    suspend fun roleNames(): List<String> =
        imds.iamRoleNames()
}
```

`ImdsOperations`는 AWS SDK v2 `Ec2MetadataAsyncClient`로 위임하고 각 호출을 설정된
timeout으로 감쌉니다. Spring startup 중에는 수동적이며, EC2에서 실행되는 애플리케이션의
instance metadata 조회 용도로만 사용합니다. IAM role credential document는 노출하지
않습니다. 애플리케이션 credential은 `DefaultCredentialsProvider`, STS web identity,
또는 명시적 AWS SDK credentials provider에 맡깁니다.

### KMS — 명시적 필드 암호화

`@KmsEncrypted`는 mapper/converter 경계에서 사용하는 metadata입니다. DTO,
entity, configuration properties, 기존 plaintext 데이터를 투명하게 변경하지 않습니다.
첫 지원 타입은 `String`/`String?`입니다.

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

Ciphertext 문자열은 `b4k-kms:v1:` prefix를 사용합니다. 잘못된 ciphertext, 지원하지
않는 field type, 누락된 key id, KMS 복호화 실패는 결정적인 예외로 실패합니다. 서비스
단위 payload나 envelope encryption은 직접 `KmsOperations`를 사용하고, 필드
암호화는 짧은 단일 `String`이 안정적인 persistence/serialization 경계를 가져야 할 때
사용합니다.

## 테스트

`src/test/...`에 로컬 AWS emulator 기반 통합 테스트가 포함되어 있습니다. 기본값은
Floci이며 `-Dbluetape4k.aws.emulator=...`로 전환할 수 있습니다:

```bash
./gradlew :bluetape4k-aws-spring-boot:test -Dbluetape4k.aws.emulator=floci
./gradlew :bluetape4k-aws-spring-boot:test -Dbluetape4k.aws.emulator=ministack
```
