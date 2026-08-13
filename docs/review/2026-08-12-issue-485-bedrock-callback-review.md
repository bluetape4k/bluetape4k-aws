# #485 Bedrock callback coordination 설계 review

## 검토 범위와 기준

- 대상 설계: `docs/superpowers/specs/2026-08-12-issue-485-bedrock-callback-design.md`
- 대상 코드: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt:62-232`
- 대상 테스트: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensionsTest.kt`
- 이슈: [#485](https://github.com/bluetape4k/bluetape4k-aws/issues/485)
- 외부 계약: [AWS SDK `EventStreamResponseHandler`](https://sdk.amazonaws.com/java/api/2.0.0/software/amazon/awssdk/awscore/eventstream/EventStreamResponseHandler.html)
- 기준 검증: targeted Bedrock baseline `BUILD SUCCESSFUL`, 20개 통과
- 검토 방식: 구현·테스트 변경 없이 설계와 현재 source를 read-only로 대조

설계 수정 전 독립 review에서 P1 stale handler failure 오귀속이 두 관점에서
수렴했다. 설계는 `handlerFailures` generation map 제거, pending callback drain,
operation future/attempt completion을 오류 원본으로 삼는 정책을 반영한 뒤 재검토했다.
Architect replacement lane은 cancellation primary/suppressed 전이의 구현 경계와
pending drain 선형화 지점을 추가 P1/P2로 지적했다. main session은 단일 cancellation
boundary의 precedence matrix, outer `finally` 보존, 동일 callback lock 아래의
등록·완료·제거·close 규칙, 양방향 lock 비중첩을 설계에 반영하고 재검토했다.

## 독립 관점 결과

| 우선순위 | 관점 | 근거 | 처분 | 재검토 |
|---|---|---|---|---|
| P1 | Performance | 수정 전 `handlerFailureFromCallback`이 현재 sequence에 stale 예외를 저장할 수 있었고, SDK callback에는 generation ID가 없다. | 수정: generation map을 제거하고 operation future/attempt completion을 오류 원본으로 명시했다. | 구현 concurrency test, targeted flow |
| P1 | Stability | replacement 뒤 늦은 `exceptionOccurred`가 새 generation을 오염할 수 있었다. 기존 테스트는 replacement 전에만 exception을 보냈다. | 수정: late handler exception 시나리오와 primary error precedence를 DoD에 추가했다. | 구현 concurrency test |
| P1 | Security/resource | publisher identity 없는 `Throwable` 보관은 stale failure과 operation 수명 중 retention을 만들 수 있었다. | 수정: `handlerFailures` 제거, callback completion pending drain/close clear를 명시했다. | 구현 resource/concurrency test |
| P1 | Developer/API | public API는 보존되지만 기존 lock 교체안은 callback error 귀속 의미를 정의하지 않았다. | 수정: callback lock은 non-suspending memory state만 보호하고, `exceptionOccurred`를 generation에 직접 귀속하지 않는 계약을 추가했다. | 구현 code review |
| P2 | Operator/Ops | 내부 lock 교체는 설정·metrics·로그·배포 절차를 바꾸지 않는다. 외부 publisher의 임의 지연은 이 변경으로 bounded하게 만들 수 없다. | 보류: lock 밖 호출과 primary-cause 보존만 구현하고, timeout/dispatcher 정책은 별도 follow-up으로 분리한다. | 별도 lifecycle 이슈 |
| P2 | User/caller | public signature, Flow buffer/backpressure, client ownership, retry/dedup 의미는 유지된다. handler failure 정책은 내부 동작이므로 caller migration은 없다. | 수정: KDoc/API migration 없음과 late failure 회귀 기준을 명시했다. | 구현 검증 |

### 보류 lane 실행 기록

첫 native review wave의 Performance/Stability/Security/Developer 결과와 Architect
replacement checkpoint를 사용했다. Architect의 material finding을 설계에 반영한 뒤
최신 spec/review를 main session이 다시 read-back했고, Operator/Ops와 User/caller
범위도 main fallback으로 확인했다. 이 fallback은 구현 승인이나 테스트 통과를
의미하지 않는다.

## 통합 판정

### P0/P1 수렴

- P0: 0건
- P1: 0건 (stale failure 오귀속과 cancellation primary masking의 설계 경계를 닫고
  회귀 기준을 추가함)
- P2: 2건
  - 외부 `SdkPublisher` cancel/subscribe의 응답 시간은 이 이슈의 내부 lock 교체로
    제한할 수 없음. lock 밖 호출, primary error 보존, throwing publisher 검증을
    본 이슈에 포함하고 timeout/dispatcher는 follow-up으로 보류한다.
  - 실제 high-retry allocation/retention 수치는 구현 후 deterministic test에서 확인한다.
- P3: 0건

### 설계 일치성 점검

- `ReentrantLock`과 기존 suspend `Mutex`의 책임 경계를 분리했다.
- callback lock 안에서 `await`, `join`, `emit`, `collect`, AWS 호출, `cancelImmediately`,
  `Mutex` 획득을 수행하지 않는 구조를 acceptance와 source review에 넣었다.
- callback completion은 pending 항목만 유지하고 close 시 drain한다.
- cancellation primary/suppressed precedence와 outer `finally`의 원인 보존을 명시했다.
- callback 등록·완료·제거·close의 선형화 지점은 같은 callback lock으로 고정하고,
  callback lock과 `Mutex`의 양방향 중첩을 금지했다.
- SDK callback에는 generation 식별자가 없다는 사실을 명시하고, operation future와
  publisher completion을 오류 원본으로 삼는다.
- public API, dependency catalog, Flow buffer/backpressure, retry/dedup/replay 계약,
  client lifecycle은 변경하지 않는다.
- 설계의 material change인 handler failure 정책과 callback retention drain은 사용자
  승인 후에만 구현 계획과 TDD로 진행한다.

## Writer gate

- SPW-01: PASS — 독자, 목적, 이슈/코드/테스트/공식 SDK source와 unresolved 외부
  publisher boundedness를 기록했다.
- SPW-02: PASS — 설계 review artifact의 범위, 근거, severity, 처분, gaps, verdict를
  모두 포함했다.
- SPW-03: PASS — 한국어 기술 문체, 고정 용어, code/API/URL 보존을 점검했다.
- SPW-04: PASS — review findings를 설계 섹션과 acceptance/DoD 항목에 연결했다.
- SPW-05: PASS — 최종 Markdown read-back과 `git diff --check`를 완료했다.

## 결론

설계 review gate는 **PASS (P0=0, P1=0)** 이다. 다만 설계가 최초의 단순 monitor
교체보다 넓어져 `handlerFailures` 제거와 pending callback drain을 포함한다. 이
material change에 대한 사용자 설계 승인을 받기 전에는 implementation plan, TDD,
production code, commit, PR을 시작하지 않는다.
