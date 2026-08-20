# Issue #515 SNS 배치 성능 self-improve 교훈

## 맥락

Issue #456에서 도입한 SNS `PublishBatch` 경로가 입력 크기 `N=1, 10, 11, 20,
21, 100`과 `maxInFlight=1, 2, 4`에서 성능·정리 경계를 유지하는지 반복 측정했다.
기준선은 `5fdb685db89972af1b8c0e01dea067532d54e4ec`이며, fake publisher를 기본으로
사용하고 success/transport 두 시나리오의 36셀을 측정했다.

## 기준선 계약

- JDK `25.0.4`, macOS arm64, Gradle `9.7.0`과 측정 커밋을 각 결과에 기록했다.
- `scripts/benchmarks/run_sns_batch_benchmark.sh <run-id>`가 throughput과 latency
  측정을 분리하고, parser가 `--require-complete-matrix`로 36셀을 검증한다.
- 기준선은 3회 반복 중앙값으로 확정했다. aggregate throughput 중앙값은
  `2720212.0095569203 messages/s`, 최대 p95는 `24736.61003365924 ns`,
  peak-heap sample 최대는 `536870912 bytes`였다.
- 모든 기준선 셀에서 `activeAfter=0`, `maxActive<=maxInFlight`,
  `expectedChunks=ceil(N/10)`을 확인했고, `N=100` transport의
  `completedEntryIds`는 0이었다.

## 후보와 판정

1. **후보 1 — `completedEntryIds` 사전할당**
   - 완료 ID 목록의 초기 capacity를 입력 크기로 지정했다.
   - 3회×36셀 측정 aggregate throughput은 `2761813.7435756726`(+1.53%)였지만
     행별 최소 개선 조건과 heap 보호 조건을 동시에 만족하지 못했다.
   - 후보 코드는 winner branch에서 되돌렸고, 원시 결과와 거부 요약은
     `.omx/self-improve/tracking/raw/issue-515-candidate-{1,2,3}`에 보존했다.

2. **후보 2 — ordered result 목록 사전할당**
   - 성공/실패 결과 목록을 입력 크기 capacity로 생성하는 별도 worktree
     `experiment/issue-515/round-2-result-capacity`에서 검증했다.
   - 3회×36셀 측정 aggregate throughput은 `2778196.1402219627`(+2.13%),
     최대 p95는 `24112.421208654043 ns`였지만 peak-heap sample 최대가
     `570425344 bytes`(+6.25%)였고 행별 throughput/heap guard가 실패했다.
   - 후보 branch의 코드는 통합하지 않았고, worktree와
     `issue-515-candidate-2-result-capacity{,-2,-3}` 원시 결과를 보존했다.

두 후보 모두 aggregate 숫자만으로 승자를 선언하지 않고 parser의 행별 acceptance
rule을 적용했다. 따라서 winner는 없고 기준선 커밋과 benchmark harness만 유지한다.
후속 후보를 만들려면 새로운 병목 근거와 별도 rollback 지점을 먼저 제시해야 한다.

## 남은 후속 범위

- 이번 결과의 peak heap은 `MemoryPoolMXBean` sample이며 실제 heap profile의 대체가
  아니다. 실제 heap/throughput 측정과 장기 allocation 검증은 아직 **PENDING**이다.
- 외부/실제 AWS publisher의 latency와 cleanup telemetry도 fake publisher 결과로
  대체하지 않았다. controlled publisher, Floci, 실제 AWS를 각각 분리한 후속 측정이
  필요하다.
- 위 두 항목은 Issue #515의 후속 범위로 계속 추적하며, 이 lesson만으로 Issue #515를
  완료 또는 성능 개선 완료로 닫지 않는다.

## 검증과 운영 방어선

- parser unittest 5개, benchmark compile/jar, smoke benchmark, SNS Spring targeted
  test, `git diff --check`, `bash -n`을 통과했다.
- 후보 1의 초기 실험은 winner worktree에서 수행했지만 production diff를 즉시
  되돌리고 결과를 거부 기록으로 남겼다. 후보 2부터는 전용 candidate worktree를
  사용했다.
- 1인 개발자 저장소 정책에 따라 human review gate는 N/A이며, PR/CI/exact-head
  merge 및 local sync/cleanup은 별도 delivery gate다.

## DoD Status

- [x] 3회 기준선과 환경·36셀 raw evidence 보존
- [x] 후보 1·2의 RED/GREEN·3회 반복·행별 acceptance 판정
- [x] winner 미선정 및 기준선 복구 상태 기록
- [x] 외부 publisher telemetry와 실제 heap·throughput 후속 범위 명시
- [ ] PR·CI·merge·local sync·cleanup

**상태: PENDING — self-improve 측정은 안전한 winner 없이 종료했으며 delivery gate와
실제 publisher/heap 후속 측정이 남아 있다.**
