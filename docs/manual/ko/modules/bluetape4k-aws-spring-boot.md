---
manualId: "bluetape4k-aws-spring-boot"
id: "bluetape4k-aws-spring-boot"
title: "AWS Spring Boot 통합"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-aws-spring-boot"
sourceDir: "aws-spring-boot"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-spring-boot
---

# AWS Spring Boot 통합

> 0.4.0 릴리스 소스를 기준으로 작성한 라이브러리 매뉴얼입니다.

## 제공하는 기능 {#problem}

선택한 AWS 서비스를 위한 Spring Boot 4 자동 설정, coroutine template, repository, listener, 설정 소스와 Micrometer 계측을 제공합니다.

## 사용하기 좋은 경우 {#when-to-use}

Spring이 AWS client와 애플리케이션용 template의 수명 주기를 관리하되 서비스 SDK 의존성은 애플리케이션이 명시적으로 고르고 싶을 때 적합합니다.

## 의존성 좌표 {#coordinates}

애플리케이션에서는 중앙 BOM 버전 하나만 선택합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot")
}
```

AWS 서비스 SDK는 `compileOnly` 정책 때문에 실제 사용하는 모듈을 런타임 의존성으로 별도 추가해야 합니다.

## 핵심 개념 {#concepts}

서비스 SDK가 없으면 조건부 자동 설정은 물러납니다. properties로 region, endpoint, credentials와 서비스 동작을 조정하고, template은 suspend 작업을 제공하며, listener container가 백그라운드 작업을 소유합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
@Service
class ObjectStore(private val s3: S3Operations) {
    suspend fun put(bucket: String, key: String, bytes: ByteArray) =
        s3.upload(bucket, key, bytes)
}
```

사용할 서비스 SDK가 classpath에 있어야 해당 bean이 생성됩니다.

## 작업별 API {#api-by-task}

S3·Transfer Manager, DynamoDB repository·DAX, SQS listener/runtime, SNS, SES, KMS, CloudWatch, IMDS, Secrets Manager, Parameter Store, S3 Access Grants·Vectors, Exposed를 지원합니다.

## 권장 패턴 {#patterns}

client와 백그라운드 작업의 소유자를 한 곳으로 정하고, region·credentials·endpoint를 호출마다 만들지 말고 애플리케이션 경계에서 구성하세요.

## 연동 {#integrations}

중앙 BOM을 가져오고 이 라이브러리는 버전 없이 추가하세요. 활성화할 자동 설정이 사용하는 `software.amazon.awssdk:<service>` 모듈만 더합니다.

## 설정 {#configuration}

`bluetape4k.aws` 설정 영역과 emulator용 endpoint override를 사용하세요. client builder를 애플리케이션에서 세밀하게 제어해야 하면 customizer bean을 제공합니다.

## 실패 유형과 해결 방법 {#failures}

bean이 생기지 않는 경우는 대부분 서비스 SDK가 없거나 조건이 맞지 않기 때문입니다. SQS visibility, listener 동시성, payload 변환, 종료 timeout은 명시적으로 조정하세요.

## 운영 {#operations}

Micrometer 지표를 노출하고 listener acknowledgement 정책을 분명히 하세요. 직접 만든 client는 닫고 환경 property source 조회는 요청 처리 경로에서 반복하지 않습니다.

## 테스트 {#testing}

조건부 bean은 `ApplicationContextRunner`로 확인하고 활성화한 서비스는 Floci 통합 테스트로 검증하세요. 성공적인 전송뿐 아니라 listener 종료와 redelivery도 테스트해야 합니다.

## 학습 경로와 예제 {#workshops}

`auto-configuration` → `storage-and-messaging` → `runtime-operations` 순서로 읽고 릴리스에 포함된 Spring Boot 예제 네 개를 실행하세요.

## 제약 사항 {#limitations}

이 모듈은 awspring이 아니며 모든 AWS 서비스를 자동으로 켜지 않습니다. 선택 기능은 필요한 클래스와 설정이 있을 때만 활성화됩니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.4.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS Spring Boot 아키텍처

[![Bluetape4k AWS Spring Boot 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-spring-boot-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-spring-boot-architecture-01.svg)

_배포본 README: [`aws-spring-boot/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/aws-spring-boot/README.ko.md)_

### Bluetape4k AWS Spring Boot configuration 처리 흐름

[![Bluetape4k AWS Spring Boot configuration 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-spring-boot-flow-02.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-spring-boot-flow-02.svg)

_배포본 README: [`aws-spring-boot/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/aws-spring-boot/README.ko.md)_

### Bluetape4k AWS Spring Boot SQS listener 시퀀스 다이어그램

[![Bluetape4k AWS Spring Boot SQS listener 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-spring-boot-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/docs/images/readme-diagrams/aws-spring-boot-sequence-03.svg)

_배포본 README: [`aws-spring-boot/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/aws-spring-boot/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [릴리스 소스: `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`](../../../../aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
- [릴리스 소스: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt`](../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt)
- [릴리스 테스트: 자동 설정](../../../../aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/AwsAutoConfigurationTest.kt)
