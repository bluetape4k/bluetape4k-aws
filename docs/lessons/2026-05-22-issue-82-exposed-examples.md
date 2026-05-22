# Issue 82 Exposed Example Modules

## Context

Issue #82 added Spring Boot and Ktor example modules for the shared
`bluetape4k-aws-exposed` database foundation.

## Decision

- Keep Spring MVC repository calls behind `transaction(database)`.
- Keep Ktor repository calls behind `call.awsExposedTransaction`.
- Use `PostgreSQLServer.Launcher.postgres` rather than direct container creation.
- Add new example modules to `settings.gradle.kts`, CI path filters/jobs, and Nightly.

## Outcome

Two example modules were added:

- `:aws-spring-boot-exposed-examples`
- `:aws-ktor-exposed-examples`

The Nightly example test command now uses `--max-workers=1` because Spring AOT/test
and Ktor tests can otherwise race on the shared reusable PostgreSQL singleton.

## Verification

- `./gradlew projects`
- `./gradlew :aws-spring-boot-exposed-examples:test :aws-ktor-exposed-examples:test --no-daemon --continue --max-workers=1`
- `./gradlew build -x test --parallel`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- Claude Code CLI code review: `.omx/artifacts/claude-issue-82-exposed-examples-code-review-rereview-small-20260522093611.md`, `Gate: PASS P0=0 P1=0`

## Future Guard

When a Nightly step is updated to add new modules, preserve any previously
explicit module tasks unless the dynamic discovery step is intentionally the sole
coverage mechanism and that decision is documented.
