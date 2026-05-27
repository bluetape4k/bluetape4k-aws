# Issue 206 Spring AWSpring-Parity Examples

## Context

Issue #206 closes the Spring Boot example slice for the 0.3.0 AWSpring-parity
work. The milestone scope is S3/SQS plus shared defaults; CloudWatch, IMDS, and
DAX are deferred to 0.4.0.

## Decision

Extend existing Spring Boot S3/SQS examples instead of creating another module.
The examples now cover optional S3 client-side encryption, typed SQS payload
conversion, manual acknowledgement, retry, and listener interceptor events.

## Outcome

The work also fixed two SQS listener integration gaps found by the examples:

- Kotlin suspend listener synthetic/static helper methods must not be registered
  as listener methods.
- Jackson-backed SQS conversion must auto-configure before the SQS listener
  post-processor is created.

## Verification

- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin :aws-spring-boot-s3-examples:test :aws-spring-boot-sqs-examples:test --no-daemon --max-workers=1`
- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*SqsAutoConfigurationTest' --tests '*SqsListenerAwsEmulatorTest' :aws-spring-boot-s3-examples:test :aws-spring-boot-sqs-examples:test --no-daemon --max-workers=1`
- `git diff --check`

## Future Guard

Example issues should be treated as adoption tests. If an example needs a
workaround for a public auto-configuration contract, fix the contract and cover
it with the example test rather than hiding the issue in example-only wiring.
