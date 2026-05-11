# Kover Coverage Policy

## Current Status

`bluetape4k-aws` generates module Kover XML reports in Nightly for `aws`,
`aws-kotlin`, `aws-spring-boot`, and `aws-ktor`. No module currently has a
failing `koverVerify` threshold.

## Policy

Status: report-only transition.

The repository is integration-heavy because many tests depend on AWS SDK
behavior, LocalStack, Ktor clients, and Spring Boot auto-configuration. Do not
enable a broad repository-wide gate until module baselines are measured.

## Threshold Plan

- Pure client/wrapper modules: target 70%, then raise toward 80%.
- Spring/Ktor integration modules: start with a documented 60-70% bound after
  baseline measurement.
- Examples: informational coverage only.

## CI/Nightly Contract

Nightly uploads Kover XML artifacts. Add `koverVerify` to CI or Nightly only
after a module-level bound is introduced.
