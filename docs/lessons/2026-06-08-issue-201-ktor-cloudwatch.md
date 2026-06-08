# Issue #201 Ktor CloudWatch and CloudWatch Logs

Date: 2026-06-08
Issue: #201

## Context

`aws-ktor` had shared AWS defaults, SQS lifecycle handling, and IMDS passive
metadata access, but no Ktor-facing CloudWatch or CloudWatch Logs integration.
The existing Spring Boot CloudWatch work set the baseline for optional AWS SDK
service jars and explicit Micrometer snapshot publishing.

## Decision

- Add optional Ktor CloudWatch and CloudWatch Logs plugins backed by existing
  `bluetape4k-aws-java` coroutine extensions.
- Keep `software.amazon.awssdk:cloudwatch` and `cloudwatchlogs` as
  `compileOnly` production dependencies and test dependencies for `aws-ktor`.
- Preserve injected-client ownership: plugin-created clients close on
  `ApplicationStopping`, injected clients and injected operations remain
  application-owned.
- Keep publishing explicit. Installing a plugin stores operations/runtime only;
  metrics and log events publish only after application code invokes the
  operations or appends events to the logs runtime.
- Use `CloudWatchLogStream` for log group/stream identity to avoid same-type
  positional string mistakes in the new public API.
- Reuse the bluetape4k Ktor ecosystem directly: `AwsKtorCore { ktorCore() }`
  installs the shared `bluetape4k-ktor-core` baseline, and Ktor HTTP assertions
  in tests use `bluetape4k-ktor-testing`.

## Outcome

`aws-ktor` now has CloudWatch metric operations, CloudWatch Logs operations,
buffered log publishing with bounded shutdown flush, and an explicit
Micrometer snapshot publisher for CloudWatch. README English/Korean files cover
dependencies, examples, options, ownership, `ktorCore()` baseline setup, and
opt-in behavior.

## Verification

- `./gradlew :bluetape4k-aws-ktor:compileKotlin` passed.
- `./gradlew :bluetape4k-aws-ktor:compileTestKotlin` passed.
- `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.AwsKtorCoreTest' --tests 'io.bluetape4k.aws.ktor.cloudwatch.*'`
  passed with 41 focused tests.
- `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.cloudwatch.*'`
  passed with 38 focused tests before the `ktorCore()` bridge follow-up.
- `./gradlew :bluetape4k-aws-ktor:test` passed with 126 tests.

## Future Guard

Do not add a global logging appender or scheduled CloudWatch Micrometer
registry exporter to `aws-ktor` without a separate issue. Keep CloudWatch
publishing explicit, preserve cancellation propagation, and test lifecycle
ownership whenever a Ktor plugin creates an AWS SDK client. Before adding new
Ktor setup or test utilities, check `bluetape4k-projects/ktor/*` and prefer the
shared Ktor core/testing modules when the dependency boundary already allows it.
