# AWS Request builder DSL 구현 lesson

## 결정

`aws-java`의 반복적인 AWS SDK v2 Request 생성을 Kotlin DSL로 보완하되, 필수 정보와
기존 호출 호환성은 `fooRequestOf(...)`에 남겼다. `fooRequest { }`는 builder 필드만
설정하는 단일 진입점으로 제한했고, 서로 다른 SDK `Request` 타입마다 하나의 builder-only
함수만 제공했다.

## 실제 구현 결과

- SSM, Secrets Manager, Step Functions, Lambda, Bedrock Runtime, EventBridge, EventBridge
  Scheduler, S3 Tables의 8개 support 파일에 builder-only API를 추가했다.
- 기존 `RequestOf`의 필수 scalar, callback precedence, final validation을 보존했다.
- Bedrock의 helper-owned 필드와 SSM `PutParameterRequest` specialization을 별도 계약으로
  유지했다.
- secret-bearing raw builder의 평문 보유 가능성을 KDoc과 README에 기록하고
  `AwsSecretValue` helper를 연결했다.
- 한·영 README와 `[미출시]` CHANGELOG에 Issue #665 사용법과 범위를 기록했다.

## 검증에서 배운 점

- RED 심볼 누락을 서비스 slice별로 먼저 고정하면 다음 slice의 컴파일을 막지 않고
  builder-only API를 단계적으로 추가할 수 있다.
- source compilation만으로는 JVM ABI 보장이 되지 않으므로, consumer fixture와 함께
  base/head JAR의 top-level facade `javap -public -s` 비교가 필요하다.
- `compatibilityCheck`는 제품 코드가 아니라 기존 `compileKinesisDryRunLegacyStubs`의
  `getAwsKotlinProductionJar()` null 오류로 중단될 수 있다. 공통 task 결과와 product test
  결과를 분리해 보고해야 한다.
- callback이 필수 값을 무효화할 수 있으므로 helper-owned validation은 SDK `build()` 뒤
  최종 Request에 적용해야 한다. 단, Step Functions의 `{}` normalization rebuild는 의도된
  예외로 남겨야 한다.
- public `inline` 함수가 참조하는 `@PublishedApi internal` 상수·helper는 JVM facade에
  public ABI로 노출될 수 있다. 내부 검증을 재사용할 때도 승인된 additive symbol allowlist와
  `javap` 전체 public diff로 의도하지 않은 ABI 고정을 확인해야 한다.
- AWS SDK builder는 `type` 누락이나 `secretString`·`secretBinary` 동시 설정을 build 단계에서
  막지 않을 수 있다. `RequestOf`의 필수값·상호배타 조건은 callback 적용 뒤 최종 Request에서
  다시 검증하고, 각각의 callback 무효화 회귀 테스트를 둬야 한다.
- `--rerun-tasks` 전체 테스트에서 Testcontainers가 Docker 환경을 일시적으로 찾지 못해
  worker 오류가 연쇄될 수 있다. `colima status`, `docker context show`, `docker info`를
  확인한 뒤 동일 exact HEAD에서 재실행하고, 최초 환경 실패와 최종 제품 테스트 결과를
  분리해 기록한다.
- 복구된 workflow receipt는 `session_id`가 없을 수 있어 외부 mutation-check를 통과하지
  못한다. 원본 receipt를 보존하고, 복구 provenance와 live GitHub read-back을 별도로
  기록해야 한다.
- 이번 recovery run은 통과한 check 결과와 독립 review unavailable을 모두 기록한 뒤
  sequence 17에서 `blocked`로 종료했다. 운영 receipt의 blocked 상태와 코드의 자동 검증
  PASS는 서로 다른 gate로 보고해야 한다.

## 향후 guard

1. 새 Request helper는 먼저 distinct SDK Request 타입과 required-info 경계를 inventory한다.
2. 기존 overload에 default trailing block을 추가하기 전에 Kotlin overload ambiguity를
   compile fixture로 확인한다.
3. public facade 변경은 compatibility task가 완주했는지와 무관하게 base/head `javap`를
   함께 수행한다.
4. 독립 code-review lane이 없으면 P0/P1이 없다고 추정하지 말고 review gate를 PENDING으로
   유지한다.
5. 복구 workflow에서 session-bound mutation이 불가능하면 issue 본문을 임의로 변경하지
   않고 blocker를 evidence와 final report에 남긴다.
