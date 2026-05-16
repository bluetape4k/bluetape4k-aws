# Spring Boot Examples AOT And SQS FIFO Hardening

Date: 2026-05-16
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/79

## Context

Spring Boot example modules must be AOT-ready, and SQS Spring Boot support
needed stronger parity around listener acknowledgement, FIFO metadata, and
shutdown behavior.

## Decision or Finding

Apply GraalVM Native Build Tools automatically to every project named
`aws-spring-boot-*-examples` once it applies the Spring Boot plugin. This keeps
future Spring Boot examples from missing `processAot` and `processTestAot`.

For SQS, keep listener acknowledgement delete-on-success and expose FIFO metadata
through `SqsReceivedMessage` rather than requiring callers to inspect raw AWS SDK
maps. Add `SqsSendRequest` for FIFO group/deduplication IDs and message
attributes while preserving the existing simple `send(queueUrl, body)` API.

## Outcome

- Spring Boot S3 and SQS/SNS examples now expose `processAot`,
  `processTestAot`, `nativeCompile`, and `nativeTest` tasks.
- SQS receives all message system attributes and message attributes.
- `SqsReceivedMessage` exposes `messageGroupId`, `messageDeduplicationId`,
  `sequenceNumber`, `approximateReceiveCount`, and `messageAttributes`.
- README files document listener ack/failure/delete behavior, FIFO metadata, and
  AOT verification commands.

## Verification

- `./gradlew :aws-spring-boot-s3-examples:tasks --all`
- `./gradlew :aws-spring-boot-sqs-examples:tasks --all`
- `./gradlew :aws-spring-boot-s3-examples:processAot :aws-spring-boot-sqs-examples:processAot --stacktrace`
- `./gradlew :aws-spring-boot-s3-examples:processTestAot :aws-spring-boot-sqs-examples:processTestAot --stacktrace`

## Future Guidance

Every new Spring Boot example must match the `aws-spring-boot-*-examples` naming
pattern or explicitly explain why it should not inherit AOT verification. When
adding SQS receive surfaces, request SQS system attributes by default so FIFO and
retry metadata remain visible to callers.
