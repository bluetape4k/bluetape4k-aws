---
title: 데이터베이스 설정 해석
description: pool 생성과 트래픽 처리 전에 AWS 기반 JDBC 설정을 해석합니다.
manualId: bluetape4k-aws-exposed
chapterId: database-settings
---

# 데이터베이스 설정 해석

AWS 기반 데이터베이스 설정은 transaction 안에서 문자열 하나를 몰래 조회하는 기능이 아니라 단계가 분명한 파이프라인입니다.

## 해석 파이프라인

`AwsDatabaseConnectionProperties`에는 로컬 기본값과 선택적인 Secrets Manager·Parameter Store descriptor가 함께 들어갑니다. `AwsDatabaseSettingsResolver`가 descriptor를 URL, driver, username, password 또는 IAM 설정으로 바꾼 뒤에 pool을 만듭니다.

```kotlin
val properties = AwsDatabaseConnectionProperties(
    secretSource = AwsDatabaseConfigSource(
        type = AwsDatabaseConfigSourceType.SECRETS_MANAGER,
        sourceId = "prod/orders-db",
    ),
    pool = AwsDatabasePoolProperties(maximumPoolSize = 12),
)
val resolved = resolver.resolve("orders", properties)
```

## 우선순위를 분명히 정한다

원격 값이 비어 있는 로컬 필드만 채우는지, 설정된 값도 덮어쓰는지 문서로 정하세요. resolver 경계를 두는 이유는 Spring, Ktor, 테스트, 사용자 정의 배포가 같은 결정 규칙을 사용하게 하기 위해서입니다.

## 비밀 값 다루기

password는 `AwsSecretString`으로 감싸 진단 출력에서 가립니다. JDBC driver에 넘기는 순간에만 값을 꺼내세요. 해석된 properties 전체를 로그나 health endpoint에 출력하면 안 됩니다.

## 트래픽을 받기 전 검증

애플리케이션 시작 시 database name, driver class, URL, 인증 방식, pool 제한, source ID를 검증하세요. optional source는 없어도 되지만 필수 source가 없으면 불완전한 pool을 만들지 말고 시작을 실패시켜야 합니다.

## Resolver 테스트

AWS 없이 fake resolver로 우선순위와 redaction을 검증하세요. 원격 payload 형식은 emulator test로, 최종 연결은 일회용 JDBC database로 따로 확인합니다.

## 근거 자료

- [Database properties](../../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsDatabaseProperties.kt)
- [Config source model](../../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsDatabaseConfigSource.kt)
- [Settings resolver 계약](../../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsDatabaseSettingsResolver.kt)

