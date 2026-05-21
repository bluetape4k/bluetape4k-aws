# Issue #77 RDS IAM Auth Plan

Date: 2026-05-21
Spec: `docs/superpowers/specs/2026-05-21-issue-77-rds-iam-auth-design.md`

## Task 1: Add RDS IAM Configuration Model

complexity: medium

- Add `AwsDatabaseAuthenticationMode`.
- Add `AwsRdsIamAuthenticationProperties`.
- Extend `AwsDatabaseConnectionProperties` with explicit authentication mode
  and optional RDS IAM settings.
- Preserve source compatibility by defaulting existing call sites to
  `STATIC_PASSWORD`.
- Validate nonblank region/hostname/username, `port in 1..65535`,
  `tokenTtl <= 15 minutes`, `refreshBeforeExpiry < tokenTtl`, and invalid
  mixed static password/RDS IAM settings.
- Add English KDoc to every new or changed public type.
- Keep all public model classes serializable with `serialVersionUID`.
- Verification: `./gradlew :bluetape4k-aws-exposed:compileKotlin`.

## Task 2: Add Token Request And Generator Contracts

complexity: medium

- Add `AwsRdsIamAuthTokenRequest`.
- Add `AwsRdsIamAuthTokenGenerator`.
- Add `AwsSdkRdsIamAuthTokenGenerator` backed by AWS SDK Java v2
  `RdsUtilities.generateAuthenticationToken`.
- Add `AwsRdsIamAuthTokenException` with redaction-safe failure messages.
- Validate SDK-backed generator setup eagerly where possible: parse
  `Region.of(...)`, verify `RdsUtilities` class availability, and fail with a
  redaction-safe `AwsRdsIamAuthTokenException` when SDK support is absent.
- Pin generator lifecycle in KDoc: caller-provided `RdsUtilities` is
  caller-managed and not closed by the generator.
- Verify exact `RdsUtilities` and `GenerateAuthenticationTokenRequest` builder
  APIs from local source/jar after adding the dependency.
- Add English KDoc to each new public generator/request/exception type.
- Add `aws2-rds` version catalog alias and `aws-exposed` dependencies using
  compile-only service SDK policy plus test dependency.
- Verification: compile + request-shape and generator-failure unit tests.

## Task 3: Add Refresh-Aware Password Provider

complexity: high

- Add `AwsDatabasePasswordProvider`.
- Add `AwsDatabasePasswordProviders` factory helpers that select static vs RDS
  IAM provider behavior from `AwsDatabaseConnectionProperties.authenticationMode`.
- Add static password provider behavior for existing mode.
- Add RDS IAM provider that caches a token only until the configured refresh
  boundary.
- Inject `Clock` for deterministic refresh tests.
- Coalesce concurrent refresh with `java.util.concurrent.locks.ReentrantLock`;
  do not use `synchronized`/`@Synchronized`.
- Guarantee no token inside the refresh window is returned.
- Add English KDoc to public provider/factory types.
- Ensure all returned secrets use `AwsSecretString`.
- Verification: token reuse, refresh-boundary, concurrent single-flight, and
  redaction tests with fake generator and mutable clock, including exception
  message/cause-chain checks.

## Task 4: Wire Hikari DataSource Creation

complexity: high

- Keep current Hikari static-password path unchanged.
- For RDS IAM mode, configure Hikari with an internal refreshing `DataSource`
  assigned through `HikariConfig.dataSource`; do not set
  `HikariConfig.username` or `HikariConfig.password` in RDS IAM mode.
- Implement the wrapper's no-arg `getConnection()` path because Hikari calls it
  for physical connections; reject user/password overloads or delegate them
  safely without bypassing the provider.
- Preserve pool settings and JDBC data source properties.
- Load `driverClassName` explicitly before `DriverManager.getConnection(...)`
  when one is configured.
- Build token-bearing JDBC `Properties` per connection call, forward configured
  data source properties, and do not retain that instance after the call.
