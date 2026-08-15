# SNS 배치·비동기 퍼블리싱 설계 통합 리뷰

## 범위와 기준

- 대상 이슈: [#456](https://github.com/bluetape4k/bluetape4k-aws/issues/456)
- Epic: [#499](https://github.com/bluetape4k/bluetape4k-aws/issues/499)
- 검토 대상: `docs/superpowers/specs/2026-08-15-sns-batch-async-publishing-design.md`
- 기준 커밋: `bd97ef16357a5cea93c10c60916d9bd54138409f`
- 검토 방식: Type-A 설계 게이트의 독립 관점 6개 최종 lane을 동일한 최신 명세에 대해 read-only로 실행
- 외부 근거: AWS SNS `PublishBatch` API와 Spring Cloud AWS SNS batch/async 문서
- 후속 이슈: [#514](https://github.com/bluetape4k/bluetape4k-aws/issues/514), [#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)

## 최종 lane 판정

| 관점 | lane | 판정 | 핵심 확인 |
|---|---|---|---|
| 보안 | `spec-security-final2` | PASS | 안전한 transport wrapper, `failureType` allowlist, 진단 redaction, low-level raw SDK 예외의 caller 경계 |
| API·호환성 | `spec-api-final3` | PASS | `completedEntryIds`의 terminal-response 의미, mixed-success, `Serializable` 6개 모델, ABI·모듈 경계 |
| 사용자·문서 | `spec-user-final4` | PASS | sibling partial-send에서 selective retry 금지, FIFO/idempotency, prefix fallback, 양국 README parity |
| 성능 | `spec-performance-final2` | PASS | lazy iterator, 고정 worker 수, `10 * maxInFlightBatches` resident/task 상한, 무제한 queue 금지 |
| 운영 | `spec-ops-final2` | PASS | client 수명·close 책임, best-effort 취소, rollback/보상 부재, emulator와 후속 이슈 경계 |
| 안정성·동시성 | `spec-stability-final2` | PASS | structured cancellation, underlying future 취소, permit 정리, protocol/partial failure 보호 |

## 심각도 요약

- P0: 0
- P1: 0
- P2: 구현 단계에서 확인할 후속 검증만 남음
- P3: 0

현재 명세는 구현 착수 차단 이슈가 없다. 다만 설계 통과가 구현·테스트 통과를 의미하지는 않는다.

## 후속 검증과 처분

1. 입력 경계 행렬(`N=0, 1, 10, 11, 20, 21, 100`)과 정확한 `ceil(N/10)` SDK 호출 수는 구현 테스트에서 RED/GREEN으로 증명한다.
2. mixed-success 선행 chunk와 sibling transport failure의 수동 reconciliation 절차를 `README.md`와 `README.ko.md`에 동일 anchor·순서로 구체화한다. terminal response, canceled sibling, unknown/in-flight entry, rollback 부재, dedup/idempotency, 수동 재처리 판단을 포함한다.
3. 실제 구현에서 low-level future 취소, permit 반환, resident 중간 상태 상한, redaction, ABI fixture, Floci capability를 검증한다. backend capability가 불명확하면 결정성 mock을 필수 증거로 유지한다.
4. 외부 publisher latency/cleanup telemetry 및 실제 heap·throughput 측정은 이번 범위의 수용 기준이 아니다. 측정 환경·반복·기준값·목표값·중단 조건은 [#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)에서 별도로 수행한다.
5. Spring Cloud AWS식 공개 `BatchExecutionStrategy`·converter SPI, retry 정책, emulator capability 확장은 [#514](https://github.com/bluetape4k/bluetape4k-aws/issues/514)로 분리했다. 현재 API·의존성 범위를 불필요하게 넓히지 않는다.

## Writer·Kotlin 패턴 점검

- SPW-01: 구현자·리뷰어를 대상으로 문제, 책임 경계, API, 실패 모드, 테스트, 문서, 후속 이슈를 모두 연결했다.
- SPW-02: AWS/Spring Cloud AWS 공식 링크와 저장소 파일 근거를 명시했다.
- SPW-03: 본문은 한국어로 작성하고 API·명령·식별자·URL은 원문 토큰을 보존했다.
- SPW-04: 이전 리뷰의 partial-send, cancellation, rollback, bounded worker 지적이 명세와 후속 검증 항목에 반영됐다.
- SPW-05: Markdown 원문을 재독해했고 `git diff --check` 및 placeholder scan을 통과했다.
- Kotlin 설계 점검: nullable/불변 모델, 명시적 suspend 경계, structured concurrency, client lifecycle, 예외·진단 경계를 구현 계획의 필수 검증으로 고정했다. 구현 코드가 아직 없어 unsafe construct 판정은 적용 대상이 아니다.

## 게이트 결과

설계 통합 리뷰는 **PASS (P0=0, P1=0)** 이다. 사용자가 2026-08-15 이 명세를 승인했으므로 다음 단계는 구현 계획 리뷰와 계획 승인을 받는 것이다. 계획 승인 전에는 production code를 변경하지 않는다.

1인 개발자 저장소이므로 human review gate는 N/A로 처리하지만, 사용자 설계 승인·계획 승인·CI·exact-head merge 승인은 별도 게이트로 유지한다.
