# #505 Bedrock ConverseStream controlled harness 교훈

## 배경과 결정

PR #504의 `StreamCoordinator` lifecycle과 failure-retention 경계를 외부 AWS
endpoint 없이 반복 검증할 필요가 있었다. 저장소에는 별도 benchmark task와
CI·Kover·발행 경계가 없었고, GNO 검색에서 기존 SQS controlled regression
패턴을 확인했으므로 새 benchmark module이나 dependency 대신 `aws-java`의
JUnit 5 harness를 선택했다.

실제 지연·blocking publisher cleanup, 장기 heap·throughput, production
telemetry는 측정 성격과 실행 비용이 달라 [#506](https://github.com/bluetape4k/bluetape4k-aws/issues/506)으로
분리했다. #505에서는 test-owned publisher의 lifecycle과 bounded failure
contract만 hard gate로 둔다.

## 구현 결과

- `NORMAL`, `COLLECTOR_CANCELLATION`, `OPERATION_FAILURE`, `REPLACEMENT` 네
  경로를 같은 JVM과 single-thread dispatcher에서 실행한다.
- warmup 3회와 measurement 10회를 수행하고 coordinator cleanup 및
  test-owned publisher cleanup을 별도 기록한다.
- failure volume 20에서 operation future cause가 primary이고, retained root
  16개·overflow marker 1개·dropped 4건·중복 identity 1개·pending callback 0을
  확인한다. marker에는 원 `Throwable` 참조를 보관하지 않는다.
- baseline/candidate raw samples, p50/p95/p99, commit/JVM/dispatcher/반복 수와
  harness·adapter SHA-256을 `.bluetape/evidence/issue-505/perf/`에 남긴다.
  harness 또는 adapter hash가 바뀌면 baseline을 재생성하고, 같으면 baseline을
  보존한 채 candidate만 갱신한다.

## 검증 증거

실행 명령:

```bash
./gradlew --no-daemon --max-workers=1 --no-parallel --no-build-cache \
  :bluetape4k-aws-java:cleanTest --no-build-cache
./gradlew --no-daemon --max-workers=1 --no-parallel --no-build-cache \
  :bluetape4k-aws-java:test \
  --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimePerformanceTest' \
  --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest'
./gradlew --no-daemon --max-workers=1 --no-parallel detekt
git diff --check
```

최종 targeted 실행은 40개 테스트 통과, `BUILD SUCCESSFUL`이었다. controlled
artifact의 candidate summary는 samples 40, p50 `409420250 ns`, p95
`1025888416 ns`, p99 `1029273208 ns`로 기록되었지만, 이는 Awaitility와
test-owned publisher를 포함한 회귀 harness 값이며 외부 publisher latency나
처리량 목표가 아니다. 최종 로그는
`.bluetape/evidence/issue-505/targeted-final.log`에 있다.

## 놓친 점과 다음 방어선

- 첫 구현은 adapter hash가 바뀌어도 baseline을 보존했다. baseline stale 판정에
  harness와 adapter 두 SHA-256을 모두 포함하도록 수정하고 fresh run으로
  baseline/candidate를 재생성했다.
- `pendingCallbackCount`는 terminal close 뒤 late callback이 즉시 cancel되는
  관찰값이다. production map 자체를 공개하는 test hook은 추가하지 않았다.
- 실제 지연 publisher의 blocking/cancellation latency, heap root/reference와
  throughput 수치는 #506에서 별도 adapter·실행 환경·반복 정책으로 정한다.

## DoD

- **SPW-01:** 독자·목적·결정·범위·후속 이슈를 기록했다.
- **SPW-02:** scenario, retention, artifact, rollback과 수용 결과를 기록했다.
- **SPW-03:** 한국어 기술 문체를 사용하고 API·명령·URL·수치를 보존했다.
- **SPW-04:** source, 기존 SQS precedent, GNO hit, PR #504, issues #505/#506을
  대조했다.
- **SPW-05:** 최종 Markdown headings, code token, 링크와 evidence 경로를
  read-back했다.
- **KT-TEST-01/02/05:** JUnit 5·MockK·Bluetape assertions·`runSuspendIO`와
  실제 cancellation/cleanup을 사용했다.
- **KT-FIN-01/03/04/06/07/08/10/11:** executor/dispatcher를 `finally`에서
  닫고, production API·dependency·`!!`·suspend `runCatching`·swallowed
  cancellation을 추가하지 않았다.
