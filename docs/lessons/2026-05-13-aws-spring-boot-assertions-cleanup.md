# AWS Spring Boot Assertions Cleanup

## Context

After PR #62, follow-up cleanup removed remaining AssertJ usage from `aws-spring-boot/src/test/kotlin` and aligned touched tests with `bluetape4k-assertions`.

- Scope: `aws-spring-boot/src/test/kotlin`
- Files touched: 9
- Review-driven corrective iterations: 1
- Review findings fixed: P0=0, P1=0, P2=1

## Decision

Touched tests must use `bluetape4k-assertions` consistently instead of mixing AssertJ with repo-native assertions.

For `ApplicationContextRunner` tests, bean presence is clearer with `getBeansOfType(...).size`, `startupFailure.shouldBeNull()`, and infix equality checks.

## Outcome

- Removed AssertJ from S3/SNS/SQS auto-configuration tests.
- Removed AssertJ from S3/SNS/SQS LocalStack integration tests.
- Removed AssertJ from Parameter Store and Secrets Manager environment post-processor tests.
- Kept assertion intent explicit with `shouldBeEqualTo`, `shouldContain`, `shouldEndWith`, `shouldHaveSize`, `shouldBeEmpty`, `shouldNotBeBlank`, and `assertFailsWith`.
- Restored SNS topic ARN suffix checks with `shouldEndWith` after review found that `shouldContain` weakened the original `endsWith` assertion.
- `aws-spring-boot/src/test/kotlin` now has no remaining AssertJ usage.

## Verification

- `./gradlew :aws-spring-boot:compileTestKotlin`
- `./gradlew :aws-spring-boot:test`
- `git diff --check`
- Claude CLI review: P0=0, P1=0, P2=1 on first pass; fixed and rechecked.

Result: 68 `aws-spring-boot` tests passed.

## Future Guard

When touching bluetape4k Kotlin tests, migrate touched assertion blocks to `bluetape4k-assertions` immediately instead of preserving mixed assertion styles for convenience.
