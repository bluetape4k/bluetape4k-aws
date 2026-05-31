# aws-ktor-exposed-examples

English | [한국어](./README.ko.md)

Ktor 3 examples for `aws-ktor` and `bluetape4k-aws-exposed`. The module installs
`AwsExposedPlugin`, creates an Exposed schema on application start, and exposes
order routes backed by PostgreSQL. Local tests use Testcontainers and do not
require AWS credentials. It uses `bluetape4k-ktor-core` for shared route
parameter validation and `bluetape4k-ktor-testing` for common Ktor response
assertions.

## Architecture

![aws ktor exposed examples Architecture diagram](../../docs/images/readme-diagrams/examples-aws-ktor-exposed-examples-architecture-01.png)

## Routes

| Method | Path | Description |
|---|---|---|
| `POST` | `/exposed/orders` | Create an order and return `201 Created` |
| `GET` | `/exposed/orders/{id}` | Read one order or return `404 Not Found` |
| `GET` | `/exposed/orders?customerId={customerId}` | List orders, optionally filtered by customer |

## Transaction Boundary

Routes call repositories only through `call.awsExposedTransaction { ... }`.
The repository contains Exposed query code only and does not own the Ktor plugin,
database handle, or connection lifecycle. `AwsExposedPlugin` dispatches route
transactions through its `transactionContext`, which defaults to `Dispatchers.IO`
for blocking JDBC work.

## Configuration

```kotlin
install(AwsExposedPlugin) {
    defaultDatabase {
        url = "jdbc:postgresql://localhost:5432/orders"
        driverClassName = "org.postgresql.Driver"
        username = "postgres"
        password = "postgres"
        pool {
            maximumPoolSize = 2
            minimumIdle = 0
        }
    }
}
```

For production, resolve values from AWS Secrets Manager, Parameter Store, or the
environment before installing the plugin.

## Test

```bash
./gradlew :aws-ktor-exposed-examples:test
```

The test starts the shared `PostgreSQLServer.Launcher.postgres` container and
verifies route-level create/read/list/not-found behavior.
