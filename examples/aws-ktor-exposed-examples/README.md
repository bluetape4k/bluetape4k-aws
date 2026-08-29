# aws-ktor-exposed-examples

English | [한국어](./README.ko.md)

Ktor 3 examples for `aws-ktor` and `bluetape4k-aws-exposed`. The module installs
`AwsExposedPlugin`, creates the Exposed schema on application start, and exposes
order routes backed by PostgreSQL. The example is intentionally small: routes
own HTTP semantics, repositories own Exposed queries, and the plugin owns the
database lifecycle and transaction dispatch. Local tests use Testcontainers and
do not require AWS credentials.

## Architecture

![aws ktor exposed examples Architecture diagram](../../docs/images/readme-diagrams/examples-aws-ktor-exposed-examples-architecture-01.png)

## Routes

| Method | Path | Description |
|---|---|---|
| `POST` | `/exposed/orders` | Create an order and return `201 Created` |
| `GET` | `/exposed/orders/{id}` | Read one order or return `404 Not Found` |
| `GET` | `/exposed/orders?customerId={customerId}&limit={limit}&cursor={cursor}` | Return a cursor page of orders, optionally filtered by customer |
| `GET` | `/healthz/exposed` | Optional probe-free liveness |
| `GET` | `/readyz/exposed` | Optional JDBC `SELECT 1` readiness |

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

## Selective JDBC Health/Readiness

The example opts into the 2.0.0 backend-selective Ktor surface with only the
core and JDBC artifacts. The compatibility `bluetape4k-exposed-ktor` aggregator,
R2DBC, and cache artifacts are intentionally absent.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k.exposed:bluetape4k-exposed-bom:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-core")
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-jdbc")
}
```

The shared catalog has not published dedicated `core`/`jdbc` aliases yet, so
the versionless coordinates are managed by the Exposed BOM until that
prerequisite is available. Health routes are opt-in through the example
module's `healthConfig` parameter:

```kotlin
exposedExampleModule(
    database = database,
    healthConfig = AwsExposedKtorHealthConfig(
        blockingDispatcher = Dispatchers.IO,
    ),
)
```

Liveness does not touch the database. Readiness resolves the AWS registry's
default (or named) `Database` at request time and runs `SELECT 1` on the caller
dispatcher under one shared timeout budget. Failures expose only fixed status
tokens; optional Micrometer metrics use the core JDBC/backend/component/outcome
tags. The AWS plugin still owns registry and pool shutdown, while the existing
transaction API remains unchanged.

## Transaction Boundary

Routes call repositories only through `call.awsExposedTransaction { ... }`.
`OrderRepository` contains Exposed query code only and does not own the Ktor
plugin, database handle, or connection lifecycle. `AwsExposedPlugin` dispatches
route transactions through its `transactionContext`, which defaults to
`Dispatchers.IO` for blocking JDBC work. The startup hook uses the same boundary
to create `OrdersTable`.

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

The test starts the shared `PostgreSQLServer.Launcher.postgres` container,
passes its JDBC settings into `ExampleDatabaseConfig`, and verifies route-level
create/read/list/not-found behavior, cursor page traversal, invalid cursor or
limit rejection, and the opt-in liveness/readiness probes with JDBC metrics.
