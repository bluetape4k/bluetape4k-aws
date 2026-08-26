# Issue #530 SNS 실제 AWS 측정 계획

## SPW-01 — 대상과 근거

- **문서 종류:** Type-E 측정 harness·운영 계획
- **독자:** AWS SNS 성능 측정을 승인하고 결과를 재현할 bluetape4k 유지보수자
- **목적:** Issue #529의 Floci 결과와 실제 AWS 결과를 섞지 않고, 승인된 계정에서만 실행할 측정 경계를 고정한다.
- **현재 근거:** Issue #530 본문, `SnsBatchExecutor.kt`, `SnsCoroutinesTemplateAwsEmulatorTest.kt`, `parse_sns_batch_benchmark.py`, `run_sns_batch_floci_measurement.sh`
- **미확인 사항:** 실제 AWS 지연시간·처리량·heap/allocation·retention·caller cancellation 결과와 hosted CI 결과는 아직 없다.

Issue #530에는 최적화 후보, 목표 개선율, 중단 기준이 없다. 따라서 Type-F 후보 경쟁으로 확장하지 않고 Type-E 준비 작업으로 분류한다. 이 계획은 생산 코드나 릴리스 기준선을 변경하지 않는다.

## SPW-02 — 범위와 계약

### 변경 파일

1. `scripts/benchmarks/sns_aws_measurement_preflight.py` — 승인·도구·endpoint 사전 검사
2. `scripts/benchmarks/run_sns_batch_aws_measurement.sh` — 검사 통과 뒤의 순차 실행 wrapper
3. `scripts/benchmarks/check_sns_measurement_redaction.py` — 텍스트 산출물 비밀값 검사
4. `scripts/benchmarks/test_sns_aws_measurement_preflight.py`
5. `scripts/benchmarks/test_check_sns_measurement_redaction.py`
6. `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsCoroutinesTemplateAwsMeasurementTest.kt` — opt-in AWS SDK v2 테스트 harness
7. 이 문서, `docs/lessons/2026-08-26-issue-530-sns-aws-measurement.md`, `docs/review/2026-08-26-issue-530-sns-aws-measurement-review.md`

### 실행 전 승인 게이트

wrapper는 다음 조건을 모두 만족하지 않으면 exit 2로 끝내고 AWS API를 호출하지 않는다.

- `BLUETAPE4K_AWS_SNS_APPROVAL=approved`
- 정확히 12자리인 `BLUETAPE4K_AWS_SNS_ACCOUNT_ID`
- 명시적인 `AWS_REGION`과 `AWS_PROFILE`
- `BLUETAPE4K_AWS_SNS_QUOTA_APPROVAL=approved`
- 양수인 `BLUETAPE4K_AWS_SNS_COST_LIMIT_USD`
- 60초 이상인 `BLUETAPE4K_AWS_SNS_RETENTION_SECONDS`
- `BLUETAPE4K_AWS_SNS_CONFIRM=I_UNDERSTAND_AWS_COST_AND_DATA_REDACTION`
- 비어 있는 `AWS_ENDPOINT_URL`, `AWS_ENDPOINT_URL_SNS`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`, `AWS_SECURITY_TOKEN`
- `aws`, `java`, `jcmd`, `jfr` 실행 파일

사전 검사는 profile 값, 계정 ID, 자격증명, topic ARN, 입력 ID, 메시지를 출력하지 않는다. 검사를 통과한 뒤에만 `aws sts get-caller-identity`의 계정 값을 메모리에서 비교하고 즉시 폐기한다.

### 측정 행렬과 산출물

기존 Floci 행렬과 같은 36셀을 사용한다. `scenario`는 `success`와 `transport`, `entryCount`는 `1, 10, 11, 20, 21, 100`, `maxInFlightBatches`는 `1, 2, 4`이다. 각 셀은 warmup 1회와 측정 3회를 수행한다.

각 셀에서 다음을 기록한다.

- `PublishBatch(...).await()` 전체 시간의 p50/p95/p99와 메시지 처리량
- active/max-active, chunk 수, 완료 entry 수와 ID 관측 수, 성공·실패 수
- `MemoryPoolMXBean` peak 값은 보조 지표로만 기록
- JFR의 allocation·old-object·GC·thread allocation 이벤트와 `jcmd GC.class_histogram` 요약
- topic 삭제 뒤 승인된 retention 시간 동안의 profile 상태

`throughput.json`과 `latency.json`은 기존 parser가 읽는 JMH 배열을 따른다. `environment.json`에는 backend, region, matrix, warmup/repetition, endpoint override 여부만 저장한다. `summary.json`, `heap-profile.jfr`, `allocation-summary.json`, `retention.json`, `capability.json`을 함께 보존한다. 계정 ID·profile·topic ARN·entry ID·message 본문은 모든 텍스트 산출물에서 제외한다.

JFR은 `jdk.ObjectAllocationInNewTLAB`, `jdk.ObjectAllocationOutsideTLAB`, `jdk.ObjectAllocationSample`, `jdk.OldObjectSample`, `jdk.GarbageCollection`, `jdk.GCPhasePause`, `jdk.ThreadAllocationStatistics`만 활성화한다. `SocketRead`, `SocketWrite` 같은 네트워크 이벤트는 설정하지 않는다. 이 산출물은 redaction-safe allocation/retention profile이며 HPROF 덤프가 아니다. JFR 또는 class histogram을 사용할 수 없으면 `capability.json`에 unavailable을 기록하고 실제 heap 기준선을 주장하지 않는다.

AWS backend가 혼합 응답·프로토콜 불일치·caller cancellation을 결정적으로 재현하지 못하면 `capability.json`에 `not_deterministically_reproducible`을 남긴다. 해당 경계의 결정론적 회귀 근거는 기존 `SnsBatchExecutorTest`가 담당한다.

## 실행 순서

1. Python RED 테스트로 승인 누락, endpoint 우회, 비밀값·payload 검사를 먼저 고정한다.
2. preflight와 redaction checker를 구현하고 Python 테스트를 GREEN으로 만든다.
3. `SnsCoroutinesTemplateAwsMeasurementTest`를 추가한다. 테스트는 `DefaultCredentialsProvider`와 기본 AWS endpoint만 사용하고, system property가 없으면 비활성화한다.
4. wrapper가 preflight → caller identity 비교 → Gradle 테스트 → parser → JFR metadata 확인 → redaction 검사를 순서대로 수행하게 한다.
5. 로컬에서는 실제 AWS 환경변수를 설정하거나 wrapper를 성공 경로로 실행하지 않는다. 현재 검증은 no-approval fail-closed, Python, Kotlin compile, shell syntax에 한정한다.

## 검증 명령과 기대 증거

```bash
PYTHONPATH=scripts/benchmarks python3 -m unittest \
  scripts/benchmarks/test_sns_aws_measurement_preflight.py \
  scripts/benchmarks/test_check_sns_measurement_redaction.py
