# 이슈 #11 Ktor DynamoDB 설계

작성일: 2026-05-14
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/11
브랜치: `issue-11-ktor-dynamodb`
관련 항목: https://github.com/bluetape4k/bluetape4k-aws/issues/85

## 목표

`bluetape4k-aws`의 `:aws-kotlin` module과 공식 AWS SDK for Kotlin을 주요 AWS surface로 사용해 DynamoDB repository-style access를 위한 Ktor server integration을 추가한다.

plugin은 Ktor application이 DynamoDB 지원을 설치하고 관리되는 AWS Kotlin SDK `DynamoDbClient`에 접근하며 repository object를 생성하고 선택적으로 table을 생성하며 scan/query 결과를 Kotlin `Flow`로 제공할 수 있게 해야 한다.

## 현재 상태

- `aws-ktor`는 현재 `:aws`를 `api`로, `:aws-kotlin`을 `compileOnly`로 의존한다.
- 기존 `aws-ktor` SQS integration은 Java SDK v2 `SqsAsyncClient`를 사용한다.
- 이슈 #11은 원래 Java SDK v2인 DynamoDB Enhanced Client를 언급한다.
- `aws-kotlin`은 이미 `dynamoDbClientOf`, `withDynamoDbClient`, table helper, batch executor, `DynamoItemMapper`를 포함한 native suspend DynamoDB 지원을 제공한다.
- AWS Kotlin SDK DynamoDB Mapper가 있지만 AWS는 이를 Developer Preview로 문서화한다. 이 이슈의 기본 stable dependency가 아니라 평가/후속 경로로 취급한다.

## 외부 reference 확인

- Ktor custom plugin은 `createApplicationPlugin`과 `ApplicationStarted`, `ApplicationStopped` 같은 lifecycle monitoring event를 사용할 수 있다.
- AWS SDK for Kotlin DynamoDB는 native suspend operation과 paginator를 제공한다.
- AWS SDK for Kotlin DynamoDB Mapper는 Developer Preview이며 이 이슈의 주요 구현 대상이 아니다.
- AWS Java SDK v2 Enhanced Async Client는 paginated `PagePublisher` 결과를 반환하지만 Java Enhanced Client는 이 이슈의 기본 Ktor 경로가 아니다.

## 설계 방향

### dependency 방향

두 Kotlin-first layer를 사용한다.

1. client 생성, DynamoDB DSL, table utility, batch helper, mapper convention에 `bluetape4k-aws` `:aws-kotlin` helper를 재사용한다.
2. 공식 AWS SDK for Kotlin DynamoDB module(`aws.sdk.kotlin:dynamodb`)을 기반 service SDK로 사용한다.

기존 `:aws-kotlin` helper를 `aws-ktor` 내부에서 다시 구현하지 않는다.

이 이슈의 dependency 결정:

- 새 Ktor DynamoDB API가 의도적으로 `:aws-kotlin` convention을 노출하므로 `aws-ktor`에서 `project(":aws-kotlin")`을 `compileOnly`에서 `api`로 승격한다.
- `aws-ktor`에 `compileOnly(libs.aws.kotlin.dynamodb)`과 `testImplementation(libs.aws.kotlin.dynamodb)`을 추가한다.
- 저장소 규칙에 따라 AWS service SDK dependency는 compile-only로 유지하므로 consumer가 사용하는 AWS Kotlin DynamoDB runtime dependency를 계속 추가한다.
- #85에서 compatibility-safe migration 경로를 정의할 때까지 기존 S3/SQS/SigV4 code를 위해 `project(":aws")`를 `api`로 유지한다.

### 주요 API

`:aws-kotlin` module을 통해 AWS Kotlin SDK `DynamoDbClient`를 사용한다.

```kotlin
install(DynamoDbKtorPlugin) {
    region = "ap-northeast-2"
    tablePrefix = "dev_"
    autoCreateTables = true
}

val dynamoDb = application.dynamoDb()
```

