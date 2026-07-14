---
manualId: "repository-overview"
title: "Bluetape4k AWS 매뉴얼"
locale: "ko"
releaseRef: "0.4.0"
---

# Bluetape4k AWS 매뉴얼

`bluetape4k-aws`는 Kotlin/JVM 애플리케이션과 AWS를 잇는 라이브러리다. AWS SDK for Java v2에는 코루틴 어댑터를 더하고, AWS SDK for Kotlin에는 자주 쓰는 확장 함수와 요청 빌더를 제공한다. Spring Boot 자동 설정과 Ktor 플러그인, AWS에서 읽은 데이터베이스 설정을 Exposed JDBC로 연결하는 기능도 함께 담고 있다.

이 매뉴얼은 패키지 목록을 나열하는 대신 선택 순서부터 설명한다. 먼저 Java SDK와 Kotlin SDK 중 주 경로를 고르고, 라이브러리를 직접 쓸지 Spring Boot나 Ktor에 맡길지 결정한다. S3, DynamoDB, SQS/SNS 학습 경로에는 실행 가능한 예제를 연결했고, 데이터베이스와 운영 문서에서는 클라이언트와 자원을 누가 닫는지까지 짚는다.

## 버전 기준

애플리케이션이 직접 선택할 버전은 중앙 BOM인 `io.github.bluetape4k:bluetape4k-dependencies:<version>` 하나다. `bluetape4k-aws`, AWS SDK와 다른 bluetape4k 라이브러리의 버전을 따로 맞출 필요는 없다.

이 매뉴얼의 기술 기준은 `bluetape4k-aws 0.4.0`이다. 이 안정 릴리스에 포함된 배포 프로젝트 6개와 실행 예제 8개, 모두 14개 Gradle 프로젝트만 다룬다. 이후 `develop`에 추가된 프로젝트는 의도적으로 제외했다.

- 릴리스 태그: [`0.4.0`](https://github.com/bluetape4k/bluetape4k-aws/tree/0.4.0)
- 릴리스 커밋: [`be4e6daea5654f84579955307ec56a58c8f405be`](https://github.com/bluetape4k/bluetape4k-aws/commit/be4e6daea5654f84579955307ec56a58c8f405be)
- 실행 환경 기준: JDK 21, Kotlin 2.3, Spring Boot 4, Ktor 3

## 어디서 시작할까

- [시작하기](getting-started.md)에서 중앙 BOM을 적용하고 실제로 사용할 AWS 서비스 모듈만 추가한다.
- 한 서비스에서 Java SDK와 Kotlin SDK를 섞기 전에 [SDK 선택 가이드](guides/sdk-selection.md)를 읽는다.
- 안정 릴리스의 14개 프로젝트가 어떻게 이어지는지는 [저장소 지도](architecture/repository-map.md)에서 확인한다.
- S3, DynamoDB, messaging, 관계형 데이터베이스를 순서대로 익히려면 [학습 경로](guides/learning-path.md)를 따라간다.
- 에뮬레이터 범위, 클라이언트 소유권과 종료 정책을 정하기 전에 [테스트와 운영](guides/testing-and-operations.md)을 읽는다.

## 책임 경계

이 저장소는 AWS 클라이언트를 Kotlin에 맞게 다듬고 애플리케이션 프레임워크에 연결하는 역할을 맡는다. AWS 서비스 자체의 동작, IAM 정책, 안전한 재시도 조건, 자원 소유권까지 숨기지는 않는다. 사용할 서비스 SDK, 자격 증명과 리전 공급자, 제한 시간과 재시도, 멱등성, 운영 지표는 애플리케이션이 정해야 한다.

## 근거 소스

- [릴리스 모듈 등록부](../../../settings.gradle.kts)
- [저장소 의존성과 모듈 개요](../../../README.ko.md)
