# aws-spring-boot S3 Auto-Configuration Plan

Date: 2026-05-10
Spec: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/1-spring-boot-s3/docs/superpowers/specs/2026-05-10-aws-spring-boot-s3-design.md`
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/1

## Execution Rules

- Work inside
  `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/1-spring-boot-s3`.
- Keep #1 independent from PR #28 (`aws #9`); base remains `origin/develop`.
- Do not use awspring.
- Keep AWS service SDK dependencies `compileOnly` for main code and explicit
  test dependencies for verification.
- Public APIs get Korean KDoc.
- README.md and README.ko.md must stay in sync.

## Plan

### 1. Build And Auto-Configuration Registration

1. Update `aws-spring-boot/build.gradle.kts`.
   - Add `compileOnly(libs.aws2.s3)`.
   - Add `testImplementation(libs.aws2.s3)`.
   - Keep existing `testImplementation` extension from `compileOnly`.
   - Verify `annotationProcessor(libs.spring.boot.configuration.processor)` is
     present for configuration metadata; add it if missing.
2. Add S3 auto-configuration class to
   `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
3. Keep `AwsAutoConfiguration` unchanged except for ordering references from
   S3 auto-config.

Verification:
- `./gradlew :aws-spring-boot:compileKotlin --no-daemon`

### 2. Properties And Model Types

Create package `io.bluetape4k.aws.spring.s3`.

Files:
- `S3Properties.kt`
- `S3ObjectLocation.kt`
- `S3ListPage.kt`
- `S3PresignRequest.kt` if request options need a named model; otherwise use
  method parameters and skip this file.

Tasks:
1. Implement `S3Properties` with prefix `bluetape4k.aws.s3`.
2. Add endpoint+region invariant in `init`.
3. Add nested `Presign` properties with default duration 15 minutes.
4. Add simple location/page model types with Korean KDoc.
5. Prefer immutable data classes with defaults.

Verification:
- ApplicationContextRunner property-binding tests in Step 5.

### 3. S3 Auto-Configuration

Create `S3AutoConfiguration.kt`.

Required annotations:
- `@AutoConfiguration(after = [AwsAutoConfiguration::class])`
- `@ConditionalOnClass(name = [...])` with S3 and AWS HTTP client FQCN strings
- `@ConditionalOnProperty(prefix = "bluetape4k.aws.s3", name = ["enabled"], havingValue = "true", matchIfMissing = true)`
- `@EnableConfigurationProperties(S3Properties::class)`

Bean methods:
1. `s3Client(...)`: Spring-managed `S3Client` for `S3Resource`.
2. `s3AsyncClient(...)`: primary async S3 client.
3. `s3Presigner(...)`: presigned URL support.
4. `s3Operations(s3AsyncClient, s3Client, s3Presigner, properties)`:
   `S3CoroutinesTemplate`.

Builder rules:
- Build clients inline with AWS SDK builders, not `S3ClientFactory`, to avoid
  `ShutdownQueue` ownership.
- Apply `Region.of(properties.region)` only when region is non-null.
- Apply `endpointOverride` only when non-null.
- Resolve `AwsCredentialsProvider` through
  `ObjectProvider<AwsCredentialsProvider>` and fall back to
  `DefaultCredentialsProvider.builder().build()` when no bean exists. Do not
  assume `AwsAutoConfiguration` is present.
- Accept `ObjectProvider<SdkHttpClient>` and `ObjectProvider<SdkAsyncHttpClient>`
  overrides.
- Build `S3Configuration` and apply path-style, accelerate, and optional
  chunked encoding flags.

Back-off rules:
- `@ConditionalOnMissingBean` on each bean method.

### 4. S3Operations And Template

Create:
- `S3Operations.kt`
- `S3CoroutinesTemplate.kt`

Implement:
1. `existsBucket`.
2. `upload` for bytes and text.
3. `downloadBytes` and `downloadText`.
4. `delete`.
5. `listPage`.
6. `listFlow`.
7. `resource`.
8. `presignGet`.
9. `presignPut`.

Implementation rules:
- Constructor inputs are `S3AsyncClient`, sync `S3Client`, `S3Presigner`, and
  `S3Properties`.
- Reuse existing `aws` module coroutine extensions where they fit.
- Use `CompletableFuture.await()` for direct async SDK calls.
- Do not wrap suspend calls in broad `runCatching`.
- Re-throw `CancellationException` if any catch boundary is introduced.
- `listFlow` should use continuation tokens and `flow { emit(...) }`.
- `presignPut` KDoc must state that signed headers, including `Content-Type`,
  must match the actual upload request.

### 5. S3Resource

Create `S3Resource.kt`.

Implementation:
- Extend `AbstractResource`.
- Store `S3Client` and `S3ObjectLocation`.
- `getDescription()` returns `s3://bucket/key`.
- `exists()`, `contentLength()`, `lastModified()` call `headObject`.
- `getInputStream()` calls sync `getObject` with
  `ResponseTransformer.toInputStream()`.
- Translate missing object/bucket errors to normal Spring `Resource` semantics
  where appropriate:
  - `exists()` returns false for 404/NoSuchKey/NoSuchBucket.
  - `getInputStream()` lets the SDK exception propagate.

