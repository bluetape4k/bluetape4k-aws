# Issue #5 KMS Spring Boot Support Plan

Date: 2026-05-13
Spec: `docs/superpowers/specs/2026-05-13-issue-5-kms-spring-boot-design.md`
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/5

## Scope

Implement the first KMS Spring Boot slice:

1. Auto-configure `KmsAsyncClient`.
2. Bind `bluetape4k.aws.kms`.
3. Provide coroutine encrypt/decrypt and data-key generation.
4. Provide bounded in-memory data key cache.
5. Provide optional Spring Security `TextEncryptor` adapter.
6. Expand README/README.ko.md with user-oriented explanation and UML diagrams.

## Tasks

### T0 - Baseline

- Confirm no existing #5 spec/plan exists.
- Create worktree from `origin/develop`.
- Inspect S3/SQS/DynamoDB Spring Boot patterns.
- Check Spring Boot auto-configuration documentation and AWS SDK v2 KMS behavior.

### T1 - Build Wiring

- Add `libs.aws2.kms` to `aws-spring-boot` compile/test dependencies.
- Add `spring-security-crypto` alias and optional compile/test dependency.
- Register KMS auto-configuration classes in `AutoConfiguration.imports`.

### T2 - Core API

- Add `KmsProperties`.
- Add `KmsOperations`.
- Add `KmsDataKey`, `KmsDataKeyCacheKey`, `DataKeyCache`, and default in-memory cache.
- Add `KmsCoroutinesEncryptor`.

### T3 - Spring Configuration

- Add `KmsAutoConfiguration`.
- Add optional `KmsTextEncryptorAutoConfiguration`.
- Ensure custom user beans back off.
- Keep direct AWS SDK service references guarded by `@ConditionalOnClass`.

### T4 - Tests

- Add `ApplicationContextRunner` tests for auto-config, disable flag, custom beans, and endpoint-region validation.
- Add LocalStack test for encrypt/decrypt and data-key caching.
- Add TextEncryptor adapter test when `spring-security-crypto` is on the test classpath.

### T5 - Documentation

- Update README.md and README.ko.md dependency snippets.
- Add KMS configuration section.
- Add user-facing examples for small secret encryption and TextEncryptor.
- Add UML diagrams for component and runtime flows.
- State KMS payload limit caveat and data-key cache security tradeoff.

### T6 - Verification and PR

- Run targeted compile/tests.
- Run full `:aws-spring-boot:test`.
- Run `git diff --check`.
- Commit with Lore trailers.
- Push branch, open PR assigned to `debop`, monitor CI, mark ready when green.

## Risks

- `TextEncryptor` is blocking while KMS client calls are async/suspend. Mitigation: document intended small-secret use and keep it as optional adapter.
- Plaintext data-key caching is sensitive. Mitigation: conservative defaults, bounded TTL and size, explicit documentation.
- LocalStack KMS behavior may differ from AWS. Mitigation: keep tests focused on SDK request/response behavior and report real AWS as not tested.

## Acceptance Criteria

- `KmsOperations` can encrypt/decrypt bytes through LocalStack KMS.
- `generateDataKey` uses cache when enabled and bypasses it when disabled.
- `KmsTextEncryptor` round-trips text and emits Base64 ciphertext.
- `aws-spring-boot` tests pass locally and in GitHub Actions.
- README files explain the feature from a user perspective and include UML diagrams.
