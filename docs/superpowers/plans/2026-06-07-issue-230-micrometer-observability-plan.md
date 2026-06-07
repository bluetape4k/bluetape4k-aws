# Issue #230 Micrometer Observability Plan

Date: 2026-06-07
Issue: #230

## Order

1. Add optional Micrometer dependency scope to `aws-ktor`; keep
   `aws-spring-boot` as-is because Micrometer core is already an API dependency.
2. Add small shared Micrometer helpers for low-cardinality tags, queue-name
   derivation, exception naming, and timer recording in each touched module.
3. Implement Spring Boot SQS instrumentation:
   - `MicrometerSqsOperations`
   - `MicrometerSqsListenerInterceptor`
   - conditional auto-registration in `SqsAutoConfiguration`
4. Implement Spring Boot S3 instrumentation:
   - `MicrometerS3Operations`
   - conditional decoration in `S3AutoConfiguration`
5. Implement Ktor SQS instrumentation:
   - `MicrometerSqsConsumerObserver`
   - `SqsConsumerPluginConfig.micrometer(...)`
   - `send` observations in `SqsConsumerRuntime`
6. Implement Ktor S3 instrumentation:
   - `MicrometerS3KtorClient`
   - `S3KtorClient.withMicrometer(...)`
7. Add focused tests for decorators, conditional registration, observer mapping,
   and selected wrapper methods.
8. Update root and module README files in English and Korean.
9. Add implementation review and lesson.
10. Validate, commit, create PR, verify PR body, monitor CI, then merge only
    after checks are green.

## Verification Commands

- `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency micrometer-core --configuration compileClasspath`
- `./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency micrometer-core --configuration compileClasspath`
- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.*Micrometer*'`
- `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.*Micrometer*'`
- `./gradlew :bluetape4k-aws-spring-boot:test`
- `./gradlew :bluetape4k-aws-ktor:test`
- `git diff --check`

## Risks

- Double instrumentation if users manually add their own interceptors. Keep
  auto adapters conventional and documented.
- High-cardinality tags. Keep URLs, keys, receipt handles, message IDs, and
  raw exception messages out of default tags.
- Ktor dependency leakage. Keep Micrometer as compile-only and test-only for
  `aws-ktor`.
