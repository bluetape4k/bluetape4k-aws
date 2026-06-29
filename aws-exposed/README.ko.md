# bluetape4k-aws-exposed

[English](README.md) | [한국어](README.ko.md)

AWS 기반 데이터베이스 설정과 Exposed JDBC를 연결하는 공통 기반 모듈입니다.

## 기능

- default/named database를 위한 `AwsDatabaseProperties`.
- Spring Boot/Ktor adapter가 Secrets Manager 또는 Parameter Store 값을 주입할 수 있는 `AwsDatabaseSettingsResolver`.
- 비밀번호 진단 출력을 가리는 `AwsSecretString`.
- Amazon RDS IAM database authentication token을 위한
  `AwsDatabaseAuthenticationMode.RDS_IAM`.
- Hikari 기반 Exposed `Database`를 생성하는 `AwsExposedDatabaseFactory`.
- default/named handle을 제공하는 `AwsExposedDatabaseRegistry`.

이 모듈은 AWS 값을 직접 조회하지 않습니다. Spring Boot와 Ktor adapter가 AWS 설정을
해결한 뒤 최종 JDBC 설정을 이 foundation에 전달합니다.
`AwsSecretString`은 진단 출력을 redaction하지만 Java serialization byte에는 raw secret이
포함되므로 신뢰된 process 또는 storage boundary 안에서만 다뤄야 합니다.

## 다이어그램

### 모듈 아키텍처

![AWS Exposed architecture diagram](../docs/images/readme-diagrams/aws-exposed-architecture-01.png)

### 설정 흐름

![AWS Exposed configuration flow diagram](../docs/images/readme-diagrams/aws-exposed-flow-02.png)

### 데이터베이스 핸들 시퀀스

![AWS Exposed database handle sequence diagram](../docs/images/readme-diagrams/aws-exposed-sequence-03.png)

## 의존성

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-exposed:0.2.2")
}
```

RDS IAM authentication mode를 사용할 때는 AWS SDK RDS module도 runtime classpath에
필요합니다.

```kotlin
dependencies {
    runtimeOnly("software.amazon.awssdk:rds")
}
```

## 사용 예

### Static Password

```kotlin
val factory = AwsExposedDatabaseFactory()
val handle = factory.create(
    properties = AwsDatabaseConnectionProperties(
        url = "jdbc:postgresql://localhost:5432/app",
        driverClassName = "org.postgresql.Driver",
        username = "app",
        password = AwsSecretString.of("secret"),
    )
)

transaction(handle.database) {
    // bluetape4k-exposed repository 또는 Exposed DSL을 실행합니다.
}
```

### RDS IAM Authentication

```kotlin
val handle = factory.create(
    properties = AwsDatabaseConnectionProperties(
        url = "jdbc:postgresql://database-1.cluster-example.ap-northeast-2.rds.amazonaws.com:5432/app",
        driverClassName = "org.postgresql.Driver",
        username = "app_user",
        authenticationMode = AwsDatabaseAuthenticationMode.RDS_IAM,
        rdsIam = AwsRdsIamAuthenticationProperties(
            region = "ap-northeast-2",
            hostname = "database-1.cluster-example.ap-northeast-2.rds.amazonaws.com",
            port = 5432,
        ),
        dataSourceProperties = mapOf("sslmode" to "require"),
    )
)
```

RDS IAM mode는 Hikari가 physical JDBC connection을 열기 전에 새 token을 서명합니다.
SDK-backed generator는 token signing을 공용 `bluetape4k-aws-java` RDS IAM helper에
위임한 뒤, redaction된 core token을 JDBC 경계의 `AwsSecretString`으로 변환합니다.
Token은 JDBC password 대체값으로만 사용하며 refresh window까지만 cache됩니다.
`RdsUtilities` token 생성 자체는 실제 AWS network call을 하지 않지만, AWS SDK
credential chain은 credential을 해석할 수 있습니다.

`AwsRdsIamAuthenticationProperties.hostname`에는 실제 RDS endpoint hostname을
사용해야 합니다. AWS는 custom Route 53 DNS alias로 IAM database authentication token을
생성하는 방식을 지원하지 않습니다. PostgreSQL의 `sslmode=require`처럼 engine별 TLS JDBC
property는 호출자가 설정해야 합니다. 호출자의 IAM principal에는 대상 DB resource ARN에
대한 `rds-db:connect` 권한이 필요합니다.

```text
arn:aws:rds-db:{region}:{account-id}:dbuser:{dbi-resource-id}/{db-user-name}
```

## 로컬 검증

```bash
./gradlew :bluetape4k-aws-exposed:test
```

테스트는 H2와 PostgreSQL Testcontainers를 사용하며 실제 AWS credential이 필요하지 않습니다.
