# Issue #636 Kinesis 관측 conformance 설계 검토

## 범위와 provenance

- 대상: `2026-09-06-issue-636-kinesis-observation-conformance-design.md`
- 근거: Issue #636, 양 module의 metrics/event/consumer/test와 Gradle dependency
- 방식: 현재 세션 정책에 따른 main-session six-lens fallback
- 독립 reviewer/model provenance: 없음. 독립 검토로 주장하지 않는다.

## 관점별 판정

| 관점 | 판단 | 상태 |
| --- | --- | --- |
| Performance | token 재hash 없이 prefix만 만들고 event당 최대 2개 bounded value를 생성한다. | PASS |
| Stability | legacy callback과 consumer lifecycle을 바꾸지 않고 순수 adapter만 추가한다. | PASS |
| Security | raw identifier/payload/sequence/cause가 canonical value에 없고 vocabulary/token/bound를 fail-closed한다. | PASS |
| Operator/Ops | schema version, series vocabulary와 migration 기간을 문서화한다. | PASS |
| Developer/API | 기존 sealed API를 보존하고 양 SDK 간 production dependency를 추가하지 않는다. | PASS |
| User/caller | 신규 exporter만 opt-in adapter를 사용하며 기존 소비자는 그대로 동작한다. | PASS |

## Finding 처분

| Priority | Finding | 처분 |
| --- | --- | --- |
| P1 | Java `Shard(completed)`와 Kotlin의 checkpoint+shard 두 event를 1:1 매핑하면 lifecycle 의미가 소실된다. | adapter 반환을 list로 두고 Java completion을 두 canonical event로 확장한다. |
| P2 | 64자로 통일하면 Java legacy token과 dashboard series가 끊긴다. | canonical v1은 24자를 선택하고 Kotlin legacy token을 prefix로 정규화한다. |
| P2 | 동일 value 구현 두 벌은 drift할 수 있다. | root manifest 한 벌과 양 module conformance test를 required CI surface로 둔다. |

최종 판정: 모든 finding을 설계에 반영해 `P0=0`, `P1=0`. Step 2-R `PASS`.
