---
manualId: "learning-path"
title: "Bluetape4k AWS 학습 경로"
locale: "ko"
releaseRef: "0.5.0"
---

# Bluetape4k AWS 학습 경로

이 매뉴얼에는 선택 기준과 런타임 경계, 직접 실행할 수 있는 예제를 상세히 담았다. 모든 모듈을 이름순으로 읽을 필요는 없다. 아직 결정하지 못한 지점에서 시작해 한 경로를 끝까지 완성한 다음 서비스나 프레임워크 기능을 늘려 가면 된다.

## 1. 의존성 경계 정하기

[시작하기](../getting-started.md)를 먼저 읽는다. `bluetape4k-dependencies`를 한 번 가져오고 애플리케이션에 필요한 래퍼와 AWS 서비스 모듈만 추가한다. 테스트나 다른 프레임워크가 우연히 끌고 온 서비스 아티팩트에 운영 코드가 기대고 있지는 않은지도 확인한다.

## 2. SDK 모델 선택하기

[SDK 선택 가이드](sdk-selection.md)에서 Java SDK v2와 AWS SDK for Kotlin을 기존 생태계, 비동기 모델, 서비스 범위, 전송 계층과 소유권으로 비교한다. 프레임워크를 붙이기 전에 작은 클라이언트 호출 하나를 만들고 자원 종료까지 검증한다.

## 3. Service 경로 하나 완성하기

[AWS 서비스별 학습 경로](service-learning-paths.md)를 따라간다.

- S3: 객체 수명 주기, 애플리케이션 라우트, 에뮬레이터 테스트를 끝낸 뒤 사전 서명이나 암호화를 추가한다.
- DynamoDB: 키 설계, 저장소와 조건부 동작을 정한 뒤 테이블 수명 주기를 다룬다.
- SQS/SNS: 확인 응답, 가시성, 멱등성과 재전달을 검증한 뒤 팬아웃을 붙인다.

각 경로에는 자세한 설명과 테스트를 갖춘 Ktor와 Spring Boot 예제가 연결되어 있다. README와 운영 코드, 테스트를 함께 읽자. 수명 주기와 설정 판단은 컨트롤러나 라우트 바깥에 있는 경우가 많다.

## 4. Framework 연동 추가하기

[Spring Boot와 Ktor 비교](spring-vs-ktor.md)를 읽고 애플리케이션 설정과 종료를 이미 맡고 있는 프레임워크를 선택한다. 둘 다 맞지 않으면 두 번째 프레임워크의 수명 주기를 들이지 말고 SDK 기반 모듈을 직접 쓴다.

## 5. 관계형 저장소 연결하기

[AWS 설정을 Exposed JDBC로 연결하기](database-with-exposed.md)에서 Secrets Manager와 Parameter Store 설명자, 선택적 RDS IAM 인증, Hikari/Exposed 데이터베이스 생성, 이름 있는 레지스트리와 트랜잭션 소유권을 확인한다. 저장소와 트랜잭션 패턴은 [`bluetape4k-exposed` 매뉴얼](https://bluetape4k.github.io/ko/manual/bluetape4k-exposed/)에서 이어서 학습한다.

## 6. 운영과 종료 검증하기

마지막으로 [테스트와 운영](testing-and-operations.md), [런타임 소유권](../architecture/runtime-boundaries.md)을 읽는다. 가장 가까운 예제를 Floci에서 실행하고 필요한 지점에만 LocalStack 대체 경로를 추가한다. 이어서 실패, 재시도, 중복 전달과 종료 과정을 검증한다.

여기까지 마치면 모든 AWS 연동에 대해 세 가지 질문에 답할 수 있어야 한다. 클라이언트를 누가 만들고 닫는가, 재시도가 어떤 작업을 반복할 수 있는가, 실제 경계를 어느 테스트가 증명하는가.

## 근거 소스

- [릴리스 예제 대표 문서](../../../../examples/aws-ktor-s3-examples/README.ko.md)
- [릴리스 프로젝트 등록부](../../../../settings.gradle.kts)
