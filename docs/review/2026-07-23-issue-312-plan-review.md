# Issue #312 Bedrock Runtime 구현 계획 리뷰

## 범위

- 승인 설계:
  `docs/superpowers/specs/2026-07-23-issue-312-bedrock-runtime-design.md`
- 구현 계획:
  `docs/superpowers/plans/2026-07-23-issue-312-bedrock-runtime-plan.md`
- 대상 브랜치:
  `feat/issue-312-bedrock-runtime`
- 리뷰 단계:
  Type A Step 3-R
- 중단 조건:
  6개 관점 모두 `P0=0`, `P1=0`

이 리뷰는 프로덕션 코드 구현 전에 계획의 실행 가능성, 공개 계약,
운영 안전성, 자원 상한을 검증했다. 리뷰 에이전트는 파일을 수정하지 않고
발견과 근거만 보고했으며, 계획 수정과 최종 통합은 주 에이전트가 수행했다.

## SDK 소스 검증

계획의 공개 타입과 callback 계약은 Maven Central의 고정 버전 source
artifact를 기준으로 확인했다.

- AWS SDK for Java v2 `2.47.1`
  - `ConverseStreamResponseHandler`가 client method의 generated handler다.
  - 필수 callback은 `responseReceived`, `onEventStream`,
    `exceptionOccurred`, `complete`다.
  - stream event subtype은 `ContentBlockDeltaEvent`다.
  - `ContentBlock.text()`는 non-text union에서 `null`을 반환할 수 있다.
  - built client의 `serviceClientConfiguration().endpointOverride()`로
    builder-only endpoint override를 post-build 검증할 수 있다.
- AWS SDK for Kotlin `1.8.0`
  - `ContentBlock`은 sealed union이며 text 생성은
    `ContentBlock.Text(value)`다.
  - `ConverseStreamOutput.asContentBlockDeltaOrNull()`을 사용할 수 있다.
  - `BedrockRuntimeClient.config.endpointUrl`로 최종 endpoint를
    post-build 검증할 수 있다.
  - `ConverseResponse` 성공값은 request ID를 직접 노출하지 않는다.

## 1차 리뷰와 보정

### Developer/API

초기 결과는 `P0=0`, `P1=4`였다.

1. Java stream 성공 future 이후 active publisher를 너무 일찍 취소할 수
   있었다.
   - `futureSucceeded()`를 winning publisher terminal까지 기다리는
     suspending barrier로 고정했다.
   - outer `channelFlow`에도 `.buffer(0)`을 적용했다.
2. generic parent handler를 사용해 generated client signature와 맞지
   않았다.
   - `ConverseStreamResponseHandler`와 네 callback을 정확히 고정했다.
3. Java response helper가 absent/unknown output에서 NPE를 낼 수 있었다.
   - nullable native accessor와 `orEmpty()`를 사용하도록 바꿨다.
4. 신규 public API KDoc 실행 단계가 불완전했다.
   - Tasks 2–5의 모든 public client/model/operation/Flow helper에 영어
     KDoc 계약과 검증을 추가했다.

추가로 test publisher의 invalid demand, cancel, terminal 동작을 Reactive
Streams 계약에 가깝게 보강했다.

재리뷰 결과: `P0=0`, `P1=0`.

### Stability/Concurrency

초기 결과는 `P0=0`, `P1=3`이었다.

1. callback child job 실행 순서가 callback 도착 순서를 뒤집을 수 있었다.
   - callback 수신 시 sequence를 동기 할당하고 stale generation을
     재검사하도록 고정했다.
2. inner reactive bridge만 rendezvous여서 outer queue가 앞서갈 수 있었다.
   - outer `.buffer(0)`과 slow-collector barrier 검증을 추가했다.
3. SDK method가 callback 후 동기 예외를 던지면 cleanup을 건너뛸 수
   있었다.
   - nullable operation future 생성부터 `try/finally` 안에 넣고 해당
     회귀 테스트를 추가했다.

첫 재리뷰에서는 incrementality 테스트가 `first()`를 사용해 의도적으로
upstream을 취소하면서 `future.isDone == false`를 기대하는 모순 1건이
발견됐다. 비종료 barrier collector로 증분 전달을 검증하고, `first()`의
cancel-once 동작은 별도 테스트로 분리했다.

최종 재리뷰 결과: `P0=0`, `P1=0`.

### Operator/Ops

초기 결과는 `P0=0`, `P1=4`였다.

