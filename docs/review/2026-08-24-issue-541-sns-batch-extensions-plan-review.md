# Issue #541 SNS batch 확장 구현 계획 리뷰

## 리뷰 범위

- 대상 계획: `docs/superpowers/plans/2026-08-24-issue-541-sns-batch-extensions-plan.md`
- 저장소: `bluetape4k-aws`
- 작업 branch: `feat/issue-541-sns-batch-extensions`
- 기준 branch/commit: `origin/develop` / `fe24e60204d74d730bd189d2c67f260b1d834f79`
- 설계 기준 commit: `4eac62de1de8c1979cf8379fd2e1e6ffc9771530`
- 리뷰 시점: 2026-08-24

이번 문서는 구현 결과가 아니라 승인된 설계·실행 계획의 readiness를 판정한다. 설계 기준선에서 실행한 기존 SNS 회귀 기준선은 다음 명령으로 `BUILD SUCCESSFUL`, 12 tests 통과를 확인했다.

```text
./gradlew :bluetape4k-aws-spring-boot:test --rerun-tasks \
  --tests 'io.bluetape4k.aws.spring.sns.SnsOperationsBatchCompatibilityTest' \
  --tests 'io.bluetape4k.aws.spring.sns.SnsBatchExecutorTest'
```

## 여섯 관점 리뷰

| 관점 | 판정 | 계획에 반영한 핵심 경계 |
|---|---|---|
| 성능·구조 | PASS | 입력 chunk마다 coroutine을 만들지 않고 `minOf(maxInFlightBatches, chunkCount)` 고정 worker, rendezvous 결과 채널, no-queue claim, 10개 단위 resident bound를 사용한다. 최종 O(N) 결과 목록과 활성 resident bound를 분리해 측정한다. |
| 안정성·보안 | PASS | request 단위 guard, invocation-wide attempted ID, `OPEN -> CLOSING -> CLOSED`, `NonCancellable` drain, 형제 취소, fatal `Error` 전파, cause-free/redacted contract·conversion 예외를 명시한다. client는 caller-owned이며 template이 닫지 않는다. |
| 운영·복구 | PASS | runtime telemetry/IAM mutation은 이번 범위에서 제외한다. canary는 constructor·isolated consumer, stop/drain은 lifecycle test, rollback은 Phase 1 checkpoint로 고정하고 low-cardinality telemetry는 후속 범위로 남긴다. Floci 불가 시 fake publisher와 분리된 `PENDING` receipt를 남긴다. |
| 개발자·API | PASS | 2-인자 생성자를 보존하고 3-인자 strategy 생성자를 추가한다. strategy에는 raw AWS client가 아니라 `SnsBatchExecutionPort`만 노출하며 public KDoc, typed result, exact suspend signature, `Collection<Message<*>>` converter 계약을 명시한다. |
| 사용자·호출자 | PASS | 기본 serializer는 `String`만 허용하고 구조화 payload는 명시적 suspend serializer를 요구한다. 허용 header, UUID 우선순위, FIFO 조건, defensive copy, no-network conversion, 불확실한 원격 partial publish에 대한 whole-request 자동 재시도 금지를 문서화한다. |
| 통합·검증 | PASS | isolated classloader, legacy consumer, public constructor reflection, compileOnly/runtime POM·module metadata, README/manual 양국어 parity, release-pinned manual, focused/module/static/Floci 검증과 Type-A receipt 갱신 순서를 구체화했다. |

## 주요 발견과 해결

초기 독립 리뷰에서 발견한 P1/P2 항목은 계획 재검토 중 모두 해결했다.

