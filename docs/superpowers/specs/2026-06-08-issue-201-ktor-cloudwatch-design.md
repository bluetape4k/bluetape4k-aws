# Issue #201 Ktor CloudWatch and CloudWatch Logs design

Date: 2026-06-08
Issue: #201
Milestone: 0.4.0

## Context

`aws-spring-boot` already provides CloudWatch and CloudWatch Logs operations
from issue #194. `aws-ktor` has Ktor-native lifecycle patterns from SQS and
IMDS, but no CloudWatch-facing plugin. Issue #201 asks for optional Ktor
plugins that can publish CloudWatch metrics and CloudWatch Logs events while
preserving Ktor lifecycle ownership, coroutine operations, and opt-in behavior.

Current reusable assets:

- `bluetape4k-aws-java` already exposes AWS SDK Java v2 coroutine extensions:
  `CloudWatchAsyncClient.putMetricData`, `CloudWatchAsyncClient.listMetrics`,
  `CloudWatchLogsAsyncClient.createLogGroup`, `createLogStream`,
  `putLogEvents`, `describeLogGroups`, and `describeLogStreams`.
- `aws-ktor` already uses plugin-created vs injected-client ownership in
  `SqsConsumer` and `ImdsKtorPlugin`.
- `AwsKtorCore` stores shared region, endpoint override, credentials, and
  service client customizers in application attributes.
- `bluetape4k-projects` provides `bluetape4k-ktor-core` and
  `bluetape4k-ktor-testing`; `aws-ktor` should use those helpers for shared
  Ktor baseline installation and Ktor HTTP test assertions instead of treating
  raw Ktor server setup as the only path.
- Ktor Micrometer support is optional through `compileOnly(micrometer-core)` and
  explicit helper objects, not through global Ktor metrics replacement.

## Goals

1. Add `CloudWatchKtorPlugin` for CloudWatch metric operations.
2. Add `CloudWatchLogsKtorPlugin` for CloudWatch Logs operations and explicit
   batched log-event publishing.
3. Keep AWS service dependencies optional for consumers:
   `software.amazon.awssdk:cloudwatch` and `cloudwatchlogs` are `compileOnly`
   in production and `testImplementation` in this module.
4. Reuse `bluetape4k-aws-java` coroutine extensions instead of duplicating AWS
   request plumbing.
5. Reuse `bluetape4k-ktor-core` through `AwsKtorCore` so AWS Ktor applications
   can opt into the shared bluetape4k Ktor baseline from the same setup block.
6. Preserve ownership semantics:
   injected clients and injected operations are never closed by the plugin;
   plugin-created clients are closed exactly once on Ktor stop.
7. Keep publishing opt-in:
   installing either plugin does not call AWS or publish data by default.
8. Provide bounded, cancellation-safe shutdown flush for buffered log events.
9. Update English and Korean `aws-ktor` README files.

## Non-Goals

- Do not replace global Ktor logging appenders.
- Do not register a scheduled Micrometer CloudWatch registry/exporter.
- Do not require CloudWatch, LocalStack, or Floci during local development or
  normal CI.
- Do not expose AWS Kotlin SDK CloudWatch APIs in this issue. The existing
  CloudWatch helpers are AWS SDK Java v2 based and match the Spring work.
- Do not create log groups or streams at install time by default.

## Proposed API

### Shared Ktor defaults

Extend `AwsKtorCore` with service-specific builder customizers:

- `AwsKtorCoreConfig.ktorCore(...)`, which installs the shared
  `bluetape4k-ktor-core` baseline only when explicitly requested.
- `AwsKtorCloudWatchAsyncClientCustomizer`
- `AwsKtorCloudWatchLogsAsyncClientCustomizer`
- `AwsKtorDefaults.cloudWatchAsyncClientCustomizers`
- `AwsKtorDefaults.cloudWatchLogsAsyncClientCustomizers`
- `AwsKtorCoreConfig.cloudWatchAsyncClient { ... }`
- `AwsKtorCoreConfig.cloudWatchLogsAsyncClient { ... }`

