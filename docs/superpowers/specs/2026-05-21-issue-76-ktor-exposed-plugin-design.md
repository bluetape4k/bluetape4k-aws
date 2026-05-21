# Issue #76 Ktor Exposed Plugin Design

Date: 2026-05-21
Repository: `bluetape4k-aws`
Branch: `feat/issue-76-ktor-exposed-plugin`
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/76

## Problem

Issue #82 needs Ktor examples for AWS-backed Exposed databases, but `aws-ktor`
does not yet provide a Ktor lifecycle adapter for the shared
`:bluetape4k-aws-exposed` foundation from #74. Applications need a plugin that
creates an `AwsExposedDatabaseRegistry`, stores it in application attributes,
provides application/call helpers, and runs Exposed JDBC work through a
coroutine-friendly transaction helper.

## Current Evidence

- #74 added `AwsDatabaseProperties`, `AwsExposedDatabaseFactory`,
  `AwsExposedDatabaseRegistry`, redacted `AwsSecretString`, source descriptors,
  and the pluggable `AwsDatabaseSettingsResolver`.
- #75 proved the framework adapter pattern in Spring Boot: bind adapter-local
  configuration, convert to the shared model, create a closeable registry, and
  expose default/named handles without owning transaction semantics.
- Existing Ktor plugins in `aws-ktor` store runtime objects in application
  attributes and use `MonitoringEvent(ApplicationStarted/ApplicationStopping)`
  for lifecycle work.
- Ktor 3.5 official docs show `createApplicationPlugin` for custom plugins and
  `MonitoringEvent(ApplicationStarted/ApplicationStopped)` for lifecycle
  cleanup.
- `bluetape4k-exposed` JDBC coroutine tests use Exposed
  `newSuspendedTransaction(context = Dispatchers.IO, db = database)` for
  coroutine-friendly JDBC work.
- Context7 documentation lookup was attempted and blocked by quota exhaustion;
  external API evidence is from official Ktor documentation and local source.

## Constraints

- No Spring Boot dependency.
- Keep AWS SDK service clients optional; no real AWS credentials in tests.
- Do not duplicate the shared database model or Hikari/Exposed factory from
  `:bluetape4k-aws-exposed`.
- `aws-ktor` uses `compileOnly` for optional Ktor server, AWS service, and
  Exposed integration dependencies; applications add runtime artifacts for the
  features they install.
- Secret values must stay redacted in generated diagnostics and logs.
- Ktor monitoring events are synchronous; suspend startup/shutdown work needs a
  bounded `runBlocking(Dispatchers.IO)` bridge, matching existing Ktor plugins.
- Public API and KDoc must be English.
- README changes must update both `aws-ktor/README.md` and
  `aws-ktor/README.ko.md`.
- Tests must use bluetape4k assertions and avoid JUnit/kotlin.test assertions.

## Design Options

### Option A: Put Exposed setup directly in route helpers

Rejected. Route-local factory creation would make connection pools per route or
per request too easy, and it would not give Ktor a clear lifecycle owner for
closing Hikari pools.

### Option B: Add a full Ktor configuration-source loader for Secrets Manager
and Parameter Store

Rejected for this slice. The foundation already has source descriptors and a
resolver hook. A complete loader would duplicate Spring Boot's property-source
machinery before Ktor has a general configuration-source abstraction. The Ktor
plugin should accept an `AwsDatabaseSettingsResolver` and preserve source
descriptors so applications can plug in Secrets Manager, Parameter Store, test
doubles, or future shared loaders.

### Option C: Add `AwsExposedPlugin` plus a runtime/DSL/helper layer

Selected. The plugin owns the Ktor lifecycle boundary, the shared foundation
owns database creation, and Exposed owns transaction behavior. The adapter stays
small and testable while still supporting direct properties, named databases,
custom resolvers, and suspend route transactions.

## Public API Shape

Package: `io.bluetape4k.aws.ktor.exposed`

- `AwsExposedPlugin`
  - Ktor `ApplicationPlugin<AwsExposedPluginConfig>`
  - creates and starts `AwsExposedKtorRuntime`
  - stores runtime under `AwsExposedKtorRuntimeKey`
  - closes the registry on `ApplicationStopping`
- `AwsExposedPluginConfig`
  - builds `AwsDatabaseProperties` through a Ktor-friendly DSL
  - accepts `databaseProperties`, `settingsResolver`, `databaseFactory`, and
    `transactionContext: CoroutineContext`
  - exposes `startTimeout: Duration = 30.seconds` and
    `stopTimeout: Duration = 10.seconds`
  - supports `defaultDatabase { ... }` and `database("name") { ... }`
- `AwsExposedConnectionConfig`
  - Ktor-local mutable builder that converts plain password strings to
    `AwsSecretString`
  - exposes source descriptors through `secretSource(...)` and
    `parameterSource(...)`
- `AwsExposedKtorRuntime`
  - starts by calling `AwsExposedDatabaseFactory.createRegistry(...)`
  - keeps lifecycle in an atomic state machine
  - exposes `registry`, `handle(name)`, `database(name)`, and
    `transaction(name, context) { ... }`
  - stops by closing the registry once
- Helpers:
  - `Application.awsExposed()`
  - `ApplicationCall.awsExposed()`
  - `Application.awsExposedHandle(name)`
  - `ApplicationCall.awsExposedHandle(name)`
  - `Application.awsExposedTransaction(name, context) { ... }`
  - `ApplicationCall.awsExposedTransaction(name, context) { ... }`

## Dependencies

`aws-ktor/build.gradle.kts` gains:

- `compileOnly(project(":bluetape4k-aws-exposed"))`
- `testImplementation(project(":bluetape4k-aws-exposed"))`
- `testImplementation(libs.h2.v2)`

