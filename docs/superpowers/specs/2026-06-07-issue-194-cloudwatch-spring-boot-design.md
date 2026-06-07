# Issue #194 CloudWatch Spring Boot Integration Spec

- Date: 2026-06-07
- Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/194
- Work type: Type A Full Feature
- Target module: `aws-spring-boot`
- Gate: spec

## Background

`aws-java` already exposes AWS SDK v2 CloudWatch and CloudWatch Logs helpers:

- `io.bluetape4k.aws.cloudwatch.CloudWatchAsyncClientCoroutinesExtensions`
- `io.bluetape4k.aws.cloudwatch.CloudWatchLogsAsyncClientCoroutinesExtensions`
- `io.bluetape4k.aws.cloudwatch.model.metricDatumOf`
- `io.bluetape4k.aws.cloudwatch.model.cloudwatchlogs.inputLogEventOf`

`aws-spring-boot` already has the shared Spring Boot 4 AWS foundation:

- `AwsProperties`
- `AwsClientDefaults`
- `resolveClientDefaults(...)`
- `AwsAsyncClientCustomizer`
- `AwsClientCustomizer<B>`
- existing optional async-client patterns in SQS and SNS auto-configuration

Spring Cloud AWS current docs still position CloudWatch as optional
Micrometer/CloudWatch integration. bluetape4k should not clone that
implementation or replace Micrometer registries globally. Because this module
is a Spring Boot integration module, Micrometer core is an acceptable default
dependency and this issue should provide a thin, coroutine-friendly Spring Boot
adapter over existing bluetape4k/AWS SDK v2 and Micrometer surfaces.

## Goal

Add optional Spring Boot auto-configuration for CloudWatch custom metric
publishing, Micrometer meter snapshot publishing, and CloudWatch Logs event
publishing.

Applications should be able to inject:

- `CloudWatchAsyncClient`
- `CloudWatchOperations`
- `CloudWatchLogsAsyncClient`
- `CloudWatchLogsOperations`
- `CloudWatchMeterPublishingOperations` when a `MeterRegistry` is available

Only the relevant beans should appear when the AWS SDK service classes are on
the classpath and the service-specific `enabled` property is not disabled.

## Proposed Public Surface

### Properties

Use service-specific properties under the existing namespace:

- `bluetape4k.aws.cloudwatch.enabled`
- `bluetape4k.aws.cloudwatch.region`
- `bluetape4k.aws.cloudwatch.endpoint-override`
- `bluetape4k.aws.cloudwatch.namespace`
- `bluetape4k.aws.cloudwatch.batch-size`
- `bluetape4k.aws.cloudwatch.micrometer.enabled`
- `bluetape4k.aws.cloudwatch-logs.enabled`
- `bluetape4k.aws.cloudwatch-logs.region`
- `bluetape4k.aws.cloudwatch-logs.endpoint-override`
- `bluetape4k.aws.cloudwatch-logs.log-group-name`
- `bluetape4k.aws.cloudwatch-logs.log-stream-name`
- `bluetape4k.aws.cloudwatch-logs.batch-size`

`endpoint-override` requires a region, matching existing service-property
validation.

### Auto-configuration

Add:

- `CloudWatchAutoConfiguration`
- `CloudWatchLogsAutoConfiguration`

Both should be `@AutoConfiguration(after = [AwsAutoConfiguration::class])`,
guarded by `@ConditionalOnClass` for the relevant AWS SDK v2 async client and
`SdkAsyncHttpClient`, and registered in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

Client construction must reuse:

- `AwsProperties.resolveClientDefaults(...)`
- `AwsCredentialsProvider`
- optional `SdkAsyncHttpClient`
- `AwsAsyncClientCustomizer`
- service-specific `AwsClientCustomizer<CloudWatchAsyncClientBuilder>`
- service-specific `AwsClientCustomizer<CloudWatchLogsAsyncClientBuilder>`

### Operations

Add coroutine operations over the existing `aws-java` coroutine extensions:

- `CloudWatchOperations`
  - `putMetricData(namespace, metricData)`
  - `putMetricData(metricData)` using configured default namespace
  - `putMetricDatum(namespace, metricDatum)`
  - `putMetricDatum(metricDatum)` using configured default namespace
  - `listMetrics(namespace, metricName, dimensions)`
- `CloudWatchLogsOperations`
  - `createLogGroup(logGroupName)`
  - `createLogStream(logGroupName, logStreamName)`
  - `putLogEvents(logGroupName, logStreamName, logEvents)`
  - `putLogEvents(logEvents)` using configured default group and stream
  - `describeLogGroups(logGroupNamePrefix)`
  - `describeLogStreams(logGroupName, logStreamNamePrefix)`

Operations should validate caller input with bluetape4k validation helpers
where they add behavior beyond the lower-level helpers. Missing configured
namespace/group/stream should fail fast with `IllegalArgumentException`.

### Micrometer-Friendly Helper

Add `micrometer-core` as a normal `aws-spring-boot` dependency and provide a
lightweight helper over `MeterRegistry` for explicitly selected custom
application metrics.

The helper should:

- require an existing `MeterRegistry`;
- be guarded by `@ConditionalOnClass(MeterRegistry::class)`;
- be guarded by `@ConditionalOnBean(MeterRegistry::class)`;
- support publishing selected meter snapshots through `CloudWatchOperations`;
- avoid creating or replacing a global `MeterRegistry`;
- avoid pulling in `micrometer-registry-cloudwatch` in this PR.

## Non-goals

- No Spring Cloud AWS implementation clone.
- No global Micrometer registry replacement.
- No always-on CloudWatch publishing.
- No `micrometer-registry-cloudwatch` auto-registration in this PR.
- No Ktor CloudWatch plugin work; #201 owns that.
- No low-level `aws-java` or `aws-kotlin` API changes unless a compile problem
  proves a small compatibility fix is required.

## Expected Files

Likely touched files:

- `aws-spring-boot/build.gradle.kts`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/cloudwatch/*`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/cloudwatch/*`
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `aws-spring-boot/README.md`
- `aws-spring-boot/README.ko.md`
- root `README.md`
- root `README.ko.md`
- `docs/images/readme-diagrams/aws-spring-boot-architecture-01.*` if the
  architecture diagram changes
- `docs/review/*`
- `docs/lessons/*`

## Verification Requirements

Minimum local verification:

- `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.cloudwatch.*'`
- `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency micrometer-core --configuration compileClasspath`
- `./gradlew :bluetape4k-aws-spring-boot:test`
- `git diff --check`

Required review gates:

- spec review: P0=0, P1=0
- plan review: P0=0, P1=0
- implementation review: P0=0, P1=0
- PR review evidence before merge

## Open Questions

- Whether a future issue should add `micrometer-registry-cloudwatch`
  auto-registration for users who want native Micrometer CloudWatch export.
- Whether local emulator coverage for CloudWatch Logs is reliable enough under
  the repo's Floci-first policy. If not, unit and auto-configuration tests are
  sufficient for this issue and the gap should be documented.
