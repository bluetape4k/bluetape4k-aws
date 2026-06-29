# Issue #200 - Ktor IMDS Helpers Plan

Date: 2026-06-07
Issue: #200 `feat(aws-ktor): add optional EC2 Instance Metadata Service helpers`

## Work Type

Type A full feature.

## Steps

1. Dependency wiring
   - Add `compileOnly(libs.aws2.imds)` and `testImplementation(libs.aws2.imds)`
     to `aws-ktor/build.gradle.kts`.
   - Verify with `dependencyInsight`.

2. Operations and template
   - Add `ImdsKtorOperations` with safe metadata helpers.
   - Add `ImdsKtorTemplate` backed by `Ec2MetadataAsyncClient`.
   - Validate paths with bluetape4k helpers and apply `withTimeout`.

3. Plugin configuration and runtime
   - Add `ImdsKtorPluginConfig` with enabled flag, injected operations, injected
     client, endpoint, endpoint mode, token TTL, request timeout, retries, and
     customizers.
   - Add `ImdsKtorRuntime` to hold operations and owned client lifecycle.
   - Add `ImdsKtorPlugin`, attribute keys, `Application.imds()`, and
     `Application.imdsOrNull()`.

4. Tests
   - Add template tests for path validation, path normalization, string/list
     parsing, and timeout cancellation.
   - Add plugin/config tests for disabled behavior, attribute storage,
     injected operations/client behavior, startup no-call behavior, validation,
     and owned-client close behavior.

5. Documentation
   - Update root and module README locale set with dependency, usage, EC2-only
     caveats, timeout behavior, and credential non-exposure.

6. Review and verification
   - Add implementation review artifact with `P0=0`, `P1=0`.
   - Add lesson entry.
   - Run:
     - `./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency imds --configuration compileClasspath`
     - `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.imds.*'`
     - `./gradlew :bluetape4k-aws-ktor:test`
     - `git diff --check`

## Stop Condition

PR is open for #200 with passing local verification evidence, committed
spec/plan/review/lesson artifacts, verified PR body, formal PR review, and CI
status monitored.
