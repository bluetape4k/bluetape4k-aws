# Issue 194 CloudWatch Spring Boot auto-configuration

## Context

`aws-spring-boot` needed first-class CloudWatch and CloudWatch Logs support while
preserving the repository rule that AWS SDK service jars stay optional for
consumers.

## Decision

- Add CloudWatch and CloudWatch Logs auto-configurations after the shared
  `AwsAutoConfiguration` phase.
- Keep `software.amazon.awssdk:cloudwatch` and `cloudwatchlogs` as `compileOnly`
  in production and `testImplementation` for slice tests.
- Add `micrometer-core` as a normal `aws-spring-boot` dependency because Spring
  Boot applications already treat Micrometer as an observability baseline.
- Provide `CloudWatchMeterPublishingOperations` as an explicit snapshot helper
  over the current `MeterRegistry`, not as a `micrometer-registry-cloudwatch`
  replacement or scheduler.

## Outcome

Applications get coroutine metric/log publishing helpers, default namespace and
log group/stream property support, and a Micrometer snapshot publisher when a
`MeterRegistry` bean exists. Existing service auto-configuration patterns and
AWS credential/customizer paths remain unchanged.

## Verification

- `dependencyInsight` confirmed `io.micrometer:micrometer-core:1.16.5` on
  `compileClasspath`.
- Focused CloudWatch tests passed with 21 tests.
- Full `:bluetape4k-aws-spring-boot:test` passed with 178 tests.
- README SVG parsed, PNG rendered, and the updated architecture diagram was
  visually inspected at README scale.
- `git diff --check` passed.

## Future Guard

Do not conflate explicit metric snapshot publishing with Micrometer registry
export. If users need scheduled CloudWatch registry export, create a separate
issue for `micrometer-registry-cloudwatch` integration and document its
operational tradeoffs separately.
