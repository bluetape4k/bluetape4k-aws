# Issue #77 RDS IAM Auth Design

Date: 2026-05-21
Repository: `bluetape4k-aws`
Branch: `feat/issue-77-rds-iam-auth`

## Problem

Issue #75 and #76 need a shared way to use Amazon RDS IAM database
authentication with the Exposed database foundation from #74. RDS IAM auth uses
a short-lived signed token as the JDBC password, so the foundation cannot set a
single static pool password at startup and assume it remains valid for new
connections.

## Current Evidence

- #77 depends on #74 and is used by #75 and #76.
- #74 introduced `bluetape4k-aws-exposed`, `AwsDatabaseConnectionProperties`,
  `AwsSecretString`, `AwsJdbcDataSourceFactory`, and Hikari-backed Exposed
  database creation.
- AWS RDS documentation says IAM authentication tokens are used instead of
  passwords and are valid for 15 minutes.
- AWS SDK for Java 2.x exposes `RdsUtilities.generateAuthenticationToken(...)`.
  The request carries hostname, port, username, region, and credentials
  provider override fields.
- AWS SDK `RdsUtilities` documentation states the utility does not make network
  calls when generating the token.
- AWS RDS Java documentation warns not to use a custom Route 53 DNS record
  instead of the DB instance endpoint when generating a token.

Primary sources:

- https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.IAMDBAuth.Connecting.html
- https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/java_rds_code_examples.html
- https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/rds/RdsUtilities.html
- https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/rds/model/GenerateAuthenticationTokenRequest.html

## Constraints

- Keep the shared contract in `aws-exposed`; Spring Boot and Ktor adapters will
  wire framework/client lifecycle later.
- Do not contact production AWS in tests.
- Keep tokens redacted through `AwsSecretString`.
- Do not create a proprietary connection pool.
- Avoid long-lived token caching beyond AWS token TTL semantics.
- Keep public KDoc in English.
- Keep README updates multilingual where localized READMEs exist.
- Follow existing AWS SDK dependency policy: service SDK modules are optional
  for consumers that use the corresponding service integration.
- RDS IAM token signing uses the configured RDS endpoint hostname verbatim.
  Custom DNS aliases are unsupported for token generation.
- JDBC drivers require passwords as `String`, so token reveal at the JDBC
  boundary is an accepted JVM heap exposure tradeoff. The implementation must
  not log tokens or store revealed token strings beyond the connection-opening
  call.

## Design Options

### Option A: Generate The Token Once In `AwsDatabaseSettingsResolver`

Rejected. It preserves the #74 resolver shape but fails the token refresh
requirement. Hikari could create a new physical connection after the token
expires and reuse the stale password.

### Option B: Replace Hikari With A Custom Pool

Rejected. Issue #77 explicitly excludes proprietary pool implementation, and
Hikari already supports wrapping a `DataSource`.

### Option C: Add A Refresh-Aware Password Provider And DataSource Path

Selected. Add explicit auth mode properties, a small token request/generator
contract, a cached RDS IAM password provider, and an internal `DataSource` used
by the Hikari factory for RDS IAM mode. Hikari remains the pool, but physical
connections ask the provider for a current token when they are opened.

## API Shape

Package: `io.bluetape4k.aws.exposed`

- `AwsDatabaseAuthenticationMode`
  - `STATIC_PASSWORD`
  - `RDS_IAM`
- `AwsRdsIamAuthenticationProperties`
  - `region: String`
  - `hostname: String`
  - `port: Int`
  - `username: String?`
  - `tokenTtl: Duration = 15 minutes`
  - `refreshBeforeExpiry: Duration = 2 minutes`
- `AwsDatabaseConnectionProperties`
  - add `authenticationMode`
  - add optional `rdsIam`
  - keep existing `password` for static password mode
- `AwsRdsIamAuthTokenRequest`
  - serializable request shape used by tests and generators
- `AwsRdsIamAuthTokenGenerator`
  - small blocking interface that returns `AwsSecretString`
- `AwsSdkRdsIamAuthTokenGenerator`
  - AWS SDK Java v2 implementation backed by `RdsUtilities`
- `AwsDatabasePasswordProvider`
  - blocking, thread-safe password provider for a physical connection creation
    boundary
- `AwsDatabasePasswordProviders`
  - factory helpers for static password and RDS IAM modes
- `AwsRdsIamAuthTokenException`
  - redaction-safe wrapper for token generation failures

Validation:

- `region` and `hostname` must be nonblank.
- `port` must be in `1..65535`.
- The effective username must be nonblank.
- `tokenTtl` must be positive and must not exceed 15 minutes.
- `refreshBeforeExpiry` must be positive and less than `tokenTtl`.
- `RDS_IAM` mode must not carry a static `password`.
- `STATIC_PASSWORD` mode must not carry `rdsIam` settings.

## Behavior

- `STATIC_PASSWORD` keeps existing Hikari behavior.
- `RDS_IAM` requires region, hostname, port, and an effective username from
  `rdsIam.username` or `AwsDatabaseConnectionProperties.username`.
