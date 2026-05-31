# aws-ktor-dynamodb-examples

English | [한국어](./README.ko.md)

Ktor 3 examples for the `aws-ktor` DynamoDB plugin. The module installs
`DynamoDbKtorPlugin`, auto-creates an `orders` table, and exposes CRUD routes
backed by a coroutine DynamoDB repository. It uses `bluetape4k-ktor-core` for
shared route parameter validation and `bluetape4k-ktor-testing` for common
Ktor response assertions.

## Architecture

![aws ktor dynamodb examples Architecture diagram](../../docs/images/readme-diagrams/examples-aws-ktor-dynamodb-examples-architecture-01.png)

## DynamoDB Model

The example stores `Order` items with an `id` partition key:

```kotlin
data class Order(
    val id: String,
    val status: String,
    val description: String = "",
)
```

`DynamoItemMapper` and `DynamoItemReader` map the Kotlin model to DynamoDB
attributes. The plugin configures the table with `BillingMode.PayPerRequest`.

## Server Routes

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/dynamodb/orders` | Save an order. Blank `id` or `status` returns `400`. |
| `GET` | `/dynamodb/orders/{id}` | Find an order by partition key. Missing orders return `404`. |
| `DELETE` | `/dynamodb/orders/{id}` | Delete an order by partition key. |
| `GET` | `/dynamodb/orders` | Scan the table and return all orders. |

## Configuration

The module is configured by calling `dynamoDbExampleModule` with an endpoint,
region, and credentials provider. Tests pass the selected AWS emulator values:

```kotlin
application {
    dynamoDbExampleModule(
        endpointUrl = endpointUrl,
        region = localStack.regionName,
        credentialsProvider = credentialsProvider,
    )
}
```

## Test

```bash
./gradlew :aws-ktor-dynamodb-examples:test
```
