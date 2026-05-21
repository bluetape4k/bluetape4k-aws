# Issue #74 Exposed Database Foundation Design

Date: 2026-05-21
Repository: `bluetape4k-aws`
Branch: `feat/issue-74-exposed-db-foundation`

## Problem

Issue #82 cannot be implemented first because its Spring Boot and Ktor examples
depend on the shared Exposed-first AWS database foundation from #74 plus the
framework adapters from #75 and #76. Issue #74 must provide the reusable core
contract for AWS-backed database settings, pluggable secret/config resolution,
Exposed `Database` creation, and default/named database registry behavior.

## Current Evidence

- `WIP.md` orders the database work as `#74 -> #75/#76 -> #77 -> #82`.
- Issue #82 explicitly depends on #74, #75, and #76.
- `bluetape4k-exposed` uses Exposed JDBC `Database.connect(dataSource)` in
  `ExposedSpringDataAutoConfiguration`.
- `bluetape4k-exposed` repository patterns expect callers to run repository
  operations inside Exposed transactions, not inside an AWS-owned abstraction.
- Existing `aws-spring-boot` Secrets Manager and Parameter Store support loads
  key/value configuration but is Spring-specific.
- Context7 official documentation lookup was attempted and blocked by monthly
  quota exhaustion. Third-party API assumptions are therefore grounded in local
  `bluetape4k-exposed` source and dependency compile/test verification.

## Constraints

- AWS integration owns secret/config loading and future RDS IAM token hooks.
- Exposed owns `Database`, transactions, repositories, and SQL behavior.
- No awspring JDBC compatibility, JPA/Hibernate, or production RDS network test.
- No real AWS credentials in tests or examples.
- Secret-bearing model values must not leak through accidental `toString()`.
- Public APIs and KDoc must be English.
- README changes must update both `README.md` and `README.ko.md`.
- New module work must update `settings.gradle.kts`, BOM/publication coverage,
  CI, Nightly, README tables, and Gradle verification.

## Design Options

### Option A: Add the foundation to `bluetape4k-aws-java`

Rejected. This would add Exposed, Hikari, and JDBC database concerns to every
consumer of the base AWS Java SDK wrapper. The dependency and API blast radius
is broader than #74 requires.

### Option B: Add framework-local foundations to Spring Boot and Ktor modules

Rejected. This duplicates the database properties, resolver contract, factory,
registry, and secret masking logic. It also makes #75 and #76 diverge before
the shared contract has stabilized.

### Option C: Add `bluetape4k-aws-exposed`

Selected. A narrow publishable module can expose database settings, resolver
contracts, Hikari-backed DataSource creation, Exposed `Database` handles, and a
default/named registry. Spring Boot and Ktor adapters can depend on it without
polluting the base AWS modules.

## API Shape

Package: `io.bluetape4k.aws.exposed`

- `AwsDatabaseProperties`
  - `defaultDatabase: AwsDatabaseConnectionProperties`
  - `namedDatabases: Map<String, AwsDatabaseConnectionProperties>`
- `AwsDatabaseConnectionProperties`
  - `url`, `driverClassName`, `username`, `password`, `pool`, `metadata`
  - optional `secretSource` and `parameterSource` descriptors for adapter use
- `AwsSecretString`
  - wraps sensitive values and returns a redacted string from `toString()`
  - exposes `reveal()` only for connection construction
- `AwsDatabasePoolProperties`
  - maximum pool size, minimum idle, timeout metadata, optional pool name
- `AwsDatabaseConfigSource`
  - storage-neutral descriptor for Secrets Manager or Parameter Store sources
- `AwsDatabaseSettingsResolver`
  - suspend pluggable contract that resolves one named connection before the
    factory creates a DataSource
- `AwsExposedDatabaseFactory`
  - validates resolved settings
  - creates a Hikari `DataSource`
  - creates an Exposed JDBC `Database` with `Database.connect(dataSource)`
  - returns a closeable `AwsExposedDatabaseHandle`
- `AwsExposedDatabaseRegistry`
  - holds the default handle plus named handles
  - looks up handles by nullable/default name or explicit name
  - closes owned handles in reverse creation order

## Failure Modes And Mitigations

- Secret leakage through model logs: represent passwords with `AwsSecretString`
  and test `toString()` redaction.
- Partial registry creation leak: if a named database fails after earlier
  handles are created, close already-created handles before rethrowing.
- Invalid pool settings: validate pool sizes and timeout values before Hikari
  initialization.
- Missing JDBC driver: optionally load `driverClassName` with `Class.forName`
  and surface a clear `IllegalArgumentException`.
- Framework coupling: keep AWS SDK clients and Spring/Ktor types out of this
  module. Framework adapters provide actual AWS resolvers later.
- Test false positives: run H2 and PostgreSQL Testcontainers create/read
  transaction tests through Exposed.

## Acceptance Criteria

- Model supports URL, driver, username, password, pool metadata, and named
  database entries.
- Secrets Manager / Parameter Store resolution is pluggable and does not log
  secret values.
- Exposed `Database` creation is tested with H2 and PostgreSQL Testcontainers.
- Public API aligns with `bluetape4k-exposed` repository and transaction
  conventions.
- New module is registered, publishable, included in BOM aggregation, CI, and
  Nightly verification.
- Root README and Korean README describe the module and local verification
  without requiring real AWS credentials.

## Step 2-R Review Notes

Claude Code Opus advisor: not run. Local CLI currently reports usage credits
exhausted; Context7 docs lookup also reports quota exhaustion.

| Priority | Finding | Decision |
|---|---|---|
| P1 | Data class passwords can leak through generated `toString()`. | Accepted: use `AwsSecretString` for password values and add redaction tests. |
| P1 | Registry creation can leak Hikari pools on partial failure. | Accepted: factory closes already-created handles if a later named database fails. |
| P2 | Real AWS resolvers would expand #74 beyond its framework-neutral scope. | Accepted: #74 provides resolver contracts only; #75/#76 wire Secrets Manager/Parameter Store clients. |
| P2 | Context7 and Claude advisor gaps reduce external review coverage. | Recorded: compile/tests and local source evidence are required before PR. |

Convergence: P0 = 0, P1 = 0 after accepted spec edits.

## DoD

- Spec and plan committed before implementation.
- `./gradlew projects` shows `:bluetape4k-aws-exposed`.
- Targeted tests pass for `:bluetape4k-aws-exposed`.
- `actionlint` passes for workflow edits.
- Current-session code review finds no P0/P1.
- Claude review gap is recorded if CLI quota remains unavailable.
- Lesson file created under `docs/lessons/`.
