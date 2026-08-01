# Issue 308 계획 검토

## 범위

- Issue: #308 EventBridge core wrapper.
- 검토 문서: `docs/superpowers/plans/2026-07-01-issue-308-eventbridge-core-plan.md`.
- 방법: 지연된 subagent lane을 중지한 뒤 로컬 Step 3-R fallback.

## 결과

- 요구사항/API/문서/release readiness: P0 없음. Java/AWS Kotlin 작업, 원본 응답, 요청당 한 번 호출, README locale/KDoc, 최종 검증을 명시했다.
- 테스트 가능성 P1: 열린 command 메모를 Floci-first 검사와 smoke/fallback command로 수정했다.
- 수명 주기 P1: 모든 EventBridge client build 뒤 `ShutdownQueue.register(this)`를 요구하도록 수정했다.

## 판정

- P0: 0
- P1: 계획 수정 후 0
- 잔여 위험: Floci/LocalStack에서 EventBridge emulator를 지원하지 않을 수 있으며, 이 경우 허위 smoke 대신 정확한 미지원 증거를 남긴다.
