# PR 검토 게이트 지표

## 배경

PR #60은 병합 전에 Codex 검토와 Claude Code CLI 검토를 수행하는 새 PR 이후 검토
게이트를 처음 적용한 bluetape4k-aws PR이다. 게이트를 시작하기 전에 로컬 테스트와
GitHub CI는 이미 통과한 상태였다.

## 지표

| 지표 | 수치 | 설명 |
|---|---:|---|
| 공식 검토 횟수 | 8 | 첫 PR 이후 검토부터 두 검토자 모두 최종 승인할 때까지의 횟수 |
| 검토에서 시작된 수정 반복 | 6 | Codex/Claude의 `REQUEST_CHANGES` 또는 가치가 높은 `COMMENT`로 시작한 반복 |
| 사용자 지시에 따른 테스트 안정화 반복 | 1 | 불안정한 원시 `delay` 대기를 Awaitility / `untilSuspending`으로 교체 |
| P0 지적 | 0 | 데이터 손실/보안에 치명적인 즉시 차단 항목은 P0로 분류되지 않음 |
| 원시 P1 지적 | 11 | 두 검토자가 같은 문제를 찾은 중복 지적 포함 |
| 고유 P1 결함 | 10 | 근본 원인 기준으로 중복 제거 |
| 병합 전 수정한 P2/P3 지적 | 3 | Awaitility hold-window 오용, 종료 중 수명 주기 race, `deleted` 가시성 |
| 검토 후 추가한 새 회귀 테스트 | 4 | Backpressure, drain 중 heartbeat, shutdown timeout/heartbeat cancellation, start-during-stop |
| 최종 SQS 대상 테스트 | 14 | `./gradlew :aws-ktor:test --tests 'io.bluetape4k.aws.ktor.sqs.*'` |
| 최종 모듈 테스트 | 33 | `./gradlew :aws-ktor:test` |

## 발견한 P1 결함

1. Receive loop 실패를 운영자가 충분히 관찰할 수 없었다.
2. Queue name 해석 실패가 재시도 대신 poller를 종료할 수 있었다.
3. 테스트 코드가 잘못된 assertion helper 계열을 사용했다.
4. Ktor stopping hook가 안전하지 않은 blocking 경계를 사용해 IO로 옮겼다.
5. Handler가 성공한 뒤 delete에 실패하면 message가 manual DLQ로 잘못 전달될 수 있었다.
6. 느린 handler에 backpressure가 없어 처리 중 작업이 무제한 증가할 수 있었다.
7. 광범위한 `Throwable` catch가 치명적인 JVM 오류를 숨길 수 있었다.
8. Graceful shutdown drain 중 visibility heartbeat가 중단됐다.
9. `shutdownTimeout`이 협조하지 않는 handler의 실제 상한이 아니었다.
10. Timeout으로 취소된 handler가 `stop()` 반환 후 message를 자동 삭제할 수 있었다.

## 결정

Runtime, 보안, 자동 구성, coroutine, persistence 작업에는 PR 이후 외부 검토 게이트를
필수로 유지한다. `COMMENT`가 비용이 낮고 확신도 높은 정확성 개선을 지적하면 P1이
아니어도 병합 차단 항목으로 취급한다.

## 결과

PR #60은 다음 조건을 모두 충족한 뒤 병합했다.

- Codex 최종 판정: `APPROVE`
- Claude Code CLI 최종 판정: `APPROVE`
- GitHub CI: 성공
- 병합 commit: `631d4278bdf448acf14866691a2f422b38f5a590`

## 모듈별 검토 시리즈

PR #60에서 게이트를 확립한 뒤 모듈별 강화 PR 네 개가 `:aws`, `:aws-kotlin`,
`:aws-spring-boot`, `:aws-ktor`에 같은 원칙을 적용했다.

| 모듈 | PR | 변경 파일 | 검토 횟수 | P0 | P1 | P2 수정/수용 | 로컬 테스트 증거 | CI 증거 |
|---|---:|---:|---:|---:|---:|---:|---|---|
| `:aws` | #64 | 테스트 14개 + 교훈 1개 | 3 | 0 | 0 | 3 | 252개 통과, 2개 pending | `Test / aws` 통과 |
| `:aws-kotlin` | #65 | 테스트 37개 + 교훈 1개 | 3 | 0 | 0 | 4 | 443개 통과, 5개 pending | `Test / aws-kotlin` 통과 |
| `:aws-spring-boot` | #66 | 테스트 8개 + 교훈 1개 | 2 | 0 | 0 | 3 | 68개 통과 | `Test / aws-spring-boot` 통과 |
| `:aws-ktor` | #67 | 테스트 5개 + 교훈 1개 | 2 | 0 | 0 | 2 | 33개 통과 | `Test / aws-ktor` 통과 |

시리즈 합계:

- 모듈 로컬 테스트, GitHub CI, 외부 검토 후 PR 네 개를 병합했다.
- 테스트 파일 64개와 교훈 파일 4개를 변경했다.
- PR 게이트 전후에 로컬/조언자 검토를 10회 수행했다.
- P0 지적: 0개
- P1 지적: 0개
- 병합 전 수정하거나 명시적으로 수용했다고 기록한 P2 지적: 12개
- 개별 모듈 실행에서 로컬 증거로 통과한 테스트 796개와 pending 테스트 7개를 확인했다.
- 영향받은 모든 모듈 범위의 GitHub CI가 통과했다.

반복해서 확인한 규칙:

- 변경한 테스트에서는 `bluetape4k-assertions`를 사용한다. 검토 전에 `kotlin.test.*`,
  AssertJ, Kluent, JUnit assertion import를 scan한다.
- 비동기 consumer 테스트에서 고정 sleep보다 Awaitility 또는 `untilSuspending {}`을
  우선한다.
- LocalStack, AWS SDK, Ktor, 기타 blocking I/O 경계에는 `runSuspendIO`를 사용하고,
  가상 시간 또는 순수 coroutine 수명 주기 테스트에는 `runTest`를 유지한다.
- Framework callback이 동기식이면 blocking bridge를 숨기지 말고
  `runBlocking(Dispatchers.IO)`을 유지하는 이유를 문서화한다.

## 향후 지침

이 게이트는 병합 후가 아니라 병합 전에 실행한다. Coroutine 수명 주기, visibility,
acknowledgement 결함을 도입 후에 발견하는 비용보다 검토 비용이 낮다. 게이트에서 P0/P1
결함을 찾으면 관련 교훈에 정량 검토 지표를 기록한다.
