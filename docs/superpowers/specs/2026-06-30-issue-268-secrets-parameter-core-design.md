# Issue #268 Design - Core Secrets Manager and Parameter Store Wrappers

## Context

Issue #268 targets milestone `0.5.0` and asks for framework-neutral Secrets
Manager and SSM Parameter Store helpers in the core SDK modules:

- `bluetape4k-aws-java`
- `bluetape4k-aws-kotlin`

The root README already describes Secrets Manager and Parameter Store as service
coverage, but the core module rows do not expose first-class helpers for direct
SDK users. Spring Boot already has Environment source support, and
`bluetape4k-aws-exposed` has source descriptors. This work must not move Spring
Environment loading into core modules.

## Evidence From Current Code

- `aws-java/build.gradle.kts` already has catalog aliases for Java SDK
  `aws2-secretsmanager` and `aws2-ssm`, but they are not declared in
  `aws-java`.
- `aws-kotlin/build.gradle.kts` has AWS Kotlin SDK service dependencies for
  DynamoDB, S3, SES, SNS, SQS, KMS, CloudWatch, Kinesis, and STS. It does not
  yet declare Secrets Manager or SSM.
- Maven Central has `aws.sdk.kotlin:secretsmanager:1.6.102` and
  `aws.sdk.kotlin:ssm:1.6.102`, with JVM variant artifacts present.
- `javap` against the AWS Kotlin SDK JVM variants confirmed:
  `SecretsManagerClient.getSecretValue`, `listSecrets`, `putSecretValue`,
  `createSecret`, `describeSecret`; and `SsmClient.getParameter`,
  `getParameters`, `getParametersByPath`, `putParameter`,
  `describeParameters`.
- `javap` against AWS Java SDK v2 `2.46.17` confirmed equivalent sync and async
  client methods for `SecretsManagerClient`, `SecretsManagerAsyncClient`,
  `SsmClient`, and `SsmAsyncClient`.
- Existing Java module patterns use:
  - service client factories such as `snsClientOf(...)`
  - request builders under `.../model`
  - async `CompletableFuture` helpers plus coroutine `.await()` wrappers
  - `compileOnly` service SDK dependencies and `testImplementation` for touched
    services
- Existing Kotlin module patterns use:
  - `xxxClientOf(...)` and `withXxxClient { }`
  - native suspend extension functions
  - request DSL builders with `requireNotBlank`
  - `useSafe` for short-lived client lifecycle
- Dependency work is explicit:
  - Java aliases `libs.aws2.secretsmanager` and `libs.aws2.ssm` already exist
    and must be added to `aws-java` as `compileOnly` plus `testImplementation`.
  - Kotlin aliases `libs.aws.kotlin.secretsmanager` and
    `libs.aws.kotlin.ssm` must be added to `gradle/libs.versions.toml` and then
    consumed by `aws-kotlin` as `compileOnly` plus `testImplementation`.
- CodeGraph was built for this worktree on branch
  `feat/aws-secrets-parameter-core`: 741 files, 4,754 nodes, 38,526 edges.

## Goals

1. Add low-level Java SDK v2 helpers for Secrets Manager and SSM Parameter Store.
2. Add low-level AWS Kotlin SDK suspend helpers for Secrets Manager and SSM.
3. Keep service dependencies optional for consumers by using `compileOnly`.
4. Provide focused request builders and convenience operations for common
   get/list/put flows.
5. Avoid secret leakage through value object `toString()` or error messages.
6. Update README module descriptions and service coverage chart in both
   English and Korean.

## Non-Goals

- Do not move Spring Environment post-processors into core modules.
- Do not add Spring Boot auto-configuration.
- Do not build a caching or refresh layer.
- Do not add a new shared configuration abstraction across Spring, Exposed, and
  core modules.
- Do not add new runtime service dependencies to consumers by using `api` for
  AWS service modules.
- Do not add `SecretBinary` convenience helpers in this PR. Binary payloads
  remain on raw SDK calls until a redacted binary value type is designed.
- Do not add all-pages collection helpers in this PR. Single-page operations
  must expose `nextToken` / `maxResults` through the SDK request and response
  types.

## Approach Options

### Option A - Request Builders Only

Add only `GetSecretValueRequest`, `GetParameterRequest`, and similar DSL
builders.

