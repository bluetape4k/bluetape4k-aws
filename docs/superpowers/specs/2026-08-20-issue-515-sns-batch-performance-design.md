# SNS 배치 성능·메모리·정리 관측 설계

## 목적

Issue #515는 Issue #456의 SNS 배치 실행기를 대상으로, 기능 동작을 바꾸지
않고 반복 가능한 성능·메모리·정리 증거를 수집한다. 이 작업의 결과는 특정
환경에서의 절대 성능 약속이 아니라, 동일한 커밋과 동일한 입력에서 회귀를
감지하고 다음 최적화 후보를 비교할 수 있는 기준선이다.

## 범위와 경계

- 기준선은 Issue #456이 develop에 병합된 커밋 `2ff6b957fee97ff...`의
  동작이다.
- 실행기는 현재의 10개 단위 분할, 입력 순서 보존, `maxInFlight` 상한,
  취소·전송·프로토콜 오류 계약을 유지한다.
- 새로운 공개 API, 재시도 정책, converter 또는 `BatchExecutionStrategy`
  SPI를 추가하지 않는다. 해당 확장은 후속 이슈의 범위다.
- 기본 측정은 자격 증명과 네트워크가 필요 없는 결정적 fake publisher로
  수행한다. Floci와 실제 AWS 측정은 별도 프로파일이며 기준선 판정에 섞지
  않는다.
- 측정 harness와 파서는 보호된 입력으로 취급한다. 후보 최적화가 입력,
  파서, 기준선 파일을 수정하면 해당 라운드는 신뢰 실패로 중단한다.

## 측정 계약

### 입력 행렬

성공 기준선은 `N = 1, 10, 11, 20, 21, 100`과 큰 bounded 경계 입력을 사용하고
`maxInFlight = 1, 2, 4`를 교차한다. 오류·취소 시나리오는 같은 fixture의 계약
테스트 행렬로 독립 실행하여 기능·정리 경계를 검증한다.

계약 테스트와 별도로 다음 publisher 시나리오를 독립 검증한다.

1. 전부 성공
2. 혼합 성공/실패 응답
3. 전송 오류
4. 프로토콜 오류(응답 ID 누락 또는 미지 ID)
5. 호출자 취소
6. 종료 정리(cleanup) 경로

각 성공 행렬의 분할 수는 `ceil(N / 10)`이어야 하며, 모든 결과와 실패의
입력 순서는 보존되어야 한다. 활성 publisher와 대기 chunk 수는
`maxInFlight` 경계를 넘지 않아야 한다. 실행기가 직접 보관하는 활성
chunk/entry root는 최대 `10 * maxInFlight` 수준으로 제한하고, 최종 결과
조립은 O(N)임을 별도로 기록한다.

### 지표

- 지연: chunk 완료와 전체 batch 완료의 p50, p95, p99
- 처리량: 초당 처리 메시지 수
- 할당: JMH GC profiler의 `gc.alloc.rate`와 가능할 때의 통제된 peak heap
  샘플
- 정리: active publisher, pending chunk, `completedEntryIds` root 수, SDK
  future 취소 관측, permit/chunk buffer 잔존 여부
- 관측성: low-cardinality 상태·원인·chunk 수·maxInFlight만 기록한다.
  payload, credential, token, 전체 메시지, 원문 예외 문자열은 기록하지
  않는다.

JMH percentile과 allocation은 동일한 JDK·OS·Gradle·커밋에서 수집한다.
실제 JVM heap 전체와 외부 publisher가 보관하는 객체 그래프의 상한을
주장하지 않으며, 이 코드가 직접 보관하는 root와 profiler가 관측한
allocation만 보고한다.

### 반복·판정

- warm-up 1회 이상 후 본 측정 3회 이상을 수행한다.
- 기준선과 후보는 같은 JVM 옵션, fork 수, iteration 시간, 입력 seed를
  사용한다.
- 3회 연속으로 모든 필수 조합이 성공해야 하며, 한 번이라도 기능 테스트,
  정리 경계 또는 bounded root 계약을 위반하면 후보를 탈락시킨다.
- p95 지연 또는 peak heap이 기준선보다 10% 초과 악화되거나 처리량이
  10% 초과 하락하면 회귀로 판정한다. 작은 변동은 단일 실행이 아니라
  반복 결과의 중앙 경향으로 판단한다.
- 목표를 달성하지 못한 최적화 후보를 억지로 채택하지 않는다. 개선이
  정체되거나 최대 라운드·회로 차단기에 도달하면 현재 최선 후보와 중단
  사유를 기록한다.

## Harness와 구현 배치

새 모듈을 만들지 않고 `aws-spring-boot` 모듈의 `benchmark` source set에
`kotlinx-benchmark` 플러그인을 연결한다. 이렇게 하면 `SnsBatchExecutor`의
internal 계약을 공개하지 않고도 기존 모듈의 test와 같은 fake publisher를
재사용할 수 있다. `tasks --all`로 확인한 생성 task는
`:bluetape4k-aws-spring-boot:benchmarkBenchmarkJar`,
`snsBatchThroughputBenchmark`, `snsBatchLatencyBenchmark`다. 기준선 실행은
`scripts/benchmarks/run_sns_batch_benchmark.sh <run-id>`로 jar 생성,
throughput(`-prof gc`), latency(`-bm avgt`), parser를 함께 고정한다.

fake publisher와 계약 fixture는 다음을 제공한다.

- deterministic seed와 Base58 식별자
- benchmark에서는 성공 publisher와 활성 호출·최대 활성 수·정리 관찰 hook을
  제공하고 `success`와 `transport` 두 시나리오를 같은 18셀 입력 행렬로
  측정한다.
- 계약 fixture에서는 chunk별 지연·성공·혼합·전송/프로토콜 오류를 주입하고
  pending 수와 완료 ID root를 검증한다.
- 호출자 취소 시 원래 `CancellationException` identity와 SDK
  `CompletableFuture.cancel` 전파를 확인하는 barrier

관찰 hook은 production API가 아니며 benchmark/test source set에만 둔다.
telemetry formatter는 low-cardinality 필드만 반환하고 민감한 원문을
삭제한다.

## 오류·취소·정리 계약

- publisher가 던진 원래 `CancellationException`은 호출자에게 동일
  identity로 전달된다.
- 호출자 취소는 활성 SDK future를 취소하고, 취소 barrier가 끝난 뒤에만
  실행기가 종료한다.
- 전송 오류가 발생하면 sibling publisher를 더 시작하지 않고 이미 완료된
  entry ID만 보존한다.
- 프로토콜 오류도 동일한 cleanup 경계를 사용하며, 응답 payload나
  credential이 예외/telemetry에 포함되지 않는다.
- 정상 종료 후 active/pending/future/permit/chunk buffer root는 0이어야
  하며 `completedEntryIds`는 해당 실행의 진단 범위 밖으로 누출되지 않는다.

## 산출물

1. benchmark source set과 deterministic fake publisher
2. RED→GREEN 계약 테스트 및 SDK future cancellation barrier 테스트
3. JMH JSON 원시 결과와 요약 parser
4. 고정 환경·기준선·후보별 결과를 담은 한국어 문서
5. PR 본문의 마지막 H2인 `## DoD Status`에 기준선, 최종 후보, 반복 수,
   회귀 여부, 남은 측정 한계를 기록

외부 publisher latency/cleanup telemetry와 실제 heap·throughput 측정은 이
이슈가 담당하는 후속 작업으로 남겨 둔다. 이 범위를 완료했다고 표시하려면
해당 측정 결과 또는 명시적인 차단 사유가 문서와 Issue #515에 남아 있어야
한다.
