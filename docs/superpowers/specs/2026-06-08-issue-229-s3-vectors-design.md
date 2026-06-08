# Issue #229 S3 Vectors Design

Date: 2026-06-08
Issue: #229
Work type: Type A Full Feature

## Context

Issue #229 is the 0.4.0 follow-up for S3 Vector support that was deferred from
the earlier Spring and Ktor advanced S3 work. The previous slices intentionally
kept S3 Vectors out of the default S3 API surface because it uses a separate AWS
SDK Java v2 service module and a different runtime model from ordinary S3 object
operations.

Current upstream evidence shows that AWS SDK Java v2 exposes
`software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient`, and the
repository's current AWS SDK v2 version line, `2.46.0`, has a published Maven
artifact at `software.amazon.awssdk:s3vectors:2.46.0`. The AWS API surface is a
dedicated `s3vectors` service with vector bucket, vector index, vector
put/get/list/query, policy, and tagging operations.

## Current Repository Evidence

- GitHub issue #229 was updated on 2026-06-08 with the current SDK and optional
  dependency constraints.
- `gradle/libs.versions.toml` currently has `aws2 = "2.46.0"` and S3 aliases
  such as `aws2-s3`, `aws2-s3control`, and `aws2-s3-transfer-manager`, but no
  `aws2-s3vectors` alias.
- `aws-java` has coroutine-first extension patterns for AWS SDK Java v2 async
  clients under packages such as `io.bluetape4k.aws.s3`, `cloudwatch`, `sqs`,
  and `kinesis`.
- `aws-spring-boot` Access Grants uses `compileOnly` SDK dependencies,
  string-based `@ConditionalOnClass` guards, explicit opt-in properties, caller
  bean backoff, and `ApplicationContextRunner` tests.
- `aws-ktor` Access Grants uses caller-owned operations/client support,
  plugin-owned client cleanup on `ApplicationStopping`, and `AwsKtorCore`
  default/customizer inheritance.
- Prior lessons require S3 Access Grants and S3 Vectors to stay optional until
  the service-specific API surface is stable and explicitly wrapped.
- CodeGraph is not available in this session, so source discovery used GNO,
  official AWS documentation, Maven artifact checks, and direct source reads.

## Goals

- Add optional S3 Vectors support without changing basic S3 runtime behavior.
- Add a small `aws-java` coroutine facade over `S3VectorsAsyncClient` for the
  common application path.
- Add Spring Boot 4 auto-configuration that is disabled by default, guarded by
  SDK class presence, and compatible with caller-owned beans.
- Add Ktor 3 plugin support that can inherit `AwsKtorCore` defaults and
  customizers while remaining side-effect-free until operations are invoked.
- Document the runtime dependency and unsupported emulator status in English and
  Korean README files.

## Non-Goals

- Do not add S3 Vectors methods to `S3Operations` or `S3KtorClient`.
- Do not make `software.amazon.awssdk:s3vectors` an `api` or mandatory runtime
  dependency of any published bluetape4k module.
- Do not claim Floci, LocalStack, or Ministack support for S3 Vectors unless a
  verified emulator contract is added later.
- Do not wrap every administrative policy/tagging API in the first pass.
  Applications can still use the raw `S3VectorsAsyncClient` for unsupported
  operations.
- Do not add AWS Kotlin SDK S3 Vectors support in this issue; the verified
  upstream surface for this slice is AWS SDK Java v2.

## Dependency Contract

Add to `gradle/libs.versions.toml`:

```toml
aws2-s3vectors = { module = "software.amazon.awssdk:s3vectors", version.ref = "aws2" }
```

Add as `compileOnly` and `testImplementation` where the optional API is used:

- `aws-java/build.gradle.kts`
- `aws-spring-boot/build.gradle.kts`
- `aws-ktor/build.gradle.kts`

Consumer documentation must state:

```kotlin
runtimeOnly("software.amazon.awssdk:s3vectors")
```

## Public API Shape

### aws-java

Add package `io.bluetape4k.aws.s3vectors`.

Public surface:

- `S3VectorsOperations`: suspend facade over common application operations.
- `S3VectorsCoroutinesTemplate`: `S3VectorsAsyncClient` backed implementation
  using `CompletableFuture.await()`.
- `S3VectorsAsyncClientCoroutinesExtensions`: low-level `*Suspend` extensions
  when direct SDK client usage is enough. The suffix avoids Kotlin member
  resolution conflicts with AWS SDK async-client methods that already return
  `CompletableFuture`.

Initial stable operation set:

- `listVectorBuckets`
- `getVectorBucket`
- `listIndexes`
- `getIndex`
- `putVectors`
- `getVectors`
- `listVectors`
- `queryVectors`

This subset covers discovery and application read/write/query workflows while
leaving policy, tagging, and destructive administrative APIs to raw SDK clients.

### aws-spring-boot

Add package `io.bluetape4k.aws.spring.s3vectors`.

Public surface:

- `S3VectorsProperties`
- `S3VectorsAutoConfiguration`
- `S3VectorsOperations` from `aws-java`
- `S3VectorsCoroutinesTemplate` from `aws-java`

Property prefix:

```properties
bluetape4k.aws.s3-vectors
```

Initial properties:

- `enabled`: default `false`
- `region`: optional service-specific override
- `endpointOverride`: optional service-specific endpoint override

