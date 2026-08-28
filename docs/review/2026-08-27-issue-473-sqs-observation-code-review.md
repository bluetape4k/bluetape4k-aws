# 이슈 #473 SQS Observation 구현 검토

작성일: 2026-08-28

## 검토 범위

- 대상 branch: `feat/issue-473-sqs-observation`
- 코드 검토 exact head: `17489298f86122b2bf46a954e37c56a064cab508`
- merge base: `8baa578a77d4c41cdc3245fed8a1fa7fed11b1d0`
- develop 충돌 해소 merge: `d7364c31d7a4be3b33534bb214730b5ff9f0c8bb`
- GitHub issue: #473 `feat(aws-spring-boot): SQS ObservationRegistry·trace context 전파 지원`
- milestone/assignee: `1.0.0` / `debop`
- 검토 범위: SQS receive·process·acknowledgement observation, coroutine context 전파, auto-configuration,
  Floci acceptance, 성능 회귀, EN/KO 문서와 운영 진단
- 범위 제외: 실제 AWS 계정, IAM, 실제 AWS redelivery timing, production OpenTelemetry
  SDK·exporter·collector, hosted PR CI
- human review: 1인 개발자 저장소이며 별도 human reviewer가 없어 `N/A`
- 로컬 emulator: `bluetape4k-testcontainers`의 `FlociServer`만 사용

판정 규칙은 P0/P1 0건이다. P2/P3도 이번 검토에서 남기지 않았으며, 검증할 수 없는 실제 AWS와
production telemetry 범위는 Floci 결과로 대체하지 않는다. PR #586에는 develop 최신 문서를
보존하는 non-ff merge와 비동기 로그 수집기 경쟁을 제거한 테스트 보강만 추가되었고 production
동작·공개 ABI는 바뀌지 않았다.

## 구현 근거 원장

| 영역 | 소스 근거 |
| --- | --- |
| opt-in과 prerequisite | `SqsObservationProperties`, `SqsObservationAutoConfiguration` |
| public 확장점 | `SqsObservationFactory`, `SqsObservationConvention`, `SqsObservationContext` |
| coroutine context와 lifecycle | `SqsObservationRuntime`, `SqsPreparedObservation` |
| receive·process·acknowledgement 연결 | `SqsMessageListenerContainer` |
| 단건·배치 ACK 실제 I/O | `SqsAcknowledgement`, `SqsBatchAcknowledgement` |
| 기존 meter 공존 | `SqsMicrometerAutoConfiguration` |
| ABI·dependency 계약 | `SqsObservationBinaryCompatibilityTest`, `SqsObservationDependencyContractTest` |
| Floci acceptance | `SqsObservationAwsEmulatorTest`, `FlociServer.Launcher.floci` |
| 사용자·운영 계약 | EN/KO README, `storage-and-messaging.md`, `runtime-operations.md`, `CHANGELOG.md` |

## 6개 관점 판정

| 관점 | 상태 | P0/P1/P2/P3 | 판정 근거 |
| --- | --- | --- | --- |
| Performance | PASS | 0/0/0/0 | fatal heartbeat 전용 wrapper는 오류 경로에만 있다. disabled/NOOP fast path, ACK mutex 밖 observation 준비, FIFO I/O, queue-name cache는 변경되지 않았다. allocation upper95 `0.0 B/op`과 기존 JMH 결과가 현재 delta에도 적용된다는 독립 판정을 받았다. |
| Stability | PASS | 0/0/0/0 | `CancellationException`과 `Error`가 일반 `Throwable`보다 먼저 처리된다. heartbeat는 `coroutineScope`와 `cancelAndJoin`으로 수명을 닫고, generation stop·handler permit·FIFO ticket·ACK waiter cleanup 경계를 회귀 테스트로 고정했다. |
| Security | PASS | 0/0/0/0 | `FatalHeartbeatError`가 원본 `Error`를 cause로 보존하되 bounded fatal 로그에는 타입만 기록한다. single/batch 테스트가 generic heartbeat 로그, formatted message, throwable proxy에 비밀 문자열이 없는지 확인한다. 일반 handler `Error`는 별도 경계에서 원래 stack trace를 유지한다. |
| Operator/Ops | PASS | 0/0/0/0 | default-disabled, prerequisite backoff, 기존 meter 공존, `BT4K-SQS-OBS-101/201/202` 진단, restart/redeploy rollout·rollback, Floci와 실제 AWS 증거 경계가 source와 EN/KO manual에 정렬됐다. |
| Developer/API | PASS | 0/0/0/0 | 공개 확장점은 정제된 context와 bounded stage/tag만 노출한다. heartbeat wrapper는 private이고 기존 JVM descriptor를 바꾸지 않는다. `context-propagation`은 runtime dependency지만 public signature에는 노출되지 않는다. |
| User/caller | PASS | 0/0/0/0 | 직전 P1에서 일반 handler fatal stack trace가 사라진 회귀를 찾았다. heartbeat 전용 marker로 범위를 분리하고, 일반 handler throwable 보존과 heartbeat 비밀 비노출을 각각 테스트한 뒤 exact-head 재검토에서 승인됐다. |

