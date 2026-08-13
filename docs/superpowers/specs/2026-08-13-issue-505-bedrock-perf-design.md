# #505 Bedrock ConverseStream controlled regression harness 설계

## 문서 계약

- **독자:** `aws-java` 유지보수자와 #485 후속 검토자
- **목적:** `StreamCoordinator` lifecycle/failure-retention 경계를 외부 AWS 없이
  재현하고 baseline/candidate 측정 artifact를 남긴다.
- **범위:** Type-E 테스트·측정 유지보수. production API, telemetry dependency,
  AWS 서비스 호출은 포함하지 않는다.
- **후속 범위:** 실제 지연·blocking publisher와 장기 heap/throughput 실측은
  [#506](https://github.com/bluetape4k/bluetape4k-aws/issues/506)에서 다룬다.

## 근거와 현재 상태

| 근거 | 확인한 사실 |
| --- | --- |
| `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt` | `StreamCoordinator`, `StreamAttempt`, `BoundedFailureAccumulator`가 callback lock과 coroutine lifecycle을 소유한다. |
| `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensionsTest.kt` | 기존 회귀 테스트가 정상 완료, cancellation, replacement, operation failure와 bounded failure를 재현한다. |
| `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/RecordingSdkPublisher.kt` | test-owned Reactive Streams publisher가 demand, cancel, terminal signal을 관찰한다. |
| `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchPerformanceTest.kt` | 동일 JVM·dispatcher·warmup·measurement 조건과 `.bluetape/evidence/...` raw artifact 패턴을 이미 사용한다. |
| `docs/superpowers/plans/2026-08-11-sqs-batch-listener-plan.md` (GNO 검색) | 절대 benchmark 주장을 하지 않고 controlled regression과 raw artifact를 분리한다. |
| `gh issue view 505`, `gh issue view 506` | #505는 controlled harness로 좁혔고 실제 publisher/heap/throughput은 #506으로 연결했다. |
| `gh pr view 504` | 구현 기준점은 PR #504의 exact head `99d872e82aeb8dd7b42e011ecd54acd750ac04cb`다. |

현재 저장소에는 benchmark module/task가 없다. 중앙 catalog에
`kotlinx.benchmark` alias가 존재하지만, 새 benchmark module은 등록·CI·Kover·발행
경계를 확장한다. #505의 명시적인 JUnit controlled-regression 계약과 기존 SQS
패턴을 따르므로 새 module/dependency를 추가하지 않는다.

## 계약

### Scenario

`BedrockRuntimePerformanceRuntimeAdapter`는 다음 네 경로를 test-owned publisher로
재현한다.

| 경로 | publisher 동작 | 검증 |
| --- | --- | --- |
| `NORMAL` | 고정 event를 demand에 맞춰 emit하고 complete | collector 정상 종료, SDK flow cleanup cancel 1회 이상 |
| `COLLECTOR_CANCELLATION` | 첫 event 이후 collector를 취소 | future/publisher cancel 1회, cleanup 완료 |
| `OPERATION_FAILURE` | publisher가 event 후 실패하고 operation future도 실패 | operation cause가 primary, cleanup 결과 기록 |
| `REPLACEMENT` | 첫 publisher를 교체하고 최신 publisher만 event/complete | 이전 publisher cancel 1회, 최신 event만 전달 |

각 sample은 다음을 기록한다.

- scenario, runtime path, event/failure volume
- operation start와 controlled cleanup completion 사이의 `coordinatorCleanupNanos`
- publisher cancel request와 publisher cleanup completion 사이의
  `publisherCleanupNanos` (이번 harness에서는 test-owned immediate cleanup)
- event 수, cancel 수, terminal 결과, pending callback map이 비었는지
- JVM/dispatcher, warmup/measurement 수, current commit, harness/adapter SHA-256

실제 외부 지연은 이번 artifact의 값으로 해석하지 않는다. 지연 publisher와
blocking cleanup은 #506에서 별도 adapter로 측정한다.

### Failure retention

서로 다른 cancellation failure 20개를 replacement 경로에 주입한다.

- operation future cause 1개가 primary다.
- 서로 다른 suppressed root는 최대 16개만 직접 보관한다.
- overflow marker는 dropped occurrence count만 보관하고 원 `Throwable` 참조를
  보관하지 않는다.
- 동일 `Throwable` identity를 반복해도 suppressed에 중복 추가하지 않는다.
- terminal close 뒤 late callback은 reject되고 pending map은 비어 있다.

이 검증은 실제 JVM heap 상한이나 외부 `Throwable` object graph 전체의 상한을
주장하지 않는다. 그 측정은 #506의 후속 계약이다.

### Baseline/candidate artifact

테스트는 `.bluetape/evidence/issue-505/perf/`에 다음 파일을 생성한다.

- `baseline-commit.txt`, `baseline-commit.json`, `baseline-raw-samples.json`,
  `baseline-summary.json`
- `candidate-HEAD.json`, `candidate-raw-samples.json`, `candidate-summary.json`
- scenario별 p50/p95/p99 비교 JSON
- `retention-summary.json` (failure volume, retained sample, marker count/dropped
  count, duplicate identity, pending map)

첫 실행은 current HEAD를 baseline으로 기록한다. 같은 harness SHA-256이 유지되는
후속 실행은 baseline을 보존하고 candidate만 갱신한다. artifact는 `.bluetape/`로
제외되며, commit에는 측정 계약·명령·해석만 남긴다.

## 비기능 경계

- test는 `runSuspendIO`와 test-owned single-thread dispatcher를 사용한다.
- blocking AWS call이나 실제 AWS endpoint를 호출하지 않는다.
- production source와 public API는 변경하지 않는다.
- 절대 latency/throughput 목표를 설정하지 않는다. noisy host 결과는 artifact에
  남기되 lifecycle assertion이 hard gate다.
- 새 dependency, raw-JMH-only module, public telemetry API를 추가하지 않는다.

## 수용 기준

1. 네 scenario가 RED→GREEN 순서로 동작하고 각 publisher cleanup 결과를 기록한다.
2. warmup 3회와 measurement 10회를 동일 dispatcher/JVM에서 실행한다.
3. p50/p95/p99, raw samples, baseline/candidate commit과 source hashes가 모두
   생성된다.
4. failure 20개 입력에서 primary/suppressed/overflow identity 계약과 marker의
   원 `Throwable` 비보관을 검증한다.
5. terminal close 뒤 pending callback map이 비어 있음을 검증한다.
6. `detekt`, targeted test, `git diff --check`가 통과한다.

## 롤백과 후속 경계

- production source를 수정하지 않으므로 실패 시 새 test/adapter와 docs artifact를
  한 단위로 되돌리고 parent #504 기준으로 targeted test를 재실행한다.
- artifact가 noisy하거나 dispatcher가 고정되지 않으면 측정 결과만 폐기하고
  contract test는 보존한다.
- 외부 publisher 지연, 실제 heap/throughput, production telemetry 요구는 #506으로
  이동하며 이 branch에서 범위를 넓히지 않는다.

## Writer DoD

- **SPW-01:** 독자·목적·Type-E 범위와 source ledger를 위에 고정했다.
- **SPW-02:** 계약, 경계, failure mode, 수용 기준, rollback을 포함했다.
- **SPW-03:** 한국어 기술 문체와 안정된 용어를 적용하고 code token/URL/수치를
  보존했다. `korean-naturalness-checklist.md`를 read-back에 사용했다.
- **SPW-04:** current source, SQS precedent, GNO hit, PR #504, issues #505/#506을
  대조했다.
- **SPW-05:** 최종 Markdown을 다시 읽고 headings/table/code token/links를 확인했다.

## 상태

설계 승인 완료. 구현은 동반 plan의 RED→baseline→GREEN 순서를 따른다.