Rejected because issue #268 asks for first-class wrappers, not just request
factories. Direct SDK users would still lack common get/list/put operations.

### Option B - Focused Core Helpers Per SDK

Add service client factories, request builders, sync/async/suspend operations
in `aws-java`, and native suspend operations in `aws-kotlin`.

Selected because it matches the existing SQS/SNS/Kinesis shape, keeps Spring
behavior out of core, and gives direct SDK users useful APIs without large
architecture changes.

### Option C - Promote Spring Environment Source Logic To Core

Move flattening, source descriptors, and Environment loading concepts into core.

Rejected because it duplicates #180-related Spring behavior and would make the
core modules own Spring-style property source semantics.

## Selected Design

### Java SDK Module

Add packages:

- `io.bluetape4k.aws.secretsmanager`
- `io.bluetape4k.aws.secretsmanager.model`
- `io.bluetape4k.aws.ssm`
- `io.bluetape4k.aws.ssm.model`

Java module APIs:

- `secretsManagerClient { }`, `secretsManagerClientOf(...)`
- `secretsManagerAsyncClient { }`, `secretsManagerAsyncClientOf(...)`
- `SsmClient` / `SsmAsyncClient` equivalents
- request builders for:
  - Secrets Manager: get, batch get, list, describe, create, and put secret
    value
  - SSM: get parameter, get parameters, get parameters by path, put parameter,
    and describe parameters
- sync extension functions for common calls
- async extension functions returning `CompletableFuture`
- coroutine extension functions on async clients using `.await()`
- no delete convenience wrappers; destructive delete calls remain on raw SDK
  clients so they stay explicit at the call site
- Java clients created by helper factories follow existing Java module
  ownership: helpers register created clients with `ShutdownQueue`; callers may
  still close them explicitly when they own a short-lived client.

Secret string returns should be wrapped in a redacted value object:

- `AwsSecretValue` with `reveal()`
- `toString()` returns `"****"`
- blank values are rejected

The wrapper is intentionally small and framework-neutral. It mirrors the RDS
IAM token redaction rule but does not reuse that type because Secrets Manager
and Parameter Store are not authentication tokens.

Write-path helpers that accept secret-bearing values must also accept redacted
wrappers, not raw `String` values:

- `createSecret` / `putSecretValue` convenience helpers accept `AwsSecretValue`
  for `SecretString`.
- `putParameter` helpers accept `AwsSecretValue` for `SecureString` values and
  plain `String` only for explicitly non-secret `String` / `StringList`
  parameter types.
- Raw `SecretBinary` convenience helpers are out of scope.
- Helpers reveal the raw value only inside AWS SDK request construction.

### AWS Kotlin SDK Module

Add packages:

- `io.bluetape4k.aws.kotlin.secretsmanager`
- `io.bluetape4k.aws.kotlin.secretsmanager.model`
- `io.bluetape4k.aws.kotlin.ssm`
- `io.bluetape4k.aws.kotlin.ssm.model`

Kotlin module APIs:

- `secretsManagerClientOf(...)`, `withSecretsManagerClient { }`
- `ssmClientOf(...)`, `withSsmClient { }`
- Secrets Manager native suspend helpers:
  - `getSecretString`
  - `listSecrets`
  - `describeSecret`
  - `createSecret`
  - `putSecretValue`
  - `batchGetSecretValues`
- SSM native suspend helpers:
  - `getParameter`
  - `getSecureParameter`
  - `getParameters`
  - `getParametersByPath`
  - `describeParameters`
  - `putParameter`
- request builder helpers that match the generated AWS Kotlin SDK builder shape
- a redacted `AwsSecretValue` wrapper inside `aws-kotlin`
- no delete convenience wrappers; destructive delete calls remain on raw SDK
  clients so they stay explicit at the call site
- Kotlin `xxxClientOf(...)` helpers return caller-owned clients; callers close
  them explicitly. Kotlin `withXxxClient { }` helpers own and close the client
  through `useSafe`.

The Kotlin module should not depend on `aws-java`. Its wrappers should be
native to the AWS Kotlin SDK types. Both modules may use the same public type
name `AwsSecretValue` in their own package because the module/package prefix
already distinguishes Java SDK and AWS Kotlin SDK APIs.

## Value Semantics

