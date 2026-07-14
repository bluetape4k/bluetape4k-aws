---
manualId: "repository-map"
title: "AWS 저장소 지도"
locale: "ko"
releaseRef: "0.4.0"
---

# AWS 저장소 지도

`0.4.0` 릴리스에는 Gradle 프로젝트 14개가 있다. 배포하는 라이브러리와 플랫폼이 6개이고, 직접 실행해 볼 수 있는 예제가 8개다. 이름순으로 훑기보다 역할에 따라 다섯 층으로 나누어 보면 흐름이 선명해진다.

![AWS 저장소 모듈 지도](../../assets/overview/repository-module-map.png)

## 1층: 버전 정렬

`bluetape4k-aws-bom`은 이 저장소에서 배포하는 artifact의 버전을 맞춘다. 다만 애플리케이션에서는 더 넓은 범위를 관리하는 `bluetape4k-dependencies` BOM을 사용한다. 여러 bluetape4k 저장소를 아우르는 사용자용 버전 경계가 그쪽에 있기 때문이다.

## 2층: SDK 기반

| 프로젝트 | SDK 모델 | 주 용도 |
| --- | --- | --- |
| `bluetape4k-aws-java` | AWS SDK for Java v2 | 동기 도우미, `CompletableFuture` 확장, `suspend` 어댑터, Enhanced DynamoDB 저장소, S3 전송과 폭넓은 Java SDK 서비스 지원 |
| `bluetape4k-aws-kotlin` | AWS SDK for Kotlin | 네이티브 `suspend` 클라이언트, 요청 DSL, DynamoDB 일괄 처리, S3 도우미와 Kotlin 중심 서비스 접근 |

애플리케이션 코드에서는 대체로 둘 중 하나를 고른다. 프레임워크 모듈 안에서 둘을 함께 쓰기도 한다. 예를 들어 Java SDK SQS와 Kotlin SDK DynamoDB를 한 애플리케이션에 둘 수는 있지만, 각 클라이언트와 HTTP 엔진의 소유자는 따로 정해야 한다.

## 3층: 데이터베이스 연결부

`bluetape4k-aws-exposed`는 AWS에서 가져온 접속 설정을 해석하고, 필요하면 RDS IAM 인증 토큰을 만든다. 이어서 Hikari 데이터 소스와 Exposed JDBC `Database`를 생성하고, 기본 데이터베이스와 이름 있는 데이터베이스 핸들을 닫을 수 있는 레지스트리로 묶는다. 트랜잭션과 AWS 클라이언트의 수명 주기는 이 모듈이 대신 관리하지 않는다.

## 4층: 애플리케이션 프레임워크

- `bluetape4k-aws-spring-boot`는 `bluetape4k.aws.*` 속성을 바인딩하고 조건에 맞는 클라이언트, 템플릿, 저장소, 리스너와 데이터베이스 레지스트리를 만든다. 자동 설정이 만든 빈은 Spring 컨텍스트가 종료할 때 닫는다.
- `bluetape4k-aws-ktor`는 SigV4, S3, SQS, DynamoDB, CloudWatch, IMDS, S3 Access Grants, S3 Vectors와 Exposed용 플러그인과 런타임을 제공한다. 플러그인이 직접 만든 자원은 Ktor 애플리케이션과 함께 종료하지만, 외부에서 주입한 클라이언트는 애플리케이션 소유로 남는다.

## 5층: 실행 가능한 학습 프로젝트

| 목표 | Ktor 예제 | Spring Boot 예제 |
| --- | --- | --- |
| S3 객체 HTTP API | `aws-ktor-s3-examples` | `aws-spring-boot-s3-examples` |
| DynamoDB 저장소 | `aws-ktor-dynamodb-examples` | `aws-spring-boot-dynamodb-examples` |
| SQS 처리와 SNS 팬아웃 | `aws-ktor-sqs-examples` | `aws-spring-boot-sqs-examples` |
| AWS 설정과 Exposed JDBC | `aws-ktor-exposed-examples` | `aws-spring-boot-exposed-examples` |

예제는 배포용 아티팩트가 아니다. 설정, 애플리케이션 경계와 에뮬레이터 통합 테스트를 함께 보여 주는 참고 구현이다. 라우트나 컨트롤러 하나만 떼어 가면 주변의 의존성과 종료 책임을 놓치기 쉽다. 연결된 라이브러리 모듈과 함께 읽는 편이 좋다.

## 릴리스 범위 원칙

이 지도에는 `0.4.0` 태그의 `settings.gradle.kts`에 등록된 프로젝트만 포함한다. `develop`에서 이미 보이는 새 프로젝트라도 다음 안정 버전이 나오기 전에는 이 매뉴얼의 기준에 넣지 않는다.

## 근거 소스

- [Gradle 프로젝트 등록부](../../../../settings.gradle.kts)
- [AWS platform 설정](../../../../bom/build.gradle.kts)
- [저장소 모듈 개요](../../../../README.ko.md)
