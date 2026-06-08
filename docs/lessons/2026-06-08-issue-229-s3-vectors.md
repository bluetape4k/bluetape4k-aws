# Issue #229 S3 Vectors optional boundary

## Context

Issue #229 added optional Amazon S3 Vectors support across `aws-java`,
`aws-spring-boot`, and `aws-ktor`.

## Decision

Keep S3 Vectors separate from the ordinary S3 object API because AWS exposes it
through the separate `s3vectors` SDK service and IAM namespace. Reuse the
`aws-java` coroutine facade from Spring Boot and Ktor instead of creating
adapter-specific operation interfaces.

## Outcome

- `software.amazon.awssdk:s3vectors` stays `compileOnly` plus test scope in the
  library modules.
- Consumers add `runtimeOnly("software.amazon.awssdk:s3vectors")` only when they
  enable or install S3 Vectors support.
- Spring Boot activation requires `bluetape4k.aws.s3-vectors.enabled=true`.
- Ktor activation requires explicit `S3VectorsKtorPlugin` installation.
- README diagrams and service coverage prose must reflect S3 Vectors as
  optional and must not imply emulator support.

## Verification

- Maven Central artifact probe confirmed `software.amazon.awssdk:s3vectors`.
- `javap` confirmed `S3VectorsAsyncClient` operation names before API wrapping.
- Focused Gradle compile/test runs covered `aws-java`, `aws-spring-boot`, and
  `aws-ktor` S3 Vectors paths.

## Future Rule

When AWS introduces a new service-specific SDK artifact, start with an optional
facade in the lowest shared module, reuse it in framework adapters, and document
runtime dependency ownership clearly in all README locale files.
