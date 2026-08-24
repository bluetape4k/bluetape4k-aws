# Module bluetape4k-aws-spring-boot

[English](README.md) | 한국어

AWS Java SDK v2를 Spring Boot 4에서 바로 쓰기 위한 자동 설정 모듈입니다.
Coroutine 우선 템플릿, SQS 리스너 컨테이너, CloudWatch metric/log helper,
EC2 IMDS metadata operation, Kinesis와 EventBridge operations, 원격 Environment source,
AWS-backed Exposed 데이터베이스 연결을 제공합니다. `awspring` 런타임 의존성은 사용하지
않습니다.

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
- **SNS** — `SnsCoroutinesTemplate`로 topic 생성/조회, 단건·배치 topic publish,
  FIFO publish 필드, 직접 SMS publish 옵션, HTTP(S) notification JSON 파싱과 token 기반 subscription
  confirmation을 지원합니다.
- **Kinesis** — `KinesisCoroutinesTemplate`로 stream 생성, record publish, shard
  iterator 조회, 제한된 `GetRecords` polling, single-shard cold `Flow<Record>`를
  제공합니다.
- **EventBridge** — `EventBridgeCoroutinesTemplate`로 event bus, rule, target, list,
  `PutEvents` operation을 제공하고 raw partial-failure response를 그대로 노출합니다.
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
  명시적 기준 데이터용 `CloudWatchMeterPublishingOperations` helper와 선택적 native
  Micrometer `CloudWatchMeterRegistry` exporter를 제공합니다.
- **EC2 IMDS** — `ImdsOperations`가 AWS SDK v2 IMDS 호출을 coroutine method와
  operation timeout으로 감싸 EC2 instance metadata 조회를 제공합니다.
- **KMS** — `KmsOperations`로 coroutine 암호화/복호화와 data key 생성을
  제공하고, 선택적 Spring Security `TextEncryptor`, `String` 필드용 명시적
  `@KmsEncrypted` + `KmsEncryptedFieldCodec`를 지원합니다.
- **S3 / Secrets Manager / Parameter Store config** — S3 object, 원격 secret/parameter를
  시작 시점에 Spring Environment로 로드합니다. 선택적 lazy refresh와 Spring `@Value` 기반
  `@SecretsValue` / `@ParameterStoreValue` 조합 어노테이션도 제공합니다.
- **AWS AppConfig Data ConfigData** — `aws-app-config:`으로
  application/profile/environment identifier를 import하고 properties/YAML/JSON을
  해석하며, Spring Cloud Context 없이 token 기반 runtime reload를 선택적으로 제공합니다.
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
    implementation("software.amazon.awssdk:eventbridge")
    implementation("software.amazon.awssdk:appconfigdata") // aws-app-config import에 필요
    implementation("software.amazon.awssdk:sesv2")
    implementation("software.amazon.awssdk:sns")
    implementation("software.amazon.awssdk:sns-message-manager") // SNS HTTP 서명 검증에 필요
    implementation("software.amazon.awssdk:sqs")
    implementation("software.amazon.awssdk:sts") // 선택적 web-identity credentials 지원
    implementation("software.amazon.awssdk:dynamodb-enhanced")
    implementation("software.amazon.awssdk:cloudwatch")
    implementation("software.amazon.awssdk:cloudwatchlogs")
    runtimeOnly("io.micrometer:micrometer-registry-cloudwatch2") // native scheduled export에만 필요
    implementation("software.amazon.awssdk:imds")
    implementation("software.amazon.awssdk:kinesis")
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

## Testcontainers ServiceConnection

Floci 또는 LocalStack 통합 테스트에서는 endpoint와 credentials를
`DynamicPropertySource`로 properties에 복사하던 경로를 Spring Boot의 named
`@ServiceConnection` 계약으로 옮기는 것을 권장합니다. Boot 4.1 API는 단일
service name 값을 사용합니다.

```kotlin
testImplementation(libs.spring.boot.testcontainers)
testImplementation(bt4k.bluetape4k.testcontainers)

@Container
@ServiceConnection(name = "s3")
val floci: FlociServer = FlociServer.Launcher.floci
```

