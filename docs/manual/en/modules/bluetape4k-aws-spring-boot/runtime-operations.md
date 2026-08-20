---
title: Runtime operations
description: Run Spring AWS integrations with bounded lifecycle and observability.
manualId: bluetape4k-aws-spring-boot
chapterId: runtime-operations
---

# Runtime operations

Operational correctness comes from lifecycle, bounded concurrency, observability, and secret discipline rather than from auto-configuration alone.

## Client ownership

Auto-configured clients are Spring-owned. Application-provided clients remain application-owned unless registered as closeable beans. Listener containers must stop before their clients. Do not close a shared client from a request handler.

## SQS runtime

Tune concurrent consumers, long-poll duration, maximum messages, visibility timeout, failure visibility, and shutdown timeout together. More pollers can increase throughput but also raise in-flight messages and downstream pressure. Prefer native SQS redrive policies over ad-hoc endless retries.

## Observability

When a `MeterRegistry` is available, S3/SQS operations and listener phases can emit low-cardinality timers. Do not put bucket keys, message bodies, secret IDs, or unbounded exception text in metric tags. Correlate AWS request IDs in logs.

## Remote configuration

Secrets Manager, Parameter Store, and S3 config loaders run during environment preparation. Treat a required source failure as startup failure. Cache resolved configuration in the environment instead of making AWS calls for every request.
Set `bluetape4k.aws.enabled=false` to disable these startup loaders together with AWS auto-configuration; configured remote sources are not accessed.

## Graceful shutdown

Stop ingress, stop listener polling, await handlers up to a configured timeout, close owned service clients, then close database pools. Verify the same sequence in tests.

## Extended Client shutdown and rollback

`SqsExtendedClientLifecycle` runs before managed AWS clients. Its drain timeout
is bounded by `shutdown-drain-timeout-seconds` and the Spring shutdown phase
budget. A timeout leaves the client running and records a bounded diagnostic so
an explicit stop retry can finish; it does not close a client with active work.

The rollback coordinator disables the producer, stops the legacy consumer,
drains extended operations, and observes two empty raw probes over the maximum
visibility/retry window (`max=1`, `visibility=0`, `wait=0`). It rejects malformed
or exhausted `RedrivePolicy`/DLQ budgets, then rehydrates quarantine pointers to
inline messages and verifies all counts and idempotency before starting legacy.
The global rollback deadline never extends when a pointer reappears. Any
`DEADLINE_EXCEEDED` or `REDRIVE_BUDGET_EXHAUSTED` result is `ROLLBACK_BLOCKED`.
Do not start an `@SqsListener` on an extended pointer queue during this flow.

Least-privilege policy shape:

```json
{
  "Action": ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage"],
  "Resource": "arn:aws:sqs:ap-northeast-2:123456789012:orders"
}
```

Add `s3:PutObject`, `s3:GetObject`, and `s3:DeleteObject` only for
`arn:aws:s3:::orders-extended-payloads/bluetape4k/sqs/orders/*`. Encrypted
policies additionally require `kms:GenerateDataKey` and `kms:Decrypt` on one
exact CMK ARN with the configured encryption-context condition. Wildcard or
foreign bucket/key/CMK identities are rejected by configuration validation.

## Operational checklist

- Region and endpoint match the deployment.
- Credentials have least privilege and rotate.
- Service SDK jars match enabled integrations.
- Retry budgets are bounded.
- Metrics and logs avoid secrets and high-cardinality identifiers.
- Floci is the default local emulator; LocalStack is an explicit fallback.
- Follow-up issue #515 owns external publisher latency/cleanup telemetry and
  heap/throughput measurements; those deferred measurements are not Extended
  Client completion evidence.

## Sources

- [SQS listener container](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainer.kt)
- [Micrometer SQS interceptor](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/MicrometerSqsListenerInterceptor.kt)
- [Secrets environment processor](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/secretsmanager/SecretsManagerEnvironmentPostProcessor.kt)
