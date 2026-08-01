# Issue #201 Step 2-R 명세 검토

Date: 2026-06-08
Spec: `docs/superpowers/specs/2026-06-08-issue-201-ktor-cloudwatch-design.md`
Reference: `bluetape4k-full-feature/references/step-2r-spec-review.md`

## 검토 범위

- GitHub issue #201 인수 조건
- 기존 `aws-java` CloudWatch 및 CloudWatch Logs coroutine 확장
- 기존 `aws-ktor` SQS 및 IMDS lifecycle, ownership, optional dependency 패턴
- issue #194, #197, #199, #200의 교훈
- 초안 명세의 API, lifecycle, 검증, 문서, 인수 검사

## 관점별 검토 결과

| 관점 | P0 | P1 | P2 | P3 | 결과 |
|---|---:|---:|---:|---:|---|
| 개발자 | 0 | 0 | 0 | 0 | API 형태가 기존 coroutine 도우미를 재사용하고 Ktor plugin 패턴을 따른다. |
| 보안 | 0 | 0 | 0 | 0 | 자격 증명 노출, 기본 AWS 호출, 전역 logging appender가 없다. |
| Ops/SRE | 0 | 0 | 1 | 0 | P2: 숨은 호출이나 중복 retry 계층을 피하도록 빈 flush와 retry 소유권을 명확히 했다. 명세에서 해결했다. |
| 사용자/호출자 | 0 | 0 | 0 | 0 | 비활성화, 주입된 소유권, 기본 식별자, opt-in publishing 등 오용 경계가 명시적이다. |

## 7단계 검토 결과

| 단계 | 범위 | P0 | P1 | P2 | P3 | 근거 |
|---|---|---:|---:|---:|---:|---|
| 1 보안 | 안전한 기본값과 호출자 제어 endpoint/credential | 0 | 0 | 0 | 0 | Endpoint override에는 region이 필요하고 publishing/setup은 기본적으로 비활성화된다. |
| 2 Ops/SRE 신뢰성 | startup, shutdown, retry, 빈 flush, cleanup | 0 | 0 | 1 | 0 | P2 해결: retry 위임과 빈 flush no-op을 명시했다. |
| 3 구조 영향 | `AwsKtorCore`, `aws-java`, Ktor plugin 경계 | 0 | 0 | 0 | 0 | 명세는 새 모듈 간 소유권 없이 service customizer를 확장한다. |
| 4 Kotlin/API 품질 | 공개 타입, coroutine 호출, config 검증 | 0 | 0 | 0 | 0 | 이름은 기존 `SqsConsumer`와 `ImdsKtorPlugin` 규칙을 따른다. |
| 5 테스트 가능성/타입/조용한 실패 | 인수 검사와 mock 가능한 작업 | 0 | 0 | 0 | 0 | 주입된 작업과 빈 목록 no-op 동작을 AWS 없이 테스트할 수 있다. |
| 6 성능/안정성 | batching, mutex, shutdown timeout, cancellation | 0 | 0 | 0 | 0 | Batch 제한은 AWS 제약과 일치하고 종료 시간은 제한된다. |
| 7 문서/릴리스/증거 | README parity, KDoc, dependencies | 0 | 0 | 0 | 0 | 명세는 영어/한국어 README 갱신과 선택적 SDK 의존성을 요구한다. |

## 통합 검토 결과

| 심각도 | 개수 | 상태 | 비고 |
|---|---:|---|---|
| P0 | 0 | PASS | 없음. |
| P1 | 0 | PASS | 없음. |
| P2 | 0 | PASS | 게이트를 닫기 전에 Ops/SRE P2 하나를 명세에서 해결했다. |
| P3 | 0 | PASS | 없음. |

## 제외한 항목

- Ktor 전역 logging appender 추가: issue #201이 전역 appender 교체를 명시적으로 제외하므로 거부했다.
- 예약 실행 Micrometer CloudWatch registry 추가: issue #194에서 명시적 snapshot publishing을
  현재 패턴으로 확립했으므로 거부했다.
- plugin 수준 retry loop 추가: AWS SDK retry 정책을 client builder로 구성할 수 있고 중복 retry
  loop는 cancellation과 shutdown을 복잡하게 하므로 거부했다.

## 열린 질문

차단 항목이 없다. 구현은 Step 3 계획으로 진행할 수 있다.

## 게이트 판정

P0 = 0
P1 = 0

Step 2-R을 닫았고 Step 3 계획의 차단이 해제되었다.
