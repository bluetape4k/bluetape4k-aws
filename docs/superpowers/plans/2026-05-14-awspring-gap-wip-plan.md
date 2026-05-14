# Spring Cloud AWS Gap / Exposed WIP Plan

Date: 2026-05-14

## Scope

Track the feature backlog derived from comparing `bluetape4k-aws` with
Spring Cloud AWS 4.0.0 and from the follow-up decision to make JDBC/database
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
- PR #57 merged: Secrets Manager / Parameter Store loading.

## State Sync Notes

- #6 is still open in GitHub even though PR #57 merged remote-config loading.
  Before starting new work from #6, verify whether only docs/reload parity
  remains or whether the issue should be closed.
- #71 remains open for README gaps around SNS, KMS, and remote-config features.
  This should be handled before claiming the Spring Boot docs are current.

## Active Backlog

### Exposed-first AWS database integration

- #74 `feat(aws): Exposed-first AWS database integration foundation`
  - Shared database properties, secret/config loading contract,
    `bluetape4k-exposed` database factory, and named database registry.
- #75 `feat(aws-spring-boot): Exposed database auto-configuration`
  - Spring Boot 4 auto-configuration for `bluetape4k-exposed` databases backed
    by AWS config/secrets.
- #76 `feat(aws-ktor): AwsExposedPlugin for AWS-backed Exposed databases`
  - Ktor server plugin, application attributes, and `bluetape4k-exposed`
    suspend transaction helper.
- #77 `feat(aws): RDS IAM auth token provider for Exposed integrations`
  - IAM token password provider for `bluetape4k-exposed` database creation
    paths.
- #82 `feat(examples): Spring Boot and Ktor Exposed AWS database examples`
  - Adoption examples using `bluetape4k-exposed`, Testcontainers PostgreSQL,
    and local/mock AWS config.

Recommended execution order:

1. #6 state sync: close if PR #57 fully satisfies it, or narrow the remaining
   work to reload/docs parity.
2. #74 shared database foundation.
3. #75 Spring Boot Exposed auto-configuration.
4. #76 Ktor `AwsExposedPlugin`.
5. #77 RDS IAM auth token provider.
6. #82 examples.

### Remaining adoption/examples

- #7 `feat(aws-spring-boot): SES email sender`
  - Spring Boot SES sender and coroutine template.
- #11 `feat(aws-ktor): DynamoDB repository via Ktor server`
  - Ktor DynamoDB server-side repository integration.
- #14 `feat(examples): spring-boot-dynamodb`
  - Spring Boot 4 + DynamoDB example.
- #16 `feat(examples): ktor-sqs`
  - Ktor + SQS example.
- #17 `feat(examples): ktor-dynamodb`
  - Ktor + DynamoDB example.
- #71 `docs(aws-spring-boot): README missing SNS / KMS / remote-config features`
  - Documentation catch-up for already merged Spring Boot capabilities.

### AWSpring gap hardening

- #78 `feat(aws-spring-boot): S3 advanced transfer and config support`
  - Optional S3TransferManager/CRT support, multipart transfer hardening, S3
    config/property-source follow-up, and Access Grants evaluation.
- #79 `feat(aws-spring-boot): SQS listener and template parity hardening`
  - Batch operations, ack modes, visibility heartbeat, FIFO metadata, converter
    and header mapping, lifecycle and metrics.
- #80 `feat(aws-spring-boot): SNS HTTP endpoint and SMS support`
  - SMS publish support and HTTP(S) notification endpoint handling.
- #81 `feat(aws): Kinesis and DynamoDB Streams coroutine Flow support`
  - Coroutine Flow-based streaming alternative to Spring Integration/Kinesis
    binder style APIs.

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
