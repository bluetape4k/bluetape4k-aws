# Changelog

All notable changes to `bluetape4k-aws` are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
This project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.0] - 2026-06-27

### Added

- Added Ktor DynamoDB integration and emulator-backed Ktor DynamoDB examples
  ([#179](https://github.com/bluetape4k/bluetape4k-aws/issues/179)).
- Added optional Spring Boot DynamoDB Accelerator (DAX) client integration,
  keeping emulator tests on the ordinary DynamoDB client path
  ([#191](https://github.com/bluetape4k/bluetape4k-aws/issues/191)).
- Added Spring Boot CloudWatch and CloudWatch Logs auto-configuration,
  coroutine operation templates, and Micrometer snapshot publishing helpers
  ([#194](https://github.com/bluetape4k/bluetape4k-aws/issues/194)).
- Added Ktor CloudWatch and CloudWatch Logs plugins for explicit metric/log
  publishing and buffered shutdown flushes
  ([#201](https://github.com/bluetape4k/bluetape4k-aws/issues/201)).
- Added EC2 Instance Metadata Service helpers for Spring Boot and Ktor without
  using IMDS as a credential strategy
  ([#196](https://github.com/bluetape4k/bluetape4k-aws/issues/196),
  [#200](https://github.com/bluetape4k/bluetape4k-aws/issues/200)).
- Added optional S3 Access Grants support for Spring Boot and Ktor through the
  AWS SDK v2 S3 Control boundary
  ([#227](https://github.com/bluetape4k/bluetape4k-aws/issues/227),
  [#228](https://github.com/bluetape4k/bluetape4k-aws/issues/228)).
- Added optional S3 Vectors support across the Java SDK facade, Spring Boot
  auto-configuration, and Ktor plugin integration
  ([#229](https://github.com/bluetape4k/bluetape4k-aws/issues/229)).
- Added optional Micrometer observability adapters for SQS and S3 operation
  timing without making metrics mandatory for every consumer
  ([#230](https://github.com/bluetape4k/bluetape4k-aws/issues/230)).

### Changed

- Opened the `0.4.0` development line after the `0.3.1` stable release and
  aligned local bluetape4k BOM refs to `bluetape4k-bom:1.11.0-SNAPSHOT` and
  `bluetape4k-exposed-bom:1.11.0-SNAPSHOT`.
- Adopted shared `bluetape4k-ktor-*` modules in AWS Ktor integrations and
  examples instead of carrying local Ktor helper duplicates
  ([#244](https://github.com/bluetape4k/bluetape4k-aws/issues/244),
  [#245](https://github.com/bluetape4k/bluetape4k-aws/issues/245)).
- Moved AWS emulator-aware tests and examples to a Floci-first default while
  preserving explicit LocalStack fallback runs for API coverage gaps
  ([#239](https://github.com/bluetape4k/bluetape4k-aws/issues/239),
  [#241](https://github.com/bluetape4k/bluetape4k-aws/issues/241)).
- Refreshed the root README architecture, component, and service coverage
  diagrams for the 0.4.0 service surface
  ([#266](https://github.com/bluetape4k/bluetape4k-aws/pull/266),
  [#291](https://github.com/bluetape4k/bluetape4k-aws/pull/291)).
- Prepared 0.4.0 release documentation and code-pattern preflight evidence for
  the coordinated dependencies train
  ([#292](https://github.com/bluetape4k/bluetape4k-aws/issues/292),
  [#294](https://github.com/bluetape4k/bluetape4k-aws/issues/294)).

### Fixed

- Closed final IMDS and DAX review gaps, including injected-operation
  validation and DAX concurrency validation
  ([#281](https://github.com/bluetape4k/bluetape4k-aws/issues/281),
  [#282](https://github.com/bluetape4k/bluetape4k-aws/issues/282),
  [#283](https://github.com/bluetape4k/bluetape4k-aws/issues/283)).
- Hardened CI and Nightly snapshot dependency refresh behavior against Central
  snapshot metadata and cache refresh failures
  ([#251](https://github.com/bluetape4k/bluetape4k-aws/issues/251),
  [#253](https://github.com/bluetape4k/bluetape4k-aws/issues/253),
  [#255](https://github.com/bluetape4k/bluetape4k-aws/issues/255),
  [#257](https://github.com/bluetape4k/bluetape4k-aws/issues/257),
  [#259](https://github.com/bluetape4k/bluetape4k-aws/issues/259),
  [#261](https://github.com/bluetape4k/bluetape4k-aws/issues/261),
  [#263](https://github.com/bluetape4k/bluetape4k-aws/issues/263)).

## [0.3.1] - 2026-06-01

### Changed

- Updated `aws-exposed` to consume
  `io.github.bluetape4k.exposed:bluetape4k-exposed-bom:1.10.0`.
- Changed the `bluetape4k-exposed-bom` platform import from API scope to
  implementation scope so the AWS Exposed module does not export the BOM
  platform to consumers.
- Kept the concrete `bluetape4k-exposed-jdbc` API dependency versioned directly
  from the same catalog line so downstream modules do not rely on API-scoped BOM
  propagation.
- Updated the default bluetape4k dependencies catalog ref to
  `catalog/2026-06-01-00` for release workflow alignment.

## [0.3.0] - 2026-05-27

### Added

- Shared Spring Boot AWS core properties and client customizer foundation for
  S3 and SQS production integrations ([#190](https://github.com/bluetape4k/bluetape4k-aws/issues/190)).
- Advanced Spring Boot S3 support for encryption, config reload, access grants,
  vector operations, and content helpers ([#192](https://github.com/bluetape4k/bluetape4k-aws/issues/192)).
- Advanced Spring Boot SQS support for typed conversion, manual acknowledgement,
  retry policies, listener interceptors, and observability hooks
  ([#193](https://github.com/bluetape4k/bluetape4k-aws/issues/193)).
- Shared Ktor AWS defaults and client customizer hooks
  ([#197](https://github.com/bluetape4k/bluetape4k-aws/issues/197)).
- Advanced Ktor S3 and SQS integrations plus runnable advanced examples
  ([#199](https://github.com/bluetape4k/bluetape4k-aws/issues/199),
  [#203](https://github.com/bluetape4k/bluetape4k-aws/issues/203),
  [#207](https://github.com/bluetape4k/bluetape4k-aws/issues/207)).
- Spring Boot S3 and SQS AWSpring-parity example scenarios
  ([#206](https://github.com/bluetape4k/bluetape4k-aws/issues/206)).

### Changed

- Prepared the 0.3.0 release line to consume
  `io.github.bluetape4k:bluetape4k-bom:1.9.2` and
  `io.github.bluetape4k.exposed:bluetape4k-exposed-bom:1.9.2`.
- Refreshed README architecture, flow, and sequence diagrams across AWS Java,
  AWS Kotlin, AWS Ktor, AWS Spring Boot, AWS Exposed, and examples.

### Fixed

- Stabilized SNS-to-SQS fanout LocalStack coverage
  ([#182](https://github.com/bluetape4k/bluetape4k-aws/issues/182)).

## [0.2.0] - 2026-05-22

### Added

- `aws-exposed` foundation module for shared Exposed database registry,
  settings, and AWS-backed credential/property resolution ([#74](https://github.com/bluetape4k/bluetape4k-aws/issues/74)).
- Spring Boot Exposed database auto-configuration for AWS-backed JDBC
  integrations ([#75](https://github.com/bluetape4k/bluetape4k-aws/issues/75)).
- Ktor `AwsExposedPlugin` lifecycle integration for AWS-backed Exposed
  databases ([#76](https://github.com/bluetape4k/bluetape4k-aws/issues/76)).
- `aws-exposed` RDS IAM authentication token provider for Hikari-backed Exposed
  databases, including refresh-aware token caching and AWS SDK Java v2
  `RdsUtilities` integration ([#77](https://github.com/bluetape4k/bluetape4k-aws/issues/77)).
- Spring Boot and Ktor Exposed AWS database example modules ([#82](https://github.com/bluetape4k/bluetape4k-aws/issues/82)).
- Spring Boot SES email sender support ([#7](https://github.com/bluetape4k/bluetape4k-aws/issues/7)).

### Changed

- Prepared the 0.2.0 release line to consume `io.github.bluetape4k:bluetape4k-bom:1.9.0` and `io.github.bluetape4k.exposed:bluetape4k-exposed-bom:1.9.0`.
- Imported `bluetape4k-exposed-bom` for Exposed helper artifacts instead of pinning direct Exposed helper versions.

### Fixed

- Hardened secret redaction across AWS Exposed serialization paths.

## [0.1.1] - 2026-05-22

### Added

- Added S3 async and AWS Kotlin SDK object listing Flow helpers that automatically traverse `ListObjectsV2` pagination ([#145](https://github.com/bluetape4k/bluetape4k-aws/issues/145)).

### Changed

- Prepared the 0.1.1 patch release line to consume `io.github.bluetape4k:bluetape4k-bom:1.9.0`.

### Fixed

- Fixed `forceDeleteBucket` so versioned S3 buckets remove object versions and delete markers before bucket deletion ([#147](https://github.com/bluetape4k/bluetape4k-aws/issues/147)).

## [0.1.0] - 2026-05-16

### Added

- Spring Boot SNS direct SMS publishing plus HTTP(S) endpoint payload parsing
  and token-based subscription confirmation ([PR #95](https://github.com/bluetape4k/bluetape4k-aws/pull/95)).
- Optional Spring Boot S3 transfer operations backed by AWS SDK v2
  `S3TransferManager`, without forcing CRT dependencies on basic S3 users
  ([PR #94](https://github.com/bluetape4k/bluetape4k-aws/pull/94)).
- Spring Boot SQS/SNS fanout examples are wired for Spring AOT processing
  alongside the existing Spring Boot S3 example ([PR #93](https://github.com/bluetape4k/bluetape4k-aws/pull/93)).
- Ktor DynamoDB server plugin and repository facade built on `:aws-kotlin` and
  the official AWS SDK for Kotlin ([PR #87](https://github.com/bluetape4k/bluetape4k-aws/pull/87)).
- Secrets Manager and Parameter Store refresh support for remote Environment
  sources ([PR #84](https://github.com/bluetape4k/bluetape4k-aws/pull/84)).
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

- Spring Boot SQS listener/template parity now includes FIFO metadata exposure,
  explicit send request fields, and AOT-safe example coverage ([PR #93](https://github.com/bluetape4k/bluetape4k-aws/pull/93)).
- Dependency baselines were refreshed for AWS SDK, Ktor 3.5, Gradle 9.5.1, and
  SLF4J 2.0.18 ([PR #89](https://github.com/bluetape4k/bluetape4k-aws/pull/89),
  [PR #90](https://github.com/bluetape4k/bluetape4k-aws/pull/90),
  [PR #91](https://github.com/bluetape4k/bluetape4k-aws/pull/91),
  [PR #92](https://github.com/bluetape4k/bluetape4k-aws/pull/92)).
- GitHub Actions and Gradle Actions caching were refreshed, and the CI secret
  scan installer was stabilized after the dependency update wave
  ([PR #88](https://github.com/bluetape4k/bluetape4k-aws/pull/88)).
- Standardized AWS modules on `bluetape4k-jackson3` and moved direct Jackson
  helper usage to `tools.jackson`.
- Refreshed README workbench image and aligned license text on MIT ([PR #72](https://github.com/bluetape4k/bluetape4k-aws/pull/72), [PR #70](https://github.com/bluetape4k/bluetape4k-aws/pull/70)).
- Consolidated PR review gate metrics documentation ([PR #69](https://github.com/bluetape4k/bluetape4k-aws/pull/69)).
- Hardened review findings across `aws`, `aws-kotlin`, `aws-spring-boot`, and `aws-ktor` tests ([PR #64](https://github.com/bluetape4k/bluetape4k-aws/pull/64), [PR #65](https://github.com/bluetape4k/bluetape4k-aws/pull/65), [PR #66](https://github.com/bluetape4k/bluetape4k-aws/pull/66), [PR #67](https://github.com/bluetape4k/bluetape4k-aws/pull/67)).
- Unified `aws-spring-boot` tests on `bluetape4k-assertions` ([PR #63](https://github.com/bluetape4k/bluetape4k-aws/pull/63)).
- Refreshed WIP queue and review-gate metrics documentation ([PR #56](https://github.com/bluetape4k/bluetape4k-aws/pull/56), [PR #61](https://github.com/bluetape4k/bluetape4k-aws/pull/61)).
- CI uses path filtering and retry configuration to reduce unnecessary test work and improve transient failure handling ([PR #23](https://github.com/bluetape4k/bluetape4k-aws/pull/23)).
- Test code migrated from Kluent to `bluetape4k-assertions` ([PR #22](https://github.com/bluetape4k/bluetape4k-aws/pull/22)).

### Fixed

- Remote Environment refresh now keeps stable snapshots during reload and avoids
  refresh race regressions ([PR #86](https://github.com/bluetape4k/bluetape4k-aws/pull/86)).
- Removed deprecated `S3Factory`, `SesFactory`, `SnsFactory`, and `SqsFactory`
  objects before first public release; use `S3ClientFactory`, `SesClientFactory`,
  `SnsClientFactory`, and `SqsClientFactory` respectively ([#98](https://github.com/bluetape4k/bluetape4k-aws/issues/98), [PR #113](https://github.com/bluetape4k/bluetape4k-aws/pull/113)).
- `@Disabled` test annotations now include issue references and English rationale
  for emulator-limited and out-of-band-protocol tests ([#99](https://github.com/bluetape4k/bluetape4k-aws/issues/99), [#100](https://github.com/bluetape4k/bluetape4k-aws/issues/100), [PR #114](https://github.com/bluetape4k/bluetape4k-aws/pull/114)).

### 0.2.0 Roadmap

At the 0.1.0 release, the following items were deferred to the 0.2.0 line:

- Exposed-first AWS database integration ([#74](https://github.com/bluetape4k/bluetape4k-aws/issues/74)).
- Spring Boot and Ktor Exposed auto-configuration and `AwsExposedPlugin`
  ([#75](https://github.com/bluetape4k/bluetape4k-aws/issues/75),
  [#76](https://github.com/bluetape4k/bluetape4k-aws/issues/76)).
- RDS IAM auth token provider and Exposed database examples
  ([#77](https://github.com/bluetape4k/bluetape4k-aws/issues/77),
  [#82](https://github.com/bluetape4k/bluetape4k-aws/issues/82)).
- Kinesis and DynamoDB Streams coroutine `Flow` support ([#81](https://github.com/bluetape4k/bluetape4k-aws/issues/81)).
- Spring Boot SES sender ([#7](https://github.com/bluetape4k/bluetape4k-aws/issues/7)).
- Ktor integrations migration toward `:aws-kotlin` ([#85](https://github.com/bluetape4k/bluetape4k-aws/issues/85)).
- LocalStack-compatible test strategy for SES V2 and SNS token flow ([#105](https://github.com/bluetape4k/bluetape4k-aws/issues/105)).
- Disabled-test registry and CI release gate ([#106](https://github.com/bluetape4k/bluetape4k-aws/issues/106)).
