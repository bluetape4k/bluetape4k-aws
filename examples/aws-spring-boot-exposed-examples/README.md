# aws-spring-boot-exposed-examples

English | [한국어](./README.ko.md)

Spring Boot 4 MVC examples for the `aws-spring-boot` and `bluetape4k-aws-exposed`
auto-configuration path. The module uses PostgreSQL through Testcontainers and
does not require AWS credentials for local tests. It keeps the boundaries
deliberately small: the controller owns HTTP status behavior, the service owns
`transaction(database) { ... }`, and the repository owns Exposed queries.

## Architecture

![aws spring boot exposed examples Architecture diagram](../../docs/images/readme-diagrams/examples-aws-spring-boot-exposed-examples-architecture-01.png)

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/orders` | Create an order and return `201 Created` |
| `GET` | `/orders/{id}` | Read one order or return `404 Not Found` |
| `GET` | `/orders?customerId={customerId}&limit={limit}&cursor={cursor}` | Return a cursor page of orders, optionally filtered by customer |
| `GET` | `/orders/search` | Query by Example with a bounded closed projection |

## Cursor Pagination

The list route returns `ExposedCursorPage<OrderRecord, Long>` instead of a
plain array:

```json
{
  "content": [{ "id": 41, "customerId": "customer-1", "status": "CREATED", "notes": null }],
  "nextCursor": 41,
  "hasNext": true
}
```

- `customerId` is optional and filters the page.
- `limit` is an optional integer from `1` to `100`; the default is `20`.
- `cursor` is an optional non-negative last-seen `OrdersTable.id`. Send the
  returned `nextCursor` unchanged to fetch the next page.
- The final page has `hasNext: false` and `nextCursor: null`. The query does
  not calculate a total count.

This example exposes the raw primary-key cursor to keep the wire contract
visible. Production callers should encode, sign, scope, and expire cursor
tokens before exposing them to clients.

## QBE Projection Search

`/orders/search` keeps the cursor list endpoint while demonstrating the Spring
Data Exposed 2.0.0 JDBC adapter:

```text
GET /orders/search?customerId=customer-1&status=PAID&limit=20&sort=-customerId
-> [{"customerId":"customer-1","status":"PAID"}]
```

`customerId` and `status` are optional exact QBE conditions; blank values are
ignored. `limit` defaults to `20` and is capped at `100`. `sort` accepts
`customerId` or `status`, with a leading `-` for descending order. Unknown
sorts, unsupported statuses, and limits outside `1..100` return `400 Bad
Request`. A combination with no matching order returns `200 []`.

The endpoint uses `ExposedJdbcRepository.findBy(Example)` with the
`OrderSummaryProjection` closed projection. The SQL pushdown test verifies that
only `customer_id` and `status` are selected and that `ORDER BY` and `LIMIT`
are executed in SQL; the `notes` column and count query are not materialized.
The adapter requires a persisted probe in the current Exposed transaction, so
the service obtains a matching probe before executing the final projection and
returns an empty list when no probe exists.

## Transaction Boundary

`OrderController` delegates to `OrderService`; `OrderService` owns
`transaction(database) { ... }`; `OrderRepository` assumes an active Exposed
transaction. `OrderSchemaInitializer` creates `OrdersTable` after the
auto-configured `Database` bean is available. The QBE search is additionally
wrapped by Spring's `springTransactionManager` so the persisted probe and
FluentQuery share one Exposed transaction.

The example adds `bluetape4k-exposed-spring-boot-jdbc` because the QBE,
closed-projection, sort, and limit contracts are provided by the Spring Data
Exposed 2.0.0 adapter rather than by the AWS wrapper. The shared BOM currently
resolves the adapter from the `2.0.0-SNAPSHOT` line. After `2.0.0` is promoted,
update the immutable dependency-catalog ref and rerun this module's integration
and SQL pushdown tests before removing the snapshot qualification.

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
uses `bluetape4k-testcontainers-spring` to expose lazy
`testcontainers.postgresql.*` properties. `src/test/resources/application.yml`
maps those standard keys to the AWS database prefix, while pool sizing stays
an example-specific dynamic setting. It then verifies auto-configured
`AwsExposedDatabaseRegistry`, `DataSource`, Exposed `Database`, and HTTP
create/read/list/not-found behavior through a random-port `SpringBootTest`,
including cursor page traversal, invalid cursor or limit rejection, QBE search,
and projection SQL pushdown.
