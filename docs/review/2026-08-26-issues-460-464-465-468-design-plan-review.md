# #460/#464/#465/#468 설계·계획 통합 검토

> 검토 기준: 승인된 `docs/superpowers/specs/2026-08-26-issues-460-464-465-468-design.md`와 `docs/superpowers/plans/2026-08-26-issues-460-464-465-468-plan.md`
> 검토일: 2026-08-26
> 범위: API, 동시성·수명, 보안·redaction, 성능·자원, 운영, 문서·ABI + 통합

## 근거 ledger

- 로컬: `SqsMessageListenerContainer.kt`, `SqsListenerEndpoint.kt`,
  `S3TransferAutoConfiguration.kt`, `S3TransferTemplate.kt`,
  `DynamoDbAutoConfiguration.kt`, `AbstractCoroutinesDynamoDbRepository.kt`.
- 변경 이력: #453 heartbeat, #454 batch/partial ack, #461 SQS batching, #463
  S3 ResourceLoader.
- 외부: [Spring Cloud AWS SQS reference](https://docs.awspring.io/spring-cloud-aws/docs/3.4.1/reference/html/index.html),
  [S3 CRT auto-configuration API](https://docs.awspring.io/spring-cloud-aws/docs/3.3.1/apidocs/io/awspring/cloud/autoconfigure/s3/S3CrtAsyncClientAutoConfiguration.html).
- 승인: 2026-08-26 사용자 `승인` 메시지.

## 관점별 판정

| 관점 | 확인한 위험 | 처분 | 상태 |
|---|---|---|---|
| API/ABI | 기존 `SqsOperations`, `S3TransferOperations`, repository ABI 변경 위험 | additive type/default method, existing bean back-off와 baseline ABI test를 계획에 고정 | PASS (P0=0, P1=0) |
| 동시성·수명 | SQS over-receive, FIFO group race, stream close, coroutine cancellation | semaphore/mutex, generation drain, temp cleanup, cancellation 재전파 테스트를 순서화 | PASS (P0=0, P1=0) |
| 보안·redaction | payload·URL·credential leakage, CRT custom client 소유권 | redacted diagnostics, classpath condition, user client precedence와 negative test 고정 | PASS (P0=0, P1=0) |
| 성능·자원 | 무제한 buffer/cache, transfer manager/client 중복, publisher leak | threshold spool, bounded TTL cache, manager owner close, sequential emulator 검증 | PASS (P0=0, P1=0) |
| 운영·호환성 | Floci capability gap, queue not found, old properties/defaults | Floci-first + explicit LocalStack fallback, policy enum, default behavior regression | PASS (P0=0, P1=0) |
| 문서·ABI | Korean KDoc/config drift, optional dependency linkage | writer SPW-01~05, exact property examples, compileOnly and diff/ABI checks | PASS (P0=0, P1=0) |
| 통합 | 이슈 간 순서와 다음 component 의존성 | #460 → #464 → #465 → #468 topology/dependency와 issue별 targeted gate | PASS (P0=0, P1=0) |

## SPW gate

- SPW-01: PASS — 독자(라이브러리 사용자/유지보수자), 목적(네 운영 경계),
  로컬·GNO·공식 자료와 승인 메시지를 기록했다.
- SPW-02: PASS — spec은 경계·대안·실패·호환성·수용 기준을, plan은 파일·순서·테스트·롤백을 포함한다.
- SPW-03: PASS — 한국어 기술 문체, 고정 API/property/command token과 영어 URL을 보존했다.
- SPW-04: PASS — local anchor → 설계 선택 → plan task → validation command가 추적된다.
- SPW-05: PASS — Markdown read-back에서 placeholder, 충돌, 누락된 이슈 순서가 없다.

## 통합 verdict

설계와 계획은 승인된 범위 안에서 구현을 시작할 수 있다. 구현 중 public
contract, lifecycle owner, emulator 정책, issue 순서를 바꾸면 해당 artifact를
갱신하고 재승인을 받아야 한다. PR·merge·release gate는 이 작업 범위 밖이다.
