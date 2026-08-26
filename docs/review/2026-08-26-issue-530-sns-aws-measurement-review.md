# Issue #530 SNS 실제 AWS 측정 준비 review

## SPW-01 — 범위와 검토 근거

- **범위:** 새 preflight, AWS 실행 wrapper, redaction checker, opt-in Kotlin measurement test와 관련 문서
- **검토 기준:** Issue #530 본문, 기존 #529 Floci 측정 경로, `SnsBatchExecutor.kt`의 await·bounded worker·cleanup 계약, parser의 36셀 guard, `issue-530-floci-20260826` 결과
- **검토 방식:** 소스·스크립트 read-only 대조와 로컬 테스트/컴파일 결과 확인
- **제외:** 실제 AWS 계정 호출, hosted CI, PR review, merge, release baseline

## 판정

**PENDING — 로컬 준비는 통과했지만 실제 AWS 증거가 없어 Issue #530 완료나 production performance baseline을 판정할 수 없다.**

FlociServer 보조 측정은 `issue-530-floci-20260826`에서 36행과 parser/redaction PASS로
확인했지만, 이는 실제 AWS publisher·비용·quota·heap·retention 증거가 아니다.

## Findings

### P1 — 실제 AWS 승인과 원시 결과 부재

- **근거:** `docs/superpowers/plans/2026-08-26-issue-530-sns-aws-measurement-plan.md`의 승인 게이트와 Issue #530 본문
- **영향:** p50/p95/p99, throughput, in-flight cleanup, 비용·quota 영향은 아직 검증되지 않았다.
- **처분:** `run_sns_batch_aws_measurement.sh`를 추가했지만 자격증명·계정·비용 승인이 없는 현재는 성공 경로를 실행하지 않는다.
- **상태:** PENDING, 승인과 새 run-id가 필요하다.

### P1 — backend heap profile과 장기 retention 미확인

- **근거:** Kotlin test의 `JfrCapture`, `writeAllocationSummary`, `writeRetentionArtifact`
- **영향:** `MemoryPoolMXBean` 값만으로 전체 heap을 설명할 수 없고, JFR은 HPROF가 아니다.
- **처분:** allocation/old-object/GC 이벤트와 `jcmd GC.class_histogram`을 별도 파일로 보존하고, unavailable이면 capability에 남기도록 했다.
- **상태:** 로컬 경로 PASS, 실제 profile·retention 결과 PENDING.

### P2 — mixed/protocol/caller cancellation의 AWS 재현성

- **근거:** `capability.json` 계약과 기존 `SnsBatchExecutorTest`
- **영향:** AWS 서비스가 malformed response나 원하는 partial failure를 결정적으로 만들지 못할 수 있다.
- **처분:** 측정하지 못한 경계를 성공으로 세지 않고 `not_deterministically_reproducible`로 기록한다. 결정론적 executor 회귀는 기존 단위 테스트 근거를 사용한다.
- **상태:** 계약 PASS, 실제 backend capability 결과 PENDING.

### P2 — 민감값 redaction 경계

- **근거:** `check_sns_measurement_redaction.py`와 preflight의 profile/env credential 차단
- **영향:** AWS SDK 오류·debug 로그가 ARN이나 payload를 남기면 측정 결과를 공유할 수 없다.
- **처분:** 텍스트 파일을 마지막에 검사하고 JFR 바이너리는 별도 metadata 명령으로만 검증한다. 실패 시 wrapper는 exit 1이다.
- **상태:** 안전 fixture·위험 fixture·JFR skip 회귀 테스트 PASS.

## 검증 증거

- Floci 보조 측정: `bluetape4k-testcontainers` `FlociServer.Launcher.floci`, 36행 PASS
- Floci 결과 JSON parser와 redaction checker: PASS
- Python 단위 테스트: 8건 PASS
- shell syntax: `bash -n` PASS
- Kotlin: `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --max-workers=1 --no-configuration-cache` PASS
- no-approval wrapper: exit 2, `aws sts` 호출 전 중단
- human review: N/A — 1인 개발자 요청이며 PR·외부 리뷰 단계가 열리지 않았다.

## 후속 조건

실제 실행 전에 승인된 AWS profile/account/region/quota/cost/retention을 운영 기록으로 고정한다. 실행 후 `throughput.json`, `latency.json`, parser `summary.json`, `heap-profile.jfr`, `allocation-summary.json`, `retention.json`, `capability.json`, redaction PASS를 같은 run-id로 연결하고, 그 결과를 다시 review한다.

## Writer DoD

- [x] SPW-01: 범위·근거·제외 영역을 명시했다.
- [x] SPW-02: severity, 근거, 영향, 처분, 상태, 검증 증거와 후속 조건을 포함했다.
- [x] SPW-03: 사실과 미확인 상태를 분리한 한국어 기술 문체를 사용했다.
- [x] SPW-04: 코드 위치와 명령 결과를 대조하고 PENDING을 유지했다.
- [x] SPW-05: 완성본 read-back 후 verdict를 PENDING으로 기록했다.