plugin은 하나의 runtime registry를 제공해야 한다.

- `DynamoDbKtorPlugin`
- `DynamoDbKtorPluginConfig`
- `DynamoDbKtorRuntime`
- `DynamoDbKtorRuntimeKey`
- `Application.dynamoDb()`

`Application.dynamoDb()`는 `DynamoDbKtorRuntime`을 반환한다. runtime은 기본 `DynamoDbClient`, table definition, repository/table helper를 저장한다. 구현에서 test에 필요하지 않는 한 named client는 v1 범위에서 제외한다.

### 저장소 API

`aws-kotlin` mapper convention을 기반으로 `aws-ktor`에 Kotlin-SDK repository 계약을 제공한다.

```kotlin
interface KtorDynamoDbRepository<T: Any, ID: Any> {
    val tableName: String
    val runtime: DynamoDbKtorRuntime

    suspend fun save(item: T): T
    suspend fun findById(id: ID): T?
    suspend fun deleteById(id: ID): T?
    fun scan(...): Flow<T>
    fun query(...): Flow<T>
}
```

AWS Kotlin SDK type 때문에 완전히 generic한 CRUD abstraction이 취약해진다면 정확한 generic 계약은 Spring Boot repository 계약보다 작게 유지할 수 있다. 명확하게 test할 수 없는 광범위한 API보다 안정적인 mapper + table binding 계약을 우선한다.

### 매핑

reflection이 아니라 명시적인 mapping을 사용한다.

- `DynamoItemMapper<T>`는 entity를 DynamoDB item map으로 변환한다.
- `:aws-kotlin`에 `DynamoItemReader<T>`를 추가한다.
  `fun readDynamoItem(item: Map<String, AttributeValue>): T`.
- repository 구현은 key selector/reader를 명시적으로 받아야 한다.
- `aws-ktor` repository 지원은 `DynamoItemMapper<T>`, `DynamoItemReader<T>`, key selector function을 조합한다.

이유: Kotlin SDK Mapper는 Developer Preview이고 raw reflection 기반 mapping은 `aws-ktor` 내부에 불안정한 새 framework를 추가한다.

### table 생성

명시적인 table definition을 통해 선택형 table auto-creation을 지원한다.

plugin은 임의의 Kotlin class에서 schema를 추론하면 안 된다. table definition은 `createTable`을 안전하게 호출할 수 있는 충분한 AWS Kotlin SDK request data를 포함해야 한다.

`autoCreateTables`는 plugin configuration에 명시적으로 등록한 table의 startup 생성만 제어한다. DynamoDB가 이미 존재한다고 보고하는 table은 건너뛰며 schema verification은 연기한다.

### 생명주기

- plugin은 자신이 생성한 client를 소유한다.
- plugin은 application-injected `DynamoDbClient`를 닫으면 안 된다.
- 설정하면 startup에서 table을 생성할 수 있다.
- shutdown에서는 plugin-owned client만 닫는다.

기존 SQS plugin의 Ktor monitoring event는 synchronous하다. shutdown에 suspend cleanup이 필요하면 동일한 제한적 `runBlocking(Dispatchers.IO)` pattern을 사용하고 이유를 문서화한다.

| client source | startup 동작 | shutdown 동작 |
|---|---|---|
| plugin-created client | region/endpoint/credentials config로 생성 | `ApplicationStopping`에서 bounded timeout으로 닫음 |
| injected client | 그대로 사용 | 닫지 않음 |

## module / dependency 규칙

