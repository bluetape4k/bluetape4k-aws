# Issue #10 Ktor SQS 소비자

## 배경

`aws-ktor`에는 AWS SQS를 transitive runtime 의존성으로 만들지 않으면서 Ktor 수명
주기에 맞는 server-side SQS consumer/publisher가 필요했다.

## 결정

주입한 `SqsAsyncClient`를 사용하고 main의 `aws2.sqs`를 `compileOnly`로 유지한다.
Ktor `SqsConsumer` plugin 뒤에서 수명 주기를 테스트할 수 있는 핵심으로
`SqsConsumerRuntime`을 제공한다. `ApplicationStarted`에서 시작하고
`ApplicationStopping`에서 처리 중인 작업을 비운다.

## 결과

Coroutine poller, typed handler 변환, 게시, 수신 오류 backoff, 실행 중 handler의
backpressure, 선택적 visibility heartbeat, graceful shutdown, 실패 visibility를
구현했다. 또한 최선형 수동 DLQ forwarding을 문서화했다.

## 검증

- `./gradlew :aws-ktor:compileKotlin`
- `./gradlew :aws-ktor:compileTestKotlin`
- `./gradlew :aws-ktor:test --tests 'io.bluetape4k.aws.ktor.sqs.*'` - 14개 통과
- `./gradlew :aws-ktor:test` - 33개 통과

## 향후 지침

실패한 message를 애플리케이션 코드로 보강해야 하는 경우가 아니라면 native SQS
redrive policy를 우선한다. 향후 multi-queue 지원은 instance당 handler 하나라는 계약을
바꾸지 말고 `SqsConsumerRuntime` 위의 registry형 계층으로 유지한다. Consumer 테스트의
suspend polling에는 Awaitility와 `untilSuspending {}`를 사용하고, raw `delay`를 동기화
primitive로 사용하지 않는다.

PR 이후 review gate metric은 `docs/lessons/2026-05-13-pr-review-gate-metrics.md`를
참조한다. 검토 8라운드에서 raw P1 발견 사항 11건, 고유 P1 결함 10건을 찾았고 병합
전에 새 회귀 테스트 4개를 추가했다.
