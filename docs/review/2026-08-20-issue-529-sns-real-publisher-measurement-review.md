# Issue #529 SNS 외부 publisher 실환경 측정 검토

> 대상 이슈: [#529](https://github.com/bluetape4k/bluetape4k-aws/issues/529)
> 부모 이슈: [#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)
> 검토일: 2026-08-20

## 검토 범위와 근거

- `SnsCoroutinesTemplateAwsEmulatorTest`의 opt-in Floci 측정 경로
- `scripts/benchmarks/run_sns_batch_floci_measurement.sh` 실행 경계와
  `parse_sns_batch_benchmark.py` complete-matrix parser
- Floci raw artifact의 36셀 throughput/latency와 environment metadata
- `SnsBatchExecutor`의 기존 mixed/protocol/cancellation fault-injection 테스트

측정 경로는 실제 `SnsAsyncClient.publishBatch(...).await()` 호출을 사용한다. 다만
실제 AWS 계정에 접속하지 않았으므로 AWS 운영 성능에 대한 결론은 내리지 않는다.

## 판정

**Floci 측정 경로: PASS. Issue #529 전체: PENDING.**

| 검토 항목 | 판정 | 근거 |
|---|---|---|
| 실제 publisher 연결 | PASS | Floci topic 생성·삭제와 `SnsAsyncClient.publishBatch`를 실행했다. |
| 측정 행렬 | PASS | success/transport × 6 entryCount × 3 maxInFlight = 36셀, warm-up 1회·측정 3회다. |
| latency/throughput artifact | PASS | JMH 호환 JSON 36개씩과 parser summary를 생성했다. |
| cleanup telemetry | PASS | 전 셀 `activeAfter=0`, `maxActive<=maxInFlight`; success 완료 ID와 transport 0 완료 ID를 확인했다. |
| heap 관측 | PASS (범위 한정) | `MemoryPoolMXBean` peak sample을 기록했지만 profile/retention 증거는 아니다. |
| fake baseline 비교 | N/A | publisher/backend 경계가 달라 개선율 판정을 하지 않았다. |
| mixed/protocol/cancellation 외부 측정 | PENDING | 기존 fault-injection 테스트는 있으나 Floci 외부 행렬에는 포함하지 않았다. |
| 실제 AWS 측정 | PENDING | 자격증명·비용 승인과 실행 결과가 없다. |

## 주요 수치

| 지표 | Floci 측정 결과 |
|---|---:|
| complete cells | 36 |
| success throughput range | 38.46–302.66 operations/s |
| transport throughput range | 157.99–419.46 operations/s |
| maximum p95 latency | 29,370,375 ns |
| maximum peak heap sample | 69,161,008 bytes |
| cleanup violations | 0 |

수치는 commit `a83b6f5acf633bca114e66dbb8c92149f0d670d2`의 run-id
`issue-529-floci-20260820-final`, JDK 25.0.4, Gradle 9.7.0, macOS arm64, Floci,
단일 실행 결과에서
얻었다. 반복 실행 간 분산과 실제 서비스 환경의 네트워크·quota·계정 상태를
포함하지 않으므로 release 성능 기준선으로 승격하지 않는다.

## 발견 사항과 처분

1. **P1 — 실제 AWS 증거 부재**
   - 영향: Issue #529의 AWS publisher DoD와 비용 경계가 충족되지 않는다.
   - 처분: 자격증명과 비용 승인이 있는 별도 실행으로 보류한다. Floci 결과를
     AWS 결과로 대체하지 않는다.

2. **P2 — heap profile/장기 retention 부재**
   - 영향: `MemoryPoolMXBean` sample만으로 allocation graph나 장기 root 보존을
     증명할 수 없다.
   - 처분: 별도 후속 측정으로 남기고 현재 DoD에서 명시적으로 미완료 처리한다.

3. **P2 — 외부 mixed/protocol/cancellation 행렬 부재**
   - 영향: 해당 fault class의 동작은 기존 executor 테스트로 검증하지만 외부
     publisher의 지연·cleanup 특성은 아직 관측하지 못했다.
   - 처분: Floci가 fault injection을 지원하는지 먼저 확인한 뒤, 지원 범위만
     별도 scenario로 추가한다.

## 검증 증거

- RED: helper 추가 전 opt-in targeted test가 `compileTestKotlin`에서 unresolved
  reference로 실패했다.
- GREEN: Colima socket 환경에서 targeted test 1개 passing, Gradle build successful.
- Parser: `--require-complete-matrix` 통과, parser unittest 5개 통과.
- Static: runner `bash -n`, `git diff --check` 통과.
- Human review: 1인 개발자 정책으로 N/A.

## DoD Status

- [x] Floci 실제 publisher 측정 결과와 cleanup telemetry 검토
- [x] 측정 명령·환경·행렬·한계 문서화
- [ ] 실제 AWS publisher 측정 결과와 비용·자격증명 경계
- [ ] heap profile·allocation·장기 retention 후속 증거
- [ ] PR·CI·merge·local sync·cleanup

**최종 상태: PENDING — Floci 경로의 측정·검토는 완료했지만 Issue #529의 실제 AWS와
heap profile gate가 남아 있다.**