No Ktor Exposed code path should require Spring Boot. Exposed JDBC and Hikari
arrive through the `:bluetape4k-aws-exposed` API dependency for callers that
add that artifact. README examples still name the optional
`bluetape4k-aws-exposed` dependency explicitly so non-Exposed `aws-ktor`
consumers do not inherit the integration accidentally.

## Configuration Resolution

The plugin supports two mutually exclusive configuration paths:

1. Direct model path: `databaseProperties(AwsDatabaseProperties(...))`.
2. DSL path: `defaultDatabase { ... }` and `database("name") { ... }`.

Mixing both paths in one plugin install is invalid and throws
`IllegalArgumentException`. This avoids silent precedence and merge surprises.
Duplicate named database registrations are also invalid.

`AwsExposedConnectionConfig` fields mirror
`AwsDatabaseConnectionProperties`: `url`, `driverClassName`, `username`,
`password`, `pool`, `dataSourceProperties`, `metadata`, `secretSource`,
`parameterSource`, `authenticationMode`, and `rdsIam`. Blank URLs, blank named
database names, blank usernames, blank driver names, blank source IDs, and blank
password strings are rejected with caller-input exceptions before registry
creation.

`secretSource(sourceId) { ... }` creates an
`AwsDatabaseConfigSource(SECRETS_MANAGER, sourceId, prefix, optional)`.
`parameterSource(sourceId) { ... }` creates an
`AwsDatabaseConfigSource(PARAMETER_STORE, sourceId, prefix, optional)`.
The Ktor adapter preserves these descriptors and delegates actual value
resolution to the configured `AwsDatabaseSettingsResolver`.

RDS IAM authentication remains a shared foundation concern. The Ktor plugin
forwards `authenticationMode` and `rdsIam` model values to the foundation but
does not add separate IAM behavior.

## Lifecycle

1. Plugin installation creates a runtime and puts it in application attributes.
2. `ApplicationStarted` calls `runtime.start()` through
   `runBlocking(Dispatchers.IO)` and enforces `startTimeout`.
3. `runtime.start()` creates exactly one registry. Lifecycle state is
   `NEW -> STARTING -> STARTED -> STOPPING -> STOPPED`; repeated starts after
   `STARTED` are no-ops and starts after `STOPPED` fail clearly.
4. Routes access the runtime from application or call helpers.
5. `ApplicationStopping` calls `runtime.stop()` through
   `runBlocking(Dispatchers.IO)` and enforces `stopTimeout`.
6. `runtime.stop()` is safe before a successful start, closes the registry once,
   and clears the runtime state.

## Failure Modes And Mitigations

- Missing plugin install: helpers throw `IllegalStateException` with a clear
  message instead of leaking a raw Ktor attribute error.
- Route used before start: runtime throws `IllegalStateException` with a clear
  "not started" message.
- Startup timeout: plugin start fails with a timeout message and does not store
  a half-built registry.
- Shutdown timeout: plugin logs a warning and clears state so repeated shutdown
  does not try to close the same registry again.
- Partial database creation failure: delegated to
  `AwsExposedDatabaseFactory.createRegistry`, which already closes created
  handles on failure.
- Secret leakage: password DSL converts to `AwsSecretString`; tests assert
  rendered config/runtime diagnostics and logs do not contain the literal secret
  sentinel.
- Blocking JDBC work on event loop: transaction helpers default to
  `Dispatchers.IO` and expose an override for advanced callers.
- Shutdown leak: stop closes registry once; tests prove close behavior with a
  test registry.
- Unsupported remote config loader: source descriptors remain in properties and
  custom `AwsDatabaseSettingsResolver` can resolve them; README states that no
  Ktor AWS source loader is bundled in this slice.

## Acceptance Criteria

- Ktor plugin lifecycle tests prove install, start, helper access, and stop
  behavior.
- H2 route test proves suspend transaction usage from an `ApplicationCall`.
- Named database lookup is tested through application/call helpers.
- Custom resolver test proves source descriptors can be resolved without
  logging secrets.
- Access before plugin installation and access before start fail with clear
  `IllegalStateException` messages.
- Start/stop idempotence and close-once behavior are tested with a
  close-counting test double.
- Startup timeout and stop timeout behavior are tested with controlled doubles.
- Transaction exception propagation and rollback are tested.
- README and README.ko show dependency, direct H2-style configuration, named
  database lookup, and suspend transaction usage.
- Targeted `:bluetape4k-aws-ktor` compile/tests pass.
- Current-session review plus Claude advisor gap/artifact is recorded.

## Step 2-R Review Notes

Claude Code Opus advisor artifact:
`.omx/artifacts/claude-issue-76-spec-review-20260521.md`.

| Priority | Finding | Decision |
|---|---|---|
| P0 | Unbounded lifecycle `runBlocking` can hang Ktor startup/shutdown. | Accepted: add `startTimeout` and `stopTimeout`; lifecycle events enforce them. |
| P1 | `databaseProperties` and DSL precedence was undefined. | Accepted: mixing direct model and DSL config is invalid. |
| P1 | Dependency strategy for `aws-ktor -> aws-exposed` was implicit. | Accepted: dependencies are listed and README must name optional runtime artifacts. |
| P1 | Idempotency needed a concrete state machine. | Accepted: atomic lifecycle state is now part of the spec. |
| P1 | Runtime/helper failure paths and secret redaction tests were underspecified. | Accepted: acceptance criteria now require not-installed/not-started, timeout, close-once, rollback, and redaction tests. |

Current Codex integration review: P0 = 0, P1 = 0 after accepted edits.