이 연결은 endpoint, region, 테스트 credentials만 제공합니다. 선택적인
Testcontainers 의존성이나 annotation이 없으면 기존 service properties 경로가
properties-only fallback으로 유지됩니다. `bluetape4k.aws.emulator`는
Floci/LocalStack backend 선택자이며 resource URL의 source가 아닙니다.

이름 없는 `@ServiceConnection`은 명시적인 all-services opt-in이며 named
선언과 함께 두지 않습니다. factory는 SQS queue, SNS topic, DynamoDB table,
Kinesis stream을 만들지 않습니다. fixture가 resource를 생성하고 queue
URL/topic ARN/table name/stream name을 주입한 뒤 owner-token resource만
정리합니다.

S3 통합 테스트는 하나의 bucket 안에서만 수행하고 bucket과 object key에
`owner-token`을 포함하세요. wildcard 또는 외부 literal은 AWS 호출 전에
거부해야 합니다. 정리 순서는 fixture cleanup, application context close,
Testcontainers teardown이며 cleanup 실패는 secret-free 형태로 바꾸어
suppressed 처리하고 cancellation은 다시 전파합니다. optional linkage가
없으면 조용히 credentials fallback을 하지 않고 `FACTORY_LINKAGE` 오류를
명확히 표시합니다.

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
        message-visibility-heartbeat-interval-seconds: 20
        message-visibility-heartbeat-seconds: 60
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
    app-config:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:2772
      separator: "#"
      refresh-interval: 30s
      required-minimum-poll-interval: 15s
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
명시적 method 호출 시점에 meter 기준 데이터를 읽습니다. 활성 registry를 대체하지는 않습니다.
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
`bluetape4k.aws.enabled=false`로 설정하면 AWS 자동 구성뿐 아니라 startup
Environment source도 비활성화되어, 설정된 원격 source에 접근하지 않습니다.

### ConfigData import

Spring Boot ConfigData import는 `aws-s3:`, `aws-parameterstore:`,
`aws-secretsmanager:`, `aws-app-config:` 위치를 startup에 로드합니다. 예시는
다음과 같습니다.

```properties
spring.config.import=optional:aws-s3:/config-bucket/application.yml?prefix=app&format=yaml,aws-parameterstore:/application?prefix=app&recursive=true&withDecryption=true,optional:aws-secretsmanager:application?prefix=app&format=json
```

AppConfig Data source는 같은 속성에 세 identifier를 지정합니다.

```properties
# AWS AppConfig Data: application#profile#environment
spring.config.import=aws-app-config:orders-api#production#ap-northeast-2?format=yaml&prefix=app
```

`optional:`은 backend별 not-found 결과만 건너뜁니다. 인증, network, parsing 및
그 밖의 service 오류는 startup을 실패시킵니다. 같은
`bluetape4k.aws.enabled=false` 설정은 ConfigData client 생성과 원격 접근도
막습니다. AppConfig Data reload는 기본 비활성이고
`bluetape4k.aws.app-config.refresh-interval`을 지정할 때만 resource마다
fixed-delay poller 하나를 만듭니다. 빈 응답과 decode/transport 오류는 마지막 정상
map을 유지하며 `Environment`는 새 값을 읽지만 `@Value`나
`@ConfigurationProperties`는 자동 rebind하지 않습니다. 로컬 emulator는 Floci를
우선 사용하고, LocalStack은 명시적인 fallback으로 사용하세요.
전체 계약은 [runtime 운영 manual](../docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md)에서
확인할 수 있습니다.

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

### S3 — ResourceLoader와 패턴

S3 자동 설정은 exact Spring Resource protocol도 등록합니다.

```kotlin
val resource = applicationContext.getResource(
    "s3://order-config/config/application.yml",
)
```

같은 exact 형식은 `@Value("s3://order-config/config/application.yml")`로 주입할
수 있습니다. `ApplicationContext.getResources(...)`는 이 pattern resolver가 자동으로
가로채지 않습니다. `S3ResourcePatternResolver` concrete type을 직접 주입하거나,
`@Qualifier("s3ResourcePatternResolver")`를 붙인 `ResourcePatternResolver`를
주입하세요.

