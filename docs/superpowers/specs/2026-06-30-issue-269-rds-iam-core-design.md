# Issue #269 RDS IAM Core Helper Design

Date: 2026-06-30
Repository: `bluetape4k-aws`
Issue: #269
Branch: `feat/aws-rds-iam-core`

## Problem

`bluetape4k-aws-exposed` already generates Amazon RDS IAM authentication
tokens for JDBC connection creation, but the token request, generator, and
redaction-safe failure contract live inside the Exposed module. The root README
and service coverage chart now list RDS IAM as an AWS feature, so Java SDK v2
consumers should be able to use the framework-neutral RDS IAM token helper
without depending on Exposed, HikariCP, or JDBC data source creation.

Issue #269 promotes the RDS IAM token-generation boundary into core AWS modules
while keeping `aws-exposed` responsible for JDBC integration, refresh-aware
password providers, and Hikari/DriverManager behavior.

## Current Evidence

- Live GitHub issue #269 is assigned to `debop`, milestone `0.5.0`, and asks
  for a framework-neutral RDS IAM helper API, redaction-safe request/exception
  handling, tests, `aws-exposed` reuse, and README service coverage updates.
- Prior issue #77 and PR #163 added RDS IAM support in `aws-exposed` with:
  `AwsRdsIamAuthenticationProperties`, `AwsRdsIamAuthTokenRequest`,
  `AwsRdsIamAuthTokenGenerator`, `AwsSdkRdsIamAuthTokenGenerator`,
  `AwsRdsIamAuthTokenException`, `AwsDatabasePasswordProvider`, and
  `RdsIamRefreshingDataSource`.
- The existing Exposed implementation calls AWS SDK Java v2
  `RdsUtilities.generateAuthenticationToken(...)` with hostname, port,
  username, and region.
- Local AWS SDK Java v2 artifact `software.amazon.awssdk:rds:2.46.17`
  confirms `RdsUtilities.generateAuthenticationToken(...)` accepts
  `GenerateAuthenticationTokenRequest`, and that request exposes hostname,
  port, username, region, and credentials-provider fields.
- `aws-java` declares AWS service modules as `compileOnly`; consumers add the
  service SDKs they use at runtime. `aws-java` currently has no RDS dependency.
- `aws-kotlin` has no source helper for RDS IAM and the repo catalog contains
  no AWS Kotlin SDK RDS alias. The available implementation should therefore be
  Java SDK-backed unless a future AWS Kotlin RDS API is added.
- CodeGraph did not resolve the RDS IAM symbols in the worktree, so source
  inspection, GNO results, local Gradle catalog, and local SDK bytecode
  inspection are the current evidence path.

## Constraints

- Keep framework-neutral token generation in `:bluetape4k-aws-java`.
- Keep JDBC password refresh, Hikari, DriverManager, and Exposed `Database`
  creation in `:bluetape4k-aws-exposed`.
- Do not implement issue #295 here. `bluetape4k-jdbc` extraction remains a
  separate upstream/design task.
- Do not add a new AWS Kotlin SDK RDS dependency unless the catalog and SDK
  surface prove a native RDS IAM API is available.
- Preserve `compileOnly` service SDK policy: `aws-java` should compile against
  `software.amazon.awssdk:rds`, and consumers using RDS IAM add it at runtime.
- Preserve token redaction. Raw token strings may be revealed only at explicit
  caller/JDBC boundaries.
- Keep public API KDoc in English.
- Keep `README.md` and `README.ko.md` source-equivalent.
- If the service coverage chart changes, update SVG and PNG together and run
  rendered visual validation.

## Design Options

### Option A: Move Existing Exposed Types Into `aws-java`

Move `AwsRdsIamAuthTokenRequest`, `AwsRdsIamAuthTokenGenerator`,
`AwsSdkRdsIamAuthTokenGenerator`, and `AwsRdsIamAuthTokenException` from
`io.bluetape4k.aws.exposed` to `io.bluetape4k.aws.rds`, then update
`aws-exposed` imports.

