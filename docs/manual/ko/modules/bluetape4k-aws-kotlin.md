---
manualId: "bluetape4k-aws-kotlin"
id: "bluetape4k-aws-kotlin"
title: "AWS SDK for Kotlin 확장"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-aws-kotlin"
sourceDir: "aws-kotlin"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-kotlin
---

# AWS SDK for Kotlin 확장

> 0.4.0 릴리스 소스를 기준으로 작성한 라이브러리 매뉴얼입니다.

## 제공하는 기능 {#problem}

native suspend 기반 AWS SDK for Kotlin에 빌더, 모델 변환, Flow helper와 클라이언트 수명 주기 유틸리티를 더합니다.

## 사용하기 좋은 경우 {#when-to-use}

애플리케이션이 coroutine 중심이고 Java SDK v2 클라이언트와의 상호 운용이 필요하지 않을 때 선택하세요.

## 의존성 좌표 {#coordinates}

애플리케이션에서는 중앙 BOM 버전 하나만 선택합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-kotlin")
}
```

AWS 서비스 SDK는 `compileOnly` 정책 때문에 실제 사용하는 모듈을 런타임 의존성으로 별도 추가해야 합니다.

## 핵심 개념 {#concepts}

서비스 호출 자체가 SDK의 suspend 함수입니다. 이 모듈은 간결한 요청 빌더, pagination·Flow 패턴, `with...Client` 수명 주기 helper를 제공합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
withS3Client(region = region) { s3 ->
    s3.putFromByteArray(bucket, key, bytes)
    s3.getAsByteArray(bucket, key)
}
```

`withS3Client` 블록이 끝나면 임시 클라이언트가 닫힙니다.

## 작업별 API {#api-by-task}

DynamoDB 모델 DSL·배치, S3 객체 작업, SQS/SNS, SES, KMS, CloudWatch, Kinesis record Flow, STS와 HTTP engine provider를 제공합니다.

## 권장 패턴 {#patterns}

client와 백그라운드 작업의 소유자를 한 곳으로 정하고, region·credentials·endpoint를 호출마다 만들지 말고 애플리케이션 경계에서 구성하세요.

## 연동 {#integrations}

사용할 `aws.sdk.kotlin:<service>` 모듈을 명시적으로 추가하세요. native Kotlin SDK client가 알맞은 Ktor 통합에서도 사용할 수 있습니다.

## 설정 {#configuration}

클라이언트를 만들 때 region, credential provider, endpoint, retry strategy, CRT 또는 OkHttp engine을 선택합니다.

## 실패 유형과 해결 방법 {#failures}

Java SDK v2 모델과 Kotlin SDK 모델을 섞지 마세요. 제한 없는 Flow 수집, 누락된 서비스 모듈, 닫히는 범위 밖에서 생성한 client를 점검하세요.

## 운영 {#operations}

호출량이 많다면 장수명 client를 공유하고, 범위가 짧은 작업에는 `with...Client`를 사용하세요. 선택한 HTTP engine과 timeout 정책도 운영 문서에 남겨야 합니다.

## 테스트 {#testing}

서비스 테스트는 Floci에서 먼저 실행하고, native Kotlin SDK 기능을 지원하지 않을 때만 emulator를 명시적으로 바꾸세요.

## 학습 경로와 예제 {#workshops}

S3 요청 빌더부터 시작해 DynamoDB 모델 변환을 익힌 뒤 Kinesis 또는 pagination Flow 처리로 넓혀 가세요.

## 제약 사항 {#limitations}

Java SDK v2 호환 wrapper가 아니므로 타입과 서비스 지원 범위가 다를 수 있습니다.

## 근거 자료 {#sources}

- [릴리스 소스: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/s3/S3ClientSupport.kt`](../../../../aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/s3/S3ClientSupport.kt)
- [릴리스 소스: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlow.kt`](../../../../aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlow.kt)
- [릴리스 테스트: emulator 선택](../../../../aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/AbstractAwsTest.kt)
