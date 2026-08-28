# #506 Bedrock 외부 publisher 지연·heap/throughput 측정 설계

## 문서 계약

- **독자:** `aws-java` 유지보수자와 #505/#485 후속 검토자
- **목적:** 실제 AWS 없이 Bedrock `StreamCoordinator`의 publisher cleanup 지연과
  장기 실행 자원 사용을 같은 JVM에서 재현하고, 해석 가능한 측정 artifact를 남긴다.
- **분류:** Type-E 테스트·측정 유지보수. production API, telemetry dependency,
  benchmark module, AWS endpoint는 범위에서 제외한다.
- **선행 결과:** #505의 controlled lifecycle/failure-retention harness와
  `RecordingSdkPublisher`가 `develop`에 반영되어 있다.

## 근거와 재사용

| 근거 | 재사용 또는 경계 |
| --- | --- |
| `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt` | `converseStreamFlow`, callback replacement, cancellation과 bounded failure의 실제 동작을 검증한다. |
| `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimePerformanceRuntimeAdapter.kt` | MockK client, `runSuspendIO`, single-thread dispatcher, `RecordingSdkPublisher` fixture를 재사용한다. |
| `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/RecordingSdkPublisher.kt` | demand·cancel·terminal 신호를 관찰하는 test-owned publisher를 재사용한다. |
| `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchPerformanceRuntimeAdapter.kt` | `ThreadMXBean` allocated bytes와 worker-id 측정 경계를 재사용한다. |
| `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchPerformanceTest.kt` | baseline/candidate JSON, p95/비교 artifact와 source hash 패턴을 재사용한다. |
| Issue #505 / PR #507 | controlled four-path 계약은 유지하고 외부 지연·장기 측정만 확장한다. |

새 의존성·새 benchmark module·production telemetry는 추가하지 않는다. 실제 AWS
호출 대신 MockK `BedrockRuntimeAsyncClient`와 test-owned publisher를 사용한다.

## 측정 계약

### Cleanup 모드

각 lifecycle 경로를 `IMMEDIATE`, `DELAYED`, `BLOCKING` publisher cleanup 모드로
실행한다.

- `IMMEDIATE`: cancel callback에서 cleanup completion을 즉시 기록한다.
- `DELAYED`: cancel request는 즉시 반환하고 test-owned scheduler가 고정 지연 뒤
  cleanup completion을 기록한다.
- `BLOCKING`: bounded worker에서 releasable latch를 기다린다. watchdog가
  cancel 관찰 후 latch를 해제하며, 5초 안에 해제되지 않으면 harness를 실패시킨다.

publisher cleanup은 cancel request 시각부터 completion까지, coordinator cleanup은
취소·실패·replacement를 시작한 시각부터 collector와 callback drain이 끝난 시각까지
별도 `Long` 필드로 기록한다. 두 값은 외부 AWS latency나 서비스 SLO가 아니다.

### Lifecycle 경로

기존 #505 계약의 네 경로를 유지한다.

1. `NORMAL`: 고정 event를 emit하고 operation을 성공 완료한다.
2. `COLLECTOR_CANCELLATION`: 첫 event 뒤 collector job을 취소한다.
3. `OPERATION_FAILURE`: publisher failure를 누적하고 operation future를
   `ValidationException`으로 실패시킨다.
4. `REPLACEMENT`: 첫 publisher를 교체하고 최신 publisher만 event/complete한다.

각 경로는 publisher cleanup completion, coordinator cleanup completion, event 수,
cancel 수, terminal 상태와 terminal 이후 late callback의 pending count 0을 기록한다.

### 장기 실행 측정

고정 event volume과 반복 수로 `NORMAL` 및 failure-retention 경계를 반복한다.

- `ThreadMXBean`으로 측정 worker의 allocated bytes를 기록한다.
- `Runtime` heap 사용량 before/after를 관찰값으로 기록하고 GC·호스트 노이즈를
  절대 상한으로 해석하지 않는다.
- event 수 / coordinator elapsed time으로 throughput을 계산한다.
- failure 20개에서 operation primary 1개, suppressed sample 최대 16개,
  overflow marker와 dropped count, 원 `Throwable` 참조 비보관을 검증한다.
- terminal close 이후 late callback publisher가 취소되고 pending callback count가
  0인지 검증한다.

### Artifact

`.bluetape/evidence/issue-506/perf/`에 다음을 기록한다.

- baseline/candidate commit metadata와 harness·adapter·publisher SHA-256
- latency raw samples와 모드별 p50/p95/p99
- publisher/coordinator latency 비교 JSON
- throughput, allocated bytes, heap delta raw/summary JSON
- retention summary와 pending callback count

artifact에는 command, JVM, dispatcher, warmup/measurement, event volume, cleanup
mode, runtime path와 결과 해석 경계를 포함한다. baseline은 source hash가 바뀔 때만
갱신하고, 동일 hash에서는 candidate를 갱신한다.

## 비기능 경계와 안전성

- 실제 AWS 계정·credential·endpoint를 사용하지 않는다.
- production source/public API/build/catalog/dependency/telemetry는 변경하지 않는다.
- `CountDownLatch` blocking cleanup은 bounded worker·watchdog·releasable latch로
  고립하며, executor/dispatcher는 모든 경로에서 `finally`로 닫는다.
- cancellation exception을 삼키지 않고, cleanup 관찰만 `NonCancellable` 경계에서
  수행한다.
- p50/p95/p99와 heap/throughput은 controlled snapshot이며 절대 성능 보장이 아니다.

## 수용 기준

1. 세 cleanup 모드가 네 lifecycle 경로를 모두 재현한다.
2. publisher/coordinator cleanup latency가 별도 필드로 기록되고 모드별 p50/p95/p99가 생성된다.
3. blocking 모드가 watchdog 해제 후 bounded time 안에 종료되며 executor leak이 없다.
4. 장기 실행 artifact에 throughput, allocated bytes, heap delta, raw samples와
   pending callback 0이 기록된다.
5. failure volume 20에서 primary/suppressed/overflow identity 계약을 검증한다.
6. `detekt`, targeted tests, `git diff --check`가 통과한다.

## 롤백과 후속 경계

- 실패 시 새 test/adapter와 문서만 한 단위로 되돌리고 #505의 controlled harness는
  보존한다.
- 호스트 노이즈로 측정 수치가 흔들리면 수치 artifact를 폐기하고 lifecycle assertion은
  유지한다.
- production telemetry, public API, 실제 AWS latency SLO, 공통 dependency 요구는
  별도 설계/이슈로 분리한다.

## Writer DoD

- **SPW-01:** 독자·목적·Type-E 범위·source ledger와 미지원 주장을 고정했다.
- **SPW-02:** cleanup/lifecycle/long-run/artifact/경계/수용/rollback 계약을 포함했다.
- **SPW-03:** 한국어 기술 문체로 작성하고 API·명령·URL·수치를 보존한다.
- **SPW-04:** #505 source, SQS precedent, live issues와 merged PR을 대조한다.
- **SPW-05:** 최종 Markdown headings/table/code token/link를 read-back한다.

## 상태

사용자 승인된 #476→#505→#506 순서에서 #476 병합과 #505 closeout 후 구현을 시작한다.
