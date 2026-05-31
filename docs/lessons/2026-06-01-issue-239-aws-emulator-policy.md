# Issue 239 AWS Emulator Policy

## Context

`bluetape4k-aws` was historically tested mostly with LocalStack, but newer
Spring Boot emulator-aware tests already default to Floci and allow explicit
switching through `-Dbluetape4k.aws.emulator=floci|localstack|ministack`.
Issue #239 tracks the repo-wide migration from LocalStack assumptions toward a
clearer emulator policy.

## Decision

Keep the repository policy **Floci-first**:

- Use Floci as the preferred default for new or migrated emulator-aware tests.
- Keep LocalStack as an explicit fallback while legacy modules still depend on it.
- Treat MiniStack as an evaluation backend for service-coverage gaps, not as the
  default recommendation, until the same AWS SDK smoke matrix passes repeatedly.

## Outcome

The root README, Korean README, repo-local agent guidance, and Spring Boot README
now describe the same policy. The Spring Boot README commands were also aligned
with the real Gradle project path, `:bluetape4k-aws-spring-boot:test`.

## Verification

Verified the edited guidance with targeted text search for stale LocalStack
default phrasing and incorrect `:aws-spring-boot:test` commands. `git diff
--check` passed. The Spring Boot `*AwsEmulatorTest` smoke run passed with Floci
(`34 passing`) and LocalStack (`34 passing`), and failed with MiniStack
(`33 passing`, `1 failing`) because the SQS FIFO message group id was `null`
instead of `orders`.

## Future Guidance

Do not switch the repository default to MiniStack based only on service-count
claims. Revisit the default only after Floci, LocalStack, and MiniStack pass the
same target smoke matrix for S3, SQS, SNS, DynamoDB, KMS, Secrets Manager, and
SSM in this repository.
