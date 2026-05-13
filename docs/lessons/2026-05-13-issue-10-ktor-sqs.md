# Issue #10 Ktor SQS Consumer

## Context

`aws-ktor` needed a server-side SQS consumer/publisher that fits Ktor lifecycle
without making AWS SQS a transitive runtime dependency.

## Decision

Use an injected `SqsAsyncClient`, keep `aws2.sqs` as `compileOnly` for main,
and expose `SqsConsumerRuntime` as the lifecycle-testable core behind the Ktor
`SqsConsumer` plugin. Start on `ApplicationStarted` and drain on
`ApplicationStopping`.

## Outcome

Implemented coroutine pollers, typed handler conversion, publishing,
receive-error backoff, in-flight handler backpressure, optional visibility
heartbeat, graceful shutdown, failure visibility, and documented best-effort
manual DLQ forwarding.

## Verification

- `./gradlew :aws-ktor:compileKotlin`
- `./gradlew :aws-ktor:compileTestKotlin`
- `./gradlew :aws-ktor:test --tests 'io.bluetape4k.aws.ktor.sqs.*'` - 14 passing
- `./gradlew :aws-ktor:test` - 33 passing

## Future Guidance

Prefer native SQS redrive policies unless failed messages must be enriched by
application code. Keep any future multi-queue support as a registry-style layer
above `SqsConsumerRuntime` instead of changing the one-handler-per-instance
contract. For consumer tests, use Awaitility and `untilSuspending {}` for
suspend polling; avoid raw `delay` as a synchronization primitive.

See `docs/lessons/2026-05-13-pr-review-gate-metrics.md` for the post-PR review
gate metrics: 8 review rounds, 11 raw P1 findings, 10 unique P1 defects, and 4
new regression tests added before merge.
