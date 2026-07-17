---
manualId: "bluetape4k-aws-java"
id: "bluetape4k-aws-java"
title: "AWS SDK for Java v2 확장"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-aws-java"
sourceDir: "aws-java"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-java
---

# AWS SDK for Java v2 확장

> 0.4.0 릴리스 소스를 기준으로 작성한 라이브러리 매뉴얼입니다.

## 제공하는 기능 {#problem}

AWS SDK for Java v2 클라이언트에 Kotlin 빌더와 동기, `CompletableFuture`, coroutine API를 더합니다.

## 사용하기 좋은 경우 {#when-to-use}

Java SDK v2 클라이언트를 사용하거나 동기·비동기 경로가 함께 필요할 때, 또는 Spring Boot와 연동할 때 적합합니다.

## 의존성 좌표 {#coordinates}

애플리케이션에서는 중앙 BOM 버전 하나만 선택합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
}
```

AWS 서비스 SDK는 `compileOnly` 정책 때문에 실제 사용하는 모듈을 런타임 의존성으로 별도 추가해야 합니다.

## 핵심 개념 {#concepts}

Factory가 클라이언트를 만들고 extension이 AWS 클라이언트 타입을 그대로 확장합니다. async extension은 future를, coroutine extension은 이를 기다리는 suspend API를 제공합니다. 만든 클라이언트의 종료 책임은 애플리케이션에 있습니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val s3 = S3AsyncClient.create()
try {
    s3.putAsByteArray(bucket, key, payload)
    val objects = s3.listAllObjects(bucket).toList()
} finally {
    s3.close()
}
```

공유 클라이언트는 애플리케이션 수명 주기에 맞춰 한 번만 닫습니다.

## 작업별 API {#api-by-task}

S3 객체·전송, DynamoDB Enhanced 저장소·배치, SQS/SNS 메시징, KMS, CloudWatch, Kinesis, SES, STS와 요청 모델 빌더를 제공합니다.

## 권장 패턴 {#patterns}

client와 백그라운드 작업의 소유자를 한 곳으로 정하고, region·credentials·endpoint를 호출마다 만들지 말고 애플리케이션 경계에서 구성하세요.

## 연동 {#integrations}

Spring Boot와 Ktor 모듈이 이 라이브러리를 기반으로 동작합니다. 런타임에는 `software.amazon.awssdk:s3`처럼 실제로 쓰는 Java SDK v2 서비스만 추가하세요.

## 설정 {#configuration}

AWS client builder에서 region, credentials, endpoint override, HTTP 구현체, 재시도와 timeout을 정한 뒤 클라이언트를 공유하세요.

## 실패 유형과 해결 방법 {#failures}

빈 queue URL, 끝까지 읽지 않은 S3 pagination, 누락된 서비스 JAR, coroutine 스레드에서의 blocking 호출, 닫히지 않은 async client를 먼저 점검하세요.

## 운영 {#operations}

thread-safe client는 재사용하고 동시성을 제한하세요. 재시도와 throttling을 관측하고 S3 이동이 copy 후 delete라는 점을 운영 절차에 반영해야 합니다.

## 테스트 {#testing}

endpoint override와 테스트용 고정 자격 증명으로 Floci를 먼저 사용하세요. Floci가 제공하지 않는 서비스 동작만 LocalStack으로 보완합니다.

## 학습 경로와 예제 {#workshops}

`client-lifecycle` → `sync-async-coroutines` → `service-patterns` 순서로 읽고, 릴리스에 포함된 S3·DynamoDB·SQS 예제로 이어가세요.

## 제약 사항 {#limitations}

이 artifact는 모든 AWS 서비스 SDK를 런타임에 끌어오지 않습니다. coroutine helper를 사용해도 동기 클라이언트가 비동기 클라이언트로 바뀌지는 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.4.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS Java 아키텍처

[![Bluetape4k AWS Java 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-java-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-java-architecture-01.svg)

_배포본 README: [`aws-java/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/aws-java/README.ko.md)_

### Bluetape4k AWS Java operation 처리 흐름

[![Bluetape4k AWS Java operation 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-java-flow-02.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-java-flow-02.svg)

_배포본 README: [`aws-java/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/aws-java/README.ko.md)_

### Bluetape4k AWS Java coroutine 시퀀스 다이어그램

[![Bluetape4k AWS Java coroutine 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-java-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-java-sequence-03.svg)

_배포본 README: [`aws-java/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/aws-java/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [릴리스 소스: `aws-java/src/main/kotlin/io/bluetape4k/aws/s3/S3AsyncClientCoroutinesExtensions.kt`](../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/s3/S3AsyncClientCoroutinesExtensions.kt)
- [릴리스 소스: `aws-java/src/main/kotlin/io/bluetape4k/aws/dynamodb/repository/DynamoDbCoroutineRepository.kt`](../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/dynamodb/repository/DynamoDbCoroutineRepository.kt)
- [릴리스 테스트: emulator 선택](../../../../aws-java/src/test/kotlin/io/bluetape4k/aws/AbstractAwsTest.kt)
