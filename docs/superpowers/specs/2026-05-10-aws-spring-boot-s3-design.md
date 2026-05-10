# aws-spring-boot S3 Auto-Configuration Design

Date: 2026-05-10
Repo: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/1-spring-boot-s3`
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/1

## Problem

`aws-spring-boot` currently exposes only `AwsAutoConfiguration`, which registers
a default `AwsCredentialsProvider`. Issue #1 asks for an S3 Spring Boot 4
integration without awspring:

- `S3AutoConfiguration`: auto-register `S3AsyncClient`.
- `S3Operations` interface for upload, download, delete, and list.
- `S3CoroutinesTemplate`: coroutine-first operations over `S3AsyncClient`.
- `S3Properties`: `@ConfigurationProperties("bluetape4k.aws.s3")`.
- `S3Resource`: Spring `Resource` wrapper for an S3 object.
- Presigned URL support.
- LocalStack + Testcontainers coverage.

The implementation must remain compatible with the repo rule that AWS service
SDK dependencies are `compileOnly`; consumers provide runtime service modules.

## Evidence

### Current Repo

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/AwsAutoConfiguration.kt`
  registers only `DefaultCredentialsProvider`.
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  currently registers `AwsAutoConfiguration`.
- `aws/src/main/kotlin/io/bluetape4k/aws/s3/S3ClientFactory.kt` already has
  `S3ClientFactory.Async.create(endpointOverride, region, credentialsProvider, ...)`
  and registers created clients in `ShutdownQueue`.
- `aws/src/main/kotlin/io/bluetape4k/aws/s3/S3AsyncClientCoroutinesExtensions.kt`
  already provides suspend wrappers for `getAsByteArray`, `getAsString`,
  `putAsByteArray`, `putAsString`, `putAsFile`, delete/move helpers, etc.
- `aws/src/test/kotlin/io/bluetape4k/aws/s3/AbstractS3Test.kt` uses
  LocalStack, `localStackServer.region()`, and `localStackServer.credentialsProvider`
  as the S3 integration-test source of truth.

### Ecosystem Patterns

- `bluetape4k-graph` Spring Boot starter uses `@AutoConfiguration`,
  `@EnableConfigurationProperties`, and separate properties classes.
- `bluetape4k-leader` Spring Boot starter uses `@ConditionalOnClass`,
  `@ConditionalOnMissingBean`, backend-specific auto-configurations, and Korean
  KDoc on public Spring APIs.
- `bluetape4k-projects` Spring Boot tests use `ApplicationContextRunner` with
  `AutoConfigurations.of(...)` to assert bean registration, property binding,
  and back-off behavior.

### Official Docs

- Spring Boot 4 discovers library auto-configurations from
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
  one fully-qualified auto-configuration class per line.
- Spring Boot auto-configurations should use conditions such as
  `@ConditionalOnClass` and `@ConditionalOnMissingBean`, and expose typed
  properties through `@ConfigurationProperties` plus
  `@EnableConfigurationProperties`.
- `ApplicationContextRunner` is the recommended lightweight surface for testing
  auto-configuration, property binding, and back-off behavior.
- AWS SDK Java v2 requires a region even when `endpointOverride` is used for a
  custom endpoint; `S3Presigner` builds presigned GET/PUT requests with
  `signatureDuration`.

## Goals

1. Auto-configure S3 beans when S3 SDK classes are present:
   - `S3Client` for Spring `Resource` sync bridge
   - `S3AsyncClient`
   - `S3Presigner`
   - `S3Operations` implemented by `S3CoroutinesTemplate`
2. Bind `bluetape4k.aws.s3.*` properties:
   - `enabled`
   - `region`
   - `endpoint-override`
   - `path-style-access-enabled`
   - `accelerate-mode-enabled`
   - `chunked-encoding-enabled`
   - presigned URL defaults.
3. Keep override boundaries explicit:
   - user-defined `S3AsyncClient` backs off client creation.
   - user-defined `S3Presigner` backs off presigner creation.
   - user-defined `S3Operations` backs off template creation.
4. Provide coroutine-first API for common S3 workflows:
   - upload bytes/string/resource/file/path.
   - download bytes/string/resource.
   - delete object.
   - bounded list page.
   - object listing as `Flow`.
   - presigned GET/PUT URL.
