# #505 Bedrock controlled regression harness 구현 계획

## 실행 경계

- Workflow: Type-E, component `issue-505-bedrock-perf-harness`
- Base: PR #504 exact head `99d872e82aeb8dd7b42e011ecd54acd750ac04cb`
- Worktree: `.worktrees/chore-issue-505-bedrock-perf-harness`
- Production 변경: 없음. `aws-java/src/test`, `docs/superpowers`, `docs/lessons`만
  소유한다.
- 후속 이슈: #506을 생성하고 #505 본문에 연결했다. 실제 지연 publisher와
  heap/throughput 실측은 이 계획에 넣지 않는다.

## 의존 순서와 gate

### 1. 계약·baseline 고정 — 완료

- `bluetape-workflow`, `bluetape-kotlin-patterns`, `bluetape-writer`와 triggered
  Kotlin testing/module references를 읽는다.
- GNO에서 SQS controlled regression precedent를 찾고, live `gh`로 #505/#506과
  PR #504를 확인한다.
- parent targeted baseline을 실행한다.

명령:

```bash
./gradlew --no-daemon --max-workers=1 --no-parallel \
  :bluetape4k-aws-java:test \
  --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest'
```

기대 결과: 기존 `BedrockRuntimeFlowExtensionsTest` 38개가 통과하고 production
source가 변경되지 않는다.

### 2. RED harness 추가 — 구현 전 실패 증거

다음 파일을 생성한다.

- `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimePerformanceTest.kt`
- `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimePerformanceRuntimeAdapter.kt`

RED 테스트 이름:

1. `controlledPublisherRecordsNormalCancellationFailureReplacementPaths`
2. `boundedFailureRunKeepsPrimarySamplesMarkerAndPendingMapBounded`

RED 명령:

```bash
./gradlew --no-daemon --max-workers=1 --no-parallel \
  :bluetape4k-aws-java:test \
  --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimePerformanceTest'
```

Expected RED: 아직 존재하지 않는 harness class/test로 test discovery 또는 compile이
실패한다. production source는 이 단계에서 수정하지 않는다.

### 3. Test-owned adapter와 lifecycle 측정 구현

`BedrockRuntimePerformanceRuntimeAdapter`에 다음을 구현한다.

- MockK `BedrockRuntimeAsyncClient`와 `CompletableFuture`를 고정한다.
- `RecordingSdkPublisher`를 사용해 demand·emit·cancel·terminal을 관찰한다.
- `runSuspendIO`와 single-thread `Executors.newSingleThreadExecutor()` dispatcher를
  사용한다.
- `NORMAL`, `COLLECTOR_CANCELLATION`, `OPERATION_FAILURE`, `REPLACEMENT`를
  명시적인 sealed/enum scenario로 분리한다.
- operation start, publisher cancel request, controlled cleanup completion을
  `System.nanoTime()`으로 기록한다.
- `await.atMost(...).untilSuspending`으로 demand/handler/cleanup을 기다리고,
  polling timeout에서 coroutine cancellation을 삼키지 않는다.
- publisher external time과 coordinator cleanup time을 다른 필드로 저장한다.

`BedrockRuntimePerformanceTest`에 다음을 구현한다.

- warmup 3회, measurement 10회로 동일 adapter/dispatcher 조건을 고정한다.
- scenario별 p50/p95/p99와 raw sample을 계산한다.
- `.bluetape/evidence/issue-505/perf/`에 baseline/candidate JSON을 기록한다.
- baseline은 harness/adapter SHA-256이 바뀔 때만 새로 만들고, 동일 SHA에서는
  candidate만 갱신한다.

### 4. GREEN lifecycle·retention 검증

GREEN 명령:

```bash
./gradlew --no-daemon --max-workers=1 --no-parallel \
  :bluetape4k-aws-java:test \
  --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimePerformanceTest'
```

검증:

- 네 scenario가 정상 종료하고 publisher cancel count가 경로별 계약과 일치한다.
- operation failure가 primary로 남고, cleanup failure sample은 bounded 된다.
- 20개 distinct failure에서 retained suppressed root가 16개를 넘지 않고,
  overflow marker가 원 `Throwable` identity를 보관하지 않는다.
- terminal close 뒤 late callback을 reject하고 pending callback map이 비어 있다.
- JSON에 commit, JVM, dispatcher, warmup/measurement, source hash, p50/p95/p99,
  raw sample이 모두 있다.
- `retention-summary.json`에 failure volume, retained sample 16개, overflow marker
  1개와 dropped count, duplicate identity, pending map 결과가 있다.

### 5. 모듈·Kotlin 검증

새 module/dependency를 추가하지 않으므로 KT-MOD-01/02/04는 N/A 근거를 기록한다.
새 benchmark module을 만들지 않아 `kotlinx.benchmark` task 등록도 N/A다.

순서:

```bash
./gradlew --no-daemon --max-workers=1 --no-parallel \
  :bluetape4k-aws-java:test \
  --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimePerformanceTest' \
  --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest'
./gradlew --no-daemon --max-workers=1 --no-parallel detekt
git diff --check
```

Kotlin checklist evidence:

- JUnit 5, MockK, bluetape4k assertions, `runSuspendIO`를 사용한다.
- real cancellation/cleanup을 test-owned publisher로 실행한다.
- 새 production `!!`, suspend `runCatching`, swallowed cancellation, blocking
  event-loop call을 추가하지 않는다.
- 모든 executor/dispatcher를 `finally`에서 닫는다.

### 6. lesson·receipt·handoff

`docs/lessons/2026-08-13-issue-505-bedrock-perf.md`에 다음을 기록한다.

- 결정: benchmark module 대신 controlled JUnit, 실제 지연/heap/throughput은 #506
- 실행 명령과 fresh 결과, scenario matrix, artifact 경로
- noisy host/절대 성능 주장 금지와 남은 gap
- SPW-01..05, KT-TEST-01/02/05, KT-FIN-01/03/04/06/07/08/10/11 결과

workflow receipt checks:

`design-contract → task-registration → red-harness → baseline-artifact →
green-validation → diff-check → lesson → completion-check → complete` 순서로
각 check에 command/result/evidence path를 기록한다.

## Rollback / rerun

- production source를 건드리지 않으므로 adapter/test/docs를 한 단위로 되돌린다.
- RED test가 기대대로 실패하지 않으면 구현을 시작하지 않고 test contract를
  수정한다.
- baseline artifact가 현재 harness SHA와 다르면 해당 baseline을 폐기하고
  parent exact head에서 3 warmup/10 measurement를 다시 실행한다.
- test timeout이나 executor leak이면 성능 수치를 해석하지 않고 lifecycle test와
  cleanup을 먼저 고친다.

## 완료 기준

- 설계·계획·lesson이 commit되고 SPW-01..05 read-back이 남아 있다.
- 두 performance test가 fresh GREEN이고 parent targeted test도 fresh GREEN이다.
- `detekt`, `git diff --check`, workflow receipt가 모두 통과한다.
- PR 생성은 별도 gate이며, merge는 CI와 exact-head 재확인 후 사용자의 별도 승인을
  받는다.