| 발견 | 해결 |
|---|---|
| strategy가 guard를 우회하거나 raw client를 직접 보유할 위험 | strategy에는 library-owned guarded port만 전달하고, guard가 request ID subset·chunk 크기·중복 claim·동시성·SDK mapping을 단일 경계에서 소유하도록 고정했다. |
| active permit 해제 후 같은 ID 재시도 가능성 | `activeClaims`와 invocation-wide `attemptedEntryIds`를 분리해 release가 attempted 기록을 제거하지 않도록 했다. |
| cancellation 시 finally가 취소되어 claim이 남을 위험 | template finally 전체가 `withContext(NonCancellable)` 안에서 CLOSING 전환·형제 취소·SDK future 대기·zero claim 검증·CLOSED 전환을 수행하고 원래 `CancellationException` 인스턴스를 보존한다. |
| bounded metadata를 임의 cap하면 기존 recovery 계약이 깨질 위험 | `SnsBatchTransportException.completedEntryIds`와 `SnsBatchProtocolException.completedEntryIds`를 수정하지 않고 기존 전체 terminal-ID semantics를 보존한다. |
| protocol/strategy 오류가 raw payload·credential·ARN을 노출할 위험 | 새 contract/conversion 예외는 cause-free이며 enum·entry index·allowlisted field만 포함한다. 기존 transport/protocol 예외의 의미도 변경하지 않는다. |
| converter가 의존성을 runtime으로 전파할 위험 | `spring-messaging`은 version catalog alias와 `compileOnly`로만 추가하고 resolved configuration, generated POM, Gradle module metadata, isolated classloader로 검증한다. |
| Phase 1 rollback 기준선이 Phase 2 뒤에 위치하는 위험 | 계획을 재배치해 Task 3 phase-one targeted matrix 직후, converter 작업 직전에 checkpoint를 생성하도록 했다. |

## 계획 품질 영수증

- `SPW-01` 목적·범위·금지 범위·stop condition: PASS
- `SPW-02` 승인된 public API와 JVM descriptor 일치: PASS
- `SPW-03` 각 production 단계의 failing test와 구체적인 Gradle selector: PASS
- `SPW-04` guard·coordinator·converter·compileOnly·ABI·문서·Floci 검증 연결: PASS
- `SPW-05` PR/merge/publish/issue mutation을 별도 gate로 유지: PASS
- `KO-01` 한국어 reader-facing prose와 English agent-facing instruction 분리: PASS
- `KO-02` README/manual 영어·한국어 구조 parity receipt: PASS
- `KO-03` release `0.5.0` pin 불변 및 `Unreleased/develop` 표시: PASS
- `KO-04` public KDoc checklist와 lifecycle/no-network/redaction 계약: PASS
- `KO-05` wire-size preflight·Jackson3·ByteArray 후속 범위 분리: PASS
- `KO-06` telemetry/IAM 운영 범위의 명시적 제외: PASS
- `KO-07` Phase 1 rollback checkpoint의 물리적 순서: PASS

계획 파일 자체의 현재 정적 증거:

- `git diff --check`: PASS, 출력 없음
- Markdown code fence count: 60, 짝수 PASS
- trailing whitespace count: 0, PASS
- placeholder scan (`TODO`, `TBD`, `FIXME`, `<TestClass>`, `<path>`, `<value>`, `placeholder`): 0, PASS
- `audit-korean-terms.mjs ... --json`: `findings: []`, PASS

## 후속 후보와 남은 검증

구현 이후 별도 이슈로 승격할 후보는 SNS 262,144-byte individual/aggregate preflight, Jackson3 opt-in serializer, `ByteArray` 지원, low-cardinality strategy/chunk/protocol/transport telemetry이다. 각 후보는 byte accounting·media type·raw payload 비노출·benchmark evidence를 acceptance criteria로 삼는다.

아직 생성되지 않은 증거는 구현 단계에서 수집해야 한다. 실제 production code, ABI/classpath, generated metadata, manual release validator, Floci SNS batch smoke, module/static checks, implementation review, PR/merge/delivery 상태는 이 계획 리뷰의 PASS를 구현 완료로 해석할 수 없는 이유다.

## 판정

- P0: 0
- P1: 0 (초기 리뷰에서 확인된 drain·metadata cap·checkpoint 순서 이슈를 계획에 반영한 뒤 재검토)
- P2: 후속 후보와 실제 실행 증거 수집으로 한정
- 계획 readiness: **PASS — 구현 시작 가능**
- 구현: **PENDING**
- 검증·delivery: **PENDING**

## DoD Status

| 항목 | 상태 | 증거 |
|---|---|---|
| 승인 설계·구현 계획 artifact | PASS | 계획 파일과 정적 audit receipt |
| 독립 여섯 관점 계획 리뷰 | PASS | 본 문서의 관점별 판정 및 해결표 |
| 기존 회귀 기준선 | PASS | baseline command, `BUILD SUCCESSFUL`, 12 tests |
| production 구현 | PENDING | 구현 전 상태 |
| focused/module/ABI/manual/Floci 검증 | PENDING | 구현 후 수집 필요 |
| PR/merge/release/delivery | PENDING | 별도 명시 승인 필요 |
