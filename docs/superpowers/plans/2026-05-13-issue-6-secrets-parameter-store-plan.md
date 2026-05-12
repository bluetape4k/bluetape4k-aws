# Issue #6 Secrets Manager / Parameter Store Plan

## Scope

Implement issue #6 in `aws-spring-boot` from:

- Spec: `docs/superpowers/specs/2026-05-13-issue-6-secrets-parameter-store-design.md`
- Branch: `issue-6-secrets-parameter-store`
- Base: `origin/develop`

## Steps

### 1. Build Configuration

- Add version catalog aliases for `software.amazon.awssdk:secretsmanager` and
  `software.amazon.awssdk:ssm`.
- Add `compileOnly` and `testImplementation` dependencies in
  `aws-spring-boot/build.gradle.kts`.

### 2. Shared Environment Source Utilities

- Add a small internal property key flattener.
- Add property source ordering helper.
- Add common AWS client builder helper only if it removes duplication without
  obscuring service-specific behavior.

### 3. Secrets Manager Environment Source

- Add `SecretsManagerProperties`.
- Add `SecretsManagerEnvironmentPostProcessor`.
- Add `SecretsManagerPropertySourceLoader` or equivalent testable internal
  collaborator.
- Register the post-processor in `META-INF/spring.factories`.

### 4. Parameter Store Environment Source

- Add `ParameterStoreProperties`.
- Add `ParameterStoreEnvironmentPostProcessor`.
- Add paged `getParametersByPath` loading.
- Register the post-processor in `META-INF/spring.factories`.

### 5. Tests

- Add ApplicationContextRunner tests for binding, disabled/no-source behavior,
  missing SDK guard, and validation.
- Add LocalStack tests for one JSON secret and one recursive parameter path.

### 6. README

- Update `README.md` and `README.ko.md` dependency snippets and configuration
  examples.

### 7. Verification

Run:

1. `rg '[가-힣]' aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/{secretsmanager,parameterstore}` to catch public KDoc language drift.
2. `git diff --check`
3. `./gradlew :aws-spring-boot:compileKotlin`
4. `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.secretsmanager.*' --tests 'io.bluetape4k.aws.spring.parameterstore.*'`
5. `./gradlew :aws-spring-boot:test`

## Review Checklist

- No awspring or Spring Cloud dependency.
- No remote AWS call unless sources are explicitly configured.
- Endpoint override requires region.
- SDK absence has a clear failure mode only when users configure sources.
- Secrets and parameter values are not logged.
- AWS clients are closed after startup loading.
- Public API KDoc is English.
- README and Korean README stay in sync.

