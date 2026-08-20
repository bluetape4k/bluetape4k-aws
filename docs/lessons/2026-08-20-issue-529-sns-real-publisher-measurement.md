# Issue #529 SNS 외부 publisher 실환경 측정 교훈

## 맥락

Issue #515의 fake publisher 기준선만으로는 AWS SDK `SnsAsyncClient.publishBatch`
경로의 지연, throughput, heap, 정리 상태를 판단할 수 없었다. 후속 Issue #529에서는
Floci를 실제 backend로 사용해 기존 36셀 행렬을 같은 조건으로 재측정했다. 실제 AWS
측정은 비용과 자격증명 경계를 분리하기 위해 이번 실행에 포함하지 않았다.

## 측정 계약

- 측정 경로는 `SnsAsyncClient.publishBatch(...).await()`를 `SnsBatchExecutor`에
  연결한 실제 publisher다. fake publisher나 결과만 모사하는 fixture는 사용하지 않았다.
- 시나리오는 `success`와 `transport`다. `success`는 생성한 topic ARN을 사용하고,
  `transport`는 매 샘플마다 존재하지 않는 topic ARN을 사용한다.
- 입력은 `entryCount={1,10,11,20,21,100}`,
  `maxInFlightBatches={1,2,4}`의 36셀이다. 각 셀은 warm-up 1회와 측정 3회를
  실행하며 throughput 중앙값과 p50/p95/p99 latency를 보존한다.
- 각 샘플에서 payload·credential·token·전체 message 본문을 기록하지 않고,
  `activeAfter`, `maxActive`, chunk 수, 완료 entry 수·ID 수, peak heap sample만
  저카디널리티 telemetry로 기록한다.

## 실행과 결과

측정은 Colima socket을 명시한 다음 명령으로 재현한다.

```bash
scripts/benchmarks/run_sns_batch_floci_measurement.sh issue-529-floci-20260820-final
```

실행 스크립트는 `DOCKER_HOST`와
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`를 `/Users/debop/.colima/default/docker.sock`
에 맞추고, 결과를 `.omx/self-improve/tracking/raw/<run-id>/`에 보존한다. JMH 호환
`throughput.json`과 `latency.json`은 기존 parser의 complete-matrix 검증을 통과해야
하며, `environment.json`에는 backend, endpoint, commit, JDK, OS, Gradle, warm-up,
반복 수를 기록한다.

commit `a83b6f5acf633bca114e66dbb8c92149f0d670d2`에서 run-id
`issue-529-floci-20260820-final`로 실행한 Floci 측정 결과의 요약은 다음과 같다.

| 항목 | 관측값 |
|---|---:|
| 행렬 | 36셀 (`success` 18 + `transport` 18) |
| 측정 반복 | 셀당 3회, warm-up 1회 |
| success throughput 범위 | 38.46–302.66 operations/s (셀별 publisher operation) |
| transport throughput 범위 | 157.99–419.46 operations/s |
| 최대 p95 latency | 29,370,375 ns |
| 최대 peak heap sample | 69,161,008 bytes |
| `activeAfter != 0` | 0셀 |
| `maxActive > maxInFlight` | 0셀 |
| parser complete-matrix | PASS |

`entryCount=100`, `maxInFlightBatches=4` 셀에서도 success는 100개 entry와 100개
완료 ID를 보존했고, transport는 완료 entry·ID를 0개로 정리했다. 이 수치는 한 번의
Floci 실행에 대한 비교 가능한 측정 결과이며, 운영 환경의 절대 성능 순위나 회귀
개선율을 의미하지 않는다.

## 범위와 한계

- Floci에서 실제 SDK publisher의 success/transport 경계와 in-flight cleanup을
  확인했다. mixed failure, protocol failure, caller cancellation은 기존
  `SnsBatchExecutorTest`의 fault-injection·identity 테스트로 동작을 검증하지만,
  이번 외부 backend 측정 행렬에는 넣지 않았다.
- `MemoryPoolMXBean`의 peak usage는 heap profile이 아니라 JVM memory-pool sample이다.
  실제 heap profile, allocation flame graph, 장시간 retention 측정은 별도 작업이다.
- 실제 AWS publisher latency·cleanup telemetry와 실제 AWS heap·throughput은
  credential/cost 승인 전까지 **PENDING**이다. Floci 결과만으로 AWS 운영 성능을
  주장하지 않는다.
- #515의 fake baseline과 backend 결과는 환경과 publisher 경계가 다르므로 숫자를
  직접 합산하거나 개선율로 판정하지 않는다.

## 검증과 운영 방어선

- RED 단계에서 측정 helper가 없는 상태의 targeted test가 `compileTestKotlin`
  실패를 냈고, helper·artifact·parser를 연결한 뒤 같은 테스트가 Floci에서 1개
  passing으로 전환됐다.
- `scripts/benchmarks/parse_sns_batch_benchmark.py --require-complete-matrix`와
  parser unittest 5개가 통과했다.
- 1인 개발자 저장소 정책에 따라 human review gate는 N/A다. PR·CI·exact-head
  merge·local sync/cleanup은 별도 delivery gate다.

## DoD Status

- [x] Floci 실제 publisher latency·cleanup telemetry 보존
- [ ] 실제 AWS publisher latency·cleanup telemetry 보존
- [x] Floci backend heap sample·throughput 결과와 fake baseline 경계 보존
- [x] #515 후속 측정 항목과 문서에 Floci 결과·한계 반영
- [ ] 실제 heap profile·allocation·장시간 retention 후속 측정
- [ ] PR·CI·merge·local sync·cleanup

**상태: PENDING — Floci 측정과 parser/cleanup 검증은 완료했지만 실제 AWS 및 실제
heap profile 측정이 남아 있다.**
