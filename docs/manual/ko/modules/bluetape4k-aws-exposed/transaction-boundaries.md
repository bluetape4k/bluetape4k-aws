---
title: Transaction 경계
description: AWS 설정, pool 소유권, Exposed 업무 transaction 경계를 분리합니다.
manualId: bluetape4k-aws-exposed
chapterId: transaction-boundaries
---

# Transaction 경계

`AwsExposedDatabaseFactory`는 연결 인프라를 만들 뿐 업무 transaction 경계를 고르지 않습니다. 여러 읽기와 쓰기를 묶는 애플리케이션 서비스가 Exposed transaction을 소유해야 합니다.

## Registry 소유권

factory는 기본 handle과 선택적인 이름 있는 handle을 만듭니다. 각 handle에는 Exposed `Database`와 data source가 들어 있습니다. registry는 만든 handle을 모두 닫고, 뒤쪽 database 생성이 실패한 경우 앞에서 만든 자원도 정리합니다.

## AWS 호출을 JDBC transaction 밖에 둔다

secret 해석과 pool 생성은 시작 단계에서 끝내세요. transaction마다 Secrets Manager를 조회하거나 원격 설정을 만들면 안 됩니다. JDBC 연결을 잡은 채 AWS 네트워크를 기다리면 DB 작업 없이 pool 용량만 소비합니다.

```kotlin
suspend fun createOrder(command: CreateOrder): OrderRecord =
    withContext(Dispatchers.IO) {
        transaction(registry.default.database) {
            Orders.insertAndGetId { /* command 매핑 */ }
            // 이 블록을 나가기 전에 결과를 완성한다.
        }
    }
```

JDBC는 blocking입니다. 프레임워크에서 정한 I/O 또는 transaction context를 사용하세요. coroutine wrapper는 실행 위치를 바꿀 뿐 driver protocol을 논블로킹으로 바꾸지 않습니다.

## 재시도와 부수 효과

예외가 transaction 밖으로 나가야 Exposed가 rollback할 수 있습니다. 블록 전체를 재시도하면 내부 부수 효과도 반복되므로 메시지는 commit 뒤에 발행하거나 outbox를 사용하세요. transaction이 끝난 뒤 Exposed DAO entity를 들고 있으면 안 됩니다.

## 이름 있는 database

transaction을 시작하기 전에 handle을 고릅니다. 두 handle은 서로 다른 연결과 commit을 뜻하며 이 라이브러리는 분산 transaction을 제공하지 않습니다.

## 테스트

rollback, handle close, 이름 조회, registry 생성 중 실패 시 정리, pool 반환을 검증하세요. IAM 테스트에는 fake token generator와 제어 가능한 clock을 주입합니다.

## 근거 자료

- [Database factory](../../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseFactory.kt)
- [Database handle](../../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseHandle.kt)
- [Database registry](../../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseRegistry.kt)

