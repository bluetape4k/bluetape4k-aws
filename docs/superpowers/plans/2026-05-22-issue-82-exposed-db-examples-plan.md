# 이슈 #82 Exposed AWS Database 예제 계획

## Gate 상태

- 명세: `docs/superpowers/specs/2026-05-22-issue-82-exposed-db-examples-design.md`
- Claude 명세 재검토: `.omx/artifacts/claude-issue-82-examples-spec-rereview-20260522091157.md`
- 명세 gate: `P0=0`, `P1=0`, `Gate: PASS`

## 구현 단계

1. Gradle module을 등록한다.
   - `examples/aws-spring-boot-exposed-examples`에 `:aws-spring-boot-exposed-examples`를 추가한다.
   - `examples/aws-ktor-exposed-examples`에 `:aws-ktor-exposed-examples`를 추가한다.
   - 등록 후 `./gradlew projects`를 실행한다.

2. Spring Boot MVC Exposed 예제를 추가한다.
   - Spring Boot MVC, `bluetape4k-aws-spring-boot`,
     `bluetape4k-aws-exposed`, `bluetape4k-exposed-jdbc`, PostgreSQL driver,
     bluetape4k JUnit, bluetape4k-testcontainers, Spring Boot test support를 포함한 `build.gradle.kts`를 추가한다.
   - `SpringBootExposedExampleApplication`을 추가한다.
   - `OrderRecord : Serializable`, `OrdersTable`, `OrderRepository :
     LongJdbcRepository<OrderRecord>`를 추가한다.
   - `requireNotBlank` 같은 bluetape4k helper로 `OrderRecord`를 검증한다.
   - 유일한 Spring transaction boundary로 `OrderService`를 추가한다.
     `transaction(database) { repository.* }`.
   - repository를 직접 호출하지 않고 `OrderService`를 사용하는 `OrderController`를 추가한다.
   - `transaction(database) { SchemaUtils.create(OrdersTable) }`를 사용하는 `ApplicationRunner`로 `OrderSchemaInitializer`를 추가한다.

3. Spring Boot test를 추가한다.
   - `@SpringBootTest(webEnvironment = RANDOM_PORT)`를 사용한다.
   - `TestRestTemplate`을 사용한다.
   - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`를 사용한다.
   - `PostgreSQLServer.Launcher.postgres`를 사용하고 `PostgreSQLContainer`를 직접 생성하지 않는다.
   - `@JvmStatic @DynamicPropertySource` companion-object function으로 `bluetape4k.aws.exposed.default-database.*` 값을 bind한다.
   - auto-config bean이 존재하고 HTTP create/read/list/not-found 경로가 동작하는지 검증한다.

4. Ktor Exposed 예제를 추가한다.
   - Ktor server/test/Jackson,
     `bluetape4k-aws-ktor`, `bluetape4k-aws-exposed`,
     `bluetape4k-exposed-jdbc`, PostgreSQL driver, bluetape4k JUnit, and
     bluetape4k-testcontainers를 포함한 `build.gradle.kts`를 추가한다.
   - test용 `ExampleDatabaseConfig`와 `ExampleDatabaseConfig.from(postgres)`를 추가한다.
   - `ContentNegotiation { jackson() }`을 설치한다.
   - `defaultDatabase { ... }`와 함께 `AwsExposedPlugin`을 설치한다.
   - `monitor.subscribe(ApplicationStarted)`로 plugin 시작 후 schema를 초기화한다.
   - 여기서 `runBlocking(Dispatchers.IO)`은 엄격히 제어된 시작 bridge로만 취급하고 route handler에서는 `runBlocking`을 사용하지 않는다.
   - `/exposed/orders` 아래에 route를 추가하고 `call.awsExposedTransaction { ... }` 내부에서만 repository method를 호출한다.

5. Ktor test를 추가한다.
   - `testApplication`을 사용한다.
   - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`를 사용한다.
   - `PostgreSQLServer.Launcher.postgres`를 사용한다.
   - HTTP create/read/list/not-found 경로를 검증한다.

6. resource와 문서를 추가한다.
   - 두 module에 `src/test/resources/junit-platform.properties`와 `src/test/resources/logback-test.xml`을 추가한다.
   - 두 module에 서로 대응하는 `README.md`와 `README.ko.md`를 추가한다.
   - 실행 가능한 Gradle command, 실제 AWS credential이 필요 없다는 안내, route, 예상 status code, transaction-boundary 규칙을 포함한다.

7. CI와 Nightly를 갱신한다.
   - `.github/workflows/ci.yml`에 두 example module의 path filter와 test job을 추가한다.
   - 각 example path와 `aws-exposed/**`, `aws-spring-boot/**`, `aws-ktor/**` 변경 시 이 job을 실행한다.
   - `.github/workflows/nightly-tests.yml`에서 두 module을 전체 범위 container 기반 example test에 추가한다.

8. local에서 검증한다.
   - `./gradlew projects`
   - `./gradlew :aws-spring-boot-exposed-examples:test`
   - `./gradlew :aws-ktor-exposed-examples:test`
   - `./gradlew build -x test --parallel`
   - `rg '!!' examples/aws-spring-boot-exposed-examples examples/aws-ktor-exposed-examples`
   - 가능하면 IDE diagnostic을 실행하고, 그렇지 않으면 Gradle diagnostic을 fallback evidence로 기록한다.

9. review gate를 실행한다.
   - 변경된 diff에 대해 Codex review를 실행한다.
   - user-scope AGENTS.md에서 검증된 `claude -p "$prompt"` pattern으로 Claude Code CLI code review를 실행한다.
   - Claude가 `P0=0`, `P1=0`을 보고한 경우에만 계속한다.

10. workflow 산출물을 마무리한다.
    - `docs/lessons/2026-05-22-issue-82-exposed-db-examples.md`를 추가한다.
    - Lore protocol로 commit한다.
    - branch를 push하고 `debop`에게 할당된 PR을 생성하며 관련 label을 포함한다.
      `examples`, `spring-boot`, `ktor`, `exposed`, `database`, `testing`.
