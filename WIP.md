# WIP - bluetape4k-aws

Snapshot: 2026-05-09 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
Open count: 17 issues.

## Current Direction

`aws` and `aws-kotlin` are the stable base modules. The active work is to add
two integration surfaces without depending on awspring:

- `aws-spring-boot`: Spring Boot 4 auto-configuration and coroutine templates.
- `aws-ktor`: Ktor 3 HTTP integration, starting from SigV4 signing.

Examples should wait until their backing integration issue is closed.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P1 | [#8](https://github.com/bluetape4k/bluetape4k-aws/issues/8) Ktor SigV4 request signing | L | Foundation for all Ktor AWS HTTP clients. |
| P1 | [#1](https://github.com/bluetape4k/bluetape4k-aws/issues/1) Spring Boot S3 | L | First high-value Spring Boot integration; unlocks `#12` and image S3 work. |
| P1 | [#2](https://github.com/bluetape4k/bluetape4k-aws/issues/2) Spring Boot SQS | L | Needed before SQS/SNS example; likely reusable listener/container patterns. |
| P1 | [#3](https://github.com/bluetape4k/bluetape4k-aws/issues/3) Spring Boot DynamoDB | L | Coroutine repository foundation; unlocks `#14` and Ktor DynamoDB reuse. |
| P1 | [#9](https://github.com/bluetape4k/bluetape4k-aws/issues/9) Ktor S3 client | L | Depends on `#8`; high leverage for Ktor S3 example. |
| P2 | [#4](https://github.com/bluetape4k/bluetape4k-aws/issues/4) Spring Boot SNS | M | Pair with `#2`; unlocks fanout example `#13`. |
| P2 | [#5](https://github.com/bluetape4k/bluetape4k-aws/issues/5) KMS support | M | Security/ops feature after base clients settle. |
| P2 | [#6](https://github.com/bluetape4k/bluetape4k-aws/issues/6) Secrets Manager / Parameter Store | M | Operationally useful, not an example blocker. |
| P2 | [#10](https://github.com/bluetape4k/bluetape4k-aws/issues/10) Ktor SQS | L | Reuse `#8` and Spring SQS design decisions where possible. |
| P2 | [#11](https://github.com/bluetape4k/bluetape4k-aws/issues/11) Ktor DynamoDB | L | Reuse `#3` mapping/repository conventions. |
| P3 | [#12](https://github.com/bluetape4k/bluetape4k-aws/issues/12) Spring Boot S3 example | M | Depends on `#1`. |
| P3 | [#13](https://github.com/bluetape4k/bluetape4k-aws/issues/13) Spring Boot SQS/SNS example | M | Depends on `#2` and `#4`. |
| P3 | [#14](https://github.com/bluetape4k/bluetape4k-aws/issues/14) Spring Boot DynamoDB example | M | Depends on `#3`. |
| P3 | [#15](https://github.com/bluetape4k/bluetape4k-aws/issues/15) Ktor S3 example | M | Depends on `#8` and `#9`. |
| P3 | [#16](https://github.com/bluetape4k/bluetape4k-aws/issues/16) Ktor SQS example | M | Depends on `#10`. |
| P3 | [#17](https://github.com/bluetape4k/bluetape4k-aws/issues/17) Ktor DynamoDB example | M | Depends on `#11`. |
| P4 | [#7](https://github.com/bluetape4k/bluetape4k-aws/issues/7) SES sender | M | Standalone and lower ecosystem leverage than S3/SQS/DynamoDB. |

## Dependency Map

```text
#8 SigV4
  -> #9 Ktor S3
      -> #15 Ktor S3 example

#1 Spring Boot S3
  -> #12 Spring Boot S3 example

#2 Spring Boot SQS
#4 Spring Boot SNS
  -> #13 Spring Boot SQS/SNS example

#3 Spring Boot DynamoDB
  -> #14 Spring Boot DynamoDB example
  -> #11 Ktor DynamoDB conventions
      -> #17 Ktor DynamoDB example

#10 Ktor SQS
  -> #16 Ktor SQS example
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Ktor foundation | 1 | `#8` |
| Spring Boot foundation | 1 | `#1`, then `#2/#3` |
| Examples | 0 until core closes | Start only after dependency issue closes. |
