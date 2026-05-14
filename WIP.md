# WIP - bluetape4k-aws

Snapshot: 2026-05-14 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
Open count: 6 issues.

## Recently Completed

- `aws-spring-boot`: SNS, Secrets Manager / Parameter Store, and KMS support are
  merged by PR #55, PR #57, PR #58, and PR #62.
- `aws-ktor`: SQS consumer runtime is merged by PR #60.
- `aws`: KMS field-level encryption and Spring Boot SQS/SNS fanout examples are
  merged by PR #73.
- Remote configuration refresh contract follow-up for Secrets Manager /
  Parameter Store is merged after the previous WIP snapshot.
- Review hardening is merged for `aws`, `aws-kotlin`, `aws-spring-boot`, and
  `aws-ktor` by PR #64 through PR #67.
- Test assertions and review-gate documentation were refreshed by PR #61 and
  PR #63.

## Current Direction

`aws` and `aws-kotlin` remain stable base modules. The active work is now Ktor
DynamoDB, examples, and one lower-priority Spring Boot SES sender.

- `aws-spring-boot`: KMS, Secrets Manager / Parameter Store, SNS, and remote
  refresh follow-ups are merged. SES sender remains open as `#7`.
- `aws-ktor`: S3 and SQS are merged; `#11` is active for DynamoDB and should use
  `:aws-kotlin` plus the official AWS SDK for Kotlin.
- Existing `aws-ktor` S3/SQS/SigV4 migration toward `:aws-kotlin` is tracked
  separately by unassigned issue `#85` so it does not block `#11`.
- Examples should compile and test in Nightly. SQS/SNS examples are unblocked
  by SNS and SQS support, while Ktor DynamoDB still depends on `#11`.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P1 | [#11](https://github.com/bluetape4k/bluetape4k-aws/issues/11) Ktor DynamoDB | L | Active; use `:aws-kotlin` and official AWS SDK for Kotlin DynamoDB. |
| P2 | [#14](https://github.com/bluetape4k/bluetape4k-aws/issues/14) Spring Boot DynamoDB example | M | Unblocked by PR #31; must compile/test in Nightly examples job. |
| P2 | [#16](https://github.com/bluetape4k/bluetape4k-aws/issues/16) Ktor SQS example | M | Unblocked by PR #60; should exercise consumer and publish paths. |
| P2 | [#17](https://github.com/bluetape4k/bluetape4k-aws/issues/17) Ktor DynamoDB example | M | Depends on `#11`. |
| P4 | [#7](https://github.com/bluetape4k/bluetape4k-aws/issues/7) SES sender | M | Standalone and lower ecosystem leverage than examples and encryption follow-up. |
| P4 | [#71](https://github.com/bluetape4k/bluetape4k-aws/issues/71) aws-spring-boot README feature refresh | S | Documentation-only; keep behind runtime/API work. |

## Unassigned Tracking Issues

| Issue | Notes |
|---|---|
| [#85](https://github.com/bluetape4k/bluetape4k-aws/issues/85) Existing Ktor integrations toward `:aws-kotlin` | Audit and migration design for S3/SQS/SigV4 public Java SDK v2 exposures; do not block `#11`. |

## Dependency Map

```text
#60 Ktor SQS runtime
  -> #16 Ktor SQS example

#31 Spring Boot DynamoDB
  -> #14 Spring Boot DynamoDB example
  -> #11 Ktor DynamoDB
      -> #17 Ktor DynamoDB example

#11 Ktor DynamoDB
  -> #85 Existing Ktor aws-kotlin migration audit (separate, non-blocking)
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Security/ops | 1 | No assigned security/ops issue after KMS field-level work merged. |
| Ktor foundation | 1 | `#11`; keep broad migration under `#85`. |
| Examples | 1 | Choose one of `#14/#16/#17`; each must remain wired into Nightly. |
| Docs/KDoc polish | 1 | Keep as small focused PRs only. |
