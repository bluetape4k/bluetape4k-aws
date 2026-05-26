# Issue #182 SNS-to-SQS Fanout Stability

## Context

Issue #182 revisited the Spring Boot SQS example fanout test because SNS-to-SQS
delivery should stay enabled only when LocalStack coverage is deterministic.

## Decision

Keep the fanout test enabled. The current LocalStack/Testcontainers behavior is
stable for the combined REST send, listener receive, SNS-to-SQS fanout, and DLQ
setup example.

## Outcome

The test now uses short `Base58.randomString(8)` suffixes for REST, listener,
fanout, queue, topic, and DLQ names so stale resources or messages from prior
receives cannot accidentally satisfy the assertions. Timeout failures now
include the waited condition.

## Verification Evidence

- `:aws-spring-boot-sqs-examples:test --tests '*SqsSnsExampleLocalStackTest' --rerun-tasks` passed three consecutive times.
- `:aws-spring-boot-sqs-examples:compileTestKotlin :aws-spring-boot-sqs-examples:test --tests '*SqsSnsExampleLocalStackTest' --rerun-tasks` passed after switching suffixes to `Base58.randomString(8)`.
- `:aws-spring-boot-sqs-examples:test` passed after the final suffix change.

## Future Guard

If fanout becomes flaky again, inspect queue policy propagation and SNS
subscription setup before disabling the test. If disabling is unavoidable, use
the required `@Disabled("#NNN — <reason>")` format and document the emulator
blocker in this lesson or a follow-up lesson.
