---
name: ktor-dynamodb-examples
description: aws-ktor-dynamodb-examples 모듈 구현에서 얻은 교훈(issue #17)
metadata:
  type: project
---

# Ktor DynamoDB 예제 모듈(Issue #17)

## 요약

`aws-ktor`의 `DynamoDbKtorPlugin`을 통해 DynamoDB CRUD를 보여 주는 Ktor 3 애플리케이션
`examples/aws-ktor-dynamodb-examples`를 추가했다. AWS Kotlin SDK와 LocalStack 통합
테스트를 사용해 HTTP route의 save, findById, scan, delete를 다룬다.

## 근본 원인 / 배경

출시 전까지 `aws-ktor` DynamoDB 통합에는 Ktor 애플리케이션에
`DynamoDbKtorPlugin`을 연결하고 전체 CRUD와 동시 접근을 테스트하는 end-to-end 예제가
없었다.

## 주요 결정

### DynamoDbKtorPlugin 연결

```kotlin
install(DynamoDbKtorPlugin) {
    region = regionName
    endpointUrl = endpointUrl
    credentialsProvider = provider
    autoCreateTables = true
    table("orders") { /* schema */ }
}
```

Repository에는 `application.dynamoDb().repository("orders", mapper, reader, keyMapper)`로
접근한다.

### AWS Kotlin SDK 자격 증명

```kotlin
val credentialsProvider = StaticCredentialsProvider {
    accessKeyId = localStack.accessKey
    secretAccessKey = localStack.secretKey
}
val endpointUrl = Url.parse(localStack.endpoint.toString())
```

### 테스트 구조

- Test class에 `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`를 적용한다.
- Private `testModule(block)` helper가 `testApplication {}`을
  `dynamoDbExampleModule(...)`과 함께 감싼다.
- 각 `@Test`는 `runSuspendIO`로 감싸지 않고 `testModule { ... }`을 test return value로
  직접 호출한다.
- Jackson으로 JSON content negotiation을 설정한다.
  `createClient { install(ContentNegotiation) { jackson() } }`
- 동시성 테스트는 `testModule {}` 안에서
  `SuspendedJobTester().workers(4).rounds(3).add {...}.run()`을 사용한다.

### Assertion 방식

`shouldBeEqualTo`, `shouldBeTrue` 등 bluetape4k assertion을 사용한다. HTTP status는
`status shouldBeEqualTo HttpStatusCode.Created` 등으로 비교한다.

## 피한 함정

- `testApplication {}`을 `runSuspendIO {}` 안에서 감싸지 않는다.
- `testModule` helper는 `testApplication {}`의 결과를 직접 반환해야 한다.

## 검증

검증한 route:

- `POST /dynamodb/orders` → 201 Created
- `GET /dynamodb/orders/{id}` → 200 OK 또는 404 Not Found
- `GET /dynamodb/orders` → 200 OK(list)
- `DELETE /dynamodb/orders/{id}` → 204 No Content

동시성 테스트에서는 worker 4개가 3회씩 서로 다른 order를 저장하고 즉시 id로 조회했다.

## 향후 지침

- DynamoDB Ktor 예제를 확장할 때 이 모듈에 route와 대응하는 test case를 추가한다.
- `Url.parse(localStack.endpoint.toString())` + `StaticCredentialsProvider` pattern을 AWS
  Kotlin SDK용 표준 LocalStack 연결 방식으로 사용한다.
