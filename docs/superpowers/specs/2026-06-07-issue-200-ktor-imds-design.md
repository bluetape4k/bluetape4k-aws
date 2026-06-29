# Issue #200 - Ktor IMDS Helpers Spec

Date: 2026-06-07
Issue: #200 `feat(aws-ktor): add optional EC2 Instance Metadata Service helpers`
Work type: Type A full feature

## Context

`aws-spring-boot` now has optional EC2 Instance Metadata Service support from
#196 / PR #277. `aws-ktor` has shared application AWS defaults through
`AwsKtorCore`, plus Ktor integrations for SigV4, S3, SQS, DynamoDB, and
AWS-backed Exposed databases. It does not expose an IMDS package yet.

Current evidence:

- `gradle/libs.versions.toml` has `aws2-imds`.
- `aws-ktor/build.gradle.kts` has no IMDS dependency yet.
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor` has no `imds` package.
- Spring Boot IMDS public behavior is already defined by `ImdsOperations`,
  `ImdsCoroutinesTemplate`, bounded request timeout, and no credential document
  exposure.
- `AwsKtorCore` stores shared region, endpoint override, credentials providers,
  clocks, engines, and service customizers in Ktor application attributes.

## Goals

- Add optional Ktor IMDS helpers backed by AWS SDK v2
  `Ec2MetadataAsyncClient`.
- Provide coroutine operations for safe EC2 metadata reads.
- Provide a Ktor application plugin that stores IMDS operations in application
  attributes.
- Keep startup safe outside EC2: installing the plugin must not call IMDS.
- Bound every metadata operation with a request timeout.
- Close only plugin-created IMDS clients on application stopping.
- Document EC2-only behavior and credential non-exposure.

## Non-Goals

- Do not make IMDS the default credentials strategy.
- Do not expose temporary credential documents through APIs, routes, logs,
  metrics, DTOs, or examples.
- Do not add Spring Boot APIs or property binding.
- Do not require live EC2 or real AWS credential tests.
- Do not inherit `AwsKtorCore.endpointOverride` automatically. IMDS endpoint
  configuration is metadata-service-specific, not a normal AWS service endpoint.

## Public API

Add package `io.bluetape4k.aws.ktor.imds`.

Expected types:

- `ImdsKtorOperations`
  - `suspend fun get(path: String): String`
  - `suspend fun getList(path: String): List<String>`
  - common helpers: `instanceId`, `instanceType`, `availabilityZone`, `region`,
    `localIpv4`, `iamRoleNames`.
- `ImdsKtorTemplate`
  - Delegates to AWS SDK v2 `Ec2MetadataAsyncClient`.
  - Applies `withTimeout(requestTimeout.toMillis())` around every call.
  - Validates paths with bluetape4k validation helpers.
- `ImdsKtorPluginConfig`
  - `enabled`
  - `ec2MetadataAsyncClient`
  - `imdsOperations`
  - `endpoint`
  - `endpointMode`
  - `tokenTtl`
  - `requestTimeout`
  - `retries`
  - client builder customizers
- `ImdsKtorRuntime`
  - Holds operations and optional owned client lifecycle.
- `ImdsKtorPlugin`
  - Stores runtime/operations in application attributes when enabled.
  - Does not call metadata endpoints during install/startup.
- Accessors:
  - `fun Application.imds(): ImdsKtorOperations`
  - `fun Application.imdsOrNull(): ImdsKtorOperations?`

## Design Rules

- Add `compileOnly(libs.aws2.imds)` and matching `testImplementation`.
- Follow current Ktor plugin patterns: config class, plugin, runtime, attribute
  key, and application accessor.
- Use `Ec2MetadataRetryPolicy.none()` when retries are zero.
- Use `endpoint` when explicitly configured, otherwise `endpointMode`.
- Use injected `ImdsKtorOperations` directly and create no client.
- Use injected `Ec2MetadataAsyncClient` without closing it.
- Close plugin-created `Ec2MetadataAsyncClient` on `ApplicationStopping`.
- Keep helpers low-level and explicit. `iamRoleNames()` may list role names,
  but public helpers must not read role credential documents.

## Tests

Required tests:

- Plugin installs runtime/operations and stores attributes when enabled.
- Plugin does not store attributes when disabled.
- `imdsOrNull()` returns null when absent and `imds()` fails when absent.
- Injected operations win and no client is created.
- Injected client wins and is not called during startup.
- Plugin-created client can be closed on stopping without closing injected
  clients.
- Config validates positive `tokenTtl`, positive `requestTimeout`, and
  non-negative `retries`.
- Template validates blank paths, normalizes paths, parses string/list
  responses, and times out non-completing futures.

## Documentation

Update README locale set:

- `README.md`
- `README.ko.md`
- `aws-ktor/README.md`
- `aws-ktor/README.ko.md`

Documentation must cover dependency, plugin install, operations usage,
startup-safe behavior, timeout behavior, and credential non-exposure.

## DoD

- Spec review: `P0=0`, `P1=0`.
- Plan review: `P0=0`, `P1=0`.
- Focused IMDS tests pass.
- Full `:bluetape4k-aws-ktor:test` passes.
- `:bluetape4k-aws-ktor:compileKotlin` passes.
- `dependencyInsight` confirms `software.amazon.awssdk:imds:2.46.0`.
- `git diff --check` passes.
- PR body ends with `## DoD Status`.
