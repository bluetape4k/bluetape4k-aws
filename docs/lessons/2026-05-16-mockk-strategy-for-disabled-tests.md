# Emulator가 지원하지 않는 테스트를 위한 MockK 전략

**날짜**: 2026-05-16
**이슈**: #106
**브랜치**: feat/issue-106-mockk-tests

## 배경

`bluetape4k-aws`의 테스트 중 두 범주는 LocalStack에서 실행할 수 없다.

1. **SES V2** (`aws-kotlin`) — LocalStack이 SES V2 API surface를 구현하지 않는다.
2. **SNS confirmSubscription** (`aws`, `aws-kotlin`) — Subscription confirmation token은
   SMS 또는 HTTP callback으로 subscriber에게 out-of-band 전달되므로 emulator에서
   주입할 수 없다.

이 테스트에는 이슈 참조가 포함된 `@Disabled`를 적용했지만 해당 API를 감싸는 bluetape4k
extension function의 coverage는 전혀 없었다.

## 결정

MockK contract test로 bluetape4k coroutine wrapper가 하위 AWS Kotlin SDK interface method에
올바르게 위임하는지 검증한다. 실제 emulator는 필요하지 않다.

## 구현

### SES V2 (`SesV2ClientExtensionsMockTest`)

- AWS Kotlin SDK의 `SesV2Client`는 **interface**이므로 `mockk<SesV2Client>()`로 직접
  mock할 수 있다.
- `send`가 `sendEmail`, `sendBulk`가 `sendBulkEmail`에 위임하는지 검증한다.
  `getTemplateOrNull`은 성공 시 `Template?`, exception 발생 시 `null`을 반환하고
  `CancellationException`은 다시 던지는지 테스트한다.
- `getTemplateOrNull`의 CancellationException test는 `runSuspendIO { }`가 아니라
  `kotlinx-coroutines-test`의 `runTest { }`를 사용해야 한다. 그래야 coroutine machinery가
  exception을 전파하기 전에 `assertFailsWith<CancellationException>`가 포착한다.

### SNS confirmSubscription 테스트 (`SnsConfirmSubscriptionMockTest`)

- `SnsClient`도 interface이므로 직접 mock할 수 있다.
- SDK의 DSL `client.confirmSubscription { ... }`은 `suspend inline`이므로 호출 지점에서
  `client.confirmSubscription(request)`로 inline된다. MockK는 interface method를
  가로챈다.
- Exception propagation test에 `coVerify(exactly = 1)`을 추가하지 않는다. 같은 mock class
  instance의 test 사이에서는 PER_METHOD lifecycle을 사용해도 MockK의 global ID counter
  때문에 call record가 누적되어 "2 matching calls found" assertion failure가 발생할 수
  있다. Exception을 포착하고 `thrown.shouldNotBeNull()`을 확인하는 것으로 충분하다.

## 교훈

- SDK model package의 정확한 class name은 `TemplateContent`가 아니라
  `EmailTemplateContent`다.
- 응답 타입(`SendEmailResponse`, `GetEmailTemplateResponse`,
  `ConfirmSubscriptionResponse`)은 `ResponseType { field = value }` DSL builder를 지원한다.
- 정확한 field name을 확신할 수 없는 response object(예: `SendBulkEmailResponse`)에는
  `mockk<T>(relaxed = true)`를 사용한다. 특정 field value를 검증할 때만 구체 DSL
  builder를 사용한다.
- Suspend function의 exception propagation을 검증하는 test는 shared mock ID로 인한
  test 간 누적을 피하도록 `coVerify` call count보다 try/catch와 assertion을 우선한다.
