# WIP - bluetape4k-aws

Snapshot: 2026-05-10 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
Open count: 12 issues.

## Current Direction

`aws` and `aws-kotlin` are the stable base modules. The active work is to
finish the remaining Spring Boot and Ktor integrations without depending on
awspring:

- `aws-spring-boot`: S3, SQS, and DynamoDB are merged by PR #29, PR #30,
  and PR #31. SNS, KMS, Secrets Manager / Parameter Store, and SES remain.
- `aws-ktor`: SigV4 signing and Ktor S3 are merged by PR #27 and PR #28.
  Ktor SQS and Ktor DynamoDB remain.

Examples should compile and test in Nightly. Spring Boot S3 `#12`, Spring Boot
DynamoDB `#14`, and Ktor S3 `#15` are now unblocked. Spring Boot SQS/SNS `#13`
still depends on SNS `#4`.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P1 | [#4](https://github.com/bluetape4k/bluetape4k-aws/issues/4) Spring Boot SNS | M | Completes the SQS/SNS Spring lane and unlocks fanout example `#13`. |
| P1 | [#10](https://github.com/bluetape4k/bluetape4k-aws/issues/10) Ktor SQS | L | Reuse `#8/#9` Ktor client patterns and `#2` SQS semantics. |
| P1 | [#11](https://github.com/bluetape4k/bluetape4k-aws/issues/11) Ktor DynamoDB | L | Reuse `#3` mapping/repository conventions. |
| P2 | [#12](https://github.com/bluetape4k/bluetape4k-aws/issues/12) Spring Boot S3 example | M | Unblocked by PR #29; must compile/test in Nightly examples job. |
| P2 | [#14](https://github.com/bluetape4k/bluetape4k-aws/issues/14) Spring Boot DynamoDB example | M | Unblocked by PR #31; must compile/test in Nightly examples job. |
| P2 | [#15](https://github.com/bluetape4k/bluetape4k-aws/issues/15) Ktor S3 example | M | Unblocked by PR #28; client example is partially seeded, server/routes remain. |
| P2 | [#5](https://github.com/bluetape4k/bluetape4k-aws/issues/5) KMS support | M | Security/ops feature after base clients settle. |
| P2 | [#6](https://github.com/bluetape4k/bluetape4k-aws/issues/6) Secrets Manager / Parameter Store | M | Operationally useful, not an example blocker. |
| P3 | [#13](https://github.com/bluetape4k/bluetape4k-aws/issues/13) Spring Boot SQS/SNS example | M | SQS is closed by PR #30; still depends on SNS `#4`. |
| P3 | [#16](https://github.com/bluetape4k/bluetape4k-aws/issues/16) Ktor SQS example | M | Depends on `#10`. |
| P3 | [#17](https://github.com/bluetape4k/bluetape4k-aws/issues/17) Ktor DynamoDB example | M | Depends on `#11`. |
| P4 | [#7](https://github.com/bluetape4k/bluetape4k-aws/issues/7) SES sender | M | Standalone and lower ecosystem leverage than S3/SQS/DynamoDB. |

## Dependency Map

```text
#8 SigV4 (closed by PR #27)
  -> #9 Ktor S3 (closed by PR #28)
      -> #15 Ktor S3 example (unblocked; partially seeded by PR #28, server/routes remain)

#1 Spring Boot S3 (closed by PR #29)
  -> #12 Spring Boot S3 example (unblocked)

#2 Spring Boot SQS (closed by PR #30)
#4 Spring Boot SNS
  -> #13 Spring Boot SQS/SNS example (still blocked by #4)

#3 Spring Boot DynamoDB (closed by PR #31)
  -> #14 Spring Boot DynamoDB example (unblocked)
  -> #11 Ktor DynamoDB conventions
      -> #17 Ktor DynamoDB example

#10 Ktor SQS
  -> #16 Ktor SQS example
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Ktor foundation | 1 | `#10/#11` after SNS or in a separate branch. |
| Spring Boot foundation | 1 | `#4` |
| Examples | 1 | `#12/#14/#15` are unblocked; every example must be included in Nightly. |
