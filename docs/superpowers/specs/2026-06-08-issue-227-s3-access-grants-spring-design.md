# Issue #227 Spring S3 Access Grants Design

## Context

Issue #227 is the 0.4.0 follow-up for the S3 Access Grants slice that was
deferred from issue #192. The 0.3.0 S3 Spring Boot work intentionally delivered
S3 config reload and KMS-backed client-side encryption only, because Access
Grants introduces a separate optional AWS SDK control-plane surface.

The current AWS SDK for Java v2 surface exposes S3 Access Grants operations on
`software.amazon.awssdk.services.s3control.S3ControlClient` and
`S3ControlAsyncClient`. The repository currently declares `aws2-s3` and
`aws2-s3-transfer-manager`, but it does not yet declare an `aws2-s3control`
catalog alias.

## Current Evidence

- GitHub issue #227 was updated on 2026-06-08 with the `s3control` direction.
- AWS SDK Java API reference lists Access Grants methods such as
  `createAccessGrant`, `createAccessGrantsInstance`, `getDataAccess`, and
  `listCallerAccessGrants` on `S3ControlClient`.
- The AWS SDK BOM 2.46.0 includes the `software.amazon.awssdk:s3control`
  artifact.
- `./gradlew -q :bluetape4k-aws-spring-boot:dependencyInsight --dependency s3control --configuration compileClasspath`
  currently reports no matching dependency.
- CodeGraph is not initialized for this worktree, so source discovery used GNO,
  Gradle dependency inspection, official AWS SDK docs, and direct source reads.

## Goals

- Add optional Spring Boot auto-configuration for S3 Access Grants.
- Keep basic S3 users free from the `s3control` runtime dependency.
- Reuse existing AWS core defaults and client customizer infrastructure.
- Provide a coroutine-first operations/template surface over
  `S3ControlAsyncClient`.
- Document user-facing properties and usage in `README.md` and `README.ko.md`.

## Non-Goals

- Do not implement Ktor Access Grants helpers; issue #228 owns that.
- Do not implement S3 Vector support; issue #229 owns that.
- Do not run real AWS Access Grants integration tests requiring account-level
  IAM Identity Center or Access Grants resources.
- Do not fold Access Grants into `S3Operations`; it is a control-plane feature
  with different lifecycle and permissions.

## Proposed API

Add a new package under `io.bluetape4k.aws.spring.s3.accessgrants`:

- `S3AccessGrantsProperties`
- `S3AccessGrantsAutoConfiguration`
- `S3AccessGrantsOperations`
- `S3AccessGrantsCoroutinesTemplate`

The property prefix is:

```properties
bluetape4k.aws.s3.access-grants
```

Initial properties:

- `enabled`: default `false`.
- `region`: optional service-specific override.
- `endpointOverride`: optional service-specific endpoint override.

The template should expose a minimal stable operation set:

- `getDataAccess(...)`
- `listCallerAccessGrants(...)`
- `listAccessGrants(...)`
- `listAccessGrantsInstances(...)`
- `listAccessGrantsLocations(...)`

Administrative create/delete/update APIs remain accessible through caller-owned
`S3ControlClient`/`S3ControlAsyncClient` beans. This keeps the first Spring
surface focused on application access workflows rather than account bootstrap.

## Auto-Configuration Contract

Register `S3AccessGrantsAutoConfiguration` after `AwsAutoConfiguration` and
after `S3AutoConfiguration`.

Use string class guards:

```kotlin
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.services.s3control.S3ControlClient",
        "software.amazon.awssdk.services.s3control.S3ControlAsyncClient",
    ]
)
```

Use property guards:

- `bluetape4k.aws.s3.enabled=true` or missing.
- `bluetape4k.aws.s3.access-grants.enabled=true`.

Beans:

- `S3ControlClient`, `destroyMethod = "close"`, backs off on caller bean.
- `S3ControlAsyncClient`, `destroyMethod = "close"`, backs off on caller bean.
- `S3AccessGrantsOperations`, backs off on caller bean.

Client builders must reuse:

- `AwsProperties.resolveClientDefaults(...)`
- `applyAwsDefaults(...)`
- `applyGlobalCustomizers("s3control", ...)`
- `applyServiceCustomizers(...)`

## Dependency Contract

Add to `gradle/libs.versions.toml`:

```toml
aws2-s3control = { module = "software.amazon.awssdk:s3control", version.ref = "aws2" }
```

Add to `aws-spring-boot/build.gradle.kts`:

- `compileOnly(libs.aws2.s3control)`
- `testImplementation(libs.aws2.s3control)`

Do not add `api` or `runtimeOnly` for `s3control`.

## Testing Strategy

Use `ApplicationContextRunner`, MockK, and bluetape4k assertions.

Required tests:

- Access Grants auto-configuration is disabled by default.
- Enabling Access Grants registers sync/async S3 Control clients and operations.
- Missing `s3control` classes back off cleanly via `FilteredClassLoader`.
- Caller-provided `S3ControlClient` / `S3ControlAsyncClient` beans are reused.
- Caller-provided `S3AccessGrantsOperations` backs off the template.
- Global and service-specific client customizers apply with service name
  `s3control`.
- Endpoint override without region fails through the shared AWS defaults rule.
- Template delegates async SDK calls and awaits completion.

Do not add emulator tests for Access Grants in this issue; no local emulator in
this repository currently proves the account-level Access Grants workflow.

## Documentation

Update:

- Root `README.md`
- Root `README.ko.md`

Documentation should state that Access Grants is opt-in, requires the caller to
add `software.amazon.awssdk:s3control`, and is separate from basic S3 object
operations.

## Risks

- `S3Control` covers many S3 control-plane APIs beyond Access Grants. The
  public template should keep the first surface narrow to avoid accidentally
  committing broad S3 Control compatibility.
- Access Grants setup often needs account-level permissions and IAM Identity
  Center association. Unit/slice tests can prove wiring, but real AWS behavior
  remains out of scope.
- Adding the catalog alias locally duplicates a centrally governed AWS artifact
  alias until `bluetape4k-dependencies` grows a generated alias. This is
  acceptable because the version remains the existing `aws2` line.

## DoD

- Spec review reports `P0=0`, `P1=0`.
- Plan review reports `P0=0`, `P1=0`.
- Compile and targeted tests pass for `:bluetape4k-aws-spring-boot`.
- README locale set is updated.
- A concise lesson is added under `docs/lessons/`.
- Final code review reports `P0=0`, `P1=0`.
