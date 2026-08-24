---
title: 자동 설정
description: 조건부 AWS 서비스 bean, properties와 back-off 규칙을 설명합니다.
manualId: bluetape4k-aws-spring-boot
chapterId: auto-configuration
---

# 자동 설정

Spring 모듈은 조건부 자동 설정을 사용합니다. 서비스 SDK 클래스와 활성화 설정이 있을 때만 해당 통합이 나타납니다. 덕분에 라이브러리는 여러 서비스를 지원하면서도 애플리케이션 런타임 classpath에는 선택한 서비스만 남길 수 있습니다.

## 의존성 경계

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot")
    implementation("software.amazon.awssdk:s3")
}
```

애플리케이션은 중앙 BOM 버전과 서비스 SDK만 고릅니다. AWS 저장소 라이브러리 버전을 따로 선택하지 않습니다.

## 공통 기본값과 서비스별 override

`bluetape4k.aws` 아래의 `AwsProperties`가 enabled, region, endpoint override, 선택적인 web identity credentials를 제공합니다. 서비스별 properties가 공통 기본값보다 우선합니다. 서명 scope에는 region이 필요하므로 endpoint override를 사용할 때도 region을 설정해야 합니다.

## Back-off는 정상 동작이다

예상한 bean이 없다면 수동 bean부터 추가하지 말고 condition report를 확인하세요. 흔한 원인은 `compileOnly` 서비스 SDK 누락, disabled property, 또는 애플리케이션이 같은 타입의 bean을 제공해 자동 설정이 물러난 경우입니다.

## Testcontainers ServiceConnection

Floci와 LocalStack 테스트에서는 endpoint와 credentials를
`DynamicPropertySource`로 주입하던 코드를 named Spring Boot
`@ServiceConnection`으로 옮깁니다. Boot 4.1 annotation은 하나의 service name을
받으며 테스트 classpath에는 선택적 dependency alias를 추가합니다.

```kotlin
testImplementation(libs.spring.boot.testcontainers)
testImplementation(bt4k.bluetape4k.testcontainers)

@Container
@ServiceConnection(name = "s3")
val floci: FlociServer = FlociServer.Launcher.floci
```

details에는 endpoint, region, 테스트 credentials만 들어갑니다. 선택적 의존성이나
annotation이 없으면 기존 properties-only fallback이 계속 동작합니다.
`bluetape4k.aws.emulator`는 backend launcher를 선택할 뿐 resource URL을
제공하지 않습니다. 이름 없는 `@ServiceConnection`은 명시적인 all-services
opt-in이며 named 선언과 함께 사용하지 않습니다.

factory는 애플리케이션 resource를 만들지 않습니다. fixture가 SQS queue URL,
SNS topic ARN, DynamoDB table name, Kinesis stream name을 만들고 소유한 literal만
정리합니다. S3 테스트는 하나의 bucket에서만 실행하고 bucket과 object key에
`owner-token`을 넣습니다. `wildcard` 또는 외부 literal은 AWS 호출 전에
거부합니다. lifecycle 순서는 fixture `cleanup`, application context close,
Testcontainers teardown입니다. cleanup 오류는 secret-free 형태로 바꾸어
suppressed 처리하고 cancellation은 다시 전파합니다. optional factory
dependency가 없으면 조용히 credentials를 바꾸지 말고 `FACTORY_LINKAGE` 오류로
실패시킨 뒤 테스트 classpath를 고치거나 annotation을 제거하세요.

## 사용자 정의

region과 endpoint 설정만으로 부족하면 제공된 client builder customization hook을 사용하세요. 서비스 bean마다 나중에 손대기보다 한 곳에서 builder를 조정하는 편이 안전합니다.

## 시작 단계 검증

잘못된 endpoint·region 조합, queue 설정, pool 크기, 함께 쓸 수 없는 credentials mode는 시작할 때 실패시켜야 합니다. 환경 post processor는 원격 설정을 시작 과정에서 한 번 읽고 요청 경로에서는 반복하지 않아야 합니다.

## 근거 자료

- [자동 설정 목록](../../../../../aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
- [공통 AWS properties](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/AwsProperties.kt)
- [AWS 자동 설정](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/AwsAutoConfiguration.kt)
