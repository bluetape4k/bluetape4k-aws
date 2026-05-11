# Issue #4 SNS Spring Boot

## Context

Issue #4 added SNS support to `aws-spring-boot`: Boot 4 auto-configuration, coroutine operations, topic lookup, FIFO publishing, and SNS-to-SQS fanout verification.

## Decision

Follow the existing SQS Spring Boot pattern instead of introducing a separate abstraction style:

- compile-only AWS SNS SDK dependency in production,
- string-based `@ConditionalOnClass`,
- `@ConditionalOnMissingBean` back-off for SDK client and operations,
- coroutine template over `SnsAsyncClient`,
- LocalStack integration tests.

## Outcome

The implementation includes `SnsOperations`, `SnsCoroutinesTemplate`, `SnsProperties`, `SnsPublishRequest`, FIFO throughput scope enum, and `SnsAutoConfiguration`. README and Korean README document runtime dependency, configuration, publishing, and the issue #13 boundary for full example work.

## Verification

- `./gradlew :aws-spring-boot:compileKotlin`
- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.*'` — 16 passing
- `./gradlew :aws-spring-boot:test` — 49 passing
- Claude advisor reviewed the spec, plan, and final diff with no remaining blockers.

## Future Guidance

- For Spring Boot map keys containing dots, use bracket notation in property-value tests, such as `topics[orders.fifo]`.
- Keep full application examples separate from library feature PRs when a follow-up issue owns the example module.
- Use one executable LocalStack fanout test when adding publish-side integration features; it provides better evidence than README-only snippets.
