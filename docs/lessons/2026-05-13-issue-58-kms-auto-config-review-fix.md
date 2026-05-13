# Issue #58 KMS Auto-Configuration Post-Review Fix

## Context

PR #58 was merged with KMS Spring Boot support, then post-merge review found one P1 startup bug and one P2 test robustness issue.

- P1 findings: 1
- P2 findings: 1
- Review-driven corrective iterations: 2

## Decision

`KmsTextEncryptorAutoConfiguration` must honor the top-level `bluetape4k.aws.kms.enabled` switch, not only `bluetape4k.aws.kms.text-encryptor.enabled`.

The text-encryptor phase also registers `KmsProperties` explicitly so a user-provided `KmsOperations` can still use the adapter without relying on the client auto-configuration phase.

## Outcome

- Added a regression test for `bluetape4k.aws.kms.enabled=false` with a custom `KmsOperations` bean.
- Added coverage that the text-encryptor phase can bind `KmsProperties` for custom operations.
- Replaced KMS package AssertJ usage with bluetape4k assertions.
- Replaced KMS LocalStack `runTest` usage with `runSuspendIO`.
- Switched the KMS LocalStack test to `LocalStackServer.Launcher.getLocalStack("kms")`.

## Verification

- `./gradlew :aws-spring-boot:compileKotlin :aws-spring-boot:compileTestKotlin`
- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.kms.*'`
- `git diff --check`

Result: 15 KMS tests passed.

## Future Guard

For Spring Boot auto-configuration phases, apply the parent `enabled` condition to every phase class. When testing context startup, prefer direct bluetape4k assertions such as `startupFailure.shouldBeNull()` and infix equality checks over AssertJ.
