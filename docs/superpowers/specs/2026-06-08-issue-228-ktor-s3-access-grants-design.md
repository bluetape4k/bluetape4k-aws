# Issue #228 Ktor S3 Access Grants Spec

Date: 2026-06-08
Issue: #228
Work type: Type B Fast Track

## Context

`aws-ktor` already has a REST-first `S3KtorClient` for object operations and
separate Ktor server plugins for AWS SDK Java v2 services such as CloudWatch.
Issue #227 added Spring Boot S3 Access Grants through the AWS SDK Java v2 S3
Control module. Ktor should reuse that service boundary: Access Grants belongs
to S3 Control, not to the ordinary S3 REST client.

## Goal

Add an optional Ktor S3 Access Grants integration that exposes coroutine
operations for common read/data-access methods, supports plugin-owned and
caller-owned clients, and inherits `AwsKtorDefaults` where appropriate.

## Non-Goals

- Do not add S3 Access Grants methods to `S3KtorClient`.
- Do not make `software.amazon.awssdk:s3control` a mandatory runtime
  dependency.
- Do not wrap administrative create/update/delete operations in this issue.
  Those remain available through the raw S3 Control client.
- Do not add live AWS integration tests; Access Grants needs account-level AWS
  setup and is outside the emulator matrix.

## Public API Shape

- Package: `io.bluetape4k.aws.ktor.s3.accessgrants`.
- `S3AccessGrantsKtorOperations`: suspend facade for:
  - `getDataAccess`
  - `listCallerAccessGrants`
  - `listAccessGrants`
  - `listAccessGrantsInstances`
  - `listAccessGrantsLocations`
- `S3AccessGrantsKtorTemplate`: AWS SDK Java v2 `S3ControlAsyncClient` backed
  implementation using `CompletableFuture.await()`.
- `S3AccessGrantsKtorPlugin`: Ktor application plugin.
- `S3AccessGrantsKtorPluginConfig`: enabled flag, caller-owned operations,
  caller-owned async client, region, endpoint override, credentials provider,
  and service customizers.
- `Application.s3AccessGrants()` and `Application.s3AccessGrantsOrNull()`.

## Dependency Boundary

Add `libs.aws2.s3control` as `compileOnly` and `testImplementation` in
`aws-ktor`. Consumers must add `runtimeOnly("software.amazon.awssdk:s3control")`
or equivalent when installing the plugin or using the template.

## Lifecycle

The plugin stores a runtime and operations facade only when enabled. Injected
operations bypass client creation and validation. Injected clients remain
application-owned. Plugin-created async clients are closed once on
`ApplicationStopping` using the existing Ktor plugin lifecycle pattern.

## Defaults And Customizers

Plugin-created clients inherit:

- region from plugin config, then `AwsKtorDefaults.region`
- endpoint override from plugin config, then `AwsKtorDefaults.javaEndpointOverride`
- credentials provider from plugin config, then `AwsKtorDefaults.javaCredentialsProvider`
- shared S3 Control customizers, then service-local customizers

Endpoint override requires a non-blank region.

## Documentation

Update `README.md` and `README.ko.md` with:

- runtime dependency snippet
- `AwsKtorCore` + `S3AccessGrantsKtorPlugin` minimal install example
- direct `S3AccessGrantsKtorTemplate` caller-owned client example or note
- explicit boundary that admin operations use raw S3 Control clients

## Verification

- Compile `:bluetape4k-aws-ktor`.
- Run focused Access Grants tests.
- Run related Ktor defaults/plugin regression tests.
- Run `git diff --check`.
- Run 7-tier review with `P0=0`, `P1=0`.
