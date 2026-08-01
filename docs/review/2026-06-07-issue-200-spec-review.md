# Issue #200 스펙 검토

날짜: 2026-06-07
범위: `docs/superpowers/specs/2026-06-07-issue-200-ktor-imds-design.md`

## 판정

PASS (P0: 0, P1: 0, P2: 0). 차단 문제 없음.

## 검토 증거

- 2026-06-07에 갱신된 live issue #200 본문
- 현재 `aws-ktor` plugin/config/runtime 패턴
- `develop`에 반영된 #196 Spring Boot IMDS 구현
- #196의 AWS SDK v2 IMDS API 증거: `Ec2MetadataAsyncClient`, `Ec2MetadataRetryPolicy`, `EndpointMode`, `Ec2MetadataResponse`

## 메모

- plugin 설치/시작 중 IMDS 호출을 금지해 시작 안전성을 보존한다.
- public helper에서 credential 처리를 제외한다.
- IMDS metadata endpoint 구성에 일반 AWS service endpoint override를 잘못 상속하지 않는다.
