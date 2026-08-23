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

## ConfigData import

Use Spring Boot ConfigData imports when a remote source is needed only during startup:

```properties
spring.config.import=optional:aws-s3:/config-bucket/application.yml?prefix=app&format=yaml,aws-parameterstore:/application?prefix=app&recursive=true&withDecryption=true,optional:aws-secretsmanager:application?prefix=app&format=json
```

The same imports can be declared as a YAML list:

```yaml
spring:
  config:
    import:
      - optional:aws-s3:/config-bucket/application.yml?prefix=app&format=yaml
      - aws-parameterstore:/application?prefix=app&recursive=true&withDecryption=true
      - optional:aws-secretsmanager:application?prefix=app&format=json
```

The supported prefixes are `aws-s3:`, `aws-parameterstore:`,
`aws-secretsmanager:`, and `aws-app-config:`. `optional:` suppresses only the
matching backend's not-found result. Authentication, network, parsing, and
other service failures remain startup failures. `bluetape4k.aws.enabled=false`
is evaluated before SDK classpath checks, so ConfigData creates no AWS client
and performs no remote access when disabled. Floci is the preferred emulator;
LocalStack is an explicit fallback. S3, Parameter Store, and Secrets Manager
ConfigData remain startup-only. The legacy `EnvironmentPostProcessor` sources
remain available for their existing refresh and precedence behavior.

### AppConfig Data runtime reload

Add the AppConfig Data SDK at runtime and import three identifiers in
`application`, `profile`, `environment` order:

```kotlin
implementation("software.amazon.awssdk:appconfigdata")
```

```properties
spring.config.import=aws-app-config:orders-api#production#ap-northeast-2?format=yaml&prefix=app
```

The default separator is `#`; configure one safe single character with
`bluetape4k.aws.app-config.separator`. Each component can be an AWS name or
identifier. Supported formats are `auto`, `properties`, `yaml`, and `json`;
`prefix` is applied after decoding and JSON/YAML values are flattened into
Spring property keys.

```yaml
bluetape4k:
  aws:
    app-config:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:2772
      fail-fast: true
      refresh-interval: 30s       # omitted/null: startup load only
      required-minimum-poll-interval: 15s
```

The loader calls `StartConfigurationSession` once, then `GetLatestConfiguration`
with exactly the newest token from each response. Empty responses retain the
last map and advance the token. Decode or transport failures retain the last
good map; transport/session failures discard the session and retry with bounded
full jitter. Payloads are bounded to 1 MiB, 32 flatten levels, and 10,000
properties. Tokens, response bodies, and remote identifiers are not written to
logs.

Reload is disabled unless `refresh-interval` is explicitly set. A context owns
one bounded scheduler and one fixed-delay self-rescheduling task per AppConfig
resource. The bootstrap client is closed after the initial ConfigData load; the
runtime client belongs to the application context. On shutdown, scheduling is
blocked, tasks are cancelled and drained, and the scheduler stops before the
runtime client is closed. `Environment` reads the newest map; `@Value` fields and
`@ConfigurationProperties` instances are not automatically rebound. This module does not add Spring Cloud Context,
`RefreshScope`, or an event bus.

AWS AppConfig Data authorization uses the service actions below. The API does
not expose a resource ARN for these actions, so the IAM statement must use
`Resource: "*"`; restrict account/region and workload scope with role
boundaries, organization policies, and network controls.

```json
{
  "Action": [
    "appconfig:StartConfigurationSession",
    "appconfig:GetLatestConfiguration"
  ],
  "Resource": "*"
}
```

Long polls consume AppConfig Data requests and can add cost; choose the service
poll interval deliberately or use the AWS AppConfig Agent when its local
sidecar model is a better operational fit. The module does not install or
manage the Agent. Floci/LocalStack support for this API is not assumed: use the
fake session contract for deterministic tests, and run a real smoke only when
`BLUETAPE4K_APPCONFIG_REAL_SMOKE=true` and explicit AWS identifiers and
credentials are present.

### Import precedence

| Situation | Result |
| --- | --- |
| Later entry in one comma-separated or YAML list | Later import overrides an earlier value. |
| Profile-specific document | Spring Boot selects the profile document; the resolver does not append a remote profile suffix. |
| Imported data versus the declaring document | Imported data takes precedence over the document that declares the import. |
| Legacy `EnvironmentPostProcessor` | Keep it when refresh or its existing property-source order is required. |

### Failure policy

| Condition | Required import | `optional:` import |
| --- | --- | --- |
| Backend-specific not-found | Startup failure | Import is skipped. |
| Authentication, credential, network, parse, or missing `SecretString` | Startup failure | Startup failure. |
| `bluetape4k.aws.enabled=false` or backend disabled | Empty no-op source; no client or network call | Same behavior. |

Service region and endpoint override take precedence over shared AWS defaults.
When Web Identity is enabled, STS and the configured role ARN, session name, and
readable token file are required; malformed settings fail closed instead of
falling back to the default credential chain. ConfigData startup clients do not
discover application bean customizers. An application may register an explicit
`AwsSyncClientCustomizer` through `BootstrapRegistryInitializer`.

### Migration from the legacy source

| Requirement | Recommended path |
| --- | --- |
| Read a remote value once during startup | `spring.config.import` ConfigData. |
| Refresh values after startup | Existing `EnvironmentPostProcessor` properties. |
| Preserve an established legacy property-source winner | Existing `EnvironmentPostProcessor` properties. |
| Skip only a missing optional backend source | Prefix that location with `optional:`. |

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
