# Issue #4 SNS Spring Boot 작업 교훈

## 배경

Issue #4에서는 `aws-spring-boot`에 SNS 지원을 추가했다. 범위는 Spring Boot 4 자동설정, coroutine 기반 작업 API, topic ARN 조회, FIFO publish, SNS-to-SQS fanout 검증이다.

PR: <https://github.com/bluetape4k/bluetape4k-aws/pull/55>

커밋: `0793e10 feat: Spring Boot에서 SNS 발행을 코루틴으로 제공`

PR은 `develop` 대상 draft로 생성했고 `debop`에게 assign했다. 전체 SQS-SNS 애플리케이션 예제는 issue #13 범위로 남겼고, #4 PR에는 명시적으로 포함하지 않았다.

## 결정

새로운 추상화 스타일을 만들지 않고 기존 SQS Spring Boot 패턴을 따른다:

- production classpath에서는 AWS SNS SDK를 `compileOnly`로 유지한다.
- SDK type 조건은 string 기반 `@ConditionalOnClass`로 건다.
- SDK client와 operations bean은 `@ConditionalOnMissingBean`으로 사용자 bean을 우선한다.
- `SnsAsyncClient` 위에 coroutine template을 둔다.
- LocalStack 통합 테스트로 실행 가능한 증거를 남긴다.

신규 Spring Boot library 기능에는 strict design gate가 효과적이었다:

- 구현 전에 spec을 먼저 작성한다.
- spec을 외부 advisor로 리뷰한다.
- spec이 안정된 뒤 implementation plan을 작성한다.
- production code 수정 전에 plan을 리뷰한다.
- 테스트 통과 후 final strict code review를 수행한다.

## 결과

구현에 포함된 항목:

- `SnsAsyncClient`와 `SnsOperations`를 등록하는 `SnsAutoConfiguration`
- `SnsOperations`, `SnsCoroutinesTemplate`, `SnsProperties`, `SnsPublishRequest`
- standard topic 생성, FIFO topic 생성, topic ARN 조회, publish
- 로컬에서 판단 가능한 FIFO 계약 검증
- SNS-to-SQS fanout 통합 검증
- `README.md`, `README.ko.md` 업데이트
- durable spec, plan, lesson 문서

publish public API는 같은 타입의 positional parameter 실수를 줄이기 위해 request object 기반으로 유지했다.

## 검증

- `./gradlew :aws-spring-boot:compileKotlin`
- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.*'` - 16 passing
- `./gradlew :aws-spring-boot:test` - 49 passing
- Claude advisor가 spec, plan, final diff를 리뷰했고 남은 blocker는 없었다.

작업 중 review artifact는 `.omx/artifacts/`에 저장했다:

- `ask-claude-issue-4-sns-spec-review.md`
- `ask-claude-issue-4-sns-plan-review.md`
- `ask-claude-issue-4-sns-code-review.md`
- `ask-claude-issue-4-sns-final-code-review.md`

## 잘 된 점

- 기존 SQS 구현에서 출발해 불필요한 새 추상화를 피했다.
- spec review에서 실제 FIFO semantic 버그를 구현 전에 잡았다. standard topic에는 FIFO 전용 publish field를 허용하면 안 된다.
- plan review에서 구현 위치 누락을 잡았다. 특히 client bean lifecycle, configured-topic 검증 위치, `SnsPublishRequest.init` 검증 위치가 중요했다.
- 실행 가능한 LocalStack fanout 테스트 하나가 README-only 설명보다 훨씬 강한 증거가 됐다.
- 전체 module 테스트 전에 targeted SNS 테스트를 먼저 돌려 실패 원인을 빠르게 분리했다.

## 주의할 점

- Spring Boot property binding은 map key 안의 dot을 특별하게 처리한다. `orders.fifo` 같은 topic name을 key로 쓸 때는 `topics[orders.fifo]` bracket notation을 사용한다.
- LocalStack SNS-to-SQS fanout에는 queue policy 설정이 필요하다. 이후 이 테스트가 실패하면 테스트를 약화하기 전에 queue policy와 subscription 설정을 먼저 확인한다.
- LocalStack 테스트의 `@file:Suppress("DEPRECATION")`은 파일 단위로만 유지하고 production code의 deprecation을 숨기지 않는다.
- issue #13은 분리한다. issue 범위가 바뀌지 않는 한 library feature PR을 full example application PR로 확장하지 않는다.

## 다음 작업 지침

- Spring Boot map key에 dot이 들어가면 property-value 테스트에서 `topics[orders.fifo]` 같은 bracket notation을 사용한다.
- 후속 issue가 example module을 소유한다면 library feature PR과 full application example PR을 분리한다.
- publish-side integration 기능을 추가할 때는 실행 가능한 LocalStack fanout 테스트를 하나 둔다.
- bluetape4k에서 public API가 추가되는 신규 기능은 구현이 단순해 보여도 Spec -> Plan -> Implementation -> Tests -> Code Review 순서를 유지한다.
