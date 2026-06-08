# Issue #229 S3 Vectors Plan

Date: 2026-06-08
Issue: #229
Spec: `docs/superpowers/specs/2026-06-08-issue-229-s3-vectors-design.md`

## Objective

Deliver optional S3 Vectors support across `aws-java`, `aws-spring-boot`, and
`aws-ktor` without adding a mandatory runtime dependency or changing ordinary
S3 object-operation behavior.

## Gate Order

1. Issue intake and update.
2. Spec.
3. Spec review, required `P0=0`, `P1=0`.
4. Plan.
5. Plan review, required `P0=0`, `P1=0`.
6. Implementation.
7. Local verification.
8. 7-tier code review, required `P0=0`, `P1=0`.
9. PR body verification and CI.

## Implementation Steps

### Step 1 - Dependency Alias

- Add `aws2-s3vectors` to `gradle/libs.versions.toml` using the existing
  `aws2` version line.
- Add `compileOnly(libs.aws2.s3vectors)` and
  `testImplementation(libs.aws2.s3vectors)` to:
  - `aws-java/build.gradle.kts`
  - `aws-spring-boot/build.gradle.kts`
  - `aws-ktor/build.gradle.kts`
- Verify with dependency insight for all three modules.

DoD:

- `s3vectors` appears only through compile/test scopes.
- No `api` or `runtimeOnly` dependency is added.
- No new version key is introduced.

### Step 2 - Shared aws-java Facade

- Add `io.bluetape4k.aws.s3vectors.S3VectorsOperations`.
- Add `S3VectorsCoroutinesTemplate` backed by `S3VectorsAsyncClient`.
- Add `S3VectorsAsyncClientCoroutinesExtensions` for the same stable operation
  set, using `*Suspend` names to avoid AWS SDK member-method resolution
  conflicts.
- Expose suspend functions for:
  - `listVectorBuckets`
  - `getVectorBucket`
  - `listIndexes`
  - `getIndex`
  - `putVectors`
  - `getVectors`
  - `listVectors`
  - `queryVectors`
- Use `CompletableFuture.await()` and do not wrap suspend calls in
  `runCatching`.
- Add English KDoc on public classes, interfaces, and extension functions.

DoD:

- Spring and Ktor can reuse this facade without a duplicate adapter-specific
  operations interface.
- Unsupported policy/tagging/destructive admin APIs remain available through
  the raw SDK client and are documented as out of first-pass scope.

### Step 3 - aws-java Tests

- Add focused MockK tests for `S3VectorsCoroutinesTemplate`.
- Add focused tests for low-level suspend extensions where they add coverage
  beyond the template.
- Cover exceptional completion and cancellation propagation where practical.

DoD:

- Tests use bluetape4k assertions and `runSuspendIO` or the established
  coroutine helper for IO-like async SDK futures.
- No emulator or real AWS dependency is introduced.

### Step 4 - Spring Boot Auto-Configuration

- Add `io.bluetape4k.aws.spring.s3vectors.S3VectorsProperties`.
- Add `S3VectorsAutoConfiguration`.
- Register it in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  after `AwsAutoConfiguration`.
- Use string `@ConditionalOnClass(name = [...])` guards for compile-only SDK
  types, including:
  - `software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient`
  - `software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClientBuilder`
  - `software.amazon.awssdk.http.async.SdkAsyncHttpClient`
- Use `@ConditionalOnProperty(prefix = "bluetape4k.aws.s3-vectors", name = ["enabled"], havingValue = "true")`.
- Create `S3VectorsAsyncClient` with:
  - shared `AwsProperties` client defaults
  - credentials provider fallback
  - optional async HTTP client bean
  - global customizers using service name `s3vectors`
  - service-specific `AwsClientCustomizer<S3VectorsAsyncClientBuilder>`
- Create shared `S3VectorsOperations` via `S3VectorsCoroutinesTemplate` when no
  caller bean exists.

DoD:

- Caller-provided client and operations beans back off correctly.
- Owned client is closed by Spring through `destroyMethod = "close"`.
- No basic S3 property enables S3 Vectors implicitly.

### Step 5 - Spring Boot Tests

- Add `S3VectorsAutoConfigurationTest`.
- Test cases:
  - disabled by default
  - enabled registers `S3VectorsAsyncClient`, `S3VectorsProperties`, and
    `S3VectorsOperations`
  - missing `s3vectors` classes back off via `FilteredClassLoader`
  - caller-provided `S3VectorsAsyncClient` is reused
  - caller-provided `S3VectorsOperations` backs off the template
  - endpoint override without region fails through shared defaults
  - global and service customizers apply in deterministic order

