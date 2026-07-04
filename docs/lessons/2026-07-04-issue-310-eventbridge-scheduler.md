# Issue #310 EventBridge Scheduler Support

## Context

Milestone `0.5.0` needed EventBridge Scheduler support after the EventBridge core and integration work landed. Scheduler is a distinct AWS service artifact (`scheduler`) rather than an EventBridge event-bus feature, so the implementation had to keep package and dependency boundaries explicit.

## Decision

Add thin Java SDK v2 and AWS Kotlin SDK helper surfaces under separate Scheduler packages:

- `io.bluetape4k.aws.scheduler`
- `io.bluetape4k.aws.scheduler.model`
- `io.bluetape4k.aws.kotlin.scheduler`
- `io.bluetape4k.aws.kotlin.scheduler.model`

The helpers build schedule, schedule-group, target, flexible-window, retry-policy, and dead-letter requests while returning raw SDK responses from sync, async, coroutine, and native suspend client calls.

## Outcome

- Added optional `scheduler` dependency aliases for AWS Java SDK v2 and AWS Kotlin SDK.
- Added request range validation for Scheduler list page sizes, flexible time windows, retry event age, and retry attempts.
- Kept target-specific validation, cross-account orchestration, and service-side semantics outside bluetape4k helper scope.
- Updated English and Korean README dependency guidance and EventBridge boundary text.

## Verification

- `./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-kotlin:compileKotlin` — PASS
- `./gradlew :bluetape4k-aws-java:test --tests '*scheduler*' :bluetape4k-aws-kotlin:test --tests '*scheduler*'` — PASS
- `git diff --check` — PASS

## Future Guidance

If Scheduler support expands into Spring Boot or Ktor, keep those integrations separate from EventBridge event-bus operations and require the runtime `scheduler` SDK artifact explicitly.
