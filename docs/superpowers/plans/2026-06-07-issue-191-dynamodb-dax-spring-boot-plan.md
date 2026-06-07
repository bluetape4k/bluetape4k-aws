# Issue #191 DynamoDB DAX Spring Boot Plan

Date: 2026-06-07
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/191
Spec: `docs/superpowers/specs/2026-06-07-issue-191-dynamodb-dax-spring-boot-design.md`

## Gate Status

- Spec review: PASS, `P0=0`, `P1=0`
- Plan review: pending

## Implementation Steps

### 1. Dependency Boundary

- Add local catalog aliases:
  - `dax-client = "2.0.9"`
  - `aws-dax-client = { module = "software.amazon.dax:amazon-dax-client", version.ref = "dax-client" }`
- Add to `aws-spring-boot/build.gradle.kts`:
  - `compileOnly(libs.aws.dax.client)`
  - `testImplementation(libs.aws.dax.client)`
- Run dependency insight:
  - `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --configuration testCompileClasspath --dependency amazon-dax-client`
  - `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --configuration testCompileClasspath --dependency software.amazon.awssdk:dynamodb`
- Confirm `amazon-dax-client:2.0.9` is present and AWS SDK DynamoDB remains on the repository/catalog-selected line.

### 2. Properties

- Add `DynamoDbDaxProperties`.
- Add `val dax: DynamoDbDaxProperties = DynamoDbDaxProperties()` to `DynamoDbProperties`.
- Keep existing `endpointOverride` validation.
- Validate DAX URL/timeouts/retries only from the DAX auto-configuration path
  when DAX is enabled and classpath is present.
- Use bluetape4k validation helpers where they fit the value type; use
  `require` only when no existing helper matches `Duration`/`URI` semantics.

### 3. Shared DynamoDB Auto-Configuration Helpers

- Extract private helpers from `DynamoDbAutoConfiguration` when needed:
  - `resolveCredentialsProvider`
  - `resolveAwsProperties`
- Keep them package-private/internal to avoid new public API.

### 4. DAX Auto-Configuration

- Add `DynamoDbDaxAutoConfiguration`.
- Conditions:
  - after `AwsAutoConfiguration`
  - before `DynamoDbAutoConfiguration`
  - `@ConditionalOnClass(name = ["software.amazon.dax.ClusterDaxAsyncClient"])`
  - `@ConditionalOnProperty(prefix = "bluetape4k.aws.dynamodb", name = ["enabled"], havingValue = "true", matchIfMissing = true)`
  - `@ConditionalOnProperty(prefix = "bluetape4k.aws.dynamodb.dax", name = ["enabled"], havingValue = "true")`
  - `@ConditionalOnMissingBean(DynamoDbAsyncClient::class)`
- Build `ClusterDaxAsyncClient` via:
  - `ClusterDaxAsyncClient.builder().overrideConfiguration(Configuration.builder()...build()).build()`
- Apply:
  - `url`
  - `region` from `dax.region ?: dynamodb.region ?: aws.region`
  - credentials provider from existing Spring AWS resolver
  - timeout/retry/concurrency/hostname verification properties
- Do not apply AWS SDK async client customizers; document as DAX-specific property tuning.
- DAX-enabled context tests must register a dummy static `AwsCredentialsProvider`
  because `Configuration.Builder` resolves credentials at bean construction
  time.

### 5. Auto-Configuration Registration

- Register `DynamoDbDaxAutoConfiguration` in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  before `DynamoDbAutoConfiguration`.

### 6. Tests

Update `DynamoDbAutoConfigurationTest` or add a dedicated
`DynamoDbDaxAutoConfigurationTest`.

Required cases:

- default DynamoDB path registers one regular `DynamoDbAsyncClient` and one enhanced client.
- `dax.enabled=true` with filtered `software.amazon.dax` classpath does not create a DAX client and default DynamoDB path remains available.
- `dax.enabled=true` without `dax.url` fails clearly.
- `dax.enabled=true` with `dax.url` creates a `ClusterDaxAsyncClient` as the only `DynamoDbAsyncClient`.
- enhanced client is present when the DAX client is selected.
- custom `DynamoDbAsyncClient` bean backs off DAX and default client creation.

### 7. Docs And Lesson

- Update root `README.md` and `README.ko.md`.
- Update `aws-spring-boot/README.md` and `aws-spring-boot/README.ko.md`.
- Add `docs/lessons/2026-06-07-issue-191-dynamodb-dax.md`.
- Mention:
  - consumer runtime dependency
  - DAX is for real AWS DAX clusters, not LocalStack/DynamoDB Local
  - cache consistency/latency tradeoffs
  - existing repository code continues using `DynamoDbEnhancedAsyncClient`

### 8. Validation

Run, in order:

```bash
./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --configuration testCompileClasspath --dependency amazon-dax-client
./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --configuration testCompileClasspath --dependency software.amazon.awssdk:dynamodb
./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.dynamodb.*'
./gradlew :bluetape4k-aws-spring-boot:test
git diff --check
```

If `:bluetape4k-aws-spring-boot:test` is too broad or blocked by unrelated
emulator/runtime issues, keep the targeted DynamoDB test result and record the
blocker explicitly.

## PR Scope

Expected touched files:

- `gradle/libs.versions.toml`
- `aws-spring-boot/build.gradle.kts`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/dynamodb/*`
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/dynamodb/*`
- `README.md`
- `README.ko.md`
- `aws-spring-boot/README.md`
- `aws-spring-boot/README.ko.md`
- `docs/lessons/2026-06-07-issue-191-dynamodb-dax.md`

## Stop Condition

- Implementation compiles.
- Targeted tests pass.
- Local review reports `P0=0`, `P1=0`.
- PR is created with verified body ending in `## DoD Status`.
