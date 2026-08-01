# Issue 78: Spring Boot S3 Transfer 지원

## 배경

`aws` 모듈은 이미 low-level `S3TransferManager` factory와 coroutine 확장을 제공한다.
Spring Boot 지원에서 transfer 요청 생성이나 future-await 로직을 중복해서는 안 된다.

## 결정

`aws-spring-boot`의 transfer 지원을 선택적 Spring bean으로 제공한다.

- `software.amazon.awssdk:s3-transfer-manager`가 있을 때만 `S3TransferManager`를 설정한다.
- `S3TransferOperations`는 기존 `aws` 모듈의 coroutine transfer 확장을 감싼다.
- Transfer manager class가 없어도 기본 `S3Operations`를 사용할 수 있다.

## 보호 장치

TransferManager 지원은 classpath 검사로 보호하고 의존성을 가볍게 유지한다. CRT 기반
`S3AsyncClient` bean을 제공하면 CRT 기반 transfer를 사용할 수 있다. 기본 S3 object
작업만 필요한 애플리케이션에 CRT runtime 의존성을 강제하지 않는다.

## 검증

Spring Boot S3 대상 테스트에서는 다음을 검증해야 한다.

- 기본 transfer bean 등록
- transfer 비활성화/back-off property
- transfer-manager classpath가 없는 경우
- 사용자 정의 `S3TransferOperations` back-off
- `S3TransferOperations`를 통한 LocalStack 파일 upload/download
