# Issue #313 Step Functions 구현 계획 검토

## 판정

`PASS` — 구현 전 Type A 계획 검토를 완료했다. 성능, 안정성, 보안, 운영, 개발자/API,
사용자/호출자 여섯 관점과 주 agent 통합 검토에서 최종 P0/P1/P2/P3가 모두 0이다.

이 판정은 구현 계획의 실행 가능성과 설계 추적성에 대한 것이다. production code, emulator,
전체 module test는 아직 실행하지 않았으며 사용자 계획 승인 전에는 구현하지 않는다.

## 기준선

- Repository: `bluetape4k/bluetape4k-aws`
- Epic: [#501](https://github.com/bluetape4k/bluetape4k-aws/issues/501)
- Issue: [#313](https://github.com/bluetape4k/bluetape4k-aws/issues/313)
- Base: `develop` `c9350bc1ae14cd72056fb358d8f3a427467848f9`
- Design: `docs/superpowers/specs/2026-08-22-issue-313-step-functions-design.md`
- Design SHA-256: `627e5c6f73f2a8ebbfbf6109b28c5558855e47ae635e24e3c266cfee7aa2618c`
- Plan: `docs/superpowers/plans/2026-08-22-issue-313-step-functions-plan.md`
- Plan SHA-256: `c84409d5414bfb1f8fc0aa3770e056fd9b13bacbd103ad246375bc2946094d31`

SHA가 달라지면 이 판정은 무효이며 구현 전에 영향받은 관점 검토를 다시 실행한다.

## 여섯 관점 최종 결과

| 관점 | P0 | P1 | P2 | P3 | 최종 판정 | 확인한 핵심 계약 |
|---|---:|---:|---:|---:|---|---|
| 성능 | 0 | 0 | 0 | 0 | PASS | 1초 하한, cold Flow, backpressure, multi-collector와 `shareIn`/`stateIn`, aggregate quota |
| 안정성 | 0 | 0 | 0 | 0 | PASS | unknown 무방출, cancellation, `NonCancellable` cleanup, baseline 비교, rollback |
| 보안 | 0 | 0 | 0 | 0 | PASS | IAM/KMS, full redaction, trusted endpoint, HTTP ownership, `includedData` |
| 운영 | 0 | 0 | 0 | 0 | PASS | Floci-first, LocalStack fallback, fresh XML receipt, timeout, stable manual ref |
| 개발자/API | 0 | 0 | 0 | 0 | PASS | Java/Kotlin signature, `statusFilter`, lifecycle factory, consumer omission compile |
| 사용자/호출자 | 0 | 0 | 0 | 0 | PASS | 분석적 source 오류, dependency boundary, lifecycle/Flow 예제, emulator 정책 |

## 통합 중 반영한 수정

- Java `ListExecutionsRequest`의 `statusFilter()` property를 사용하고 state-machine/Map Run source를
  callback 뒤 field-specific message로 재검증한다.
- Java의 `null`/`UNKNOWN_TO_SDK_VERSION`과 Kotlin `SdkUnknown(value)`에서 raw response를 emit하지 않고
  exact SDK invocation count와 자동 `StopExecution` 부재를 검증한다.
- application factory, short-lived lifecycle helper, builder override, caller-owned HTTP client/engine을
  public consumer compile과 unit test에 모두 매핑한다.
- cold Flow를 lifecycle block 안에서 수집하고, 다중 collector의 upstream 호출량과 공유 시 호출 통합을
  virtual time으로 검증한다. 승인된 generic lifecycle signature는 유지한다.
- Floci와 LocalStack 네 case의 exit code, fresh XML, count, exact reason, SHA-256을 backend별 receipt로 보존한다.
  evidence 수집 성공과 live integration의 `PASS`/`UNVERIFIED`/`FAIL`을 분리한다.
- 실제 AWS IAM/KMS는 emulator 결과로 승격하지 않고 계속 `UNVERIFIED`로 남긴다.
- 변경 전 pinned-base full test/detekt baseline, final comparison, bounded resource cleanup, recoverable rollback을
  계획에 추가했다.

## 주 agent 통합 판정

주 agent는 여섯 결과를 최신 계획에 재적용해 설계의 public signature, 비목표, 검증 경계가 유지되는지
확인했다. 성능 초기 검토에서 제안된 terminal 전용 API 추가는 승인된 generic lifecycle signature와
비목표를 바꾸므로 채택하지 않았다. 대신 block 내부 collection 순서, escaped cold Flow 비지원 KDoc,
multi-collector 호출량 검증으로 같은 위험을 계획 범위 안에서 닫았다.

최종 계획은 Task 0~9의 RED→GREEN, Lore commit, consumer compile omission, 양 module full test, detekt,
manual/terminology, emulator receipt, diff allowlist, rollback stop condition을 제공한다. push, PR, merge,
branch 삭제, release는 포함하지 않는다.

## 정적 검증 증거

- 계획 Markdown code fence: `120`, 짝수 균형 PASS
- smoke receipt shell block: `bash -n` PASS
- 한국어 용어 감사: findings `0`
- 미완성 표식 검색: findings `0`
- design/plan/review 문서 whitespace 검사: 오류 `0`
- 현재 worktree 변경 범위: 설계, 설계 검토, 구현 계획, 계획 검토 문서만 신규 추가

## 실행 전 남은 gate

- 사용자 구현 계획 승인: `PENDING`
- Task 0 pinned-base baseline 재실행: `PENDING`
- production/test/docs 구현: `PENDING`
- PR/merge/release: 현재 범위 밖

최종 상태는 `PENDING`이다. 사용자가 위 Plan SHA-256을 승인한 뒤에만 Task 0부터 실행한다.
