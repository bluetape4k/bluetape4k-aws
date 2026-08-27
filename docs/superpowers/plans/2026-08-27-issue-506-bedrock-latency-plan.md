# #506 Bedrock latency·heap/throughput harness 구현 계획

## 실행 경계

- Workflow: Type-E, component `issue-506-bedrock-latency-harness`
- Base: `origin/develop` at `d62387d40a453852e2710057930ba8038347545a`
- Branch/worktree: `chore/issue-506-bedrock-latency-harness`
- Production 변경: 없음. `aws-java/src/test`, `docs/superpowers`, `docs/lessons`만 수정한다.
- 실제 AWS/Floci endpoint: 미사용. Bedrock client와 publisher는 MockK/test-owned fixture다.

## 의존 순서와 gate

### 1. RED 계약

기존 `BedrockRuntimePerformanceTest`에 #506 계약 테스트를 먼저 추가하고 아직 없는
`CleanupMode`·`runLongRun` API를 호출한다. targeted Gradle 실행은
compile/discovery 실패를 기록했으며 이 단계에서는 production source를 수정하지 않았다.

### 2. Test-owned adapter

기존 `BedrockRuntimePerformanceRuntimeAdapter`를 확장한다.

- #505의 MockK `BedrockRuntimeAsyncClient`, `RecordingSdkPublisher`, `runSuspendIO`
  경계를 재사용한다.
- `CleanupMode(IMMEDIATE, DELAYED, BLOCKING)`와 네 lifecycle path를 명시한다.
- delayed scheduler와 blocking worker/watchdog를 bounded로 관리하고 close한다.
- cancel request, publisher cleanup completion, coordinator completion 시각을
  별도 기록한다.
- late callback을 terminal 뒤 주입해 pending callback count 0을 기록한다.
- ThreadMXBean worker allocation과 Runtime heap snapshot을 측정한다.

### 3. GREEN latency/retention/long-run test

`BedrockRuntimePerformanceTest`에 다음을 구현한다.

- 세 cleanup mode × 네 path의 measurement 2회
- 모드/경로별 publisher·coordinator p50/p95/p99 및 raw samples
- 장기 normal path의 256 events × 4 measurements와 failure volume 20의
  throughput/allocation/heap artifact
- operation failure 20개의 primary/suppressed/overflow/identity/pending assertions
- baseline/candidate commit·JVM·dispatcher·반복·source SHA-256 metadata

절대 latency·heap·throughput 목표는 두지 않는다. lifecycle assertion과 artifact
completeness만 hard gate로 삼고, host noise는 해석 주의사항으로 기록한다.

### 4. 문서·artifact

- `docs/superpowers/specs/2026-08-27-issue-506-bedrock-latency-design.md`
- `docs/superpowers/plans/2026-08-27-issue-506-bedrock-latency-plan.md`
- `docs/superpowers/risk/2026-08-27-issue-506-bedrock-latency-risk.md`
- `docs/superpowers/reviews/2026-08-27-issue-506-bedrock-latency-implementation-review.md`
- `docs/lessons/2026-08-27-issue-506-bedrock-latency.md`

Artifact는 `.bluetape/`에만 생성하며 commit에 포함하지 않는다. 문서에는 실제 AWS
미사용, controlled 수치의 한계, #505 재사용, #506 범위와 후속 분리 경계를 기록한다.

### 5. 검증 순서

```bash
./gradlew --no-daemon --max-workers=1 --no-parallel --no-build-cache \
  :bluetape4k-aws-java:test \
  --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimePerformanceTest'
./gradlew --no-daemon --max-workers=1 --no-parallel --no-build-cache \
  :bluetape4k-aws-java:test \
  --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimePerformanceTest' \
  --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimePerformanceTest' \
  --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest'
./gradlew --no-daemon --max-workers=1 --no-parallel detekt
git diff --check
```

Kotlin test checklist: JUnit 5, MockK, Bluetape assertions, `runSuspendIO`,
`untilSuspending`, real cancellation/cleanup, `finally` resource close, no production
`!!`, no suspend `runCatching`, no swallowed `CancellationException`.

### 6. PR gate

PR 생성 전 implementation review에서 P0/P1=0, artifact JSON completeness, source
boundary, SPW-01..05와 KT-TEST/KT-FIN rows를 수렴한다. PR 생성·CI·merge는 common
gates를 따르며, merge는 exact-head fresh approval 후에만 수행한다.

## Rollback / rerun

- RED가 compile failure가 아니면 test contract를 먼저 수정한다.
- blocking watchdog timeout이나 executor leak이면 수치를 폐기하고 cleanup lifecycle을
  먼저 고친다.
- source hash가 바뀌면 baseline을 재생성하고, 동일 hash에서는 baseline을 보존한다.
- artifact JSON이 불완전하면 PR 진행을 멈추고 생성 경로를 수정한다.

## 완료 기준

- 설계·계획·risk·review·lesson이 한국어로 read-back되고 SPW-01..05가 기록된다.
- 세 cleanup mode × 네 path와 long-run measurement가 fresh GREEN이다.
- `detekt`, `git diff --check`, artifact completeness와 workflow receipt가 PASS다.
- production/build/dependency/public API 변경이 0건이다.
