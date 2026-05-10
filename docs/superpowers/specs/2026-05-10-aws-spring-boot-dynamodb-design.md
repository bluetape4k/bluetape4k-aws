# aws-spring-boot DynamoDB Repository Design

Date: 2026-05-10
Repo: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository`
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/3

## Problem

`aws-spring-boot` currently provides the common `AwsAutoConfiguration` and S3
auto-configuration. Issue #3 asks for a Spring Boot 4 DynamoDB integration that
does not depend on awspring:

- `DynamoDbAutoConfiguration`
- `DynamoDbAsyncClient` and `DynamoDbEnhancedAsyncClient` beans
- `DynamoDbProperties` bound to `bluetape4k.aws.dynamodb`
- `CoroutinesDynamoDbRepository<T, ID>` interface
- `AbstractCoroutinesDynamoDbRepository<T, ID>` base implementation
- `@DynamoDbBean` mapping support
- paging, scan, query as Kotlin `Flow`
- secondary index query support
- LocalStack + Testcontainers CRUD, paging, query coverage

The public Spring API should be coroutine-first and should reuse the existing
`aws` module DynamoDB Enhanced Async helpers where they already fit.

## Evidence

### Current Repo

- `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository/aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3AutoConfiguration.kt`
  is the nearest auto-configuration pattern: `@AutoConfiguration`,
  `@ConditionalOnClass`, `@ConditionalOnProperty`, `@EnableConfigurationProperties`,
  `ObjectProvider<AwsCredentialsProvider>`, optional HTTP client beans, and
  `@ConditionalOnMissingBean`.
- `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository/aws/src/main/kotlin/io/bluetape4k/aws/dynamodb/repository/DynamoDbCoroutineRepository.kt`
  already provides a coroutine repository for entities implementing
  `DynamoDbEntity`, but it is tied to that entity model and has no Spring Boot
  auto-configuration.
- `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository/aws/src/main/kotlin/io/bluetape4k/aws/dynamodb/enhanced/DynamoDbAsyncTableExtensions.kt`
  already provides coroutine/Flow helpers for `DynamoDbAsyncTable<T>`:
  `getItem`, `putItem`, `deleteItem`, `scanAll`, `queryAll`, `queryByPartition`,
  `findAll`, `findByPartition`, and `exists`.
- `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository/aws/src/main/kotlin/io/bluetape4k/aws/dynamodb/enhanced/DynamoDbEnhancedAsyncClientSupport.kt`
  wraps `DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(client).build()`.
- `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository/aws/src/test/kotlin/io/bluetape4k/aws/dynamodb/examples/food/repository/CustomerRepository.kt`
  shows current repository usage through `DynamoDbEnhancedAsyncClient.table(...)`,
  `QueryEnhancedRequest`, and `QueryConditional`.
- `gradle/libs.versions.toml` already exposes
  `libs.aws2.dynamodb.enhanced` for `software.amazon.awssdk:dynamodb-enhanced`.

### Official Docs

- AWS SDK for Java 2.x builds `DynamoDbEnhancedAsyncClient` from a
  `DynamoDbAsyncClient`.
- Enhanced async single-item operations return `CompletableFuture`.
- Enhanced async scan/query operations return `PagePublisher<T>` / `SdkPublisher`
  and should be consumed asynchronously.
- `TableSchema.fromBean(MyClass::class.java)` supports `@DynamoDbBean` mapping.
- Table and index query support is exposed through `DynamoDbAsyncTable<T>` and
  `DynamoDbAsyncIndex<T>` with `QueryEnhancedRequest` and `QueryConditional`.
- Spring Boot auto-configuration should use classpath conditions,
  `@ConditionalOnMissingBean`, and typed configuration properties.

## Goals

1. Auto-configure DynamoDB beans when the AWS DynamoDB Enhanced SDK is present:
   - `DynamoDbAsyncClient`
   - `DynamoDbEnhancedAsyncClient`
2. Bind `bluetape4k.aws.dynamodb.*` properties:
   - `enabled`
   - `region`
   - `endpoint-override`
   - table prefix/default capacity/test-friendly defaults when needed
3. Provide Spring-friendly coroutine repository base types:
   - `CoroutinesDynamoDbRepository<T, ID>`
   - `AbstractCoroutinesDynamoDbRepository<T, ID>`
4. Support `@DynamoDbBean` entities through `TableSchema.fromBean(...)`.
5. Support common operations:
   - save, find by id, delete by id
   - update/delete item overloads
   - scan as `Flow<T>`
   - query as `Flow<T>`
   - index query as `Flow<T>`
   - bounded first page helpers where useful
6. Add LocalStack tests for bean registration, CRUD, scan paging, query, and
   index query.
7. Sync README.md and README.ko.md.

## Non-Goals

- Do not add awspring or Spring Data DynamoDB.
- Do not implement annotation scanning that auto-discovers every repository
  interface in this first PR.
- Do not build a full Spring Data repository factory, derived query parser, or
  transaction abstraction.
- Do not require entities to implement the existing `DynamoDbEntity`; the new
  Spring repository must work with ordinary `@DynamoDbBean` classes.
- Do not auto-create production tables on application startup. Tests may create
  tables explicitly.
- Do not hide DynamoDB consistency/capacity trade-offs behind magic defaults.

## Approach Options

### Option A: Reuse Existing `DynamoDbCoroutineRepository`

Use the existing `aws` module repository interface directly.

Pros:
- Very small surface.
- Reuses tested code.

Cons:
- Requires `T : DynamoDbEntity`, which conflicts with issue #3's generic
  `@DynamoDbBean` mapping goal.
- Does not model `ID`.
- Does not give Spring users a clean abstract repository base.

Decision: reject as the primary Spring API, but reuse its ideas and enhanced
async extension functions.

### Option B: Spring Data-Like Repository Factory

Implement repository interface scanning, generated proxies, and method-name
query derivation.

Pros:
- Familiar Spring user experience.

Cons:
- Large blast radius.
- Query derivation is a separate product surface.
- Hard to finish safely in a first DynamoDB PR.

Decision: reject for #3. Leave it as a future issue after the base repository
is proven.

### Option C: Explicit Abstract Repository Base

Provide a small generic interface and abstract class. Applications define
repository beans by extending the base and supplying table name, schema, and key
mapping.

Pros:
- Works with plain `@DynamoDbBean`.
- Keeps mapping/key/index decisions explicit.
- Easy to test with LocalStack.
- Reuses AWS Enhanced Async client directly.

Cons:
- Slightly more boilerplate per repository.
- No derived query methods.

Decision: accept for #3.

## Proposed API

Package:

```text
io.bluetape4k.aws.spring.dynamodb
  DynamoDbAutoConfiguration
  DynamoDbProperties
  CoroutinesDynamoDbRepository
  AbstractCoroutinesDynamoDbRepository
  DynamoDbTableNameResolver
  DefaultDynamoDbTableNameResolver
