# Spring Cloud AWS Gap / Exposed WIP Plan

Date: 2026-05-14
Last updated: 2026-05-26

## Scope

Track the feature backlog derived from comparing `bluetape4k-aws` with
Spring Cloud AWS 4.x and from the follow-up decision to make JDBC/database
support Exposed-first for both Spring Boot and Ktor.

## Direction

- Do not clone awspring JDBC APIs.
- Use `bluetape4k-exposed` as the database access surface. In this WIP and the
  linked issues, "Exposed" means the `bluetape4k-exposed` project and its
  repository, transaction, audit, and column conventions, not a standalone raw
  Exposed integration.
- Let AWS integrations provide configuration, secrets, token generation, and
  framework wiring.
- Keep Spring Boot and Ktor adapters thin over shared core contracts.
- Prefer coroutine-native APIs over Spring Integration or blocking adapter
  compatibility unless compatibility gives clear adoption value.

## Completed Baseline

These are treated as already delivered unless later issues reopen a narrower
hardening slice.

- #1 closed / PR #29 merged: Spring Boot S3 auto-configuration.
- #2 closed / PR #30 merged: Spring Boot SQS listener and coroutine template.
- #3 closed / PR #31 merged: Spring Boot DynamoDB coroutine repository.
- #4 closed / PR #55 merged: Spring Boot SNS coroutine publisher.
- #5 closed / PR #58 merged: Spring Boot KMS encryption support.
- #8 closed / PR #27 merged: Ktor SigV4 client plugin.
- #9 closed / PR #28 merged: Ktor S3 client upload/download.
- #10 closed / PR #60 merged: Ktor SQS consumer runtime.
- #12 closed / PR #54 merged: Spring Boot S3 example.
- #13 closed: Spring Boot SQS/SNS example.
- #15 closed / PR #54 merged: Ktor S3 example.
- #59 closed: `@KmsEncrypted` field-level encryption.
- #6 closed / PR #57, PR #84, PR #86 merged: Secrets Manager / Parameter
  Store loading, refresh support, and refresh snapshot race fix.
- #11 closed / PR #87 merged: Ktor DynamoDB server plugin and repository
  facade on `:aws-kotlin`.
- #78 closed / PR #94 merged: Spring Boot S3 transfer operations and advanced
  transfer configuration.
- #79 closed / PR #93 merged: Spring Boot SQS parity hardening and Spring Boot
  example AOT wiring.
- #80 closed / PR #95 merged: Spring Boot SNS SMS publishing and HTTP(S)
  endpoint message parsing.

## State Sync Notes

- #71 is closed; README coverage for SNS, KMS, and remote-config features is no
  longer tracked as active WIP.
- New AWSpring-parity epics were created after the 2026-05-26 AWSpring 4.x gap
  pass:
  - #204 `[Epic] AWSpring-parity Spring Boot integrations`
  - #205 `[Epic] AWSpring-parity Ktor integrations`
- 0.3.0 is intentionally narrowed to S3/SQS production hardening plus the
  minimum shared configuration foundation needed to keep S3/SQS implementations
  consistent.
- DynamoDB, Exposed/database, CloudWatch/Logs, IMDS, and DAX work stays in
  Backlog unless a later release planning pass explicitly pulls it forward.

## 0.3.0 Scope: S3/SQS Production Hardening

### Keep in 0.3.0

- #190 `feat(aws-spring-boot): add shared AWS core properties and client customizers`
  - Foundation for Spring Boot S3/SQS region, endpoint, credential, and client
    customization consistency.
- #197 `feat(aws-ktor): add shared AWS defaults and client customizer hooks`
  - Foundation for Ktor S3/SQS plugin defaults, client ownership, and lifecycle
    consistency.
- #193 `feat(aws-spring-boot): add advanced SQS listener conversion, ack, retry, and observability`
  - Spring Boot SQS production controls: conversion, acknowledgement, retry,
    interceptors, and metrics/observability.
- #199 `feat(aws-ktor): add advanced SQS conversion, manual ack, retry, and observability`
  - Ktor SQS consumer runtime production controls.
- #192 `feat(aws-spring-boot): add advanced S3 encryption, config reload, access grants, and vector support`
  - 0.3.0 slice is implemented as S3 Environment config import/reload plus
    KMS-backed byte-array client-side encryption. Access Grants and S3 Vector
    are deferred because they would add optional SDK/client surfaces beyond the
    production-hardening slice.
- #203 `feat(aws-ktor): add advanced S3 encryption, access grants, vector, and config helpers`
  - 0.3.0 slice is S3 encryption plus content-type/config helpers. Access
    Grants and S3 Vector may be split or deferred if they expand release scope.