5. Provide `S3Resource` for Spring integration:
   - location metadata (`bucket`, `key`)
   - `exists()`, `contentLength()`, `lastModified()`, `getInputStream()`
   - no hidden upload semantics in `Resource`; upload remains in `S3Operations`.
6. Add LocalStack integration coverage and ApplicationContextRunner coverage.
7. Sync README.md and README.ko.md.

## Non-Goals

- Do not depend on awspring.
- Do not implement a Ktor server example; that is a separate examples issue.
- Do not implement S3 event notifications, S3 Select, directory buckets, object
  lock, ACL policy helpers, or TransferManager-backed high-level directory
  transfers in this issue.
- Do not expose synchronous operations as the primary API. A Spring-managed
  `S3Client` is allowed only for `S3Resource`, because Spring `Resource` is a
  synchronous abstraction and production `runBlocking` is not acceptable.
- Do not publish example modules from this issue.

## Proposed API

### Package Layout

```text
io.bluetape4k.aws.spring.s3
  S3AutoConfiguration
  S3Properties
  S3Operations
  S3CoroutinesTemplate
  S3Resource
  S3ObjectLocation
  S3ListPage
  S3PresignRequest
```

### Build Changes

`aws-spring-boot` must declare the S3 service SDK directly:

- `compileOnly(libs.aws2.s3)`
- `testImplementation(libs.aws2.s3)`

`S3Presigner` is provided by the AWS SDK v2 `s3` artifact in the current
version catalog; no separate `s3-presigner` alias exists.

### Properties

```kotlin
@ConfigurationProperties(prefix = "bluetape4k.aws.s3")
data class S3Properties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val pathStyleAccessEnabled: Boolean = false,
    val accelerateModeEnabled: Boolean = false,
    val chunkedEncodingEnabled: Boolean? = null,
    val presign: Presign = Presign(),
) {
    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "bluetape4k.aws.s3.region is required when endpoint-override is set."
        }
    }

    data class Presign(
        val duration: Duration = Duration.ofMinutes(15),
    )
}
```

Region resolution:

1. `bluetape4k.aws.s3.region`
2. AWS SDK default region provider chain by leaving the builder region unset
3. LocalStack tests set region explicitly through properties.

Endpoint override resolution:

- If `endpointOverride` is set, pass it to both `S3AsyncClient` and
  `S3Client` and `S3Presigner`.
- AWS SDK still needs a region for signing when endpoint override is used; the
  properties object rejects `endpointOverride` without `region`.

S3 client options:

- Build clients inline in `S3AutoConfiguration` instead of using
  `S3ClientFactory`, because Spring must own bean lifecycle without
  `ShutdownQueue` registration.
- Apply `S3Configuration.builder()` to sync and async clients:
  - `pathStyleAccessEnabled`
  - `accelerateModeEnabled`
  - `chunkedEncodingEnabled` when non-null.
- Resolve credentials from `ObjectProvider<AwsCredentialsProvider>` and fall
  back to `DefaultCredentialsProvider.builder().build()` if no bean is present.
  This keeps S3 auto-configuration robust even if applications override or omit
  `AwsAutoConfiguration`.
- Accept optional user-supplied `SdkHttpClient` / `SdkAsyncHttpClient` beans as
  client transport overrides. Do not add HTTP-client-specific properties in
  this issue.

### Auto-Configuration Shape

Use top-level auto-configuration classes listed in
`AutoConfiguration.imports`; do not import them through component scanning.

```kotlin
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnClass(name = [
    "software.amazon.awssdk.http.SdkHttpClient",
    "software.amazon.awssdk.http.async.SdkAsyncHttpClient",
    "software.amazon.awssdk.services.s3.S3Client",
    "software.amazon.awssdk.services.s3.S3AsyncClient",
    "software.amazon.awssdk.services.s3.presigner.S3Presigner",
])
@ConditionalOnProperty(prefix = "bluetape4k.aws.s3", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(S3Properties::class)
class S3AutoConfiguration
```

Each bean method returning compileOnly S3 SDK types must also avoid eager
class loading through class-level string conditions. User override boundaries:

- `@ConditionalOnMissingBean(S3Client::class)`
- `@ConditionalOnMissingBean(S3AsyncClient::class)`
- `@ConditionalOnMissingBean(S3Presigner::class)`
- `@ConditionalOnMissingBean(S3Operations::class)`

### S3Operations

