# Issue #74 Exposed Database Foundation Plan

Date: 2026-05-21
Spec: `docs/superpowers/specs/2026-05-21-issue-74-exposed-database-foundation-design.md`

## Task 1: Register The New Module

complexity: medium

- Add `:bluetape4k-aws-exposed` mapped to `aws-exposed/`.
- Add Exposed, HikariCP, H2, PostgreSQL, and Testcontainers PostgreSQL aliases
  to `gradle/libs.versions.toml`.
- Add `aws-exposed/build.gradle.kts` with published-library dependencies.
- Add `README.md`, `README.ko.md`, `src/test/resources/junit-platform.properties`,
  and `src/test/resources/logback-test.xml`.
- Verification: `./gradlew projects`.

## Task 2: Implement Foundation Public API

complexity: high

Apply `$bluetape4k-patterns` and `$ecc-kotlin-exposed`.

- Implement serializable public model classes:
  - `AwsDatabaseProperties`
  - `AwsDatabaseConnectionProperties`
  - `AwsDatabasePoolProperties`
  - `AwsDatabaseConfigSource`
  - `AwsDatabaseConfigSourceType`
  - `AwsSecretString`
- Keep public KDoc in English.
- Validate nonblank names, URLs, and driver names at factory boundaries.
- Ensure sensitive values render redacted in `toString()`.
- Verification: `./gradlew :bluetape4k-aws-exposed:compileKotlin`.

## Task 3: Implement Resolver, Factory, And Registry

complexity: high

Apply `$bluetape4k-patterns`, `$ecc-kotlin-exposed`, and coroutine cancellation
rules.

- Implement `AwsDatabaseSettingsResolver` as a suspend pluggable resolver.
- Implement `NoopAwsDatabaseSettingsResolver`.
- Implement `AwsExposedDatabaseFactory`.
- Implement closeable `AwsExposedDatabaseHandle`.
- Implement closeable `AwsExposedDatabaseRegistry`.
- Ensure partial registry failures close previously created handles.
- Avoid AWS SDK, Spring, and Ktor types in this module.
- Verification: targeted unit tests plus `compileKotlin`.

## Task 4: Add H2 And PostgreSQL Tests

complexity: medium

Apply `$ecc-kotlin-testing` and `$ecc-kotlin-exposed`.

- Add model validation and secret redaction tests.
- Add H2 Exposed create/read transaction test.
- Add PostgreSQL Testcontainers Exposed create/read transaction test.
- Add registry lookup and partial-failure cleanup tests.
- Use bluetape4k assertions and `@TestInstance(PER_CLASS)`.
- Testcontainers commands must run sequentially.
- Verification: `./gradlew :bluetape4k-aws-exposed:cleanTest :bluetape4k-aws-exposed:test --no-build-cache`.

## Task 5: Update Docs And CI/Nightly

complexity: medium

- Update root `README.md` and `README.ko.md` module tables and local test commands.
- Update module README pair with dependency and no-real-AWS examples.
- Add `:bluetape4k-aws-exposed:test` to CI and Nightly module jobs.
- Add Kover report/upload coverage where the existing workflow expects per-module artifacts.
- Run `actionlint` after workflow edits.
- Verification: README/source grep, `actionlint`, targeted Gradle tests.

## Task 6: Review, Lesson, Commit, PR

complexity: medium

- Run current-session code review on the diff using the six-tier frame.
- Attempt Claude Code CLI review; record quota/unavailable gap if still blocked.
- Create `docs/lessons/2026-05-21-issue-74-exposed-database-foundation.md`.
- Commit with Lore trailers.
- Push branch and open PR assigned to `debop`.
- Post-PR review and CI gate are required before merge request.

## Step 3-R Review Notes

Claude Code Opus advisor: not run. Local CLI quota is currently exhausted.

| Priority | Finding | Decision |
|---|---|---|
| P1 | New-module plan must include CI, Nightly, BOM/publication, README pair, and `./gradlew projects`. | Accepted: Task 1 and Task 5 cover these checks. |
| P1 | Tests must prove both secret redaction and partial registry cleanup. | Accepted: Task 4 includes both cases. |
| P2 | Real AWS resolver implementations may be expected by issue wording. | Rejected for #74 implementation: issue asks for a pluggable contract; framework-specific AWS clients are #75/#76 scope. |
| P2 | Official docs are unavailable through Context7 quota. | Recorded: rely on local source and compile/test evidence. |

Convergence: P0 = 0, P1 = 0 after accepted plan edits.

## Verification Commands

```bash
./gradlew projects
./gradlew :bluetape4k-aws-exposed:compileKotlin
./gradlew :bluetape4k-aws-exposed:cleanTest :bluetape4k-aws-exposed:test --no-build-cache
actionlint
```
