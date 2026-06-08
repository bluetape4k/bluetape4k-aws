# Issue 228 Ktor S3 Access Grants

## Context

#227 added Spring Boot S3 Access Grants support through AWS SDK v2 S3 Control.
The Ktor module needed the same boundary without moving Access Grants into
`S3KtorClient`, which should stay focused on object REST operations.

## Decision

Add an optional `S3AccessGrantsKtorPlugin` backed by `S3ControlAsyncClient`.
Expose suspend read/data-access and discovery operations, inherit
`AwsKtorCore` defaults/customizers, and keep administrative create/update/delete
operations on the raw S3 Control client.

## Outcome

The module now has:

- `S3AccessGrantsKtorOperations` and `S3AccessGrantsKtorTemplate`.
- Plugin config/runtime lifecycle with caller-owned operations, caller-owned
  clients, plugin-owned clients, disabled mode, and customizer ordering.
- English/Korean README updates with a new Access Grants flow diagram.

## Verification

- `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:compileTestKotlin --no-daemon --max-workers=1` succeeded.
- `./gradlew :bluetape4k-aws-ktor:test --tests '*AwsKtorCoreTest' --tests '*S3AccessGrants*' --rerun-tasks --no-daemon --max-workers=1` succeeded with 17 passing tests.
- Diagram gate printed `badEndpointAngle=0`, `badBends=0`, `interiorCrossings=0`, `marginImbalance=0`, `titleGap=54`.
- `xmllint --noout` passed for the SVG and sketch SVG.
- Diagram grep found no `/Users/debop`, `Inter`, `Arial`, or `Helvetica`.
- Rendered PNG was inspected at `docs/images/readme-diagrams/aws-ktor-s3-access-grants-flow-01.png`.
- `git diff --check` passed.

## Future Guard

When adding AWS service-level Ktor plugins, keep raw AWS SDK administrative APIs
outside the Ktor facade unless request-handling code needs them. README updates
for public plugin behavior should include both localized prose and a matching
diagram asset.
