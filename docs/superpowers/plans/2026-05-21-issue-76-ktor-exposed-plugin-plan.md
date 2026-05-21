# Issue #76 Ktor Exposed Plugin Plan

Date: 2026-05-21
Spec: `docs/superpowers/specs/2026-05-21-issue-76-ktor-exposed-plugin-design.md`

## Task 1: Wire Optional Dependencies

complexity: medium

- Add `compileOnly(project(":bluetape4k-aws-exposed"))` to `aws-ktor`.
- Add `testImplementation(project(":bluetape4k-aws-exposed"))`.
- Add H2 test runtime dependency for route-level JDBC tests.
- Keep the dependency optional in README, matching existing Ktor server and AWS
  service client dependency policy.
- Verification: `./gradlew :bluetape4k-aws-ktor:compileKotlin`.

## Task 2: Implement Ktor Exposed Runtime And Plugin

complexity: high

Apply `$bluetape4k-patterns`, `kotlin-coroutines-skill`, and Exposed rules.

- Add package `io.bluetape4k.aws.ktor.exposed`.
- Implement `AwsExposedPluginConfig`.
- Implement `AwsExposedConnectionConfig` and pool/source helper builders.
- Implement `AwsExposedKtorRuntime`.
- Implement `AwsExposedPlugin` and `AwsExposedKtorRuntimeKey`.
- Use `MonitoringEvent(ApplicationStarted/ApplicationStopping)`.
- Add `startTimeout` and `stopTimeout`; enforce both around lifecycle
  `runBlocking(Dispatchers.IO)` bridges.
- Add comments at both lifecycle bridges explaining why `runBlocking` is
  permitted there.
- Use an explicit atomic lifecycle state for registry transitions.
- Make `start()` and `stop()` idempotent where safe; starts after stop fail
  clearly.
- Forbid mixing direct `databaseProperties(...)` and DSL database builders.
- Reject duplicate named database registrations.
- Keep public KDoc in English with realistic usage examples.
- Verification: `./gradlew :bluetape4k-aws-ktor:compileKotlin`.

## Task 3: Implement Application And Call Helpers

complexity: medium

- Add `Application.awsExposed()`.
- Add `ApplicationCall.awsExposed()`.
- Add handle/database helper functions for default and named databases.
- Add suspend transaction helpers backed by Exposed JDBC
  `newSuspendedTransaction`.
- Default transaction context must be `Dispatchers.IO`; callers can override it.
- Public helper KDoc must document `IllegalStateException` when the plugin is
  not installed or not started.
- Avoid deprecated Exposed imports.
- Verification: compile plus route-level tests.

## Task 4: Add Tests

complexity: high

- Add plugin lifecycle test with H2 configuration.
- Add route-level suspend transaction test using `testApplication`.
- Add named database lookup test.
- Add custom resolver/source descriptor test proving secret redaction by
  asserting the sentinel password does not appear in rendered config/runtime
  diagnostics or captured plugin logs.
- Add access-before-install and access-before-start tests with clear
  `IllegalStateException` messages.
- Add mandatory stop/close idempotence test proving `registry.close()` is
  invoked exactly once, using a close-counting test double.
- Add startup-timeout and stop-timeout tests with controlled doubles.
- Add transaction exception propagation and rollback test.
- Add optional Ktor-edge partial-creation failure test if the test double can
  stay small and readable.
- Use bluetape4k assertions only.
- Use `runSuspendIO` for real IO/suspend database tests.
- Verification:
  `./gradlew :bluetape4k-aws-ktor:cleanTest :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.exposed.*' --no-build-cache --no-configuration-cache --no-daemon`.

## Task 5: Update README Locale Set

complexity: medium

- Update `aws-ktor/README.md`.
- Update `aws-ktor/README.ko.md`.
- Include dependency snippet for `bluetape4k-aws-exposed`, Ktor server core,
  H2/PostgreSQL JDBC driver examples, Exposed JDBC, and Exposed usage.
- Show `install(AwsExposedPlugin)`, named database lookup, and
  `call.awsExposedTransaction { ... }`.
- State that Ktor remote source loading is resolver-based in this slice.
- Name package path `io.bluetape4k.aws.ktor.exposed`.
- Verification: grep examples against actual source names.

## Task 6: Review, Lessons, Commit, PR

complexity: medium

- Run current-session code review with at least Tier 4 + Tier 5 and DB/Exposed
  lifecycle focus.
- Attempt Claude Code Opus advisor review for spec/plan and code review; save
  artifacts or record quota/unavailable gaps.
- Create `docs/lessons/2026-05-21-issue-76-ktor-exposed-plugin.md`.
- Commit spec/plan before implementation if review gates pass.
- Commit implementation and lesson with Lore trailers.
- Push branch and open PR assigned to `debop`.
- Post PR comment and formal review entry.
- Check CI status; do not merge without user request.

## Step 3-R Review Notes

Claude Code Opus advisor artifact:
`.omx/artifacts/claude-issue-76-plan-review-20260521.md`.

| Priority | Finding | Decision |
|---|---|---|
| P1 | Lifecycle timeout tasks were missing. | Accepted: Task 2 adds start/stop timeout implementation and verification. |
| P1 | Access-before-start failure path was untested. | Accepted: Task 4 adds before-install and before-start tests. |
| P1 | Secret redaction assertion mechanism was unspecified. | Accepted: Task 4 now requires sentinel assertions against diagnostics/logs. |
| P1 | Close-once test was optional. | Accepted: Task 4 makes close-once idempotence mandatory. |
| P2 | Helper KDoc and dependency docs needed sharper wording. | Accepted: Task 3 and Task 5 updated. |

Current Codex integration review: P0 = 0, P1 = 0 after accepted edits.

## Verification Commands

```bash
./gradlew :bluetape4k-aws-ktor:compileKotlin
./gradlew :bluetape4k-aws-ktor:cleanTest :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.exposed.*' --no-build-cache --no-configuration-cache --no-daemon
./gradlew :bluetape4k-aws-ktor:test --no-build-cache --no-configuration-cache --no-daemon
./gradlew :bluetape4k-aws-ktor:detekt
git diff --check
```
