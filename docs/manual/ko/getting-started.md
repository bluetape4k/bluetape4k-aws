---
manualId: "getting-started"
title: "Bluetape4k AWS 시작하기"
locale: "ko"
releaseRef: "0.5.0"
---

# Bluetape4k AWS 시작하기

## 버전은 한 곳에서만 정한다

중앙 `bluetape4k-dependencies` BOM을 사용한다. 애플리케이션에서 이 버전을 한 번 정한 다음, 필요한 bluetape4k 아티팩트와 AWS 서비스 아티팩트를 버전 없이 선언하면 된다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))

    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:s3")
}
```

`<version>`에는 애플리케이션에서 사용할 `bluetape4k-dependencies` 릴리스 버전을 넣는다. 이 매뉴얼에 표시한 `0.5.0`은 문서를 검증한 AWS 소스 기준일 뿐, 사용자가 따로 맞춰야 할 두 번째 버전이 아니다.

AWS 서비스 SDK는 래퍼 라이브러리에서 `compileOnly`로 선언한다. 실제로 호출하는 서비스 아티팩트는 애플리케이션에 직접 추가해야 한다. S3를 예로 들면 Java SDK 경로에서는 `software.amazon.awssdk:s3`, Kotlin SDK 경로에서는 `aws.sdk.kotlin:s3`가 필요하다. 쓰지 않는 AWS 서비스가 런타임 클래스패스에 따라오는 것을 막기 위한 구조다.

## SDK 경로를 고른다

이미 Java SDK 클라이언트를 사용하고 있거나 Enhanced DynamoDB 클라이언트, S3 Transfer Manager, `CompletableFuture` 기반 라이브러리와 연동한다면 Java SDK v2 경로가 잘 맞는다. Bluetape4k는 이 모델에 동기 도우미, 비동기 확장 함수, `suspend` 어댑터를 더한다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:dynamodb-enhanced")
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:sqs")
}
```

처음부터 네이티브 `suspend` 클라이언트와 Kotlin 요청 빌더를 중심으로 구성한다면 Kotlin SDK 경로를 선택한다. 두 경로를 모두 쓸 수 있다는 이유만으로 함께 추가하지는 말자. SDK가 둘이면 클라이언트, HTTP 엔진, 설정과 종료 책임도 둘로 늘어난다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-kotlin")
    implementation("aws.sdk.kotlin:dynamodb")
    implementation("aws.sdk.kotlin:s3")
    implementation("aws.sdk.kotlin:sqs")
}
```

자세한 기준은 [SDK 선택 가이드](guides/sdk-selection.md)에 정리했다.

## 애플리케이션 연동은 하나만 더한다

Spring Boot 애플리케이션은 보통 `bluetape4k-aws-spring-boot`부터 시작한다. 필요한 자동 설정이 활성화되도록 사용할 서비스 SDK를 함께 추가한다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot")
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:sqs")
}
```

Ktor에서는 `bluetape4k-aws-ktor`로 SigV4, S3 REST 클라이언트, SQS 소비자, DynamoDB 저장소, CloudWatch, IMDS와 AWS 설정 기반 Exposed 연동을 구성한다. 이 public plugin API가 사용하는 Java SQS와 Kotlin DynamoDB SDK 타입은 `aws-ktor`가 이미 transitive로 노출하므로, 애플리케이션이 소유하는 runtime 통합만 추가한다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-ktor")
    implementation("io.ktor:ktor-server-core") // server plugin을 설치할 때
    implementation("io.ktor:ktor-client-cio") // CIO client engine을 사용할 때
}
```

애플리케이션 코드가 `aws-ktor` plugin API 밖에서 SDK를 직접 호출할 때만
`software.amazon.awssdk:sqs` 또는 `aws.sdk.kotlin:dynamodb`를 직접 선언한다.
`bluetape4k-jackson3`, Micrometer, Exposed와 JDBC driver도 해당 선택적 통합을
사용할 때만 추가한다.

문법 취향보다는 설정과 자원의 수명 주기를 누가 맡는지 보고 선택해야 한다. [Spring Boot와 Ktor 비교](guides/spring-vs-ktor.md)에 차이를 정리했다.

## 가장 가까운 예제 하나를 검증한다

저장소 전체를 먼저 실행하지 말고 애플리케이션 경계와 가장 가까운 예제를 고른다.

```bash
./gradlew :aws-ktor-s3-examples:test
./gradlew :aws-spring-boot-dynamodb-examples:test
./gradlew :aws-spring-boot-sqs-examples:test
```

기본 에뮬레이터는 Floci다. Floci가 지원하지 않는 작업이나 연동을 확인할 때만 LocalStack을 명시한다.

```bash
./gradlew :aws-spring-boot-sqs-examples:test \
  -Dbluetape4k.aws.emulator=localstack
```

## 근거 소스

- [Java SDK 모듈 의존성](../../../aws-java/build.gradle.kts)
- [Kotlin SDK 모듈 의존성](../../../aws-kotlin/build.gradle.kts)
- [Spring Boot 모듈 의존성](../../../aws-spring-boot/build.gradle.kts)
- [Ktor 모듈 의존성](../../../aws-ktor/build.gradle.kts)
