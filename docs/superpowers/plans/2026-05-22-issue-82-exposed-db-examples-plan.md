# Issue #82 Exposed AWS Database Examples Plan

## Gate State

- Spec: `docs/superpowers/specs/2026-05-22-issue-82-exposed-db-examples-design.md`
- Claude spec re-review: `.omx/artifacts/claude-issue-82-examples-spec-rereview-20260522091157.md`
- Spec gate: `P0=0`, `P1=0`, `Gate: PASS`

## Implementation Steps

1. Register Gradle modules.
   - Add `:aws-spring-boot-exposed-examples` at `examples/aws-spring-boot-exposed-examples`.
   - Add `:aws-ktor-exposed-examples` at `examples/aws-ktor-exposed-examples`.
   - Run `./gradlew projects` after registration.

2. Add Spring Boot MVC Exposed example.
   - Add `build.gradle.kts` with Spring Boot MVC, `bluetape4k-aws-spring-boot`,
     `bluetape4k-aws-exposed`, `bluetape4k-exposed-jdbc`, PostgreSQL driver,
     bluetape4k JUnit, bluetape4k-testcontainers, and Spring Boot test support.
   - Add `SpringBootExposedExampleApplication`.
   - Add `OrderRecord : Serializable`, `OrdersTable`, and `OrderRepository :
     LongJdbcRepository<OrderRecord>`.
   - Validate `OrderRecord` with bluetape4k helpers such as
     `requireNotBlank`.
   - Add `OrderService` as the only Spring transaction boundary:
     `transaction(database) { repository.* }`.
   - Add `OrderController` using `OrderService`, not direct repository calls.
   - Add `OrderSchemaInitializer` as `ApplicationRunner` using
     `transaction(database) { SchemaUtils.create(OrdersTable) }`.

3. Add Spring Boot tests.
   - Use `@SpringBootTest(webEnvironment = RANDOM_PORT)`.
   - Use `TestRestTemplate`.
   - Use `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`.
   - Use `PostgreSQLServer.Launcher.postgres`; do not instantiate
     `PostgreSQLContainer` directly.
   - Use a `@JvmStatic @DynamicPropertySource` companion-object function to bind
     `bluetape4k.aws.exposed.default-database.*` values.
   - Verify auto-config beans exist and HTTP create/read/list/not-found paths
     work.

4. Add Ktor Exposed example.
   - Add `build.gradle.kts` with Ktor server/test/Jackson,
     `bluetape4k-aws-ktor`, `bluetape4k-aws-exposed`,
     `bluetape4k-exposed-jdbc`, PostgreSQL driver, bluetape4k JUnit, and
     bluetape4k-testcontainers.
   - Add `ExampleDatabaseConfig` and `ExampleDatabaseConfig.from(postgres)` for
     tests.
   - Install `ContentNegotiation { jackson() }`.
   - Install `AwsExposedPlugin` with `defaultDatabase { ... }`.
   - Initialize schema after plugin startup with `monitor.subscribe(ApplicationStarted)`.
   - Treat `runBlocking(Dispatchers.IO)` there as a tightly controlled startup
     bridge only; do not use `runBlocking` in route handlers.
   - Add routes under `/exposed/orders` and call repository methods only inside
     `call.awsExposedTransaction { ... }`.

5. Add Ktor tests.
   - Use `testApplication`.
   - Use `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`.
   - Use `PostgreSQLServer.Launcher.postgres`.
   - Verify HTTP create/read/list/not-found paths.

6. Add resources and documentation.
   - Add `src/test/resources/junit-platform.properties` and
     `src/test/resources/logback-test.xml` to both modules.
   - Add matching `README.md` and `README.ko.md` for both modules.
   - Include runnable Gradle commands, no-real-AWS-credential note, routes,
     expected status codes, and the transaction-boundary rule.

7. Update CI and Nightly.
   - In `.github/workflows/ci.yml`, add path filters and test jobs for both
     example modules.
   - Trigger these jobs on changes to each example path plus `aws-exposed/**`,
     `aws-spring-boot/**`, and `aws-ktor/**`.
   - In `.github/workflows/nightly-tests.yml`, add both modules to full-scope
     container-backed example testing.

8. Verify locally.
   - `./gradlew projects`
   - `./gradlew :aws-spring-boot-exposed-examples:test`
   - `./gradlew :aws-ktor-exposed-examples:test`
   - `./gradlew build -x test --parallel`
   - `rg '!!' examples/aws-spring-boot-exposed-examples examples/aws-ktor-exposed-examples`
   - Run IDE diagnostics if available; otherwise record Gradle diagnostics as
     fallback evidence.

9. Run review gates.
   - Run Codex review against the changed diff.
   - Run Claude Code CLI code review using the user-scope AGENTS.md known-good
     `claude -p "$prompt"` pattern.
   - Continue only when Claude reports `P0=0` and `P1=0`.

10. Finish workflow artifacts.
    - Add `docs/lessons/2026-05-22-issue-82-exposed-db-examples.md`.
    - Commit with Lore protocol.
    - Push branch, create PR assigned to `debop`, and include relevant labels:
      `examples`, `spring-boot`, `ktor`, `exposed`, `database`, `testing`.
