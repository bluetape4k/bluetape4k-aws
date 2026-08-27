# Issue #471 Spring Modulith SNS·SQS event externalization 설계 6관점 검토

## 검토 범위

- 대상: `docs/superpowers/specs/2026-08-26-issue-471-modulith-sns-sqs-design.md`
- 이슈: [#471](https://github.com/bluetape4k/bluetape4k-aws/issues/471), 상위 Epic [#500](https://github.com/bluetape4k/bluetape4k-aws/issues/500)
- 관점: 성능, 안정성, 보안, 운영, 개발/API, 사용자·호출자
- 경계: production code와 GitHub 상태를 바꾸지 않는 read-only 명세 검토
- 검증 환경: real AWS 계정 없이 Floci를 필수 integration evidence로 사용

## 1차 판정과 설계 수정

| 관점 | 최초 판정 | blocker | 설계 반영 |
| --- | --- | --- | --- |
| 성능 | P1 1 / P2 3 / P3 1 | outbound child 무상한 | `max-in-flight` semaphore, queue 없는 admission, payload byte budget, SQS single-flight, bounded-load invariant |
| 안정성 | P1 3 / P2 1 | close race, restart orphan claim, 복합 실패 우선순위 | `OPEN→CLOSING→CLOSED`, shared close completion, lease/fencing takeover, failure precedence table |
| 보안 | P1 3 / P2 4 | cross-account target, polymorphic deserialize, source 위조 | ARN/URL target 거부, concrete-type constrained JSON, DIRECT 또는 verified SNS mode, redrive startup gate |
| 운영 | P1 0 / P2 2 / P3 1 | 관측성, version rollout/rollback, exact-head evidence | bounded diagnostic catalog/metric, consumer-first rollout, state 보존 rollback, hosted artifact 계약 |
| 개발/API | P1 2 / P2 3 | idempotency ABI와 ack ownership 모호 | 정확한 suspend Kotlin SPI/TCK, public outcome과 internal ack, Modulith 2.1 signature/lifecycle |
| 사용자·호출자 | P1 2 / P2 3 | consumer-only startup과 DIRECT/SNS recipe 모호 | producer 기본 `false`, dependency matrix, 세 최소 recipe, 단일 built-in source 경계, README DoD |

## 관점별 결정

### 성능

producer admission은 lifecycle lock 안에서 permit과 job을 함께 등록하고 설정 상한을 넘으면
AWS call 없이 exceptional future를 반환한다. serializer 결과와 envelope를 단계별로
제한하고 동일 문자열을 재사용한다. concurrent 최초 SQS resolution은 single-flight이며
실패 entry만 제거한다. idempotency count/in-progress/key bytes와 deterministic load test를
추가해 이전 무상한 상태를 제거했다.

### 안정성

close 전환과 externalization registration을 같은 lock에서 선형화하고 concurrent close는
같은 completion을 기다린다. AWS completion과 cancellation은 first-terminal-wins다.
durable claim은 lease heartbeat, monotonic generation, stale-token fencing,
`recoverExpired`로 restart를 복구한다. handler/cancellation/complete/release/ack의 primary
failure와 suppressed cleanup 규칙을 표로 고정했다.

### 보안

producer destination은 topic/queue name만 받아 current client trust boundary 안에서
해석한다. inbound payload는 exact registry class, 제한된 JSON 구조, type-id 거부 뒤에만
deserialize한다. consumer는 queue당 DIRECT 또는 SNS source 하나를 명시하고 SNS는 expected
TopicArn과 existing `SnsHttpMessageVerifier`를 요구한다. event/payload/header/AWS 객체는
로그·metric에 노출하지 않는다.

### 운영

diagnostic code와 안전한 field, bounded metric을 고정했다. 새 event version은 consumer를
먼저 전개하고 producer를 나중에 켠다. rollback은 producer stop, in-flight/close 확인,
queue/DLQ drain, durable state 보존, consumer stop 순서다. exact-head `Test /
aws-spring-boot`와 `test-results-aws-spring-boot` artifact를 hosted evidence로 요구한다.

### 개발/API

registration generic/heterogeneous lookup, exact Spring Modulith 2.1
`CompletableFuture<?>` override, `AutoCloseable` destruction, idempotency token/result/mutation,
public consumer outcome와 package-private listener ack 책임을 Kotlin declaration으로
고정했다. optional classpath는 name-only outer guard, Modulith nested configuration,
service별 nested publisher 순서로 격리한다.

### 사용자·호출자

producer는 root enablement와 별도로 명시적으로 켠다. BOM 기반 dependency matrix와
producer-only, consumer-DIRECT, consumer-SNS 최소 recipe를 설계 DoD에 포함했다. built-in
listener는 queue/source 하나만 담당하며 복수 source는 별도 consumer/listener pair를
구성한다. README와 README.ko.md는 같은 구조로 configuration, source trust, diagnostics,
rollout/rollback을 안내한다.

## 재검토 판정

| 재검토 묶음 | 결과 | 핵심 근거 |
| --- | --- | --- |
| 성능·운영 | `P0=0, P1=0, P2=0, P3=0 — PASS` | bounded admission/payload/single-flight, atomic claim, diagnostics, rollout/rollback, exact-head artifact |
| 안정성·개발/API | `P0=0, P1=0, P2=0, P3=0 — PASS` | lifecycle/close, exact Kotlin SPI, ack ownership, generic registry, optional classloading |
| 보안·사용자/호출자 | `P0=0, P1=0, P2=0, P3=0 — PASS` | source trust, safe JSON, producer default, dependency/recipe, multi-source scope |

최종 설계 검토 판정은 `P0=0, P1=0 — PASS`다. 구현·README·Floci·hosted CI는 아직
실행하지 않았으며 구현 계획과 delivery DoD에서 증명한다.

## writer gate

| Gate | 상태 | 근거 |
| --- | --- | --- |
| SPW-01 한국어 독자 prose | 완료 | 용어 감사 findings 0 |
| SPW-02 exact token 보존 | 완료 | API/property/diagnostic code와 Gradle 좌표 read-back |
| SPW-03 README locale parity | 계획 고정 | 구현 DoD에 EN/KO 동일 구조 요구 |
| SPW-04 근거와 한계 구분 | 완료 | Floci/mock/real AWS 증거 경계를 분리 |
| SPW-05 public artifact 안전성 | 완료 | `git diff --check`, credential pattern audit; API `token` 식별자만 존재 |

## 승인 게이트

현재 상태는 `READY FOR USER APPROVAL`이다. 6관점과 writer gate를 통과했으며 사용자에게
written spec 승인만 요청한다. 구현 계획과 production code는 그 승인 전 시작하지 않는다.
