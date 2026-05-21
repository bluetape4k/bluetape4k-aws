# Issue #147 S3 versioned bucket force delete

- Date: 2026-05-21
- Scope: `aws-kotlin` S3 bucket cleanup helpers
- Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/147

## Context

`S3Client.forceDeleteBucket()` deleted only current keys from `listObjectsV2`.
That is insufficient for versioned or versioning-suspended buckets because prior
object versions and delete markers can keep the bucket non-empty.

## Decision

Delete object versions and delete markers first with `listObjectVersions`, using
version-aware `ObjectIdentifier` values, then run the existing current-object
cleanup loop before `deleteBucket`.

## Outcome

The helper now handles version-aware cleanup without narrowing the public
contract. The regression test uses MockK instead of LocalStack so the contract is
not dependent on emulator versioning support.

## Verification

- `./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.s3.S3ClientBucketMockTest'`
- `./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.s3.S3ClientExtensionsTest'`
- `./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.s3.*'` reported 83 passing tests.

## Future Notes

When deleting S3 buckets, treat `listObjectsV2` as current-object cleanup only.
Versioned cleanup needs `listObjectVersions` plus delete-marker handling.
