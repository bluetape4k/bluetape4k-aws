# aws-spring-boot-dynamodb-examples

English | [한국어](./README.ko.md)

Spring Boot 4 WebFlux examples for the `aws-spring-boot` DynamoDB
auto-configuration. The module wires `DynamoDbAutoConfiguration`, exposes an
`OrderRepository` based on `AbstractCoroutinesDynamoDbRepository`, and provides a
small `/orders` REST API. It is a compact copy point for coroutine CRUD on top of
the DynamoDB enhanced async client.

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
id values to enhanced client `Key` instances. Repository methods remain suspend
or `Flow`-based, while the enhanced client and table name resolver come from
Spring Boot auto-configuration.

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

LocalStack or Floci tests provide `bluetape4k.aws.dynamodb.region` and
`bluetape4k.aws.dynamodb.endpoint-override` through `ApplicationContextRunner`.
The runner also supplies emulator credentials as an `AwsCredentialsProvider`
bean.

## Run

```bash
./gradlew :aws-spring-boot-dynamodb-examples:bootRun
```

## Test

```bash
./gradlew :aws-spring-boot-dynamodb-examples:test
```

The suite covers repository CRUD, scan, concurrent save/find operations through
`SuspendedJobTester`, and the controller HTTP layer with `WebTestClient`.

## AOT

The module applies the Spring Boot plugin, so the standard Boot AOT tasks are
available when validating native-image metadata:

```bash
./gradlew :aws-spring-boot-dynamodb-examples:processAot
```
