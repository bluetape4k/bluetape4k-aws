# Issue #194 스펙 검토

- 날짜: 2026-06-07
- 문서: `docs/superpowers/specs/2026-06-07-issue-194-cloudwatch-spring-boot-design.md`
- Gate: spec review

## 판정

- P0: 0
- P1: 0
- 판정: PASS

## 검토 내용

- 범위를 `aws-spring-boot`로 제한하고 Spring Cloud AWS 복제나 Micrometer global registry 교체를 피한다.
- 기존 `aws-java` CloudWatch coroutine extension, `AwsProperties`, 공유 client default, global/service customizer, bluetape4k validation helper를 재사용한다.
- 승인 범위에 `micrometer-core`와 `MeterRegistry` 기반 명시적 publishing helper를 포함하되 global registry를 교체하거나 자동 등록하지 않는다.
- Classpath/property guard를 기존 optional service 자동 구성 패턴에 맞게 명시한다.
- 집중 CloudWatch test, 전체 `aws-spring-boot` test, diff whitespace, P0/P1 gate를 검증한다.

## 비차단 후속 작업

- native Micrometer registry export가 필요하면 `micrometer-registry-cloudwatch` 자동 등록을 후속 작업으로 둔다.
- CloudWatch/Logs integration test를 추가하기 전에 emulator 안정성을 확인하고 불안정한 LocalStack/Floci coverage를 critical path에 강제하지 않는다.
