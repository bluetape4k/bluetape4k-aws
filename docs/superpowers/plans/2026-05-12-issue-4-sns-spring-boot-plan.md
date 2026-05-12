# Issue #4 SNS Spring Boot Plan

## Scope

Implement issue #4 in `aws-spring-boot` using the reviewed design spec:

- Spec: `docs/superpowers/specs/2026-05-12-issue-4-sns-spring-boot-design.md`
- Branch: `issue-4-sns-spring-boot`
- Base: `origin/develop`
- Out of scope: issue #13 full SQS-SNS application example.

## Quality Gates

1. Spec review must be clean of blockers before implementation.
2. Plan review must be clean of blockers before implementation.
3. Kotlin public API must have English KDoc, because KDoc is contributor-facing
   public documentation under the workspace language policy.
4. Compile-only SNS SDK types must be protected by string-based `@ConditionalOnClass`.
5. Tests must prove auto-configuration behavior and coroutine SNS behavior.
6. Final strict code review must be completed before commit/PR.

## Implementation Steps

### 1. Build Configuration

- Add `compileOnly(awsLibs.aws2.sns)` to `aws-spring-boot/build.gradle.kts`.
- Add `testImplementation(awsLibs.aws2.sns)` to `aws-spring-boot/build.gradle.kts`.

### 2. SNS Spring Package

Create `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/`.

Files:

- `SnsFifoThroughputScope.kt` with explicit `attributeValue: String` mapping to AWS values `Topic` and `MessageGroup`.
- `SnsProperties.kt`; do not validate FIFO topic names inside `Topic`, because the topic name is the map key.
- `SnsPublishRequest.kt` with `init` validation for blank fields and FIFO-only publish fields.
- `SnsOperations.kt`
- `SnsCoroutinesTemplate.kt`
- `SnsAutoConfiguration.kt`

Implementation details:

- Use `require(...)` validation with stable exception type `IllegalArgumentException`.
- Use `kotlinx.coroutines.future.await()` for SDK futures.
- Keep AWS SDK calls cancellation-friendly by avoiding broad `runCatching`.
- Use AWS SDK model builders directly where existing `aws` module helpers do not cover the full Spring template contract.
- For `findTopicArn`, page through `listTopics` with `nextToken`.
- For FIFO attributes, write `FifoTopic=true`, `ContentBasedDeduplication=<true|false>`, and optional `FifoThroughputScope=<Topic|MessageGroup>`.
- For `publish`, set optional subject, message attributes, FIFO group id, and FIFO deduplication id only when present.
- `SnsPublishRequest.init` must require `messageGroupId` for FIFO topic ARNs and reject `messageGroupId`/`messageDeduplicationId` for standard topic ARNs.
- `SnsCoroutinesTemplate` constructor must mirror the SQS pattern: `SnsCoroutinesTemplate(snsAsyncClient, properties)`.
- `createConfiguredTopic(topicName)` must:
  - Look up `SnsProperties.topics[topicName]`.
  - Throw `IllegalArgumentException` if the topic is not configured.
  - Throw `IllegalArgumentException` if the configured topic is FIFO and `topicName` does not end with `.fifo`.

### 3. Auto-Configuration Registration

- Add `io.bluetape4k.aws.spring.sns.SnsAutoConfiguration` to `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- In `SnsAutoConfiguration`, register the client with `@Bean(destroyMethod = "close")` and `@ConditionalOnMissingBean`.
- Register the template with `@Bean` and `@ConditionalOnMissingBean(SnsOperations::class)`.

### 4. Tests

Create `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/`.

Support files:

- `NoopSnsOperations.kt`, matching the SQS test pattern for custom operations back-off tests.

ApplicationContextRunner tests:

- Auto-registers `SnsAsyncClient` and `SnsOperations`.
- Disables registration when `bluetape4k.aws.sns.enabled=false`.
- Backs off for custom `SnsAsyncClient`.
- Backs off for custom `SnsOperations`.
- Fails when `endpointOverride` is set without `region`.
- Does not register when SNS SDK class is filtered.
- Rejects invalid configured FIFO topic names.

LocalStack tests:

- Creates standard topic and finds its ARN through pagination-aware lookup.
- Publishes standard message and checks `messageId`.
- Creates configured topic from properties.
- Creates FIFO topic and publishes with `messageGroupId`.
- Rejects FIFO-only publish fields for standard topic locally.
- Verifies invalid/non-existent ARN errors propagate from AWS.
- Attempts a minimal SNS-to-SQS fanout test. Keep it if stable; if not stable, document the LocalStack blocker and keep README fanout snippet.

### 5. README

Update:

- `README.md`
- `README.ko.md`

Changes:

- Add SNS runtime dependency in the Spring Boot section.
- Add `bluetape4k.aws.sns` YAML sample.
- Add coroutine publish sample with `SnsOperations` and `SnsPublishRequest`.
- State that the full SQS-SNS example application is handled by issue #13.

### 6. Verification

Run, in order:

1. IDE diagnostics if available for touched Kotlin files.
2. `./gradlew :aws-spring-boot:compileKotlin`
3. `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.*'`
4. If targeted test filtering misses context tests, run `./gradlew :aws-spring-boot:test`.

If LocalStack is unavailable or unstable, report the exact failure and run the next-best non-LocalStack coverage. Do not claim full integration verification without passing evidence.

## Review Checklist

- Public API does not expose ambiguous positional same-type parameters.
- Compile-only SDK types are not used in unconditional bean signatures without class guards.
- No `runBlocking` in production code.
- No `runCatching` around suspend calls.
- No `!!`.
- Cancellation is not swallowed.
- Auto-configuration backs off for user beans.
- FIFO validations prevent AWS-only runtime surprises where feasible.
- README and Korean README are updated together.

## Commit And PR

- Commit with Lore protocol trailers.
- Push `issue-4-sns-spring-boot`.
- Create a draft PR against `develop`, assign `debop`, and link issue #4.

## Plan Review Notes

- Claude Code advisor review artifact: `.omx/artifacts/ask-claude-issue-4-sns-plan-review.md`.
- Resolved blockers: explicit `SnsAsyncClient` bean registration shape, `createConfiguredTopic` FIFO validation location, and `SnsPublishRequest.init` validation location.
- Resolved high-risk notes: enum AWS value mapping, missing configured-topic behavior, no-op test helper, targeted test filter, and template constructor shape.
