# SNS HTTP 메시지 서명 검증 implementation plan review

## 검토 범위

- 대상 plan: docs/superpowers/plans/2026-08-15-issue-457-sns-signature-plan.md
- 기준 spec: docs/superpowers/specs/2026-08-15-issue-457-sns-signature-design.md
- 요구사항: GitHub issue #457
- 검토 방식: 성능, 안정성, 보안, 운영, 개발자/API, 사용자/호출자와 통합 관점의 Step 3-R 점검

## 관점별 판정

| 관점 | 검토 근거 | 판정 |
| --- | --- | --- |
| 성능 | parser와 expected TopicArn 조기 거부가 manager 호출보다 앞선다. 인증서 cache와 외부 호출은 SDK 경계에 남기고, unit test는 manager mock을 사용한다. timeout·telemetry·실제 throughput 측정은 이번 PR에서 주장하지 않는다. | PASS; P2 후속 |
| 안정성 | SnsHttpMessageVerifier close는 AtomicBoolean으로 한 번만 manager에 위임하고 Spring bean destroyMethod가 이를 호출한다. manager/classpath/property 부재는 auto-config backoff 테스트로 고정한다. | PASS |
| 보안 | 기존 parser가 URL·header·payload 경계를 먼저 검사하고 expected TopicArn mismatch는 certificate fetch 전에 거부한다. SDK 예외는 동일 cause로 fail-closed 전파한다. | PASS |
| 운영 | compileOnly runtime dependency, 기본 enabled, 명시적 opt-out, Floci 서명 미지원과 fixture/mock 한계를 README와 PR DoD에 기록한다. 후속 issue 생성 단계가 plan에 있다. | PASS |
| 개발자/API | parser public API를 바꾸지 않고 verifier를 별도 타입으로 제공한다. region factory, optional header/topic, custom bean backoff, resolved dependencyInsight 명령이 일관된 이름을 사용한다. | PASS |
| 사용자/호출자 | HTTP adapter의 parser → verifier → handler 순서를 문서화하고 검증 실패 payload의 handler 전달을 금지한다. 기존 SNS client/template 테스트를 함께 실행한다. | PASS |
| 통합/순서 | RED 테스트와 dependency scaffolding이 production 구현보다 앞서고, auto-config resource·docs·detekt·PR/merge 단계가 후속 artifact에 의존하지 않는다. | PASS |

## 리스크와 수정 판정

- **P0:** 없음.
- **P1:** 없음. manager interface와 parseMessage(String) return type은 공식 SDK API와 일치하며, plan의 MockK 반환값은 SnsMessage mock으로 대체 가능하다.
- **P2:** certificate fetch timeout·cleanup telemetry, 실제 AWS credential-gated signature smoke, 인증서 cache hit/miss의 heap·throughput 실측은 구현 후 별도 backlog issue로 남긴다. 이 범위는 #457 DoD의 성공 주장에 포함하지 않는다.
- **P3:** 없음.

## TDD·rollback 게이트

1. Task 1의 exact RED command를 production type 추가보다 먼저 실행하고 실패 output을 보존한다.
2. Task 2와 Task 3은 각각 verifier와 auto-config의 최소 GREEN을 확인한 뒤 커밋한다.
3. Task 4 문서/locale diff, Task 5 module verification, Task 7 PR metadata 순으로 진행한다.
4. rollback은 catalog/dependency, production verifier, auto-config/imports, tests, docs를 behavior unit으로 되돌리며 spec/plan/review와 RED 증거는 보존한다.
5. shared bluetape4k-dependencies catalog는 이 repository에서 임의로 수정하지 않고, alias가 pinned ref에 없을 때 owning repository의 별도 issue/PR로 분리한다.

## Review artifact gate

- SPW-R01: PASS — 6개 관점과 통합 순서를 모두 점검했다.
- SPW-R02: PASS — spec 수용 기준과 plan task가 일대일로 연결된다.
- SPW-R03: PASS — P0/P1 없음과 P2 후속 범위를 분리했다.
- SPW-R04: PASS — RED-before-GREEN, exact commands, rollback boundary를 기록했다.
- SPW-R05: PASS — 한국어 설명과 API/property/command token을 보존했다.

**Verdict: PASS (P0=0, P1=0).**
