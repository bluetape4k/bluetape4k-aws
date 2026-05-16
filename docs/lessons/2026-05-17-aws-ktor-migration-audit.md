# aws-ktor Migration Audit: Java SDK v2 Exposure Classification

**Date**: 2026-05-17
**Issue**: #85
**Branch**: docs/aws-ktor-migration-design

## Summary

Audited `aws-ktor` for all Java SDK v2 (`software.amazon.awssdk.*`) type exposures
in the public API and classified each integration for migration to AWS Kotlin SDK.

## Key Findings

### Java SDK v2 Exposure (3 public-API surfaces)

1. **AwsSigV4Plugin ecosystem**: `AwsCredentialsProvider`, `AwsV4HttpSigner` in public config.
2. **S3KtorClient**: Same credentials/signer types in constructor and factory.
3. **SQS Consumer**: `SqsAsyncClient`, `Message`, `SendMessageResponse` in public API.

### Already on AWS Kotlin SDK (DynamoDB)

DynamoDB Ktor integration was designed Kotlin-first from the start. No migration needed.

## Decisions

| Integration | Decision | Reason |
|---|---|---|
| AwsSigV4Plugin | Keep Java SDK v2 | No public signing API in Smithy Kotlin for external HTTP clients |
| S3KtorClient | Keep Java SDK v2 | Tied to SigV4 decision |
| SQS Consumer | Defer to 0.2.0 | Breaking API change; no urgency for 0.1.0 |
| DynamoDB | Already migrated | No action needed |

## Lesson: AWS Kotlin SDK Signing API — Not Drop-in, But Not Absent

The AWS Kotlin SDK (`aws.smithy.kotlin:aws-signing-default`) **does** expose
`AwsSigner` and `DefaultAwsSigner` as public types:

```kotlin
suspend fun sign(request: HttpRequest, config: AwsSigningConfig): AwsSigningResult<HttpRequest>
```

However, Smithy's `HttpRequest` is a different type from Ktor's
`HttpRequestBuilder`. Using Smithy signing in a Ktor plugin requires a
Ktor ↔ Smithy `HttpRequest`/body/header adapter layer — there is no official
bridge, and building one adds meaningful integration cost.

**Lesson**: Do not claim Smithy signing has "no public API". The correct claim
is: "not drop-in for the current Ktor plugin; requires a Ktor ↔ Smithy
`HttpRequest` adapter and API compatibility work."

Java SDK v2's `http-auth-aws` module (`AwsV4HttpSigner`) remains the only
out-of-the-box stable option for a Ktor-based SigV4 plugin today.

Track the upstream Smithy Kotlin project for an official Ktor adapter or public
signing bridge before planning SigV4 migration.

## Policy: New Integrations

All new `aws-ktor` integrations added after #85 must use AWS Kotlin SDK by default.
Java SDK v2 is only permitted where AWS Kotlin SDK lacks stable parity (SigV4 case).