DoD:

- Tests use `ApplicationContextRunner`, MockK only where needed, and
  bluetape4k assertions.
- No emulator/real AWS claim is introduced.

### Step 6 - Ktor Shared Defaults

- Add `AwsKtorS3VectorsAsyncClientCustomizer`.
- Add the new customizer list to `AwsKtorDefaults` and `AwsKtorCoreConfig`.
- Update equality, hashCode, toString helper, and existing `AwsKtorCoreTest`
  coverage.

DoD:

- Existing `AwsKtorCore` behavior remains backward compatible.
- S3 Vectors customizers are symmetric with existing S3 Control/CloudWatch/SQS
  customizer lanes.

### Step 7 - Ktor Plugin

- Add package `io.bluetape4k.aws.ktor.s3vectors`.
- Add runtime holder, plugin config, plugin, and application accessors:
  - `S3VectorsKtorRuntime`
  - `S3VectorsKtorPluginConfig`
  - `S3VectorsKtorPlugin`
  - `Application.s3Vectors()`
  - `Application.s3VectorsOrNull()`
- Reuse `S3VectorsOperations` and `S3VectorsCoroutinesTemplate` from
  `aws-java`.
- Support caller-owned operations, caller-owned async client, and plugin-owned
  async client.
- Close only plugin-owned clients on `ApplicationStopping`.
- Inherit `AwsKtorCore` region, endpoint override, Java credentials provider,
  and S3 Vectors customizers, then apply service-local customizers.

DoD:

- Plugin install is side-effect-free until operations are called.
- Caller-owned operations bypass client creation and endpoint validation.
- Endpoint override requires region only when a plugin-owned client must be
  created.

### Step 8 - Ktor Tests

- Add focused plugin/config/runtime tests.
- Test cases:
  - disabled plugin stores no operations and accessor fails
  - injected operations are stored and route-level usage works
  - injected operations bypass endpoint validation
  - injected client remains application-owned
  - plugin-owned client closes once
  - shared customizer runs before service customizer
  - template delegates all supported operations to `S3VectorsAsyncClient`

DoD:

- Route-level tests use `bluetape4k-ktor-testing` helpers where status/assertion
  helpers fit the current Ktor test shape.
- Tests use bluetape4k assertions and class-level MockK mocks where strict
  interaction checks matter.

### Step 9 - Documentation, Research Preservation, and Lesson

- Preserve official AWS S3 Vectors research in `bluetape4k-wiki` with a concise
  Korean summary note, then validate with GNO commands if the wiki toolchain is
  available.
- Update README locale sets:
  - root `README.md` and `README.ko.md`
  - `aws-java/README.md` and `aws-java/README.ko.md`
  - `aws-spring-boot/README.md` and `aws-spring-boot/README.ko.md`
  - `aws-ktor/README.md` and `aws-ktor/README.ko.md`
- Add `docs/lessons/2026-06-08-issue-229-s3-vectors.md`.
- Add diagram assets only if README architecture/flow content changes enough to
  need visuals; if added, follow `bluetape4k-diagram` PNG/SVG and geometry
  gates.

DoD:

- Documentation names the optional runtime dependency.
- Documentation states that emulator-backed S3 Vectors behavior is not claimed.
- Lesson records the optional dependency and shared facade reuse decision.

## Validation Commands

Run in order:

```bash
./gradlew :bluetape4k-aws-java:dependencyInsight --dependency s3vectors --configuration compileClasspath --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency s3vectors --configuration compileClasspath --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency s3vectors --configuration compileClasspath --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-java:compileTestKotlin --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:compileTestKotlin --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-java:test --tests '*S3Vectors*' --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:test --tests '*S3Vectors*' --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-ktor:test --tests '*S3Vectors*' --tests '*AwsKtorCoreTest' --no-daemon --max-workers=1
git diff --check
```

If GitHub Actions snapshot metadata returns Sonatype 403, retry failed CI jobs
once and classify the failure from logs before treating it as a code failure.

## Review Checklist

- P0/P1 workflow gate compliance.
- `software.amazon.awssdk:s3vectors` remains optional.
- Shared `aws-java` operations facade is reused by Spring and Ktor.
- Compile-only SDK types are protected by string `@ConditionalOnClass` guards.
- Spring and Ktor integrations are disabled or absent by default.
- Coroutine cancellation is not swallowed.
- Plugin-owned resources are closed; caller-owned resources are not.
- Public KDoc is English.
- README locale set is updated.
- No emulator-backed behavior is claimed.
