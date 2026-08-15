# SNS 배치·비동기 퍼블리싱 구현 계획 통합 리뷰

> 대상 이슈: #456
> Epic: #499
> 계획: docs/superpowers/plans/2026-08-15-sns-batch-async-publishing-plan.md
> 리뷰일: 2026-08-15 (계획 단계; 구현 후 상태는 최종 리뷰 artifact에 기록)

## 리뷰 범위와 게이트

승인된 설계 명세를 기준으로 구현 계획의 파일 경계, API/ABI, redaction,
coroutine/backpressure, caller retry semantics, emulator/운영 검증, Kotlin
테스트·문서 추적성을 독립적으로 검토했다. 모든 lane은 읽기 전용으로 수행했고
구현 코드, 빌드, 커밋, 외부 상태 변경은 수행하지 않았다.

1인 개발자 저장소이므로 human review gate는 N/A다. 다만 사용자 계획 승인,
TDD RED→GREEN, exact-head CI, merge 승인, local sync와 cleanup은 별도 게이트다.

## 독립 리뷰 결과

| 관점 | 판정 | P0 | P1 | P2 | P3 | 핵심 확인 |
|---|---|---:|---:|---:|---:|---|
| API·ABI·호환성 | PASS | 0 | 0 | 0 | 0 | Java exact declaration, resolved SNS main jar hash/javap, SNS 전용 fixture classpath/task, baseline class hash와 non-recompile URLClassLoader |
| 보안·redaction | PASS | 0 | 0 | 0 | 0 | low-level raw SDK passthrough와 Spring safe-wrapper 분리, failureType allowlist, getMessage/toString/stack/suppressed/CRLF 검증 |
| concurrency·backpressure | PASS | 0 | 0 | 0 | 0 | lazy chunk, fixed worker, Mutex/withPermit 경계, barrier race RED, coroutineScope sibling 취소, resident bound |
| caller·retry·partial-send | PASS | 0 | 0 | 0 | 0 | options=4 fallback 순차 assertion, terminal completedEntryIds, cancellation/no-retry, FIFO·idempotency·manual reconciliation |
| operations·emulator·release | PASS | 0 | 0 | 0 | 0 | 세 모듈 dependencyInsight tee, exact jar assertion, Floci 순차 opt-in, deterministic mock/Colima 원인 보존, CI/handoff gate |
| tests·docs·Kotlin | PASS | 0 | 0 | 0 | 0 | Base58.randomString(16), data class/val/nullable/serialVersionUID, 전용 모델·예외 test command, 한·영 README parity, #514/#515 추적 |

**통합 판정: PASS (P0=0, P1=0, P2=0, P3=0).**

## 계획 자체 검증

- whitespace 검사 통과: plan 전체에 trailing blank 없음.
- placeholder 검사 통과: 미완성 표식 없음.
- 계획 헤더, Goal/Architecture/Tech Stack, checkbox task 형식, RED→GREEN
  순서, exact Gradle 명령, rollback/recovery 경계가 존재한다.
- Base58.randomString(16)은 Java/Kotlin/Spring 테스트 입력 계약에 명시되어
  있다.
- public strategy/converter/retry 확장은 #514, 외부 publisher latency/cleanup
  telemetry와 실제 heap·throughput 측정은 #515로 분리되어 이번 acceptance에
  섞이지 않는다.
- SNS business rollback/보상 트랜잭션 부재와 코드 변경 복구 단위를 분리했다.

## 사용자 승인과 다음 게이트

계획은 독립 리뷰 PASS 후 사용자 승인을 받았고, 명세·계획·review artifact를 먼저
커밋한 뒤 TDD 구현과 모듈 검증까지 완료했다. PR·CI·merge·local sync·cleanup은
별도 최종 게이트로 남아 있다. 다음 순서를 유지한다.

1. 설계 명세, 구현 계획, 설계 review, 본 plan-review를 Lore commit protocol로
   먼저 커밋한다.
2. Task 0 baseline에서 resolved SDK evidence와 legacy fixture class/hash를
   보존한다. API 변경 후 fixture compile task를 다시 실행하지 않는다.
3. 각 task의 RED를 먼저 실행하고, 해당 테스트가 실패한 이유를 기록한 뒤
   production 구현으로 GREEN을 만든다.
4. 통합 검증, PR, exact-head CI, fresh merge approval, merge, local sync,
   proof-gated cleanup을 순서대로 수행한다.

## DoD Status

- [x] 설계 명세 사용자 승인 반영
- [x] 구현 계획 작성
- [x] 여섯 독립 plan-review lane PASS
- [x] plan whitespace/placeholder 자체 검증
- [x] 사용자 구현 계획 승인
- [x] 승인된 명세·계획·review artifact Lore commit
- [x] TDD 구현·검증
- [ ] PR·CI·merge·local sync·cleanup

**상태: PENDING — PR·CI·merge·local sync·cleanup 게이트가 남아 있다.**
