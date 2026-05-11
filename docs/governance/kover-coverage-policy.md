# Kover Coverage Policy

## Current Status

`bluetape4k-aws` generates module Kover XML reports in Nightly for `aws`,
`aws-kotlin`, `aws-spring-boot`, and `aws-ktor`. No module currently has a
failing coverage threshold.

## Policy

Status: report-only transition.

The repository is integration-heavy because many tests depend on AWS SDK
behavior, LocalStack, Ktor clients, and Spring Boot auto-configuration. Do not
enable a broad repository-wide gate until module baselines are measured.

## Threshold Plan

- Treat Kover as a trend signal, not a build gate.
- Use Nightly XML reports and existing coverage artifact uploads to identify
  coverage regressions.
- Open a focused issue when a module needs coverage repair; do not introduce a
  failing threshold as the default enforcement mechanism.
- Examples remain informational coverage only.

## CI/Nightly Contract

Nightly uploads Kover XML artifacts and keeps trend visibility. CI and Nightly
must not fail solely because a module is below a fixed coverage percentage
unless a future issue explicitly reintroduces that gate.
