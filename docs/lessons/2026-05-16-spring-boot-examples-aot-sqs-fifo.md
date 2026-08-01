# Spring Boot 예제 AOT 및 SQS FIFO 강화

날짜: 2026-05-16
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/79

## 배경

Spring Boot 예제 모듈은 AOT를 지원해야 했다. SQS Spring Boot 지원에는 listener
acknowledgement, FIFO metadata, shutdown 동작을 더 충실히 맞춰야 했다.

## 결정 또는 발견

Spring Boot plugin을 적용한 뒤 이름이 `aws-spring-boot-*-examples`인 모든 프로젝트에
GraalVM Native Build Tools를 자동으로 적용한다. 이후 추가하는 Spring Boot 예제에서
`processAot`와 `processTestAot`가 빠지는 일을 막는다.

SQS listener acknowledgement는 성공 시 delete 동작을 유지한다. 호출자가 원본 AWS SDK
map을 검사하게 하지 않고 `SqsReceivedMessage`를 통해 FIFO metadata를 제공한다. 기존의
간단한 `send(queueUrl, body)` API를 유지하면서 FIFO group/deduplication ID와 message
attribute를 위한 `SqsSendRequest`를 추가한다.

## 결과

- 이제 Spring Boot S3 및 SQS/SNS 예제에 `processAot`, `processTestAot`,
  `nativeCompile`, `nativeTest` task가 있다.
- SQS가 모든 message system attribute와 message attribute를 수신한다.
- `SqsReceivedMessage`에서 `messageGroupId`, `messageDeduplicationId`,
  `sequenceNumber`, `approximateReceiveCount`, `messageAttributes`를 제공한다.
- README에 listener ack/failure/delete 동작, FIFO metadata, AOT 검증 명령을 문서화했다.

## 검증

- `./gradlew :aws-spring-boot-s3-examples:tasks --all`
- `./gradlew :aws-spring-boot-sqs-examples:tasks --all`
- `./gradlew :aws-spring-boot-s3-examples:processAot :aws-spring-boot-sqs-examples:processAot --stacktrace`
- `./gradlew :aws-spring-boot-s3-examples:processTestAot :aws-spring-boot-sqs-examples:processTestAot --stacktrace`

## 향후 지침

새 Spring Boot 예제는 `aws-spring-boot-*-examples` naming pattern을 따라야 한다. 따르지
않는다면 AOT 검증을 상속하지 않아야 하는 이유를 명시한다. SQS 수신 surface를 추가할
때는 FIFO 및 retry metadata가 호출자에게 계속 보이도록 SQS system attribute를 기본으로
요청한다.
