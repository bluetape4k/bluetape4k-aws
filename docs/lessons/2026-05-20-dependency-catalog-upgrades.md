# Dependency Catalog Upgrades

## Context

`bluetape4k-dependencies` folded Dependabot PRs for AWS SDK Java and AWS SDK
Kotlin into the central dependency upgrade batch.

## Decision

Materialize the central catalog versions in the AWS repository instead of
accepting repo-local Dependabot version bumps.

## Outcome

- AWS SDK Java moved to `2.44.9`.
- AWS SDK Kotlin moved to `1.6.77`.

## Verification

- `./gradlew build -x test --parallel --no-daemon`
