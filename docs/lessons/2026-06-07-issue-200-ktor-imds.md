# Issue #200 Ktor IMDS Integration

Date: 2026-06-07
Issue: #200

## Context

`aws-ktor` had shared Ktor AWS configuration helpers but no Ktor-facing EC2
Instance Metadata Service integration. The Spring Boot IMDS work from #196 set
the safety baseline: optional dependency, no startup probe, bounded reads, and
no temporary credential document exposure.

## Decision

Add optional Ktor IMDS helpers as a passive Ktor plugin:

- Keep `software.amazon.awssdk:imds` as an optional compile-time dependency for
  users and a test dependency for this module.
- Provide `ImdsKtorOperations` and `ImdsKtorTemplate` for coroutine metadata
  reads.
- Store operations in Ktor application attributes through `ImdsKtorPlugin`.
- Use explicit IMDS endpoint configuration instead of inheriting normal AWS
  service endpoint overrides.
- Expose safe metadata helpers and IAM role names only.

## Outcome

`aws-ktor` now supports installing `ImdsKtorPlugin` and resolving
`Application.imds()` for bounded metadata reads. The plugin can be disabled,
can accept injected operations or clients for tests, owns only clients it
creates, and keeps metadata calls out of application startup.

## Verification

- `./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency imds --configuration compileClasspath`
  confirmed `software.amazon.awssdk:imds:2.46.0`.
- `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.imds.*'`
  passed with 13 focused IMDS tests.
- `./gradlew :bluetape4k-aws-ktor:test` passed with 82 tests.
- `git diff --check` passed.

## Future Guard

Keep Ktor IMDS passive. Do not add an install-time probe, do not expose role
credential documents, and do not couple IMDS endpoint selection to ordinary AWS
service endpoint overrides.
