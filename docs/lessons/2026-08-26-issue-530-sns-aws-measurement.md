# Issue #530 SNS 실제 AWS 측정 준비 lesson

## SPW-01 — 맥락과 근거

Issue #529는 Floci에서 36셀 SNS batch 경로를 측정했지만, 자격증명·비용 승인이 없어 실제 AWS latency, throughput, cleanup, heap profile을 남기지 못했다. Issue #530은 이 공백을 후속 측정으로 분리한다. 이번 기록의 근거는 `SnsBatchExecutor.kt`, 기존 Floci 테스트와 parser, 새 AWS wrapper·preflight·measurement test이다.

## 결정

최적화 후보나 목표 개선율이 없는 후속 측정이므로 Type-F가 아닌 Type-E로 진행했다. 승인되지 않은 계정 호출을 막기 위해 preflight가 profile 기반 자격증명, 계정·region·quota·비용·retention 승인, endpoint override 부재, 실행 도구를 확인하도록 했다. wrapper는 preflight를 통과한 뒤에만 caller identity를 비교한다.

입력 ID와 message는 매 샘플에서 만들지만 파일·로그에 쓰지 않는다. JFR은 allocation/retention 관련 이벤트만 선택하고, HPROF 대신 redaction-safe JFR과 class histogram을 저장한다. AWS backend에서 혼합 응답·프로토콜 오류·caller cancellation을 재현하지 못하면 capability 결과를 `not_deterministically_reproducible`로 분리한다.

## 결과

로컬 준비 결과는 다음과 같다.

- preflight와 redaction checker Python 테스트 8건 통과
- `bash -n scripts/benchmarks/run_sns_batch_aws_measurement.sh` 통과
- `:bluetape4k-aws-spring-boot:compileTestKotlin` 통과
- 승인 없는 wrapper 실행은 exit 2로 끝났고 AWS identity 호출 전에 중단됐다.
- 새 Kotlin 테스트는 system property가 없으면 실행되지 않으며, 기존 production API는 변경하지 않았다.

실제 AWS raw result, JFR 내용, class histogram, long retention 관측은 아직 없다. 따라서 Issue #530의 performance baseline이나 release readiness는 PENDING이다.

## FlociServer 보조 측정

실제 AWS를 호출하지 않고 `bluetape4k-testcontainers`의
`FlociServer.Launcher.floci`를 통해 `issue-530-floci-20260826`을 실행했다. 36행
행렬(`success`/`transport` × `entryCount` 6개 × `maxInFlightBatches` 3개)이 생성되었고,
warmup 1회·측정 3회, parser JSON 검증, redaction 검사가 통과했다.

이는 로컬 Floci/JVM 경로의 재현 가능한 보조 baseline이다. 실제 AWS publisher의
backend 지연시간, 비용·quota, 장기 retention, heap profile을 대체하지 않으므로 Issue
#530 완료나 production baseline으로 승격하지 않는다.

## 놓친 점과 보완

`MemoryPoolMXBean` peak 값만으로는 backend heap profile을 증명할 수 없다. 이번 harness는 JFR와 class histogram을 별도 산출물로 만들지만, JFR이 HPROF와 같은 전체 heap dump는 아니라는 점을 문서와 `capability.json`에 명시했다. 또한 AWS 서비스가 원하는 혼합·프로토콜·취소 오류를 항상 재현한다는 가정은 버렸다.

## 재발 방지 규칙

1. 실제 AWS 성능을 말할 때는 backend, commit, JDK, OS, Gradle, matrix, warmup/repetition, 원시 결과와 parser 요약을 함께 제시한다.
2. 승인 없는 실행, endpoint override, 환경변수 자격증명 혼용은 preflight에서 거부한다.
3. 결과 디렉터리에는 credential, account ID, profile, topic ARN, entry ID, message를 저장하지 않고 redaction checker를 마지막 단계로 실행한다.
4. AWS에서 재현하지 못한 fault boundary는 성공으로 간주하지 않고 capability 상태로 남긴다.
5. Floci 결과는 실제 AWS 결과와 backend를 명시해 분리한다.
6. 실제 AWS와 heap/retention 증거가 모두 생기기 전에는 Issue #530을 닫지 않는다.

## Writer DoD

- [x] SPW-01: Issue #529/#530과 현재 소스·스크립트를 근거로 맥락을 고정했다.
- [x] SPW-02: 결정, 결과, 놓친 점, 재발 방지 규칙을 기록했다.
- [x] SPW-03: 불확실성과 형식 한계를 완곡하게 숨기지 않고 한국어 기술 문체로 적었다.
- [x] SPW-04: 테스트·컴파일·fail-closed 실행 증거와 실제 AWS 미실행을 구분했다.
- [x] SPW-05: 문서를 다시 읽고 PENDING 상태와 다음 증거를 명시했다.
