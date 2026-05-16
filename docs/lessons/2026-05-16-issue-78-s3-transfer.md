# Issue 78: Spring Boot S3 Transfer Support

## Context

The `aws` module already owns the low-level `S3TransferManager` factory and
coroutine extensions. Spring Boot support should not duplicate transfer request
construction or future-await logic.

## Decision

Expose transfer support from `aws-spring-boot` as optional Spring beans:

- `S3TransferManager` is configured only when `software.amazon.awssdk:s3-transfer-manager`
  is present.
- `S3TransferOperations` wraps the existing `aws` module coroutine transfer
  extensions.
- Basic `S3Operations` remains available when transfer manager classes are absent.

## Guardrails

Keep TransferManager support classpath-guarded and dependency-light. CRT-backed
transfers are supported by providing a CRT-backed `S3AsyncClient` bean; do not
force CRT runtime dependencies on applications that only need basic S3 object
operations.

## Verification

Targeted Spring Boot S3 tests must cover:

- default transfer bean registration,
- transfer disable/back-off properties,
- missing transfer-manager classpath,
- custom `S3TransferOperations` back-off,
- LocalStack file upload/download through `S3TransferOperations`.
