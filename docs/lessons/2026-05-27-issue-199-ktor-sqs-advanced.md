# Issue 199 Ktor SQS Advanced Controls

## Context

Issue #199 expands the Ktor SQS consumer from the initial coroutine poller into a production hardening surface: typed conversion failure policy, manual ack/nack, retry visibility strategy, lifecycle interceptors, and observation hooks.

## Decision

Keep the existing `onMessage<T>` and `deleteOnSuccess = true` behavior as the default. Add advanced controls as opt-in runtime configuration so existing users keep source and behavior compatibility.

Use lightweight observer events instead of adding a Micrometer dependency to `aws-ktor`; applications can bridge observations to Micrometer, OpenTelemetry, logs, or tests.

## Outcome

The SQS runtime now supports:

- `SqsConversionFailurePolicy` for conversion failures before handler invocation.
- `SqsMessageContext.ack()` and `nack(timeoutSeconds)` for manual acknowledgement flows.
- `SqsFailureVisibilityStrategy` with fixed and receive-count linear implementations.
- `SqsConsumerInterceptor` hooks around receive, invoke, ack, and nack.
- `SqsConsumerObserver` events for receive, convert, invoke, ack, and nack outcomes.

## Verification

Targeted verification covered runtime configuration validation, conversion failure delete policy, manual ack/nack, interceptor ordering, and observer/failure visibility behavior.

## Future Guard

Keep SQS advanced controls opt-in. Do not add Spring-style annotations or metrics dependencies to the Ktor module; expose small runtime hooks and let applications adapt them.
