# Issue #201 Ktor CloudWatch and CloudWatch Logs implementation plan

Date: 2026-06-08
Issue: #201
Spec: `docs/superpowers/specs/2026-06-08-issue-201-ktor-cloudwatch-design.md`

## Objective

Add optional CloudWatch and CloudWatch Logs Ktor plugins to `aws-ktor` using
existing `bluetape4k-aws-java` coroutine helpers, while preserving opt-in
publishing, AWS SDK dependency optionality, lifecycle ownership, cancellation,
and README parity.

## Task Plan

### 1. Dependency and shared defaults wiring

Files:

- `aws-ktor/build.gradle.kts`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/AwsKtorCore.kt`
- `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/AwsKtorCoreTest.kt`

Actions:

- Add `compileOnly(libs.aws2.cloudwatch)` and
  `compileOnly(libs.aws2.cloudwatchlogs)`.
- Add matching `testImplementation` dependencies.
- Add `AwsKtorCloudWatchAsyncClientCustomizer` and
  `AwsKtorCloudWatchLogsAsyncClientCustomizer`.
- Add opt-in `AwsKtorCoreConfig.ktorCore(...)` wiring that installs the shared
  `bluetape4k-ktor-core` baseline from the existing `AwsKtorCore` setup block.
- Extend `AwsKtorDefaults` constructor, transient storage, accessors,
  equality/hash/toString, and `AwsKtorCoreConfig`.
- Add tests for default storage, `bluetape4k-ktor-core` baseline installation,
  and customizer ordering.

DoD:

- Shared and service-local customizers run in deterministic order.
- `AwsKtorCore { ktorCore() }` exposes `bluetape4k-ktor-core` health/readiness
  routes and is verified with `bluetape4k-ktor-testing` assertions.
- Endpoint override still requires effective region.
- Public KDoc is English.

### 2. CloudWatch metrics operations and plugin

Files:

- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorOperations.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorTemplate.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorPluginConfig.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorRuntime.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorPlugin.kt`

Actions:

- Implement operations facade over `CloudWatchAsyncClient` coroutine
  extensions.
- Batch `putMetricData` by `batchSize`, skip empty metric lists, and require
  non-blank default namespace only for default-namespace methods.
- Add plugin config for injected operations, injected clients, plugin-created
  clients, client customizers, region/endpoint/credentials, namespace, and
  `batchSize`.
- Store operations/runtime attributes only when enabled.
- Close only plugin-owned clients once on `ApplicationStopping`.
- Create plugin-owned clients during plugin configuration conversion and start
  no background work for metrics. Stop closes owned clients on
  `ApplicationStopping`.

Tests:

- Disabled plugin stores no attributes and `cloudWatchOrNull()` returns null.
- Injected operations bypass client-only validation.
- Injected client is not closed on runtime stop.
- Plugin-owned client closes once.
- Empty metric list does not call AWS.
- Metric batches split by configured `batchSize`.
- Missing default namespace fails only for default-namespace methods.
- Cancellation from underlying AWS futures propagates for both
  `putMetricData` and `listMetrics` suspend operations.

DoD:

- No AWS call happens during install.
- Ownership and validation match existing SQS/IMDS Ktor patterns.
- Public KDoc is English.

### 3. CloudWatch Micrometer snapshot bridge

Files:

- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorMeterPublishingOperations.kt`
- `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorMeterPublishingTemplateTest.kt`

Actions:

- Mirror the Spring `CloudWatchMeterPublishingOperations` behavior in the Ktor
  package.
- Read an existing `MeterRegistry` only when application code invokes
  `publishMeters` or `publishMeter`.
- Reuse `CloudWatchKtorOperations` for actual publishing.

Tests:

- Empty registry or filtered-out meters returns `emptyList()` and does not call
  AWS operations.
- Selected finite measurements map to `MetricDatum` with Micrometer tags as
  dimensions.
- `publishMeter` rejects blank names.
- Cancellation from `CloudWatchKtorOperations.putMetricData` propagates.

DoD:

- Micrometer remains `compileOnly` and opt-in.
- No global registry/exporter/scheduler is introduced.

### 4. CloudWatch Logs operations, runtime, and plugin

Files:

- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchLogsKtorOperations.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchLogsKtorTemplate.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchLogsKtorPluginConfig.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchLogsKtorRuntime.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchLogsKtorPlugin.kt`

