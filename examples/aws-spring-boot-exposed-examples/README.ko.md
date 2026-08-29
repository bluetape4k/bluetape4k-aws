# aws-spring-boot-exposed-examples

[English](./README.md) | 한국어

`aws-spring-boot`와 `bluetape4k-aws-exposed` 자동설정 경로를 보여주는
Spring Boot 4 MVC 예제입니다. 로컬 테스트는 Testcontainers PostgreSQL을 사용하며
AWS credential이 필요하지 않습니다. 경계는 작고 명확하게 나눴습니다. Controller는
HTTP status 동작을, service는 `transaction(database) { ... }` 경계를, repository는
Exposed query를 맡습니다.

## 아키텍처

![aws spring boot exposed examples Architecture diagram](../../docs/images/readme-diagrams/examples-aws-spring-boot-exposed-examples-architecture-01.png)

## API

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/orders` | 주문 생성 후 `201 Created` 반환 |
| `GET` | `/orders/{id}` | 주문 조회, 없으면 `404 Not Found` 반환 |
| `GET` | `/orders?customerId={customerId}&limit={limit}&cursor={cursor}` | cursor 페이지로 주문 목록 조회, 고객별 필터 지원 |
| `GET` | `/orders/search` | Query by Example과 제한된 closed projection 검색 |

## Cursor pagination

목록 route는 단순 배열 대신 `ExposedCursorPage<OrderRecord, Long>`을 반환합니다.

```json
{
  "content": [{ "id": 41, "customerId": "customer-1", "status": "CREATED", "notes": null }],
  "nextCursor": 41,
  "hasNext": true
}
```

- `customerId`는 선택 값이며 해당 고객으로 페이지를 필터링합니다.
- `limit`은 `1`~`100` 범위의 선택적 정수이며 기본값은 `20`입니다.
- `cursor`는 마지막으로 본 `OrdersTable.id`인 0 이상 정수입니다. 다음 페이지를
  조회할 때 응답의 `nextCursor`를 그대로 전달합니다.
- 마지막 페이지는 `hasNext: false`, `nextCursor: null`을 반환하며 전체 건수는
  계산하지 않습니다.

이 예제는 wire contract를 명확히 보여주기 위해 raw primary-key cursor를 노출합니다.
운영 호출자는 client에 전달하기 전에 cursor token을 인코딩·서명하고 tenant/권한 범위와
만료 시간을 적용해야 합니다.

## QBE projection 검색

`/orders/search`는 기존 목록 endpoint를 유지하면서 Spring Data Exposed
2.0.0 JDBC adapter를 사용하는 예제입니다.

```text
GET /orders/search?customerId=customer-1&status=PAID&limit=20&sort=-customerId
-> [{"customerId":"customer-1","status":"PAID"}]
```

`customerId`, `status`는 선택적인 exact QBE 조건이며 blank 값은 무시합니다.
`limit` 기본값은 `20`, 상한은 `100`입니다. `sort`는 `customerId` 또는
`status`를 받고, 앞에 `-`를 붙이면 내림차순입니다. 지원하지 않는 정렬·상태와
`1..100` 범위를 벗어난 limit은 `400 Bad Request`를 반환합니다. 일치하는 주문이
없으면 `200 []`을 반환합니다.

이 endpoint는 `ExposedJdbcRepository.findBy(Example)`과
`OrderSummaryProjection` closed projection을 사용합니다. SQL pushdown 테스트는
`customer_id`, `status`만 선택하고 `ORDER BY`, `LIMIT`을 SQL에서 처리하는지,
`notes` column과 count query를 materialize하지 않는지를 확인합니다. Adapter는
현재 Exposed transaction에 연결된 영속 probe를 요구하므로 service가 조건에 맞는
probe를 먼저 읽고, probe가 없으면 빈 목록을 반환합니다.

## 트랜잭션 경계

`OrderController`는 `OrderService`에 위임하고, `OrderService`가
`transaction(database) { ... }` 경계를 소유합니다. `OrderRepository`는 활성화된
Exposed transaction 안에서만 호출됩니다. `OrderSchemaInitializer`는 자동설정된
`Database` bean을 사용할 수 있게 된 뒤 `OrdersTable`을 생성합니다. QBE 검색은
Spring의 `springTransactionManager`로 감싸 probe와 FluentQuery가 하나의 Exposed
transaction을 공유합니다.

QBE, closed projection, 정렬, limit 계약은 AWS wrapper가 아니라 Spring Data
Exposed 2.0.0 adapter가 제공하므로 `bluetape4k-exposed-spring-boot-jdbc` 의존성을
추가했습니다. 공유 BOM은 현재 `2.0.0-SNAPSHOT` 계열 adapter를 해석합니다.
`2.0.0`이 정식 승격되면 immutable dependency-catalog ref를 갱신하고 이 모듈의
통합 테스트와 SQL pushdown 테스트를 다시 실행한 뒤 snapshot 표기를 제거합니다.

## 설정

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

운영 환경에서는 같은 값을 AWS Secrets Manager, Parameter Store, 환경 변수,
또는 Spring 설정 소스에서 제공합니다.

## 실행

```bash
./gradlew :aws-spring-boot-exposed-examples:bootRun
```

## 테스트

```bash
./gradlew :aws-spring-boot-exposed-examples:test
```

테스트는 공유 `PostgreSQLServer.Launcher.postgres` 컨테이너를 시작한 뒤
`bluetape4k-testcontainers-spring`으로 lazy `testcontainers.postgresql.*` 프로퍼티를
노출합니다. `src/test/resources/application.yml`은 이 표준 키를 AWS database prefix로
placeholder 매핑하고, pool 크기만 예제 전용 dynamic 설정으로 별도 유지합니다. 이후
`AwsExposedDatabaseRegistry`, `DataSource`, Exposed `Database`, HTTP 생성/조회/목록/404
동작과 cursor 페이지 순회, 잘못된 cursor·limit 거부, QBE 검색과 projection SQL 형태를
random-port `SpringBootTest`로 검증합니다.
