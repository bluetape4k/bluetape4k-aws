# Issue #194 CloudWatch Spring Boot Integration Plan

- Date: 2026-06-07
- Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/194
- Spec: `docs/superpowers/specs/2026-06-07-issue-194-cloudwatch-spring-boot-design.md`
- Gate: plan

## Decisions

- Implement CloudWatch and CloudWatch Logs as separate optional Spring Boot
  auto-configurations under `io.bluetape4k.aws.spring.cloudwatch`.
- Use AWS SDK v2 async clients, matching the existing SQS/SNS async client
  pattern and Spring Cloud AWS CloudWatch async-client direction.
- Reuse `aws-java` coroutine extensions instead of duplicating AWS request
  construction in `aws-spring-boot`.
- Add AWS SDK v2 CloudWatch and CloudWatch Logs dependencies to
  `aws-spring-boot` as `compileOnly` plus `testImplementation`.
- Add `micrometer-core` as a normal `aws-spring-boot` dependency because Spring
  Boot applications treat Micrometer as a default observability surface.
- Provide explicit `MeterRegistry`-based metric publishing helpers, but do not
  add `micrometer-registry-cloudwatch` or replace/create global registries.
- Do not add emulator-backed CloudWatch tests unless a quick focused check
  proves reliability; unit and `ApplicationContextRunner` coverage are the
  required gate.

## Step Plan

1. Add dependency surface
   - Update `aws-spring-boot/build.gradle.kts` with `libs.aws2.cloudwatch` and
     `libs.aws2.cloudwatchlogs` as `compileOnly` and `testImplementation`.
   - Add a `micrometer-core` alias to `gradle/libs.versions.toml` and use it as
     an `api` dependency in `aws-spring-boot`; reuse Spring Boot dependency
     management for the version.

2. Add properties and constants
   - Add `CloudWatchProperties` with `enabled`, `region`, `endpointOverride`,
     `namespace`, `batchSize`, and nested `micrometer.enabled`.
   - Add `CloudWatchLogsProperties` with `enabled`, `region`,
     `endpointOverride`, `logGroupName`, `logStreamName`, and `batchSize`.
   - Use serializable data classes and validation for endpoint/region and
     positive batch sizes.

3. Add operations contracts and templates
   - Add `CloudWatchOperations` and `CloudWatchCoroutinesTemplate`.
   - Add `CloudWatchLogsOperations` and `CloudWatchLogsCoroutinesTemplate`.
   - Add `CloudWatchMeterPublishingOperations` and a default implementation
     that reads selected `Meter` snapshots from `MeterRegistry` and converts
     finite Micrometer measurements into CloudWatch `MetricDatum` values.
   - Delegate to existing `aws-java` coroutine extensions.
   - Validate configured defaults before default-namespace/group/stream calls.

4. Add auto-configuration
   - Add `CloudWatchAutoConfiguration` with:
     - `@ConditionalOnClass` for `SdkAsyncHttpClient` and
       `CloudWatchAsyncClient`
     - service-specific `@ConditionalOnProperty`
     - shared credentials, http client, global async customizers, and
       service-specific customizer support
     - conditional Micrometer publishing helper when `MeterRegistry` is present
       and `bluetape4k.aws.cloudwatch.micrometer.enabled=true`
   - Add `CloudWatchLogsAutoConfiguration` with the same pattern for
     `CloudWatchLogsAsyncClient`.
   - Register both classes in `AutoConfiguration.imports`.

5. Add tests
   - Use `ApplicationContextRunner` coverage for bean registration, disabled
     properties, custom bean backoff, endpoint override validation, property
     binding, and classpath absence.
   - Use MockK field mocks reset with `clearMocks(...)` in `@BeforeEach`.
   - Add operation/template tests for default namespace/group validation,
     delegation to AWS SDK async clients through completed futures, and
     Micrometer `SimpleMeterRegistry` meter snapshot conversion.

6. Update documentation
   - Update `aws-spring-boot/README.md` and `README.ko.md` with properties and
     examples.
   - Update root `README.md` and `README.ko.md` service/module feature table if
     needed.
   - Update the `aws-spring-boot` architecture diagram only if README flow now
     needs a CloudWatch/Logs lane; apply `bluetape4k-diagram` if changed.

7. Review, lesson, and validation
   - Add implementation review with P0=0/P1=0.
   - Add `docs/lessons/2026-06-07-issue-194-cloudwatch-spring-boot.md`.
   - Run:
     - `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.cloudwatch.*'`
     - `./gradlew :bluetape4k-aws-spring-boot:test`
     - `git diff --check`

## Risks And Mitigations

- CloudWatch Logs sequence-token behavior has service-specific quirks.
  Mitigation: provide low-level publish operations only and avoid hiding AWS SDK
  errors or state management in this PR.
- Micrometer registry integration could expand scope. Mitigation: add only
  `micrometer-core` and explicit `MeterRegistry` snapshot publishing; leave
  CloudWatch registry auto-registration to a follow-up.
- Local emulator CloudWatch/Logs behavior may be incomplete. Mitigation: do not
  make emulator coverage mandatory unless quick verification proves it stable.

## Stop Condition

Stop when the feature is implemented, docs and lessons are updated, local
targeted/full module tests and diff check pass, implementation review reports
P0=0/P1=0, a PR is created with verified DoD body, and CI/PR review evidence is
available.
