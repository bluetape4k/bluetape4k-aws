# Issue #471 Spring Modulith SNS·SQS event externalization 구현 계획 6관점 검토

## 검토 범위

- 대상 설계: `docs/superpowers/specs/2026-08-26-issue-471-modulith-sns-sqs-design.md`
- 대상 계획: `docs/superpowers/plans/2026-08-26-issue-471-modulith-sns-sqs-plan.md`
- 이슈: [#471](https://github.com/bluetape4k/bluetape4k-aws/issues/471), 상위 Epic [#500](https://github.com/bluetape4k/bluetape4k-aws/issues/500)
- 계획 검토 기준 branch/head: `feat/issue-471-modulith-sns-sqs` / `09730917d6eaf713ba97b793ba49450924217363`
- 변경 경계: 설계 보정과 구현 계획만 검토했으며 production code는 변경하지 않음
- 실행 환경: 실제 AWS 계정 없이 `FlociServer` 기반 integration evidence를 사용하고 real AWS 검증은 N/A
- 협업 환경: 1인 개발이므로 human review는 N/A지만 독립 6관점 검토와 hosted CI는 생략하지 않음

## 최초 finding과 반영 결과

| 관점 | 최초 finding | 계획 반영 |
| --- | --- | --- |
| 성능 | bounded repeat와 SQS resolution single-flight 증거가 약함 | `@RepeatedTest(100)`, barrier fake, 호출 수·누수 invariant, single-flight 실패 제거를 Task 4·10에 고정 |
| 안정성 | cancellation cleanup이 무상한이고 Floci teardown·결정론 경계가 부족함 | `NonCancellable` 안의 bounded timeout, failure precedence, LIFO teardown, partial setup cleanup, 10초 종료 상한을 고정 |
| 보안 | SNS raw body preflight와 검증 순서, hostile throwable 로그 경계가 불충분함 | byte bound→bounded preflight→TopicArn allowlist→signature 검증 순서, cause 초기화 차단, adapter log no-leak을 고정 |
| 운영 | blocker 없음. readiness와 diagnostic evidence의 최종 실행 위치 확인 필요 | code/phase/outcome bounded metric, rollout·rollback, exact-head artifact와 Task 10 final proof를 유지 |
| 개발/API | registry lookup, consumer 생성자, 시간 ABI, exception catalog와 외부 constructor proof가 모호함 | internal resolved wrapper, public consumer/internal constructor, `java.time`, 18개 public catch catalog, 독립 negative compile task를 고정 |
| 사용자·호출자 | retryability·caller action과 최소 recipe, public exception 오용 방지가 약함 | code-owned action table, documentation recipe test, sealed base와 internal concrete constructor, 외부 catch fixture를 고정 |

## 핵심 수렴 결정

Spring Modulith 2.1의 실제 package를 기준으로
`EventExternalizationTransport`와 `EventExternalizerModuleListener`는
`org.springframework.modulith.events.support`, `EventSerializer`는
`org.springframework.modulith.events.core`, `EventExternalizationConfiguration`은
`org.springframework.modulith.events`로 고정했다. transport contract는
`externalize(Object, RoutingTarget): CompletableFuture<?>`이며 실제
`EventExternalizerModuleListener`와 `DefaultEventPublicationRegistry`를 사용하는 completion
integration test를 계획했다.

Outbound는 registration, target resolution, bounded envelope, admission, publish, close 순서를
RED/GREEN 단위로 나눴다. Inbound는 DIRECT와 SNS source를 분리하고 SNS에만 bounded
Notification preflight와 `SnsHttpMessageVerifier`를 적용한다. durable claim은 lease와 fencing을
사용하고 dispatch 뒤 complete에 성공한 경우만 acknowledge한다.

Public exception은 diagnostic code, phase, retryability, caller action이 고정된 18개 catch type으로
제한한다. concrete constructor는 모두 `internal constructor()`이며 외부 fixture가 catch/accessor
ABI를 컴파일한다. configuration과 dispatch constructor 오용은 서로 다른 expected-failure Kotlin
compile task로 각각 증명한다. adapter-generated exception의 cause는 초기화된 `null`로 잠가
`initCause` 우회도 거부한다.

Floci 검증은 `AwsSpringBootTestEmulator.get("sns", "sqs")`를 사용한다. 준비된 resource만 역순으로
정리하고 setup 실패와 cleanup 실패를 함께 보존한다. Docker-backed module test는 다른 heavy
command와 병렬 실행하지 않으며, 실제 AWS 계정 또는 서명된 AWS delivery는 완료 조건으로 두지
않는다.

## 최종 6관점 판정

| 관점 | 최종 결과 | 핵심 근거 |
| --- | --- | --- |
| 성능 | `P0=0, P1=0, P2=0, P3=0 — PASS` | bounded admission/payload, single-flight, 100회 반복과 leak/call-count invariant |
| 안정성 | `P0=0, P1=0, P2=0, P3=0 — PASS` | bounded cleanup, deterministic barrier/timeout, Floci LIFO/partial cleanup |
| 보안 | `P0=0, P1=0, P2=0, P3=0 — PASS` | SNS preflight/allowlist/signature 순서, no-leak 범위, cause mutation 차단 |
| 운영 | `P0=0, P1=0, P2=0, P3=0 — PASS` | diagnostic/readiness, rollout·rollback, exact-head artifact 계약 |
| 개발/API | `P0=0, P1=0, P2=0, P3=0 — PASS` | exact Modulith ABI, 18개 catch catalog, positive/negative 외부 compile proof |
| 사용자·호출자 | `P0=0, P1=0, P2=0, P3=0 — PASS` | retry/action mapping, 세 recipe, public catch와 constructor 오용 방지 |

최종 구현 계획 검토 판정은 `P0=0, P1=0 — PASS`다.

## writer와 계획 검증

| Gate | 상태 | 근거 |
| --- | --- | --- |
| SPW-01 한국어 독자 prose | 완료 | 설계·계획·검토 문서 terminology audit findings 0을 요구 |
| SPW-02 exact token 보존 | 완료 | API, package, property, diagnostic code, Gradle task를 exact token으로 유지 |
| SPW-03 locale parity | 계획 고정 | `README.md`와 `README.ko.md`를 같은 구조로 검증하는 Task 9 포함 |
| SPW-04 근거와 한계 구분 | 완료 | contract/mock, Floci, hosted CI, real AWS evidence 경계를 분리 |
| SPW-05 public artifact 안전성 | 완료 | placeholder, credential pattern, whitespace, code fence 검증을 계획/승인 gate에 포함 |

Task 1부터 Task 11까지 각 단계는 write scope, RED, GREEN, rollback/rerun, commit intent를
포함한다. Task 10은 module test, detekt, 외부 ABI positive/negative compile, full build,
`git diff --check`, 구현 diff 6관점 검토를 다시 실행한다. Task 11은 PR metadata와 exact-head
hosted CI를 별도 delivery gate로 둔다.

## 미검증 범위와 승인 상태

- Kotlin production code와 test는 아직 작성하지 않았다.
- Gradle, Floci runtime, Docker resource cleanup은 구현 승인 뒤 실행한다.
- hosted CI와 exact-head artifact는 PR delivery 단계에서 검증한다.
- 실제 AWS 검증과 human review는 사용자 환경·개발 방식에 따라 N/A다.

현재 상태는 `READY FOR USER APPROVAL`이다. 설계 보정과 구현 계획이 6관점 검토를 통과했으며,
승인 뒤 Task 1의 외부 consumer fixture RED부터 TDD 순서로 실행한다.