### 6. Tests

Extend `AwsAutoConfigurationTest`; do not remove existing credentials-provider
coverage.

ApplicationContextRunner tests:
1. `AwsAutoConfiguration` still registers default credentials provider.
2. S3 auto-config registers `S3Client`, `S3AsyncClient`, `S3Presigner`,
   `S3Operations`.
3. `enabled=false` registers no S3 beans.
4. User-provided `S3AsyncClient`, `S3Client`, `S3Presigner`, and `S3Operations`
   back off defaults.
5. `endpoint-override` without `region` fails fast.
6. Region/endpoint/path-style/chunked/accelerate properties bind.
7. Presign duration default binds.
8. S3 auto-config falls back to `DefaultCredentialsProvider` when no
   credentials provider bean exists.

LocalStack integration tests:
1. Add `AbstractS3SpringBootTest` under `aws-spring-boot/src/test/kotlin`.
   - Use `LocalStackServer.Launcher.getLocalStack("s3")`.
   - Provide helper properties for endpoint, region, and static credentials.
   - Do not depend on `:aws` test sources.
2. Bind region and endpoint override through properties.
3. `existsBucket`.
4. Upload and download bytes/text.
5. List page and list flow by prefix.
6. Delete and verify absence.
7. `S3Resource.exists`, `contentLength`, and `getInputStream`.
8. Presigned GET/PUT URL generation shape.

Test hygiene:
- Keep LocalStack tests in a separate class so context-runner failures are fast.
- Use unique bucket/key names.
- Clean up created objects/buckets.

### 7. Documentation

Update:
- `aws-spring-boot/README.md`
- `aws-spring-boot/README.ko.md`
- root `README.md`
- root `README.ko.md`

Document:
- dependency requirement for consumers: add AWS SDK `s3` runtime dependency.
- auto-configured beans.
- `bluetape4k.aws.s3.*` properties.
- coroutine template usage.
- `S3Resource` read-only sync bridge.
- presigned PUT content-type/header contract.

### 8. Verification

Run in order:

1. `./gradlew :aws-spring-boot:compileKotlin --no-daemon`
2. `./gradlew :aws-spring-boot:test --no-daemon`
3. `./gradlew :aws-spring-boot:koverHtmlReport --no-daemon`
4. `./gradlew :aws-spring-boot:detekt --no-daemon` if task exists; otherwise
   `./gradlew detekt --no-daemon`
5. `./gradlew build -x test --parallel --no-daemon`
6. `rg 'runBlocking|Thread\\.sleep|GlobalScope' aws-spring-boot/src/main/kotlin`
   should return no production hits.
7. `git diff --check`

If any test fails, fix before PR. If LocalStack is unavailable, capture the
failure and run the next-best ApplicationContextRunner coverage; do not claim
LocalStack verification passed.

### 9. Commit And PR

1. Commit spec/plan first after advisor review.
2. Commit implementation separately with Lore trailers and
   `Co-authored-by: OmX <omx@oh-my-codex.dev>`.
3. Push `feat/1-spring-boot-s3`.
4. Create PR with title `[feat] Add Spring Boot S3 auto-configuration`.
5. PR body in Korean; include `Closes #1`.

## Step Checklist Completion

| Item | Status | Notes |
|---|---|---|
| Missing implementation tasks covered | Done | Build, properties, auto-config, operations, resource, tests, docs, verification. |
| Ordering is dependency-safe | Done | Build/properties before auto-config/template/resource/tests/docs. |
| Tests and diagnostics included | Done | ContextRunner, LocalStack, compile, test, detekt, build, diff check. |
| Dependency/API risks included | Done | S3 SDK compileOnly/test deps, string class guards, Spring lifecycle, S3Configuration. |
| Complexity labels reasonable | Done | Type A full design retained. |

## Claude Code Opus Advisor

Artifact:
`/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/1-spring-boot-s3/.omx/artifacts/ask-claude-aws-spring-boot-s3-plan-20260510-181111.md`

| Severity | Finding | Decision | Follow-up |
|---|---|---|---|
| Blocking | Credentials provider injection contract was underspecified. | Accepted | Use `ObjectProvider<AwsCredentialsProvider>` with default fallback. |
| Blocking | `S3Operations` needs sync `S3Client` for `resource()`. | Accepted | Template constructor and bean signature include sync client. |
| Blocking | LocalStack test base cannot reuse `:aws` test sources. | Accepted | Add `AbstractS3SpringBootTest` in this module. |
| Blocking | Configuration processor verification missing. | Accepted | Step 1 now verifies existing processor dependency. |
| Blocking | AWS HTTP client class guard missing. | Accepted | Include `SdkHttpClient` and `SdkAsyncHttpClient` FQCNs. |
| Non-blocking | Existing credentials provider test should remain. | Accepted | Extend rather than replace `AwsAutoConfigurationTest`. |
| Non-blocking | Commit-format comment conflicted with active AGENTS Lore protocol. | Rejected | Use Lore commit trailers required by AGENTS.md. |
