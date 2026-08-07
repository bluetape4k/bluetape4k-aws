---
manualId: "repository-map"
title: "AWS 저장소 지도"
locale: "ko"
releaseRef: "0.5.0"
---

# AWS 저장소 지도

`0.5.0` 릴리스에는 Gradle 프로젝트 14개가 있다. 배포하는 라이브러리와 플랫폼이 6개이고, 직접 실행해 볼 수 있는 예제가 8개다. 이름순으로 훑기보다 역할에 따라 다섯 층으로 나누어 보면 흐름이 선명해진다.

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

이 지도에는 `0.5.0` 태그의 `settings.gradle.kts`에 등록된 프로젝트만 포함한다. `develop`에서 이미 보이는 새 프로젝트라도 다음 안정 버전이 나오기 전에는 이 매뉴얼의 기준에 넣지 않는다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS 아키텍처

[![Bluetape4k AWS 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-architecture-01.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### Three-Tier API (bluetape4k-aws-java 모듈 — Java SDK v2) 다이어그램

[![Three-Tier API (bluetape4k-aws-java 모듈 — Java SDK v2) 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-architecture-02.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-architecture-02.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### Native Suspend (bluetape4k-aws-kotlin 모듈 — Kotlin SDK) 다이어그램

[![Native Suspend (bluetape4k-aws-kotlin 모듈 — Kotlin SDK) 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-architecture-03.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-architecture-03.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### CloudWatch metrics and logs 구성 요소

[![CloudWatch metrics and logs 구성 요소](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-components-12.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-components-12.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### CloudWatch metrics DSL 지원 범위

[![CloudWatch metrics DSL 지원 범위](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-components-30.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-components-30.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### CloudWatch publish 흐름

[![CloudWatch publish 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-flow-13.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-flow-13.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### CloudWatch metrics publish and list 흐름

[![CloudWatch metrics publish and list 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-flow-31.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-flow-31.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### Bluetape4k AWS 구성 요소도

[![Bluetape4k AWS 구성 요소도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-components-04.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-components-04.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### DynamoDB coroutine repository 구성 요소

[![DynamoDB coroutine repository 구성 요소](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-components-10.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-components-10.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### DynamoDB native suspend 지원 범위

[![DynamoDB native suspend 지원 범위](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-components-28.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-components-28.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### DynamoDB coroutine repository 흐름

[![DynamoDB coroutine repository 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-flow-11.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-flow-11.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### DynamoDB suspend item and batch 흐름

[![DynamoDB suspend item and batch 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-flow-29.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-flow-29.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### Secrets Manager and Parameter Store environment sources

[![Secrets Manager and Parameter Store environment sources](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-env-sources-components-16.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-env-sources-components-16.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### Secrets Manager and Parameter Store property key mapping

[![Secrets Manager and Parameter Store property key mapping](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-env-sources-flow-17.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-env-sources-flow-17.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### EC2 IMDS access surfaces

[![EC2 IMDS access surfaces](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-imds-components-14.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-imds-components-14.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### EC2 IMDS metadata 흐름

[![EC2 IMDS metadata 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-imds-flow-15.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-imds-flow-15.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### KMS Spring Boot 구성 요소

[![KMS Spring Boot 구성 요소](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-components-06.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-components-06.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### KMS Spring Boot 지원 범위

[![KMS Spring Boot 지원 범위](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-components-20.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-components-20.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### KMS encrypt and decrypt 흐름

[![KMS encrypt and decrypt 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-flow-07.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-flow-07.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### KMS operations 흐름

[![KMS operations 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-flow-21.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-flow-21.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### S3 Access Grants 구성 요소

[![S3 Access Grants 구성 요소](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-access-grants-components-08.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-access-grants-components-08.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### S3 Access Grants 흐름

[![S3 Access Grants 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-access-grants-flow-09.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-access-grants-flow-09.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### S3 coroutine 지원 범위

[![S3 coroutine 지원 범위](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-components-24.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-components-24.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### S3 coroutine operation 흐름

[![S3 coroutine operation 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-flow-25.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-flow-25.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### Bluetape4k AWS service coverage 차트

[![Bluetape4k AWS service coverage 차트](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### SNS Spring Boot 지원 범위

[![SNS Spring Boot 지원 범위](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sns-components-22.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sns-components-22.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### SNS publish and HTTP endpoint 흐름

[![SNS publish and HTTP endpoint 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sns-flow-23.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sns-flow-23.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### SQS Spring Boot 런타임

[![SQS Spring Boot 런타임](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-components-18.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-components-18.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### SQS coroutine 지원 범위

[![SQS coroutine 지원 범위](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-components-26.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-components-26.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### SQS listener 흐름

[![SQS listener 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-flow-19.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-flow-19.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### SQS coroutine message 흐름

[![SQS coroutine message 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-flow-27.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-flow-27.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### Bluetape4k AWS 모듈 구성도

[![Bluetape4k AWS 모듈 구성도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/root-readme-module-chart-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/root-readme-module-chart-01.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

### Bluetape4k AWS 개요

[![Bluetape4k AWS 개요](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/root-readme-overview-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/root-readme-overview-01.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 소스

- [Gradle 프로젝트 등록부](../../../../settings.gradle.kts)
- [AWS platform 설정](../../../../bom/build.gradle.kts)
- [저장소 모듈 개요](../../../../README.ko.md)
