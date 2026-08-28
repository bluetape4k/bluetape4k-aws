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

## S3 ResourceLoader

The S3 auto-configuration resolves an exact object through Spring's resource
protocol:

```kotlin
val resource = applicationContext.getResource(
    "s3://order-config/config/application.yml",
)
```

The exact form is also valid for `@Value("s3://order-config/config/application.yml")`.
`ApplicationContext.getResources(...)` is not automatically intercepted by the
S3 pattern resolver. Inject the fixed-name pattern bean directly instead:

```kotlin
class ConfigReader(
    @Qualifier("s3ResourcePatternResolver")
    private val resources: ResourcePatternResolver,
) {
    fun yamlFiles(): Array<Resource> =
        resources.getResources("s3://order-config/config/**/*.yml")
}
```

Patterns are intentionally narrow: one literal bucket, a non-empty prefix, and
only `*`, `?`, and `**`. Cross-bucket patterns and root listings such as
`s3://order-config/*.json` or `s3://order-config/**` fail before AWS is called.
Writes and output streams are not supported. The default bean name
`s3ResourcePatternResolver` is reserved for the default or custom S3 pattern
implementation; a custom replacement must keep that name, and an unrelated
resolver must not claim it.

Exact reads require `s3:GetObject` on the same bucket/key prefix; S3 HEAD
metadata checks use that permission as well. Pattern listing additionally needs
`s3:ListBucket` with an `s3:prefix` condition for the same prefix. A minimal
policy therefore names only one bucket and its `config/` prefix, for example:

```json
{
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::order-config/config/*"
    },
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::order-config",
      "Condition": { "StringLike": { "s3:prefix": ["config/*"] } }
    }
  ]
}
```

Do not add `s3:ListAllMyBuckets` or permissions for another bucket. The
`bluetape4k.aws.s3.enabled=false` switch disables the complete S3 backend
auto-configuration, including these resolvers; it is not a resolver-only
switch. Custom replacements and direct `S3Resource` construction leave input
validation and IAM enforcement to the caller.

Pattern calls synchronously consume every paginator page on the caller thread.
The resolver adds no retry, cache, coalescing, or executor and uses the injected
client's timeout and transport settings. A short prefix can still scan many
objects and increase cost and heap, so keep the prefix non-empty and align it
with the IAM `s3:prefix` condition. Callers close each returned stream and use
resources only while the owning client and application context are alive. Error
diagnostics contain only bounded bucket/prefix values; secrets, credentials,
headers, and unrestricted cause text must not be logged or placed in metrics.

## SQS runtime

Tune concurrent consumers, long-poll duration, maximum messages, visibility timeout, failure visibility, and shutdown timeout together. More pollers can increase throughput but also raise in-flight messages and downstream pressure. Prefer native SQS redrive policies over ad-hoc endless retries.

## Observability

When a `MeterRegistry` is available, S3/SQS operations and listener phases can emit low-cardinality timers. Do not put bucket keys, message bodies, secret IDs, or unbounded exception text in metric tags. Correlate AWS request IDs in logs.

### SQS Observation rollout and rollback

SQS Observation has one runtime owner: `debop` approves activation, canary
promotion, dashboard changes, and rollback. Keep the automatic legacy listener
meter (`MicrometerSqsListenerInterceptor`) in a separate control cohort and the
operations meter (`MicrometerSqsOperations`) in both cohorts during migration.
Observation activation suppresses the automatic legacy listener meter in the
candidate cohort; manually registering it there would duplicate listener
instrumentation. Enable the observation property
only after a real registry and supporting handler are present, then restart or
redeploy. The runtime does not rebind when a property changes at runtime.

Run a canary for at least 30 minutes and 10,000 messages, satisfying both
limits. During the complete window, compare the control cohort's legacy
listener meter with the candidate cohort's new observation counts, plus
process-latency p95, redelivery rate, and DLQ visible count.
Do not switch dashboards or alerts to the new meters until the canary passes.
Stop the canary immediately for any of these signals:

- receive/process/acknowledgement observation counts do not match after
  accounting for empty polls and partial acknowledgements;
- process p95 rises by more than 20% against the same-run legacy baseline;
- redelivery rate rises by more than 1 percentage point; or
- a new DLQ item becomes visible.