```kotlin
interface S3Operations {
    suspend fun existsBucket(bucket: String): Boolean
    suspend fun upload(bucket: String, key: String, bytes: ByteArray, contentType: String? = null): PutObjectResponse
    suspend fun upload(bucket: String, key: String, text: String, contentType: String = "text/plain; charset=utf-8"): PutObjectResponse
    suspend fun downloadBytes(bucket: String, key: String): ByteArray
    suspend fun downloadText(bucket: String, key: String, charset: Charset = Charsets.UTF_8): String
    suspend fun delete(bucket: String, key: String): DeleteObjectResponse
    suspend fun listPage(bucket: String, prefix: String? = null, maxKeys: Int = 1000, continuationToken: String? = null): S3ListPage
    fun listFlow(bucket: String, prefix: String? = null, pageSize: Int = 1000): Flow<S3Object>
    fun resource(bucket: String, key: String): S3Resource
    fun presignGet(bucket: String, key: String, duration: Duration? = null): URL
    fun presignPut(bucket: String, key: String, duration: Duration? = null, contentType: String? = null): URL
}
```

`S3CoroutinesTemplate` should delegate to existing `aws` module extension
functions where possible. Any direct AWS SDK call must remain non-blocking or
await a `CompletableFuture`. It must rethrow `CancellationException`; do not
wrap suspend bodies in broad `runCatching`.

`presignGet` and `presignPut` are synchronous because signing is local
computation. Duration precedence is per-call duration, then
`S3Properties.presign.duration`. If `presignPut` signs `Content-Type`, callers
must send the same header when using the URL.

### S3Resource

`S3Resource` should extend `AbstractResource`, not implement an upload-capable
resource. It should use the Spring-managed sync `S3Client` because `Resource`
is synchronous by contract and production `runBlocking` is not acceptable. It
should:

- return `s3://bucket/key` from `getDescription()`;
- call `headObject` for `exists`, `contentLength`, and `lastModified`;
- call `getObject(..., ResponseTransformer.toInputStream())` from
  `getInputStream()` to avoid materializing the whole object;
- stay read-only; upload remains in `S3Operations`.

## Design Options

### Option A - Minimal Auto-Configuration + Coroutine Template (Selected)

Create Spring-managed `S3Client`, `S3AsyncClient`, `S3Presigner`, and
`S3CoroutinesTemplate`. Use existing `aws` coroutine extensions for common
operations and direct SDK calls for `headObject`, paginated list, and presigner
gaps.

Pros:
- Matches issue #1 exactly.
- Reuses existing `aws` module behavior.
- Keeps Spring integration thin and testable.
- Avoids awspring; sync client is limited to the Spring `Resource` bridge.

Cons:
- `S3Resource` introduces one sync client bean, but only for synchronous Spring
  `Resource` calls.
- Presigner is a second client-like object with its own lifecycle.

### Option B - Resource-First API

Build a richer `S3Resource` and make `S3Operations` mostly a factory for
resources.

Pros:
- Familiar to Spring users.
- Similar to awspring's public surface.

Cons:
- Upload semantics through `Resource` are awkward and easy to make blocking.
- Pushes coroutine-first API behind a sync abstraction.

### Option C - TransferManager-Centric API

Auto-configure `S3TransferManager` and implement operations through transfer
manager APIs.

Pros:
- Stronger path for large files and directory transfer.

Cons:
- Larger dependency and lifecycle surface.
- Not required by issue #1's common upload/download/list/delete API.
- Harder to keep the first Spring Boot S3 integration small.

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Missing AWS S3 classes at runtime | Spring context fails unexpectedly | Guard with string-based `@ConditionalOnClass(name = [...])` and keep S3 SDK as compileOnly. |
| Endpoint override without region | Presigner/client signing failure | Reject this property combination and test it. |
| `S3Resource` blocks event-loop threads | Hidden latency or deadlock | Use sync `S3Client` only in `Resource`; document coroutine template as primary API. |
| User custom beans are overwritten | App-specific SDK settings lost | Put `@ConditionalOnMissingBean` on each bean. |
| Client/presigner lifecycle conflict | Double close or leaked resources | Build clients inline without `ShutdownQueue`; Spring owns `AutoCloseable` bean lifecycle. |
| Listing large buckets | Heap pressure | Provide `listPage` and `listFlow`; avoid an unbounded flat list API. |
| Presigned PUT header mismatch | Consumer uploads fail with signature mismatch | Document that signed headers such as `Content-Type` must match the upload request. |
| Property metadata missing | Poor Boot UX | Use `@ConfigurationProperties` and annotation processor already configured. |
| LocalStack tests become slow/flaky | CI instability | Split lightweight ApplicationContextRunner tests from a focused LocalStack round-trip test. |

