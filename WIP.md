# WIP - bluetape4k-aws

Snapshot: 2026-05-13 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
Open count: 10 issues.

## Recently Completed

- `aws-spring-boot`: SNS, Secrets Manager / Parameter Store, and KMS support are
  merged by PR #55, PR #57, PR #58, and PR #62.
- `aws-ktor`: SQS consumer runtime is merged by PR #60.
- Review hardening is merged for `aws`, `aws-kotlin`, `aws-spring-boot`, and
  `aws-ktor` by PR #64 through PR #67.
- Test assertions and review-gate documentation were refreshed by PR #61 and
  PR #63.

## Current Direction

`aws` and `aws-kotlin` remain stable base modules. The active work has shifted
from core Spring Boot foundation toward examples, Ktor DynamoDB, and targeted
security/ops polish.

- `aws-spring-boot`: KMS base support exists; `#59` is the next field-level
  encryption layer. Secrets Manager / Parameter Store and SNS are merged.
- `aws-ktor`: S3 and SQS are merged; DynamoDB remains open.
- Examples should compile and test in Nightly. SQS/SNS examples are unblocked
  by SNS and SQS support, while Ktor DynamoDB still depends on `#11`.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P1 | [#59](https://github.com/bluetape4k/bluetape4k-aws/issues/59) `@KmsEncrypted` field-level encryption | M | Builds on merged KMS primitives; security-sensitive, needs focused tests. |
| P1 | [#11](https://github.com/bluetape4k/bluetape4k-aws/issues/11) Ktor DynamoDB | L | Reuse Spring DynamoDB repository conventions and Ktor S3/SQS module shape. |
| P2 | [#14](https://github.com/bluetape4k/bluetape4k-aws/issues/14) Spring Boot DynamoDB example | M | Unblocked by PR #31; must compile/test in Nightly examples job. |
| P2 | [#13](https://github.com/bluetape4k/bluetape4k-aws/issues/13) Spring Boot SQS/SNS example | M | SQS and SNS support are merged; example wiring can proceed. |
| P2 | [#16](https://github.com/bluetape4k/bluetape4k-aws/issues/16) Ktor SQS example | M | Unblocked by PR #60; should exercise consumer and publish paths. |
| P2 | [#17](https://github.com/bluetape4k/bluetape4k-aws/issues/17) Ktor DynamoDB example | M | Depends on `#11`. |
| P3 | [#6](https://github.com/bluetape4k/bluetape4k-aws/issues/6) Secrets Manager / Parameter Store follow-up | M | Base support is merged; keep open only for additional scope not covered by PR #57. |
| P3 | [#5](https://github.com/bluetape4k/bluetape4k-aws/issues/5) KMS support follow-up | M | Base support is merged; reconcile remaining scope with `#59`. |
| P4 | [#7](https://github.com/bluetape4k/bluetape4k-aws/issues/7) SES sender | M | Standalone and lower ecosystem leverage than examples and encryption follow-up. |
| P4 | [#10](https://github.com/bluetape4k/bluetape4k-aws/issues/10) Ktor SQS follow-up | L | Runtime is merged by PR #60; close or narrow after confirming residual scope. |

## Dependency Map

```text
#58/#62 KMS base support
  -> #59 @KmsEncrypted field-level encryption

#55 SNS + #30 SQS
  -> #13 Spring Boot SQS/SNS example

#60 Ktor SQS runtime
  -> #16 Ktor SQS example
  -> #10 residual scope review/close

#31 Spring Boot DynamoDB
  -> #14 Spring Boot DynamoDB example
  -> #11 Ktor DynamoDB
      -> #17 Ktor DynamoDB example
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Security/ops | 1 | `#59` |
| Ktor foundation | 1 | `#11`; reconcile `#10` residual scope separately. |
| Examples | 1 | Choose one of `#13/#14/#16`; each must remain wired into Nightly. |
| Docs/KDoc polish | 1 | Keep as small focused PRs only. |
