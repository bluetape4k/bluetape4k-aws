---
name: ktor-sqs-examples
description: Lessons from implementing the aws-ktor-sqs-examples module (issue #16)
metadata:
  type: project
---

# Ktor SQS Examples Module (Issue #16)

## Summary

Added `examples/aws-ktor-sqs-examples` — a Ktor 3 application demonstrating SQS messaging via the
`aws-ktor` `SqsConsumer` plugin. Covers send, receive (consumer), queue creation, and queue attribute
inspection via HTTP routes backed by LocalStack integration tests.

## Root Cause / Context

Pre-release gap: the `aws-ktor` SQS integration had no end-to-end example showing how to wire up
`SqsConsumer` inside a Ktor application and verify behavior through HTTP routes.

## Key Decisions

### SqsConsumer plugin wiring

```kotlin
install(SqsConsumer) {
    sqsAsyncClient = sqsClient
    this.queueUrl = queueUrl
    coroutines = 2
    maxMessages = 10
    waitTimeSeconds = 1
    visibilityTimeoutSeconds = 30
    onMessage<String> { body -> received.add(body) }
}
```

Send via `call.application.sqsConsumer().send(body, queueUrl)`.

### Test structure

- `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` on the test class.
- `@BeforeAll` / `@AfterAll` use `runSuspendIO {}` for suspend setup/teardown.
- Each `@Test` uses `testApplication {}` directly (not wrapped in `runSuspendIO`).
- Concurrent test uses `SuspendedJobTester().workers(4).rounds(5).add {...}.run()` inside
  `testApplication {}` — valid because `testApplication` provides a suspend context.

### Assertion style

Use bluetape4k assertions: `shouldBeEqualTo`, `shouldBeTrue`, `shouldBeNull`. Never use plain
`assert()` or `assertEquals` in new tests.

## Pitfalls Avoided

- Do NOT wrap `testApplication {}` inside `runSuspendIO {}` — `testApplication` calls
  `runBlocking` internally; nesting would deadlock.
- Do NOT instantiate `SqsConsumerRuntime`/`SqsConsumerRuntimeConfig` directly — they are internal.
  Test behavior through HTTP routes only.

## Verification

- LocalStack SQS queue created in `@BeforeAll`, deleted in `@AfterAll`.
- Routes: `POST /sqs/messages`, `GET /sqs/queues/attributes`, `POST /sqs/queues/{name}`.
- Concurrent test: 4 workers × 5 rounds each, all expecting `HttpStatusCode.OK`.

## Future Guidance

- When adding new `aws-ktor` SQS features, extend these routes and add corresponding test cases.
- The `SqsClientFactory.Async.create(endpointOverride, region, credentialsProvider)` pattern is the
  canonical way to create a test client pointing at LocalStack.
