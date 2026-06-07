# Issue #196 IMDS Spring Boot Integration

Date: 2026-06-07
Issue: #196

## Context

`aws-spring-boot` had service auto-configuration for S3, SQS, SNS, SES, KMS,
DynamoDB, and CloudWatch but no Spring Boot-facing facade for EC2 Instance
Metadata Service reads.

## Decision

Add optional IMDS support as a passive Spring Boot auto-configuration:

- Keep `software.amazon.awssdk:imds` as an optional AWS SDK v2 dependency.
- Provide `ImdsOperations` and `ImdsCoroutinesTemplate` for coroutine metadata
  reads.
- Bound every metadata call with `bluetape4k.aws.imds.request-timeout`.
- Expose safe metadata helpers and IAM role names only; do not expose temporary
  credential documents.

## Outcome

The module now registers `Ec2MetadataAsyncClient` and `ImdsOperations` when the
IMDS SDK dependency is present and `bluetape4k.aws.imds.enabled` is true. It
backs off for disabled state, missing SDK classes, custom client beans, and
custom operations beans.

## Verification

- `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency imds --configuration compileClasspath`
  confirmed `software.amazon.awssdk:imds:2.46.0`.
- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.imds.*'`
  passed with 12 focused IMDS tests.
- `./gradlew :bluetape4k-aws-spring-boot:test` passed with 190 tests.
- `git diff --check` passed.

## Future Guard

Do not add an IMDS startup probe to prove EC2 presence. Non-EC2 applications
must pay no startup network penalty, and credential retrieval should remain on
the AWS SDK credential provider chain or explicit STS web identity support.

