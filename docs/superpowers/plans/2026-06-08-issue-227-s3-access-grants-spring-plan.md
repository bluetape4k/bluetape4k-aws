# Issue #227 Spring S3 Access Grants Plan

## Objective

Deliver optional Spring Boot S3 Access Grants support for `aws-spring-boot`
without adding runtime dependencies or default beans for basic S3 users.

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

- Add `aws2-s3control` to `gradle/libs.versions.toml`.
- Add `compileOnly(libs.aws2.s3control)` and
  `testImplementation(libs.aws2.s3control)` to
  `aws-spring-boot/build.gradle.kts`.
- Verify with:
  `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency s3control --configuration compileClasspath`.

DoD:

- `s3control` appears only through compile/test scopes.
- No `api` or `runtimeOnly` dependency is added.

### Step 2 - Properties

- Add `S3AccessGrantsProperties` under
  `io.bluetape4k.aws.spring.s3.accessgrants`.
- Prefix: `bluetape4k.aws.s3.access-grants`.
- Defaults:
  - `enabled=false`
  - `region=null`
  - `endpointOverride=null`
- Add validation for blank region if a string value is present.
- Keep the data class `Serializable` with `serialVersionUID`.

DoD:

- Properties are isolated from `S3Properties` to avoid enabling Access Grants
  with basic S3.

### Step 3 - Operations and Template

- Add `S3AccessGrantsOperations`.
- Add `S3AccessGrantsCoroutinesTemplate` backed by `S3ControlAsyncClient`.
- Use `CompletableFuture.await()` from `kotlinx-coroutines-jdk8` transitively
  available through existing AWS Java support.
- Expose minimal application access workflow methods:
  - `getDataAccess(GetDataAccessRequest)`
  - `listCallerAccessGrants(ListCallerAccessGrantsRequest)`
  - `listAccessGrants(ListAccessGrantsRequest)`
  - `listAccessGrantsInstances(ListAccessGrantsInstancesRequest)`
  - `listAccessGrantsLocations(ListAccessGrantsLocationsRequest)`
- Keep administrative create/delete/update operations available through raw
  caller-owned `S3ControlClient` / `S3ControlAsyncClient` beans.

DoD:

- Template methods are suspend functions and rethrow coroutine cancellation by
  not wrapping suspend calls in `runCatching`.
- No broad S3 Control compatibility surface is committed in this issue.

### Step 4 - Auto-Configuration

- Add `S3AccessGrantsAutoConfiguration`.
- Register it in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  after `S3AutoConfiguration`.
- Use string `@ConditionalOnClass` guards for:
  - `software.amazon.awssdk.services.s3control.S3ControlClient`
  - `software.amazon.awssdk.services.s3control.S3ControlAsyncClient`
- Use property guards:
  - `bluetape4k.aws.s3.enabled=true` or missing.
  - `bluetape4k.aws.s3.access-grants.enabled=true`.
- Create sync and async S3 Control clients with:
  - shared AWS defaults
  - credentials provider fallback
  - optional sync/async HTTP client beans
  - global customizers using service name `s3control`
  - service-specific customizers for `S3ControlClientBuilder` and
    `S3ControlAsyncClientBuilder`
- Add `S3AccessGrantsOperations` bean when missing.

DoD:

- Caller-provided clients and operations back off correctly.
- Owned clients are closed by Spring through `destroyMethod="close"`.

### Step 5 - Tests

Add `S3AccessGrantsAutoConfigurationTest` and
`S3AccessGrantsCoroutinesTemplateTest`.

Auto-configuration test cases:

- Disabled by default.
- Enabled registers `S3ControlClient`, `S3ControlAsyncClient`,
  `S3AccessGrantsProperties`, `S3AccessGrantsOperations`, and template.
- Missing `s3control` classes backs off with `FilteredClassLoader`.
- Basic S3 disabled also disables Access Grants.
- Caller-provided sync/async clients are reused.
- Caller-provided operations backs off the template.
- Endpoint override without region fails through shared defaults.
- Global and service customizers apply in deterministic order.

Template test cases:

- `getDataAccess` delegates to async client and awaits result.
- List methods delegate to async client and await result.

DoD:

- Tests use bluetape4k assertions, MockK, and `runSuspendIO` or a suitable
  existing coroutine test helper.
- No emulator or real AWS dependency is introduced.

### Step 6 - Documentation and Lesson

- Update root `README.md` and `README.ko.md`.
- Mention the optional `software.amazon.awssdk:s3control` consumer dependency.
- Add a short `docs/lessons/2026-06-08-issue-227-s3-access-grants-spring.md`.

DoD:

- English and Korean README entries are consistent.
- Lesson records the `s3control` discovery and optional dependency guard.

## Validation Commands

Run in order:

```bash
./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency s3control --configuration compileClasspath
./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:test --tests '*S3AccessGrants*' --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:test --tests '*S3AutoConfigurationTest' --tests '*S3AccessGrants*' --no-daemon --max-workers=1
git diff --check
```

If CI snapshot metadata returns Sonatype 403, retry failed CI jobs once and
classify the failure from logs before treating it as code failure.

## Review Checklist

- P0/P1 workflow gate compliance.
- Optional dependency remains optional.
- `@ConditionalOnClass(name = [...])` protects every compileOnly bean signature.
- `@ConditionalOnProperty` applies to the new auto-configuration class.
- Existing S3 users do not get Access Grants beans unless explicitly enabled.
- Coroutine cancellation is not swallowed.
- Public KDoc is English.
- README locale set is updated.
