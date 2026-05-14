# Issue #11 Ktor DynamoDB Design

Date: 2026-05-14
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/11
Branch: `issue-11-ktor-dynamodb`
Related: https://github.com/bluetape4k/bluetape4k-aws/issues/85

## Goal

Add Ktor server integration for DynamoDB repository-style access using
`bluetape4k-aws`'s `:aws-kotlin` module and the official AWS SDK for Kotlin as
the primary AWS surface.

The plugin should let a Ktor application install DynamoDB support, access a
managed AWS Kotlin SDK `DynamoDbClient`, create repository objects, optionally
create tables, and expose scan/query results as Kotlin `Flow`.

## Current Reality

- `aws-ktor` currently depends on `:aws` as `api` and `:aws-kotlin` as
  `compileOnly`.
- Existing `aws-ktor` SQS integration uses Java SDK v2 `SqsAsyncClient`.
- Issue #11 originally mentions DynamoDB Enhanced Client, which is Java SDK v2.
- `aws-kotlin` already provides native suspend DynamoDB support:
  `dynamoDbClientOf`, `withDynamoDbClient`, table helpers, batch executor, and
  `DynamoItemMapper`.
- AWS Kotlin SDK DynamoDB Mapper exists, but AWS documents it as Developer
  Preview. Treat it as an evaluation/follow-up path, not the default stable
  dependency for this issue.

## External Reference Check

- Ktor custom plugins can use `createApplicationPlugin` and lifecycle
  monitoring events such as `ApplicationStarted` and `ApplicationStopped`.
- AWS SDK for Kotlin DynamoDB provides native suspend operations and paginators.
- AWS SDK for Kotlin DynamoDB Mapper is Developer Preview and is not the
  primary implementation target for this issue.
- AWS Java SDK v2 Enhanced Async Client returns paginated `PagePublisher`
  results, but Java Enhanced Client is not the default Ktor path for this issue.

## Design Direction

### Dependency Direction

Use two Kotlin-first layers:

1. Reuse `bluetape4k-aws` `:aws-kotlin` helpers for client creation, DynamoDB
   DSLs, table utilities, batch helpers, and mapper conventions.
2. Use the official AWS SDK for Kotlin DynamoDB module
   (`aws.sdk.kotlin:dynamodb`) as the underlying service SDK.

Do not reimplement existing `:aws-kotlin` helpers inside `aws-ktor`.

Dependency decision for this issue:

- Promote `project(":aws-kotlin")` from `compileOnly` to `api` in `aws-ktor`
  because the new Ktor DynamoDB API intentionally exposes `:aws-kotlin`
  conventions.
- Add `compileOnly(libs.aws.kotlin.dynamodb)` and
  `testImplementation(libs.aws.kotlin.dynamodb)` to `aws-ktor`.
- Consumers still add the AWS Kotlin DynamoDB runtime dependency they use,
  because AWS service SDK dependencies remain compile-only by repository rule.
- Keep `project(":aws")` as `api` for existing S3/SQS/SigV4 code until #85
  defines a compatibility-safe migration path.

### Primary API

Use AWS Kotlin SDK `DynamoDbClient` through the `:aws-kotlin` module.

```kotlin
install(DynamoDbKtorPlugin) {
    region = "ap-northeast-2"
    tablePrefix = "dev_"
    autoCreateTables = true
}

val dynamoDb = application.dynamoDb()
```

The plugin should expose one runtime registry:

- `DynamoDbKtorPlugin`
- `DynamoDbKtorPluginConfig`
- `DynamoDbKtorRuntime`
- `DynamoDbKtorRuntimeKey`
- `Application.dynamoDb()`

`Application.dynamoDb()` returns `DynamoDbKtorRuntime`. The runtime stores the
default `DynamoDbClient`, table definitions, and repository/table helpers. Named
clients are out of scope for v1 unless implementation needs them for tests.

### Repository API

Provide a Kotlin-SDK repository contract in `aws-ktor`, backed by
`aws-kotlin` mapper conventions.

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

The exact generic contract may stay smaller than the Spring Boot repository
contract if AWS Kotlin SDK types make a fully generic CRUD abstraction brittle.
Prefer a stable mapper + table binding contract over a broad API that cannot be
tested clearly.

### Mapping

Use explicit mapping, not reflection:

- `DynamoItemMapper<T>` converts entities to DynamoDB item maps.
- Add `DynamoItemReader<T>` to `:aws-kotlin`:
  `fun readDynamoItem(item: Map<String, AttributeValue>): T`.
- Repository implementations should accept key selectors/readers explicitly.
- `aws-ktor` repository support composes `DynamoItemMapper<T>`,
  `DynamoItemReader<T>`, and key selector functions.

