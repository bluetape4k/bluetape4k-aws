# Issue #530 SNS Floci 최종 측정 review

## SPW-01 — 범위와 검토 근거

- **범위:** 기존 FlociServer 측정 경로, redaction/parser 검증과 수용 범위 문서
- **검토 기준:** Issue #530 본문, `bluetape4k-testcontainers` `FlociServer`, `SnsBatchExecutor.kt`의 await·bounded worker·cleanup 계약, parser의 36셀 guard, `issue-530-floci-20260826` 결과
- **검토 방식:** 소스·스크립트 read-only 대조와 로컬 테스트/컴파일 결과 확인
- **제외:** 실제 AWS 계정 호출, hosted CI, PR review, merge, release baseline

## 판정

**PASS (Floci-only scope) — 사용자가 실제 AWS 계정 없이 Floci 결과를 최종 수용했으며, 36행
측정·parser·redaction 증거가 수렴했다. 실제 AWS 운영 baseline은 범위에서 제외한다.**

FlociServer 측정은 `issue-530-floci-20260826`에서 36행과 parser/redaction PASS로
확인했다. 이는 실제 AWS publisher·비용·quota·heap·retention 증거가 아니며, 해당 비교는
별도 후속 이슈의 범위다.

## Findings

### N/A — 실제 AWS 승인과 원시 결과

- **근거:** `docs/superpowers/plans/2026-08-26-issue-530-sns-aws-measurement-plan.md`의 승인 게이트와 Issue #530 본문
- **영향:** 실제 AWS 운영 성능과 비용·quota 영향은 검증하지 않는다.
- **처분:** 사용자가 실제 AWS 계정 없이 Floci 수용을 결정했으며, AWS 비교가 필요하면 별도 이슈로 분리한다.
- **상태:** N/A — 현재 이슈의 완료 조건에서 제외한다.

### P1 — backend heap profile과 장기 retention 미확인

- **근거:** Kotlin test의 `JfrCapture`, `writeAllocationSummary`, `writeRetentionArtifact`
- **영향:** `MemoryPoolMXBean` 값만으로 전체 heap을 설명할 수 없고, JFR은 HPROF가 아니다.
- **처분:** allocation/old-object/GC 이벤트와 `jcmd GC.class_histogram`을 별도 파일로 보존하고, unavailable이면 capability에 남기도록 했다.
- **상태:** Floci 로컬 경로 PASS, 실제 backend profile·retention은 별도 이슈 범위다.

### P2 — mixed/protocol/caller cancellation의 AWS 재현성

- **근거:** `capability.json` 계약과 기존 `SnsBatchExecutorTest`
- **영향:** AWS 서비스가 malformed response나 원하는 partial failure를 결정적으로 만들지 못할 수 있다.
- **처분:** 측정하지 못한 경계를 성공으로 세지 않고 `not_deterministically_reproducible`로 기록한다. 결정론적 executor 회귀는 기존 단위 테스트 근거를 사용한다.
- **상태:** Floci 계약 PASS, 실제 AWS backend capability는 별도 이슈 범위다.

### P2 — 민감값 redaction 경계

- **근거:** `check_sns_measurement_redaction.py`와 preflight의 profile/env credential 차단
- **영향:** AWS SDK 오류·debug 로그가 ARN이나 payload를 남기면 측정 결과를 공유할 수 없다.
- **처분:** 텍스트 파일을 마지막에 검사하고 JFR 바이너리는 별도 metadata 명령으로만 검증한다. 실패 시 wrapper는 exit 1이다.
- **상태:** 안전 fixture·위험 fixture·JFR skip 회귀 테스트와 Floci 결과 redaction PASS.

## 검증 증거

- Floci 보조 측정: `bluetape4k-testcontainers` `FlociServer.Launcher.floci`, 36행 PASS
- Floci 결과 JSON parser와 redaction checker: PASS
- Python 단위 테스트: 8건 PASS
- shell syntax: `bash -n` PASS
- Kotlin: `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --max-workers=1 --no-configuration-cache` PASS
- no-approval wrapper: exit 2, `aws sts` 호출 전 중단
- human review: N/A — 1인 개발자 요청이며 PR·외부 리뷰 단계가 열리지 않았다.

## 후속 조건

실제 AWS 운영 비교가 필요해지면 승인된 profile/account/region/quota/cost/retention을 별도
이슈에서 고정한다. 현재 이슈는 `throughput.json`, `latency.json`, parser `summary.json`과
redaction PASS가 연결된 Floci 결과로 종료 가능한 수용 범위다.

## Writer DoD

- [x] SPW-01: 범위·근거·제외 영역을 명시했다.
- [x] SPW-02: severity, 근거, 영향, 처분, 상태, 검증 증거와 후속 조건을 포함했다.
- [x] SPW-03: 사실과 미확인 상태를 분리한 한국어 기술 문체를 사용했다.
- [x] SPW-04: 코드 위치와 명령 결과를 대조하고 Floci-only PASS와 실제 AWS 제외를 분리했다.
- [x] SPW-05: 완성본 read-back 후 Floci-only verdict를 PASS로 기록했다.