- Secrets Manager `getSecretString` helpers return `AwsSecretValue`.
- If Secrets Manager returns `SecretBinary` and no `SecretString`, string helper
  functions fail with an `IllegalStateException` that includes only the
  operation and secret id, not payload material.
- Raw `GetSecretValueResponse` helpers remain available for callers that need
  `SecretBinary`.
- SSM `getSecureParameter` helpers return redacted secret values and set
  `withDecryption = true` explicitly.
- SSM `getParameter` helpers for non-secret values return plain SDK responses or
  `String` values and default `withDecryption = false`.
- SSM `StringList` helpers return plain list/string values only when the caller
  chooses non-secret helpers.
- Missing secret or parameter SDK exceptions propagate; helpers must not
  normalize missing resources into empty strings or success.
- Redacted value objects are regular classes, not `data class` or value class
  declarations. They have private raw values, `reveal()`, redacted `toString`,
  redacted constant `hashCode`, constant-time equality where practical,
  `Serializable` with `readResolve` when serializable, companion factories, and
  explicit serialization-boundary warnings.
- Java and Kotlin modules both provide package-local top-level factories named
  `awsSecretValueOf(...)` plus companion `of(...)` / `invoke(...)` factories.
- Redacted wrappers wrap only returned secret/string values, not list metadata
  entries.

## Pagination And Batch Contract

- List/describe/path helpers return a single SDK page by default and expose
  token/max-results fields through the request builders.
- This PR does not add hidden all-pages eager collection helpers.
- If an all-pages helper is added later, its name must include `All` or `Flow`,
  it must be cold/lazy, and it must avoid unbounded eager collection.
- Secrets Manager batch get helpers reject more than 20 secret ids.
- SSM get-parameters helpers reject more than 10 names.
- Batch/collection helpers must preserve partial failure information. Simplified
  helpers either return raw SDK responses with `errors` / `invalidParameters`,
  or fail explicitly; they must not return only found values as full success.
- No helper may use unbounded `async`, unbounded `CompletableFuture.allOf`, or
  implicit parallel fan-out for batch splitting in this PR.
- Non-null token parameters are rejected when blank. `maxResults` stays
  caller-controlled without implicit loops or clamping unless the SDK/service
  contract requires validation.

## Validation And Error Handling

- Use bluetape4k `requireNotBlank` and collection validation helpers for caller
  input.
- Re-throw `CancellationException` before broad exception handling in suspend
  code.
- Do not catch and wrap all AWS SDK exceptions in convenience operations unless
  there is a redaction reason. Let service exceptions propagate so callers can
  handle AWS-specific error types.
- Helpers do not add custom retry, backoff, timeout, or deadline loops. They
  rely on SDK client/request override configuration, preserve coroutine
  cancellation, and do not retry write helpers outside SDK policy.
- Redaction-safe wrappers must not leak raw values through `toString()`.
- Tests must assert missing-resource exceptions are not normalized to success.
  The SDK service exception type should propagate when callers request a missing
  secret/parameter.
- Helpers must not log secret/parameter values, `SecretString`, `SecretBinary`,
  or SSM `value`. Safe diagnostics may include operation name, secret id/ARN,
  parameter path/name, AWS request id when available, and exception type.
- Documentation examples must not print or log revealed values. `reveal()` may
  appear only at an explicit consumer boundary.

## Testing Strategy

- Unit tests for request builders and validation.
- MockK tests for Java async coroutine adapters and Kotlin suspend extensions.
- Redaction tests for secret value wrappers.
- Client factory tests that create and close clients with region/endpoint
  configuration without contacting AWS.
- Client factory tests must use dummy credentials, explicit local endpoints, and
  explicit regions. Unit-scope tests must not rely on production AWS endpoints
  or default credential-chain resolution.
- Tests cover sentinel raw secret values and assert that helper/model
  `toString()`, exception messages, and diagnostics do not contain the sentinel.
- Tests cover SSM `withDecryption` mapping for secure and non-secure reads.
- Tests cover Secrets Manager batch limit `21` rejection and SSM
  get-parameters limit `11` rejection.
- Tests cover partial batch failure preservation for Secrets Manager batch get
  and SSM get parameters.
- Tests prove `withSecretsManagerClient` / `withSsmClient` close clients when
  the block throws or is cancelled, and Java factory ownership follows existing
  `ShutdownQueue` behavior.
