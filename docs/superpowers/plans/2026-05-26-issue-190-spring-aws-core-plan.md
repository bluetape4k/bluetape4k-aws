# Issue #190 Spring Boot AWS Core Plan

Date: 2026-05-26
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/190

## Implementation Steps

1. Add shared `AwsProperties` and common client-builder support.
2. Add global sync/async and typed service-specific customizer APIs.
3. Register opt-in web-identity credentials support in `AwsAutoConfiguration`.
4. Apply shared defaults/customizers to existing Spring Boot AWS client
   auto-configurations.
5. Add ApplicationContextRunner coverage:
   - shared defaults binding and endpoint validation
   - STS-present and STS-absent web identity behavior
   - S3 shared defaults and sync/service customizer order
   - SQS shared defaults and async/service customizer order
6. Update English and Korean module README configuration docs.
7. Add a lesson for the #190 foundation decision.
8. Verify with targeted compile/test, `git diff --check`, and review gates.

## Validation Commands

- `./gradlew --no-daemon --max-workers=1 :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin`
- `./gradlew --no-daemon --max-workers=1 :bluetape4k-aws-spring-boot:test --tests '*AwsAutoConfigurationTest' --tests '*S3AutoConfigurationTest' --tests '*SqsAutoConfigurationTest'`
- `./gradlew --no-daemon --max-workers=1 :bluetape4k-aws-spring-boot:test`
- `git diff --check`

## Done Criteria

- #190 acceptance criteria are covered for shared defaults, customizer hooks,
  optional STS/web-identity behavior, and documentation.
- P0/P1 review findings are zero.
- PR targets `develop`, is assigned to `debop`, and links #190.