## 발견 사항 처분

### 수정 완료

1. 초기 검토에서 default-disabled 활성화, telemetry 준비 실패, business delivery 우선순위와 문서 경계의
   불일치를 확인했다. auto-configuration·runtime·manual을 정렬하고 targeted/Floci 회귀로 닫았다.
2. observation 준비가 ACK mutex 또는 actual I/O 직렬 경계를 점유할 수 있던 경로를 mutex 밖으로 옮겼다.
   동시 terminal ACK, cancellation rollback, waiter 재시도와 allocation 회귀를 다시 검증했다.
3. telemetry setup 실패 로그가 원본 예외와 민감 정보를 노출할 수 있어 bounded reason 진단으로 바꿨다.
   `heartbeat_telemetry_setup`은 redelivery 결과를 바꾸지 않는다.
4. heartbeat observation 준비 실패의 source·manual·CHANGELOG 설명을 실제 fail-open/redelivery 계약과
   맞췄다.
5. broad `Throwable` 경계가 `CancellationException`과 fatal `Error`를 setup failure로 오분류할 수 있어
   두 예외를 먼저 재전파했다. 단건·배치·factory 경로의 원본 identity와 I/O 미수행을 검증했다.
6. 상위 heartbeat loop가 fatal telemetry `Error`를 raw Throwable과 함께 기록하고 계속할 수 있어
   fatal 경계를 분리했다. single/batch 테스트는 generation 중단, visibility I/O 0회, generic 진단과
   비밀 문자열 비노출을 검증한다.
7. 첫 보안 수정이 모든 사용자 handler `Error`의 stack trace까지 제거한 caller P1을 만들었다.
   `FatalHeartbeatError`를 private 내부 marker로 추가해 heartbeat fatal만 redaction하고 일반 handler
   throwable은 기존대로 기록하도록 복구했다. 강화 테스트 RED 1건을 확인한 뒤 관련 3건과 핵심 176건을
   GREEN으로 전환했고 보안·호출자 재검토가 모두 승인했다.
8. PR exact-head Floci 재실행에서 `ListAppender`의 기본 `ArrayList`를 백그라운드 컨테이너 로그와
   동시에 순회하는 테스트 경쟁을 확인했다. 해당 진단 테스트의 수집 목록만
   `CopyOnWriteArrayList`로 교체했으며 production source와 public API는 변경하지 않았다. 대상
   테스트 단독·클래스 전체·전체 module 재실행에서 경쟁 예외가 재발하지 않았다.

### 후속 delivery 경계

- 실제 AWS, IAM, 실제 redelivery timing, production OpenTelemetry exporter·collector는 계정과 운영
  환경이 없어 `N/A`다. Floci PASS는 이 범위의 성공을 뜻하지 않는다.
- PR #586의 hosted exact-head CI는 생성 직후 아직 check-run이 보고되지 않았다. path-filter를
  포함한 applicable job의 terminal 상태를 별도로 확인해야 한다.
- human review는 1인 개발자 저장소 조건으로 `N/A`지만, 6개 독립 model review와 exact-head 검증은
  생략하지 않았다.

## 검증 증거

| 검증 | 결과 |
| --- | --- |
| fatal 경계 TDD | 일반 handler 진단 강화 테스트 RED 1/1 확인 후, handler·single heartbeat·batch heartbeat 3/3 PASS |
| SQS 핵심 회귀 | `SqsMessageListenerContainerTest` 142, `SqsAcknowledgementTest` 16, `SqsBatchAcknowledgementTest` 18; 합계 176, failure/error/skip 0 |
| 전체 `aws-spring-boot` + Floci | 187 suites, 1,560 passing, 2 pending, failure/error 0 |
| Floci pending 분류 | `SnsCoroutinesTemplateAwsEmulatorTest` 측정 artifact와 실제 AWS SNS 측정 test 2건; SQS Observation acceptance skip 0 |
| compile/static | `compileKotlin`, `compileTestKotlin`, current module `detekt` PASS |
| allocation | 3/3 PASS, 30 paired samples, median `0.0 B/op`, upper95 `0.0 B/op` |
| JMH fast path | `directBaseline` `41.1966 ns/op`, `disabledFastPath` `41.4602 ns/op`, `activeProcess` `1328.5492 ns/op` |
| JMH contention | batch ACK p50/p95/p99 `44,736/194,304/225,536 ns/op`; heartbeat `97,152/201,984/224,000 ns/op`; single ACK `187,136/220,416/236,032 ns/op` |
| benchmark fail-closed | forced teardown 실패가 non-zero exit로 전파되고 hidden assertion/exception scan PASS |
| manual | manifest current; manual contract 9 runs/44 assertions, failure/error/skip 0 |
| 문서 정합성 | EN/KO parity test를 포함한 전체 module PASS; 한국어 terminology audit 5 files, finding 0 |
| diff | `git diff --check origin/develop...17489298f86122b2bf46a954e37c56a064cab508` PASS |

