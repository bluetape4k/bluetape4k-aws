# aws-spring-boot-dynamodb-examples

English | [한국어](./README.ko.md)

Spring Boot 4 WebFlux examples for the `aws-spring-boot` DynamoDB
auto-configuration. The module wires `DynamoDbAutoConfiguration`, exposes an
`OrderRepository` based on `AbstractCoroutinesDynamoDbRepository`, and provides a
small `/orders` REST API.

## Architecture

![aws spring boot dynamodb examples Architecture diagram](../../docs/images/readme-diagrams/examples-aws-spring-boot-dynamodb-examples-architecture-01.png)

## Repository

`Order` is a DynamoDB enhanced client bean with `id` as the partition key:

```kotlin
@DynamoDbBean
class Order {
    @get:DynamoDbPartitionKey
    var id: String = ""
    var status: String = ""
    var description: String = ""
}
```

`OrderRepository` resolves the table name as `orders` and converts both item and
id values to enhanced client `Key` instances.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/orders` | Create an order with a generated UUID. |
| `GET` | `/orders/{id}` | Find an order by id. Missing orders return `404`. |
| `DELETE` | `/orders/{id}` | Delete an order by id. |
| `GET` | `/orders` | Return a `Flow<Order>` scan result. |

Request body:

```json
{
  "status": "NEW",
  "description": "first order"
}
```

## Configuration

The example enables DynamoDB auto-configuration in `application.yml`:

```yaml
bluetape4k:
  aws:
    dynamodb:
      enabled: true
```

LocalStack tests provide `bluetape4k.aws.dynamodb.region` and
`bluetape4k.aws.dynamodb.endpoint-override` through `ApplicationContextRunner`.

## Run

```bash
./gradlew :aws-spring-boot-dynamodb-examples:bootRun
```

## Test

```bash
./gradlew :aws-spring-boot-dynamodb-examples:test
```

## AOT

The module applies the Spring Boot plugin, so the standard Boot AOT tasks are
available when validating native-image metadata:

```bash
./gradlew :aws-spring-boot-dynamodb-examples:processAot
```
