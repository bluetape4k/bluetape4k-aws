# Issue #192 Spring S3 Advanced

## Context

Milestone 0.3.0 narrowed the AWSpring S3 parity issue to production-hardening
features that are immediately useful: S3-backed Spring Environment config
reload and KMS-backed S3 client-side encryption. S3 Access Grants and S3 Vector
remain outside this slice because they require additional optional SDK/client
surfaces.

## Decision

Add S3 config loading as an `EnvironmentPostProcessor` that reuses the existing
AWS property-source refresh support, and add byte-array envelope encryption as
an opt-in `S3ClientSideEncryptionOperations` bean guarded by a `KmsOperations`
bean and `bluetape4k.aws.s3.client-side-encryption.enabled=true`.

## Outcome

S3 config sources can load `properties`, YAML, or JSON objects and lazily reload
when `refresh-interval` is configured. S3 encryption uses KMS data keys,
AES-GCM local payload encryption, and S3 metadata for the encrypted data key and
nonce. The helper intentionally does not support multipart or streaming
client-side encryption.

## Verification

- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --max-workers=1`
- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*S3AutoConfigurationTest' --tests '*S3ConfigEnvironmentPostProcessorAwsEmulatorTest' --tests '*S3CoroutinesTemplateAwsEmulatorTest' --no-daemon --max-workers=1`

## Future Guard

Keep Access Grants and S3 Vector out of the default Spring Boot S3 API until
there is a concrete application-owned integration shape. If streaming or
multipart encryption is needed later, add it as a separate contract instead of
stretching the byte-array helper.