Abort and roll back in this order:

1. stop receiving;
2. drain in-flight handlers;
3. wait for `STOPPING_RECEIVE -> DRAINING -> STOPPED`;
4. set `bluetape4k.aws.sqs.observation.enabled=false`;
5. restart or redeploy and read back the disabled property and restored legacy meters.

Preserve the legacy dashboards and alerts until the full canary window passes.
The heartbeat interval, timeout, lifecycle child-job, cancellation, and
visibility policy remain owned by issue #453; this observation change measures
that path and does not redefine its policy. The same Floci acceptance boundary
applies here: `FlociServer.Launcher.floci` proves the local emulator path, while
actual AWS and an OpenTelemetry exporter remain `N/A`.

### SQS Observation diagnostics

| Code | Meaning | Operator action |
| --- | --- | --- |
| `BT4K-SQS-OBS-101` | Observation activation prerequisites are missing, including Context Propagation, a registry, a non-NOOP registry, or a supporting Spring handler. | Inspect `context-propagation-missing`, `registry-missing`, `registry-noop`, or `handler-missing`; add the required runtime class/bean or leave observation disabled. A user factory alone is insufficient. |
| `BT4K-SQS-OBS-201` | The listener could not resolve a queue URL for the observation boundary. | Check the queue name/URL, endpoint configuration, and `getQueueUrl` permission before retrying. |
| `BT4K-SQS-OBS-202` | Foreground observation setup failed closed, or visibility-heartbeat telemetry setup/cleanup failed open. | Inspect the bounded `stage` and `reason`. Foreground setup remains primary. Heartbeat setup failure skips that visibility extension but lets the background handler continue, so duplicate delivery is possible; heartbeat cleanup preserves the visibility and handler result. |

For `BT4K-SQS-OBS-201`, the runtime does not publish a guessed queue name.
For `BT4K-SQS-OBS-202`, `reason=telemetry_setup` identifies a fail-closed
foreground setup failure. `reason=heartbeat_telemetry_setup` means that the current
visibility extension did not run while the background handler continued, so monitor for
duplicate delivery. `reason=telemetry_cleanup` does not change a successful heartbeat
visibility I/O or handler outcome. Treat all three as diagnostics to investigate,
not as permission to add raw queue URLs, receipt handles, or exception text to
tags.

## Modulith event runtime operations (Unreleased/develop)

The inbound adapter uses SQS at-least-once delivery. It verifies the source and
decodes the envelope before claiming `(type, eventId)`, publishes the local
event synchronously, completes the claim, and only then acknowledges SQS. A
completed duplicate skips the local handler and retries acknowledgement. A
failure before claim completion is not acknowledged and remains subject to the
queue visibility and redrive policy.

The default `InMemoryAwsModulithEventIdempotencyStore` is process-local. It
limits duplicate handling within one process, but it does not survive restart,
coordinate multiple instances, or make the local side effect and claim commit
atomic. Supply a durable `AwsModulithEventIdempotencyStore` for those needs.
This boundary is duplicate suppression over at-least-once delivery, not an
exactly-once guarantee. Asynchronous event listener completion is also outside
the acknowledgement boundary: completion means the synchronous
`ApplicationEventPublisher.publishEvent` call returned.

When `MeterRegistry` is present, the consumer registers
`bluetape4k.aws.modulith.events`, `bluetape4k.aws.modulith.events.latency`, and
`bluetape4k.aws.modulith.events.inflight`. The bounded tags are `service`,
`phase`, `outcome`, and `code`. Never add event IDs, payloads, message IDs,
TopicArns, queue URLs, or raw exception text as tags.

