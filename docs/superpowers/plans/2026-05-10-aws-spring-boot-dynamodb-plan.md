# aws-spring-boot DynamoDB Repository Plan

Date: 2026-05-10
Spec: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository/docs/superpowers/specs/2026-05-10-aws-spring-boot-dynamodb-design.md`
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/3

## Execution Rules

- Work inside `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository`.
- Keep #3 independent from PR #30 (`aws #2`) unless a direct dependency is
  discovered.
- Do not use awspring.
- Keep AWS service SDK dependencies `compileOnly` in main and explicit
  `testImplementation` for tests.
- Public APIs get Korean KDoc.
- README.md and README.ko.md stay in sync.

## Step 1: Build And Registration

1. Update `aws-spring-boot/build.gradle.kts`.
   - Add `compileOnly(libs.aws2.dynamodb.enhanced)`.
   - Add `testImplementation(libs.aws2.dynamodb.enhanced)`.
2. Add `DynamoDbAutoConfiguration` to
   `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
3. Compile early:
   - `./gradlew :aws-spring-boot:compileKotlin --no-daemon`

## Step 2: Properties And Table Name Resolver

Create package `io.bluetape4k.aws.spring.dynamodb`.

Files:

- `DynamoDbProperties.kt`
- `DynamoDbTableNameResolver.kt`
- `DefaultDynamoDbTableNameResolver.kt`

Tasks:

1. Implement `DynamoDbProperties` with prefix `bluetape4k.aws.dynamodb`.
2. Enforce `endpointOverride == null || region is not blank`.
3. Add `tablePrefix: String = ""`.
4. Implement resolver as `tablePrefix + tableName`.
5. Add Korean KDoc to public types.

Tests:

- Property binding success.
- Endpoint override without region fails.
- Resolver applies prefix.

## Step 3: Auto-Configuration

Create `DynamoDbAutoConfiguration.kt`.

Bean methods:

1. `dynamoDbAsyncClient(...)`
   - `@Bean(destroyMethod = "close")`
   - `@ConditionalOnMissingBean`
   - `DynamoDbAsyncClient.builder()`
   - credentials provider fallback
   - optional region, endpoint, async HTTP client
2. `dynamoDbEnhancedAsyncClient(dynamoDbAsyncClient)`
   - `@ConditionalOnMissingBean`
   - `DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(dynamoDbAsyncClient).build()`
3. `dynamoDbTableNameResolver(properties)`
   - `@ConditionalOnMissingBean`
   - `DefaultDynamoDbTableNameResolver(properties.tablePrefix)`

ContextRunner tests:

- all beans registered by default.
- `enabled=false` backs off.
- custom `DynamoDbAsyncClient` backs off.
- custom `DynamoDbEnhancedAsyncClient` backs off.
- custom resolver backs off.
- classpath absence backs off with `FilteredClassLoader`.

## Step 4: Repository Contract And Base Class

Files:

- `CoroutinesDynamoDbRepository.kt`
- `AbstractCoroutinesDynamoDbRepository.kt`

Contract:

- `save(item): T`
- `findById(id): T?`
- `existsById(id): Boolean`
- `deleteById(id): T?`
- `delete(item): T?`
- `update(item): T?`
- `scan(...): Flow<T>`
- `query(...): Flow<T>`
- `queryIndex(...): Flow<T>`

Base class:

1. Constructor receives:
   - `DynamoDbEnhancedAsyncClient`
   - `DynamoDbTableNameResolver`
   - `entityClass: Class<T>`
2. Subclasses provide:
   - `tableName`
   - `keyOf(id: ID)`
   - optionally `keyOf(item: T)`
   - optionally `tableSchema`
3. Build table lazily with resolved table name and schema.
4. Use `CompletableFuture.await()` for single operations.
5. Use `table.scan(request).items().asFlow()`.
6. Use `table.query(request).items().asFlow()`.
7. Use `table.index(indexName).query(request).items().asFlow()`.
8. Rethrow `CancellationException` if any catch boundary is introduced.

## Step 5: LocalStack Test Model

Create test-only model under
`aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/dynamodb`.

Model:

- `OrderDocument`
- `@DynamoDbBean`
- partition key: `orderId`
- sort key: `createdAt`
- GSI partition key: `customerId`
- GSI sort key: `createdAt`
- mutable properties and public no-arg constructor compatible with Enhanced
  Client.

Repository:

- `OrderRepository : AbstractCoroutinesDynamoDbRepository<OrderDocument, OrderId>`
- `OrderId(orderId: String, createdAt: String)`
- helper `findByCustomer(customerId): Flow<OrderDocument>` using `queryIndex`.

Table setup:

- Use `DynamoDbAsyncClient.createTable` or enhanced table `createTable`.
- Include primary key schema and `customer-createdAt-index` GSI.
- Wait until table exists/active with bounded Awaitility.

## Step 6: Tests

ApplicationContextRunner:

1. registers `DynamoDbAsyncClient`, `DynamoDbEnhancedAsyncClient`,
   `DynamoDbTableNameResolver`, `DynamoDbProperties`.
2. disabled property registers no DynamoDB beans.
3. custom beans back off.
4. endpoint override requires region.
5. table prefix binds and resolver applies it.
6. classpath absence backs off.

LocalStack:

1. CRUD: save -> findById -> existsById -> update -> deleteById.
2. Scan Flow returns all inserted items.
3. Query Flow returns items for a partition key.
4. GSI query Flow returns items for a customer.
5. Table prefix works by using a prefixed table name in test properties.

## Step 7: Documentation

Update root docs:

- `README.md`
- `README.ko.md`

Document:

- AWS SDK runtime dependency: `software.amazon.awssdk:dynamodb-enhanced`
- auto-configured beans
- `bluetape4k.aws.dynamodb.*` properties
- repository extension example with `@DynamoDbBean`
- note that table creation is explicit and not performed by auto-config

## Step 8: Verification

Run in order:

1. `./gradlew :aws-spring-boot:compileKotlin :aws-spring-boot:compileTestKotlin --no-daemon`
2. `./gradlew :aws-spring-boot:test --no-daemon`
3. `./gradlew :aws-spring-boot:koverHtmlReport detekt :aws-spring-boot:build -x test --no-daemon`
4. `rg 'runBlocking|Thread\\.sleep|GlobalScope' aws-spring-boot/src/main/kotlin`
5. `git diff --check`

If LocalStack fails for environmental reasons, inspect the concrete failure and
retry once before classifying it as environment-only.

## Step 9: Review, Commit, PR

1. Run local self-review focused on:
   - AWS publisher Flow cancellation
   - repository key mapping
   - GSI schema/query mismatch
   - Spring conditional back-off
2. Attempt Claude advisor review with full absolute paths and save artifact.
3. Commit implementation with Lore trailers and
   `Co-authored-by: OmX <omx@oh-my-codex.dev>`.
4. Push `feat/3-dynamodb-repository`.
5. Create PR title:
   - `[feat] aws-spring-boot DynamoDB coroutine repository`
6. PR body in Korean and include `Closes #3`.

## Checklist

| Item | Status | Notes |
|---|---|---|
| Worktree scoped | Done | `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository` |
| Existing repo reuse identified | Done | Existing `aws` DynamoDB Enhanced Async helpers inform implementation. |
| Official docs checked | Done | AWS SDK Java v2 Enhanced Async Client and Spring Boot auto-config patterns. |
| Implementation order dependency-safe | Done | Build/properties/autoconfig before repository/tests/docs. |
| Verification commands listed | Done | Compile, tests, kover/build, static scans, diff check. |
