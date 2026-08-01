# 이슈 #82 Exposed AWS 데이터베이스 예제 설계

## 배경

이슈 #82는 Exposed AWS 데이터베이스 스택의 `0.2.0` 도입 공백을 해소한다.
기반 모듈은 이미 존재한다.

- `bluetape4k-aws-exposed`는 `AwsDatabaseProperties`에서 Hikari 기반 Exposed
  `Database` 핸들을 생성한다.
- `bluetape4k-aws-spring-boot`는 `bluetape4k.aws.exposed` 속성을 바인딩하고 기본
  Exposed 구성 요소인 `AwsExposedDatabaseRegistry`, `AwsExposedDatabaseHandle`, `DataSource`,
  `Database` 빈을 노출한다.
- `bluetape4k-aws-ktor`는 `AwsExposedPlugin`을 설치하고 `awsExposedTransaction`
  같은 라우트/애플리케이션 도우미를 노출한다.

부족한 부분은 애플리케이션 코드가 이 어댑터를 `bluetape4k-exposed` 저장소 규칙과
함께 사용하는 방법을 보여 주는, 자격 증명 없이 실행 가능한 예제다.

## 목표

- Spring Exposed 자동 구성, bluetape4k-exposed JDBC 저장소, PostgreSQL
  Testcontainers 검증을 사용하는 Spring Boot 4 예제 모듈을 추가한다.
- `AwsExposedPlugin`을 설치하고 라우트에서 suspend Exposed 트랜잭션을 실행하며,
  같은 생성/조회 저장소 경로를 PostgreSQL Testcontainers로 검증하는 Ktor 3 예제
  모듈을 추가한다.
- 두 예제를 기본적으로 실제 AWS 자격 증명 없이 실행할 수 있게 유지한다.
- 병합 후 예제 테스트가 누락되지 않도록 새 모듈을 Gradle 설정, CI, Nightly에 등록한다.
- 각 모듈의 `README.md`와 `README.ko.md`에 실행 가능한 명령을 문서화한다.

## 제외 범위

- `aws-exposed`, `aws-spring-boot` 또는 `aws-ktor`에 새 운영 API를 추가하지 않는다.
- 이 예제에 LocalStack을 사용하지 않는다. 데이터베이스는 PostgreSQL Testcontainers로
  검증하고 AWS 원격 구성은 로컬/정적 설정이나 테스트 resolver로 표현한다.
- 첫 예제 경로에서 RDS IAM 인증을 요구하지 않는다. 이슈 #77에서 토큰 생성을 이미
  검증했으므로 이 예제는 도입 방법에 집중한다.
- Flyway나 Liquibase 같은 스키마 마이그레이션 도구를 추가하지 않는다.

## 모듈 형태

게시하지 않는 예제 모듈 두 개를 만든다.

- `examples/aws-spring-boot-exposed-examples`
  - Gradle 프로젝트 경로: `:aws-spring-boot-exposed-examples`
  - 패키지: `io.bluetape4k.aws.examples.spring.exposed`
  - 의존성:
    - `project(":bluetape4k-aws-spring-boot")`
    - `project(":bluetape4k-aws-exposed")`
    - `libs.bluetape4k.exposed.jdbc`
    - 필요한 Exposed JDBC/BOM 의존성
    - PostgreSQL JDBC 드라이버
    - Spring Boot MVC 웹 및 테스트 지원
    - `libs.testcontainers.postgresql`

- `examples/aws-ktor-exposed-examples`
  - Gradle 프로젝트 경로: `:aws-ktor-exposed-examples`
  - 패키지: `io.bluetape4k.aws.examples.ktor.exposed`
  - 의존성:
    - `project(":bluetape4k-aws-ktor")`
    - `project(":bluetape4k-aws-exposed")`
    - `libs.bluetape4k.exposed.jdbc`
    - Ktor 서버/테스트/Jackson 의존성
    - PostgreSQL JDBC 드라이버
    - `libs.testcontainers.postgresql`