JMH와 allocation 값은 해당 로컬 JVM·workload의 회귀 기준이다. 임의의 절대 latency threshold로
일반화하지 않는다. 최종 fatal remediation은 오류 전용 분기이고 성능 hot path를 바꾸지 않았다는
exact-head 성능 독립 검토를 받았다.

## Kotlin 최종 체크리스트

| Gate | 상태 | 근거 |
| --- | --- | --- |
| KT-FIN-01 current surface | PASS | source, callers, tests, EN/KO docs와 `origin/develop...17489298f86122b2bf46a954e37c56a064cab508` diff를 대조했다. |
| KT-FIN-02 validation contracts | PASS | caller validation과 공개 exception 계약을 변경하지 않았다. |
| KT-FIN-03 unsafe constructs | PASS | 새 production `!!`, suspend `runCatching`, swallowed cancellation, event-loop blocking, monitor 기반 coroutine lock이 없다. |
| KT-FIN-04 lifecycle ownership | PASS | generation, heartbeat child, ACK ticket, cleanup과 fatal 경계를 source·회귀로 확인했다. |
| KT-FIN-05 Exposed boundaries | N/A | Exposed transaction·DDL·operator를 변경하지 않았다. |
| KT-FIN-06 triggered references | PASS | Kotlin test·coroutine·Testcontainers·Spring auto-configuration 계약을 적용했다. HTTP/HC5와 module layout은 변경하지 않았다. |
| KT-FIN-07 named behavior | PASS | JUnit 5·MockK·bluetape4k assertions를 사용하고 fatal 진단·비노출·I/O 0회를 직접 검증한다. |
| KT-FIN-08 public docs | PASS | README 요약과 EN/KO manual의 활성화·실패·Floci 경계를 source와 맞췄다. |
| KT-FIN-09 diagnostics | PASS | compile·전체 detekt PASS, import/deprecation 오류 0건이다. |
| KT-FIN-10 fresh validation | PASS | exact-head Floci·core·compile·detekt·manual·diff 검증이 모두 통과했다. allocation·JMH는 production 변경이 없는 test-only delta와 무관한 기존 exact-head 증거로 유지한다. |
| KT-FIN-11 final scope | PASS | 이슈 #473 범위만 포함하고 독립 6관점 P0/P1/P2/P3가 모두 0이다. |

## Writer·증거 게이트

| Gate | 상태 | 근거 |
| --- | --- | --- |
| SPW-01 audience/evidence | PASS | 독자는 구현·delivery 검토자이며 issue, exact head, source, test, benchmark, 문서 근거를 고정했다. |
| SPW-02 review contract | PASS | 범위, severity, 위치·증거, 처분, gap, 최종 판정을 구분했다. |
| SPW-03 Korean register | PASS | 기술 식별자와 수치를 보존하고 번역투·홍보성 표현 없이 검토 문체로 작성했다. |
| SPW-04 traceability | PASS | 발견 사항을 수정 commit·회귀·6관점 재검토·검증 결과에 연결했다. |
| SPW-05 readback | PASS | 표, heading, 명령·수치, N/A와 delivery gap을 최종 Markdown에서 다시 확인했다. |
| KO-01~KO-07 | PASS | 사실·식별자·수치를 보존했고 terminology audit 1 file, finding 0을 확인했다. |
| Human review | N/A | 1인 개발자 저장소이며 별도 human reviewer가 없다. |

## DoD Status

- [x] 이슈 #473 구현 범위와 exact code head 고정
- [x] fatal telemetry Error의 generic 로그 우회와 일반 handler 진단 회귀 수정
- [x] performance, stability, security, Ops, developer/API, user/caller 6개 관점 검토
- [x] 독립 검토 P0=0, P1=0, P2=0, P3=0
- [x] 실제 AWS 없이 `FlociServer` 전체 module 검증
- [x] allocation·JMH·compile·detekt·manual·문서 정합성 검증
- [x] 실제 AWS·production OpenTelemetry·human review N/A 경계 기록
- [x] PR #586 한국어 metadata·label·milestone·assignee와 exact head 고정
- [ ] PR exact-head hosted CI와 별도 merge gate

최종 판정: **PASS (local implementation/review/delivery)**. PR #586은 OPEN이며 exact head와
metadata가 고정됐다. hosted exact-head CI terminal 성공과 별도 merge 승인은 아직 남아 있다.
