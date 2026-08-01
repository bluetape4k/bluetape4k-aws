---
name: spring-boot-dynamodb-examples
description: aws-spring-boot-dynamodb-examples 모듈 구현에서 얻은 교훈(issue #14)
metadata:
  type: project
---

# Spring Boot DynamoDB 예제 모듈(Issue #14)

## 요약

`AbstractCoroutinesDynamoDbRepository`를 통해 DynamoDB CRUD를 보여 주는 Spring Boot 4
애플리케이션 `examples/aws-spring-boot-dynamodb-examples`를 추가했다. LocalStack 기반
`ApplicationContextRunner` 통합 테스트로 save, findById, scan, delete를 다룬다.

## 근본 원인 / 배경

출시 전까지 `aws-spring-boot` DynamoDB 자동 구성에는 repository를 만들고
`ApplicationContextRunner`로 테스트하는 end-to-end 예제가 없었다.

## 주요 결정

### 저장소 기반 클래스

`OrderRepository`는 `AbstractCoroutinesDynamoDbRepository<Order, String>`을 확장한다.
`tableName`, `keyFromId`, `keyFromItem`을 override한다. Entity에는 `@DynamoDbBean`,
partition key getter에는 `@get:DynamoDbPartitionKey`를 적용한다.

### ApplicationContextRunner 연결

```kotlin
ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(AwsAutoConfiguration::class.java, DynamoDbAutoConfiguration::class.java))
    .withBean(AwsCredentialsProvider::class.java, { localStack.getCredentialProvider() })
    .withPropertyValues(
        "bluetape4k.aws.dynamodb.region=${localStack.regionName}",
        "bluetape4k.aws.dynamodb.endpoint-override=${localStack.awsEndpoint}",
    )
```

### 테스트 구조

- Test class에 `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`를 적용한다.
- `contextRunner().run { context -> ... }`은 blocking이므로 lambda **안에서**
  `runSuspendIO {}`를 사용한다.
- `DynamoDbAsyncClient.createTable(...).await()`로 table을 만들고 ACTIVE status를 polling한다.
- 동시성 테스트는 `runSuspendIO {}` 안에서
  `SuspendedJobTester().workers(4).rounds(3).add {...}.run()`을 사용한다.

### Assertion 방식

`shouldBeEqualTo`, `shouldBeTrue`, `shouldBeNull` 등 bluetape4k assertion을 사용한다. 일반
`assert()`는 사용하지 않는다.

### Table 관리

각 테스트는 idempotent한 `createOrdersTable(asyncClient, tableName)`을 호출할 수 있다.
이 함수는 `ResourceNotFoundException`을 검사하고 table이 이미 있으면 생성을 건너뛴다.
`TableStatus.ACTIVE`를 최대 30초 기다린다.

## 피한 함정

- 바깥 test function 수준에서 `runSuspendIO {}`를 호출하지 않는다.
  `contextRunner().run {}`이 blocking이므로 가장 바깥 scope여야 한다.
- `contextRunner().run { context -> ... }` lambda **안에서만** `runSuspendIO {}`를 사용한다.

## 검증

- CRUD 테스트: save → findById → scan → deleteById → findById(null)
- 동시성 테스트: worker 4개가 3회씩 서로 다른 order를 저장하고 findById로 확인

## 향후 지침

- Spring Boot DynamoDB 예제를 확장할 때 같은 `ApplicationContextRunner` pattern을 따른다.
- `LocalStackServer.Launcher.getLocalStack("dynamodb")` + `getCredentialProvider()` pattern을
  Spring Boot 테스트용 표준 LocalStack 연결 방식으로 사용한다.
