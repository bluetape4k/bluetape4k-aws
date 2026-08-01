# Issue #196 스펙 검토

날짜: 2026-06-07
범위: `docs/superpowers/specs/2026-06-07-issue-196-imds-spring-boot-design.md`

## 판정

PASS (P0: 0, P1: 0, P2: 0). 차단 문제 없음.

## 검토 증거

- 갱신된 live issue #196 본문
- `aws-spring-boot` source tree에 기존 `imds` package 없음
- `gradle/libs.versions.toml`에 현재 `aws2-imds` alias 없음
- Maven Central HEAD: `software.amazon.awssdk:imds:2.46.0`
- `imds-2.46.0.jar`의 `Ec2MetadataAsyncClient`, `Ec2MetadataClientBuilder`, `Ec2MetadataRetryPolicy`, `EndpointMode`를 `javap`으로 검사
- CloudWatch/S3의 기존 Spring Boot 자동 구성 패턴

## 메모

- IMDS credential 노출을 피하고 `DefaultCredentialsProvider` 소유권을 유지한다.
- Bean 생성 중 metadata network 호출을 금지한다.
- Ktor 지원은 별도 #200 후속 작업으로 둔다.
