# bluetape4k-aws

[![CI](https://github.com/bluetape4k/bluetape4k-aws/actions/workflows/ci.yml/badge.svg)](https://github.com/bluetape4k/bluetape4k-aws/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-25-ED8B00?logo=openjdk)](https://openjdk.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[English](./README.md) | 한국어

![bluetape4k AWS 작업대 일러스트](./docs/assets/aws-workbench.png)

**AWS Java SDK v2**와 **AWS Kotlin SDK**를 Kotlin/JVM 서비스에서 쓰기 쉽게
감싼 라이브러리입니다. Coroutine 친화 API, Spring Boot 4 자동 설정, Ktor 3
통합을 제공하되, 애플리케이션이 특정 스택 하나에 묶이지 않도록 경계를
얇게 유지합니다.

SDK와 framework 선택, lifecycle 원칙, 실전 예제 중심의 학습 경로는
[AWS 매뉴얼](https://bluetape4k.github.io/ko/manual/bluetape4k-aws/0.5/)에서 자세히 설명합니다. README는 저장소를
빠르게 둘러보는 문서이고, 상세 설명의 기준은 매뉴얼입니다.

---

## 프로젝트 목적

`bluetape4k-aws`는 Kotlin 서비스에서 AWS SDK를 자연스럽게 사용하도록 돕습니다.
Java SDK v2의 async 모델, AWS Kotlin SDK의 suspend 모델, Spring Boot 4 자동 설정,
Ktor 3 HTTP 통합을 연결하되, 실제로 사용할 AWS SDK 모듈과 런타임 스택은
애플리케이션이 직접 선택하도록 둡니다.

## 제공 기능

- **Kotlin-first AWS 클라이언트** — Java SDK v2 coroutine adapter, AWS Kotlin SDK helper, 작은 request DSL
- **서비스 범위** — DynamoDB, DynamoDB Streams, S3, S3 Tables, S3 Vectors, SES/SESv2, SNS, SQS, KMS, CloudWatch, CloudWatch Logs, EC2 IMDS, Kinesis, EventBridge, EventBridge Scheduler, Step Functions, Lambda, Bedrock Runtime, STS, RDS IAM, Secrets Manager, Parameter Store
- **Spring Boot 4 operations** — awspring 없이 coroutine 중심 template, repository, listener,
  선택적 lifecycle-aware SQS Observation, Spring Modulith SNS/SQS event 외부화,
  auto-configuration 제공. 자세한 내용은 [storage와 messaging 매뉴얼](https://bluetape4k.github.io/ko/manual/bluetape4k-aws/0.5/modules/bluetape4k-aws-spring-boot/storage-and-messaging/)을 참고하세요.
- **Ktor 3 통합** — SigV4 signing, coroutine S3 접근, SQS consumer runtime, EventBridge publishing, DynamoDB server repository, EC2 IMDS helper, 선택적 Exposed JDBC health/readiness route, Ktor server/client 예제
- **로컬 통합 테스트** — Testcontainers 기반 Floci-first emulator와 coverage gap을 위한 명시적 LocalStack fallback 검증

<!-- README_VISUAL_OVERVIEW:START -->
## Overview Diagram

![Bluetape4k AWS overview diagram](docs/images/readme-diagrams/root-readme-overview-01.png)

## Module Composition Chart

![Bluetape4k AWS module composition chart](docs/images/readme-diagrams/root-readme-module-chart-01.png)
<!-- README_VISUAL_OVERVIEW:END -->

## 모듈

| 모듈 | 아티팩트 | 설명 |
|---|---|---|
| `bluetape4k-aws-java` | `io.github.bluetape4k.aws:bluetape4k-aws-java` | AWS Java SDK v2 래퍼. DynamoDB, DynamoDB Streams, S3, S3 Tables, 선택적 S3 Vectors, SES/v2, SNS, SQS, KMS, CloudWatch, CloudWatch Logs, Kinesis, EventBridge, EventBridge Scheduler, Step Functions, Lambda, Bedrock Runtime, STS, Secrets Manager, Parameter Store에 대한 동기, 비동기(`CompletableFuture`), Coroutines 확장과 Java SDK 기반 RDS IAM token helper 제공 |
| `bluetape4k-aws-kotlin` | `io.github.bluetape4k.aws:bluetape4k-aws-kotlin` | AWS Kotlin SDK 래퍼. DynamoDB, DynamoDB Streams, S3, S3 Tables, SES/v2, SNS, SQS, KMS, CloudWatch, CloudWatch Logs, Kinesis, EventBridge, EventBridge Scheduler, Step Functions, Lambda, Bedrock Runtime, STS, Secrets Manager, Parameter Store에 대한 네이티브 `suspend` 함수 + DSL 빌더 제공 |
| `bluetape4k-aws-exposed` | `io.github.bluetape4k.aws:bluetape4k-aws-exposed` | AWS 기반 설정과 Exposed JDBC를 연결하는 공통 기반. 데이터베이스 프로퍼티, RDS IAM 인증 토큰, Secrets Manager/Parameter Store source descriptor, Hikari 기반 Exposed `Database` 생성, default/named database registry 제공 |
| `bluetape4k-aws-spring-boot` | `io.github.bluetape4k.aws:bluetape4k-aws-spring-boot` | AWS 서비스용 Spring Boot 4 자동설정. Coroutines 네이티브, awspring 미사용. S3 Transfer Manager(`S3TransferTemplate`), S3 Control 기반 선택적 S3 Access Grants, 선택적 S3 Vectors operations, EventBridge operations, SES sender와 JavaMail adapter, SNS HTTP(S) 엔드포인트 알림 파싱(`SnsHttpMessageParser`)과 MVC/WebFlux composed mapping, SQS listener, 선택적 Spring Modulith SNS/SQS event 외부화, Kinesis operations, 선택적 DAX를 포함한 DynamoDB, Micrometer 기준 데이터 전송을 포함한 CloudWatch/CloudWatch Logs, EC2 IMDS metadata operations, KMS, Secrets Manager, Parameter Store 지원 |
| `bluetape4k-aws-ktor` | `io.github.bluetape4k.aws:bluetape4k-aws-ktor` | Ktor 3 SigV4 client plugin, KMS encryption header를 지원하는 coroutine 친화적 S3 REST client, 선택적 S3 Access Grants 및 S3 Vectors server plugin, EventBridge server plugin, Kinesis 및 STS server plugin, SES v2 및 SNS server plugin, SQS consumer runtime, DynamoDB server repository plugin, EC2 IMDS helper, AWS 기반 Exposed configuration, 선택적 Exposed JDBC health/readiness route, 공유 `bluetape4k-ktor-core` 기반 helper |
| `aws-ktor-dynamodb-examples` | 배포 안 함 | Floci-first AWS emulator 테스트와 공유 `bluetape4k-ktor-*` helper 기반 Ktor 3 DynamoDB server repository 예제 |
| `aws-ktor-s3-examples` | 배포 안 함 | object route, presigned URL, content-type 감지, config object, client-side encryption을 다루는 Ktor 3 `S3KtorClient` 예제 |
| `aws-ktor-sqs-examples` | 배포 안 함 | Floci 기반 Ktor 3 SQS consumer/runtime 예제. Manual ack/nack, retry-once redelivery, interceptor, observer event 포함 |
| `aws-ktor-exposed-examples` | 배포 안 함 | PostgreSQL Testcontainers, typed `ExposedCursorPage` 주문 pagination, 선택적 `/healthz/exposed`·`/readyz/exposed` JDBC probe를 사용하는 Ktor 3 `AwsExposedPlugin` 예제 |
| `aws-ktor-service-coverage-examples` | 배포 안 함 | SES/v2, SNS, CloudWatch, CloudWatch Logs, Kinesis, STS plugin을 다루는 Ktor 3 service coverage 예제. Injected operations 기반 deterministic route test 포함 |
| `aws-spring-boot-dynamodb-examples` | 배포 안 함 | Coroutine service flow용 Spring Boot 4 DynamoDB repository 예제 |
| `aws-spring-boot-s3-examples` | 배포 안 함 | `S3Operations`/`S3CoroutinesTemplate`, presigned URL, 선택적 KMS 기반 client-side encryption을 다루는 Spring Boot 4 WebFlux 예제. 컴파일/테스트 및 Spring AOT 태스크 검증 |
| `aws-spring-boot-sqs-examples` | 배포 안 함 | `SqsOperations`, typed/manual-ack `@SqsListener`, retry, interceptor event, Floci-first SNS subscription fanout을 다루는 Spring Boot 4 SQS/SNS 예제. 컴파일/테스트 및 Spring AOT 태스크 검증 |
| `aws-spring-boot-exposed-examples` | 배포 안 함 | typed cursor pagination, Spring Data Exposed 2.0.0 QBE/closed-projection SQL pushdown, `DynamicPropertyRegistry` Testcontainers bridge를 포함한 Spring Boot 4 MVC/Exposed 예제 |

### 구성요소 맵

![AWS component map diagram](docs/images/readme-diagrams/bluetape4k-aws-components-04.png)

### 주요 교차 모듈 서비스 커버리지

![AWS service coverage chart](docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.png)

이 차트는 여러 모듈에 걸쳐 통합된 주요 서비스를 다룹니다. Bedrock Runtime처럼
core client 모듈에서만 제공하는 서비스는 위 모듈 표와 각 모듈 README에서
확인할 수 있습니다.

### DynamoDB coordination (Issue #476)

AWS Kotlin 모듈은 PK-only DynamoDB table에서 조건부 쓰기만으로 bounded coordination을
수행하는 `DynamoDbDistributedLock`과 `DynamoDbMetadataStore`도 제공합니다. Lock은
단조 증가하는 `LockLease.fencingToken`을 보존하고, metadata는 logical expiry와 조건부
remove/replace를 지원합니다. 스키마·client lifecycle·downstream fencing 경계는
[AWS Kotlin 매뉴얼](https://bluetape4k.github.io/ko/manual/bluetape4k-aws/0.5/modules/bluetape4k-aws-kotlin/#dynamodb-coordination)에서
설명합니다. 계약 테스트는 FlociServer만 사용합니다.

```bash
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-kotlin:test --tests '*DynamoDbCoordinationFlociTest'
```

---

## 아키텍처

### 전체 구조

![aws Architecture diagram](docs/images/readme-diagrams/bluetape4k-aws-architecture-01.png)

### 3단계 API (`bluetape4k-aws-java` 모듈 — Java SDK v2)

![Three-Tier API (bluetape4k-aws-java module — Java SDK v2) diagram](docs/images/readme-diagrams/bluetape4k-aws-architecture-02.png)

### 네이티브 Suspend (`bluetape4k-aws-kotlin` 모듈 — Kotlin SDK)

![Native Suspend (bluetape4k-aws-kotlin module — Kotlin SDK) diagram](docs/images/readme-diagrams/bluetape4k-aws-architecture-03.png)

---

## 요구사항

- **JDK**: 25 이상
- **Kotlin**: 2.4 이상
- **Gradle**: 9.7.0 (체크인된 Wrapper)

모든 published 모듈과 외부 Java/Kotlin consumer fixture는 JDK 25를
대상으로 합니다. 이 release line을 사용하는 consumer는 JDK 25 이상에서
실행해야 합니다.

---

## 설치

이 라이브러리는 AWS 서비스 SDK를 `compileOnly`로 선언합니다. 실제로 사용하는 서비스의
런타임 의존성은 직접 추가해야 합니다. 중앙 `bluetape4k-dependencies` BOM을 한 번
가져오면 이 라이브러리와 지원하는 AWS SDK artifact의 버전이 함께 맞춰집니다. 사용자가
저장소 버전이나 AWS SDK BOM 버전을 따로 고를 필요는 없습니다.

Core Secrets Manager와 Parameter Store helper는 의도적으로 얇은 SDK 래퍼입니다.
Spring Environment 로딩, JSON flattening, 캐시/refresh 정책, rotation orchestration,
IAM/KMS policy 관리, 전체 페이지 자동 수집 abstraction은 상위 모듈이나 애플리케이션
코드의 책임으로 남겨둡니다.

### `bluetape4k-aws-java` 사용 (Java SDK v2 래퍼)

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")

    // 사용할 AWS Java SDK v2 서비스 추가
    implementation("software.amazon.awssdk:dynamodb") // DynamoDB Streams API가 포함된 artifact
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
    implementation("software.amazon.awssdk:cloudwatchlogs")
    implementation("software.amazon.awssdk:kinesis")
    implementation("software.amazon.awssdk:eventbridge")
    implementation("software.amazon.awssdk:scheduler")
    implementation("software.amazon.awssdk:sfn")
    implementation("software.amazon.awssdk:lambda")
    implementation("software.amazon.awssdk:bedrockruntime")
    implementation("software.amazon.awssdk:rds")
    implementation("software.amazon.awssdk:sts")
}
```

> Maven Central Snapshots를 사용하는 경우 다음 리포지토리를 추가하세요:
> ```kotlin
> repositories {
>     maven("https://central.sonatype.com/repository/maven-snapshots/")
> }
> ```

### `bluetape4k-aws-kotlin` 사용 (Kotlin SDK 래퍼)

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-kotlin")

    // 사용할 AWS Kotlin SDK 서비스 추가
    implementation("aws.sdk.kotlin:dynamodb")
    implementation("aws.sdk.kotlin:dynamodbstreams")
    implementation("aws.sdk.kotlin:s3")
    implementation("aws.sdk.kotlin:secretsmanager")
    implementation("aws.sdk.kotlin:sqs")
    implementation("aws.sdk.kotlin:ssm")
    implementation("aws.sdk.kotlin:sns")
    implementation("aws.sdk.kotlin:kms")
    implementation("aws.sdk.kotlin:cloudwatch")
    implementation("aws.sdk.kotlin:kinesis")
    implementation("aws.sdk.kotlin:eventbridge")
    implementation("aws.sdk.kotlin:scheduler")
    implementation("aws.sdk.kotlin:sfn")
    implementation("aws.sdk.kotlin:lambda")
    implementation("aws.sdk.kotlin:bedrockruntime")
    implementation("aws.sdk.kotlin:sts")
}
```

### `bluetape4k-aws-spring-boot` 사용 (Spring Boot 4 자동설정)

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot")

    // 사용할 AWS Java SDK v2 서비스는 런타임 의존성으로 직접 추가합니다.
    implementation("software.amazon.awssdk:dynamodb-enhanced")
    implementation("software.amazon.awssdk:cloudwatch")
    implementation("software.amazon.awssdk:cloudwatchlogs")
    implementation("software.amazon.awssdk:eventbridge")
    implementation("software.amazon.awssdk:imds")
    implementation("software.amazon.awssdk:kinesis")
    implementation("software.amazon.awssdk:kms")
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:s3vectors")
    implementation("software.amazon.awssdk:secretsmanager")
    implementation("software.amazon.awssdk:sns")
    implementation("software.amazon.awssdk:sqs")

    // 선택: Spring Security TextEncryptor 어댑터가 필요할 때만 추가합니다.
    implementation("org.springframework.security:spring-security-crypto")
    implementation("software.amazon.awssdk:ssm")
}
```

이 모듈은 Spring이 관리하는 AWS client와 coroutine 친화적인 서비스 helper가 필요할 때
사용합니다. 이 모듈은 Spring Boot 관측성 baseline 에 맞춰 `micrometer-core` 를
포함합니다. `MeterRegistry` bean 이 있으면 SQS/S3 operation timer 와 SQS listener phase
timer 가 low-cardinality tag 로 자동 등록됩니다. 모든 AWS SDK 서비스를 런타임으로 끌고
오지 않으므로 실제로 쓰는 서비스 SDK만 직접 추가해야 합니다. CloudWatch helper 를 쓰려면
`software.amazon.awssdk:cloudwatch` 와 `software.amazon.awssdk:cloudwatchlogs` 를
추가합니다. EC2 metadata helper 를 쓰려면 `software.amazon.awssdk:imds` 를 추가합니다.
KMS를 쓰려면 `software.amazon.awssdk:kms`, Kinesis operations를 쓰려면
`software.amazon.awssdk:kinesis`를 추가합니다. EventBridge operations를 쓰려면
`software.amazon.awssdk:eventbridge`를 추가합니다. Spring Security의 동기식 `TextEncryptor`를
주입받고 싶을 때만 `spring-security-crypto`를 추가합니다.

#### Spring Modulith SNS/SQS event 외부화 (1.0.0 개발선)

| 방향 | 필요한 runtime 구성 | 전달 경계 |
| --- | --- | --- |
| SNS producer | Spring Modulith event publication/serialization, SNS SDK | publication future는 실제 AWS publish 결과를 따릅니다. |
| SQS producer | Spring Modulith event publication/serialization, SQS SDK | 논리 target 이름을 사용하며 FIFO는 routing key가 필요합니다. |
| DIRECT SQS consumer | SQS SDK, registry, serializer, redrive policy | local 동기 dispatch와 claim complete 뒤에 acknowledge합니다. |
| SNS-to-SQS consumer | DIRECT 구성에 `sns-message-manager`와 TopicArn allowlist 추가 | SNS source/signature를 검증한 뒤 decode하고 claim합니다. |

root 기능과 각 방향은 명시적으로 opt-in합니다. 다음 최소 SNS producer 설정은 위에서
가져온 root BOM을 사용하며 개별 좌표에는 버전을 쓰지 않습니다.

```kotlin
dependencies {
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    implementation("org.springframework.modulith:spring-modulith-events-jackson")
    runtimeOnly("software.amazon.awssdk:sns")
}
```

```yaml
bluetape4k.aws.modulith.events:
  enabled: true
  producer.enabled: true
  targets.order-events:
    service: sns
    destination: order-events
```

애플리케이션은 `AwsModulithEventTypeRegistry` bean을 제공하고 Spring Modulith event를
논리 alias `order-events`로 route해야 합니다. 자세한 의존성, producer, DIRECT/SNS
consumer, FIFO, back-off, diagnostic, 배포, Floci 경계는 manual의
[storage와 messaging](https://bluetape4k.github.io/ko/manual/bluetape4k-aws/0.5/modules/bluetape4k-aws-spring-boot/storage-and-messaging/#spring-modulith-snssqs-외부화-미출시develop),
[자동 설정](https://bluetape4k.github.io/ko/manual/bluetape4k-aws/0.5/modules/bluetape4k-aws-spring-boot/auto-configuration/#modulith-event-자동-설정-미출시develop),
[runtime 운영](https://bluetape4k.github.io/ko/manual/bluetape4k-aws/0.5/modules/bluetape4k-aws-spring-boot/runtime-operations/#modulith-event-runtime-운영-미출시develop)
장에 정리했습니다.

```yaml
bluetape4k:
  aws:
    kms:
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      key-id: alias/app-secrets
      encryption-context:
        service: order-api
      data-key-cache:
        enabled: true
        max-size: 64
        ttl: PT5M
    s3:
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      path-style-access-enabled: true
      presign:
        duration: PT15M
      config:
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
    dynamodb:
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      table-prefix: local-
    cloudwatch:
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      namespace: OrderApi
      micrometer:
        enabled: true
    cloudwatch-logs:
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      log-group-name: /aws/app/order-api
      log-stream-name: local
    eventbridge:
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      default-event-bus-name: orders
    imds:
      enabled: true
      endpoint-mode: ipv4
      token-ttl: PT6H
      request-timeout: 1s
      retries: 0
    sqs:
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      listener:
        max-messages: 10
        wait-time-seconds: 20
        concurrency: 2
        retry:
          max-attempts: 2
          initial-backoff: 100ms
          max-backoff: 2s
          multiplier: 2.0
          jitter-ratio: 0.2
    secrets-manager:
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      sources:
        - name: app-secret
          secret-id: local/app
          prefix: app
    parameter-store:
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      sources:
        - name: app-parameters
          path: /config/app
          prefix: app
          recursive: true
          with-decryption: true
    sns:
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      account-id: 123456789012
      allow-cross-account-topic-arn: false
      topic-arn-cache:
        enabled: true
        max-size: 256
        ttl: 5m
      topics:
        orders.fifo:
          fifo: true
          content-based-deduplication: true
          fifo-throughput-scope: message-group
```

KMS는 대용량 payload 암호화가 아니라 작은 secret과 key 관리를 위한 서비스입니다.
token, credential, 설정 secret처럼 짧은 값은 `KmsOperations.encrypt`를 바로 사용하세요.
큰 payload는 `generateDataKey`로 data key를 발급받아 로컬에서 payload를 암호화하고,
암호화된 data key를 payload metadata와 함께 저장하는 envelope encryption 방식이 적합합니다.
기본 `DataKeyCache`는 plaintext data key를 짧게 재사용할 수 있지만, 프로세스 메모리에
민감한 key material을 보관하는 것이므로 TTL과 cache size를 작게 유지하세요.

#### KMS Spring Boot 구성요소

![KMS Spring Boot components](docs/images/readme-diagrams/bluetape4k-aws-kms-components-06.png)

#### KMS 암호화 / 복호화 흐름

![KMS encrypt and decrypt flow](docs/images/readme-diagrams/bluetape4k-aws-kms-flow-07.png)

---

## 사용 예시

### S3 — Spring Boot Coroutines Template

```kotlin
import io.bluetape4k.aws.spring.s3.S3Operations
import io.bluetape4k.aws.spring.s3.S3TransferOperations
import java.nio.file.Path

class DocumentStorage(
    private val s3: S3Operations,
    private val transfer: S3TransferOperations,
) {
    suspend fun save(bucket: String, key: String, contents: String) {
        s3.upload(bucket, key, contents, contentType = "text/plain")
    }

    suspend fun read(bucket: String, key: String): String =
        s3.downloadText(bucket, key)

    suspend fun saveLargeFile(bucket: String, key: String, source: Path) {
        transfer.uploadFile(bucket, key, source)
    }
}
```

`S3Operations` 는 작은/일반 객체 작업, resource, list, presigned URL 을 담당한다.
`S3TransferOperations` 는 `software.amazon.awssdk:s3-transfer-manager` 가
classpath 에 있을 때만 자동 구성되며, 대용량 파일, multipart transfer, transfer
listener 용이다. CRT 기반 throughput 튜닝이 필요하면 AWS CRT runtime dependency 를
추가하고 CRT-backed `S3AsyncClient` bean 을 제공한다. Spring auto-configuration 은
그 client 를 재사용해 transfer manager 를 만든다.

#### S3 Access Grants

S3 Access Grants 지원은 opt-in이며 AWS SDK v2 S3 Control client를 사용합니다.
활성화하는 애플리케이션은 runtime service dependency를 추가해야 합니다. 해당 의존성이
없으면 auto-configuration 대상에서 제외됩니다.

```kotlin
runtimeOnly("software.amazon.awssdk:s3control")
```

```yaml
bluetape4k:
  aws:
    s3:
      access-grants:
        enabled: true
        region: us-east-1
```

![S3 Access Grants components](docs/images/readme-diagrams/bluetape4k-aws-s3-access-grants-components-08.png)

![S3 Access Grants flow](docs/images/readme-diagrams/bluetape4k-aws-s3-access-grants-flow-09.png)

```kotlin
import io.bluetape4k.aws.spring.s3.accessgrants.S3AccessGrantsOperations
import software.amazon.awssdk.services.s3control.model.GetDataAccessRequest
import software.amazon.awssdk.services.s3control.model.Permission

class GrantedObjectAccess(
    private val accessGrants: S3AccessGrantsOperations,
) {
    suspend fun readCredentials(accountId: String, target: String) =
        accessGrants.getDataAccess(
            GetDataAccessRequest.builder()
                .accountId(accountId)
                .target(target)
                .permission(Permission.READ)
                .build()
        )
}
```

`S3AccessGrantsOperations` 는 일반적인 read/data-access 경로인 `getDataAccess`,
`listCallerAccessGrants`, `listAccessGrants`, `listAccessGrantsInstances`,
`listAccessGrantsLocations` 를 제공합니다. 관리용 create/update/delete 호출은 의도적으로
raw `S3ControlClient` 와 `S3ControlAsyncClient` bean 에 남겨 정책을 바꾸는 작업이
명시적으로 드러나게 합니다.

### DynamoDB — Spring Boot Coroutine Repository

Spring Boot 통합은 async client와 table-name resolver를 구성합니다. 다만 repository는
entity schema, key mapping, table lifecycle을 계속 소유합니다.

![DynamoDB coroutine repository components](docs/images/readme-diagrams/bluetape4k-aws-dynamodb-components-10.png)

```kotlin
import io.bluetape4k.aws.spring.dynamodb.AbstractCoroutinesDynamoDbRepository
import io.bluetape4k.aws.spring.dynamodb.DynamoDbTableNameResolver
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.enhanced.dynamodb.Key

class OrderRepository(
    enhancedClient: DynamoDbEnhancedAsyncClient,
    tableNameResolver: DynamoDbTableNameResolver,
) : AbstractCoroutinesDynamoDbRepository<OrderDocument, OrderId>(
    enhancedClient = enhancedClient,
    tableNameResolver = tableNameResolver,
    entityClass = OrderDocument::class.java,
) {
    override val tableName: String = "orders"

    override fun keyFromId(id: OrderId): Key =
        Key.builder().partitionValue(id.orderId).sortValue(id.createdAt).build()
}
```

![DynamoDB coroutine repository flow](docs/images/readme-diagrams/bluetape4k-aws-dynamodb-flow-11.png)

`aws-spring-boot`는 DynamoDB 테이블을 자동 생성하지 않습니다. 테이블 생성은
migration, 배포 자동화, 또는 테스트 setup에서 명시적으로 수행해 schema 변경이
드러나게 합니다.

DynamoDB Accelerator(DAX)는 선택 기능이며, 사용하는 애플리케이션에 DAX runtime
dependency를 추가해야 합니다.

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
        idle-timeout: 30s
```

DAX를 활성화해도 repository 진입점은 `DynamoDbEnhancedAsyncClient`로 유지되지만,
내부 `DynamoDbAsyncClient`가 DAX client로 구성됩니다. DAX는 실제 AWS DAX cluster용
기능입니다. LocalStack, Floci, DynamoDB Local은 emulator/test 경로이며 DAX cache
consistency 또는 latency 동작을 모델링하지 않습니다.

### CloudWatch — Spring Boot Metrics와 Logs

CloudWatch metrics, CloudWatch Logs, Micrometer 기준 데이터 전송은 각각 별도의
operation API로 분리되어 있습니다. Micrometer helper는 애플리케이션 코드가 명시적으로
기준 데이터 publish를 요청할 때만 기존 registry를 읽습니다.

![CloudWatch metrics and logs components](docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-components-12.png)

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
    suspend fun processed(orderId: String) {
        cloudWatch.putMetricDatum(metricDatumOf("OrderProcessed", 1.0, StandardUnit.COUNT))
        cloudWatchLogs.putLogEvents(
            listOf(inputLogEventOf(System.currentTimeMillis(), "processed order=$orderId"))
        )
        meters.publishMeter("orders.processed")
    }
}
```

![CloudWatch publish flow](docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-flow-13.png)

Micrometer helper는 `MeterRegistry` bean 이 있을 때만 등록됩니다. 이 helper는
`CloudWatchOperations` 를 통해 명시적으로 기준 데이터를 publish 하며, scheduled
Micrometer registry publication 을 대체하지 않습니다. SQS/S3 Micrometer adapter 도
application `MeterRegistry` 가 있을 때 동작하며 SQS send/receive/listener phase 와
S3 upload/download/delete/list/presign operation 을 측정합니다. 기본 tag 에 queue URL,
message ID, object key, receipt handle 은 넣지 않습니다.

scheduled native CloudWatch publishing이 필요하면 애플리케이션에
`runtimeOnly("io.micrometer:micrometer-registry-cloudwatch2")`를 추가하고 명시적으로
opt-in하세요.

```yaml
bluetape4k:
  aws:
    cloudwatch:
      namespace: OrderApi
      micrometer:
        registry:
          enabled: true
          step: 1m
          batch-size: 20
          read-timeout: 10s
          common-tags: { application: order-api }
          filters:
            includes: ["orders.", "http.server.requests"]
            excludes: ["jvm."]
```

native registry는 기본적으로 비활성화되며 기존 `MeterRegistry` 또는
`CompositeMeterRegistry`가 있으면 back-off합니다. 공유 AWS client를 재사용하고 1분보다
짧은 step에는 `storageResolution=1`을 사용하며, close 대기는 설정한 batch 수와
`read-timeout` 범위 안에 있습니다. 비어 있는 `includes`는 모든 meter를 허용하므로 tag는
낮은 cardinality로 유지하고 secret이나 request identifier를 넣지 마세요. production에서는
HTTPS와 `cloudwatch:PutMetricData` 최소 권한을 사용하고, optional registry dependency가
없을 때는 `--debug` condition output으로 back-off 원인을 확인하세요.

### EC2 IMDS — Spring Boot Metadata Operations

Spring Boot auto-configuration과 Ktor plugin은 모두 시작 시 IMDS를 호출하지 않는
passive metadata facade를 준비합니다. 실제 조회는 operation이 호출될 때만 수행됩니다.

![EC2 IMDS access surfaces](docs/images/readme-diagrams/bluetape4k-aws-imds-components-14.png)

```kotlin
import io.bluetape4k.aws.spring.imds.ImdsOperations

class InstanceMetadataReporter(
    private val imds: ImdsOperations,
) {
    suspend fun describe(): String {
        val instanceId = imds.instanceId()
        val region = imds.region()
        val zone = imds.availabilityZone()

        return "$instanceId in $region/$zone"
    }
}
```

`ImdsOperations`는 Spring 시작 시점에는 IMDS를 호출하지 않고, operation이 호출될
때만 조회합니다. 각 호출은 `bluetape4k.aws.imds.request-timeout`으로 제한됩니다.
EC2 instance metadata 조회에만 사용하고, `DefaultCredentialsProvider` 나 EKS/IRSA
web identity credentials를 대체하는 용도로 쓰지 않습니다. helper는 IAM role 이름만
노출하며 temporary credential document는 노출하지 않습니다.

### EC2 IMDS — Ktor Plugin

```kotlin
import io.bluetape4k.aws.ktor.imds.ImdsKtorPlugin
import io.bluetape4k.aws.ktor.imds.imds
import io.ktor.server.application.Application
import io.ktor.server.application.install
import java.time.Duration

fun Application.module() {
    install(ImdsKtorPlugin) {
        requestTimeout = Duration.ofSeconds(1)
    }
}

suspend fun Application.instanceSnapshot(): Map<String, String> =
    mapOf(
        "instanceId" to imds().instanceId(),
        "region" to imds().region(),
        "availabilityZone" to imds().availabilityZone(),
    )
```

![EC2 IMDS metadata flow](docs/images/readme-diagrams/bluetape4k-aws-imds-flow-15.png)

Ktor IMDS plugin을 사용할 때는 `software.amazon.awssdk:imds` 의존성을 추가합니다.
plugin을 설치해도 IMDS는 호출하지 않습니다. metadata는 `ImdsKtorOperations` 메서드가
호출될 때만 읽습니다. helper는 IAM role 이름만 노출하며 temporary credential
document는 노출하지 않습니다.

### Secrets Manager와 Parameter Store — Environment Source

Secrets Manager와 SSM Parameter Store source는 Spring Environment
post-processing 단계에서 로드되므로 일반 `@ConfigurationProperties` 바인딩 전에
사용할 수 있습니다. source가 하나 이상 설정된 경우에만 원격 조회를 수행합니다.

![Secrets Manager and Parameter Store environment sources](docs/images/readme-diagrams/bluetape4k-aws-env-sources-components-16.png)

```kotlin
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.db")
data class DatabaseSettings(
    val username: String,
    val password: String,
)
```

`prefix: app`으로 `{"db":{"username":"scott","password":"tiger"}}` JSON
secret을 로드하면 `app.db.username`, `app.db.password` 속성이 됩니다.
Parameter Store의 `/config/app/db/password`는 `path: /config/app`,
`prefix: app` 설정에서 `app.db.password` 속성이 됩니다.
`refresh-interval`을 설정하면 property source는 읽기 시점에 갱신을 시도하고,
refresh가 실패하면 마지막으로 성공한 값을 유지합니다.

![Secrets Manager and Parameter Store property key mapping](docs/images/readme-diagrams/bluetape4k-aws-env-sources-flow-17.png)

### Environment Source 기반 Exposed Database 설정

`AwsExposedAutoConfiguration`은 Secrets Manager나 Parameter Store가 Spring
Environment에 먼저 게시한 값으로 Exposed registry를 만들 수 있습니다. Exposed
resolver는 별도의 AWS client 경로를 만들지 않고, `secret-source` 또는
`parameter-source` descriptor의 prefix 아래에서 `url`, `driver-class-name`,
`username`, `password` 같은 connection field를 읽습니다.

```yaml
bluetape4k:
  aws:
    secrets-manager:
      region: ap-northeast-2
      sources:
        - name: orders-db
          secret-id: prod/orders/database
          prefix: orders.db
    exposed:
      default-database:
        secret-source:
          source-id: prod/orders/database
          prefix: orders.db
        pool:
          maximum-pool-size: 10
```

Secret payload는 database field를 그대로 제공할 수 있습니다.

```json
{
  "url": "jdbc:postgresql://orders.cluster.local:5432/orders",
  "driver-class-name": "org.postgresql.Driver",
  "username": "orders",
  "password": "change-me"
}
```

로컬/테스트 profile에서는 기존처럼
`bluetape4k.aws.exposed.default-database.*` 속성을 직접 설정해도 됩니다. Source
descriptor 값은 descriptor prefix 아래 실제 존재하는 key만 덮어쓰며, optional
descriptor는 source가 없을 때 기존 설정을 유지합니다.

### Exposed 2.0.0 예제

두 Exposed 예제 모듈은 현재
`bluetape4k-exposed` `2.0.0-SNAPSHOT` API를 사용하면서 AWS 설정, transaction과
resource lifecycle 경계를 분명하게 보여줍니다. 두 모듈 모두 배포하지 않으며 AWS
credential 없이 PostgreSQL Testcontainers로 실행합니다.

| 예제 | 확인하는 계약 | 안내 |
|---|---|---|
| `aws-ktor-exposed-examples` | 선택적인 `customerId` 필터와 count query 없는 `ExposedCursorPage<OrderRecord, Long>` cursor pagination, Exposed core/JDBC artifact만 사용하는 opt-in `/healthz/exposed` liveness와 `/readyz/exposed` JDBC `SELECT 1` readiness | [Ktor Exposed 예제](examples/aws-ktor-exposed-examples/README.ko.md) |
| `aws-spring-boot-exposed-examples` | cursor pagination, `OrderSummaryProjection` closed projection을 사용하는 Spring Data Exposed 2.0.0 Query by Example SQL pushdown, Testcontainers property를 AWS database prefix로 매핑하는 `DynamicPropertyRegistry` bridge | [Spring Boot Exposed 예제](examples/aws-spring-boot-exposed-examples/README.ko.md) |

Docker resource를 공유할 때는 두 예제 테스트를 순서대로 실행합니다.

```bash
./gradlew :aws-ktor-exposed-examples:test
./gradlew :aws-spring-boot-exposed-examples:test
```

### SQS — Spring Boot Coroutines Template과 Listener

SQS auto-configuration은 coroutine operations API와 listener runtime을
분리합니다. Listener method는 raw payload, Jackson 변환 payload, 선택적 manual
acknowledgement를 사용할 수 있습니다.

![SQS Spring Boot runtime](docs/images/readme-diagrams/bluetape4k-aws-sqs-components-18.png)

```kotlin
import io.bluetape4k.aws.spring.sqs.SqsListener
import io.bluetape4k.aws.spring.sqs.SqsAcknowledgement
import io.bluetape4k.aws.spring.sqs.SqsOperations

data class OrderEvent(val id: String, val total: Long)

class OrderQueue(
    private val sqs: SqsOperations,
) {
    suspend fun send(queueUrl: String, payload: String) {
        sqs.send(queueUrl, payload)
    }

    @SqsListener("\${orders.queue-url}")
    suspend fun handle(body: String) {
        // 실패한 메시지는 자동 삭제되지 않으므로 핸들러는 idempotent하게 작성합니다.
        process(body)
    }

    @SqsListener("\${orders-json.queue-url}")
    suspend fun handle(event: OrderEvent, acknowledgement: SqsAcknowledgement) {
        process(event)
        acknowledgement.acknowledge()
    }
}
```

![SQS listener flow](docs/images/readme-diagrams/bluetape4k-aws-sqs-flow-19.png)

Typed listener payload는 `SqsMessageConverter`가 처리합니다. Jackson 3
`ObjectMapper` bean이 있으면 converter가 자동 등록됩니다. `SqsAcknowledgement`를
선언하면 listener는 manual acknowledgement 모드로 동작합니다. Listener retry,
backoff, jitter, `SqsListenerInterceptor` hook으로 redelivery와 observability
흐름을 구성할 수 있습니다.

#### SQS Extended Client

opt-in Extended Client는 설정한 threshold 이하의 메시지를 inline으로 보내고,
더 큰 payload를 인증된 pointer 뒤의 S3 객체로 offload합니다. producer offload를
켜기 전에 consumer를 활성화하고 drain을 완료하세요.

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
          max-offload-payload-bytes: 67108864
          orphan-retention-hours: 168
          delete-on-ack: false
          pointer-signing-key-ref: default
```

`SqsExtendedClientOperations`에는 idempotency key를 전달하고 동일한
identity-bound `SqsExtendedReceivedMessage` instance로 acknowledge하세요.
지원되는 Jackson 3 module은 raw AWS model, pointer 위치·signature, receipt
handle, cleanup handle을 직렬화하지 않습니다. 일반 `@SqsListener`와 AWS Java
Extended Client는 이 pointer 형식을 복원하지 않습니다. rollback은 drain과
두 번의 visibility-window empty probe, redrive/DLQ와 retention gate를 확인하며
`ROLLBACK_BLOCKED`이면 legacy consumer를 중지한 채 유지합니다.

네 개의 저카디널리티 counter에는 queue URL, bucket/key, payload,
diagnostic code를 tag로 넣지 않습니다. 외부 publisher latency·cleanup
telemetry와 heap/throughput 측정은 후속 이슈 #515에서 추적합니다.

SNS topic이 SQS로 fanout되면 기본 Jackson converter가 SNS `Notification`
envelope를 인식합니다. listener는 typed `SnsNotification<OrderEvent>`를
받아 SNS subject, topic ARN, timestamp, signature metadata, SNS message
attributes와 원본 `SqsReceivedMessage`에 접근할 수 있습니다.

```kotlin
import io.bluetape4k.aws.spring.sqs.SnsNotification

@SqsListener("\${orders.queue-url}")
suspend fun handle(notification: SnsNotification<OrderEvent>) {
    process(notification.message)
    val topicArn = notification.topicArn
    val sqsGroup = notification.sqs.messageGroupId
}
```

SNS가 아닌 body는 기존 SQS 변환 경로를 유지합니다. 잘못된 `Notification`은
기본적으로 해당 경로로 fallback하며, 잘못된 envelope를 거부해야 하면
`SnsMalformedEnvelopeStrategy.THROW`를 사용하세요. `SnsNotification.rawEnvelope`는
기본적으로 보존하고 원본 JSON이 필요 없으면 비활성화할 수 있습니다.

### KMS — Spring Boot Coroutines Encryptor

KMS 지원의 중심은 `KmsOperations`입니다. Auto-configuration은 SDK client,
coroutine encryptor, 제한된 data-key cache, explicit field encryption codec,
선택적 Spring Security `TextEncryptor` adapter를 등록합니다.

![KMS Spring Boot support map](docs/images/readme-diagrams/bluetape4k-aws-kms-components-20.png)

```kotlin
import io.bluetape4k.aws.spring.kms.KmsOperations
import java.util.Base64

class SecretVault(
    private val kms: KmsOperations,
) {
    suspend fun protectToken(token: String): String {
        val ciphertext = kms.encrypt(
            plaintext = token.encodeToByteArray(),
            encryptionContext = mapOf("purpose" to "api-token"),
        )
        return Base64.getEncoder().encodeToString(ciphertext)
    }

    suspend fun revealToken(encodedCiphertext: String): String {
        val plaintext = kms.decrypt(
            ciphertext = Base64.getDecoder().decode(encodedCiphertext),
            encryptionContext = mapOf("purpose" to "api-token"),
        )
        return plaintext.decodeToString()
    }
}
```

![KMS operations flow](docs/images/readme-diagrams/bluetape4k-aws-kms-flow-21.png)

encryption context는 인증되는 metadata입니다. 암호화할 때 사용한 context와 같은 값을
복호화에도 전달해야 하며, `service`, `tenant`, `purpose`처럼 안정적인 식별자를 넣는
용도로 사용하세요. AWS 로그나 policy에서 노출될 수 있으므로 secret 자체를 context에
넣으면 안 됩니다.

### KMS — Spring Security `TextEncryptor`

```kotlin
import org.springframework.security.crypto.encrypt.TextEncryptor

class PropertyProtector(
    private val textEncryptor: TextEncryptor,
) {
    fun encrypt(value: String): String =
        textEncryptor.encrypt(value)

    fun decrypt(value: String): String =
        textEncryptor.decrypt(value)
}
```

`TextEncryptor`는 동기식 인터페이스이므로 짧은 관리 흐름이나 startup 시점 secret 처리에
적합합니다. Coroutine service 안에서는 `KmsOperations`를 우선 사용하세요.

### SNS — AWS SDK 래퍼

하위 수준 `bluetape4k-aws-java` 확장은 AWS SDK 응답과 예외를 그대로 노출하면서 topic ARN,
entry ID, 중복 ID, `PublishBatch`의 10개 제한을 검증합니다. 동기, `CompletableFuture`,
coroutine API는 같은 request 모델을 공유하며 AWS Kotlin SDK 래퍼는 native suspend API를
제공합니다.

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

각 raw response에는 성공·실패 entry가 함께 포함될 수 있으므로 entry ID를 기준으로
대조하세요. 이 래퍼는 partial send를 자동 재시도하거나 rollback하지 않으며 cancellation을
전파합니다(Java coroutine 확장은 underlying future도 취소합니다). FIFO group/deduplication
값과 외부 idempotency key는 호출자의 책임입니다. payload를 노출하지 않는
transport/protocol 예외 경계가 필요하면 아래 Spring 래퍼를 사용하세요.

### SNS — Spring Boot Coroutines 템플릿

SNS 지원의 중심은 `SnsOperations`입니다. Standard/FIFO topic 생성, topic message
publish, direct SMS publish, HTTP endpoint subscription confirmation을 제공합니다.
HTTP endpoint는 애플리케이션이 신뢰 검증을 끝낸 뒤 confirmation을 호출해야 합니다.

![SNS Spring Boot support map](docs/images/readme-diagrams/bluetape4k-aws-sns-components-22.png)

```kotlin
import io.bluetape4k.aws.spring.sns.SnsHttpMessageParser
import io.bluetape4k.aws.spring.sns.SnsHttpMessageType
import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sns.SnsPublishRequest
import io.bluetape4k.aws.spring.sns.SnsPublishBatchEntry
import io.bluetape4k.aws.spring.sns.SnsPublishBatchRequest
import io.bluetape4k.aws.spring.sns.SnsBatchExecutionOptions
import io.bluetape4k.aws.spring.sns.SnsSmsRequest
import io.bluetape4k.aws.spring.sns.SnsSmsType

class OrderTopic(
    private val sns: SnsOperations,
) {
    suspend fun publish(topicArn: String, payload: String): String {
        val response = sns.publish(
            SnsPublishRequest(
                topicArn = topicArn,
                message = payload,
            )
        )
        return response.messageId()
    }

    suspend fun sendSms(phoneNumber: String, text: String): String =
        sns.publishSms(
            SnsSmsRequest(
                phoneNumber = phoneNumber,
                message = text,
                smsType = SnsSmsType.TRANSACTIONAL,
                senderId = "BLUETAPE",
            )
        ).messageId()

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

#### SNS HTTP endpoint annotation

Spring Boot 모듈은 SNS HTTP(S) 전달을 위한 composed mapping도 제공합니다. 세
mapping은 일치하는 `x-amz-sns-message-type` header의 `POST` 요청을 받고
`204 No Content`를 반환합니다. MVC와 WebFlux controller에서 Kotlin `suspend`
handler를 포함해 하나의 bounded·replayable request body로 파싱된 envelope를
재사용할 수 있습니다.

endpoint adapter는 `NotificationStatus` 공개 계약이
`ConfirmSubscriptionResponse`를 반환하므로 notification 전용 mapping을 사용할
때도 런타임에 AWS SNS service SDK가 필요합니다. 소비자 runtime에
`software.amazon.awssdk:sns`를 추가해야 하며, SDK가 없으면 auto-configuration이
자동으로 back off합니다.

```kotlin
import io.bluetape4k.aws.spring.sns.SnsHttpMessage
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationMessageMapping
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationSubscriptionMapping
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationUnsubscribeConfirmationMapping
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationMessage
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationSubject
import io.bluetape4k.aws.spring.sns.handlers.NotificationStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class SnsHttpController {
    @NotificationMessageMapping(path = ["/sns/notifications"])
    suspend fun notification(
        @NotificationMessage payload: OrderPayload,
        @NotificationSubject subject: String?,
    ) {
        // SNS 재전달을 고려해 idempotent하게 처리합니다.
    }

    @NotificationSubscriptionMapping(path = ["/sns/subscriptions"])
    suspend fun subscription(status: NotificationStatus) {
        // confirmation은 명시적으로 호출하며 adapter가 AWS를 자동 호출하지 않습니다.
        status.confirmSubscription()
    }

    @NotificationUnsubscribeConfirmationMapping(path = ["/sns/unsubscriptions"])
    fun unsubscribe(status: NotificationStatus) {
        // unsubscribe confirmation도 명시적으로 처리합니다.
    }

    @NotificationMessageMapping(path = ["/sns/raw"])
    fun raw(@io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationRawMessage message: SnsHttpMessage) {
        // typed payload가 필요하지 않으면 raw envelope를 받을 수 있습니다.
    }
}

class OrderPayload {
    var orderId: String = ""
}
```

adapter는 기본적으로 fail-closed입니다. 허용할 topic ARN을 설정하고 기존 SNS
verifier를 활성화한 뒤 traffic을 받으세요.

```yaml
bluetape4k:
  aws:
    sns:
      http-endpoints:
        enabled: true
        verification-required: true
        allow-structural-only: false
        expected-topic-arns:
          - arn:aws:sns:us-west-2:123456789012:orders
```

allowlist가 비어 있으면 요청은 `403`으로 거부되고 verifier가 없으면 `503`으로
거부됩니다. 두 경우 모두 handler는 호출되지 않습니다. SNS는 outer HTTP body를
일반적으로 `text/plain`으로 보내므로 이를 허용합니다. `String`이 아닌
`@NotificationMessage` parameter는 envelope의
`MessageAttributes.contentType=application/json`을 요구하며 malformed JSON,
signature, header/type 불일치, 크기 초과는 handler 전에 `400`으로 끝납니다.

`SnsHttpMessageVerifier`는 `software.amazon.awssdk:sns-message-manager`를
`compileOnly` 의존성으로 유지하므로 signature 검증을 활성화할 때 애플리케이션
runtime에 직접 추가해야 합니다. 인증서 조회 timeout과 retry 책임은 AWS SDK
message manager 및 애플리케이션 client 설정에 있으며, 이 adapter는 거부된 HTTP
전달이나 handler 호출을 자동 재시도하지 않습니다.

`NotificationUnsubscribeConfirmationMapping`은 unsubscribe confirmation을
별도로 처리합니다. `NotificationSubscriptionMapping`과 자동 확인을 합치지 않으며,
두 mapping 모두 명시적인 `confirmSubscription()` 호출에 사용할
`NotificationStatus`를 제공합니다. 임시 진단 rollback만 필요하면
`verification-required=false`와 `allow-structural-only=true`를 함께 설정할 수
있지만 signature 검증을 우회하므로 production SNS traffic에는 사용하지 마세요.
adapter 전체를 끄려면 `bluetape4k.aws.sns.http-endpoints.enabled=false`를 설정합니다.

`SnsOperations.findTopicArn`은 topic name 또는 명시적 SNS ARN을 받습니다. name
조회는 bounded scope별 TTL/LRU cache(기본값: 활성화, 256 entry, 5분)와
topic별 single-flight를 사용합니다. `bluetape4k.aws.sns.topic-arn-cache.enabled=false`로
영속 cache 저장만 끌 수 있으며 중복 조회 억제는 유지됩니다. 같은 계정 ARN
검증을 적용하려면 `account-id`를 설정하세요. account ID가 없으면 명시적 ARN은
`allow-cross-account-topic-arn=true`를 의도적으로 opt-in하지 않는 한 fail-closed
됩니다. explicit ARN 조회에도 wildcard 또는 미확인 region을 막기 위해 유효한
`region` 설정이 필요합니다. 사용자 정의 `SnsTopicArnResolver` 또는
`SnsTopicArnCache` bean은 범위를 좁힌 구성 override이며, 그 자체로 동작을
보존하는 rollback을 제공하지는 않습니다. 동작을 보존하려면 custom
`SnsOperations` 구현을 제공하거나 last-known-good artifact를 재배포하세요.
endpoint 또는 region을 확인할 수 없는 애플리케이션 제공 SNS client는
fail-fast하며 명시적인 resolver가 필요합니다. region을 설정하지 않으면
resolver는 AWS SDK provider chain이 최종 선택한 region을 scope로 사용하고,
명시한 endpoint/region은 client와 일치해야 합니다. `SnsCoroutinesTemplate`을
직접 생성할 때 client identity가 `SnsProperties`와 다르거나 검사할 수 없으면
resolver 주입 생성자를 사용하세요.
전체 SNS 자동 설정을 끄려면 `bluetape4k.aws.sns.enabled=false`를 사용합니다.
terminal 조회 실패는 hash 처리한 scope/topic 차원과 exception type만 기록하며
raw ARN과 topic name은 로그에 남기지 않습니다.

#### SNS 배치 발행

`SnsCoroutinesTemplate.publishBatch`는 `SnsPublishBatchEntry`를 AWS
`PublishBatchRequestEntry`로 매핑하고 SDK 요청 하나당 최대 10개 entry를
전송합니다. `maxInFlightBatches`로 동시 요청 수를 제한할 수 있으며, 빈
요청은 SDK를 호출하지 않고 즉시 반환합니다.

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

`result.successful`과 `result.failed`는 각각 입력 순서를 보존하고
reconciliation에 사용할 entry ID를 포함합니다. 전송 실패나 잘못된 응답은
payload를 노출하지 않는 Spring 예외로 전달하며, cancellation은 원본을
그대로 전파하고 자동 재시도는 수행하지 않습니다. FIFO group/deduplication
값과 외부 idempotency key의 책임은 호출자에게 있습니다. 하위 수준
Java/Kotlin SDK 확장은 SDK 응답과 예외를 그대로 전달하고, 이 Spring API는
안전한 transport/protocol 경계를 제공합니다.

한 chunk가 혼합 결과를 반환한 뒤 형제 요청이 실패하더라도 전체 입력을
무조건 재처리하지 마세요. entry ID를 기준으로 대조하고 FIFO deduplication
또는 외부 idempotency 저장소를 사용하며, terminal 응답이 불명확한 entry는
수동으로 조정해야 합니다. 비즈니스 rollback이나 보상 트랜잭션은 제공하지
않습니다. Spring Cloud AWS 방식의 공개 `BatchExecutionStrategy`·converter
확장 조사는 [#514](https://github.com/bluetape4k/bluetape4k-aws/issues/514)에서
추적합니다. Publisher cleanup/latency telemetry와 heap/throughput 측정은
[#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)에서 추적합니다.

#### SNS 메시지 변환 (1.0.0 개발선)

`SnsBatchMessageConverter`는 Spring `Message<*>`를 typed
`SnsPublishBatchRequest`로 바꾸는 opt-in·무네트워크 adapter입니다. 인자가
없는 생성자는 `String` payload만 허용하고, 두 번째 생성자는 명시적인
suspend `SnsPayloadSerializer`를 받습니다. 모든 entry 변환이 끝난 뒤에만
request를 만들며, 허용하는 header는
`SnsBatchMessageHeaders.MESSAGE_ID`, `SUBJECT`, `MESSAGE_ATTRIBUTES`,
`MESSAGE_GROUP_ID`, `MESSAGE_DEDUPLICATION_ID`입니다. 명시적 ID는
`UUID`여야 하고 없으면 `MessageHeaders.ID`의 UUID를 사용합니다. 결과는
입력 순서를 유지하며 변환 예외는 cause-free로 payload, header, ARN,
serializer exception을 숨깁니다. 취소 시 원래 `CancellationException`
instance를 유지하고 변환 실패는 SNS client를 호출하지 않습니다.

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

`spring-messaging`는 `compileOnly`이므로 converter를 사용하는 애플리케이션이
런타임에 `org.springframework:spring-messaging`를 직접 추가해야 합니다.
Guarded strategy port는 AWS client와 lifecycle을 노출하지 않으며, 상태가
불확실한 partial publish를 자동 재시도하지 않습니다. 262,144-byte SNS
제한의 byte-size preflight, Jackson 3 serialization, `ByteArray` payload
지원은 이번 release에 포함하지 않는 후속 범위입니다.

![SNS publish and HTTP endpoint flow](docs/images/readme-diagrams/bluetape4k-aws-sns-flow-23.png)

SNS는 queue policy가 topic ARN의 `sqs:SendMessage`를 허용하면 SQS subscription으로
fanout할 수 있습니다. `aws-spring-boot-sqs-examples` 모듈에는 emulator 기반 SQS/SNS
fanout 흐름이 들어 있습니다. `SnsHttpMessageParser`는 SNS HTTP JSON과 선택적
`x-amz-sns-message-type` header를 매핑하고, HTTPS가 아니거나 SNS host가 아닌
`SigningCertURL`은 거부합니다. 다만 signature 검증은 수행하지 않습니다. Notification
처리나 subscription confirmation 전에 certificate chain, `Signature`,
`SignatureVersion`, 기대한 `TopicArn`을 검증하세요.

### Kinesis — Spring Boot Coroutines 템플릿

Spring Boot Kinesis 지원의 중심은 `KinesisOperations`입니다. 명시적 shard 수나 설정된
shard 수로 stream을 만들고, record publish, shard iterator 조회, 제한된 `GetRecords`
polling, single-shard cold `Flow<Record>` 수집을 제공합니다. Listener/checkpoint runtime은
포함하지 않습니다. checkpoint가 필요하면 application code에서 명시적으로 저장하세요.

```kotlin
import io.bluetape4k.aws.spring.kinesis.KinesisOperations
import io.bluetape4k.aws.spring.kinesis.KinesisPutRecordRequest
import software.amazon.awssdk.core.SdkBytes

class StreamPublisher(
    private val kinesis: KinesisOperations,
) {
    suspend fun publish(streamName: String, payload: String): String =
        kinesis.putRecord(
            KinesisPutRecordRequest(
                streamName = streamName,
                partitionKey = "orders",
                data = SdkBytes.fromUtf8String(payload),
            )
        ).sequenceNumber()
}
```

### EventBridge — Spring Boot Coroutines Template

Spring Boot EventBridge 지원은 `EventBridgeOperations`를 중심으로 구성됩니다. Event bus
생성/삭제, rule 생성/삭제, target 추가/제거, rule/target 조회, `PutEvents`를 제공합니다.
명시적인 operations API이므로 Scheduler, 숨은 batching, retry, cleanup, listener runtime은
추가하지 않습니다.

```kotlin
import io.bluetape4k.aws.eventbridge.model.putEventsRequestEntryOf
import io.bluetape4k.aws.spring.eventbridge.EventBridgeOperations

class EventPublisher(
    private val eventBridge: EventBridgeOperations,
) {
    suspend fun publishOrderCreated(orderId: String) {
        val response = eventBridge.putEvents(
            listOf(
                putEventsRequestEntryOf(
                    source = "orders",
                    detailType = "order.created",
                    detail = """{"orderId":"$orderId"}""",
                    eventBusName = "orders",
                )
            )
        )
        require(response.failedEntryCount() == 0) { "EventBridge rejected one or more entries." }
    }
}
```

`PutEvents`, `PutTargets`, `RemoveTargets`는 요청 자체가 성공해도 일부 항목만 실패할 수
있습니다. 이후 상태를 확정하기 전에 SDK 응답의 실패 count와 항목별 실패 정보를 확인하세요.

### S3 Object IO — Coroutines (`aws-java` 모듈)

S3 coroutine 지원은 AWS SDK v2 `S3AsyncClient`와 `S3TransferManager`를 확장합니다.
Object helper는 bucket 존재 확인, 타입별 get/put overload, cold `Flow` 기반 paged
listing, 명시적인 move semantics를 제공합니다. 큰 upload/download 흐름에는
`io.bluetape4k.aws.s3.transfer.*` 아래의 transfer-manager helper를 사용합니다.

![S3 coroutine support map](docs/images/readme-diagrams/bluetape4k-aws-s3-components-24.png)

```kotlin
import io.bluetape4k.aws.s3.listAllObjects
import io.bluetape4k.aws.s3.moveObjectAtomic
import io.bluetape4k.aws.s3.putAsByteArray
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import software.amazon.awssdk.services.s3.S3AsyncClient

val s3: S3AsyncClient = S3AsyncClient.create()

suspend fun uploadObject(bucket: String, key: String, bytes: ByteArray) =
    s3.putAsByteArray(bucket, key, bytes) {
        contentLength(bytes.size.toLong())
    }

suspend fun listKeys(bucket: String, prefix: String): List<String> =
    s3.listAllObjects(bucket, prefix)
        .map { it.key() }
        .toList()

suspend fun archiveObject(bucket: String, key: String) =
    s3.moveObjectAtomic(
        srcBucketName = bucket,
        srcKey = key,
        destBucketName = bucket,
        destKey = "archive/$key",
    )
```

![S3 coroutine operation flow](docs/images/readme-diagrams/bluetape4k-aws-s3-flow-25.png)

`listAllObjects`는 반환된 `Flow`를 collect할 때 S3 호출을 시작합니다.
`nextContinuationToken`을 따라가며, S3가 truncated page라고 응답했는데 token이 없으면
빠르게 실패합니다. 일반 move는 copy-then-delete라 부분 성공이 가능합니다. source 삭제에
실패했을 때 복사된 destination object까지 되돌려야 한다면 `moveObjectAtomic`을
사용하세요.

### SQS 송수신 — Coroutines (`aws-java` 모듈)

SQS coroutine 지원은 AWS SDK v2 `SqsAsyncClient`에 queue discovery, single/batch
send, receive, visibility change, message deletion, queue deletion용 suspend helper를
더합니다. Async 계층은 SDK 호출 전에 blank queue URL, receive count, empty batch
entry를 먼저 검증합니다.

![SQS coroutine support map](docs/images/readme-diagrams/bluetape4k-aws-sqs-components-26.png)

```kotlin
import io.bluetape4k.aws.sqs.changeMessageVisibility
import io.bluetape4k.aws.sqs.deleteMessage
import io.bluetape4k.aws.sqs.receiveMessages
import io.bluetape4k.aws.sqs.send
import software.amazon.awssdk.services.sqs.SqsAsyncClient

suspend fun sendMessage(client: SqsAsyncClient, queueUrl: String, body: String) =
    client.send(queueUrl, body)

suspend fun receiveMessages(client: SqsAsyncClient, queueUrl: String) =
    client.receiveMessages(queueUrl, maxResults = 10).messages()

suspend fun processOnce(client: SqsAsyncClient, queueUrl: String) {
    val message = client.receiveMessages(queueUrl, maxResults = 1).messages().firstOrNull() ?: return
    client.changeMessageVisibility(queueUrl, message.receiptHandle(), visibilityTimeout = 30)
    process(message.body())
    client.deleteMessage(queueUrl, message.receiptHandle())
}

private fun process(body: String) = Unit
```

![SQS coroutine message flow](docs/images/readme-diagrams/bluetape4k-aws-sqs-flow-27.png)

`receiveMessages`의 `maxResults`는 SQS 범위인 `1..10`만 허용합니다. Batch send,
visibility, delete helper는 비어 있는 entry collection을 SQS로 보내기 전에 거부합니다.
메시지는 처리가 성공한 뒤 `receiptHandle`로 삭제하세요. 실패했다면 visibility timeout이
끝나 queue로 돌아가게 두는 편이 안전합니다.

### EventBridge — Core And Framework Integration

EventBridge 지원 범위는 event bus, rule, target, list, `PutEvents` 중심의
얇은 helper입니다. EventBridge Scheduler는 schedule 및 schedule group CRUD를 위한
별도 `io.bluetape4k.aws.scheduler` / `io.bluetape4k.aws.kotlin.scheduler`
표면으로 제공합니다. Java SDK v2 모듈은 sync, async, coroutine adapter를 제공하고,
AWS Kotlin SDK 모듈은 native suspend helper를 제공합니다. Spring Boot는
`EventBridgeOperations`, Ktor는 `EventBridgeKtorPlugin`을 제공합니다. Event bus/rule
작업에는 `software.amazon.awssdk:eventbridge` 또는 `aws.sdk.kotlin:eventbridge`,
Scheduler helper에는 `software.amazon.awssdk:scheduler` 또는 `aws.sdk.kotlin:scheduler`를
추가해야 합니다.

![EventBridge Spring Boot and Ktor class map](docs/images/readme-diagrams/bluetape4k-aws-eventbridge-class-32.png)

`PutEvents`, `PutTargets`, `RemoveTargets`는 일부 항목만 실패할 수 있습니다. Helper는
성공 여부를 Boolean으로 축약하지 않고 SDK 응답을 그대로 반환하므로, 호출자가 failed-entry
count와 항목별 실패 정보를 확인해야 합니다. Scheduler helper도 SDK 응답을 그대로 반환하고,
flexible time window, retry policy, list page size 같은 bluetape4k 수준의 request 범위만
검증합니다. Global endpoint, cross-account target orchestration, SDK model 타입을 넘어서는
target별 검증은 얇은 helper 계층 밖에 둡니다.

### Step Functions — 실행 helper (1.0.0 개발선)

`1.0.0` 개발선에서는 두 SDK 모듈 모두에 `StartExecution`, `StopExecution`,
`DescribeExecution`, `ListExecutions`를 위한 얇은 실행 helper가 추가됩니다. Java SDK v2
폴링은 `SfnAsyncClient`를 사용하고, Kotlin SDK는 native suspend `SfnClient`를 사용합니다.
두 모듈 모두 raw SDK 응답을 `Flow<DescribeExecutionResponse>`로 전달하는 cold Flow를
제공하며, timeout·cancellation·client 소유권은 호출자에게 남겨 둡니다. Polling helper는
Express 실행을 Standard 실행과 같은 방식으로 처리하지 않습니다.

서비스 SDK는 helper가 `compileOnly`로 유지하므로 애플리케이션에서 직접 추가해야 합니다.

```kotlin
// Java SDK v2
implementation("software.amazon.awssdk:sfn")

// AWS SDK for Kotlin
implementation("aws.sdk.kotlin:sfn")
```

Standard/Express/Map Run 경계, IAM/KMS, quota를 고려한 polling, Floci/LocalStack 검증
근거는 [Java 모듈 매뉴얼](https://bluetape4k.github.io/ko/manual/bluetape4k-aws/0.5/modules/bluetape4k-aws-java/),
[Kotlin 모듈 매뉴얼](https://bluetape4k.github.io/ko/manual/bluetape4k-aws/0.5/modules/bluetape4k-aws-kotlin/),
[테스트와 운영 가이드](https://bluetape4k.github.io/ko/manual/bluetape4k-aws/0.5/guides/testing-and-operations/)에서 확인하세요.
Emulator 결과는 운영 IAM 또는 KMS 접근 권한을 증명하지 않습니다.

### Lambda — 범위가 지정된 호출 helper (1.0.0 개발선)

`1.0.0` 개발선의 두 SDK 모듈은 얇은 `Invoke` 표면을 제공합니다. Java는 동기/async/coroutine
확장을, Kotlin은 native suspend 확장을 제공합니다. 결과는 raw SDK response와 복사한
payload를 보존하고 `FunctionError`를 반환 데이터로 취급하며 선택적으로 Lambda tail log를
디코드합니다. 서비스 SDK는 `compileOnly`이므로 런타임에
`software.amazon.awssdk:lambda` 또는 `aws.sdk.kotlin:lambda`를 추가하세요.

```kotlin
// Java SDK v2
withLambdaClient(region = Region.AP_NORTHEAST_2) { client ->
    val result = client.invokeString("orders-handler", "{\"id\":1}")
    check(!result.hasFunctionError)
}

// AWS SDK for Kotlin
withLambdaClient(region = "ap-northeast-2") { client ->
    val result = client.invokeString("orders-handler", "{\"id\":1}")
    check(!result.hasFunctionError)
}
```

함수 배포, IAM, retry, polling, 로깅 정책과 client 수명은 호출자가 소유합니다. Lambda
smoke lane은 사전 배포된 함수와 명시적인 function/region 입력이 있을 때만 opt-in으로
실행하세요. 현재 Floci는 Lambda 호출을 지원하지 않는 상태로 분류하며, 필수 입력이 없으면
client 생성 전에 건너뜁니다.

### DynamoDB — 네이티브 Suspend (`bluetape4k-aws-kotlin` 모듈)

Kotlin DynamoDB 모듈은 AWS Kotlin SDK의 native suspend API를 그대로 사용하면서
client lifecycle, table helper, request builder, `AttributeValue` 변환, scan
pagination, batch write retry를 얇게 보강합니다. `withDynamoDbClient`는 suspend
block 동안만 client를 열고 끝나면 닫습니다. Helper builder는 blank table name,
blank region, empty item map 같은 입력을 SDK 호출 전에 먼저 거부합니다.

![DynamoDB native suspend support map](docs/images/readme-diagrams/bluetape4k-aws-dynamodb-components-28.png)

```kotlin
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import io.bluetape4k.aws.kotlin.dynamodb.*
import io.bluetape4k.aws.kotlin.dynamodb.model.toAttributeValueMap
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList

// One-shot: 블록 종료 시 자동 close
suspend fun writeAndScan(tableName: String) =
    withDynamoDbClient(region = "ap-northeast-2") { client ->
        client.putItem(tableName, mapOf("id" to "u1", "name" to "Alice"))

        client.scanPaginated(tableName, exclusiveStartKey = emptyMap(), limit = 25)
            .mapNotNull { it.items }
            .toList()
    }

data class User(val id: String, val name: String)

val userMapper = DynamoItemMapper<User> { user ->
    mapOf("id" to AttributeValue.S(user.id), "name" to AttributeValue.S(user.name))
}

val userReader = DynamoItemReader<User> { item ->
    User(id = item.getValue("id").asS(), name = item.getValue("name").asS())
}

fun userAttributes(user: User) = mapOf("id" to user.id, "name" to user.name).toAttributeValueMap()
```

![DynamoDB suspend item and batch flow](docs/images/readme-diagrams/bluetape4k-aws-dynamodb-flow-29.png)

쓰기나 삭제가 DynamoDB의 `BatchWriteItem` 25개 제한을 넘을 수 있다면
`DynamoDbBatchExecutor`를 사용하세요. 요청을 25개씩 나누고 Resilience4j retry를 적용한
뒤, 응답의 `unprocessedItems`를 설정된 한도까지 재귀적으로 다시 보냅니다.

### DynamoDB Streams — Flow와 checkpoint (Java/Kotlin SDK)

Issue #469는 두 SDK 모듈에 동일한 at-least-once consumer 계약을 추가합니다.
Java SDK v2 경로는 `DynamoDbStreamsAsyncClient`, native Kotlin 경로는
`DynamoDbStreamsClient`를 사용합니다. 두 경로 모두 한 shard를 읽는
`recordFlow`와 root shard graph를 제한된 동시성으로 읽는 `shardRecordFlow`를
제공하며 시작 위치로 `TrimHorizon`, `Latest`, `AtSequenceNumber`,
`AfterSequenceNumber`를 지원합니다.

```kotlin
// AWS SDK for Kotlin
withDynamoDbStreamsClient(region = "ap-northeast-2") { client ->
    client.shardRecordFlow(
        streamArn = streamArn,
        checkpointStore = InMemoryDynamoDbStreamsCheckpointStore(),
    ).collect { envelope ->
        handle(envelope.record)
    }
}
```

Checkpoint는 downstream `emit`이 반환된 뒤에만 저장하고, 저장된 sequence를
포함해 재개하므로 재시작 시 중복이 발생할 수 있습니다. 짧은 Java 작업에는
`withDynamoDbStreamsAsyncClient`를 사용하고, 애플리케이션 범위 Java client는
shutdown에 등록하며 주입한 client의 소유권은 호출자에게 둡니다. 새 emulator
검증은 Floci를 먼저 사용합니다.

```bash
./gradlew :bluetape4k-aws-kotlin:test \
  --tests 'io.bluetape4k.aws.kotlin.dynamodbstreams.DynamoDbStreamsFlociTest' \
  -Dbluetape4k.aws.emulator=floci --no-daemon
```

Floci 실행 결과로 운영 retention, throttling, resharding timing을 증명한다고
주장하지 않습니다. 이 항목들은 AWS-only 검증 공백으로 남깁니다.

### Kinesis — 멀티 샤드 consumer와 checkpoint (Issue #470)

Java SDK v2와 AWS SDK for Kotlin 모듈에 계속해서 shard를 발견하는
`consumerFlow`를 추가했습니다. 각 shard 안에서는 polling 순서를 지키고
`maxShardConcurrency`로 동시성을 제한하며, split/merge 뒤에는 두 부모의
checkpoint가 모두 완료될 때까지 child를 시작하지 않습니다. rendezvous 경계에서
downstream `emit`이 반환된 뒤에만 checkpoint를 저장합니다. `Sequence` checkpoint는
해당 sequence를 포함해 재개하므로 정확히 한 번이 아니라 at-least-once 전달입니다.

```kotlin
withKinesisClient(endpointUrl = flociEndpoint, region = "us-east-1") { client ->
    client.consumerFlow(
        streamName = "orders",
        consumerGroup = "orders-api",
        streamIdentity = "orders-generation-1",
        position = KinesisStartingPosition.TrimHorizon,
        options = KinesisConsumerOptions(ownerId = "orders-api-${instanceId}"),
        checkpointStore = durableCheckpointStore,
        leaseStore = durableLeaseStore,
    ).collect { envelope ->
        handle(envelope.record)
    }
}
```

`KinesisCheckpointStore`와 `KinesisLeaseStore`는 호출자가 소유하는 교체 가능한
SPI입니다. 제공하는 `InMemory*` store는 테스트와 emulator 실행용이고, `Noop*` store는
명시적인 process-local 구현이므로 재시작 복구, lease takeover, durable `ShardEnd`를
보장하지 않습니다. metrics callback에는 고정 label과 해시된 stream/shard/owner token만
전달합니다. 로컬 검증은 저장소의 Floci-first 경로를 사용하고, LocalStack은 명시적인
Floci coverage gap에만 대체 경로로 사용합니다.

```bash
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-java:test --tests '*KinesisConsumerFlociTest'
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-kotlin:test --tests '*KinesisConsumerFlociTest'
```

Consumer는 AWS client나 health probe의 수명을 소유하지 않습니다. 수집 scope를 취소해
정지하고, 운영 rollout은 stop → drain → canary → scale 순서로 진행하세요. rollback 때는
마지막 durable checkpoint를 재사용하며 checkpoint를 삭제하거나 되감지 않습니다.

### CloudWatch 메트릭 — DSL (`bluetape4k-aws-kotlin` 모듈)

`bluetape4k-aws-kotlin`의 CloudWatch helper는 AWS Kotlin SDK 응답 타입을 그대로
유지하면서, 자주 쓰는 metric 흐름만 짧게 만듭니다. Scoped client를 만들고,
`MetricDatum`을 DSL로 구성한 뒤, namespace를 검증하고, 하나 또는 여러 metric을
게시하며, 필요한 경우 optional filter로 metric metadata를 조회합니다.

![CloudWatch metrics DSL support map](docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-components-30.png)

```kotlin
import aws.sdk.kotlin.services.cloudwatch.model.StandardUnit
import io.bluetape4k.aws.kotlin.cloudwatch.*
import io.bluetape4k.aws.kotlin.cloudwatch.model.metricDatumOf

suspend fun publishMetric(namespace: String, value: Double) =
    withCloudWatchClient(region = "ap-northeast-2") { client ->
        val datum = metricDatumOf(
            metricName = "RequestCount",
            value = value,
            unit = StandardUnit.Count,
        )

        client.putMetricData(namespace, datum)
        client.listMetrics(namespace = namespace, metricName = "RequestCount")
    }
```

![CloudWatch metrics publish and list flow](docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-flow-31.png)

일반적인 name/value/unit 조합은 `metricDatumOf`로 만들고, `storageResolution` 같은
추가 필드가 필요할 때는 `metricDatum { ... }` DSL을 사용하세요. `putMetricData`는
SDK 요청을 보내기 전에 빈 namespace를 거부합니다. `listMetrics`는 `namespace`,
`metricName`, `dimensions`를 모두 optional로 두기 때문에, 호출자가 필요한 폭만큼
CloudWatch metric metadata를 조회할 수 있습니다.

---

## 테스트 환경

통합 테스트는 Testcontainers 기반 AWS emulator를 사용합니다. 전환 정책은
**Floci-first** 입니다. 새로 작성하거나 마이그레이션하는 emulator-aware 테스트는
Floci를 우선하고, LocalStack은 명시적 fallback으로 유지하며, MiniStack은 동일한 SDK
smoke matrix가 반복적으로 통과하기 전까지 coverage gap 검증 후보로만 둡니다.

| 범위 | 현재 기본값 | 지원 override | 정책 |
|---|---|---|---|
| `bluetape4k-aws-spring-boot` | Floci | `floci`, `localstack`, `ministack` | Floci-first; MiniStack은 비교 실행 전용 |
| Java/Kotlin SDK wrapper tests | Floci | `floci`, `localstack` | Floci-first; Floci coverage gap은 LocalStack으로 검증 |
| Ktor 및 AWS example tests | Floci | emulator-aware 모듈은 `floci`, `localstack` | Floci-first; Floci coverage gap은 LocalStack으로 검증 |

모듈이 지원하는 경우 `-Dbluetape4k.aws.emulator=...` 로 전환할 수 있습니다. 지원
서비스 수 주장만으로 repository-wide 기본값을 바꾸지 말고, 해당 모듈이 실제 사용하는
AWS SDK 호출을 기준으로 검증해야 합니다.

Testcontainer launcher는 단일 module test JVM 안에서만 공유됩니다. 커밋되는 테스트,
예제, application startup path, CI는 Docker-level container reuse를 암묵적으로
활성화하면 안 됩니다. 로컬 실험에서만 재사용이 필요하면 개발자가 repository default
밖에서 wrapper를 `reuse = true` 로 명시 생성해야 하며, 그 선택은 CI에 들어가면 안
됩니다.

```bash
# 핵심 Floci-first 모듈
./gradlew :bluetape4k-aws-java:test
./gradlew :bluetape4k-aws-kotlin:test
./gradlew :bluetape4k-aws-spring-boot:test
./gradlew :bluetape4k-aws-ktor:test
./gradlew :aws-ktor-dynamodb-examples:test
./gradlew :aws-ktor-exposed-examples:test
./gradlew :aws-ktor-sqs-examples:test
./gradlew :aws-ktor-service-coverage-examples:test
./gradlew :aws-spring-boot-dynamodb-examples:test
./gradlew :aws-spring-boot-exposed-examples:test
./gradlew :aws-spring-boot-s3-examples:test
./gradlew :aws-spring-boot-sqs-examples:test

# emulator coverage gap 명시 fallback
./gradlew :bluetape4k-aws-java:test -Dbluetape4k.aws.emulator=localstack
./gradlew :bluetape4k-aws-kotlin:test -Dbluetape4k.aws.emulator=localstack
./gradlew :bluetape4k-aws-ktor:test -Dbluetape4k.aws.emulator=localstack

# 지원 모듈의 비교 전용 smoke 실행
./gradlew :bluetape4k-aws-spring-boot:test -Dbluetape4k.aws.emulator=ministack
```

---

## 라이선스

MIT License — [LICENSE](LICENSE) 참조.
