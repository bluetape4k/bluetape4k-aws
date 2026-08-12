---
title: Runtime lifecycle
description: Start and stop AWS clients, pollers, handlers, and registries safely.
manualId: bluetape4k-aws-ktor
chapterId: runtime-lifecycle
---

# Runtime lifecycle

A Ktor runtime may own clients, coroutine jobs, database registries, and in-flight handlers. Ownership is recorded at construction so shutdown can close only what the runtime created.

## Startup

Validate configuration before launching jobs. Resolve queue names, create database registries, and build service clients inside a bounded startup timeout. A partial startup failure must close everything already created.

## Structured background work

Pollers and handlers run under the application lifecycle, not `GlobalScope`. Bound parallelism and use a dedicated or limited I/O dispatcher for blocking boundaries. One failed child should follow the runtime's documented supervision policy rather than silently killing unrelated service work.

## Shutdown sequence

1. Stop scheduling new receives or service work.
2. Cancel or signal pollers.
3. Await in-flight handlers up to `shutdownTimeout`.
4. Apply the configured failure/visibility outcome to interrupted messages.
5. Close plugin-owned clients and registries.
6. Leave injected resources open for their owner.

```kotlin
environment.monitor.subscribe(ApplicationStopping) {
    // Plugins stop work before application-owned shared clients close.
}
```

## SQS consumer one-shot lifecycle

`SqsConsumerRuntime` is a one-shot runtime because it may own the
`SqsAsyncClient` created during plugin configuration. The first `start()` call
starts the pollers. Duplicate `start()` calls while the runtime is running or
draining are ignored, while `stop()` before or after startup permanently moves
the runtime to `STOPPED`. A `start()` call after `STOPPED` fails fast with
`IllegalStateException` instead of reusing a closed client. The runtime closes
an owned client once and never closes an injected client. Create a new plugin
instance when a fresh consumer lifecycle is required.

## SQS visibility during shutdown

If a handler can outlive visibility, enable heartbeat extension or choose a longer timeout. On forced shutdown, immediate redelivery can be safer than waiting for visibility expiry, but only when handlers are idempotent.

## CloudWatch Logs buffered shutdown

`CloudWatchLogsKtorRuntime` stops its periodic flush job before attempting one
bounded flush at `shutdownFlushTimeout`. The default
`CloudWatchLogsShutdownPolicy.WarnAndContinue` preserves the existing
non-throwing shutdown contract: a timeout is logged, the buffered events are
restored, and a plugin-owned SDK client is closed. An injected client remains
the application's responsibility.

Register an observer when the pending or dropped event count must be exported
to a metric or tracing backend without adding a metrics dependency to the
runtime:

```kotlin
install(CloudWatchLogsKtorPlugin) {
    logGroupName = "/app/orders"
    logStreamName = "ktor"
    shutdownObserver { observation ->
        metrics.record(
            outcome = observation.outcome.name,
            pending = observation.pendingEventCount,
            dropped = observation.droppedEventCount,
        )
    }
}
```

`CloudWatchLogsShutdownObservation` reports `Success`, `Timeout`, `Failure`,
or `Cancelled`. On a non-successful shutdown, `pendingEventCount` is the
buffer size immediately before client close and `droppedEventCount` records
events that will not be retried by this runtime. A caller cancellation still
rethrows its original `CancellationException` after cleanup. Set
`shutdownPolicy = CloudWatchLogsShutdownPolicy.ThrowOnTimeout` when a timeout
must fail the shutdown with `CloudWatchLogsShutdownTimeoutException`; client
cleanup and the observer notification happen before that exception is thrown.

## Observability

Record poll, receive, convert, invoke, acknowledge, and failure phases with bounded tags. Track active jobs and shutdown duration. Do not use queue URLs, message bodies, or object keys as unbounded metric tags.

## Verification

A lifecycle test should cover stop-before-start, start-stop-start, duplicate
start/stop calls, assert no job remains active, prove an injected client stays
open, prove a plugin-created client closes once, and exercise timeout
cancellation.

## Sources

- [SQS runtime](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntime.kt)
- [Exposed runtime](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/exposed/AwsExposedKtorRuntime.kt)
- [CloudWatch runtime](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorRuntime.kt)
- [CloudWatch Logs runtime](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchLogsKtorRuntime.kt)
