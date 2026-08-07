---
manualId: "aws-ktor-sqs-examples"
id: "aws-ktor-sqs-examples"
title: "Ktor SQS 워크숍"
locale: "ko"
kind: "example"
gradlePath: ":aws-ktor-sqs-examples"
sourceDir: "examples/aws-ktor-sqs-examples"
releaseRef: "0.5.0"
artifact: null
---

# Ktor SQS 워크숍

> 0.5.0 릴리스 소스로 직접 실행하는 워크숍입니다.

## 학습 목표 {#problem}

Manual ack, 한 번 재시도하는 redelivery, interceptor event와 observer summary를 갖춘 Ktor SQS consumer를 만든다. HTTP route로 publish, 처리 결과와 queue 관리까지 직접 관찰한다.

## 이 워크숍이 맞는 경우 {#when-to-use}

SQS polling을 운영하기 전이거나 handler 성공, nack 동작과 shutdown 책임이 아직 암묵적일 때 적합하다.

## 프로젝트 실행 방법 {#coordinates}

배포하지 않는 예제다. `./gradlew :aws-ktor-sqs-examples:test`를 실행한다. 실제 서비스는 중앙 BOM, `bluetape4k-aws-ktor`와 `software.amazon.awssdk:sqs`를 사용한다.

## 익혀야 할 개념 {#concepts}

`SqsConsumer`는 concurrent long poller를 실행한다. `deleteOnSuccess = false`로 manual ack/nack를 드러낸다. `retry-once:` message는 visibility를 0으로 한 번 되돌린 다음 재전달에서 ack한다. Interceptor와 observer가 각 단계와 결과를 기록한다.

## 단계별 실습 {#quick-start}

1. Queue를 만들고 handler 하나로 consumer를 설치한다.
2. 일반 message를 보내 receipt와 ack를 확인한다.
3. `retry-once:*`를 보내 두 delivery의 lifecycle event를 비교한다.
4. Queue attribute와 observer summary를 조회한다.
5. 처리 중 application을 종료하고 제한 시간 안에서 drain되는지 확인한다.

## 진입점과 기대 동작 {#api-by-task}

`SqsExampleRoutes.kt`에 plugin과 `/sqs` route가 있다. Message 전송, 수신 body, lifecycle event와 observation, queue 생성·삭제와 approximate count 조회를 제공한다.

## 권장 실습 순서 {#patterns}

`coroutines`나 `maxMessages`를 늘리기 전에 ack와 idempotency부터 드러낸다. 지속적인 dead-letter 처리는 SQS redrive를 우선하고, handler는 중복 delivery에 안전하게 만든다.

## 연동 경계 {#integrations}

예제는 `SqsAsyncClient`를 주입하므로 애플리케이션이 소유하고 닫는다. Plugin은 poller와 handler를 소유하며 host가 transport를 닫기 전에 작업부터 멈춘다.

## 설정 점검 {#configuration}

Queue URL, poller 수, max messages, long-poll wait, visibility, retry visibility와 shutdown timeout을 설정한다. 운영 값은 handler latency와 process termination budget에 맞춘다.

## 실패 유형 {#failures}

Duplicate delivery, 너무 짧은 visibility, 변환 실패, poison message, handler timeout과 처리 중 shutdown을 정상적인 실패 조건으로 다룬다. 빈 poll 응답은 오류가 아니다.

## 운영 {#operations}

Receive, convert, invoke, ack/nack 시간을 나눠 측정하고 redelivery와 oldest-message age를 추적한다. Message body와 queue URL을 metric tag로 쓰지 않는다.

## 경계 테스트 {#testing}

`SqsExampleRoutesFlociTest`는 Floci에 무작위 queue를 만들고 send, attribute, concurrent send, queue 관리, manual ack/nack, retry-once와 telemetry route를 검증한다.

## 다음 학습 경로 {#workshops}

Spring Boot SQS/SNS 워크숍에서 annotation, typed payload, fanout과 DLQ를 익힌 뒤 business idempotency를 설계한다.

## 제약 사항 {#limitations}

수신 message 저장소는 메모리 기반이다. 운영 처리량, native redrive, IAM, tracing export나 exactly-once 처리를 증명하지 않는다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS ktor sqs examples 아키텍처

[![Bluetape4k AWS ktor sqs examples 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-ktor-sqs-examples-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-ktor-sqs-examples-architecture-01.svg)

_배포본 README: [`examples/aws-ktor-sqs-examples/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/examples/aws-ktor-sqs-examples/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 소스 {#sources}

- [Consumer와 route](../../../../examples/aws-ktor-sqs-examples/src/main/kotlin/io/bluetape4k/aws/examples/ktor/sqs/SqsExampleRoutes.kt)
- [Floci 통합 테스트](../../../../examples/aws-ktor-sqs-examples/src/test/kotlin/io/bluetape4k/aws/examples/ktor/sqs/SqsExampleRoutesFlociTest.kt)
- [예제 설명](../../../../examples/aws-ktor-sqs-examples/README.ko.md)