bash -n scripts/benchmarks/run_sns_batch_aws_measurement.sh
./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin \
  --no-daemon --max-workers=1 --no-configuration-cache
```

승인 없는 wrapper 실행은 exit 2이며 `aws sts`를 호출하지 않아야 한다. 실제 AWS 실행은 승인된 계정·region·quota·비용·retention 값을 별도 운영 기록으로 남긴 뒤에만 수행한다. 성공 후에는 원시 JSON, parser `summary.json`, JFR, allocation/retention/capability 파일과 redaction PASS를 Issue #530에 연결한다.

## 재실행과 되돌리기

- 새 `run-id`마다 `.omx/self-improve/tracking/raw/<run-id>`를 사용하고 이전 원시 결과를 덮어쓰지 않는다.
- preflight 또는 redaction 실패는 결과를 기준선으로 사용하지 않고 원인을 수정한 뒤 새 `run-id`로 재실행한다.
- 변경을 되돌릴 때는 이 계획의 변경 파일을 함께 제거하고, 기존 #529 Floci 파일과 생산 코드에는 손대지 않는다.
- 실제 AWS 결과가 생기기 전에는 Issue #530을 닫거나 release/performance baseline을 선언하지 않는다.

## DoD

- [ ] AWS credential/account/region/quota/cost/retention 승인과 redaction 검증 — 외부 승인 대기
- [x] 고정된 command·matrix·warmup/repetition·commit/JDK/OS 기록 경로 — wrapper와 `environment.json` 계약
- [x] 실제 AWS latency/throughput/cleanup 원시 결과와 parser 경로 — harness 준비, AWS 실행 대기
- [x] JFR allocation/retention과 class histogram 경로 — profile 형식과 unavailable 상태 명시
- [x] mixed/protocol/cancellation capability 경계 — `capability.json` 계약과 기존 단위 테스트 연결
- [x] plan/lesson/review 문서 — 세 문서에 독립적인 근거와 미확인 사항 기록
- [ ] PR/hosted CI/merge/canonical sync/완료 worktree 정리 — PR 생성 권한과 실제 AWS 결과 이후
- [x] Human review — N/A: 1인 개발자 요청이며 이번 변경은 PR·외부 리뷰 없이 로컬 준비 범위

## Writer DoD

- [x] SPW-01: 독자·목적·현재 근거·미확인 사항을 고정했다.
- [x] SPW-02: 실행 순서, 파일, 산출물, 승인 게이트, 검증, 재실행·되돌리기를 포함했다.
- [x] SPW-03: 한국어 기술 문체와 `profile`, `payload`, `retention`, `capability` 용어를 일관되게 적용했다.
- [x] SPW-04: Issue #530, 기존 Floci harness/parser, `SnsBatchExecutor` 테스트와 계약을 대조했다.
- [x] SPW-05: 완성본을 다시 읽었고, 실제 AWS 결과가 없다는 상태를 체크박스에 남겼다.
