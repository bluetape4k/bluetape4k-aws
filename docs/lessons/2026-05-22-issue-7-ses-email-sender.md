# Issue 7 SES email sender

## Context

Issue #7 added SES support to `bluetape4k-aws-spring-boot` without awspring:
Spring Boot auto-configuration, coroutine operations, template/raw sends,
attachments, and a JavaMail adapter.

## Decision

Use AWS SDK v2 `sesv2` as a `compileOnly` service dependency and expose
`SesOperations` / `SesCoroutinesMailSender` as the primary coroutine API. Keep
the lower-level `send(SendEmailRequest)` path exact, while convenience request
types apply `defaultFrom` and `configurationSetName`.

JavaMail support needs both Jakarta Mail API and an actual provider. The adapter
auto-configuration therefore checks for Spring `JavaMailSender`, Jakarta Mail,
and Angus Mail provider classes before registering `SesJavaMailSender`.

## Outcome

Added SES auto-configuration, request models, coroutine sender, JavaMail
adapter, auto-configuration registration, README coverage, and targeted tests.
No new Gradle module was introduced, so CI/Nightly workflow registration was
not required.

## Verification

- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin`
- `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin`
- `./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.ses.*"`: 17 passing
- `./gradlew :bluetape4k-aws-spring-boot:test`: 133 passing
- Claude Code CLI final review: P0=0, P1=0, gate PASS

## Future Guidance

When adding a JavaMail-facing feature, do not assume `jakarta.mail-api` is
sufficient at runtime. Require or document a provider such as
`org.eclipse.angus:angus-mail`, and guard Spring auto-configuration on provider
presence when bean construction needs `Session`.
