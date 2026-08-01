# Issue 294 스펙 검토

검토 스펙: `docs/superpowers/specs/2026-06-08-issue-294-code-patterns-preflight-design.md`

## 검토 범위

- 열거 항목이 전체가 아니라 대표 예라는 사용자 수정
- `bluetape4k-code-patterns` value object/data class/coroutine/testing/재사용 규칙
- Repo-local `AGENTS.md` release-prep/workflow 제약
- 스펙 작성 전 scan 증거

## 7-Tier 결과

| Tier | 관점 | P0 | P1 | P2 | P3 | 증거 |
|---|---|---:|---:|---:|---:|---|
| 1 | 보안 | 0 | 0 | 1 | 0 | Secret wrapper와 private constructor/guarded factory를 범위에 둔다. |
| 2 | Ops/SRE | 0 | 0 | 1 | 0 | Coroutine cleanup과 제한된 sync Ktor lifecycle bridge를 다룬다. |
| 3 | 구조 | 0 | 0 | 1 | 0 | 게시 모듈을 우선하고 위험한 광역 rewrite는 후속 issue로 미룬다. |
| 4 | Kotlin/API | 0 | 0 | 1 | 0 | companion invoke, Serializable, serialVersionUID, assertion 규칙을 포함한다. |
| 5 | Test/Type | 0 | 0 | 1 | 0 | 변경 동작의 targeted Gradle check/test를 요구한다. |
| 6 | 성능/안정성 | 0 | 0 | 1 | 0 | `runInterruptible(Dispatchers.IO)` 단순화만 포함하고 광역 lifecycle 변경은 제외한다. |
| 7 | 문서/Release | 0 | 0 | 0 | 0 | Issue, spec, plan, review, lesson, PR DoD를 요구한다. |

## 통합 결과

- 저위험 일관성 항목 전체를 한 PR에서 고치지 않고 P0/P1과 신뢰도 높은 P2만 포함한다.
- `AwsJdbcDataSourceFactory`의 DriverManager/Hikari 교체는 `bluetape4k-jdbc` helper를 조사하고 동작/의존성 경계가 바뀌면 미룬다.

## Gate

- P0 = 0
- P1 = 0
- 판정: PASS
