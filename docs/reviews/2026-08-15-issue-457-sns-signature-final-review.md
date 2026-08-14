# SNS HTTP 메시지 서명 검증 최종 code review

## 검토 범위

- 기준: origin/develop 대비 feat/issue-457-sns-signature diff
- 구현: SnsHttpMessageVerifier, SnsHttpMessageVerificationAutoConfiguration, SnsProperties.Verification
- 검증 범위: Gradle dependency/catalog, Kotlin source/test, Spring auto-configuration resource, 영어·한국어 README
- 운영 원칙: 1인 개발자 repository이므로 human review gate는 N/A이며 CI와 exact-head gate는 필수

## 관점별 판정

| 관점 | 확인 내용 | 판정 |
| --- | --- | --- |
| 성능 | parser와 expected TopicArn 비교가 SDK manager 호출보다 앞선다. manager 인증서 cache·네트워크는 SDK 경계에 위임하고 bean 생성 시 검증 호출은 없다. | PASS; timeout·cache hit/miss throughput은 P2 후속 |
| 안정성 | SnsMessageManager 예외를 숨기지 않고, verifier close는 AtomicBoolean으로 한 번만 manager에 위임한다. Spring bean destroyMethod와 classpath/property backoff가 테스트된다. | PASS |
| 보안 | parser가 payload/header/SigningCertURL을 먼저 검증하고 expected TopicArn mismatch는 manager 호출 전에 거부한다. SDK 검증 실패는 동일 cause로 fail-closed 전파하며 payload를 로그에 남기지 않는다. | PASS |
| 운영 | compileOnly runtime dependency, 기본 enabled, verification.enabled=false opt-out 위험, Floci 서명 부재를 README 양쪽에 기록했다. | PASS |
| 개발자/API | 기존 SnsHttpMessageParser API와 wire model은 유지하고 별도 verifier의 optional header/topic, region factory, AutoCloseable 계약을 추가했다. resolved manager version은 중앙 BOM에서 선택된다. | PASS |
| 사용자/호출자 | README 예제가 parser 내부 선행을 포함하는 verifier를 handler보다 먼저 호출하며, 검증 성공 결과만 notification/confirmation에 사용한다. | PASS |
| 통합/회귀 | 기존 SnsAutoConfiguration와 parser suite, global/SNS/verification/classpath/custom bean 조건을 함께 실행했다. | PASS |

## Fresh verification evidence

1. 기존 baseline: SnsHttpMessageParserTest와 SnsAutoConfigurationTest가 BUILD SUCCESSFUL.
2. GNO/live: GNO가 bluetape4k/bluetape4k-aws #457을 반환했고 live issue는 OPEN, assignee debop, milestone 0.6.0, enhancement/aws-spring-boot/sns를 유지했다.
3. TDD RED: production verifier/auto-configuration 타입이 없는 상태에서 compileTestKotlin이 unresolved reference로 실패했다.
4. Targeted GREEN: verifier, auto-configuration, parser, SNS auto-configuration 테스트 32 passing, BUILD SUCCESSFUL.
5. Compile: :bluetape4k-aws-spring-boot:compileKotlin BUILD SUCCESSFUL.
6. Static analysis: :bluetape4k-aws-spring-boot:detekt BUILD SUCCESSFUL.
7. Full module: :bluetape4k-aws-spring-boot:test BUILD SUCCESSFUL in 1m 10s.
8. Dependency provenance: dependencyInsight가 software.amazon.awssdk:sns-message-manager:2.51.3을 중앙 AWS SDK v2 resolution으로 표시했다.
9. Resource/diff: processResources BUILD SUCCESSFUL, AutoConfiguration.imports에 새 entry가 존재하고 git diff --check가 통과했다.

## 보류와 후속 issue

- **P2-1:** SDK certificate fetch의 connect/read timeout과 cleanup telemetry를 실제 네트워크 경계에서 측정하지 않았다.
- **P2-2:** credential-gated AWS signed SNS fixture/smoke와 certificate cache hit/miss heap·throughput을 실행하지 않았다.
- 위 두 항목은 #457 DoD에 포함하지 않으며 PR merge 후 중복 검색을 거쳐 하나의 Korean backlog issue로 등록한다.
- Floci는 서명된 SNS HTTP payload를 생성하지 않으므로 emulator 결과를 signature 검증 증거로 주장하지 않는다.

## 판정

- P0: 0
- P1: 0
- P2: 2 (명시적 후속 issue)
- P3: 0
- Verdict: PASS — PR 생성 전 lesson/PR body/metadata gate로 이동 가능