- The RDS IAM password provider caches the generated token until
  `issuedAt + tokenTtl - refreshBeforeExpiry`, then generates a new token.
- The provider guarantees single-flight refresh. Concurrent callers crossing
  the refresh boundary observe one generator invocation and receive the same
  refreshed token.
- The provider returns only a token that is outside the refresh window at the
  moment the provider returns it.
- The provider never logs or exposes raw tokens except through
  `AwsSecretString.reveal()` at JDBC connection creation.
- The Hikari factory uses an internal refreshing `DataSource` for RDS IAM mode.
  Hikari must set `dataSource` to that wrapper and must not set
  `HikariConfig.username` or `HikariConfig.password` for RDS IAM mode. The
  wrapper opens each physical JDBC connection with the configured username and a
  freshly provided token, then delegates to `DriverManager.getConnection(...)`
  or an equivalent driver `DataSource` path.

## Failure Modes And Mitigations

- Stale token for new connections: generate through a provider at connection
  creation and refresh before expiry.
- Secret leakage: keep token as `AwsSecretString` and add redaction tests.
- Ambiguous signing endpoint: require explicit RDS hostname/port; do not infer
  from custom DNS.
- Token generation failure: surface `AwsRdsIamAuthTokenException` with a
  redaction-safe message and original cause.
- Missing or invalid RDS SDK configuration: validate SDK-backed generator setup
  eagerly where possible, including region parsing and optional RDS SDK class
  availability. Document that RDS IAM mode requires the AWS SDK RDS module on
  the runtime classpath.
- Blocking AWS credential resolution: token generation itself is local signing,
  but credentials provider resolution may block; framework adapters can choose
  credential provider lifecycle in #75/#76.
- Driver SSL/TLS requirements: document that callers must configure the JDBC
  URL or data source properties according to the target engine's RDS IAM SSL
  requirements.

## Acceptance Criteria

- Static password and RDS IAM token modes are explicit.
- Request shape tests verify region, host, port, and username mapping.
- Refresh behavior tests verify token reuse before expiry and regeneration
  after the refresh boundary.
- Concurrent refresh tests verify single-flight generation.
- Failure tests verify generator errors propagate as
  `AwsRdsIamAuthTokenException` without leaking token values.
- Configuration tests verify invalid host, port, username, TTL, and mixed
  static/RDS IAM settings fail with `IllegalArgumentException`.
- Redaction tests verify `AwsSecretString.toString()` and provider string
  output do not contain raw token characters.
- `aws-exposed` tests compile and pass without real AWS.
- README documents required `rds-db:connect` permission, exact RDS endpoint
  requirement, SSL/TLS caller configuration, and runtime RDS SDK dependency for
  IAM mode.

## Step 2-R Review Notes

### Claude Code Opus Advisor

Artifact: `.omx/artifacts/claude-issue-77-spec-review-20260521-213335.md`
Model: `${CLAUDE_ADVISOR_MODEL:-claude-opus-4-7}`

| Priority | Finding | Decision | Follow-up |
|---|---|---|---|
| P0 | Hikari static password path cannot refresh IAM tokens. | Accepted | Spec now requires a refreshing internal `DataSource` and leaves Hikari username/password unset in RDS IAM mode. |
| P0 | Token cache scope and concurrent refresh were underspecified. | Accepted | Spec now requires thread-safe single-flight refresh and no token returned inside the refresh window. |
| P0 | Hostname/endpoint contract and validation were too weak. | Accepted | Spec now requires nonblank endpoint hostname, verbatim RDS endpoint signing, and no custom DNS aliases. |
| P0 | Token generation errors and SDK setup failures lacked a redaction-safe contract. | Accepted | Spec now adds `AwsRdsIamAuthTokenException` and eager validation where possible. |
| P1 | Token reveal as JVM `String` needed an explicit security tradeoff. | Accepted | Spec now constrains reveal to the JDBC boundary and adds redaction acceptance tests. |
| P1 | Refresh boundary needs deterministic clock and single-flight behavior. | Accepted | Spec now requires deterministic tests and single-flight refresh behavior. |
| P1 | Mixed static password and RDS IAM configuration could silently succeed. | Accepted | Spec now rejects invalid mode/property combinations. |
| P1 | `tokenTtl` should not exceed the AWS 15-minute token lifetime. | Accepted | Spec now validates `tokenTtl <= 15 minutes` and uses `refreshBeforeExpiry = 2 minutes` by default. |

### Codex Multi-Perspective Integration

| Priority | Area | Finding | Decision |
|---|---|---|---|
| P0 | Security/DB lifecycle | A pool-level static password would violate RDS IAM expiry semantics. | Closed by the refreshing `DataSource` requirement. |
| P0 | API validation | Incorrect endpoint, port, username, TTL, or mixed auth mode settings would fail late. | Closed by explicit validation rules. |
| P1 | Testability | Refresh behavior and failure paths must not require real AWS. | Closed by fake generator and deterministic clock acceptance criteria. |
| P2 | Docs | Users need endpoint, SSL/TLS, permission, and runtime dependency guidance. | Accepted for README tasks. |

Convergence: P0 = 0, P1 = 0 after accepted edits.
