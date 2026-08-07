---
manualId: "aws-ktor-exposed-examples"
id: "aws-ktor-exposed-examples"
title: "Ktor Exposed 워크숍"
locale: "ko"
kind: "example"
gradlePath: ":aws-ktor-exposed-examples"
sourceDir: "examples/aws-ktor-exposed-examples"
releaseRef: "0.5.0"
artifact: null
---

# Ktor Exposed 워크숍

> 0.5.0 릴리스 소스로 직접 실행하는 워크숍입니다.

## 학습 목표 {#problem}

Blocking transaction과 pool lifecycle을 HTTP layer에 흘리지 않고 Ktor route를 Exposed JDBC에 연결한다. Route 응답, repository query, schema 초기화와 plugin 소유 database resource를 분리하는 방법을 익힌다.

## 이 워크숍이 맞는 경우 {#when-to-use}

Ktor에서 PostgreSQL과 Exposed를 쓰고, 이후 Secrets Manager·Parameter Store 또는 RDS IAM 설정으로 확장하려는 경우에 적합하다.

## 프로젝트 실행 방법 {#coordinates}

배포하지 않는 예제다. `./gradlew :aws-ktor-exposed-examples:test`를 실행한다. 실제 서비스에는 중앙 BOM과 `bluetape4k-aws-ktor`, `bluetape4k-aws-exposed`, Exposed JDBC, HikariCP와 JDBC driver가 필요하다.

## 익혀야 할 개념 {#concepts}

`AwsExposedPlugin`이 registry를 만들고 닫는다. `call.awsExposedTransaction`은 blocking JDBC 작업을 transaction context로 보낸다. `OrderRepository`는 활성 transaction을 전제로 query만 소유한다.

## 단계별 실습 {#quick-start}

1. `OrdersTable`, `OrderRepository`와 mapping을 읽는다.
2. `ExposedExampleApplication.kt`에서 plugin 설정과 schema 생성을 따라간다.
3. Create/read/list route가 `call.awsExposedTransaction`으로 들어가는 지점을 확인한다.
4. PostgreSQL Testcontainers 테스트와 not-found 사례를 실행한다.

## 진입점과 기대 동작 {#api-by-task}

`POST /exposed/orders`는 주문을 만들고 `201`을 반환한다. 단건 조회는 없으면 `404`이며, 목록 route는 선택적인 `customerId` filter를 받는다.

## 권장 실습 순서 {#patterns}

HTTP status는 route, transaction은 plugin 경계, SQL은 repository에 둔다. 첫 경로의 rollback과 dispatcher 동작을 확인한 뒤 query를 늘린다.

## 연동 경계 {#integrations}

`bluetape4k-aws-exposed`, Ktor plugin, Exposed JDBC, HikariCP와 PostgreSQL이 이어진다. 로컬 설정은 container에서 직접 받으므로 AWS credential은 필요 없다.

## 설정 점검 {#configuration}

`defaultDatabase`에 JDBC URL, driver, username, password와 pool 크기를 설정한다. 운영에서는 plugin 설치 전에 같은 값을 해석하되, transaction마다 secret을 읽지 않는다.

## 실패 유형 {#failures}

잘못된 driver/URL, pool 고갈, schema 초기화 실패, 잘못된 dispatcher의 blocking 작업과 transaction 밖 repository 호출이 주요 실패다.

## 운영 {#operations}

Pool 사용량과 대기 시간, transaction 실패와 query latency를 관측한다. 새 요청을 막은 뒤 plugin 소유 registry를 닫고, credential 교체는 계획한 pool 교체로 처리한다.

## 경계 테스트 {#testing}

`ExposedExampleApplicationTest`는 PostgreSQL을 시작하고 container JDBC 설정을 전달한 뒤 Ktor test route로 create, read, list, filter와 not-found를 검증한다.

## 다음 학습 경로 {#workshops}

Spring Boot Exposed 워크숍에서 bean lifecycle을 비교하고, repository와 transaction 설계는 bluetape4k-exposed 매뉴얼로 이어간다.

## 제약 사항 {#limitations}

Secrets Manager, Parameter Store, RDS IAM, TLS, credential rotation, migration과 운영 pool 크기는 검증하지 않는다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS ktor exposed examples 아키텍처

[![Bluetape4k AWS ktor exposed examples 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-ktor-exposed-examples-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-ktor-exposed-examples-architecture-01.svg)

_배포본 README: [`examples/aws-ktor-exposed-examples/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/examples/aws-ktor-exposed-examples/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 소스 {#sources}

- [Application과 plugin 설정](../../../../examples/aws-ktor-exposed-examples/src/main/kotlin/io/bluetape4k/aws/examples/ktor/exposed/ExposedExampleApplication.kt)
- [Table과 repository](../../../../examples/aws-ktor-exposed-examples/src/main/kotlin/io/bluetape4k/aws/examples/ktor/exposed/OrderDomain.kt)
- [PostgreSQL route 테스트](../../../../examples/aws-ktor-exposed-examples/src/test/kotlin/io/bluetape4k/aws/examples/ktor/exposed/ExposedExampleApplicationTest.kt)

