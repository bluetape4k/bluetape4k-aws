# #485 Bedrock callback coordination 구현 계획 review

## 검토 범위와 증거 기준

- 계획: `docs/superpowers/plans/2026-08-12-issue-485-bedrock-callback-plan.md`
- 설계: `docs/superpowers/specs/2026-08-12-issue-485-bedrock-callback-design.md`
- production/test: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt`, `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensionsTest.kt`
- 외부 계약: [AWS SDK `EventStreamResponseHandler`](https://sdk.amazonaws.com/java/api/2.0.0/software/amazon/awssdk/awscore/eventstream/EventStreamResponseHandler.html)
- 현재 source 근거: resolved `EventStreamAsyncResponseTransformer.exceptionOccurred`와 `PublisherAsFlow.collectImpl` source excerpt/hash는 Task 2 Step 1에서 실행 시 durable evidence로 보존한다. 이 계획 review는 아직 그 명령을 실행했다고 주장하지 않는다.
- baseline: targeted Bedrock 테스트 20개 통과 기록. feature branch는 당시 `origin/develop`보다 1 commit 뒤였고, implementation 전 fast-forward를 Task 1에 둔다.
- workflow read-back: run `20260812T124952Z-bceda7ab`, receipt `verify` sequence 14/checksum 통과. `completion-check`는 component, main lane, required checks, main verification이 아직 missing이며 production Kotlin 변경은 없다.
- review 경계: 이 문서는 계획 gate만 판정한다. 구현·빌드·commit·PR·merge는 실행하지 않았다.

## 독립 6관점 결과

| 관점 | 독립 evidence | 판정 |
|---|---|---|
| Architect/Stability | `issue_485_design_resume` 최종 read-back: sequence-keyed map/O(1) 제거, typed cancellation outcome, lock 밖 signal, rejection direct throw, callbackLock 소유 accumulator 직렬화, JVM 멀티스레드 race RED, 그리고 `futureSucceeded`의 close/claim → completion await → Failed primary → sequence drain 순서를 확인했다. | **PASS (P0/P1=0), P2 2** |
| Contract/Developer/API | `plan_contract_review` 최종 evidence: accumulator mutation은 callbackLock 경계로 직렬화되고, AttemptCompletion.Failed(cause)만 publisher cause로 선택하며, callable `materializeOperationFailure()`와 멀티스레드 race RED가 계획에 고정됐다. `futureSucceeded` 순서 문구도 후속 patch에서 정리했다. | **PASS (P0/P1=0), P2 3** |
| Performance/Security/Resource | `plan_performance_security`의 bounded-retention finding과 최신 root read-back: 16개 identity-dedup sample, `Long` saturating overflow, stackless marker, one-shot materialization/clear, callbackLock 직렬화, 실제 JVM race RED가 계획에 고정됐다. 외부 publisher latency와 heap/throughput은 명시적 P2 보류다. | **PASS (P0/P1=0), P2 2** |
| Operator/Ops | `plan_operator_user`: lock 밖 `subscribe/cancel`, 설정·로그·metrics·client ownership 불변. 외부 publisher latency/telemetry는 별도 lifecycle P2로 보류한다. | **PASS (P0/P1=0), P2 1** |
| User/Caller | `plan_operator_user`: public Flow signature, `buffer(0)`, `request(1)`, terminal cause identity, caller-owned client lifecycle을 유지한다. cancellation/close race는 exact RED/GREEN으로 검증한다. | **PASS (P0/P1=0)** |
| Main integration | root read-only source/plan/spec integration: private coordinator only, no public API/dependency/catalog change; workflow and implementation gates remain fail-closed. Main integration is synthesis only and does not replace unavailable independent lanes. | P0/P1 없음 |

Independent lane가 final response를 남기지 않고 unavailable이면 해당 관점은 `blocked`로
기록하며 main integration을 독립 PASS로 승격하지 않는다. Performance/Security/Resource는
이전 독립 finding과 최신 root read-back으로 bounded 계약과 P2 범위를 재확인했다.

## 이전 P1 수정 추적

| finding | 최신 수정 | 상태 |
|---|---|---|
| post-handoff `job.cancel()`이 subscription cleanup failure를 놓침 | `cancelOnce()`의 `NonCancellable` request→join→typed completion outcome과 shared result deferred | 해소 |
| 정상 취소 `CancellationException` 오인 | 명시적 CE catch, `Cancelled(null)`, suppressed-empty RED와 deferred-CE RED | 해소 |
| callback lock 안 deferred signal | `CallbackCompletion` logical state/`drainClaimed`, lock 밖 `complete`/await | 해소 |
| callback completion 제거 비용/선형화 | sequence-keyed `LinkedHashMap`, 동일 `ReentrantLock` 아래 register/remove/snapshot/clear, `values.toList()` | 해소 |
| rejection failure가 operation future로 전달된다는 잘못된 주장 | direct callback synchronous throw identity와 operation future 상태를 별도 검증 | 해소 |
| resolved SDK handler authority 증거 누락 | dependencyInsight + source path/hash/excerpt + 실제 transformer/fixture propagation test; coordinator-only late callback test 별도 | 실행 증거 pending |
| 무제한 suppressed Throwable retention | identity dedupe, 최대 16 sample, `Long` saturating overflow count, stackless `SuppressedFailureOverflow(dropped=N)`, one-shot materialization/clear | 해소(RED/GREEN 실행 pending) |
| bounded retention RED 누락 | 4개 exact test와 Task 2 Step 8 exact command; Task 3 뒤가 아닌 RED 선행 | 해소(실행 pending) |
| replacement/outer finally 이중 취소 | 단일 `cancelActiveAttempt()` claim owner와 duplicate-suppression RED | 해소(실행 pending) |
| 문서/워크플로 gate 순서 | plan review PASS → lane recovery/mutation-check → baseline ff → Lore checkpoint commit → TDD | 실행 gate pending |

## Spec → Plan traceability

| 설계 acceptance | 계획 증거 |
|---|---|
| monitor 제거와 callback/Mutex 책임 분리 | Task 3 Step 1–5, Task 6 |
| 동일 callback lock의 등록·logical complete·remove·close snapshot/clear | Task 3 Step 2–3, sequence-keyed map |
| deferred signal과 모든 await는 lock 밖 | Task 3 Step 2–3, Task 6 Step 2 |
| generation 없는 handler failure authority | Task 2 Step 1/7, 실제 transformer와 coordinator-only test 분리 |
| pre/post-handoff cancellation 및 primary/suppressed | Task 2 Step 2–8, Task 4 Step 1–4 |
| bounded failure retention | Task 2 Step 6/8 RED, Task 3 accumulator, Task 5 GREEN |
| collector cancellation cleanup 중단 방지 | Task 4 `NonCancellable`, Task 5 |
| public API/backpressure/terminal ordering 유지 | Task 5 Step 4, Task 6, existing 20 tests |
| rollback과 재실행 | Task 7 |
| Type A receipts/DoD | Task 1, Task 8, Task 9 |

## Writer gate

- SPW-01 독자·목적·근거·미확정 사항 고정: PASS
- SPW-02 계획 artifact 구조와 실행 순서: PASS
- SPW-03 한국어 기술 문체·용어와 code/command 보존: PASS
- SPW-04 소스·설계·계획·테스트 traceability: PASS(실제 resolved source artifact 수집은 구현 전 실행 pending)
- SPW-05 최신 문서 read-back·checklist·diff-check evidence: PASS(fresh marker scan과 diff-check 완료)

## 통합 판정

- P0: 0
- P1: 0. bounded accumulator, `futureSucceeded` ordering, lock ownership, typed outcome,
  callable final materialization, race RED를 최신 계획에서 재확인했다.
- P2: 2
  - 외부 `SdkPublisher.subscribe/cancel` 임의 latency 및 cleanup bound/telemetry는 별도 lifecycle follow-up.
  - 실제 heap/throughput 또는 외부 Throwable 내부 graph 전체 크기는 주장하지 않고, 본 구현이 직접 보관하는 root Throwable reference 상한만 bounded contract로 고정한다.
- P3: 0

## DoD

- [x] 이전 P1을 계획·설계에 반영하고 exact RED/GREEN 위치를 재배치했다.
- [x] 6관점 입력과 main integration의 독립성 규칙을 기록했다.
- [x] SPW-01..04를 최신 artifact 기준으로 확인했다.
- [x] final independent lane outputs를 수집하고 SPW-05/final P0/P1 read-back을 완료한다.
- [ ] implementation lane 복구와 `mutation-check`를 실행한다.
- [ ] TDD RED/GREEN, compile, Detekt, targeted/full test와 fresh workflow evidence를 수집한다.
- [ ] code review/lesson/PR/CI/merge는 후속 별도 gate다.

최종 상태: **PASS — 계획 gate 통과; 구현 미착수, 다음은 workflow lane 복구와 TDD RED**.
