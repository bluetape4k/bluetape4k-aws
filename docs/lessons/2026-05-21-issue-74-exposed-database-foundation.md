# Issue #74 Exposed Database Foundation

Date: 2026-05-21
Repository: `bluetape4k-aws`

## Context

Issue #82 is blocked by #74, #75, and #76. The first useful step was therefore
the framework-neutral Exposed database foundation, not the Spring Boot/Ktor
examples.

## Decision

Add a new publishable `bluetape4k-aws-exposed` module instead of adding Exposed
and Hikari dependencies to `bluetape4k-aws-java`. Keep AWS client resolution
pluggable through `AwsDatabaseSettingsResolver`; #75/#76 will own framework
specific Secrets Manager and Parameter Store wiring.

## Outcome

The module now provides database properties, redacted secret strings, a Hikari
data source factory, Exposed database factory, and default/named registry. Tests
cover secret redaction, H2 creation/read, PostgreSQL Testcontainers creation/read,
resolver override, registry lookup, and partial-failure cleanup.

## Verification

- `./gradlew :bluetape4k-aws-exposed:cleanTest :bluetape4k-aws-exposed:test :bluetape4k-aws-exposed:koverXmlReport --no-build-cache --no-configuration-cache` passed with 6 tests.
- `./gradlew projects --no-configuration-cache` listed `:bluetape4k-aws-exposed`.
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml` passed.
- `rg -n "\\\\'" .github/workflows` returned no escaped GitHub expression quotes.

## Future Guard

For new container-backed modules, add PR CI for changed module paths but keep
Nightly execution in the weekly/full lane unless the test is explicitly cheap
enough for daily smoke. Keep real AWS resolver implementations out of the
framework-neutral foundation.
