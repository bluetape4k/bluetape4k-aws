---
manualId: "aws-spring-boot-exposed-examples"
id: "aws-spring-boot-exposed-examples"
title: "Spring Boot Exposed 워크숍"
locale: "ko"
kind: "example"
gradlePath: ":aws-spring-boot-exposed-examples"
sourceDir: "examples/aws-spring-boot-exposed-examples"
releaseRef: "0.5.0"
artifact: null
---

# Spring Boot Exposed 워크숍

> 0.5.0 릴리스 소스로 직접 실행하는 워크숍입니다.

## 학습 목표 {#problem}

Spring Boot 4 MVC를 AWS 설정으로 확장할 수 있는 Hikari/Exposed JDBC database에 연결한다. HTTP status, transaction, schema 초기화와 query code를 서로 다른 component가 맡도록 구성한다.

## 이 워크숍이 맞는 경우 {#when-to-use}

Spring에서 Exposed JDBC를 사용하고 database 설정을 Secrets Manager, Parameter Store, environment 또는 RDS IAM으로 바꿀 수 있어야 할 때 적합하다.

## 프로젝트 실행 방법 {#coordinates}

배포하지 않는 예제다. `./gradlew :aws-spring-boot-exposed-examples:test`를 실행한다. 실제 서비스에는 중앙 BOM, `bluetape4k-aws-spring-boot`, `bluetape4k-aws-exposed`, Exposed JDBC, HikariCP와 driver가 필요하다.

## 익혀야 할 개념 {#concepts}

`OrderController`는 HTTP 응답, `OrderService`는 `transaction(database)`, `OrderRepository`는 Exposed query를 맡는다. `OrderSchemaInitializer`는 자동 설정된 `Database`가 준비된 뒤 table을 만든다.

## 단계별 실습 {#quick-start}

1. Table과 repository mapping을 읽는다.
2. Controller 호출이 service 소유 transaction으로 들어가는 흐름을 따라간다.
3. 자동 설정한 registry, data source와 `Database` bean을 확인한다.
4. PostgreSQL Testcontainers 테스트를 실행한다.
5. Secret 조회를 repository로 옮기지 않고 test 설정을 application configuration source로 교체해 본다.

## 진입점과 기대 동작 {#api-by-task}

`SpringBootExposedExampleApplication`이 서비스를 시작한다. `POST /orders`는 `201`, 단건 조회는 없으면 `404`, 목록은 선택적인 `customerId` filter를 받는다.

## 권장 실습 순서 {#patterns}

Business operation 하나에 transaction 하나를 둔다. Repository는 Spring과 AWS client를 몰라야 한다. Schema migration은 readiness 전에 끝내고 요청 경로에서 DDL을 실행하지 않는다.

## 연동 경계 {#integrations}

`AwsExposedAutoConfiguration`이 close 가능한 registry와 기본 `DataSource`, `Database` bean을 만든다. Spring이 resource를 소유하고 repository는 이미 시작된 transaction 안에서 동작한다.

## 설정 점검 {#configuration}

`bluetape4k.aws.exposed.default-database` 아래에 JDBC URL, driver, username, password와 Hikari limit를 둔다. 운영 source는 bean 생성 전에 이 값을 해석한다.

## 실패 유형 {#failures}

Driver 불일치, credential 오류, 설정 source 장애, pool 고갈, migration 실패와 transaction 밖 repository 호출을 점검한다.

## 운영 {#operations}

Pool 획득 시간, active/idle connection, transaction 오류와 query latency를 관측한다. 만료 credential은 계획한 pool 교체로 갱신하고 Spring이 registry를 한 번만 닫게 한다.

## 경계 테스트 {#testing}

`SpringBootExposedExampleApplicationTest`는 PostgreSQL을 시작하고 registry/data source/database bean을 검증한다. Random port에서 create, read, list, filter와 not-found HTTP 동작도 확인한다.

## 다음 학습 경로 {#workshops}

Ktor Exposed 워크숍에서 plugin transaction을 비교하고, repository와 transaction pattern은 bluetape4k-exposed 매뉴얼로 이어간다.

## 제약 사항 {#limitations}

AWS 설정 service나 RDS IAM을 직접 호출하지 않는다. TLS, migration tool, credential rotation과 운영 pool 크기도 증명하지 않는다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS spring boot exposed examples 아키텍처

[![Bluetape4k AWS spring boot exposed examples 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-spring-boot-exposed-examples-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-spring-boot-exposed-examples-architecture-01.svg)

_배포본 README: [`examples/aws-spring-boot-exposed-examples/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/examples/aws-spring-boot-exposed-examples/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 소스 {#sources}

- [Controller](../../../../examples/aws-spring-boot-exposed-examples/src/main/kotlin/io/bluetape4k/aws/examples/spring/exposed/OrderController.kt)
- [Transaction 소유 service](../../../../examples/aws-spring-boot-exposed-examples/src/main/kotlin/io/bluetape4k/aws/examples/spring/exposed/OrderService.kt)
- [PostgreSQL 통합 테스트](../../../../examples/aws-spring-boot-exposed-examples/src/test/kotlin/io/bluetape4k/aws/examples/spring/exposed/SpringBootExposedExampleApplicationTest.kt)