1. publication 경로의 대소문자가 실제 `BluetapeAws`와 달라 false-green
   가능성이 있었다.
   - 정확한 경로, `test -f`, exact-head metadata 재생성을 추가했다.
2. isolated consumer compile 검증이 없었다.
   - Java/Kotlin consumer fixture, 명시적 classpath, 두
     `KotlinJvmCompile` task를 추가했다.
3. smoke opt-in과 증거가 승인 설계보다 약했다.
   - property와 두 환경변수의 결합 gate, client 생성 전 skip,
     bounded timeout, allowlisted evidence를 고정했다.
4. PR 생성 직후 `WIP.md`가 stale해질 수 있었다.
   - 실제 PR 생성 후 WIP 전용 Lore commit/push와 새 exact-head
     재검증을 추가했다.

추가로 기존 diagram family의 full-size 선행 확인, 정확한 SVG/PNG
dimension 명령, live issue/PR/milestone 조회를 고정했다.

재리뷰 결과: `P0=0`, `P1=0`.

## 2차 독립 관점 리뷰와 보정

### Security

초기 결과는 `P0=0`, `P1=2`였다.

1. builder-only endpoint override가 explicit parameter 검증을 우회할 수
   있었다.
   - Java sync/async와 Kotlin 모두 built client의 최종 endpoint를
     검증하고 실패 시 등록/반환 전에 provisional client를 한 번 닫도록
     고정했다.
   - builder-only non-loopback HTTP 회귀 테스트를 추가했다.
2. smoke 실패 시 raw SDK exception이 JUnit/Gradle output에 노출될 수
   있었다.
   - exception class, error code, request ID 등 allowlisted field만 담은
     새 `AssertionError`를 사용하고 원본 cause/suppressed/message/stack을
     연결하지 않도록 고정했다.
   - sentinel secret을 포함한 fake exception으로 정제 계약을 검증한다.

재리뷰 결과: `P0=0`, `P1=0`.

### User/Caller

초기 결과는 `P0=0`, `P1=2`였다.

1. `takeUntil`을 hard bound처럼 설명할 위험이 있었다.
   - 다음 upstream event에서 멈추는 cooperative termination으로
     설명하고 hard deadline은 별도 `withTimeout` 예제로 분리했다.
2. README의 오류, 취소, partial output, caller-owned lifecycle 계약이
   부족했다.
   - native SDK exception 보존, exceptional future, upstream cancellation,
     facade-added retry 없음, partial output 비롤백, raw exception 비노출을
     양쪽 locale에 명시하도록 고정했다.

재리뷰 결과: `P0=0`, `P1=0`.

### Performance

초기 결과는 `P0=0`, `P1=2`였다.

1. `firstTextOrNull()`과 `textOrEmpty()`가 중간 `List`를 만들었다.
   - Java/Kotlin 모두 native content를 직접 순회하고 join은
     `buildString` 한 번으로 수행한다.
   - counting list와 source-contract 검증으로 first short-circuit,
     join 단일 순회, `textContents()` 비위임을 증명한다.
2. slow collector의 suspended `send`가 mutex 안에 있으면 retry
   replacement를 막을 수 있었다.
   - mutex는 non-suspending snapshot/transition만 보호하며 `send`,
     `emit`, `join`, subscribe, cancel을 mutex 안에서 금지했다.
   - A가 outer rendezvous에서 멈춘 상태에서도 B가 collector release
     전에 A를 cancel-once하고 활성화되는 barrier 테스트를 추가했다.

재리뷰 결과: `P0=0`, `P1=0`.

## 최종 게이트

| 관점 | P0 | P1 | 상태 |
|---|---:|---:|---|
| Developer/API | 0 | 0 | PASS |
| Stability/Concurrency | 0 | 0 | PASS |
| Operator/Ops | 0 | 0 | PASS |
| Security | 0 | 0 | PASS |
| User/Caller | 0 | 0 | PASS |
| Performance | 0 | 0 | PASS |

계획 자체 점검:

- 미해결 작업 표식: 없음
- release-bound `docs/manual/` write scope: 없음
- Java/Kotlin consumer fixture compile task: 명시됨
- public KDoc, bilingual README, locale별 SVG/2x PNG: 명시됨
- PR 생성 권한과 merge 별도 승인 gate: 분리됨
- `git diff --check`: PASS

결론: Step 3-R은 `P0=0`, `P1=0`으로 완료됐다. 다음 단계는 사용자에게
구현 계획 승인을 받은 뒤 Task 1부터 TDD 순서로 실행하는 것이다.
