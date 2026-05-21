# Issue #145 S3 listAllObjects Flow

- Date: 2026-05-21
- Scope: `aws` S3 coroutine extensions
- Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/145

## Context

`S3AsyncClient` coroutine helpers covered object get/put/delete-style operations
but did not provide a paginated list helper. Callers using one `ListObjectsV2`
request could silently stop after the first S3 page.

## Decision

Add `S3AsyncClient.listAllObjects(bucket, prefix)` as a cold `Flow<S3Object>`.
The flow performs `ListObjectsV2` only when collected and follows
`nextContinuationToken` until S3 reports that results are no longer truncated.

## Outcome

The core `aws` module now exposes the same pagination primitive that higher
layers need, without depending on Spring template APIs. Module README examples
show both upload/download and paginated listing.

## Verification

- `./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.s3.S3AsyncClientListAllObjectsTest'`
- `./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.s3.S3AsyncClientCoroutinesExtensionsTest' --tests 'io.bluetape4k.aws.s3.S3AsyncClientListAllObjectsTest'` reported 14 passing tests.

## Future Notes

Prefer a central Flow helper for S3 object listing. Avoid duplicating
continuation-token loops in callers unless the caller needs page-level metadata.