These customizers apply only to plugin-created Java SDK v2 async clients, after
shared defaults and before service-local customizers.

### CloudWatch metrics

Package: `io.bluetape4k.aws.ktor.cloudwatch`

Public types:

- `CloudWatchKtorOperations`
- `CloudWatchKtorTemplate`
- `CloudWatchKtorPluginConfig`
- `CloudWatchKtorRuntime`
- `CloudWatchKtorPlugin`
- `CloudWatchKtorPluginConfig.cloudWatchAsyncClient { ... }`
- `Application.cloudWatch()`
- `Application.cloudWatchOrNull()`

Operations contract:

- `putMetricData(metricData)` uses the configured default namespace and rejects
  missing/blank namespace.
- `putMetricData(namespace, metricData)` publishes one or more `MetricDatum`
  values, batching by `batchSize`.
- `putMetricDatum(metricDatum)` and `putMetricDatum(namespace, metricDatum)`
  are convenience wrappers.
- `listMetrics(namespace?, metricName?, dimensions?)` delegates to the existing
  coroutine extension.
- Empty metric lists return `emptyList()` and do not call AWS.

Configuration:

- `enabled: Boolean = true`
- `cloudWatchAsyncClient: CloudWatchAsyncClient? = null`
- `cloudWatchOperations: CloudWatchKtorOperations? = null`
- `region: String? = null`
- `endpointOverride: URI? = null`
- `credentialsProvider: AwsCredentialsProvider? = null`
- `namespace: String? = null`
- `batchSize: Int = 1000`
- service-local client customizers

Validation:

- `batchSize` must be `1..1000`.
- `endpointOverride` requires an effective region.
- Injected operations bypass client-only validation.

Micrometer bridge:

- Add `CloudWatchKtorMeterPublishingOperations` and
  `CloudWatchKtorMeterPublishingTemplate`, mirroring the Spring snapshot helper.
- It reads an existing `MeterRegistry` only when application code calls
  `publishMeters` or `publishMeter`.
- It does not register a global registry, a scheduler, or automatic publishing.

### CloudWatch Logs

Package: `io.bluetape4k.aws.ktor.cloudwatch`

Public types:

- `CloudWatchLogStream`
- `CloudWatchLogsKtorOperations`
- `CloudWatchLogsKtorTemplate`
- `CloudWatchLogsKtorPluginConfig`
- `CloudWatchLogsKtorRuntime`
- `CloudWatchLogsKtorPlugin`
- `CloudWatchLogsKtorPluginConfig.cloudWatchLogsAsyncClient { ... }`
- `Application.cloudWatchLogs()`
- `Application.cloudWatchLogsOrNull()`

Operations contract:

- `createLogGroup(logGroupName)`
- `createLogStream(logStream)` where `logStream` is a `CloudWatchLogStream`
  value object containing `logGroupName` and `logStreamName`.
- `putLogEvents(logEvents)` using configured default log group and stream
- `putLogEvents(logStream, logEvents)`
- `describeLogGroups(logGroupNamePrefix?)`
- `describeLogStreams(logGroupName, logStreamNamePrefix?)`
- Empty log-event lists return `emptyList()` and do not call AWS.

Runtime buffered publishing:

- `CloudWatchLogsKtorRuntime.append(message, timestamp = Instant.now())` appends
  one explicit event to an in-memory buffer.
- `append(InputLogEvent)` appends an already-built event.
- `flush()` sends buffered events in batches of `batchSize`.
- `flush()` returns without calling AWS when the buffer is empty.
- `start()` launches one optional periodic flush job only when the plugin is
  enabled; it remains idle until events are appended.
- `stop()` cancels periodic work, flushes remaining buffered events within
  `shutdownFlushTimeout`, closes only plugin-owned clients, and then returns.
- If shutdown flush times out, the runtime cancels the flush and still closes a
  plugin-owned client once.

Configuration:

