# Issue #196 - Spring Boot IMDS Integration Spec

Date: 2026-06-07
Issue: #196 `feat(aws-spring-boot): add optional EC2 Instance Metadata Service integration`
Work type: Type A full feature

## Context

`aws-spring-boot` already has shared AWS defaults and optional service
auto-configurations for S3, SQS, SNS, KMS, DynamoDB, SES, and CloudWatch. It
does not expose an EC2 Instance Metadata Service facade yet. Applications that
run on EC2 still need to bind directly to AWS SDK IMDS calls when they want
metadata such as instance id, region, availability zone, instance type, or IAM
role names.

Current evidence:

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring` has no `imds`
  package.
- `gradle/libs.versions.toml` has AWS SDK v2 aliases for `ec2`, `sts`, and
  service clients, but not `software.amazon.awssdk:imds`.
- Maven Central serves `software.amazon.awssdk:imds:2.46.0`, matching the
  repository AWS SDK v2 version line.
- AWS SDK v2 `Ec2MetadataAsyncClient` exposes `get(String)`,
  `endpoint(URI)`, `endpointMode(EndpointMode)`, `tokenTtl(Duration)`,
  `retryPolicy(Ec2MetadataRetryPolicy)`, and async HTTP client configuration.

## Goals

- Add optional Spring Boot auto-configuration for AWS SDK v2 IMDS.
- Provide a coroutine-friendly `ImdsOperations` facade over
  `Ec2MetadataAsyncClient`.
- Keep application startup safe outside EC2: creating beans must not call IMDS.
- Bound all metadata calls with an operation timeout and conservative retry
  defaults.
- Document EC2-only behavior and make credential non-exposure explicit.

## Non-Goals

- Do not replace `DefaultCredentialsProvider` with IMDS-specific credential
  logic.
- Do not expose temporary credential values through APIs, logs, actuator data,
  examples, or README snippets.
- Do not add Ktor IMDS support; issue #200 owns that adapter.
- Do not require live EC2 or real AWS credential integration tests.

## Public API

Add package `io.bluetape4k.aws.spring.imds`.

Expected types:

- `ImdsProperties`
  - Prefix: `bluetape4k.aws.imds`
  - Fields: `enabled`, `endpoint`, `endpointMode`, `tokenTtl`,
    `requestTimeout`, `retries`.
  - Defaults: enabled, IPv4 endpoint mode, six-hour token TTL, short operation
    timeout, zero or very small retry count.
- `ImdsOperations`
  - `suspend fun get(path: String): String`
  - `suspend fun getList(path: String): List<String>`
  - common helpers: `instanceId`, `availabilityZone`, `region`,
    `instanceType`, `localIpv4`, `iamRoleNames`.
- `ImdsCoroutinesTemplate`
  - Delegates to `Ec2MetadataAsyncClient`.
  - Applies `withTimeout(properties.requestTimeout)` around every call.
  - Validates caller paths with bluetape4k validation helpers.
- `ImdsAutoConfiguration`
  - Guarded by `Ec2MetadataAsyncClient` and `SdkAsyncHttpClient` classes.
  - Creates `Ec2MetadataAsyncClient` and `ImdsOperations` when enabled.
  - Backs off for user-provided client or operations beans.

## Design Rules

- Use `compileOnly(libs.aws2.imds)` and matching `testImplementation`.
- Prefer the existing AWS Spring Boot pattern: one properties class, one
  auto-configuration class, operations interface, coroutine template, and
  `AutoConfiguration.imports` registration.
- Do not perform a metadata probe during auto-configuration.
- Use `Ec2MetadataRetryPolicy.none()` or an equivalent bounded retry policy by
  default.
- Let `SdkAsyncHttpClient` beans be reused when available.
- Keep the IMDS path helpers low-level and explicit. Do not infer credentials
  from the IAM security-credentials endpoint.

## Tests

Required tests:

- Auto-configuration registers client and operations when enabled.
- Auto-configuration backs off when disabled.
- User-provided `Ec2MetadataAsyncClient` and `ImdsOperations` beans win.
- Filtered class loader disables IMDS wiring when SDK IMDS classes are absent.
- Properties bind endpoint, endpoint mode, token TTL, request timeout, and
  retry count.
- `ImdsCoroutinesTemplate` validates blank paths.
- `ImdsCoroutinesTemplate` converts string and list responses.
- Timeout handling turns a non-completing future into a timeout failure without
  hanging.

## Documentation

Update the root and module README locale set:

- Mention Spring Boot IMDS support in the service coverage and dependency
  sections.
- Add a short configuration snippet for `bluetape4k.aws.imds`.
- Add a usage example with `ImdsOperations`.
- Explain that IMDS is EC2-only and should not be used as an EKS/IRSA
  replacement.

## DoD

- Spec review: `P0=0`, `P1=0`.
- Plan review: `P0=0`, `P1=0`.
- Focused IMDS tests pass.
- Full `:bluetape4k-aws-spring-boot:test` passes.
- `:bluetape4k-aws-spring-boot:compileKotlin` passes.
- `dependencyInsight` confirms `software.amazon.awssdk:imds:2.46.0`.
- `git diff --check` passes.
- PR body ends with `## DoD Status`.