두 모듈은 다음을 포함한다.

- `src/test/resources/junit-platform.properties`
- `src/test/resources/logback-test.xml`
- 구조가 일치하는 영문 및 한글 README 파일

## 도메인 모델

두 예제에서 작은 주문 모델을 사용한다.

- `OrderRecord(id: Long = 0, customerId: String, status: String, description: String = "") : Serializable`
- `OrdersTable : LongIdTable("example_orders")`
- `OrderRepository : LongJdbcRepository<OrderRecord>`

`OrderRecord`는 Kotlin `data class`이므로 `java.io.Serializable`을 구현하고
`serialVersionUID`를 정의해야 한다. init 블록은 예를 들어
`customerId.requireNotBlank("customerId")`와 `status.requireNotBlank("status")`
같은 bluetape4k 도우미로 호출자 입력을 검증한다.

저장소는 독립적인 원시 Exposed 전용 저장소 대신 `bluetape4k-exposed`
`LongJdbcRepository`를 사용해야 한다. 다음을 구현한다.

- `override val table`
- `override fun extractId(entity)`
- `override fun ResultRow.toEntity()`
- `override fun BatchInsertStatement.bindSave(entity)`
- `fun save(entity): OrderRecord`
- `fun findByCustomerId(customerId): List<OrderRecord>`

상속받은 `JdbcRepository` 조회 도우미인 `findById`, `findByIdOrNull`, `findAll`도
예제 API의 일부로 유지하며 명시적 트랜잭션 경계를 통해 사용한다.

`JdbcRepository` 구현은 이 방식으로 쓰기와 읽기를 매핑하므로 저장소 메서드 안에서
Exposed DSL을 계속 사용할 수 있다. 저장소 메서드는 다음 프레임워크 트랜잭션 래퍼
밖에서 호출하지 않는다.

- Spring: `OrderService`가 호출을 `transaction(database) { ... }`로 감싼다.
- Ktor: 라우트가 호출을 `call.awsExposedTransaction { ... }`로 감싼다.

`findByCustomerId`는 작은 예제 계약을 위해 의도적으로 제한을 두지 않는다. 운영
코드는 `ExposedPage` 같은 bluetape4k-exposed 페이징 API로 페이지를 나눠야 한다.

## Spring Boot 예제

`SpringBootExposedExampleApplication`은 표준 Spring Boot MVC 애플리케이션을
로드한다. 이 예제는 JDBC 기반이고 bluetape4k-exposed `LongJdbcRepository`
메서드는 블로킹 Exposed JDBC 연산이므로 WebFlux가 아니라
`spring-boot-starter-web`을 사용한다.

`OrderService`가 트랜잭션 경계를 소유한다. `AwsExposedDefaultDatabaseAutoConfiguration`이
제공하는 Exposed `Database` 빈을 받아 모든 저장소 호출을 감싼다.

```kotlin
transaction(database) {
    repository.save(record)
}
```

컨트롤러 메서드는 `OrderService`를 호출하고 저장소 메서드를 직접 호출하지 않는다.
따라서 활성 Exposed 트랜잭션 밖에서 `JdbcRepository` 메서드가 실행되지 않으며,
WebFlux를 사용하지 않아 블로킹 JDBC 작업이 Reactor 이벤트 루프에서 실행되지 않는다.

`OrderController`는 다음을 노출한다.

| 메서드 | 경로 | 동작 |
|---|---|---|
| `POST` | `/orders` | 주문 하나를 생성하고 `201`을 반환한다. |
| `GET` | `/orders/{id}` | 주문 하나 또는 `404`를 반환한다. |
| `GET` | `/orders` | 주문 목록을 반환하며 선택적으로 `customerId`로 필터링한다. |

