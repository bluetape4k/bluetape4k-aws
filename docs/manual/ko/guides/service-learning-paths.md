---
manualId: "service-learning-paths"
title: "AWS Service별 학습 경로"
locale: "ko"
releaseRef: "0.5.0"
---

# AWS Service별 학습 경로

Service 하나를 골라 의존성, client, 애플리케이션 경계, 통합 테스트와 운영까지 한 번에 끝내는 편이 빠르다. 아래 예제에는 실행 가능한 코드와 emulator 기반 테스트가 함께 들어 있다. API 호출 몇 줄만 복사하는 것보다 전체 구조를 익히기 좋다.

![Spring Boot와 Ktor 서비스 학습 지도](../../assets/framework/service-learning-map.png)

## S3: 고급 기능보다 object lifecycle부터

1. Java 또는 Kotlin SDK S3 helper로 upload, download, 목록, copy/move와 delete 동작을 익힌다.
2. 애플리케이션 경계를 고른다. Spring Boot에서는 `S3CoroutinesTemplate`/`S3Operations`, Ktor에서는 `S3KtorClient`를 사용한다.
3. S3 예제 하나를 실행하고 HTTP route와 test fixture를 같이 읽는다.
4. 기본 object 경로와 소유권이 검증된 뒤 presigned URL, multipart transfer, client-side encryption, Access Grants나 S3 Vectors를 붙인다.

- [Ktor S3 예제](../../../../examples/aws-ktor-s3-examples/README.ko.md)는 object route, presigned URL, content-type 감지, 설정 object와 client-side encryption을 다룬다.
- [Spring Boot S3 예제](../../../../examples/aws-spring-boot-s3-examples/README.ko.md)는 WebFlux endpoint, `S3Operations`, `S3CoroutinesTemplate`, presigned URL과 선택적인 KMS 기반 client-side encryption을 보여 준다.

Presigning 테스트는 요청을 제대로 만들었다는 사실만 증명한다. 실제 실행 시 IAM 권한까지 보장하지는 않으므로 운영 endpoint와 credential 모델에서도 별도로 확인해야 한다.

## DynamoDB: Repository보다 key 모델부터

1. Partition key와 sort key, 접근 패턴, 조건부 쓰기와 table 이름을 먼저 정한다.
2. Enhanced table schema가 설계의 중심이면 Java SDK enhanced repository를 사용하고, native suspend client가 중심이면 Kotlin SDK/Ktor repository를 고른다.
3. CRUD뿐 아니라 중복 쓰기와 lost update를 막는 조건도 테스트한다.
4. Table 자동 생성은 명시적으로 등록한 정의에만 적용한다. 운영 schema의 소유권은 따로 관리하는 편이 안전하다.

- [Ktor DynamoDB 예제](../../../../examples/aws-ktor-dynamodb-examples/README.ko.md)는 plugin 설정, table 생성, mapper, repository, route와 Floci/LocalStack 테스트를 잇는다.
- [Spring Boot DynamoDB 예제](../../../../examples/aws-spring-boot-dynamodb-examples/README.ko.md)는 자동 설정한 client와 coroutine repository로 작은 REST API를 만든다.

## SQS와 SNS: 처리량보다 전달 규칙부터

1. Ack 방식, visibility timeout, retry/backoff, idempotency key와 redrive policy를 정한다.
2. SQS consumer에서 handler 성공 시 자동 삭제할지 수동 ack를 쓸지 고른다.
3. Handler 실패, 변환 실패, redelivery와 처리 중 종료를 테스트한다.
4. Queue consumer가 관측 가능하고 중복 처리에 안전해진 다음 SNS fanout을 붙인다.

- [Ktor SQS 예제](../../../../examples/aws-ktor-sqs-examples/README.ko.md)는 manual ack/nack, 한 번 재시도하는 redelivery, interceptor와 observer event를 보여 준다.
- [Spring Boot SQS/SNS 예제](../../../../examples/aws-spring-boot-sqs-examples/README.ko.md)는 typed listener, manual ack, retry/backoff, interceptor event와 SNS-to-SQS fanout을 다룬다.

Emulator 테스트는 API와 통합 동작을 확인할 수 있지만 운영 IAM, queue policy, throttling이나 exactly-once 처리를 증명하지 않는다. SQS는 at-least-once 전달이므로 business handler가 중복 메시지를 견뎌야 한다.

## 근거 소스

- [S3 Ktor client](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/s3/S3KtorClient.kt)
- [Spring S3 operations](../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3Operations.kt)
- [Ktor DynamoDB repository](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/dynamodb/DynamoDbKtorRepository.kt)
- [Spring coroutine DynamoDB repository](../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/dynamodb/CoroutinesDynamoDbRepository.kt)
- [Ktor SQS runtime](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntime.kt)
