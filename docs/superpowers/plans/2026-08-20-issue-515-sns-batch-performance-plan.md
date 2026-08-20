# Issue #515 SNS 배치 성능 후속 실행 계획

## 목표와 완료 조건

Issue #456의 동작을 보존하면서 fake publisher 기준선과 반복 가능한
`kotlinx-benchmark` 실행을 만든다. p50/p95/p99, messages/sec,
allocation/peak-heap 관측, active/pending/completed root, future/permit/buffer
정리, low-cardinality telemetry를 같은 실행 계약으로 재현할 수 있어야 한다.

완료 조건은 다음과 같다.

- 결정적 fake fixture, parser, baseline이 보호된 입력으로 봉인된다.
- 성공·transport N·maxInFlight benchmark(시나리오별 18셀)와 혼합·프로토콜·취소
  계약 행렬을 RED→GREEN 테스트로 고정한다.
- 기준선 3회와 각 후보 3회가 같은 환경에서 수집된다.
- 기준선 대비 10% 회귀와 `10 * maxInFlight` root 위반을 자동 판정한다.
- 기능/취소/프로토콜/cleanup 검증과 detekt/module test가 통과한다.
- 실제 AWS 또는 Floci 결과는 별도 프로파일로 기록하며 fake 기준선과
  합산하지 않는다.
- PR 본문은 한국어로 작성하고 마지막 H2를 `## DoD Status`로 둔다.

## 단계

### 1. 기준선과 입력 봉인

- 기준 커밋 `2ff6b957fee97ff...`, JDK/OS/Gradle/CPU 정보를 기록한다.
- self-improve 상태에 목적, 1차 지표 방향, 10% 회귀 한계, 3회 반복,
  최대 라운드와 회로 차단기를 기록한다.
- fake fixture, benchmark command, JSON parser, baseline 경로를 sealed
  파일로 지정하고 `scripts/validate-sealed.sh`로 변경 여부를 검사한다.
- benchmark source set을 기존 `aws-spring-boot` 모듈에 추가한다. 새 공개
  모듈·dependency·public API는 만들지 않는다.

### 2. RED 계약 테스트

구현을 바꾸기 전에 다음 테스트를 추가하고 실패 증거를 남긴다.

1. 모든 N에서 `ceil(N / 10)` chunk와 입력 순서를 검증한다.
2. `maxInFlight`가 1/2/4를 넘지 않고 pending root가 bounded임을 검증한다.
3. publisher가 던진 원래 `CancellationException` identity를 보존한다.
4. 호출자 취소가 활성 SDK `CompletableFuture.cancel`로 전달되고,
   cancellation barrier 이후 종료하는지 검증한다.
5. 전송/프로토콜 오류 뒤 sibling 시작 금지, 완료 ID와 민감정보 비노출을
   검증한다.
6. 정상·실패·취소 종료 뒤 active/pending/future/permit/buffer root가
   비워지는지 검증한다.
7. benchmark parser가 percentile, throughput, allocation, root/telemetry
   필드를 결정적으로 읽고 10% 회귀를 거부하는지 검증한다.

새 테스트는 `bluetape4k-assertions`의 의도형 matcher를 사용한다. 예를
들어 범위는 `shouldBeLessOrEqualTo`, 포함/비포함은 `shouldContain` /
`shouldNotContain`, identity는 `shouldBeSameInstanceAs`로 표현한다.
식별자는 `Base58.randomString(16)`을 사용하고, boolean/string을 임의의
직접 비교로 우회하지 않는다.

### 3. 최소 구현과 GREEN

- fake publisher와 관찰 hook을 benchmark/test source set에 구현한다.
- 필요할 때만 production의 취소 barrier 또는 cleanup 경계를 최소 수정한다.
  `SnsBatchExecutor`의 공개 동작, chunk 크기, 결과 순서, 예외 타입은
  바꾸지 않는다.
- benchmark task 이름을 Gradle로 확인한 뒤 실제 command
  `scripts/benchmarks/run_sns_batch_benchmark.sh <run-id>`와 parser 명령을
  문서와 harness에 기록한다.
- 단계 2의 RED를 먼저 재실행해 GREEN을 확인하고, module targeted test,
  detekt를 순차 실행한다.

### 4. 기준선 측정

- warm-up 1회, 본 측정 3회 이상으로 JMH JSON을 생성한다.
- throughput 설정과 percentile/latency 설정을 분리하고 `-prof gc`로
  allocation을 수집한다.
- fake 성공 결과를 baseline JSON으로 저장하고 parser의 요약·판정 결과를
  함께 저장한다. transport/all-success의 completed-entry root와 cleanup
  barrier는 기능 테스트에서 교차 검증하며, benchmark peak-heap sample과
  혼동하지 않는다.
- Floci/실제 AWS는 capability·credential·비용 경계를 확인한 뒤 별도 명령으로
  실행하며 baseline gate에 포함하지 않는다.

### 5. 후보 반복과 중단

- 후보는 `experiment/issue-515/...` 임시 branch에서 하나씩 실행하고,
  sealed 입력은 수정하지 않는다.
- 각 라운드는 targeted test → benchmark 3회 → parser/threshold →
  cleanup/telemetry guard 순서로 검증한다.
- p95/throughput/heap 중 하나라도 10% 회귀하거나 root bound를 위반하면
  후보를 폐기한다.
- 개선이 없으면 plateau, 반복 실패면 circuit breaker, 최대 라운드면
  max-iteration으로 종료한다. 수치가 없거나 benchmark가 실패한 후보는
  개선으로 인정하지 않는다.
- 가장 좋은 후보와 기준선의 delta, 반복 수, 환경, 한계를 한국어 lesson과
  PR DoD에 기록한다.

### 6. 전달·정리

- `git diff --check`, Kotlin 테스트/정적 분석, benchmark parser, sealed
  validation을 재실행한다.
- workflow lane component evidence와 lesson을 기록한다. helper의
  `mutation-check`가 `_run` manifest 키를 인식하지 못하면 오류 원문,
  `resume-check`/`verify` checksum, owner-fenced 대체 검증을 함께 남긴다.
- PR metadata는 assignee `debop`, Issue #515 링크, 한국어 제목/본문,
  마지막 `## DoD Status`로 정리한다. 1인 개발자이므로 human review gate는
  N/A지만 exact-head CI와 fresh merge approval은 별도다.
- merge 전에는 exact head, CI, mergeability, issue/PR metadata를 다시 읽고
  사용자의 새 승인을 받은 뒤에만 merge한다. merge 후 `develop ==
  origin/develop`, Issue #515 상태, worktree/branch 정리를 증명한다.

## 롤백

benchmark source set·fake publisher·production cleanup 변경은 하나의
논리적 단위로 되돌린다. RED 테스트와 raw evidence는 보존하고, 기준선
커밋으로 복구한 뒤 targeted test와 sealed validation을 다시 실행한다.
취소 경계만 또는 측정 harness만 부분 롤백하지 않는다.

## 보류 범위

실제 AWS의 외부 publisher 지연/cleanup telemetry와 실환경 heap·throughput
측정은 후속 측정 범위다. 이번 PR에서 측정하지 못하면 `PENDING`으로 남기고
Issue #515에 재현 명령과 차단 사유를 갱신한다. converter, 공개
`BatchExecutionStrategy`, retry 정책, payload fingerprint는 각각 #514,
#518 또는 별도 후속 이슈에서 다룬다.
