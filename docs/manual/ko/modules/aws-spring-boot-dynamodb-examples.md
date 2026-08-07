---
manualId: "aws-spring-boot-dynamodb-examples"
id: "aws-spring-boot-dynamodb-examples"
title: "Spring Boot DynamoDB 워크숍"
locale: "ko"
kind: "example"
gradlePath: ":aws-spring-boot-dynamodb-examples"
sourceDir: "examples/aws-spring-boot-dynamodb-examples"
releaseRef: "0.5.0"
artifact: null
---

# Spring Boot DynamoDB 워크숍

> 0.5.0 릴리스 소스로 직접 실행하는 워크숍입니다.

## 학습 목표 {#problem}

`AbstractCoroutinesDynamoDbRepository` 위에 Spring Boot 4 WebFlux API를 만든다. 자동 설정이 enhanced async client와 table 이름을 제공하는 동안 repository와 controller는 coroutine API를 유지하는 구조를 익힌다.

## 이 워크숍이 맞는 경우 {#when-to-use}

Spring 서비스에서 enhanced-client mapping, 조건부 AWS bean과 context가 관리하는 client lifecycle이 필요할 때 적합하다.

## 프로젝트 실행 방법 {#coordinates}

배포하지 않는 예제다. `./gradlew :aws-spring-boot-dynamodb-examples:test`를 실행한다. 실제 서비스는 중앙 BOM, `bluetape4k-aws-spring-boot`와 `software.amazon.awssdk:dynamodb-enhanced`를 사용한다.

## 익혀야 할 개념 {#concepts}

`Order`는 `id`를 partition key로 둔 `@DynamoDbBean`이다. `OrderRepository`가 `orders` table 이름과 entity/key 변환을 정한다. Suspend CRUD와 `Flow` scan은 enhanced async client 위에서 동작한다.

## 단계별 실습 {#quick-start}

1. Bean mapping과 partition key를 확인한다.
2. `OrderRepository`에서 coroutine base repository로 이어지는 호출을 읽는다.
3. POST/GET/DELETE/list controller를 따라간다.
4. Emulator 기반 repository와 WebTestClient 테스트를 실행한다.
5. 설정이나 reflection 대상 type을 바꿨다면 `processAot`도 실행한다.

## 진입점과 기대 동작 {#api-by-task}

`SpringBootDynamoDbExampleApplication`이 서비스를 시작한다. `POST /orders`는 UUID가 있는 주문을 만들고, 단건 조회는 없으면 `404`, delete는 한 건 삭제, 목록은 `Flow<Order>` scan 결과를 돌려준다.

## 권장 실습 순서 {#patterns}

Repository method보다 key와 접근 패턴을 먼저 정한다. HTTP validation은 controller, item mapping은 DynamoDB model에 둔다. Scan은 학습용으로 보고 운영 기본 query로 삼지 않는다.

## 연동 경계 {#integrations}

필요한 service class와 property가 있으면 Spring 자동 설정이 AWS client와 enhanced client를 만들고 닫는다. Repository는 이 bean을 사용하며 요청마다 transport를 만들지 않는다.

## 설정 점검 {#configuration}

`bluetape4k.aws.dynamodb`를 활성화하고 region, 필요하면 endpoint override를 넣는다. Emulator 테스트는 고정 credential을 주입한다. 운영에서는 배포 credential chain과 명시적인 table 소유권을 사용한다.

## 실패 유형 {#failures}

Enhanced-client runtime 의존성 누락, 잘못된 bean mapping, 없는 table, endpoint/region 오류, throttling과 scan pagination을 확인한다.

## 운영 {#operations}

Operation latency, throttle, 조건부 실패, consumed capacity와 scan volume을 추적한다. Spring context가 자동 설정 client를 닫도록 두고 context 종료 전에 새 요청을 막는다.

## 경계 테스트 {#testing}

`OrderControllerLocalStackTest`는 Floci 또는 LocalStack을 고르고 `ApplicationContextRunner`로 endpoint와 credential을 넣는다. Repository CRUD, scan, concurrent operation과 WebTestClient controller 동작을 검증한다.

## 다음 학습 경로 {#workshops}

Ktor DynamoDB 워크숍에서 명시적인 plugin 구성을 비교하고, 조건부 쓰기와 pagination, 접근 패턴별 query를 설계한다.

## 제약 사항 {#limitations}

단일 key table과 scan만 다룬다. Index, transaction, optimistic concurrency, 운영 capacity, IAM과 schema migration은 포함하지 않는다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS spring boot dynamodb examples 아키텍처

[![Bluetape4k AWS spring boot dynamodb examples 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-spring-boot-dynamodb-examples-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-spring-boot-dynamodb-examples-architecture-01.svg)

_배포본 README: [`examples/aws-spring-boot-dynamodb-examples/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/examples/aws-spring-boot-dynamodb-examples/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 소스 {#sources}

- [Application과 controller](../../../../examples/aws-spring-boot-dynamodb-examples/src/main/kotlin/io/bluetape4k/aws/examples/spring/dynamodb/OrderController.kt)
- [Coroutine repository](../../../../examples/aws-spring-boot-dynamodb-examples/src/main/kotlin/io/bluetape4k/aws/examples/spring/dynamodb/OrderRepository.kt)
- [Emulator 기반 테스트](../../../../examples/aws-spring-boot-dynamodb-examples/src/test/kotlin/io/bluetape4k/aws/examples/spring/dynamodb/OrderControllerLocalStackTest.kt)

