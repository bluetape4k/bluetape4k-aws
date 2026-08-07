---
manualId: "aws-ktor-dynamodb-examples"
id: "aws-ktor-dynamodb-examples"
title: "Ktor DynamoDB 워크숍"
locale: "ko"
kind: "example"
gradlePath: ":aws-ktor-dynamodb-examples"
sourceDir: "examples/aws-ktor-dynamodb-examples"
releaseRef: "0.5.0"
artifact: null
---

# Ktor DynamoDB 워크숍

> 0.5.0 릴리스 소스로 직접 실행하는 워크숍입니다.

## 학습 목표 {#problem}

AWS Kotlin SDK DynamoDB client를 사용하는 Ktor 3 CRUD API를 만든다. Table 정의, item mapping, repository, HTTP 응답과 emulator 설정을 소스 하나에서 차례로 확인할 수 있다.

## 이 워크숍이 맞는 경우 {#when-to-use}

Ktor 서비스에 DynamoDB를 처음 붙이거나 Spring enhanced-client repository와 비교할 때 적합하다. Partition key와 client 소유권을 먼저 정한 뒤 시작하는 편이 좋다.

## 프로젝트 실행 방법 {#coordinates}

배포하지 않는 예제다. 저장소에서 `./gradlew :aws-ktor-dynamodb-examples:test`를 실행한다. 실제 애플리케이션은 중앙 BOM, `bluetape4k-aws-ktor`와 AWS Kotlin DynamoDB service module을 사용한다.

## 익혀야 할 개념 {#concepts}

`DynamoDbKtorPlugin`은 직접 만든 client를 소유하고, 명시적으로 등록한 table만 선택적으로 생성한다. `Order`의 partition key는 `id`이며 `DynamoItemMapper`와 `DynamoItemReader`가 domain과 attribute 사이 변환을 드러낸다.

## 단계별 실습 {#quick-start}

1. Route보다 먼저 `Order`, mapper와 reader를 읽는다.
2. `BillingMode.PayPerRequest`를 사용하는 `orders` table 정의를 확인한다.
3. `dynamoDbExampleModule`에서 plugin 설치부터 CRUD route까지 따라간다.
4. Floci 테스트를 실행하고, fallback 확인이 필요할 때만 LocalStack으로 반복한다.

## 진입점과 기대 동작 {#api-by-task}

진입점은 `DynamoDbExampleRoutes.kt`다. `POST /dynamodb/orders`는 빈 ID나 status를 `400`으로 거절한다. 단건 조회는 없으면 `404`를 반환하고, delete는 key 하나를 지우며, 목록은 scan 결과를 돌려준다.

## 권장 실습 순서 {#patterns}

Put/get 한 번을 먼저 검증하고 validation과 not-found를 추가한다. Key 모델이 불분명한 상태에서 scan이나 table 자동 생성부터 붙이지 않는다.

## 연동 경계 {#integrations}

Ktor plugin이 route와 `bluetape4k-aws-kotlin`을 연결한다. Host가 endpoint, region과 credentials를 넘기며, 주입한 client의 종료 책임은 계속 애플리케이션에 있다.

## 설정 점검 {#configuration}

`dynamoDbExampleModule`에 `endpointUrl`, `region`, `credentialsProvider`를 전달한다. 운영에서는 emulator endpoint를 제거하고 배포 credential chain을 쓴다. Table schema는 의도적으로 자동 생성할 때가 아니면 별도 배포 절차가 맡아야 한다.

## 실패 유형 {#failures}

빈 key, 잘못된 attribute mapping, 없는 table, region/endpoint 불일치와 권한 부족을 먼저 확인한다. 로컬 scan 성공은 운영 접근 패턴의 확장성을 보장하지 않는다.

## 운영 {#operations}

Operation latency, throttling, 조건부 실패와 consumed capacity를 관측한다. Table 생성과 migration은 요청 처리와 분리하고, Ktor plugin이 멈춘 뒤 애플리케이션 소유 client를 닫는다.

## 경계 테스트 {#testing}

`DynamoDbExampleRoutesLocalStackTest`는 Floci가 기본이며 LocalStack 선택도 지원한다. 실제 SDK transport로 table 생성, create/read/delete/list, validation과 not-found를 검증한다.

## 다음 학습 경로 {#workshops}

Spring Boot DynamoDB 워크숍에서 enhanced-client mapping과 자동 설정을 비교하거나, SQS 워크숍으로 이동해 장기 실행 consumer lifecycle을 익힌다.

## 제약 사항 {#limitations}

Partition key 하나와 scan만 다룬다. Secondary index, 조건부 동시성, pagination, transaction, IAM policy와 운영 capacity 설계는 포함하지 않는다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS ktor dynamodb examples 아키텍처

[![Bluetape4k AWS ktor dynamodb examples 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-ktor-dynamodb-examples-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-ktor-dynamodb-examples-architecture-01.svg)

_배포본 README: [`examples/aws-ktor-dynamodb-examples/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/examples/aws-ktor-dynamodb-examples/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 소스 {#sources}

- [Route, table, mapper와 repository](../../../../examples/aws-ktor-dynamodb-examples/src/main/kotlin/io/bluetape4k/aws/examples/ktor/dynamodb/DynamoDbExampleRoutes.kt)
- [Emulator 기반 route 테스트](../../../../examples/aws-ktor-dynamodb-examples/src/test/kotlin/io/bluetape4k/aws/examples/ktor/dynamodb/DynamoDbExampleRoutesLocalStackTest.kt)
- [예제 설명](../../../../examples/aws-ktor-dynamodb-examples/README.ko.md)

