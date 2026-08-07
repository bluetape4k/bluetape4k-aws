---
manualId: "aws-spring-boot-sqs-examples"
id: "aws-spring-boot-sqs-examples"
title: "Spring Boot SQS와 SNS 워크숍"
locale: "ko"
kind: "example"
gradlePath: ":aws-spring-boot-sqs-examples"
sourceDir: "examples/aws-spring-boot-sqs-examples"
releaseRef: "0.5.0"
artifact: null
---

# Spring Boot SQS와 SNS 워크숍

> 0.5.0 릴리스 소스로 직접 실행하는 워크숍입니다.

## 학습 목표 {#problem}

REST publish, `@SqsListener`, typed 변환, manual ack, retry/backoff, interceptor event, SNS-to-SQS fanout과 DLQ redrive를 갖춘 Spring Boot 4 messaging 경로를 만든다.

## 이 워크숍이 맞는 경우 {#when-to-use}

Annotation 기반 SQS listener를 운영하기 전이거나 fanout, redrive와 중복에 안전한 handler를 실행 가능한 소스 하나로 익히려는 경우에 적합하다.

## 프로젝트 실행 방법 {#coordinates}

배포하지 않는 예제다. `./gradlew :aws-spring-boot-sqs-examples:test`를 실행한다. 실제 서비스는 중앙 BOM, `bluetape4k-aws-spring-boot`와 Java SDK SQS/SNS module을 사용한다.

## 익혀야 할 개념 {#concepts}

Controller는 HTTP만 맡고 `SqsSnsExampleService`가 queue URL, policy, subscription과 redrive attribute를 소유한다. Listener는 plain message, typed `OrderPayload`, manual ack, 한 번의 in-process retry와 interceptor event를 보여 준다.

## 단계별 실습 {#quick-start}

1. Queue를 만들고 일반 message 하나를 publish한다.
2. Listener store와 interceptor event를 확인한다.
3. Typed JSON을 보내 manual ack 흐름을 따라간다.
4. Retry-once를 일으켜 두 attempt를 비교한다.
5. SNS fanout을 만들고 publish한 뒤 source queue와 DLQ를 구성한다.

## 진입점과 기대 동작 {#api-by-task}

`SpringBootSqsExampleApplication`이 서비스를 시작한다. `SqsSnsExampleController`는 `/spring/sqs` 아래에서 queue 생성, send/receive, fanout, SNS publish, DLQ 설정과 listener 결과 조회를 제공한다.

## 권장 실습 순서 {#patterns}

Concurrency를 늘리기 전에 ack와 business idempotency를 분명히 한다. 지속적인 poison message 처리는 SQS redrive를 우선하고, in-process retry는 짧은 일시 장애에만 제한한다.

## 연동 경계 {#integrations}

Spring 자동 설정이 client와 listener container를 소유한다. Service는 AWS resource 사이 관계를 맡고 `ReceivedOrderStore`는 학습용 메모리 저장소일 뿐이다.

## 설정 점검 {#configuration}

SQS/SNS region과 endpoint, listener queue 이름, poll limit, retry와 manual ack를 설정한다. 운영에서는 queue policy, redrive, visibility, retention과 shutdown budget도 정한다.

## 실패 유형 {#failures}

Duplicate delivery, 잘못된 JSON, 없는 queue URL, 부족한 SNS-to-SQS policy, visibility 만료, poison message와 처리 중 listener 종료를 예상해야 한다.

## 운영 {#operations}

Receive age, delivery count, retry, ack/nack, DLQ depth와 listener 단계별 latency를 추적한다. Message body와 queue URL은 metric tag에서 제외하고 container를 client보다 먼저 닫는다.

## 경계 테스트 {#testing}

`SqsSnsExampleLocalStackTest`는 Floci 우선과 명시적 LocalStack fallback을 지원한다. Queue, listener, typed/manual ack, retry, event, fanout policy/subscription, publish와 DLQ redrive attribute를 검증한다.

## 다음 학습 경로 {#workshops}

Ktor SQS 워크숍에서 명시적인 runtime과 observer hook을 비교하고 business handler의 영속 idempotency와 장애 복구를 설계한다.

## 제약 사항 {#limitations}

메모리 store는 재시작 후 남지 않는다. Emulator 성공은 IAM, 운영 redrive 시간, 고동시성, ordering, exactly-once 또는 cross-account fanout을 증명하지 않는다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS spring boot sqs examples 아키텍처

[![Bluetape4k AWS spring boot sqs examples 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-spring-boot-sqs-examples-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-spring-boot-sqs-examples-architecture-01.svg)

_배포본 README: [`examples/aws-spring-boot-sqs-examples/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/examples/aws-spring-boot-sqs-examples/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 소스 {#sources}

- [Controller](../../../../examples/aws-spring-boot-sqs-examples/src/main/kotlin/io/bluetape4k/aws/examples/spring/sqs/SqsSnsExampleController.kt)
- [AWS resource 관리 service](../../../../examples/aws-spring-boot-sqs-examples/src/main/kotlin/io/bluetape4k/aws/examples/spring/sqs/SqsSnsExampleService.kt)
- [SQS/SNS 통합 테스트](../../../../examples/aws-spring-boot-sqs-examples/src/test/kotlin/io/bluetape4k/aws/examples/spring/sqs/SqsSnsExampleLocalStackTest.kt)

