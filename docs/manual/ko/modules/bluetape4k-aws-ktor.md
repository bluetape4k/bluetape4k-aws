---
manualId: "bluetape4k-aws-ktor"
id: "bluetape4k-aws-ktor"
title: "AWS Ktor 통합"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-aws-ktor"
sourceDir: "aws-ktor"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-ktor
---

# AWS Ktor 통합

> 0.5.0 릴리스 소스를 기준으로 작성한 라이브러리 매뉴얼입니다.

## 제공하는 기능 {#problem}

Ktor 3 client 서명과 S3, DynamoDB, SQS, Exposed, CloudWatch, IMDS, Access Grants, S3 Vectors용 server plugin·runtime을 제공합니다.

## 사용하기 좋은 경우 {#when-to-use}

Spring의 수명 주기 모델 없이 Ktor 애플리케이션에서 coroutine 중심 AWS 통합이 필요할 때 사용합니다.

## 의존성 좌표 {#coordinates}

애플리케이션에서는 중앙 BOM 버전 하나만 선택합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-ktor")
}
```

`aws-ktor`는 API aggregator이므로 게시된 POM이 Java/Kotlin wrapper, Ktor
client core, public plugin에서 사용하는 AWS SDK 모듈을 노출합니다. 이 모듈은
generated POM을 기준으로 의존성 소유권을 판단합니다.

| 구분 | 게시 scope | 애플리케이션에서 할 일 |
| --- | --- | --- |
| Bluetape wrapper, Ktor core, public Java SDK 타입 | `compile` | `bluetape4k-aws-ktor`만 추가하고 `aws-ktor` plugin 설치만을 위해 wrapper나 서비스 SDK를 다시 선언하지 않음 |
| AWS Kotlin DynamoDB public 타입 | `compile` | `DynamoDbKtorPlugin`에는 별도의 Kotlin DynamoDB SDK 선언이 필요하지 않음 |
| Ktor engine, Jackson, Micrometer, Exposed, JDBC와 기타 runtime 선택 | `compileOnly` 또는 애플리케이션 소유 | 애플리케이션이 실제로 설치하는 통합과 driver만 추가 |

하위 `bluetape4k-aws-java`와 `bluetape4k-aws-kotlin` wrapper 모듈에는 일반적인
`compileOnly` 규칙이 적용됩니다. 그러나 그 규칙을 `aws-ktor` aggregator에
그대로 적용하지 말고 현재 `build.gradle.kts`와 generated POM을 확인하세요.
애플리케이션 코드가 `aws-ktor` plugin API 밖에서 SDK를 직접 호출할 때만
서비스 SDK를 직접 선언하면 됩니다.

선택 기준은 plugin API입니다. `SqsConsumer`를 비롯한 Java 서비스 plugin은
AWS SDK for Java v2를 사용하고, `DynamoDbKtorPlugin`은 AWS Kotlin SDK를
사용합니다. 두 SDK 선택 모두 aggregator의 transitive dependency에 이미
반영되어 있습니다.

## 핵심 개념 {#concepts}

`AwsSigV4Plugin`이 Ktor client 요청에 서명합니다. Application plugin은 typed runtime을 만들고 attribute에 보관하며 백그라운드 작업을 시작한 뒤 애플리케이션 종료 시 소유 자원을 닫습니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
install(SqsConsumer) {
    queueUrl = config.queueUrl
    deleteOnSuccess = true
    onMessage<OrderMessage> { message -> process(message) }
}
```

visibility와 acknowledgement 정책을 처리 방식에 맞게 설정하세요.

## 작업별 API {#api-by-task}

SigV4 client 인증, S3 REST·암호화 helper, DynamoDB repository runtime, SQS consumer, Exposed database plugin, CloudWatch·Logs, IMDS, Access Grants, S3 Vectors를 제공합니다.

## 권장 패턴 {#patterns}

client와 백그라운드 작업의 소유자를 한 곳으로 정하고, region·credentials·endpoint를 호출마다 만들지 말고 애플리케이션 경계에서 구성하세요.

## 연동 {#integrations}

`bluetape4k-dependencies`를 통해 이 라이브러리를 추가하고, 애플리케이션이
소유하는 runtime 통합만 더하세요. 기본 plugin이 사용하는 Java SQS와 Kotlin
DynamoDB SDK 타입은 이미 transitive로 게시되므로, plugin 설치만을 위해
서비스 SDK를 다시 추가할 필요가 없습니다. SDK를 직접 호출한다면 해당
서비스 모듈을 명시적으로 선언할 수 있습니다.

## 설정 {#configuration}

region, service, credential provider, signing 옵션, queue polling, 동시성, endpoint override, 종료 timeout을 애플리케이션 설정으로 관리합니다.

## 실패 유형과 해결 방법 {#failures}

잘못된 SigV4 service·region, 이미 소비된 request body, clock skew, 누락된 서비스 SDK, plugin 중복 설치, 정리되지 않은 coroutine 종료를 먼저 확인하세요.

## 운영 {#operations}

애플리케이션의 구조화된 scope를 사용하고 consumer 동시성을 제한하세요. Micrometer 관측을 노출하고 plugin이 멈춘 뒤 공유 client를 닫는 순서를 지켜야 합니다.

## 테스트 {#testing}

Ktor `testApplication`, 서명용 고정 credentials·clock, 서비스 runtime용 Floci를 사용하세요. stop hook 이후 실행 중인 job이나 client가 남지 않는지도 검증합니다.

## 학습 경로와 예제 {#workshops}

`client-and-sigv4` → `service-plugins` → `runtime-lifecycle` 순서로 읽고 Ktor S3·DynamoDB·SQS·Exposed 예제를 실행하세요.

## 제약 사항 {#limitations}

Ktor REST helper가 AWS SDK 전체 기능을 대신하지는 않습니다. plugin을 설치해도 AWS 리소스가 자동으로 생성되지 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS Ktor 아키텍처

[![Bluetape4k AWS Ktor 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-architecture-01.svg)

_배포본 README: [`aws-ktor/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-ktor/README.ko.md)_

### Ktor S3 Access Grants 흐름

[![Ktor S3 Access Grants 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-s3-access-grants-flow-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-s3-access-grants-flow-01.svg)

_배포본 README: [`aws-ktor/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-ktor/README.ko.md)_

### Advanced S3 helper 아키텍처

[![Advanced S3 helper 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-s3-advanced-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-s3-advanced-architecture-01.svg)

_배포본 README: [`aws-ktor/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-ktor/README.ko.md)_

### Advanced S3 upload/load 처리 순서

[![Advanced S3 upload/load 처리 순서](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-s3-advanced-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-s3-advanced-sequence-01.svg)

_배포본 README: [`aws-ktor/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-ktor/README.ko.md)_

### SQS Consumer And Publisher 다이어그램

[![SQS Consumer And Publisher 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-sequence-01.svg)

_배포본 README: [`aws-ktor/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-ktor/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [릴리스 소스: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4Plugin.kt`](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4Plugin.kt)
- [릴리스 소스: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerPlugin.kt`](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerPlugin.kt)
- [릴리스 테스트: SQS runtime 실패 처리](../../../../aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntimeFailureTest.kt)
