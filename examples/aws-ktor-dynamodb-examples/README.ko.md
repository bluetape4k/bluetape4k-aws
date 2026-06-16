# aws-ktor-dynamodb-examples

[English](./README.md) | 한국어

`aws-ktor` DynamoDB 플러그인을 사용하는 Ktor 3 예제입니다.
`DynamoDbKtorPlugin`을 설치하고 시작 시점에 `orders` 테이블을 만든 뒤,
coroutine DynamoDB repository 기반 CRUD route를 제공합니다. Route code를
plugin contract에 가깝게 유지해, 개발자가 table setup, mapper, reader,
test wiring을 자신의 Ktor service로 옮기기 쉽게 구성했습니다.

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

`DynamoItemMapper`는 Kotlin 모델을 DynamoDB attribute로 쓰고,
`DynamoItemReader`는 반환된 item map에서 `Order`를 복원합니다. 플러그인은
테이블을 `BillingMode.PayPerRequest`로 설정합니다.

## 서버 Route

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/dynamodb/orders` | 주문을 저장합니다. `id` 또는 `status`가 비어 있으면 `400`을 반환합니다. |
| `GET` | `/dynamodb/orders/{id}` | partition key로 주문을 조회합니다. 없으면 `404`를 반환합니다. |
| `DELETE` | `/dynamodb/orders/{id}` | partition key로 주문을 삭제합니다. |
| `GET` | `/dynamodb/orders` | 테이블을 scan하여 전체 주문을 반환합니다. |

## 설정

`dynamoDbExampleModule`에 endpoint, region, credentials provider를 전달해
설정합니다. 테스트는 선택된 AWS emulator 값을 전달하므로, 같은 module을
Floci와 LocalStack 양쪽에서 실행할 수 있습니다.

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