## Acceptance Criteria

- `S3AutoConfiguration` appears in `AutoConfiguration.imports`.
- `ApplicationContextRunner` tests prove:
  - default properties bind;
  - disabled property backs off;
  - `S3AsyncClient`, `S3Presigner`, and `S3Operations` register by default;
  - custom beans are not replaced;
  - endpoint/region/path-style properties are applied.
- Disabled property backs off all S3 beans.
- Endpoint override without region fails fast with a clear exception.
- LocalStack integration test proves:
  - upload bytes/text;
  - download bytes/text;
  - `listPage` by prefix;
  - `listFlow` by prefix;
  - delete;
  - `S3Resource.exists/contentLength/getInputStream`;
  - presigned GET/PUT URLs are generated with expected method and expiry.
- Public APIs have Korean KDoc.
- README.md and README.ko.md describe properties, beans, and sample usage.
- `./gradlew :aws-spring-boot:compileKotlin :aws-spring-boot:test --no-daemon`
  passes.
- `./gradlew :aws-spring-boot:detekt --no-daemon` passes if the task is
  available; otherwise root `./gradlew detekt --no-daemon` passes.
- README.md and README.ko.md are updated together.
- `git diff --check` passes.

## Open Questions

Resolved locally:

- `S3Resource` may use a sync `S3Client` because Spring `Resource` is
  synchronous; `runBlocking` is rejected.
- Listing should not return an unbounded list; use page and flow APIs.
- Presign uses one default duration for v1; per-method defaults can be added
  later if a real use case appears.
- Spring owns auto-configured client lifecycle; do not use `ShutdownQueue` for
  these beans.
- HTTP client override is via optional user `SdkHttpClient` /
  `SdkAsyncHttpClient` beans, not new properties.
- #1 can proceed from `origin/develop`; #9 is independent and merge-waiting.

No user escalation is required before implementation planning.

## Claude Code Opus Advisor

Artifact:
`/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/1-spring-boot-s3/.omx/artifacts/ask-claude-aws-spring-boot-s3-spec-20260510-180655.md`

| Severity | Finding | Decision | Follow-up |
|---|---|---|---|
| Blocking | `runBlocking` in `S3Resource` is unsafe production code. | Accepted | Use sync `S3Client` for `Resource`; no `runBlocking`. |
| Blocking | CompileOnly S3 classes need string-based class conditions. | Accepted | Spec now requires `@ConditionalOnClass(name = [...])`. |
| Blocking | S3 SDK dependency not explicit in `aws-spring-boot`. | Partially accepted | Add `compileOnly/testImplementation(libs.aws2.s3)`; reject separate `s3-presigner` because current AWS SDK exposes `S3Presigner` from `s3`. |
| Blocking | Credentials/region/HTTP client wiring unspecified. | Accepted | Add after-order, credentials provider, optional SDK HTTP client bean override, and endpoint+region invariant. |
| Blocking | `pathStyleAccessEnabled` and `chunkedEncodingEnabled` plumbing missing. | Accepted | Build `S3Configuration` inline in auto-config. |
| Blocking | `ShutdownQueue` and Spring lifecycle can conflict. | Accepted | Auto-config builds clients inline; Spring owns lifecycle. |
| Blocking | `list` API was unbounded. | Accepted | Replace with `listPage` and `listFlow`. |

## Step Checklist Completion

| Item | Status | Notes |
|---|---|---|
| Architecture pre-design ran | Done | Options A/B/C compared; Option A selected. |
| Step 1-R research incorporated | Done | Spring Boot 4 docs, AWS SDK docs, current repo and ecosystem patterns included. |
| Current behavior cites source evidence | Done | Existing auto-config, imports, S3 factory/extensions, LocalStack tests cited. |
| Spec path inside feature worktree | Done | This file is under `.worktrees/feat/1-spring-boot-s3`. |
| Risks/failure modes included | Done | See risk table. |
| Approach comparison included | Done | Options A/B/C. |
| Open questions resolved | Done | No escalation needed. |
| Draft task list returned | Done | Acceptance criteria define implementation tasks; plan will expand them. |