Auto-configuration contract:

- Register after `AwsAutoConfiguration`.
- Guard with string-based `@ConditionalOnClass(name = [...])` for
  `S3VectorsAsyncClient`, `S3VectorsAsyncClientBuilder`, `SdkAsyncHttpClient`,
  and supporting AWS SDK types that appear in bean signatures.
- Require `bluetape4k.aws.s3-vectors.enabled=true`.
- Create a plugin-owned `S3VectorsAsyncClient` only when no caller bean exists.
- Create `S3VectorsOperations` only when no caller bean exists.
- Reuse `AwsProperties.resolveClientDefaults`, `applyAwsDefaults`,
  `applyGlobalCustomizers("s3vectors", ...)`, and
  `applyServiceCustomizers(...)`.

### aws-ktor

Add package `io.bluetape4k.aws.ktor.s3vectors`.

Public surface:

- `S3VectorsOperations` from `aws-java`
- `S3VectorsCoroutinesTemplate` from `aws-java`
- `S3VectorsKtorPlugin`
- `S3VectorsKtorPluginConfig`
- `Application.s3Vectors()` and `Application.s3VectorsOrNull()`
- `AwsKtorS3VectorsAsyncClientCustomizer`

Plugin contract:

- Installing the plugin stores runtime/operations only; it does not call AWS.
- Caller-owned operations bypass client creation and endpoint validation.
- Caller-owned `S3VectorsAsyncClient` remains application-owned.
- Plugin-owned clients close once on `ApplicationStopping` using the existing
  Ktor Access Grants lifecycle pattern.
- Plugin-created clients inherit region, endpoint, credentials, and service
  customizers from `AwsKtorCore`, then apply service-local customizers.
- Endpoint override requires a non-blank region.
- Route-level tests should use `bluetape4k-ktor-testing` helpers.
- A Ktor-local facade is allowed only if implementation proves that the shared
  `aws-java` facade creates an actual package-boundary problem; record the
  reason in the plan/review before adding one.

## Testing Strategy

Use JUnit 5, MockK, bluetape4k assertions, `runSuspendIO` or appropriate
coroutine test helpers, `ApplicationContextRunner`, and Ktor test application
patterns already present in the repository.

Required tests:

- `aws-java`: template delegates each supported SDK call and awaits
  `CompletableFuture` completion.
- `aws-java`: low-level suspend extensions preserve SDK request/response types.
- `aws-spring-boot`: disabled by default.
- `aws-spring-boot`: enabling properties registers client and operations.
- `aws-spring-boot`: missing `software.amazon.awssdk.services.s3vectors`
  classes backs off cleanly via `FilteredClassLoader`.
- `aws-spring-boot`: caller-owned `S3VectorsAsyncClient` and
  `S3VectorsOperations` beans are reused.
- `aws-spring-boot`: global and service-specific customizers apply with service
  name `s3vectors`.
- `aws-ktor`: disabled plugin stores no operations.
- `aws-ktor`: caller-owned operations bypass client validation.
- `aws-ktor`: caller-owned async client is not closed by plugin shutdown.
- `aws-ktor`: plugin-owned async client inherits `AwsKtorCore` defaults and
  closes on application shutdown.
- `aws-ktor`: route-level usage can call installed operations using
  bluetape4k Ktor testing helpers.

Do not add emulator tests for this issue. The test report and README must not
imply local emulator support for S3 Vectors.

## Documentation

Update:

- Root `README.md`
- Root `README.ko.md`
- `aws-java/README.md` and `aws-java/README.ko.md` if present
- `aws-spring-boot/README.md` and `aws-spring-boot/README.ko.md`
- `aws-ktor/README.md` and `aws-ktor/README.ko.md`

Documentation must explain:

- S3 Vectors is optional and separate from ordinary S3 object operations.
- Consumers must add the AWS SDK runtime dependency.
- Spring Boot and Ktor integrations are disabled/opt-in by default.
- Emulator-backed tests are not claimed in this slice.

Generated diagram work is required only if README architecture or flow content
changes enough to need a new visual asset. If a diagram is added, it must use
the `bluetape4k-diagram` PNG/SVG workflow and geometry gates.

## Risks

- The S3 Vectors service is new and may still change faster than older S3 APIs.
  Keep the first public facade narrow and direct.
- The SDK async client can still block during credentials or endpoint discovery,
  so production users need normal AWS SDK timeout/retry configuration.
- No emulator currently proves S3 Vectors behavior, so request construction and
  wiring tests are the first-pass confidence boundary.
- Adding service-specific customizers to `AwsKtorCore` can bloat the defaults
  object. Keep the new customizer symmetric with existing service customizers
  and cover equality/toString behavior through existing tests.
- Adding a local catalog alias duplicates dependency governance until
  `bluetape4k-dependencies` exports the same alias; use the existing `aws2`
  version line and avoid a new version key.

## DoD

- Spec review reports `P0=0`, `P1=0`.
- Plan review reports `P0=0`, `P1=0`.
- `:bluetape4k-aws-java`, `:bluetape4k-aws-spring-boot`, and
  `:bluetape4k-aws-ktor` compile and focused tests pass.
- README locale sets are updated when public behavior is documented.
- `docs/lessons/2026-06-08-issue-229-s3-vectors.md` is added.
- Final 7-Tier code review reports `P0=0`, `P1=0`.
