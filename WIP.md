# WIP - bluetape4k-aws

Snapshot: 2026-05-12 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
Open count: 13 issues.

## Refresh Notes

Verified with GitHub connector on 2026-05-12 KST. `gh` CLI was not used because the local token is invalid.

Open PRs to watch:

- PR #55 `feat(aws-spring-boot): add SNS coroutine publisher`, closes #4.
- PR #54 `[codex] S3 KDoc 및 예제 모듈 정리`, closes #33, #34, #12, and #15.

Recently completed and no longer part of the active implementation queue:

- `aws-ktor`: SigV4 plugin and Ktor S3 client are merged by PR #27 and PR #28.
- `aws-spring-boot`: S3, SQS, and DynamoDB are merged by PR #29, PR #30, and PR #31.
- Governance and docs maintenance merged through PR #35, #36, #38, #39, #40, #41, and #53.

## Current Direction

Keep merge-wait work visible, but do not start a duplicate implementation for issues already covered by open PRs.

- `aws-spring-boot`: SNS is in PR #55. KMS, Secrets Manager / Parameter Store, and SES remain after SNS merges.
- `aws-ktor`: Ktor SQS and Ktor DynamoDB remain. SigV4 and Ktor S3 are merged.
- Examples should compile and test in Nightly. Spring Boot S3 `#12` and Ktor S3 `#15` are in PR #54. Spring Boot DynamoDB `#14` remains open.
- KDoc/doc scan follow-ups `#33/#34` are in PR #54.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P0 | [#4](https://github.com/bluetape4k/bluetape4k-aws/issues/4) Spring Boot SNS | M | PR #55 open. Merge before opening more Spring SNS/SQS example work. |
| P0 | [#12](https://github.com/bluetape4k/bluetape4k-aws/issues/12) Spring Boot S3 example | M | PR #54 open. |
| P0 | [#15](https://github.com/bluetape4k/bluetape4k-aws/issues/15) Ktor S3 example | M | PR #54 open. |
| P0 | [#33](https://github.com/bluetape4k/bluetape4k-aws/issues/33) S3CoroutinesTemplate class KDoc | S | PR #54 open. |
| P0 | [#34](https://github.com/bluetape4k/bluetape4k-aws/issues/34) aws-ktor public KDoc consistency | S | PR #54 open. |
| P1 | [#10](https://github.com/bluetape4k/bluetape4k-aws/issues/10) Ktor SQS | L | Next foundation candidate after SNS merge wait is settled. |
| P1 | [#11](https://github.com/bluetape4k/bluetape4k-aws/issues/11) Ktor DynamoDB | L | Reuse `#3` mapping/repository conventions. |
| P2 | [#14](https://github.com/bluetape4k/bluetape4k-aws/issues/14) Spring Boot DynamoDB example | M | Unblocked by PR #31; still needs example module coverage. |
| P2 | [#5](https://github.com/bluetape4k/bluetape4k-aws/issues/5) KMS support | M | Security/ops feature after base clients settle. |
| P2 | [#6](https://github.com/bluetape4k/bluetape4k-aws/issues/6) Secrets Manager / Parameter Store | M | Operationally useful, not an example blocker. |
| P3 | [#13](https://github.com/bluetape4k/bluetape4k-aws/issues/13) Spring Boot SQS/SNS example | M | Wait for PR #55 to merge. |
| P3 | [#16](https://github.com/bluetape4k/bluetape4k-aws/issues/16) Ktor SQS example | M | Depends on `#10`. |
| P3 | [#17](https://github.com/bluetape4k/bluetape4k-aws/issues/17) Ktor DynamoDB example | M | Depends on `#11`. |
| P4 | [#7](https://github.com/bluetape4k/bluetape4k-aws/issues/7) SES sender | M | Standalone and lower ecosystem leverage than S3/SQS/DynamoDB. |

## Dependency Map

```text
#8 SigV4 (closed by PR #27)
  -> #9 Ktor S3 (closed by PR #28)
      -> #15 Ktor S3 example (PR #54 open)
      -> #34 aws-ktor KDoc consistency (PR #54 open)

#1 Spring Boot S3 (closed by PR #29)
  -> #12 Spring Boot S3 example (PR #54 open)
  -> #33 S3CoroutinesTemplate KDoc (PR #54 open)

#2 Spring Boot SQS (closed by PR #30)
#4 Spring Boot SNS (PR #55 open)
  -> #13 Spring Boot SQS/SNS example

#3 Spring Boot DynamoDB (closed by PR #31)
  -> #14 Spring Boot DynamoDB example
  -> #11 Ktor DynamoDB conventions
      -> #17 Ktor DynamoDB example

#10 Ktor SQS
  -> #16 Ktor SQS example
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Merge wait | 2 PRs | PR #54 and PR #55. |
| Ktor foundation | 1 | `#10` or `#11` after merge-wait pressure drops. |
| Spring Boot foundation | 1 | Do not duplicate `#4` while PR #55 is open. |
| Examples | 1 | `#14`; keep `#13/#16/#17` behind their owning APIs. |