Reason: Kotlin SDK Mapper is Developer Preview, and raw reflection-based mapping
would add a new unstable framework inside `aws-ktor`.

### Table Creation

Support optional table auto-creation through explicit table definitions.

The plugin should not infer schemas from arbitrary Kotlin classes. A table
definition should carry enough AWS Kotlin SDK request data to call
`createTable` safely.

`autoCreateTables` only gates startup creation for tables explicitly registered
in plugin configuration. Existing tables are skipped when DynamoDB reports they
already exist; schema verification is deferred.

### Lifecycle

- The plugin owns clients it creates.
- The plugin must not close an application-injected `DynamoDbClient`.
- Startup may create tables when configured.
- Shutdown closes only plugin-owned clients.

Ktor monitoring events are synchronous in the existing SQS plugin. If shutdown
needs suspend cleanup, use the same constrained `runBlocking(Dispatchers.IO)`
pattern and document the reason.

| Client source | Startup behavior | Shutdown behavior |
|---|---|---|
| Plugin-created client | Create from region/endpoint/credentials config | Close on `ApplicationStopping` with a bounded timeout |
| Injected client | Use as-is | Do not close |

## Module / Dependency Rules

- Keep AWS service SDK dependencies `compileOnly` for production code.
- Promote `project(":aws-kotlin")` to `api` in `aws-ktor`.
- Add `compileOnly(libs.aws.kotlin.dynamodb)` to `aws-ktor`.
- Add `testImplementation(libs.aws.kotlin.dynamodb)` for tests.
- Reuse `:aws-kotlin` public helpers instead of duplicating client factories,
  table utilities, mappers, or DynamoDB helpers in `aws-ktor`.
- Keep `:aws` available only for existing shared Java SDK v2 utilities already
  used by `aws-ktor`; do not route the new DynamoDB Ktor repository through
  Java SDK v2 by default.

## Non-goals

- No Java SDK v2 Enhanced Client as the primary Ktor implementation.
- No mandatory AWS Kotlin DynamoDB Mapper dependency in the initial slice.
- No `count`, `batchGet`, advanced update expressions, schema verification, or
  named-client registry in v1.
- No Spring dependency.
- No new example module in this issue; use follow-up #17 for Ktor DynamoDB
  examples unless the minimal test fixture naturally becomes an example.

## Risks

- A generic repository API can become too weak or too magical. Keep the initial
  contract small and mapper-driven.
- AWS Kotlin SDK Mapper may change because it is Developer Preview.
- LocalStack DynamoDB eventual consistency can cause flaky tests. Use Awaitility
  or bounded polling instead of fixed sleeps.
- Current `aws-ktor` SQS code still uses Java SDK v2; do not treat this issue as
  a broad migration of existing SQS integration. Existing `aws-ktor` migration
  toward `:aws-kotlin` and official AWS SDK for Kotlin is tracked separately in
  #85.

## Acceptance Criteria

- Ktor applications can install `DynamoDbKtorPlugin`.
- The plugin can create or accept an AWS Kotlin SDK `DynamoDbClient`.
- `Application.dynamoDb()` returns `DynamoDbKtorRuntime` usable from routes.
- Repository support uses `:aws-kotlin`, official AWS SDK for Kotlin DynamoDB
  types, `DynamoItemMapper<T>`, `DynamoItemReader<T>`, and explicit key
  selectors.
- Scan/query APIs expose Kotlin `Flow`.
- Optional table auto-creation is explicit and test-covered.
- Tests prove injected clients are not closed by the plugin.
- Unit tests cover config validation and lifecycle ownership.
- LocalStack tests cover save/find/query or scan for a simple mapped entity.
- README and README.ko document dependency requirements and plugin usage.

## Step 2-R Review Notes

- Claude advisor artifact:
  `.omx/artifacts/claude-issue-11-ktor-dynamodb-spec-20260514-201237.md`.
- P0/P1 accepted:
  - `project(":aws-kotlin")` must be `api` in `aws-ktor`.
  - `DynamoItemReader<T>` belongs in `:aws-kotlin`.
  - `Application.dynamoDb()` returns `DynamoDbKtorRuntime`.
  - Table auto-creation requires explicit registered table definitions.
  - Lifecycle ownership must distinguish plugin-created and injected clients.
  - v1 repository scope is `save`, `findById`, `deleteById`, `scan`, and
    `query`; advanced operations are deferred.
- Rejected:
  - Demoting `project(":aws")` in this issue. Existing S3/SQS/SigV4 code still
    depends on it; #85 owns that migration.
- Convergence: P0 = 0, P1 = 0 after edits.