- Keep raw token exposure limited to the `DriverManager.getConnection` call.
- Preserve driver-level SSL/TLS and data source properties by passing existing
  JDBC properties to the refreshing `DataSource`.
- Verification: compile and unit tests around provider invocation and Hikari
  mode selection where possible.

## Task 5: Update Docs

complexity: medium

- Update `aws-exposed/README.md`.
- Update `aws-exposed/README.ko.md`.
- Update root `CHANGELOG.md` Unreleased section.
- Mention `rds-db:connect`, exact RDS endpoint requirement, token TTL, and
  runtime AWS SDK RDS dependency.
- Mention SSL/TLS JDBC properties as caller responsibility for RDS IAM auth.
- Verification: README/source grep and `git diff --check`.

## Task 6: Review, Lesson, Commit, PR

complexity: medium

- Run targeted `aws-exposed` compile/tests/Kover.
- Run `:bluetape4k-aws-exposed:detekt`; add Kover verification if the module
  exposes a threshold task.
- Run current-session code review against DB/Exposed/public API scope.
- Attempt Claude Code CLI advisor review; record timeout/quota gap if blocked.
- Add `docs/lessons/2026-05-21-issue-77-rds-iam-auth.md`.
- Commit with Lore trailers, push, create PR assigned to `debop`.
- Add post-PR review comment/formal review and monitor CI.

## Step 3-R Review Notes

### Claude Code Opus Advisor

Artifact: `.omx/artifacts/claude-issue-77-plan-review-20260521-213703.md`
Model: `${CLAUDE_ADVISOR_MODEL:-claude-opus-4-7}`

| Priority | Finding | Decision | Follow-up |
|---|---|---|---|
| P1 | `AwsDatabasePasswordProviders` factory helpers were missing from tasks. | Accepted | Task 3 now adds the factory entry point. |
| P1 | SDK-backed generator eager validation was underspecified. | Accepted | Task 2 now pins region parsing, SDK class availability check, and redaction-safe failure. |
| P1 | Generator ownership/close lifecycle was unspecified. | Accepted | Task 2 now documents caller-managed `RdsUtilities`; no hidden close behavior. |
| P1 | Single-flight primitive was ambiguous and could violate virtual-thread guidance. | Accepted | Task 3 now requires `ReentrantLock` and forbids `synchronized`. |
| P2 | Refreshing `DataSource` method surface and property lifetime needed more detail. | Accepted | Task 4 now pins no-arg `getConnection()`, driver loading, per-call `Properties`, and property forwarding. |
| P2 | Public API KDoc tasks were implicit. | Accepted | Tasks 1-3 now require English KDoc. |
| P2 | Exception redaction and detekt/Kover checks were missing. | Accepted | Tasks 3 and 6 now include those checks. |

### Codex Multi-Perspective Integration

| Priority | Area | Finding | Decision |
|---|---|---|---|
| P1 | Implementability | Plan must map every spec API to a concrete task. | Closed by adding provider factory, exception, and lifecycle tasks. |
| P1 | DB lifecycle | Hikari wrapper must define exactly which connection path gets token injection. | Closed by Task 4 method-surface and property-lifetime details. |
| P1 | Kotlin quality | Public API KDoc and virtual-thread-safe locking must be explicit before implementation. | Closed by Tasks 1-3. |
| P2 | Verification | Detekt and Kover verification should be attempted with targeted tests. | Accepted in Task 6. |

Convergence: P0 = 0, P1 = 0 after accepted edits.

## Verification Commands

```bash
./gradlew :bluetape4k-aws-exposed:compileKotlin --no-configuration-cache
./gradlew :bluetape4k-aws-exposed:detekt --no-configuration-cache
./gradlew :bluetape4k-aws-exposed:cleanTest :bluetape4k-aws-exposed:test :bluetape4k-aws-exposed:koverXmlReport --no-build-cache --no-configuration-cache
git diff --check
```
