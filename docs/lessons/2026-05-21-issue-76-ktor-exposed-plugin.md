# Issue #76 Ktor Exposed Plugin

Date: 2026-05-21
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/76

## Context

`aws-ktor` needed a Ktor lifecycle integration for the `bluetape4k-aws-exposed`
foundation from #74 without duplicating Secrets Manager or Parameter Store
loading policy.

## Decision

Add `AwsExposedPlugin` inside the existing `aws-ktor` module and keep
`bluetape4k-aws-exposed` as an optional compile-time dependency. The plugin
stores one bounded lifecycle runtime in application attributes, creates a shared
registry on `ApplicationStarted`, closes it once on `ApplicationStopping`, and
exposes application/call helpers for handle, database, and suspend transaction
access.

## Outcome

The plugin accepts either prebuilt `AwsDatabaseProperties` or a Ktor DSL, rejects
mixed configuration styles, preserves AWS config-source descriptors for
resolver-based loading, and redacts static passwords through `AwsSecretString`.
README examples were updated in English and Korean.

## Verification

- `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:compileTestKotlin`
- `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.exposed.AwsExposedPluginTest'`
- `git diff --check`
- Local Codex review: P0=0, P1=0
- Claude CLI review gap recorded at `.omx/artifacts/claude-issue-76-code-review-20260521.md`

## Future Guard

For Ktor lifecycle plugins that close blocking JDBC resources, use
`runInterruptible(Dispatchers.IO)` with a timeout and test the interrupted close
path. Treat `InterruptedException` wrapped by shared registry close code as the
expected timeout signal only in the shutdown path; let ordinary close failures
propagate.