`OrderRepository`는 `Database`를 소유하지 않고 `TransactionManager.defaultDatabase`에
의존하지 않는다. 호출자가 `OrderService`가 연 트랜잭션 안에서 실행한다고 가정한다.

`OrderSchemaInitializer`는 애플리케이션 시작 시 Spring이 제공하는 Exposed
`Database`로 `OrdersTable`을 생성한다. `Database` 빈 연결 후, 예제 사용자가 일반
요청을 보내기 전에 실행되는 `ApplicationRunner`로 구현한다.

```kotlin
transaction(database) {
    SchemaUtils.create(OrdersTable)
}
```

이렇게 하면 마이그레이션을 추가하지 않고도 예제를 독립적으로 실행할 수 있다.

테스트는 `TestRestTemplate` 및 공통 bluetape4k Testcontainers launcher와 함께
`@SpringBootTest(webEnvironment = RANDOM_PORT)`를 사용한다. 테스트 클래스는
`@TestInstance(TestInstance.Lifecycle.PER_CLASS)`를 사용한다. `PostgreSQLContainer`를
직접 생성하지 말고 `bluetape4k-testcontainers`의 `PostgreSQLServer.Launcher.postgres`를
사용한다. Kotlin `@DynamicPropertySource` 메서드는 Spring 속성 바인딩 전에 이 launcher
싱글턴에서 값을 읽는 `@JvmStatic` companion-object 함수다.

- `bluetape4k.aws.exposed.default-database.url`
- `bluetape4k.aws.exposed.default-database.driver-class-name`
- `bluetape4k.aws.exposed.default-database.username`
- `bluetape4k.aws.exposed.default-database.password`
- `maximum-pool-size=2`, `minimum-idle=0` 같은 작은 풀 설정

Spring 컨텍스트는 다음을 포함한다.

- `AwsAutoConfiguration`
- `AwsExposedAutoConfiguration`
- `AwsExposedDefaultDatabaseAutoConfiguration`

테스트는 다음을 입증한다.

- Spring 자동 구성이 Testcontainers 속성에서 `AwsExposedDatabaseRegistry`,
  `DataSource`, Exposed `Database`를 생성한다.
- 컨트롤러/저장소가 주문을 생성하고 다시 조회한다.

선택적인 자동 구성 슬라이스 검증에는 `ApplicationContextRunner`를 사용할 수 있지만,
HTTP 생성/조회 검증은 Spring Boot 웹 테스트 컨텍스트를 사용해야 한다.

## Ktor 예제

`exposedExampleModule(database: ExampleDatabaseConfig)`은 다음을 설치한다.

- `ContentNegotiation { jackson() }`
- `AwsExposedPlugin`
- `call.awsExposedTransaction { ... }` 기반 라우트

라우트:

| 메서드 | 경로 | 동작 |
|---|---|---|
| `POST` | `/exposed/orders` | 주문 하나를 생성하고 `201`을 반환한다. |
| `GET` | `/exposed/orders/{id}` | 주문 하나 또는 `404`를 반환한다. |
| `GET` | `/exposed/orders` | 주문 목록을 반환하며 선택적으로 `customerId`로 필터링한다. |

애플리케이션 시작 시 플러그인이 레지스트리를 생성한 뒤 플러그인 런타임을 통해
`OrdersTable`을 초기화한다. `Application` 확장 안에서 `AwsExposedPlugin` 설치 후
`monitor.subscribe(ApplicationStarted) { ... }`를 호출하고, 동기 생명주기 이벤트를
플러그인 트랜잭션 도우미에 연결한다.

```kotlin
monitor.subscribe(ApplicationStarted) {
    runBlocking(Dispatchers.IO) {
        awsExposedTransaction {
            SchemaUtils.create(OrdersTable)
        }
    }
}
```

이는 `AwsExposedPlugin` 자체의 동기 Ktor 생명주기 브리지를 따르는 엄격히 통제된
시작 브리지다. 예제 시작 중 일회성 스키마 초기화에만 허용하며 라우트 핸들러는
`runBlocking`을 사용하면 안 된다.