- #182 `test: stabilize SNS-to-SQS fanout LocalStack coverage`
  - Regression/stability support for the SQS hardening train.
- #206 `feat(examples): add Spring Boot AWSpring-parity examples`
  - Stretch scope for 0.3.0: Spring Boot S3/SQS examples only.
- #207 `feat(examples): add Ktor advanced AWS integration examples`
  - Stretch scope for 0.3.0: Ktor S3/SQS examples only.

### Move / Keep in Backlog

- #179 `feat: add aws-ktor DynamoDB integration`
- #180 `feat: wire aws-exposed settings through Spring Boot Secrets Manager and Parameter Store`
- #181 `feat: add Ktor AWS database settings plugin for exposed integration`
- #183 `test: share DynamoDB Local Testcontainers launcher across AWS and downstream repos`
- #191 `feat(aws-spring-boot): add optional DynamoDB DAX client integration`
- #194 `feat(aws-spring-boot): add CloudWatch and CloudWatch Logs auto-configuration`
- #196 `feat(aws-spring-boot): add optional EC2 Instance Metadata Service integration`
- #200 `feat(aws-ktor): add optional EC2 Instance Metadata Service helpers`
- #201 `feat(aws-ktor): add CloudWatch and CloudWatch Logs plugins`

### Recommended 0.3.0 Execution Order

1. Foundation PR train: #190, #197.
2. SQS PR train: #193, #199, then #182 as regression coverage.
3. S3 PR train: #192 and #203 with the narrowed 0.3.0 slices.
4. Example PR train: #206 and #207 only after the related S3/SQS APIs are
   usable.

## Active Backlog

### Exposed-first AWS database integration

- #74 `feat(aws): Exposed-first AWS database integration foundation` (closed)
  - Shared database properties, secret/config loading contract,
    `bluetape4k-exposed` database factory, and named database registry.
- #75 `feat(aws-spring-boot): Exposed database auto-configuration` (closed)
  - Spring Boot 4 auto-configuration for `bluetape4k-exposed` databases backed
    by AWS config/secrets.
- #76 `feat(aws-ktor): AwsExposedPlugin for AWS-backed Exposed databases` (closed)
  - Ktor server plugin, application attributes, and `bluetape4k-exposed`
    suspend transaction helper.
- #77 `feat(aws): RDS IAM auth token provider for Exposed integrations` (closed)
  - IAM token password provider for `bluetape4k-exposed` database creation
    paths.
- #82 `feat(examples): Spring Boot and Ktor Exposed AWS database examples` (closed)
  - Adoption examples using `bluetape4k-exposed`, Testcontainers PostgreSQL,
    and local/mock AWS config.
- #180 `feat: wire aws-exposed settings through Spring Boot Secrets Manager and Parameter Store`
  - Backlog after 0.3.0 scope narrowing.
- #181 `feat: add Ktor AWS database settings plugin for exposed integration`
  - Backlog after 0.3.0 scope narrowing.

Recommended execution order:

1. #74 shared database foundation.
2. #75 Spring Boot Exposed auto-configuration.
3. #76 Ktor `AwsExposedPlugin`.
4. #77 RDS IAM auth token provider.
5. #82 examples.

### Remaining adoption/examples

- #14 `feat(examples): spring-boot-dynamodb` (closed)
  - Spring Boot 4 + DynamoDB example.
- #16 `feat(examples): ktor-sqs` (closed)
  - Ktor + SQS example.
- #17 `feat(examples): ktor-dynamodb` (closed)
  - Ktor + DynamoDB example; unblocked by PR #87.
- #206 and #207 track the next S3/SQS-focused examples, with non-S3/SQS
  examples deferred to follow-ups.

### AWSpring gap hardening

- #7 `feat(aws-spring-boot): SES email sender` (closed)
  - Spring Boot SES sender and coroutine template.
- #81 `feat(aws): Kinesis and DynamoDB Streams coroutine Flow support` (closed)
  - Coroutine Flow-based streaming alternative to Spring Integration/Kinesis
    binder style APIs.
- #204 and #205 are the current parent epics for the remaining AWSpring-parity
  backlog.

## Lower Priority / Explicitly Deferred

- Full Spring Integration adapter cloning.
- awspring JDBC API compatibility.
- JPA/Hibernate support.
- RDS/EC2/ElastiCache/CloudFormation compatibility work from older awspring 2.x
  unless a concrete bluetape4k use case appears.
- Production AWS integration tests that require real credentials.

## Verification Checklist For Future PRs

- Add or update `docs/lessons/YYYY-MM-DD-{slug}.md` for non-trivial issue work.
- Run targeted module tests.
- Run the related module build/check gate before PR creation.
- Run `git diff --check`.
- Document actually executed commands in the PR body.
