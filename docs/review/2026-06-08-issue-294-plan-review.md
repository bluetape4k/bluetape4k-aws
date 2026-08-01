# Issue 294 계획 검토

검토 계획: `docs/superpowers/plans/2026-06-08-issue-294-code-patterns-preflight-plan.md`

참조 스펙: `docs/superpowers/specs/2026-06-08-issue-294-code-patterns-preflight-design.md`

## 검토 범위

- 리포지토리 전체 code-pattern preflight의 구현 순서.
- 테스트와 컴파일 명령의 구체성.
- 공개 API 호환성과 release-prep diff 크기.
- bluetape4k assertion, coroutine helper, UUID/Base58 helper, JDBC helper 재사용 여부.

## 결과

| 우선순위 | 영역 | 발견 사항 | 조치 |
|---|---|---|---|
| P2 | 검증 | 초기 계획은 data-class 모듈 검증에 placeholder를 사용했다. | 모든 게시 모듈의 정확한 compile task를 명시했다. |
| P2 | 범위 | JDBC/DataSource 교체는 동작과 의존성 경계를 바꿀 수 있다. | 현재 의존성에 정확한 helper가 있을 때만 교체하고, 없으면 후속 issue를 만든다. |
| P2 | 전달 | 전체 raw assertion/UUID 정리는 0.4.0 preflight PR에 너무 넓다. | 변경된 고신호 테스트로 제한하고 나머지는 별도로 추적한다. |

## Gate

- P0 = 0
- P1 = 0
- 판정: PASS
