# Issue #77 RDS IAM Auth Token Provider

Date: 2026-05-21
Repository: `bluetape4k-aws`
Issue: #77

## Context

`aws-exposed` needed a shared RDS IAM database authentication path before the
Spring Boot and Ktor Exposed adapters. Hikari cannot safely reuse a static
startup password for RDS IAM because new physical connections may be opened
after the AWS token expires.

## Decision

Add explicit `STATIC_PASSWORD` and `RDS_IAM` modes to
`AwsDatabaseConnectionProperties`, keep static password behavior unchanged, and
route RDS IAM mode through a Hikari `DataSource` wrapper that requests a fresh
provider token at physical connection creation.

The token provider uses an injectable `Clock`, a `ReentrantLock` single-flight
refresh, and a refresh boundary before the AWS 15-minute token TTL. AWS SDK RDS
stays optional through `compileOnly`; consumers using RDS IAM mode add
`software.amazon.awssdk:rds` at runtime.

## Outcome

- Added RDS IAM token request/generator/provider contracts.
- Added AWS SDK Java v2 `RdsUtilities` implementation.
- Added refresh-aware token caching and Hikari `DataSource` integration.
- Added unit tests for validation, request mapping, token reuse/regeneration,
  concurrent refresh coalescing, failure wrapping, and JDBC connection opening.
- Updated English/Korean module README files and CHANGELOG.

## Verification

- `./gradlew :bluetape4k-aws-exposed:compileKotlin :bluetape4k-aws-exposed:compileTestKotlin --no-configuration-cache`
- `./gradlew :bluetape4k-aws-exposed:cleanTest :bluetape4k-aws-exposed:test :bluetape4k-aws-exposed:koverXmlReport :bluetape4k-aws-exposed:koverVerify --no-build-cache --no-configuration-cache`
  - 12 tests passed.
- `git diff --check`
- `:bluetape4k-aws-exposed:detekt` was attempted, but the module has no
  `detekt` task.
- Claude Code Opus advisor was attempted for code review, but local Claude
  returned a usage-credit blocker.

## Future Guard

When adding short-lived credentials to JDBC/Hikari, do not put the credential in
`HikariConfig.password`. Put credential acquisition at the physical connection
opening boundary and test refresh behavior with a deterministic clock plus a
concurrency test.
