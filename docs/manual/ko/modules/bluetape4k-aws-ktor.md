---
manualId: "bluetape4k-aws-ktor"
id: "bluetape4k-aws-ktor"
title: "AWS Ktor 통합"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-aws-ktor"
sourceDir: "aws-ktor"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-ktor
---

# AWS Ktor 통합

> 0.4.0 릴리스 소스를 기준으로 작성한 라이브러리 매뉴얼입니다.

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

AWS 서비스 SDK는 `compileOnly` 정책 때문에 실제 사용하는 모듈을 런타임 의존성으로 별도 추가해야 합니다.

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

`bluetape4k-dependencies`를 통해 이 라이브러리를 추가하고, 설치할 plugin이 사용하는 Java 또는 Kotlin AWS 서비스 SDK만 더하세요.

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

아래 그림은 `0.4.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS Ktor 아키텍처

[![Bluetape4k AWS Ktor 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-ktor-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-ktor-architecture-01.svg)

_배포본 README: [`aws-ktor/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/aws-ktor/README.ko.md)_

### Ktor S3 Access Grants 흐름

[![Ktor S3 Access Grants 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-ktor-s3-access-grants-flow-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-ktor-s3-access-grants-flow-01.svg)

_배포본 README: [`aws-ktor/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/aws-ktor/README.ko.md)_

### Advanced S3 helper 아키텍처

[![Advanced S3 helper 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-ktor-s3-advanced-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-ktor-s3-advanced-architecture-01.svg)

_배포본 README: [`aws-ktor/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/aws-ktor/README.ko.md)_

### Advanced S3 upload/load 처리 순서

[![Advanced S3 upload/load 처리 순서](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-ktor-s3-advanced-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-ktor-s3-advanced-sequence-01.svg)

_배포본 README: [`aws-ktor/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/aws-ktor/README.ko.md)_

### SQS Consumer And Publisher 다이어그램

[![SQS Consumer And Publisher 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-ktor-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-ktor-sequence-01.svg)

_배포본 README: [`aws-ktor/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/aws-ktor/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [릴리스 소스: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4Plugin.kt`](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4Plugin.kt)
- [릴리스 소스: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerPlugin.kt`](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerPlugin.kt)
- [릴리스 테스트: SQS runtime 실패 처리](../../../../aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntimeFailureTest.kt)
