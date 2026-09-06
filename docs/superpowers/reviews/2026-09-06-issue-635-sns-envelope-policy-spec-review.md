# Issue #635 SNS HTTP envelope 설계 검토

## 범위와 provenance

- 검토 대상: `docs/superpowers/specs/2026-09-06-issue-635-sns-envelope-policy-design.md`
- 기준 source: 두 adapter parser/model/test, 세 module dependency, Issue #635
- 검토 방식: 현재 세션의 native subagent 금지 정책에 따른 main-session six-lens fallback
- 독립 reviewer/model provenance: 없음. 관점별 근거를 분리했으며 독립 검토로 주장하지 않는다.

## 관점별 검토

| 관점 | 근거와 판단 | 결과 |
| --- | --- | --- |
| Performance | 크기 검사를 decode 전에 수행하고 decoder 호출을 1회로 제한한다. core 변환은 고정 field 수와 raw top-level 복사본만 다룬다. | PASS |
| Stability | 순수 동기 parser라 lifecycle/cancellation 자원이 없다. custom mapper와 fixture variant 실패를 명시적 실패 모드와 rollback으로 묶었다. | PASS |
| Security | duplicate/malformed/non-object, byte 상한, exact host label, region/partition, URI 요소, 필수 field를 closed corpus로 고정한다. raw payload를 오류에 포함하지 않는다. | PASS |
| Operator/Ops | 고정 enum reason과 redacted message를 제공하고 Spring 400 분류를 유지한다. credentials/IAM/retry는 caller-owned다. | PASS |
| Developer/API | core가 decoder function을 받아 framework dependency를 역전하지 않는다. adapter public parser/model signature는 유지한다. | PASS |
| User/caller | parser 출력은 인증 결과가 아니며 signature verification·topic allowlist·replay 정책을 호출자가 계속 적용한다. README/KDoc parity가 계획에 포함된다. | PASS |

## 통합 판정

| Priority | Lens | Evidence | Disposition |
| --- | --- | --- | --- |
| P2 | Security | 기존 suffix 검사는 `sns.<region>.evil.amazonaws.com` 같은 extra label을 허용할 수 있다. | exact label allowlist와 regression case를 설계에 반영했다. |
| P2 | Developer/API | 물리적으로 복제한 corpus는 다시 drift할 수 있다. | `java-test-fixtures` 단일 source variant를 선택했다. |
| P3 | Operations | 기존 Spring category는 `invalid-input`으로 거칠다. | exception의 stable `reason`을 공통 관측 계약으로 제공하고 endpoint HTTP category는 호환 유지한다. |

최종 상태: `P0=0`, `P1=0`, P2/P3 모두 설계에 반영. Step 2-R `PASS`.

## Writer DoD

- `SPW-01 PASS`: 유지보수자 대상 한국어 설계 검토이며 source/issue/baseline을 고정했다.
- `SPW-02 PASS`: 관점, 근거, severity, disposition, gap, verdict를 포함한다.
- `SPW-03 PASS`: 기술 token을 보존하고 한국어 기술 문체 checklist를 적용했다.
- `SPW-04 PASS`: finding은 설계의 host/corpus/operations 절과 연결된다.
- `SPW-05 PASS`: 표와 최종 P0/P1 수를 다시 읽어 일치함을 확인했다.