- production code에서 AWS service SDK dependency를 `compileOnly`로 유지한다.
- `aws-ktor`에서 `project(":aws-kotlin")`을 `api`로 승격한다.
- `aws-ktor`에 `compileOnly(libs.aws.kotlin.dynamodb)`을 추가한다.
- test에 `testImplementation(libs.aws.kotlin.dynamodb)`을 추가한다.
- `aws-ktor`에서 client factory, table utility, mapper, DynamoDB helper를 중복하지 않고 `:aws-kotlin` public helper를 재사용한다.
- `:aws`는 `aws-ktor`가 이미 사용하는 기존 shared Java SDK v2 utility에만 유지한다. 새 DynamoDB Ktor repository를 기본적으로 Java SDK v2를 통해 routing하지 않는다.

## 목표가 아닌 항목

- Java SDK v2 Enhanced Client를 주요 Ktor 구현으로 사용하지 않는다.
- 초기 범위에서 AWS Kotlin DynamoDB Mapper dependency를 필수로 요구하지 않는다.
- v1에서 `count`, `batchGet`, 고급 update expression, schema verification, named-client registry를 제공하지 않는다.
- Spring dependency를 추가하지 않는다.
- 이 이슈에서 새 example module을 추가하지 않는다. 최소 test fixture가 자연스럽게 example이 되지 않는 한 Ktor DynamoDB example은 후속 #17을 사용한다.

## 위험

- generic repository API는 지나치게 약하거나 마법처럼 동작할 수 있다. 초기 계약을 작고 mapper-driven하게 유지한다.
- AWS Kotlin SDK Mapper는 Developer Preview이므로 변경될 수 있다.
- LocalStack DynamoDB eventual consistency로 flaky test가 발생할 수 있다. 고정 sleep 대신 Awaitility 또는 bounded polling을 사용한다.
- 현재 `aws-ktor` SQS code는 여전히 Java SDK v2를 사용한다. 이 이슈를 기존 SQS integration의 광범위한 migration으로 취급하지 않는다. `:aws-kotlin`과 공식 AWS SDK for Kotlin을 향한 기존 `aws-ktor` migration은 #85에서 별도로 추적한다.

## 인수 기준

- Ktor application이 `DynamoDbKtorPlugin`을 설치할 수 있다.
- plugin이 AWS Kotlin SDK `DynamoDbClient`를 생성하거나 받을 수 있다.
- `Application.dynamoDb()`가 route에서 사용할 수 있는 `DynamoDbKtorRuntime`을 반환한다.
- repository 지원이 `:aws-kotlin`, 공식 AWS SDK for Kotlin DynamoDB type, `DynamoItemMapper<T>`, `DynamoItemReader<T>`, 명시적인 key selector를 사용한다.
- scan/query API가 Kotlin `Flow`를 제공한다.
- 선택형 table auto-creation이 명시적이고 test로 검증된다.
- test로 plugin이 injected client를 닫지 않음을 입증한다.
- unit test가 config validation과 lifecycle ownership을 다룬다.
- LocalStack test가 단순 mapped entity의 save/find/query 또는 scan을 다룬다.
- README와 README.ko가 dependency 요구 사항과 plugin 사용법을 문서화한다.

## 단계 2-R 검토 기록

- Claude advisor 산출물:
  `.omx/artifacts/claude-issue-11-ktor-dynamodb-spec-20260514-201237.md`.
- P0/P1 수용 항목:
  - `project(":aws-kotlin")`은 `aws-ktor`에서 `api`여야 한다.
  - `DynamoItemReader<T>`는 `:aws-kotlin`에 속한다.
  - `Application.dynamoDb()`는 `DynamoDbKtorRuntime`을 반환한다.
  - table auto-creation에는 명시적으로 등록한 table definition이 필요하다.
  - lifecycle ownership은 plugin-created client와 injected client를 구분해야 한다.
  - v1 repository 범위는 `save`, `findById`, `deleteById`, `scan`, `query`이며 고급 operation은 연기한다.
- 기각 항목:
  - 이 이슈에서 `project(":aws")`를 강등하는 방안. 기존 S3/SQS/SigV4 code가 여전히 의존하며 해당 migration은 #85의 범위다.
- 수정 후 수렴 상태: P0 = 0, P1 = 0.
