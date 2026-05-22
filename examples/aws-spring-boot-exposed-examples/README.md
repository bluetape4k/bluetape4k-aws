# aws-spring-boot-exposed-examples

English | [한국어](./README.ko.md)

Spring Boot 4 MVC examples for the `aws-spring-boot` and `bluetape4k-aws-exposed`
auto-configuration path. The module uses PostgreSQL through Testcontainers and
does not require AWS credentials for local tests.

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/orders` | Create an order and return `201 Created` |
| `GET` | `/orders/{id}` | Read one order or return `404 Not Found` |
| `GET` | `/orders?customerId={customerId}` | List orders, optionally filtered by customer |

## Transaction Boundary

`OrderController` delegates to `OrderService`; `OrderService` owns
`transaction(database) { ... }`; `OrderRepository` assumes an active Exposed
transaction. This keeps HTTP, transaction, and repository responsibilities
separate.

## Configuration

```yaml
bluetape4k:
  aws:
    exposed:
      default-database:
        url: jdbc:postgresql://localhost:5432/orders
        driver-class-name: org.postgresql.Driver
        username: postgres
        password: postgres
        pool:
          maximum-pool-size: 2
          minimum-idle: 0
```

In production, provide the same properties from AWS Secrets Manager, Parameter
Store, environment variables, or another Spring configuration source.

## Run

```bash
./gradlew :aws-spring-boot-exposed-examples:bootRun
```

## Test

```bash
./gradlew :aws-spring-boot-exposed-examples:test
```

The test starts the shared `PostgreSQLServer.Launcher.postgres` container and
verifies auto-configured `AwsExposedDatabaseRegistry`, `DataSource`, Exposed
`Database`, and HTTP create/read/list/not-found behavior.
