# Spring Boot SQS/SNS 예제

한국어 | [English](README.md)

`aws-spring-boot` 의 SQS/SNS 지원을 보여주는 Spring Boot 4 실행 예제다.
LocalStack 을 개발 환경으로 사용하며 REST 발송, `@SqsListener` 수신, SNS → SQS
팬아웃, DLQ redrive 설정을 포함한다.

## 아키텍처

![aws spring boot sqs examples Architecture diagram](../../docs/images/readme-diagrams/examples-aws-spring-boot-sqs-examples-architecture-01.png)

## 의존성 형태

```kotlin
dependencies {
    implementation(project(":aws-spring-boot"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("software.amazon.awssdk:sqs")
    implementation("software.amazon.awssdk:sns")
}
```

`aws-spring-boot` 은 AWS 서비스 SDK를 `compileOnly` 로 둔다. 애플리케이션은 실제로
사용하는 SQS/SNS SDK 모듈을 런타임 의존성으로 추가한다.

## 설정

```yaml
bluetape4k:
  aws:
    sqs:
      region: us-east-1
      endpoint-override: http://localhost:4566
      listener:
        max-messages: 1
        wait-time-seconds: 1
      queues:
        orders:
          url: http://localhost:4566/000000000000/orders
    sns:
      region: us-east-1
      endpoint-override: http://localhost:4566

example:
  aws:
    sqs:
      listener-queue: orders
```

`example.aws.sqs.listener-queue` 는 다음 listener 에서 사용한다.

```kotlin
@SqsListener(queue = "\${example.aws.sqs.listener-queue:orders}")
fun handle(message: String) { ... }
```

## REST API

| Method | Path | 목적 |
|---|---|---|
| `POST` | `/spring/sqs/queues/{queueName}` | SQS 큐 생성 |
| `POST` | `/spring/sqs/messages?queue={queueNameOrUrl}` | 메시지 발송 |
| `GET` | `/spring/sqs/messages?queue={queueNameOrUrl}&deleteAfterReceive=true` | 메시지 수신, 선택적 삭제 |
| `POST` | `/spring/sqs/fanout` | SNS topic, SQS queue, queue policy, subscription 생성 |
| `POST` | `/spring/sqs/topics/messages` | SNS 메시지 publish |
| `POST` | `/spring/sqs/dlq` | DLQ redrive policy 가 있는 source queue 생성 |
| `GET` | `/spring/sqs/listener/messages` | listener 가 처리한 메시지 조회 |

## Fanout 요청

```json
{
  "topicName": "orders",
  "queueName": "orders-events"
}
```

서비스는 topic 과 queue 를 만들고, topic 이 queue 에 메시지를 보낼 수 있도록 정책을
설정한 뒤 subscription 을 생성한다.

## DLQ 요청

```json
{
  "queueName": "orders",
  "dlqName": "orders-dlq",
  "maxReceiveCount": 3
}
```

예제는 DLQ 를 먼저 만들고 ARN 을 조회한 뒤, source queue 를 `RedrivePolicy` 와 함께
생성한다.

## 테스트

```bash
./gradlew :aws-spring-boot-sqs-examples:test -Dbluetape4k.aws.emulator=localstack
```

## AOT

모든 Spring Boot 예제는 GraalVM Native Build Tools 를 통해 Spring AOT 태스크가
생성되도록 구성합니다. 이 예제는 다음 명령으로 검증합니다.

```bash
./gradlew :aws-spring-boot-sqs-examples:processAot :aws-spring-boot-sqs-examples:processTestAot
```
