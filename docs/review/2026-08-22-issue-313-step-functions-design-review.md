# Issue #313 Step Functions 실행 helper 설계 통합 검토

## 검토 범위

- 대상: `docs/superpowers/specs/2026-08-22-issue-313-step-functions-design.md`
- 기준 branch/base: `feat/issue-313-step-functions`, `develop` `c9350bc1ae14cd72056fb358d8f3a427467848f9`
- 대상 문서 SHA-256: `627e5c6f73f2a8ebbfbf6109b28c5558855e47ae635e24e3c266cfee7aa2618c`
- 대상 문서 크기: 706행
- 방식: 성능·안정성·보안·운영·개발자/API·사용자/호출자의 독립 read-only 검토와 main integration
- 변경 경계: 설계와 검토 문서만 작성했다. production/test 구현, commit, push, PR, merge, 실제 AWS 호출은 수행하지 않았다.

## 관점별 최종 판정

| 관점 | P0 | P1 | P2 | P3 | 판정 | 최종 확인 |
|---|---:|---:|---:|---:|---|---|
| 성능 | 0 | 0 | 0 | 0 | PASS | `ShutdownQueue` 등록과 short-lived 미등록 client를 분리하고, aggregate quota·fan-out·backpressure·future cancellation을 고정했다. |
| 안정성 | 0 | 0 | 0 | 0 | PASS | unknown status 원문, cancellation, callback 후 source pinning, 전용 emulator fixture와 rollback 경계를 고정했다. |
| 보안 | 0 | 0 | 0 | 0 | PASS | IAM resource/action 분리, KMS least-privilege, payload redaction, digest/HMAC, emulator 비보안증명 경계를 고정했다. |
| 운영 | 0 | 0 | 1 | 0 | PASS | quota·deadline·관측성·Floci/LocalStack 증거 계약은 닫혔다. 실제 AWS IAM/KMS는 별도 증거 전까지 의도적으로 `UNVERIFIED`다. |
| 개발자/API | 0 | 0 | 0 | 0 | PASS | lifecycle exact signature, HTTP client 소유권, `ExecutionRedriveFilter`, SDK member와 충돌하지 않는 list API를 고정했다. |
| 사용자/호출자 | 0 | 0 | 0 | 0 | PASS | compile/runtime SDK, Standard/Express/Map Run 경계, timeout·close 예제와 raw SDK escape hatch를 명확히 했다. |

## Main integration

### 최종 결정

설계 게이트는 **PASS**다. 전 관점의 P0/P1은 0건이고 구현을 차단하는 finding은 없다. Issue #313의 “실행 이력 polling”은 literal `GetExecutionHistory` event API가 아니라 `DescribeExecutionResponse` 상태 응답 시퀀스로 명시적으로 해석했으며, state machine 작성·배포는 비목표로 유지했다.

### 통합 과정에서 수리한 계약

1. Java blocking `SfnClient` Flow를 제거하고 `SfnAsyncClient` Flow와 Kotlin native suspend client Flow로 분리했다.
2. Java nullable/unknown status와 Kotlin `SdkUnknown(value)`를 fail-closed 처리하고 future status 원문을 진단에 보존했다.
3. `ExecutionRedriveFilter`, `PENDING_REDRIVE`, Map Run source와 callback 후 invariant를 SDK별 타입·제약에 맞췄다.
4. `listExecutionsByStateMachine`과 `listExecutionsByMapRun`으로 이름을 분리해 SDK member 우선 해석을 피하고 callback source-switch를 차단했다.
5. Java/Kotlin lifecycle public signature, explicit parameter 이후 builder override, service/HTTP client 소유권과 close 동작을 고정했다.
6. application-scoped `ShutdownQueue` client와 미등록 short-lived `withSfn*Client`를 분리해 강한 참조 누적 위험을 제거했다.
7. Start/Stop/Describe/List IAM·KMS, quota, safe identity, request ID, timeout/throttle runbook을 구체화했다.
8. Floci 정적 skip의 exact `live integration unverified` 증거와 Sfn 전용 LocalStack fixture를 설계해 unrelated test 결합을 피했다.
9. public SDK type이 노출되는 compileOnly 계약에 맞춰 consumer가 service SDK를 compile/runtime classpath에 직접 추가하도록 명확히 했다.

## 구현 단계의 증거 의무

- request builder와 callback 후 local/source-specific invariant의 RED→GREEN 테스트
- Java sync/async/coroutine와 Kotlin native suspend API의 request/raw response parity
- cold Flow의 즉시 첫 조회, terminal 종료, backpressure, timeout/cancellation, 자동 `StopExecution` 부재
- application-scoped/short-lived client와 외부 HTTP client의 등록·종료·미종료 소유권
- Java/Kotlin consumer compile fixture와 service SDK compileOnly publication metadata
- Floci skipped XML과 exact `live integration unverified` 문구, Sfn 전용 LocalStack 순차 smoke 결과
- targeted tests, 양 모듈 tests, `detekt`, `git diff --check`
- 실제 AWS IAM/KMS는 별도 credential-gated 증거가 없으면 계속 `UNVERIFIED`

## 작성 품질 게이트

| Gate | 상태 | 근거 |
|---|---|---|
| SPW-01 | PASS | live Issue #313, Epic #501, 범위와 비목표를 추적했다. |
| SPW-02 | PASS | 로컬 Scheduler/Kinesis/lifecycle 패턴과 AWS 공식 API·quota·IAM 문서를 연결했다. |
| SPW-03 | PASS | polling response type, 자동 stop, domain wrapper, list API와 lifecycle 대안을 비교했다. |
| SPW-04 | PASS | API·성능·안정성·보안·운영·사용자 실패 모드와 검증 경계를 기록했다. |
| SPW-05 | PASS | 한국어 용어 감사와 whitespace 검사 후 설계 SHA를 고정했다. |

## 다음 게이트

구현 계획은 이 설계와 검토 artifact를 기준으로 concrete 파일 목록, RED→GREEN 순서, consumer/publication/emulator 검증, rollback checkpoint를 작성한다. 계획의 6개 관점 검토와 사용자 승인을 받은 뒤에만 production/test 구현을 시작한다.
