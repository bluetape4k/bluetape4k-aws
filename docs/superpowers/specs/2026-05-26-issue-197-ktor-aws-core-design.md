# Issue #197 Ktor AWS Core Design

Date: 2026-05-26
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/197
Branch: `feat/197-ktor-aws-core`

## Goal

Add an opt-in shared AWS defaults layer for `aws-ktor` so S3, SQS, DynamoDB,
and AWS-backed Exposed Ktor integrations can document one application-level
configuration model while preserving existing service-specific APIs.

## Current Evidence

- `S3KtorClient` owns an internal Ktor `HttpClient` only when created by
  `s3KtorClientOf`.
- `SqsConsumer` previously required an injected `SqsAsyncClient` and never
  closed it.
- `DynamoDbKtorPlugin` already supports injected vs plugin-created client
  ownership.
- `AwsExposedPlugin` owns the Exposed registry lifecycle, not AWS SDK clients.
- `aws-ktor/README.md` had an SQS sequence image but no module-level
  architecture image explaining integration boundaries.

## Design

Introduce `AwsKtorCore`, a Ktor application plugin that stores
`AwsKtorDefaults` in application attributes:

- `region`
- `endpointOverride`
- AWS SDK Java v2 credentials provider
- AWS SDK for Kotlin credentials provider
- signing clock
- AWS SDK for Kotlin HTTP engine
- customizers for Ktor `HttpClient`, SQS async client builders, and DynamoDB
  client builders

Service-specific settings override shared defaults.
`AwsKtorDefaults` follows the bluetape4k value-object pattern by extending
`AbstractValueObject`. Runtime collaborators are transient, endpoint override is
stored as serializable string state, and public access still exposes Ktor `Url`.

## Scope

- Add `AwsKtorCore` and defaults/customizer types.
- Add an S3 factory overload that accepts `AwsKtorDefaults`.
- Let `SqsConsumer` create a plugin-owned SQS client when no client is injected.
- Let `DynamoDbKtorPlugin` inherit shared defaults for plugin-created clients.
- Update English/Korean READMEs.
- Add a Graphviz-grounded architecture PNG/SVG for `aws-ktor`.

## Non-Goals

- No Spring Boot or awspring dependency.
- No generic AWS client abstraction that hides service-specific behavior.
- No automatic Exposed AWS SDK client creation; Exposed stays registry-oriented.

## Risks

- Public compile-only AWS service types in customizer interfaces require
  applications to add the matching runtime dependency when they use that
  integration.
- Plugin-created SQS clients must be closed only once and must not change
  injected-client ownership.
- The README architecture diagram must be source-derived and visually verified,
  not a Mermaid recolor.
