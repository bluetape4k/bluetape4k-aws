# Issue 241 Floci Test Migration

## Context

Issue #241 follows the Floci-first policy from #239/#240 and moves
LocalStack-default AWS tests away from a LocalStack default.

## Decision

Use Floci as the default emulator for Java/Kotlin SDK wrapper tests, Ktor
runtime tests, and AWS example tests that expose emulator-aware fixtures. Keep
LocalStack as an explicit fallback instead of an automatic fallback chain.
Floci-unsupported APIs are guarded by assumptions so the default run stays green
and LocalStack still proves the legacy coverage.

## Outcome

The shared Java and Kotlin test bases now select `floci` by default and accept
`-Dbluetape4k.aws.emulator=localstack`. Ktor and AWS example module tests use
the same Floci-first selector where they previously created LocalStack directly.
The Java DynamoDB food Spring tests no longer depend on
`testcontainers.localstack.port`; they receive endpoint, region, and credentials
from the selected emulator.

## Verification

- `./gradlew :bluetape4k-aws-java:compileTestKotlin :bluetape4k-aws-kotlin:compileTestKotlin`
  passed.
- `./gradlew :bluetape4k-aws-java:test -Dbluetape4k.aws.emulator=floci` passed:
  243 passing, 14 pending.
- `./gradlew :bluetape4k-aws-kotlin:test -Dbluetape4k.aws.emulator=floci`
  passed: 489 passing, 12 pending.
- `./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.kms.KsmClientTest' --tests 'io.bluetape4k.aws.sns.SnsClientTest' -Dbluetape4k.aws.emulator=localstack`
  passed: 20 passing, 1 pending.
- `./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.kms.KmsClientTest' --tests 'io.bluetape4k.aws.kotlin.sns.SnsClientExtensionsTest' -Dbluetape4k.aws.emulator=localstack`
  passed: 23 passing, 1 pending.
- `./gradlew :bluetape4k-aws-ktor:test :aws-ktor-dynamodb-examples:test :aws-spring-boot-dynamodb-examples:test :aws-spring-boot-s3-examples:test :aws-spring-boot-sqs-examples:test -Dbluetape4k.aws.emulator=floci`
  passed: 69 + 2 + 3 + 1 + 1 tests.
- `./gradlew :bluetape4k-aws-ktor:test :aws-ktor-dynamodb-examples:test :aws-spring-boot-dynamodb-examples:test :aws-spring-boot-s3-examples:test :aws-spring-boot-sqs-examples:test -Dbluetape4k.aws.emulator=localstack`
  passed: 69 + 2 + 3 + 1 + 1 tests.

## Future Guidance

Do not implement automatic emulator fallback chains for ordered integration
tests. Pick one emulator per test task, record unsupported operations explicitly,
and verify fallback behavior with a separate explicit run.
