# #659 AWS endpoint 정책은 자격 증명 조회 전에 거부한다

## 배경

[Issue #659](https://github.com/bluetape4k/bluetape4k-aws/issues/659)에서
`aws-ktor`의 AWS Kotlin SDK endpoint 정책을 정리했다. 승인 계획은 안전하지 않은
endpoint를 `CredentialsProvider.resolve()` 전에 거부하도록 요구했다.

Smithy Kotlin 1.7.9의 실행 순서는 identity와 credentials 해석이 endpoint 해석보다
앞선다. 따라서 SDK가 만든 `EndpointProvider`의 결과만 검사하면 자격 증명 provider가
이미 호출된 뒤일 수 있다. [`SdkOperationExecution.kt`](https://github.com/smithy-lang/smithy-kotlin/blob/6106ef2e27a11e3c4df40058b98599c31924a4ec/runtime/protocol/http-client/common/src/aws/smithy/kotlin/runtime/http/operation/SdkOperationExecution.kt#L294-L317)
의 순서가 이 경계를 확인한다.

## 처음 놓친 경계

초기 설계는 provider가 반환한 URL을 요청 단계에서 검사하면 credential lookup 전에도
요청을 거부할 수 있다고 보았다. 그러나 `readBeforeExecution`은 provider가 반환한 최종
URL을 받지 않고, 실제 SDK 순서도 credentials 해석 뒤에 endpoint를 해석한다. 첫
아키텍처 검토에서 이 차이가 명세 불일치로 확인됐고, 사용자는 자격 증명 조회 전 거부를
선택했다.

## 결정

- 고정 `endpointUrl`은 `readBeforeExecution`에서 검증해 잘못된 설정을 자격 증명 조회
  전에 거부한다.
- 생성 service client의 customizer가 지정한 `endpointProvider`와 DynamoDB의
  `endpointDiscoverer`는 최종 URL을 사전에 증명할 수 없으므로 구성 단계에서 거부한다.
- customizer 적용 뒤에는 기본 provider를 검사 가능한 provider로 감싸고, 해석된 endpoint와
  서명 직전의 최종 request URL을 다시 검증한다.
- 주입된 client는 plugin interceptor를 거치지 않으므로 endpoint 검증 책임을 호출자에게
  둔다.

## 결과

명시적 provider나 discoverer를 쓰는 구성이 설정 단계에서 실패한다. 이 제약은 동적
endpoint 사용을 허용하지 않는 대신, 해당 거부 경로가 credentials를 조회하지 않는다는
계약을 보장한다. [설계 명세](../superpowers/specs/2026-10-20-issue-659-aws-sdk-family-boundaries-design.md)
와 [실행 계획](../superpowers/plans/2026-10-20-issue-659-aws-sdk-family-boundaries.md)에
이 정책과 승인 근거를 기록했다.

## 검증

- Endpoint wiring 회귀 테스트 30개가 통과했고, 거부 경로에서
  `CredentialsProvider.resolve()` 호출은 0회였다.
- `./gradlew :bluetape4k-aws-ktor:test --no-daemon`: 47개 suite, 360개 테스트 통과;
  실패·오류·skip 0개.
- 독립 코드 리뷰는 P0/P1/P2 0건으로 `COMMENT` 판정했다. `lsp_diagnostics`를 사용할 수
  없어서 verdict 형식이 `COMMENT`로 남았다. 독립 아키텍처 검토는 `CLEAR`, P0/P1/P2
  0건이다.
- Detekt는 Kotlin 2.4.20과 Detekt 번들 Kotlin 2.4.10의 불일치로 완료되지 않았다.
  사용자가 이 실패를 무시하도록 지시했으며, 통과로 기록하지 않는다. PR exact-head CI는
  아직 확인하지 않았다.

## 향후 지침

AWS SDK의 확장 provider를 검증할 때는 호출 순서를 먼저 추적한다. 정책이 자격 증명
조회 전 거부를 요구하면, 해석 뒤에만 알 수 있는 endpoint를 operation interceptor에서
검사하는 방식으로는 그 계약을 만족하지 못한다. 사전 검증할 수 없는 사용자 provider는
구성 단계에서 거부하고, 회귀 테스트에서 잘못된 설정마다 credentials resolve 횟수가
0인지 확인한다. 기본 provider의 해석 결과와 실제 서명 직전 request URL 검사도 유지한다.
