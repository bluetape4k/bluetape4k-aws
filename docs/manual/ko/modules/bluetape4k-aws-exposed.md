---
manualId: "bluetape4k-aws-exposed"
id: "bluetape4k-aws-exposed"
title: "AWS Exposed 데이터베이스 통합"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-aws-exposed"
sourceDir: "aws-exposed"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-exposed
---

# AWS Exposed 데이터베이스 통합

> 0.5.0 릴리스 소스를 기준으로 작성한 라이브러리 매뉴얼입니다.

## 제공하는 기능 {#problem}

AWS에서 데이터베이스 설정을 읽고 Hikari DataSource와 Exposed Database를 만들며 기본·이름 있는 데이터베이스 handle을 관리합니다.

## 사용하기 좋은 경우 {#when-to-use}

JDBC 연결 설정이나 자격 증명을 Secrets Manager, Parameter Store, RDS IAM 인증에서 가져올 때 사용합니다.

## 의존성 좌표 {#coordinates}

애플리케이션에서는 중앙 BOM 버전 하나만 선택합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-exposed")
}
```

AWS 서비스 SDK는 `compileOnly` 정책 때문에 실제 사용하는 모듈을 런타임 의존성으로 별도 추가해야 합니다.

## 핵심 개념 {#concepts}

config source가 설정 위치를 가리키고 resolver가 실제 연결 속성을 만듭니다. data-source factory가 Hikari를 만들고 database factory가 Exposed를 연결해 닫을 수 있는 handle을 반환합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val factory = AwsExposedDatabaseFactory(resolver, dataSourceFactory)
val handle = factory.create("orders", properties)
try {
    transaction(handle.database) { Orders.selectAll().count() }
} finally {
    handle.close()
}
```

종료할 때 handle이 소유한 Hikari pool을 닫습니다.

## 작업별 API {#api-by-task}

`AwsDatabaseProperties`, `AwsDatabaseSettingsResolver`, `AwsJdbcDataSourceFactory`, `AwsExposedDatabaseFactory`, `AwsExposedDatabaseRegistry`, RDS IAM 인증을 제공합니다.

## 권장 패턴 {#patterns}

client와 백그라운드 작업의 소유자를 한 곳으로 정하고, region·credentials·endpoint를 호출마다 만들지 말고 애플리케이션 경계에서 구성하세요.

## 연동 {#integrations}

Spring Boot 자동 설정과 Ktor Exposed plugin이 이 기반 모듈을 사용합니다. Secrets Manager, SSM, RDS, JDBC driver, Hikari, Exposed 런타임 모듈을 필요한 만큼 추가하세요.

## 설정 {#configuration}

비밀 값은 정적 설정에 넣지 마세요. source, region, secret·parameter 식별자, JDBC driver·URL 형식, pool 크기와 IAM token 갱신 방식을 설정합니다.

## 실패 유형과 해결 방법 {#failures}

잘못된 secret payload, 누락된 driver JAR, 만료된 IAM token, 틀린 region, pool 소유권 실수는 설정 해석이나 첫 연결에서 드러납니다.

## 운영 {#operations}

트래픽을 받기 전에 설정을 해석하고 pool 크기를 제한하세요. 자격 증명 교체는 transaction 밖에서 처리하고 애플리케이션 종료 시 모든 handle을 닫아야 합니다.

## 테스트 {#testing}

단위 테스트에는 fake resolver를 사용하고 설정 해석과 연결을 함께 검증할 때는 emulator와 임시 데이터베이스를 사용하세요. close가 Hikari 자원을 해제하는지도 확인합니다.

## 학습 경로와 예제 {#workshops}

`database-settings` → `rds-iam-and-hikari` → `transaction-boundaries` 순서로 읽고 Spring Boot·Ktor Exposed 예제를 실행하세요.

## 제약 사항 {#limitations}

이 모듈은 연결 인프라를 만들고 관리합니다. Exposed table을 설계하거나 blocking JDBC 작업을 자동으로 coroutine 친화적으로 바꾸지는 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS Exposed 아키텍처

[![Bluetape4k AWS Exposed 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-exposed-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-exposed-architecture-01.svg)

_배포본 README: [`aws-exposed/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-exposed/README.ko.md)_

### Bluetape4k AWS Exposed configuration 처리 흐름

[![Bluetape4k AWS Exposed configuration 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-exposed-flow-02.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-exposed-flow-02.svg)

_배포본 README: [`aws-exposed/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-exposed/README.ko.md)_

### Bluetape4k AWS Exposed database handle 시퀀스 다이어그램

[![Bluetape4k AWS Exposed database handle 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-exposed-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-exposed-sequence-03.svg)

_배포본 README: [`aws-exposed/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-exposed/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [릴리스 소스: `aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseFactory.kt`](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseFactory.kt)
- [릴리스 소스: `aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsRdsIamAuthentication.kt`](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsRdsIamAuthentication.kt)
- [릴리스 테스트: `AwsExposedDatabaseFactoryTest`](../../../../aws-exposed/src/test/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseFactoryTest.kt)
