# Changelog

All notable changes to `bluetape4k-aws` are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
This project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- KMS field-level encryption and Spring Boot SQS/SNS fanout examples ([PR #73](https://github.com/bluetape4k/bluetape4k-aws/pull/73)).
- Root README hero image plus refreshed project-purpose, feature, and architecture entrypoint documentation ([PR #68](https://github.com/bluetape4k/bluetape4k-aws/pull/68)).
- Ktor SQS consumer runtime and server integration ([PR #60](https://github.com/bluetape4k/bluetape4k-aws/pull/60)).
- Spring Boot 4 SNS coroutine publisher ([PR #55](https://github.com/bluetape4k/bluetape4k-aws/pull/55)).
- Spring Boot 4 Secrets Manager and Parameter Store property loading ([PR #57](https://github.com/bluetape4k/bluetape4k-aws/pull/57)).
- Spring Boot 4 KMS encryption support and disabled-contract fix ([PR #58](https://github.com/bluetape4k/bluetape4k-aws/pull/58), [PR #62](https://github.com/bluetape4k/bluetape4k-aws/pull/62)).
- Ktor SigV4 client plugin for AWS request signing ([PR #27](https://github.com/bluetape4k/bluetape4k-aws/pull/27)).
- Ktor S3 coroutine client and LocalStack-oriented example coverage included in Nightly ([PR #28](https://github.com/bluetape4k/bluetape4k-aws/pull/28)).
- Spring Boot 4 S3 auto-configuration and coroutine operations template ([PR #29](https://github.com/bluetape4k/bluetape4k-aws/pull/29)).
- Spring Boot 4 SQS coroutine operations template, listener annotation, and polling container ([PR #30](https://github.com/bluetape4k/bluetape4k-aws/pull/30)).
- Spring Boot 4 DynamoDB enhanced async client auto-configuration and coroutine repository base ([PR #31](https://github.com/bluetape4k/bluetape4k-aws/pull/31)).
- `bluetape4k-aws-bom` BOM module for AWS library consumers ([PR #24](https://github.com/bluetape4k/bluetape4k-aws/pull/24)).
- English and Korean README files for the AWS BOM module ([PR #25](https://github.com/bluetape4k/bluetape4k-aws/pull/25)).
- GitHub Actions workflows for CI, nightly, snapshot, release, and code-quality checks ([PR #19](https://github.com/bluetape4k/bluetape4k-aws/pull/19)).

### Changed

- Refreshed README workbench image and aligned license text on MIT ([PR #72](https://github.com/bluetape4k/bluetape4k-aws/pull/72), [PR #70](https://github.com/bluetape4k/bluetape4k-aws/pull/70)).
- Consolidated PR review gate metrics documentation ([PR #69](https://github.com/bluetape4k/bluetape4k-aws/pull/69)).
- Hardened review findings across `aws`, `aws-kotlin`, `aws-spring-boot`, and `aws-ktor` tests ([PR #64](https://github.com/bluetape4k/bluetape4k-aws/pull/64), [PR #65](https://github.com/bluetape4k/bluetape4k-aws/pull/65), [PR #66](https://github.com/bluetape4k/bluetape4k-aws/pull/66), [PR #67](https://github.com/bluetape4k/bluetape4k-aws/pull/67)).
- Unified `aws-spring-boot` tests on `bluetape4k-assertions` ([PR #63](https://github.com/bluetape4k/bluetape4k-aws/pull/63)).
- Refreshed WIP queue and review-gate metrics documentation ([PR #56](https://github.com/bluetape4k/bluetape4k-aws/pull/56), [PR #61](https://github.com/bluetape4k/bluetape4k-aws/pull/61)).
- CI uses path filtering and retry configuration to reduce unnecessary test work and improve transient failure handling ([PR #23](https://github.com/bluetape4k/bluetape4k-aws/pull/23)).
- Test code migrated from Kluent to `bluetape4k-assertions` ([PR #22](https://github.com/bluetape4k/bluetape4k-aws/pull/22)).

### Planned

- Exposed-first AWS database integration will use the `bluetape4k-exposed`
  project as the database access surface, not standalone raw Exposed APIs
  ([#74](https://github.com/bluetape4k/bluetape4k-aws/issues/74)).
- Spring Boot and Ktor database adapters are tracked separately for
  `bluetape4k-exposed` auto-configuration and `AwsExposedPlugin`
  ([#75](https://github.com/bluetape4k/bluetape4k-aws/issues/75),
  [#76](https://github.com/bluetape4k/bluetape4k-aws/issues/76)).
- RDS IAM auth token support and Spring Boot/Ktor Exposed database examples are
  planned after the shared database foundation
  ([#77](https://github.com/bluetape4k/bluetape4k-aws/issues/77),
  [#82](https://github.com/bluetape4k/bluetape4k-aws/issues/82)).
- AWSpring comparison follow-ups are tracked for S3 advanced transfer/config,
  SQS parity hardening, SNS HTTP endpoint/SMS support, and Kinesis/DynamoDB
  Streams coroutine `Flow` support
  ([#78](https://github.com/bluetape4k/bluetape4k-aws/issues/78),
  [#79](https://github.com/bluetape4k/bluetape4k-aws/issues/79),
  [#80](https://github.com/bluetape4k/bluetape4k-aws/issues/80),
  [#81](https://github.com/bluetape4k/bluetape4k-aws/issues/81)).