- Emulator-backed tests are optional for this PR. If Floci/LocalStack coverage
  for Secrets Manager or SSM is not reliable in the current repo, record the
  fallback reason and keep this PR at SDK wrapper/unit-test scope.
- If emulator smoke is attempted, run Floci first with
  `-Dbluetape4k.aws.emulator=floci`. If Floci lacks coverage, record the exact
  gap and retry only the relevant smoke with `localstack` as the explicit
  fallback. Run emulator-backed checks sequentially.

## Documentation

- Update user-facing README locale sets:
  - `README.md`
  - `README.ko.md`
  - `aws-java/README.md`
  - `aws-java/README.ko.md`
  - `aws-kotlin/README.md`
  - `aws-kotlin/README.ko.md`
- Required content:
  - module rows for `bluetape4k-aws-java` and `bluetape4k-aws-kotlin`
  - runtime dependency snippets for consumer applications:
    `software.amazon.awssdk:secretsmanager`, `software.amazon.awssdk:ssm`,
    `aws.sdk.kotlin:secretsmanager`, and `aws.sdk.kotlin:ssm`
  - statement that bluetape4k service SDK dependencies are `compileOnly`, so
    applications/tests must add the service SDK modules they use
  - mandatory direct examples for Java SDK and AWS Kotlin SDK:
    get secret string, get parameter, and get parameters by path
  - examples use placeholders, do not contain realistic secrets, and do not log
    or print revealed values
  - unsupported capability notes: no Spring Environment loading, JSON
    flattening, caching, refresh, rotation orchestration, IAM/KMS policy
    management, or full pagination abstraction
  - mutation notes for create/put helpers: AWS-side mutation/versioning
    semantics, SSM `overwrite` behavior, idempotency limits, and read-only
    examples first
  - hot-path guidance: wrappers are one-shot SDK calls; callers should reuse
    application-scoped clients and add caller-owned caching for request hot
    paths
  - dependency snippet version strategy consistent with current repo
    documentation, preferring `${bluetape4kVersion}` in module READMEs where
    that is the existing convention
- Update the service coverage chart assets:
  - `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg`
  - regenerate the matching PNG
- Keep generated diagram labels in English.

## Rollback / Backout

- Before release: revert helper packages, compile/test dependency declarations,
  README locale sets, SVG chart, and PNG chart together.
- After release: do not remove public APIs in a patch release; deprecate or
  follow up in a compatible release unless the feature was not published.
- Runtime rollback: no AWS resource migration or service-side state is created
  by the wrappers. Consumer runtime dependencies remain application-owned.
- Documentation rollback: README and chart claims must revert with code if
  helpers are backed out.

## Risks

1. AWS Kotlin SDK KMP artifact coordinates may resolve differently than plain
   Maven artifact names. Mitigation: add catalog aliases and prove compile with
   Gradle variant resolution.
2. Secrets Manager/SSM emulator behavior may be uneven. Mitigation: keep the
   first PR to SDK wrapper tests unless emulator smoke tests are reliable.
3. Public API could over-promote destructive operations. Mitigation: do not add
   delete convenience wrappers; policy-changing delete calls remain on raw SDK
   clients and must be explicit at the call site.
4. Secret values can leak in logs. Mitigation: redacted value objects and tests
   for diagnostic output.

## Acceptance Criteria

- `aws-java` exposes Secrets Manager and SSM client factories, request builders,
  sync extensions, async extensions, and coroutine adapters for common get/list
  and put flows.
- `aws-kotlin` exposes Secrets Manager and SSM client factories and native
  suspend extensions for common get/list and put flows.
- AWS service dependencies remain optional `compileOnly` dependencies with
  `testImplementation` for tests.
- Tests cover request validation, redaction, client factory construction, async
  coroutine adapters, Kotlin suspend extensions, and representative missing
  resource propagation.
- Tests cover pagination token exposure for single-page helpers and reject
  hidden eager all-pages collection behavior.
- Tests cover batch limit validation for Secrets Manager and SSM collection
  helpers.
- README root/module locale sets, SVG chart, and PNG chart are updated.
- PR DoD records emulator backend tried or the reason emulator coverage was
  skipped.
- Targeted compile/tests and `git diff --check` pass.
