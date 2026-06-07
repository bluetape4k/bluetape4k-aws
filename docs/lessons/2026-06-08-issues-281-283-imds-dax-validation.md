# Issues #281-#283 IMDS and DAX Validation Follow-up

Date: 2026-06-08

## Context

Post-merge review of the AWS 0.4.0 work found three P2 gaps: Ktor IMDS validation applied to injected operations, Spring Boot IMDS required Netty even when a user provided an async HTTP client, and DAX capacity settings accepted zero.

## Decision

- Treat injected operations as a complete user-supplied IMDS runtime path and bypass validation for client-created settings.
- Keep optional default-client implementation checks separate from user-provided abstraction paths.
- Validate capacity settings such as DAX max concurrency and pending acquires as positive values, not non-negative values.

## Outcome

- Added regression coverage for all three review issues.
- Reused `SdkAsyncHttpClientProvider.defaultHttpClient` and `requirePositiveNumber` instead of adding local helper logic.
- Added follow-up hardening for the real Ktor plugin install path, Spring Boot classpath backoff for missing async HTTP client API, and the DAX minimum positive boundary.

## Future Guard

- For optional AWS HTTP clients, test both default-client and provided-client classpath paths.
- For configuration properties, test zero boundaries for every user-visible capacity setting.
- Keep post-review P2 fixes issue-backed and include tracked review evidence before PR creation.

## Verification

- `./gradlew :bluetape4k-aws-ktor:test --tests "io.bluetape4k.aws.ktor.imds.ImdsKtorPluginTest"`: passed.
- `./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.imds.ImdsAutoConfigurationTest" --tests "io.bluetape4k.aws.spring.dynamodb.DynamoDbAutoConfigurationTest"`: passed.
- `./gradlew :bluetape4k-aws-ktor:test :bluetape4k-aws-spring-boot:test`: passed.
- `./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.imds.ImdsAutoConfigurationTest" --tests "io.bluetape4k.aws.spring.dynamodb.DynamoDbAutoConfigurationTest" :bluetape4k-aws-ktor:test --tests "io.bluetape4k.aws.ktor.imds.ImdsKtorPluginTest" --rerun-tasks`: passed; 31 focused tests executed.
- `./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.imds.ImdsAutoConfigurationTest" --tests "io.bluetape4k.aws.spring.dynamodb.DynamoDbAutoConfigurationTest" :bluetape4k-aws-ktor:test --tests "io.bluetape4k.aws.ktor.imds.ImdsKtorPluginTest" --rerun-tasks`: passed after follow-up hardening; 33 focused tests executed.
- `git diff --check`: passed.
