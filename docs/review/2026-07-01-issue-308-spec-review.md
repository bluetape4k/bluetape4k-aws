# Issue #308 스펙 검토

## 범위

- Spec: `docs/superpowers/specs/2026-07-01-issue-308-eventbridge-core-design.md`
- Gate: Step 2-R
- Mode: native lane과 main-session 통합. 지연된 security lane은 사용자 수정 후 main-session fallback으로 대체했다.

## 결과

| 관점 | P0 | P1 | 조치 |
|---|---:|---:|---|
| 성능 | 0 | 1 | PutEvents 10개 항목/1 MB 계약을 추가하고 하위 항목을 계획 테스트로 옮겼다. |
| 안정성 | 0 | 2 | client 수명 주기와 PutEvents 제한 계약을 추가하고 취소/삭제 순서를 KDoc/test에 반영했다. |
| 개발자/API | 0 | 2 | PutEvents/PutRule 계약을 추가하고 target/delete/KDoc/Kotlin builder 항목을 계획에 반영했다. |
| 운영 | 0 | 1 | Floci-first smoke 또는 미지원 증거를 요구했다. |
| 사용자 | 0 | 2 | 부분 실패와 public KDoc/README 요구사항을 추가했다. |
| 보안 fallback | 0 | 0 | 호출자 제어 detail/target/resource 검증 후 차단 문제 없음. |

## 통합 판정

- P0: 0
- P1: 스펙 수정 후 0
- P2/P3: 구체적인 test, KDoc, README, emulator probe 작업으로 구현 계획에 수용했다.
