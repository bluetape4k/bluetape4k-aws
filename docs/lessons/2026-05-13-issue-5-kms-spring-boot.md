# Issue #5 KMS Spring Boot Support

Date: 2026-05-13
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/5

## Context

`aws-spring-boot` needed KMS support without awspring. The first slice had to expose a coroutine-friendly API, optional Spring Security `TextEncryptor`, and data-key cache support while keeping AWS SDK service dependencies `compileOnly`.

## Decision

Implemented startup auto-configuration plus explicit application APIs:

- `KmsAsyncClient` bean guarded by KMS SDK classpath checks.
- `KmsOperations` and `KmsCoroutinesEncryptor` for suspend encrypt/decrypt/data-key generation.
- Bounded `InMemoryDataKeyCache` with TTL and max-size limits.
- Optional `KmsTextEncryptorAutoConfiguration`, guarded so `spring-security-crypto` remains optional.
- README/README.ko.md explain direct KMS encryption vs data-key/envelope use and include PlantUML diagrams.

Deferred `@KmsEncrypted` field-level encryption because it needs a separate serialization/persistence lifecycle design.

## Verification

- `./gradlew :aws-spring-boot:compileKotlin :aws-spring-boot:compileTestKotlin`
- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.kms.*'`
- `./gradlew :aws-spring-boot:test`
- `git diff --check`
- Grep confirmed no Korean text in new public KMS main-source KDoc.

## Notes

Claude advisor review was attempted through `omx ask claude`, but the command produced no output for 60 seconds and was killed. Proceeded with Spring Boot official-doc checks, AWS SDK v2 local usage patterns, and LocalStack verification.

Future agents should keep `spring-security-crypto` optional and test it with `FilteredClassLoader` whenever the adapter changes.
