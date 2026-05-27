# Issue #193 Spring SQS Advanced

## Context

Milestone 0.3.0 needs Spring Boot SQS production hardening while keeping the
existing raw `String`, AWS `Message`, and `SqsReceivedMessage` listener
contracts source-compatible.

## Decision

Extend the listener container with opt-in typed payload conversion,
`SqsAcknowledgement` manual ack/nack, retry/backoff properties, and
`SqsListenerInterceptor` hooks. Use Jackson 3 only when an `ObjectMapper` bean is
available, and keep Micrometer-specific integration out of the core runtime by
exposing generic interception points.

## Outcome

Listeners can now receive converted payloads, decide acknowledgement manually,
retry handler failures in-process before final visibility handling, and observe
receive/handler/ack/failure phases through ordered Spring beans.

## Verification

- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --max-workers=1`
- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*SqsAutoConfigurationTest' --tests '*SqsListenerAwsEmulatorTest' --no-daemon --max-workers=1`

## Future Guard

Do not add a hard Micrometer dependency to the SQS listener container. Build
Micrometer observation as a separate adapter over `SqsListenerInterceptor` if a
future issue needs first-class metrics.
