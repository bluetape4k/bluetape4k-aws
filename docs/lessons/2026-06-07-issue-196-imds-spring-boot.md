# Issue #196 IMDS Spring Boot 통합

날짜: 2026-06-07
이슈: #196

## 배경

`aws-spring-boot`에는 S3, SQS, SNS, SES, KMS, DynamoDB, CloudWatch 서비스
자동 구성이 있었지만 EC2 Instance Metadata Service 조회를 위한 Spring Boot
퍼사드는 없었다.

## 결정

수동적인 Spring Boot 자동 구성으로 선택적 IMDS 지원을 추가한다.

- `software.amazon.awssdk:imds`를 선택적 AWS SDK v2 의존성으로 유지한다.
- 코루틴 메타데이터 조회를 위한 `ImdsOperations`와 `ImdsCoroutinesTemplate`을 제공한다.
- 모든 메타데이터 호출을 `bluetape4k.aws.imds.request-timeout`으로 제한한다.
- 안전한 메타데이터 도우미와 IAM 역할 이름만 제공하고 임시 자격 증명 문서는 노출하지 않는다.

## 결과

이제 IMDS SDK 의존성이 있고 `bluetape4k.aws.imds.enabled`가 true이면 모듈이
`Ec2MetadataAsyncClient`와 `ImdsOperations`를 등록한다. 비활성 상태, 누락된 SDK 클래스,
사용자 정의 클라이언트 빈, 사용자 정의 연산 빈이 있으면 물러난다.

## 검증

- `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency imds --configuration compileClasspath`
  `software.amazon.awssdk:imds:2.46.0`을 확인했다.
- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.imds.*'`
  IMDS 대상 테스트 12개가 통과했다.
- `./gradlew :bluetape4k-aws-spring-boot:test`에서 테스트 190개가 통과했다.
- `git diff --check`가 통과했다.

## 향후 보호 장치

EC2 존재를 입증하기 위한 IMDS 시작 탐색을 추가하지 않는다. EC2가 아닌 애플리케이션에는
시작 시 네트워크 비용이 없어야 한다. 자격 증명 조회는 AWS SDK 자격 증명 제공자 체인
또는 명시적 STS 웹 아이덴티티 지원에 유지한다.
