# Issue #620 Kinesis DryRun 설계 Step 2-R 통합 리뷰

**검토일**: 2026-09-05
**대상**: `docs/superpowers/specs/2026-09-05-issue-620-kinesis-dry-run-design.md`
**기준 SHA**: `f07015b6e9a3e6aceb4f301081b502cb88eb40c3` (`origin/develop`)
**결론**: **PASS — P0=0, P1=0**

이 문서는 구현 전 설계 검토 증거다. production source, Gradle task, emulator, full build,
GitHub CI가 이미 검증됐다고 주장하지 않는다.

## Six-lens 결과

| 관점 | 초기 결과 | 통합한 핵심 계약 | 최종 blocker |
| --- | --- | --- | --- |
| Performance | P0=0, P1=0, P2=1 | operation별 SDK 호출 정확히 1회, list/record/`ByteArray` 복사 금지, 새 retry·buffer·round trip 없음 | P0=0, P1=0 |
| Stability | P0=0, P1=4, P2=2 | exception identity와 cancellation 전파, 소유권이 확인된 stream cleanup, `NonCancellable` 30초 cleanup, 180/120/30/30초 timeout budget | P0=0, P1=0 |
| Security | P0=0, P1=1, P2=4 | loopback/emulator endpoint guard, static fake credentials, 실제 AWS endpoint·ambient credentials 차단, header/body/credential 로그 금지 | P0=0, P1=0 |
| Operator/Ops | P0=0, P1=3, P2=3, P3=1 | settings/CI immutable pin parity, compatibility path selection, backend별 closed-set capability 판정과 sanitized evidence | P0=0, P1=0 |
| Developer/API | P0=0, P1=1, P2=2 | builder-last, hidden compatibility overload, 12개 old descriptor, additive ABI task, stub/production classpath 격리 | P0=0, P1=0 |
| User/caller | P0=0, P1=3, P2=1 | payload 전송 경고, `DryRunOperationException` 의미, `false`/`null` 차이, positional builder migration, 영어·한국어 README parity | P0=0, P1=0 |

초기 합계는 **P0=0, P1=12, P2=13, P3=1**이었다. P1은 모두 설계에 반영하고
focused 재검토를 거쳤다. P2/P3는 다음 두 부류로 처분한다.

- 구현 증거로 확인: request/wire test, cleanup/classifier test, Floci와 필요 시 LocalStack
  순차 실행, ABI fixture, CI path selection, README/KDoc read-back.
- 명시적 범위 밖/N/A: 실제 AWS account·IAM policy·production latency/quota, 새 benchmark,
  중앙 manual과 health endpoint. 이 항목은 이번 PR의 성공 증거로 대체 주장하지 않는다.

## P1 수정과 focused 재검토

| 수정 묶음 | 설계 반영 | 재검토 결과 |
| --- | --- | --- |
| 실패·취소·cleanup | primary failure 보존, cleanup failure suppressed, `NonCancellable` bounded cleanup, 생성 실패 포함 | Stability/Security PASS, P0=0, P1=0 |
| timeout budget | JUnit 180초, 본문 120초, cleanup 30초, 종료 여유 30초 | Stability focused PASS, P0=0, P1=0 |
| stream ownership | run nonce+UUID, `describeStream` absence preflight, collision 시 새 이름, create 전 cleanup 등록, 소유하지 않은 이름 삭제 금지 | Ops/Performance focused PASS, P0=0, P1=0 |
| wire·credential 안전 | JDK loopback, 명시 endpoint/region/static fake credentials, body는 assertion에만 사용, 민감정보 로그 금지 | Security/Ops PASS, P0=0, P1=0 |
| API·binary compatibility | builder-last와 source caveat, 12개 direct/`$default` descriptor closed set, `VerifyAdditiveKinesisAbiTask`, Java stub/legacy consumer | API/Caller focused PASS, P0=0, P1=0 |
| catalog·CI·문서 | settings/workflow pin parity, compatibility filter, module README/KDoc exact scope와 migration/payload 경고 | Ops/Caller PASS, P0=0, P1=0 |

## Gate와 검증

- `SPW-01`: PASS — 독자, 목적, issue, base SHA, API/credential/emulator 경계를 고정했다.
- `SPW-02`: PASS — 범위, 결정, 대안, 오류·rollback, compatibility, acceptance와 DoD를 포함했다.
- `SPW-03`: PASS — 한국어 기술 문체를 사용하고 API·descriptor·명령·URL은 원문 token을 보존했다.
- `SPW-04`: PASS — Issue #620, 현재 source/CI/ABI, SDK `1.8.46`, catalog ref와 requirement-to-test 연결을 대조했다.
- `SPW-05`: PASS — heading, 표, code fence, 링크와 blocker disposition을 read-back했다.
- `git diff --check`: PASS.
- `audit-korean-terms.mjs`: PASS (`findings=0`).
- Bluetape receipt: six-lens 초기 검토와 stability/ops/API focused 재검토가 terminal complete다.

## Step DoD

- [x] 여섯 관점의 초기 독립 검토를 수행했다.
- [x] 초기 P1 12건을 설계 계약에 통합했다.
- [x] timeout, ownership, inline ABI의 후속 P1을 focused 재검토로 닫았다.
- [x] 최신 통합 결과는 `P0=0`, `P1=0`이다.
- [x] 구현되지 않은 검증은 구현 계획과 최종 DoD에 남겼다.

**상태: PASS.** 다음 gate는 구현 계획 작성과 Step 3-R 계획 검토다.