`awsExposedTransaction`은 기본값이 `Dispatchers.IO`인 플러그인 트랜잭션 컨텍스트에서
블록을 실행한다. 따라서 라우트 코드는 이벤트 루프에서 블로킹 JDBC 저장소를 직접
호출하지 않고 `call.awsExposedTransaction { ... }` 안에서 저장소 메서드를 호출해야 한다.

테스트는 PostgreSQL Testcontainers 속성을 사용하는 `testApplication`과
`@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 테스트 클래스로 HTTP를 통한
생성/조회를 입증한다.

## 자격 증명 및 에뮬레이터 전략

- 기본 예제 구성은 직접 JDBC 설정을 사용하며 AWS 자격 증명을 요구하지 않는다.
- 원격 AWS 구성 소스 서술자는 선택적 코드 조각으로 문서화할 수 있지만 테스트는
  AWS, LocalStack 또는 Floci를 호출하지 않아야 한다.
- 테스트는 공통 `PostgreSQLServer.Launcher.postgres` 인스턴스의 직접 동적 JDBC
  속성이나 직접 Ktor DSL 값을 사용한다. 나중에 별도 resolver 예제가 명시적으로
  필요하지 않으면 경쟁하는 `AwsDatabaseSettingsResolver` 테스트 경로를 추가하지 않는다.
- Spring과 Ktor 테스트 모두 `bluetape4k-testcontainers`의
  `PostgreSQLServer.Launcher.postgres`를 사용한다. launcher가 이미지/시작 생명주기를
  소유하고 Nightly에서 테스트 클래스별 Postgres 컨테이너 중복을 방지한다.
- Ktor 테스트 도우미는 `ExampleDatabaseConfig.from(postgres)`처럼 같은 launcher에서
  `ExampleDatabaseConfig`를 만들고, launcher 값을 Ktor
  `defaultDatabase { url/driverClassName/username/password/pool }` DSL에 매핑한다.

## CI와 Nightly

새 예제 모듈은 다음에 등록해야 한다.

- `settings.gradle.kts`
- `.github/workflows/ci.yml`
- `.github/workflows/nightly-tests.yml`

CI는 이 예제를 위해 현재 저장소 정책을 의도적으로 확장해야 한다. 예제 모듈과 상위
어댑터 모듈에 다음 경로 필터를 추가한다.

- `examples/aws-spring-boot-exposed-examples/**`
- `examples/aws-ktor-exposed-examples/**`
- `aws-exposed/**`
- `aws-spring-boot/**`
- `aws-ktor/**`

그런 다음 이 경로가 바뀌거나 `workflow_dispatch`로 실행할 때 동작하는 대상 테스트
job을 추가한다. 이슈 #82 인수 조건은 예제가 Nightly뿐 아니라 CI에서도 컴파일돼야
한다고 규정하며, 상위 어댑터 회귀 검증이 Nightly까지 기다리면 안 되기 때문이다.

테스트가 컨테이너 기반 PostgreSQL을 사용하므로 Nightly는 전체 범위에서 두 모듈을 실행해야 한다.

## 인수 기준

- `./gradlew projects`에 새 모듈 두 개가 표시된다.
- `./gradlew :aws-spring-boot-exposed-examples:test`가 통과한다.
- `./gradlew :aws-ktor-exposed-examples:test`가 통과한다.
- `./gradlew build -x test --parallel`이 두 모듈을 컴파일한다.
- README 파일에 실행 가능한 명령이 있고 실제 AWS 자격 증명을 요구하지 않는다.
- `.omx/artifacts` 아래 로컬 워크플로 산출물과 최종 교훈 기록은 구현이 다음 단계로
  이동하기 전에 Claude Code CLI 명세/계획/코드 리뷰 게이트가 `P0=0`, `P1=0`에
  도달했음을 보여 준다.