Actions:

- Add `CloudWatchLogStream` as a `Serializable` value object for log
  group/stream identity.
- Implement operations facade over `CloudWatchLogsAsyncClient` coroutine
  extensions.
- Batch `putLogEvents`, skip empty log-event lists, and require default
  log group/stream only for default methods.
- Implement buffered runtime with `Mutex`, explicit `append`, explicit
  `flush`, optional periodic flush, and bounded `stop`.
- Sort buffered events by timestamp before publishing.
- Add opt-in startup setup for log group and log stream.
- Store operations/runtime attributes only when enabled.
- Close only plugin-owned clients once.
- Create plugin-owned clients during plugin configuration conversion, run
  opt-in group/stream setup and periodic flush on `ApplicationStarted`, and
  stop/flush/close on `ApplicationStopping`.

Tests:

- Disabled plugin stores no attributes and `cloudWatchLogsOrNull()` returns
  null.
- Injected operations bypass client-only validation.
- Injected client is not closed; plugin-owned client closes once.
- Empty `putLogEvents` and empty `flush` do not call AWS.
- Default `append` requires non-blank log group and stream.
- `CloudWatchLogStream` rejects blank log group or stream names.
- Batching splits by configured `batchSize` and sorts events by timestamp.
- `flush()` is safe under concurrent calls and does not duplicate events.
- `stop()` flushes buffered events, respects `shutdownFlushTimeout`, and closes
  plugin-owned clients even when flush times out.
- Opt-in startup setup calls create group/stream only when configured.
- Cancellation from underlying suspend AWS calls propagates for
  `createLogGroup`, `createLogStream`, `putLogEvents`, `describeLogGroups`,
  `describeLogStreams`, and buffered `flush`.

DoD:

- Buffered publishing is explicit; installing the plugin alone does not publish.
- Shutdown behavior is bounded and idempotent.

### 5. README and lesson

Files:

- `aws-ktor/README.md`
- `aws-ktor/README.ko.md`
- `docs/lessons/2026-06-08-issue-201-ktor-cloudwatch.md`

Actions:

- Update feature list, quick-start snippets, CloudWatch/Logs usage section, and
  options table in both README locales.
- Show `AwsKtorCore { ktorCore() }` in the shared defaults example so users see
  the bluetape4k Ktor ecosystem path before raw Ktor-only setup.
- State that publishing/setup are opt-in and no global logging appender or
  Micrometer registry is replaced.
- Add concise lesson with context, decision, outcome, verification, and future
  guard.

DoD:

- README locale parity is preserved.
- Lesson captures ownership, opt-in publishing, and cancellation/shutdown guard.

### 6. Verification

Commands:

```bash
./gradlew :bluetape4k-aws-ktor:compileKotlin
./gradlew :bluetape4k-aws-ktor:compileTestKotlin
./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.AwsKtorCoreTest' --tests 'io.bluetape4k.aws.ktor.cloudwatch.*'
./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.cloudwatch.*'
./gradlew :bluetape4k-aws-ktor:test
./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency cloudwatch --configuration compileClasspath
./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency cloudwatchlogs --configuration compileClasspath
git diff --check
```

Expected evidence:

- Focused CloudWatch tests pass.
- `AwsKtorCoreTest` proves `bluetape4k-ktor-core` baseline installation and
  `bluetape4k-ktor-testing` assertions are used.
- Full `aws-ktor` tests pass.
- Dependency insight shows service SDK jars are available to `compileOnly` and
  not promoted to unconditional `api`.
- `git diff --check` passes.

## Rollback and Compatibility

- The change is additive: new plugin types, new optional dependencies, and new
  `AwsKtorCore` customizer lists plus an opt-in `ktorCore()` bridge.
- Existing S3, SQS, DynamoDB, IMDS, and Exposed APIs should remain source
  compatible.
- If `AwsKtorDefaults` equality behavior regresses, rollback the customizer
  extension and keep service customizers local to each plugin config.

## Out of Scope

- CloudWatch emulator integration tests.
- Global Ktor logging appender.
- Scheduled Micrometer CloudWatch registry exporter.
- AWS Kotlin SDK CloudWatch plugin.
