# Changelog

All notable changes to `bluetape4k-aws` are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
This project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Ktor SigV4 client plugin for AWS request signing ([PR #27](https://github.com/bluetape4k/bluetape4k-aws/pull/27)).
- Ktor S3 coroutine client and LocalStack-oriented example coverage included in Nightly ([PR #28](https://github.com/bluetape4k/bluetape4k-aws/pull/28)).
- Spring Boot 4 S3 auto-configuration and coroutine operations template ([PR #29](https://github.com/bluetape4k/bluetape4k-aws/pull/29)).
- Spring Boot 4 SQS coroutine operations template, listener annotation, and polling container ([PR #30](https://github.com/bluetape4k/bluetape4k-aws/pull/30)).
- Spring Boot 4 DynamoDB enhanced async client auto-configuration and coroutine repository base ([PR #31](https://github.com/bluetape4k/bluetape4k-aws/pull/31)).
- `bluetape4k-aws-bom` BOM module for AWS library consumers ([PR #24](https://github.com/bluetape4k/bluetape4k-aws/pull/24)).
- English and Korean README files for the AWS BOM module ([PR #25](https://github.com/bluetape4k/bluetape4k-aws/pull/25)).
- GitHub Actions workflows for CI, nightly, snapshot, release, and code-quality checks ([PR #19](https://github.com/bluetape4k/bluetape4k-aws/pull/19)).

### Changed

- CI uses path filtering and retry configuration to reduce unnecessary test work and improve transient failure handling ([PR #23](https://github.com/bluetape4k/bluetape4k-aws/pull/23)).
- Test code migrated from Kluent to `bluetape4k-assertions` ([PR #22](https://github.com/bluetape4k/bluetape4k-aws/pull/22)).
