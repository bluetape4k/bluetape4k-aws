---
name: ktor-sqs-examples
description: aws-ktor-sqs-examples 모듈 구현에서 얻은 교훈(issue #16)
metadata:
  type: project
---

# Ktor SQS 예제 모듈(Issue #16)

## 요약

`aws-ktor`의 `SqsConsumer` plugin으로 SQS messaging을 보여 주는 Ktor 3 애플리케이션
`examples/aws-ktor-sqs-examples`를 추가했다. LocalStack 통합 테스트를 사용하는 HTTP
route로 전송, 수신(consumer), queue 생성, queue attribute 검사를 다룬다.

## 근본 원인 / 배경

출시 전까지 `aws-ktor` SQS 통합에는 Ktor 애플리케이션에 `SqsConsumer`를 연결하고 HTTP
route로 동작을 검증하는 end-to-end 예제가 없었다.

## 주요 결정

### SqsConsumer plugin 연결

```kotlin
install(SqsConsumer) {
    sqsAsyncClient = sqsClient
    this.queueUrl = queueUrl
    coroutines = 2
    maxMessages = 10
    waitTimeSeconds = 1
    visibilityTimeoutSeconds = 30
    onMessage<String> { body -> received.add(body) }
}
```

`call.application.sqsConsumer().send(body, queueUrl)`로 전송한다.

### 테스트 구조

- Test class에 `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`를 적용한다.
- `@BeforeAll` / `@AfterAll`에서 suspend setup/teardown에 `runSuspendIO {}`를 사용한다.
- 각 `@Test`는 `runSuspendIO`로 감싸지 않고 `testApplication {}`을 직접 사용한다.
- 동시성 테스트는 `testApplication {}` 안에서
  `SuspendedJobTester().workers(4).rounds(5).add {...}.run()`을 사용한다.
  `testApplication`이 suspend context를 제공하므로 유효하다.

### Assertion 방식

`shouldBeEqualTo`, `shouldBeTrue`, `shouldBeNull` 등 bluetape4k assertion을 사용한다. 새
테스트에서 일반 `assert()`나 `assertEquals`를 사용하지 않는다.

## 피한 함정

- `testApplication {}`을 `runSuspendIO {}` 안에서 감싸지 않는다. `testApplication`이
  내부에서 `runBlocking`을 호출하므로 중첩하면 deadlock이 발생한다.
- 내부 type인 `SqsConsumerRuntime`/`SqsConsumerRuntimeConfig`를 직접 생성하지 않는다.
  HTTP route로만 동작을 테스트한다.

## 검증

- `@BeforeAll`에서 LocalStack SQS queue를 만들고 `@AfterAll`에서 삭제했다.
- Route: `POST /sqs/messages`, `GET /sqs/queues/attributes`, `POST /sqs/queues/{name}`
- 동시성 테스트에서 worker 4개가 각각 5회 실행했으며 모두 `HttpStatusCode.OK`를 기대했다.

## 향후 지침

- 새 `aws-ktor` SQS 기능을 추가할 때 이 route를 확장하고 대응하는 test case를 추가한다.
- `SqsClientFactory.Async.create(endpointOverride, region, credentialsProvider)` pattern을
  LocalStack용 test client의 표준 생성 방식으로 사용한다.
