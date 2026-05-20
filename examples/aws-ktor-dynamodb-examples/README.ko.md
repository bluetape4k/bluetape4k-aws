# aws-ktor-dynamodb-examples

[English](./README.md) | 한국어

`aws-ktor` DynamoDB 플러그인을 사용하는 Ktor 3 예제입니다.
`DynamoDbKtorPlugin`을 설치하고 `orders` 테이블을 자동 생성한 뒤,
coroutine DynamoDB repository 기반 CRUD route를 제공합니다.

## 아키텍처

![aws ktor dynamodb examples Architecture diagram](../../docs/images/readme-diagrams/examples-aws-ktor-dynamodb-examples-architecture-01.png)

## DynamoDB 모델

예제는 `id` partition key를 가진 `Order` item을 저장합니다.

```kotlin
data class Order(
    val id: String,
    val status: String,
    val description: String = "",
)
```

`DynamoItemMapper`와 `DynamoItemReader`가 Kotlin 모델과 DynamoDB attribute를
매핑합니다. 플러그인은 테이블을 `BillingMode.PayPerRequest`로 설정합니다.

## 서버 Route

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/dynamodb/orders` | 주문을 저장합니다. `id` 또는 `status`가 비어 있으면 `400`을 반환합니다. |
| `GET` | `/dynamodb/orders/{id}` | partition key로 주문을 조회합니다. 없으면 `404`를 반환합니다. |
| `DELETE` | `/dynamodb/orders/{id}` | partition key로 주문을 삭제합니다. |
| `GET` | `/dynamodb/orders` | 테이블을 scan하여 전체 주문을 반환합니다. |

## 설정

`dynamoDbExampleModule`에 endpoint, region, credentials provider를 전달해
설정합니다. 테스트는 LocalStack 값을 전달합니다.

```kotlin
application {
    dynamoDbExampleModule(
        endpointUrl = endpointUrl,
        region = localStack.regionName,
        credentialsProvider = credentialsProvider,
    )
}
```

## 테스트

```bash
./gradlew :aws-ktor-dynamodb-examples:test
```
