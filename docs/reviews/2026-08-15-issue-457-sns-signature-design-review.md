# SNS HTTP 메시지 서명 검증 설계 review

## 검토 범위와 기준

- 대상: `docs/superpowers/specs/2026-08-15-issue-457-sns-signature-design.md`
- 요구사항: [GitHub issue #457](https://github.com/bluetape4k/bluetape4k-aws/issues/457)
- 저장소/기준 ref: `bluetape4k/bluetape4k-aws@develop`
- 검토 방식: 성능, 안정성, 보안, 운영, 개발자/API, 사용자/호출자 관점과 통합 관점의 독립 점검
- 설계 선택: AWS SDK v2 `SnsMessageManager`에 서명·인증서 검증을 위임하고 기존 parser 결과를 반환

## 관점별 검토

| 관점 | 확인한 계약과 근거 | 판정 |
| --- | --- | --- |
| 성능 | parser를 먼저 실행하고 `expectedTopicArn` 불일치 시 manager 호출을 생략한다. manager의 인증서 cache는 SDK 경계에 두며 payload 상한은 기존 parser가 유지한다. bean 생성 시 외부 호출을 보장하지 않는다. | PASS |
| 안정성 | verifier는 `AutoCloseable`이고 Spring bean은 `destroyMethod = "close"`로 정리한다. SDK 예외를 숨기지 않으며 manager classpath/property가 없으면 auto-config가 back off한다. | PASS |
| 보안 | HTTPS SNS 인증서 URL·region·partition 검증은 parser가, Signature v1/v2·certificate chain·host 검증은 SDK가 담당한다. topic mismatch는 네트워크 전에 거부하고 서명 실패는 fail-closed로 전파한다. | PASS |
| 운영 | `verification.enabled` 기본값은 `true`이며 opt-out의 보안 의미와 compileOnly runtime dependency를 문서화한다. timeout·telemetry와 credential-gated AWS smoke는 이번 PR의 보장 범위에서 제외한다. | PASS (P2 후속) |
| 개발자/API | 원문 JSON, 선택적 type header, 선택적 `expectedTopicArn`, region factory, 명시적 close 계약이 기존 parser API와 분리되어 있다. manager를 직접 노출하지 않고 parser wire model을 재사용한다. | PASS |
| 사용자/호출자 | 후속 HTTP adapter가 parser → verifier → handler 순서를 지켜야 하며 verifier 실패 시 handler/confirm API로 전달하지 않는다. Floci에 실제 SNS 서명이 없으므로 fixture·mock 테스트 경계를 명시한다. | PASS |

## 통합 판정

1. **P0**: 없음. 인증 우회, 기존 API 제거, 무제한 외부 호출, 데이터 손실 경로를 설계에서 확인하지 못했다.
2. **P1**: 없음. parser 선행, topic 조기 거부, SDK 예외 전파, lifecycle close, auto-config backoff와 runtime dependency 경계가 서로 충돌하지 않는다.
3. **P2**: 외부 certificate fetch의 timeout·cleanup telemetry와 실제 AWS 서명 smoke 측정은 구현 후 별도 후속 이슈로 추적해야 한다. 이번 PR의 수용 기준이나 완료 주장에 포함하지 않는다.
4. **P3**: 없음.

## 실행 게이트

- 설계 문서의 writer gate와 사용자 spec review가 PASS다.
- 다음 단계는 exact file/test/command를 담은 implementation plan 작성이다.
- plan은 테스트 RED를 production 구현보다 앞에 두고, SDK resolved version을 `dependencyInsight`로 증명하며, README 영어·한국어 정렬을 포함해야 한다.
- 후속 이슈(외부 호출 timeout·cleanup telemetry, credential-gated AWS smoke)는 PR merge 후 live GitHub 상태를 재확인하고 별도 backlog로 등록한다.

## Review artifact gate

- SPW-R01: PASS — 6개 관점과 통합 판정을 모두 기록했다.
- SPW-R02: PASS — 각 판정이 spec의 API·보안·lifecycle·호환성 계약에 연결된다.
- SPW-R03: PASS — P0/P1 없음과 P2 보류 범위를 분리했다.
- SPW-R04: PASS — 구현 plan의 TDD·dependencyInsight·문서 정렬 요구를 명시했다.
- SPW-R05: PASS — Korean technical register, code token, URL, property key를 보존했다.