- `enabled: Boolean = true`
- `cloudWatchLogsAsyncClient: CloudWatchLogsAsyncClient? = null`
- `cloudWatchLogsOperations: CloudWatchLogsKtorOperations? = null`
- `region: String? = null`
- `endpointOverride: URI? = null`
- `credentialsProvider: AwsCredentialsProvider? = null`
- `logGroupName: String? = null`
- `logStreamName: String? = null`
- `batchSize: Int = 10000`
- `flushInterval: Duration = Duration.ofSeconds(5)`
- `shutdownFlushTimeout: Duration = Duration.ofSeconds(5)`
- `createLogGroupOnStart: Boolean = false`
- `createLogStreamOnStart: Boolean = false`
- service-local client customizers

Validation:

- `batchSize` must be `1..10000`.
- `flushInterval` and `shutdownFlushTimeout` must be positive.
- `endpointOverride` requires an effective region.
- Default `putLogEvents`, `append`, and startup setup require non-blank
  `logGroupName` and `logStreamName`.
- Injected operations bypass client-only validation but still require default
  log identifiers when default/buffered publishing is used.
- Public methods that need both log group and stream use `CloudWatchLogStream`
  to avoid same-type positional string mistakes.

## Lifecycle and Failure Modes

- Installing a plugin stores operations/runtime in application attributes only;
  it does not call AWS unless `createLogGroupOnStart` or
  `createLogStreamOnStart` is explicitly enabled for logs.
- `CloudWatchKtorRuntime.stop()` and `CloudWatchLogsKtorRuntime.stop()` are
  idempotent.
- SDK client close happens on `ApplicationStopping` with `Dispatchers.IO`,
  following existing Ktor plugin patterns.
- Retry behavior is delegated to the AWS SDK client configuration. The Ktor
  plugin does not add an additional retry loop; applications can customize the
  plugin-created client builder when they need service-specific retry policy.
- Suspend AWS calls must rethrow `CancellationException` and avoid
  `runCatching` around suspend calls.
- Buffered CloudWatch Logs access is protected by `Mutex` to prevent duplicate
  flushes or lost events under concurrent append/flush calls.
- Log events are sorted by timestamp before publishing because CloudWatch Logs
  expects chronological order in a batch.

## Documentation Requirements

Update both `aws-ktor/README.md` and `aws-ktor/README.ko.md`:

- Feature list entry for CloudWatch and CloudWatch Logs plugins.
- Shared `AwsKtorCore` example that shows `ktorCore()` for applications that
  want the `bluetape4k-ktor-core` JSON/status/health baseline.
- Shared `AwsKtorCore` defaults example including CloudWatch customizers only
  if it stays concise.
- CloudWatch metrics snippet showing explicit `putMetricDatum`.
- CloudWatch Logs snippet showing explicit `append` and shutdown flush behavior.
- Option table for namespace, log group/stream, batch size, flush interval, and
  shutdown flush timeout.
- Note that no AWS publish occurs by default and no global logging appender or
  Micrometer registry is replaced.
- New public API KDoc must be English and state ownership, opt-in publishing,
  and shutdown behavior.

## Acceptance Checks

- Disabled plugins store no application attributes and expose `*OrNull()` as
  null.
- Injected operations bypass client-only validation.
- Injected SDK clients remain application-owned and are not closed.
- Plugin-created SDK clients close exactly once.
- CloudWatch metric batching uses `batchSize` and skips empty batches.
- CloudWatch Logs batching uses `batchSize`, flushes on stop, and respects
  `shutdownFlushTimeout`.
- Empty CloudWatch Logs flushes do not call AWS.
- Startup setup for log group/stream is opt-in and disabled by default.
- Micrometer snapshot bridge publishes only when explicitly invoked.
- `aws-ktor` README English/Korean files are updated together.

## Risks

- CloudWatch Logs sequencing tokens are no longer required by modern
  `PutLogEvents`, but ordering still matters. The implementation must sort
  events before sending.
- Periodic flushing can create hidden AWS calls. The design keeps it idle until
  application code appends events and documents that append is an explicit
  publish request.
- Adding shared customizers changes `AwsKtorDefaults` equality/hash behavior.
  Tests should cover new customizer ordering and defaults storage.
