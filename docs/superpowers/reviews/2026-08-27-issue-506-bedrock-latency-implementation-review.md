# #506 Bedrock latency·heap/throughput 구현 리뷰

## 검토 범위

- 대상: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimePerformanceRuntimeAdapter.kt`,
  `BedrockRuntimePerformanceTest.kt`, 측정 설계·계획·risk·lesson 문서
- 제외: production `BedrockRuntimeFlowExtensions.kt`, public API, dependency,
  실제 AWS/credential/endpoint
- 분류: Type-E 테스트·측정 유지보수

## 결론

P0/P1 결함은 없다. #505의 adapter와 `RecordingSdkPublisher`를 그대로 확장해
세 cleanup mode와 네 lifecycle 경계를 같은 MockK client·single-thread dispatcher에서
재현한다. blocking 경로는 별도 daemon scheduler의 watchdog가 latch를 해제하고,
executor·dispatcher·scheduler는 `finally`에서 종료한다.

publisher cleanup은 cancel request부터 completion까지, coordinator cleanup은 각 path의
collector/callback 완료 시점까지 별도 측정한다. delayed 경로는 scheduler completion을
await하고, blocking 경로는 watchdog release와 bounded wait를 기록한다. cancellation
exception은 재전파하며 관찰용 cleanup만 `NonCancellable` 경계에서 기다린다.

## 수용 기준 대조

| 기준 | 증거 |
| --- | --- |
| 세 mode × 네 path | `externalPublisherCleanupModesSeparatePublisherAndCoordinatorLatency` |
| p50/p95/p99와 raw samples | `.bluetape/evidence/issue-506/perf/latency-*.json` |
| throughput/allocation/heap | `longRunRecordsThroughputAllocationHeapAndRetention` 및 `long-run-*.json` |
| failure primary/suppressed/overflow/identity | 기존 #505 retention test와 #506 long-run retention summary |
| pending callback 0 | 모든 sample assertion 및 retention/long-run artifact |
| source hash/JVM/dispatcher/command | `candidate-HEAD.json`, `baseline-commit.json` |
| 실제 AWS 미사용 | MockK `BedrockRuntimeAsyncClient` + test-owned publisher만 사용 |

## 검증 결과

실행 명령:

`./gradlew --no-daemon --max-workers=1 --no-parallel --no-build-cache \
  :bluetape4k-aws-java:test \
  --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimePerformanceTest' \
  --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest'`

결과: 42 passing, `BUILD SUCCESSFUL`.

추가 정적 검증은 `./gradlew --no-daemon --max-workers=1 --no-parallel --no-build-cache detekt`와
`git diff --check`로 수행했으며 모두 통과했다. detekt의 장문·line length 지적은
`@Suppress("LongMethod")`와 사전 계산된 hash 변수로 해소했다.

## 잔여 위험과 해석 경계

- heap before/after는 GC·호스트 노이즈가 있는 관찰값이며 절대 상한이 아니다.
- throughput은 controlled publisher와 동일 JVM dispatcher의 snapshot이며 AWS 서비스
  latency/SLO가 아니다.
- FlociServer에는 이 Bedrock ConverseStream 경계를 재현할 서비스 endpoint가 없어
  Floci를 가장하지 않았다. 이 범위의 외부 publisher 지연은 MockK/test-owned adapter로
  격리했다.
- baseline은 source hash가 바뀔 때 갱신되는 controlled capture이며, 별도 historical
  release benchmark가 아니다.

## Writer DoD

- **SPW-01:** 검토 대상·독자·Type-E 경계를 고정했다.
- **SPW-02:** 수용 기준·검증·위험·후속 분리를 기록했다.
- **SPW-03:** 한국어 사용자 문체와 원 API/path/token을 보존했다.
- **SPW-04:** #505 merged harness, SQS ThreadMXBean precedent, #506 issue 기준을 대조했다.
- **SPW-05:** 제목·표·명령·artifact path를 read-back했다.
