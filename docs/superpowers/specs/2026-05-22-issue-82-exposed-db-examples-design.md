# Issue #82 Exposed AWS Database Examples Design

## Context

Issue #82 closes the `0.2.0` adoption gap for the Exposed AWS database stack.
The foundation modules already exist:

- `bluetape4k-aws-exposed` creates Hikari-backed Exposed `Database` handles from
  `AwsDatabaseProperties`.
- `bluetape4k-aws-spring-boot` binds `bluetape4k.aws.exposed` properties and
  exposes the default `AwsExposedDatabaseRegistry`, `AwsExposedDatabaseHandle`,
  `DataSource`, and Exposed `Database` beans.
- `bluetape4k-aws-ktor` installs `AwsExposedPlugin` and exposes
  route/application helpers such as `awsExposedTransaction`.

The missing piece is runnable, credential-free example coverage that shows how
application code uses these adapters together with `bluetape4k-exposed`
repository conventions.

## Goals

- Add a Spring Boot 4 example module that uses the Spring Exposed
  auto-configuration, a bluetape4k-exposed JDBC repository, and PostgreSQL
  Testcontainers verification.
- Add a Ktor 3 example module that installs `AwsExposedPlugin`, runs suspend
  Exposed transactions from routes, and verifies the same create/read repository
  path with PostgreSQL Testcontainers.
- Keep both examples runnable without real AWS credentials by default.
- Register new modules in Gradle settings, CI, and Nightly so example tests are
  not skipped after merge.
- Document runnable commands in `README.md` and `README.ko.md` for each module.

## Non-Goals

- Do not add a new production API to `aws-exposed`, `aws-spring-boot`, or
  `aws-ktor`.
- Do not use LocalStack for these examples. The database is verified with
  PostgreSQL Testcontainers, and AWS remote configuration is represented by
  local/static settings or a test resolver.
- Do not require RDS IAM authentication in the first example path. Issue #77
  already validates token generation; these examples focus on adoption.
- Do not add schema migration tooling such as Flyway or Liquibase.

## Module Shape

Create two non-published example modules:

- `examples/aws-spring-boot-exposed-examples`
  - Gradle project path: `:aws-spring-boot-exposed-examples`
  - Package: `io.bluetape4k.aws.examples.spring.exposed`
  - Dependencies:
    - `project(":bluetape4k-aws-spring-boot")`
    - `project(":bluetape4k-aws-exposed")`
    - `libs.bluetape4k.exposed.jdbc`
    - Exposed JDBC/BOM dependencies as needed
    - PostgreSQL JDBC driver
    - Spring Boot MVC web and test support
    - `libs.testcontainers.postgresql`

- `examples/aws-ktor-exposed-examples`
  - Gradle project path: `:aws-ktor-exposed-examples`
  - Package: `io.bluetape4k.aws.examples.ktor.exposed`
  - Dependencies:
    - `project(":bluetape4k-aws-ktor")`
    - `project(":bluetape4k-aws-exposed")`
    - `libs.bluetape4k.exposed.jdbc`
    - Ktor server/test/Jackson dependencies
    - PostgreSQL JDBC driver
    - `libs.testcontainers.postgresql`

Both modules include:

- `src/test/resources/junit-platform.properties`
- `src/test/resources/logback-test.xml`
- English and Korean README files with matching structure.

## Domain Model

Use a small order model in both examples:

- `OrderRecord(id: Long = 0, customerId: String, status: String, description: String = "") : Serializable`
- `OrdersTable : LongIdTable("example_orders")`
- `OrderRepository : LongJdbcRepository<OrderRecord>`

`OrderRecord` is a Kotlin `data class`, so it must implement
`java.io.Serializable` and define `serialVersionUID`. Its init block validates
caller input with bluetape4k helpers, for example
`customerId.requireNotBlank("customerId")` and
`status.requireNotBlank("status")`.

The repository must use `bluetape4k-exposed` `LongJdbcRepository` instead of a
standalone raw Exposed-only repository. It implements:

- `override val table`
- `override fun extractId(entity)`
- `override fun ResultRow.toEntity()`
- `override fun BatchInsertStatement.bindSave(entity)`
- `fun save(entity): OrderRecord`
- `fun findByCustomerId(customerId): List<OrderRecord>`

Inherited `JdbcRepository` read helpers such as `findById`, `findByIdOrNull`,
and `findAll` remain part of the example surface and are used through an
explicit transaction boundary.

The repository may still use Exposed DSL inside repository methods, because that
is how `JdbcRepository` implementations are expected to map writes and reads.
Repository methods are never called outside one of the framework transaction
wrappers:

- Spring: `OrderService` wraps calls in `transaction(database) { ... }`.
- Ktor: routes wrap calls in `call.awsExposedTransaction { ... }`.

`findByCustomerId` is intentionally unbounded for the small example contract;
production code should paginate with bluetape4k-exposed paging APIs such as
`ExposedPage`.

## Spring Boot Example

`SpringBootExposedExampleApplication` loads a standard Spring Boot MVC app. Use
`spring-boot-starter-web`, not WebFlux, because this example is JDBC-backed and
the bluetape4k-exposed `LongJdbcRepository` methods are blocking Exposed JDBC
operations.

`OrderService` owns the transaction boundary. It receives the Spring-provided
Exposed `Database` bean from `AwsExposedDefaultDatabaseAutoConfiguration` and
wraps every repository call:

```kotlin
transaction(database) {
    repository.save(record)
}
```

Controller methods call `OrderService` and do not call repository methods
directly. This avoids running `JdbcRepository` methods outside an active Exposed
transaction and keeps blocking JDBC work off a Reactor event loop by not using
WebFlux.

`OrderController` exposes:

| Method | Path | Behavior |
|---|---|---|
| `POST` | `/orders` | Creates one order and returns `201`. |
| `GET` | `/orders/{id}` | Returns one order or `404`. |
| `GET` | `/orders` | Lists orders, optionally filtered by `customerId`. |

`OrderRepository` does not own a `Database` and does not rely on
`TransactionManager.defaultDatabase`. It assumes callers invoke it inside the
transaction opened by `OrderService`.

`OrderSchemaInitializer` creates `OrdersTable` on application startup using the
Spring-provided Exposed `Database`. Implement it as an `ApplicationRunner` that
runs after the `Database` bean is wired and before example users send normal
requests:

```kotlin
transaction(database) {
    SchemaUtils.create(OrdersTable)
}
```

This keeps the example self-contained without adding migrations.

Tests use `@SpringBootTest(webEnvironment = RANDOM_PORT)` with
`TestRestTemplate` and the shared bluetape4k Testcontainers launcher. The test
class uses `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`. Do not instantiate
`PostgreSQLContainer` directly; use `PostgreSQLServer.Launcher.postgres` from
`bluetape4k-testcontainers`. The Kotlin `@DynamicPropertySource` method is a
`@JvmStatic` companion-object function that reads values from that launcher
singleton before Spring property binding:

- `bluetape4k.aws.exposed.default-database.url`
- `bluetape4k.aws.exposed.default-database.driver-class-name`
- `bluetape4k.aws.exposed.default-database.username`
- `bluetape4k.aws.exposed.default-database.password`
- small pool settings such as `maximum-pool-size=2` and `minimum-idle=0`

The Spring context includes:

- `AwsAutoConfiguration`
- `AwsExposedAutoConfiguration`
- `AwsExposedDefaultDatabaseAutoConfiguration`

The test proves:

- the Spring auto-configuration creates `AwsExposedDatabaseRegistry`,
  `DataSource`, and Exposed `Database` from Testcontainers properties.
- the controller/repository creates an order and reads it back.

Optional auto-configuration slice coverage may use `ApplicationContextRunner`,
but HTTP create/read verification must use the Spring Boot web test context.

## Ktor Example

`exposedExampleModule(database: ExampleDatabaseConfig)` installs:

- `ContentNegotiation { jackson() }`
- `AwsExposedPlugin`
- routes backed by `call.awsExposedTransaction { ... }`

Routes:

| Method | Path | Behavior |
|---|---|---|
| `POST` | `/exposed/orders` | Creates one order and returns `201`. |
| `GET` | `/exposed/orders/{id}` | Returns one order or `404`. |
| `GET` | `/exposed/orders` | Lists orders, optionally filtered by `customerId`. |

Application startup initializes `OrdersTable` through the plugin runtime after
the plugin has created the registry. Inside the `Application` extension, call
`monitor.subscribe(ApplicationStarted) { ... }` after installing
`AwsExposedPlugin`, then bridge the synchronous lifecycle event to the plugin
transaction helper:

```kotlin
monitor.subscribe(ApplicationStarted) {
    runBlocking(Dispatchers.IO) {
        awsExposedTransaction {
            SchemaUtils.create(OrdersTable)
        }
    }
}
```

This is a tightly controlled startup bridge, mirroring `AwsExposedPlugin`'s own
synchronous Ktor lifecycle bridge. It is allowed only for one-time schema
initialization during example startup; route handlers must not use
`runBlocking`.

`awsExposedTransaction` runs the block on the plugin transaction context, which
defaults to `Dispatchers.IO`, so route code must call repository methods inside
`call.awsExposedTransaction { ... }` instead of calling the blocking JDBC
repository directly from the event loop.

Tests use `testApplication` with PostgreSQL Testcontainers properties, a
`@TestInstance(TestInstance.Lifecycle.PER_CLASS)` test class, and prove
create/read through HTTP.

## Credential and Emulator Strategy

- Default example configuration uses direct JDBC settings and does not require
  AWS credentials.
- Remote AWS config source descriptors may be documented as optional snippets,
  but tests should not call AWS, LocalStack, or Floci.
- Tests use direct dynamic JDBC properties or direct Ktor DSL values from the
  shared `PostgreSQLServer.Launcher.postgres` instance. Do not add a competing
  `AwsDatabaseSettingsResolver` test path unless a separate resolver example is
  explicitly needed later.
- Use `PostgreSQLServer.Launcher.postgres` from `bluetape4k-testcontainers` for
  both Spring and Ktor tests. The launcher owns the image/start lifecycle and
  avoids duplicate per-test-class Postgres containers in Nightly.
- The Ktor test helper derives `ExampleDatabaseConfig` from the same launcher,
  for example `ExampleDatabaseConfig.from(postgres)`, and maps launcher values
  to the Ktor `defaultDatabase { url/driverClassName/username/password/pool }`
  DSL.

## CI and Nightly

New example modules must be registered in:

- `settings.gradle.kts`
- `.github/workflows/ci.yml`
- `.github/workflows/nightly-tests.yml`

CI should deliberately extend the current repository policy for these examples.
Add path filters for the example modules and their upstream adapter modules:

- `examples/aws-spring-boot-exposed-examples/**`
- `examples/aws-ktor-exposed-examples/**`
- `aws-exposed/**`
- `aws-spring-boot/**`
- `aws-ktor/**`

Then add targeted test jobs that run when those paths change or on
`workflow_dispatch`. This is required because Issue #82 acceptance says examples
compile in CI as well as Nightly, and upstream adapter regressions should not
wait for Nightly only.

Nightly should run both modules in the full scope because the tests use
container-backed PostgreSQL.

## Acceptance Criteria

- `./gradlew projects` shows both new modules.
- `./gradlew :aws-spring-boot-exposed-examples:test` passes.
- `./gradlew :aws-ktor-exposed-examples:test` passes.
- `./gradlew build -x test --parallel` compiles both modules.
- README files contain runnable commands and no real AWS credential requirement.
- Local workflow artifacts under `.omx/artifacts` and the final lesson record
  show Claude Code CLI spec/plan/code review gates reached `P0=0` and `P1=0`
  before implementation moved to the next phase.