Rejected as the direct implementation shape because it would remove or rename
public `aws-exposed` types abruptly. It is acceptable only if compatibility
aliases or wrappers are kept.

### Option B: Add A Core Generator That Returns `String`

Add `aws-java` helpers that return a raw token string and let downstream modules
wrap or redact it.

Rejected. Returning `String` as the primary API weakens the redaction contract
that #269 explicitly wants to preserve and makes accidental diagnostic leakage
easier.

### Option C: Add A Core Redacted Token API And Adapt `aws-exposed`

Selected. Add a framework-neutral `io.bluetape4k.aws.rds` package in
`aws-java` with a redacted token value, request model, generator interface,
AWS SDK Java v2 generator, and redaction-safe exception. Then update
`aws-exposed` so its default generator delegates token generation to the core
generator while retaining its existing JDBC-facing `AwsSecretString`,
refresh-aware provider APIs, and source-facing public names where practical.

This preserves existing Exposed user-facing JDBC behavior, avoids raw token
strings as the core API, and gives non-Exposed Java SDK v2 consumers a direct
RDS IAM helper.

## API Shape

Package: `io.bluetape4k.aws.rds`

- `AwsRdsIamAuthToken`
  - Redacted serializable value object.
  - `reveal(): String` exposes the token only for explicit caller boundaries.
  - `toString()` always returns a redacted marker.
  - Equality compares raw values in constant-time style as far as the JVM
    byte-array comparison allows.
- `awsRdsIamAuthTokenOf(value: String)`
  - Convenience factory with nonblank validation.
- `AwsRdsIamAuthTokenRequest`
  - Serializable request shape with `region`, `hostname`, `port`, and
    `username`.
  - Validates nonblank region/hostname/username and `port in 1..65535`.
- `AwsRdsIamAuthTokenGenerator`
  - Blocking `fun interface` returning `AwsRdsIamAuthToken`.
  - KDoc documents that token signing may resolve credentials and callers
    choose where to execute it.
- `AwsSdkRdsIamAuthTokenGenerator`
  - AWS SDK Java v2 implementation backed by `RdsUtilities`.
  - Default constructor builds `RdsUtilities` with
    `DefaultCredentialsProvider`.
  - Constructor accepting caller-managed `RdsUtilities` remains available for
    tests and custom lifecycle.
  - Wraps runtime failures in `AwsRdsIamAuthTokenException` without including
    token values or credentials.
- `AwsRdsIamAuthTokenException`
  - Extends `AwsBluetapeException`, the repo-standard AWS exception base.
  - Message includes endpoint host/port context, not token or credential data.

Package: `io.bluetape4k.aws.exposed`

- Keep `AwsRdsIamAuthenticationProperties`, `AwsDatabasePasswordProvider`,
  `AwsDatabasePasswordProviders`, and `RdsIamRefreshingDataSource` in
  `aws-exposed`.
- Keep existing Exposed public generator/request/exception names as
  compatibility adapters unless implementation proves a type alias has no JVM
  or Kotlin overload ambiguity.
- Update `AwsSdkRdsIamAuthTokenGenerator` in `aws-exposed` to delegate to the
  core `io.bluetape4k.aws.rds.AwsSdkRdsIamAuthTokenGenerator` and adapt
  `AwsRdsIamAuthToken.reveal()` into `AwsSecretString`.
- Keep `AwsDatabasePasswordProviders.rdsIam(...)` accepting the Exposed
  generator interface to avoid ambiguous lambda overloads for existing Kotlin
  callers.
- Add an overload or helper for the core generator only if it does not make
  lambda call sites ambiguous; otherwise the default Exposed SDK generator is
  the reuse point and the spec review records that compatibility constraint.

## Behavior

- Core token generation signs for the exact RDS endpoint hostname and port
  supplied by the caller.
