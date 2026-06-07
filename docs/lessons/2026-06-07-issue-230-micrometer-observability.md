# Issue #230 Micrometer Observability

Date: 2026-06-07
Issue: #230

## Context

`aws-spring-boot` already treated Micrometer as a Spring Boot baseline
dependency for CloudWatch meter publishing, but SQS and S3 operations did not
emit operation timers automatically. `aws-ktor` had SQS observer hooks and S3
helpers but intentionally avoided forcing Micrometer on every Ktor user.

## Decision

Add Micrometer support through the existing extension points:

- In Spring Boot, wrap auto-configured SQS and S3 operation beans when a
  `MeterRegistry` bean is present.
- Keep concrete `SqsCoroutinesTemplate` and `S3CoroutinesTemplate` beans
  available; register Micrometer decorators as primary operation beans in
  separate auto-configuration phases.
- Add a Micrometer SQS listener interceptor for receive, handler, and
  acknowledgement phases.
- In Ktor, keep Micrometer opt-in through a SQS observer bridge and an S3 client
  wrapper.
- Keep default tags low cardinality; do not tag queue URLs, message IDs,
  receipt handles, S3 object keys, or raw exception messages.
- Add the Spring Boot BOM platform to `aws-ktor` compile/test scopes so
  optional `micrometer-core` resolves from the governed catalog without adding a
  Spring runtime dependency.

## Outcome

Spring Boot applications with a `MeterRegistry` now get SQS/S3 operation timers
automatically through primary operation decorators while retaining concrete
template beans for compatibility. Ktor applications can opt in with
`micrometer(meterRegistry)` for SQS consumer events and
`s3.withMicrometer(meterRegistry)` for selected S3 client calls. Documentation
now describes the dependency boundary and tag policy in English and Korean
README files.

## Verification

- `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency micrometer-core --configuration compileClasspath`
  confirmed `io.micrometer:micrometer-core:1.16.5`.
- `./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency micrometer-core --configuration compileClasspath`
  confirmed `io.micrometer:micrometer-core:1.16.5`.
- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-ktor:compileKotlin`
  passed.
- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.*Micrometer*' :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.*Micrometer*'`
  passed.
- `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sqs.SqsAutoConfigurationTest' --tests 'io.bluetape4k.aws.spring.s3.S3AutoConfigurationTest'`
  passed.
- `./gradlew :bluetape4k-aws-spring-boot:test :bluetape4k-aws-ktor:test`
  passed with 195 Spring Boot tests and 85 Ktor tests.
- `git diff --check` passed.

## Future Guard

When adding optional libraries whose catalog alias has no explicit version,
check whether the consuming module already imports the governing platform. For
observability, keep library-level adapters low-cardinality by default and make
high-cardinality tags explicit opt-ins.