```

Repository contract:

```kotlin
interface CoroutinesDynamoDbRepository<T: Any, ID: Any> {
    val tableName: String
    val table: DynamoDbAsyncTable<T>

    suspend fun save(item: T): T
    suspend fun findById(id: ID): T?
    suspend fun existsById(id: ID): Boolean
    suspend fun deleteById(id: ID): T?
    suspend fun delete(item: T): T?
    suspend fun update(item: T): T?

    fun scan(builder: ScanEnhancedRequest.Builder.() -> Unit = {}): Flow<T>
    fun query(
        queryConditional: QueryConditional,
        builder: QueryEnhancedRequest.Builder.() -> Unit = {},
    ): Flow<T>
    fun queryIndex(
        indexName: String,
        queryConditional: QueryConditional,
        builder: QueryEnhancedRequest.Builder.() -> Unit = {},
    ): Flow<T>
}
```

Abstract base:

```kotlin
abstract class AbstractCoroutinesDynamoDbRepository<T: Any, ID: Any>(
    private val enhancedClient: DynamoDbEnhancedAsyncClient,
    private val tableNameResolver: DynamoDbTableNameResolver,
    private val entityClass: Class<T>,
) : CoroutinesDynamoDbRepository<T, ID> {
    abstract override val tableName: String
    abstract fun keyOf(id: ID): Key
    open fun keyOf(item: T): Key = error("Override when delete(item) is used")
}
```

`table` is derived from:

```kotlin
enhancedClient.table(tableNameResolver.resolve(tableName), TableSchema.fromBean(entityClass))
```

For entities better represented by static schemas, allow a protected constructor
or overridable `tableSchema`:

```kotlin
protected open val tableSchema: TableSchema<T> = TableSchema.fromBean(entityClass)
```

## Auto-Configuration

`DynamoDbAutoConfiguration` should mirror S3/SQS patterns:

- `@AutoConfiguration(after = [AwsAutoConfiguration::class])`
- `@ConditionalOnClass` strings:
  - `software.amazon.awssdk.http.async.SdkAsyncHttpClient`
  - `software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient`
  - `software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient`
- `@ConditionalOnProperty(prefix = "bluetape4k.aws.dynamodb", name = ["enabled"], havingValue = "true", matchIfMissing = true)`
- `@EnableConfigurationProperties(DynamoDbProperties::class)`

Beans:

- `DynamoDbAsyncClient`, `@ConditionalOnMissingBean`, `destroyMethod = "close"`
- `DynamoDbEnhancedAsyncClient`, `@ConditionalOnMissingBean`
- `DynamoDbTableNameResolver`, `@ConditionalOnMissingBean`

Builder rules:

- Use `AwsCredentialsProvider` from `ObjectProvider`, fallback to
  `DefaultCredentialsProvider.builder().build()`.
- Apply `Region.of(properties.region)` only when configured.
- Apply `endpointOverride` only when configured.
- Accept optional `SdkAsyncHttpClient`.
- Reject `endpointOverride` without `region`, because the AWS signer still
  needs a region.

## Properties

```kotlin
@ConfigurationProperties(prefix = "bluetape4k.aws.dynamodb")
data class DynamoDbProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val tablePrefix: String = "",
)
```

`tablePrefix` is used only by `DynamoDbTableNameResolver`; it lets tests and
multi-environment apps keep repository code stable.

## Risks And Failure Modes

1. **Kotlin `@DynamoDbBean` mutability requirements.** Enhanced Client bean
   mapping needs compatible getters/setters and no-arg construction. Tests must
   use realistic mutable bean classes rather than immutable data classes.
2. **Index creation mismatch.** Repository index query support is only useful
   when the LocalStack test table has a GSI with matching annotations and table
   creation metadata.
3. **Publisher conversion and cancellation.** Query/scan must use reactive
   `asFlow()` over AWS publishers and avoid blocking collection.
4. **Table auto-creation temptation.** Auto-creating tables in auto-config would
   create production side effects. Keep table creation explicit in tests.
5. **Compile-only AWS dependency boundary.** Main code must add
   `compileOnly(libs.aws2.dynamodb.enhanced)` and tests must add
   `testImplementation(libs.aws2.dynamodb.enhanced)`.

## Acceptance Criteria

- `DynamoDbAutoConfiguration` registers the async and enhanced async clients
  when SDK classes are present.
- Custom user beans back off auto-configured defaults.
- Disabled property prevents DynamoDB beans.
- Endpoint override without region fails binding/startup.
- Repository base supports `@DynamoDbBean` CRUD against LocalStack.
- Scan/query return `Flow<T>`.
- GSI query works against a test table.
- README.md and README.ko.md document dependency, properties, and repository
  usage.

## Definition Of Done

- Spec and plan committed before implementation.
- Advisor review attempted with full absolute paths and accepted findings
  integrated.
- `./gradlew :aws-spring-boot:compileKotlin :aws-spring-boot:compileTestKotlin --no-daemon`
- `./gradlew :aws-spring-boot:test --no-daemon`
- `./gradlew :aws-spring-boot:koverHtmlReport detekt :aws-spring-boot:build -x test --no-daemon`
- `git diff --check`
- PR title uses `[feat]`, not `[codex]`.
- PR body is Korean and includes `Closes #3`.