```kotlin
class ConfigReader(
    @Qualifier("s3ResourcePatternResolver")
    private val resources: ResourcePatternResolver,
) {
    fun yamlFiles(): Array<Resource> =
        resources.getResources("s3://order-config/config/**/*.yml")
}
```

패턴은 literal bucket 한 개, 비어 있지 않은 prefix, `*`, `?`, `**`만 지원합니다.
cross-bucket 패턴, `s3://order-config/*.json`나 `s3://order-config/**`처럼 root를
조회하는 패턴, object write/output stream은 지원하지 않습니다. 기본 bean 이름
`s3ResourcePatternResolver`는 기본 또는 custom S3 pattern 구현을 위한 예약 이름이며,
교체 구현도 이 이름을 유지해야 합니다. unrelated resolver가 이 이름을 재사용하면
안 됩니다. 반환된 stream은 caller가 닫고 resource는 owning client와
ApplicationContext가 살아 있는 동안만 사용하세요. 기본 protocol·pattern resolver만
parser guard를 제공하며, 직접 만든 `S3Resource`와 custom resolver replacement의
입력 검증 및 IAM enforcement는 caller가 책임집니다.

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

### SQS Extended Client — S3 offload opt-in

payload가 SQS `256 KiB` 제한을 넘을 수 있을 때 coroutine-native Extended
Client를 명시적으로 활성화합니다. 작은 메시지는 기존 SQS body를 유지하고,
큰 메시지는 인증된 S3 object pointer로 전달합니다.

```yaml
bluetape4k:
  aws:
    sqs:
      extended:
        enabled: true
        producer-enabled: true
        consumer-enabled: true
        default-queue-urls:
          - https://sqs.us-east-1.amazonaws.com/123456789012/orders
        default-policy:
          bucket: my-extended-payloads
          key-prefix: bluetape4k/sqs/orders
          pointer-signing-key-ref: orders
          delete-on-ack: true
```