| Code | Retryable | Boundary | Required caller action |
| --- | --- | --- | --- |
| `BT4K-MOD-101` | no | Configuration, classpath, target, redrive guard | Stop deployment and inspect the condition report. |
| `BT4K-MOD-102` | no | Registration, serialization, envelope bound | Preserve the DLQ item and fix the registration or payload before replay. |
| `BT4K-MOD-103` | yes | Producer capacity or shutdown admission | Keep the Modulith publication incomplete and resubmit after checking in-flight work. |
| `BT4K-MOD-104` | yes | Target resolution or AWS publish | Check endpoint, permission, and SDK retry state before resubmission. |
| `BT4K-MOD-201` | no | Source mode, TopicArn, SNS signature | Do not acknowledge; quarantine the source and inspect queue policy. |
| `BT4K-MOD-202` | no | Malformed, unknown type/version, loop risk | Deploy a compatible consumer or inspect the DLQ before replay. |
| `BT4K-MOD-203` | yes | Claim, lease, fencing, completion | Keep the message unacknowledged and restore the store or wait for lease takeover. |
| `BT4K-MOD-204` | yes | Local dispatch, SQS acknowledgement, cleanup | Compare handler completion, claim state, and SQS delete before retrying. |

### Rollout and rollback

Deploy a consumer that understands every new `(type, version)` to all instances
before enabling its producer. Configure the queue DLQ/redrive policy before
starting the consumer; `redrive-required=true` makes its absence a startup
failure. For rollback, disable producer externalization first, wait for its
bounded close and preserve incomplete publications, then drain supported queue
and DLQ versions before disabling the consumer. Do not delete queued messages,
truncate the idempotency store, or downgrade while a newer version remains.

### Floci and real AWS evidence boundary

The Floci test matrix proves DIRECT SQS round trip, SNS-to-SQS transport with an
explicit `signature-not-proven` verifier fixture, FIFO group/deduplication,
duplicate acknowledgement, malformed-message no-ack, and DLQ redrive without an
AWS account. Floci does not prove production SNS certificate/signature behavior,
IAM resource policies, cross-account delivery, or real AWS redrive timing. The
SNS verifier has a separate signed request/certificate contract test; production
deployment still needs its own IAM and endpoint smoke evidence.

## Native CloudWatch registry

The module keeps two Micrometer paths separate:

| Setting | Responsibility | Default |
| --- | --- | --- |
| `bluetape4k.aws.cloudwatch.micrometer.enabled` | Existing helper for an explicit snapshot publish | `true` |
| `bluetape4k.aws.cloudwatch.micrometer.registry.enabled` | Scheduled native `CloudWatchMeterRegistry` exporter | `false` |

Add the optional runtime exporter and opt in only for the application that owns
the CloudWatch namespace:

```kotlin
implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot:${bluetape4kAwsVersion}")
runtimeOnly("io.micrometer:micrometer-registry-cloudwatch2")
implementation("software.amazon.awssdk:cloudwatch")
```

```yaml
bluetape4k:
  aws:
    cloudwatch:
      enabled: true
      region: ap-northeast-2
      namespace: OrderApi
      micrometer:
        registry:
          enabled: true
          namespace: OrderApiNative
          step: 1m
          batch-size: 20
          read-timeout: 10s
          common-tags:
            application: order-api
          filters:
            includes: ["orders.", "http.server.requests"]
            excludes: ["jvm."]
```

`registry.namespace` wins over `cloudwatch.namespace`; when both are absent,
startup fails before an AWS request. An empty `includes` list is an explicit
allow-all choice, so prefer a low-cardinality prefix allow-list and keep
secrets, object keys, request IDs, and raw exception text out of tags. An
existing application `MeterRegistry` (including `CompositeMeterRegistry`)
causes the native bean to back off. The native bean reuses the shared
`CloudWatchAsyncClient` and does not close it.

Micrometer sends `storageResolution=1` when `step < 1m`; this is a higher-cost
CloudWatch mode and emits a warning at startup. The official registry close
lifecycle waits for in-flight calls; with `n` batches, the worst-case close
wait is approximately `n * read-timeout`. Registry-level retry and cancellation
are not added: a failed or timed-out future is logged by the registry, while
AWS SDK retry policy remains the consumer's responsibility. Use production
HTTPS endpoints, the standard AWS credential provider chain, and the minimum
`cloudwatch:PutMetricData` permission. `--debug` condition output explains
back-off when the optional registry dependency is absent. Set
`bluetape4k.aws.cloudwatch.enabled=false` or `bluetape4k.aws.enabled=false` for
rollback; the existing explicit helper remains governed by its own setting.

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
- [Modulith diagnostics](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithExceptions.kt)
- [Modulith consumer metrics](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithMetrics.kt)