- Core token generation does not own JDBC connection creation, token caching,
  Hikari pool configuration, or refresh scheduling.
- `aws-exposed` remains the only module that decides when to refresh a token for
  physical JDBC connections.
- Missing `software.amazon.awssdk:rds` at runtime fails with a redaction-safe
  message telling consumers to add the RDS SDK module.
- Generator failures preserve the original cause while keeping messages free of
  raw token, username password, and credential secret material.
- The default generator may use AWS default credentials provider resolution.
  Tests use fake generators or caller-supplied `RdsUtilities`; no test contacts
  production AWS.

## Documentation

- Root `README.md` and `README.ko.md` module table should state that
  `bluetape4k-aws-java` includes Java SDK-backed RDS IAM token helpers.
- Installation snippets for `bluetape4k-aws-java` should include optional
  `software.amazon.awssdk:rds` for RDS IAM users.
- `bluetape4k-aws-kotlin` documentation should not claim a native AWS Kotlin
  RDS IAM facade unless implementation is added.
- `aws-exposed/README.md` and `aws-exposed/README.ko.md` should point to the
  shared core generator and still document JDBC refresh behavior, endpoint
  exactness, SSL/TLS caller responsibility, and runtime RDS SDK dependency.
- The root service coverage chart must reflect the new `aws-java` RDS IAM
  support. Update SVG and PNG together when the visual matrix currently marks
  RDS IAM as Exposed-only or otherwise omits the Java module.

## Acceptance Criteria

- `:bluetape4k-aws-java` exposes a framework-neutral RDS IAM token helper API
  with English KDoc.
- `:bluetape4k-aws-java` declares `libs.aws2.rds` as `compileOnly` and uses it
  in tests without changing the runtime service dependency policy.
- Core tests cover request validation, request-to-AWS-SDK mapping, redacted
  token `toString()`, factory validation, and failure wrapping without token
  leakage.
- `:bluetape4k-aws-exposed` reuses the core SDK-backed generator through a
  compatibility adapter and records why legacy Exposed generator signatures
  remain.
- Existing Exposed RDS IAM tests still pass, including refresh-boundary,
  single-flight, failure-redaction, and JDBC connection-opening behavior.
- Root README, Korean README, and Exposed README locale pair describe the new
  module boundary consistently.
- Changed diagram/chart assets have matching SVG/PNG output and rendered
  inspection evidence.
- No production AWS calls are required for local verification.

## Out Of Scope

- Extracting DriverManager/DataSource token refresh behavior into
  `bluetape4k-jdbc` (#295).
- Spring Boot or Ktor RDS IAM auto-configuration.
- A native AWS Kotlin SDK RDS facade.
- Secrets Manager and Parameter Store wrappers (#268).
- Kinesis auto-configuration (#270).
- SES/v2 and SNS Ktor integrations (#271).

## Step 2-R Review Notes

### Codex Spec Review

| Priority | Finding | Decision |
|---|---|---|
| P0 | Removing or moving existing `aws-exposed` public RDS IAM types would create avoidable API breakage for users already on 0.4.x. | Accepted. Keep Exposed public names as compatibility wrappers/adapters and delegate the SDK-backed implementation to the new core helper. |
| P1 | Adding both core and Exposed `rdsIam(..., tokenGenerator)` overloads may make Kotlin lambda call sites ambiguous. | Accepted. Keep the existing Exposed generator signature for provider factories unless a non-ambiguous helper shape is proven during implementation. |
| P1 | The exception base should not be left implementation-dependent in the spec. | Accepted. Core `AwsRdsIamAuthTokenException` extends `AwsBluetapeException`; Exposed compatibility exceptions may extend or wrap it while preserving redaction. |
| P1 | README/chart work can expand into visual redraw churn. | Accepted. Chart updates are limited to the service coverage semantics required by #269, with SVG/PNG parity and rendered validation only for changed assets. |

Convergence: P0 = 0, P1 = 0 after accepted edits.