`SqsExtendedClientOperations`의 `send`, bounded `receive`/`receiveFlow`,
identity-bound `acknowledge`, retry 가능한 `cleanup` handle을 사용합니다. 이
pointer message가 들어오는 queue에 legacy `@SqsListener` consumer를 연결하면
안 됩니다. 선택적 Jackson 3 module은 safe DTO field만 직렬화하며 raw AWS
request, body, pointer, receipt handle은 직렬화하지 않습니다. client-side
encryption은 Bluetape4k 전용 wire format이므로 AWS Java Extended Client와
상호운용되지 않습니다. 외부 publisher latency·cleanup telemetry와
heap/throughput 측정은 후속 이슈 [#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)에서
추적합니다.

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
import io.bluetape4k.aws.spring.sns.SnsHttpMessageType
import io.bluetape4k.aws.spring.sns.SnsHttpMessageVerifier
import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sns.SnsPublishRequest
import io.bluetape4k.aws.spring.sns.SnsPublishBatchEntry
import io.bluetape4k.aws.spring.sns.SnsPublishBatchRequest
import io.bluetape4k.aws.spring.sns.SnsBatchExecutionOptions
import io.bluetape4k.aws.spring.sns.SnsSmsRequest
import io.bluetape4k.aws.spring.sns.SnsSmsType

class OrderNotifications(
    private val sns: SnsOperations,
    private val verifier: SnsHttpMessageVerifier,
) {
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

    suspend fun handleHttpEndpoint(
        body: String,
        messageTypeHeader: String?,
        expectedTopicArn: String,
    ) {
        val message = verifier.verify(body, messageTypeHeader, expectedTopicArn)
        when (message.type) {
            SnsHttpMessageType.SUBSCRIPTION_CONFIRMATION,
            SnsHttpMessageType.UNSUBSCRIBE_CONFIRMATION -> sns.confirmSubscription(message)
            SnsHttpMessageType.NOTIFICATION -> processNotification(message.message)
        }
    }

    private fun processNotification(message: String) = Unit
}
```

### SNS — AWS SDK 래퍼

같은 저장소의 `bluetape4k-aws-java` 확장은 topic ARN, entry ID, 중복 ID와
`PublishBatch` 10개 제한을 검증하면서 AWS SDK 응답과 예외를 그대로 노출합니다. 동기,
`CompletableFuture`, coroutine API는 하나의 request 모델을 공유하고 AWS Kotlin SDK 래퍼는
native suspend API를 제공합니다.

```kotlin
import io.bluetape4k.aws.sns.model.publishBatchRequestEntryOf
import io.bluetape4k.aws.sns.model.publishBatchRequestOf
import io.bluetape4k.aws.sns.publishBatch
import io.bluetape4k.aws.sns.publishBatchAsync
import io.bluetape4k.aws.sns.publishBatchSuspend

val javaEntries = listOf(
    publishBatchRequestEntryOf(id = "order-001", message = "created"),
)
val request = publishBatchRequestOf(topicArn, javaEntries)

val syncResponse = snsClient.publishBatch(topicArn, javaEntries)
val futureResponse = snsAsyncClient.publishBatchAsync(request)
val suspendResponse = snsAsyncClient.publishBatchSuspend(request)
```

AWS Kotlin SDK 래퍼는 native request-entry 모델을 사용합니다.

```kotlin
import io.bluetape4k.aws.kotlin.sns.model.publishBatchRequestEntryOf
import io.bluetape4k.aws.kotlin.sns.publishBatch

val kotlinEntries = listOf(
    publishBatchRequestEntryOf(id = "order-001", message = "created"),
)
val kotlinResponse = kotlinSnsClient.publishBatch(topicArn, kotlinEntries)
```

Raw response에는 성공·실패 entry가 함께 포함될 수 있으므로 entry ID를 기준으로 대조하세요.
이 래퍼는 partial send를 재시도하거나 rollback하지 않으며 cancellation을 전파합니다(Java
coroutine 확장은 underlying future도 취소합니다). FIFO group/deduplication 값과 외부
idempotency key는 호출자의 책임입니다. payload를 숨긴 transport/protocol 예외 경계가
필요하면 아래 Spring API를 사용하세요.

#### SNS 배치 발행

`SnsCoroutinesTemplate.publishBatch`는 `SnsPublishBatchEntry`를 AWS
`PublishBatchRequestEntry`로 매핑하고 SDK 요청 하나당 최대 10개 entry를
전송합니다. `maxInFlightBatches`로 동시 요청 수를 제한할 수 있으며 빈
요청은 SDK 호출 없이 반환합니다.

```kotlin
val result = sns.publishBatch(
    SnsPublishBatchRequest(
        topicArn = topicArn,
        entries = orders.map { order ->
            SnsPublishBatchEntry(
                id = order.id,
                message = order.json,
                messageGroupId = order.groupId,       // FIFO topic에서만 사용
                messageDeduplicationId = order.deduplicationId,
            )
        },
    ),
    options = SnsBatchExecutionOptions(maxInFlightBatches = 4),
)
```

`result.successful`과 `result.failed`는 각 목록에서 입력 순서를 유지하고
대조에 사용할 entry ID를 포함합니다. 전송·프로토콜 실패는 payload를
노출하지 않는 Spring 예외로 전달하며 cancellation은 원본을 전파하고
자동 재시도는 수행하지 않습니다. FIFO group/deduplication 값과 외부
idempotency는 호출자의 책임입니다. 하위 수준 Java/Kotlin SDK API는 raw SDK
응답과 예외를 그대로 전달하고, 이 Spring template은 안전한
transport/protocol 경계를 제공합니다.

`publishBatch`를 재정의하지 않는 기존 `SnsOperations` 구현체는 additive
기본 구현을 사용합니다. 기존 단건 `publish`를 순차 호출하고 첫 번째
non-cancellation 실패에서 중단하며, 성공한 prefix만
`completedEntryIds`에 기록하고 `maxInFlightBatches`는 1로 처리합니다.
partial send를 자동 재시도하거나 rollback하지 않습니다.

앞선 chunk가 혼합 결과를 반환한 뒤 형제 chunk가 실패해도 전체 입력을
무조건 재처리하지 마세요. entry ID를 기준으로 대조하고 FIFO
deduplication 또는 외부 idempotency 저장소를 사용하며 terminal 응답이
없는 entry는 수동으로 조정해야 합니다. 비즈니스 rollback과 보상 처리는
제공하지 않습니다. Spring Cloud AWS 방식의 공개 `BatchExecutionStrategy`·converter
확장 조사는 [#514](https://github.com/bluetape4k/bluetape4k-aws/issues/514)에서
추적합니다. Publisher cleanup/latency telemetry와 heap/throughput 측정은
[#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)에서 추적합니다.

SNS는 queue policy가 topic ARN의 `sqs:SendMessage`를 허용하면 SQS subscription으로
fanout할 수 있습니다. `SnsHttpMessageParser`는 SNS HTTP JSON과 선택적
`x-amz-sns-message-type` header를 매핑하고, HTTPS가 아니거나 SNS host가 아닌
`SigningCertURL`은 거부합니다. `SnsHttpMessageVerifier`는 parser 다음,
notification 처리나 subscription confirmation 전에 실행해야 하며 Signature v1/v2,
certificate chain, SNS host 검증을 AWS SDK message manager에 위임하고 예외가 발생하면
fail-closed로 거부합니다.

### SNS HTTP 메시지 서명 검증

이 모듈은 `software.amazon.awssdk:sns-message-manager`를 `compileOnly`로 유지하므로
애플리케이션 runtime에 해당 의존성을 추가해야 합니다. 검증은 기본적으로 활성화됩니다.

```yaml
bluetape4k:
  aws:
    sns:
      verification:
        enabled: true
```

`verification.enabled=false`는 자동 구성 verifier를 제거하는 명시적 보안 opt-out이며
parser 결과만으로는 인증되지 않습니다. Floci는 서명된 SNS HTTP payload를 생성하지
않으므로 fixture 또는 manager mock으로 이 경계를 검증합니다. 인증서 요청 timeout·정리
telemetry와 실제 AWS smoke 측정은 이 계약과 분리한 후속 이슈로 추적합니다.

### Kinesis — stream operations와 record Flow

```yaml
bluetape4k:
  aws:
    kinesis:
      region: us-east-1
      streams:
        orders:
          shard-count: 1
      consumer:
        batch-limit: 100
        poll-interval: 200ms
        empty-backoff: 1s
```

```kotlin
import io.bluetape4k.aws.spring.kinesis.KinesisOperations
import io.bluetape4k.aws.spring.kinesis.KinesisPutRecordRequest
import io.bluetape4k.aws.spring.kinesis.KinesisRecordFlowRequest
import io.bluetape4k.aws.spring.kinesis.KinesisStartingPosition
import kotlinx.coroutines.flow.collect
import software.amazon.awssdk.core.SdkBytes

class OrderStream(
    private val kinesis: KinesisOperations,
) {
    suspend fun ensureStream() {
        kinesis.createConfiguredStream("orders")
    }

    suspend fun publish(payload: String): String =
        kinesis.putRecord(
            KinesisPutRecordRequest(
                streamName = "orders",
                partitionKey = "orders",
                data = SdkBytes.fromUtf8String(payload),
            )
        ).sequenceNumber()

    suspend fun consume(shardId: String) {
        kinesis.recordFlow(
            KinesisRecordFlowRequest(
                streamName = "orders",
                shardId = shardId,
                startingPosition = KinesisStartingPosition.TrimHorizon,
            )
        ).collect { record ->
            handle(record.data().asUtf8String())
        }
    }

    private fun handle(payload: String) = Unit
}
```

`KinesisOperations`는 명시적인 operations API입니다. listener container를 시작하거나
checkpoint를 관리하지 않습니다. Flow를 수집할 때 sequence number나 application
checkpoint는 애플리케이션의 저장소에 직접 기록하세요.

### EventBridge — event bus, rule, target, PutEvents

![EventBridge Spring Boot and Ktor class map](../docs/images/readme-diagrams/bluetape4k-aws-eventbridge-class-32.png)

```yaml
bluetape4k:
  aws:
    eventbridge:
      region: us-east-1
      default-event-bus-name: orders
```

```kotlin
import io.bluetape4k.aws.eventbridge.model.putEventsRequestEntryOf
import io.bluetape4k.aws.spring.eventbridge.EventBridgeOperations

class OrderEvents(
    private val eventBridge: EventBridgeOperations,
) {
    suspend fun publishCreated(orderId: String) {
        val response = eventBridge.putEvents(
            listOf(
                putEventsRequestEntryOf(
                    source = "orders",
                    detailType = "order.created",
                    detail = """{"orderId":"$orderId"}""",
                )
            )
        )
        require(response.failedEntryCount() == 0) { "EventBridge rejected one or more entries." }
    }
}
```

EventBridge operations를 쓰려면 `software.amazon.awssdk:eventbridge`를 추가하세요.
Template은 event bus 이름을 생략한 rule, target, list 호출에만
`default-event-bus-name`을 적용하고, `PutEvents` entry는 변경하지 않습니다.
`PutEvents`, `PutTargets`, `RemoveTargets`는 raw SDK response를 반환하므로 partial
failure를 호출자가 직접 확인해야 합니다.

### SQS — `@SqsListener` 어노테이션

```kotlin
import io.bluetape4k.aws.spring.sqs.SqsListener
import io.bluetape4k.aws.spring.sqs.SqsAcknowledgement
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import org.springframework.stereotype.Component

data class OrderEvent(val id: String, val total: Long)

@Component
class OrderListener {
    @SqsListener(
        queue = "orders",
        maxMessages = 10,
        waitTimeSeconds = 20,
        messageVisibilityHeartbeatIntervalSeconds = 20,
        messageVisibilityHeartbeatSeconds = 60,
    )
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

SNS topic을 SQS queue로 fanout하면 기본 Jackson converter가 SNS `Notification`
envelope를 인식합니다. 리스너는 `SnsNotification<OrderEvent>`를 받아 SNS subject,
topic ARN, timestamp, signature metadata, SNS message attributes와 원본
`SqsReceivedMessage`를 함께 사용할 수 있습니다.

```kotlin
import io.bluetape4k.aws.spring.sqs.SnsNotification

@SqsListener("\${orders.queue-url}")
suspend fun handle(notification: SnsNotification<OrderEvent>) {
    process(notification.message)
    val topicArn = notification.topicArn
    val sqsGroup = notification.sqs.messageGroupId
}
```

SNS envelope가 아닌 본문은 기존 SQS 변환 경로를 그대로 사용합니다. 손상된
`Notification`은 기본적으로 기존 경로로 fallback하며, 손상된 envelope를 반드시 거부하려면
`SnsMalformedEnvelopeStrategy.THROW`를 사용합니다. 원본 JSON은 기본적으로
`SnsNotification.rawEnvelope`에 보존되며, 필요하지 않으면 보존 옵션을 끌 수 있습니다.

리스너 ack는 성공 시 삭제 방식입니다. 리스너 메서드가 정상 반환된 뒤에만 메시지를
삭제하고, 예외가 발생하면 삭제하지 않습니다. `error-visibility-timeout-seconds`를
설정하면 실패 메시지의 visibility를 명시적으로 바꿔 재시도 타이밍을 제어합니다.
`listener.retry`는 최종 실패 처리 전에 in-process retry를 수행하며 linear/exponential
backoff와 optional jitter를 지원합니다. `SqsListenerInterceptor` bean을 등록하면
receive, handler, ack/nack, failure 단계를 Micrometer나 logging/tracing library로
관찰할 수 있습니다. `stop-timeout-millis`는 poller 취소 후 컨테이너 종료 대기 시간을 제한합니다.

선택적 visibility heartbeat를 사용하려면 `message-visibility-heartbeat-interval-seconds`와
`message-visibility-heartbeat-seconds`를 모두 설정해야 하며 기본값은 비활성화입니다. 두 값은
양수이고 interval은 heartbeat timeout보다 짧아야 하며, 43,200초를 넘을 수 없습니다. 같은
이름의 camelCase 애노테이션 속성은 전역 listener 값을 덮어씁니다. 각 heartbeat는 추가
`ChangeMessageVisibility` 요청이므로 만료 전에 여유가 남는 interval을 선택하고 SQS 요청 비용과
throttling을 고려해야 합니다. heartbeat 실패는 로그와 기존 Micrometer operation으로 관찰되며
handler 결과를 변경하지 않습니다.

배치 listener에서는 아직 acknowledgement가 완료되지 않은 메시지만 연장합니다. 부분
acknowledgement로 완료된 메시지는 다음 heartbeat 요청에서 제외되고, FIFO ordering metadata는
기존 batch acknowledgement 규칙을 따릅니다.

배치 전달은 `batch = true`로 명시적으로 활성화하며 `List<SqsReceivedMessage>`,
`List<software.amazon.awssdk.services.sqs.model.Message>`, 또는 concrete `List<T>` 하나와
선택적인 `SqsBatchAcknowledgement`를 받습니다. AWS 제한에 따라 `maxMessages`는 1..10입니다.
`acknowledgementMode`는 `INHERIT`, `ON_SUCCESS`, `MANUAL`을 지원하고, 부분 처리는
`acknowledge(messages)`, `nack(messages, timeoutSeconds = 0)`, `changeVisibility`로 수행하며
항목별 상태를 담은 `SqsBatchAcknowledgementResult`를 반환합니다. `SqsOperations.deleteBatch`와
`changeVisibilityBatch`는 가능하면 AWS batch 요청 1회, 아니면 순차 fallback을 사용합니다.
FIFO group에서는 연속해서 성공한 prefix를 보존하며, at-least-once 전달이므로 외부 side effect는
멱등하게 만들거나 message-id deduplication을 적용해야 합니다. receipt handle, body, raw
message identifier는 결과 `toString()`, 로그, metric tag, correlation 값에 기록하지 않습니다.
[storage와 messaging manual](../docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md)에서
canary/rollback 순서(`STOPPING_RECEIVE -> DRAINING -> STOPPED`, DLQ redrive, idempotency 확인)를
확인하세요.

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
`micrometer-core`를 일반 의존성으로 포함합니다. native CloudWatch exporter는
선택 기능이므로 runtime에 `io.micrometer:micrometer-registry-cloudwatch2`를 추가하고
`bluetape4k.aws.cloudwatch.micrometer.registry.enabled=true`를 명시해야 합니다. 기존
`CloudWatchMeterPublishingOperations` helper는 현재 `MeterRegistry`를 읽어 명시적으로
한 번 publish하는 기준 데이터 publisher이며, `bluetape4k.aws.cloudwatch.micrometer.enabled`
스위치와 native exporter 스위치는 서로 독립적입니다.

```yaml
bluetape4k:
  aws:
    cloudwatch:
      enabled: true
      region: ap-northeast-2
      namespace: OrderApi
      micrometer:
        enabled: true                 # 기존 수동 기준 데이터 helper
        registry:
          enabled: true               # native scheduled exporter, 기본 false
          namespace: OrderApiNative   # 없으면 cloudwatch.namespace 사용
          step: 1m                    # 1분보다 짧으면 CloudWatch high resolution
          batch-size: 20               # 요청당 PutMetricData datum 수
          read-timeout: 10s
          common-tags:
            application: order-api
          filters:
            includes: ["orders.", "http.server.requests"]
            excludes: ["jvm."]
```

애플리케이션이 `MeterRegistry`(`CompositeMeterRegistry` 포함)를 이미 제공하면
native registry는 back-off하며, 공유 `CloudWatchAsyncClient`를 재사용하되 직접 닫지
않습니다. `includes`가 비어 있으면 이 registry의 모든 meter를 허용하므로 낮은
cardinality allow-list를 권장하며 secret, object key, request ID 등 제한 없는 식별자를
tag에 넣지 마세요. `step < 1m`은 `storageResolution=1`을 전송해 CloudWatch 비용을
늘릴 수 있습니다. 공식 Micrometer close lifecycle을 사용하므로 종료 시 각 batch가
`read-timeout`까지 기다릴 수 있습니다. registry 자체 retry는 추가하지 않고 AWS SDK
retry 설정은 consumer가 소유합니다. production endpoint는 HTTPS를 사용하고 role에는
최소 `cloudwatch:PutMetricData` 권한만 부여하세요. `bluetape4k.aws.cloudwatch.enabled=false`
또는 `bluetape4k.aws.enabled=false`로 client와 native exporter를 끌 수 있습니다.
registry dependency가 없을 때의 조건 back-off는 `--debug`로 확인할 수 있습니다.

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
