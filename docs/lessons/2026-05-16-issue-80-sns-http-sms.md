# Issue #80 SNS HTTP and SMS support

Date: 2026-05-16
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/80

## Context

Issue #4 introduced the Spring Boot 4 SNS publisher and coroutine template. The
next high-value SNS slice needed direct SMS publishing and HTTP(S) endpoint
message handling without cloning Spring Cloud AWS controller annotations.

## Decisions

- Keep the Spring surface explicit: extend `SnsOperations` with `publishSms`
  and `confirmSubscription` instead of adding a broad MVC/WebFlux annotation
  layer.
- Model SMS publish options as `SnsSmsRequest`, mapping AWS-documented message
  attributes such as `AWS.SNS.SMS.SMSType`, `AWS.SNS.SMS.SenderID`,
  `AWS.SNS.SMS.MaxPrice`, `AWS.MM.SMS.OriginationNumber`,
  `AWS.MM.SMS.EntityId`, and `AWS.MM.SMS.TemplateId`.
- Parse SNS HTTP endpoint JSON through `SnsHttpMessageParser`, including
  `Notification`, `SubscriptionConfirmation`, and `UnsubscribeConfirmation`.
  The parser checks the optional `x-amz-sns-message-type` header against JSON
  `Type` and rejects non-HTTPS or non-SNS `SigningCertURL` hosts, but it
  intentionally does not perform cryptographic signature verification.
- Documentation must state the security boundary clearly: applications must
  validate the certificate chain, `Signature`, `SignatureVersion`, and expected
  `TopicArn` before processing notifications or confirming subscriptions.

## Validation

- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.*'`
  passed with 23 SNS tests, including SMS request mapping, HTTP endpoint payload
  mapping, subscription confirmation request mapping, and existing LocalStack
  SNS/SQS fanout coverage.

## Follow-up

- A future issue can add reusable signature-verification support if the AWS SDK
  v2 surface does not expose a stable verifier for Kotlin/Spring applications.
- A separate example module can wire the parser into a Spring MVC/WebFlux
  controller once the repo's Spring Boot AOT example set is ready for SNS.
