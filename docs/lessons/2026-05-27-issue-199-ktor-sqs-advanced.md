# Issue 199 Ktor SQS 고급 제어

## 배경

Issue #199에서는 Ktor SQS consumer를 초기 coroutine poller에서 운영 환경 강화 API로
확장한다. Typed 변환 실패 policy, 수동 ack/nack, 재시도 visibility 전략, 수명 주기
interceptor, 관찰 hook을 포함한다.

## 결정

기존 `onMessage<T>`와 `deleteOnSuccess = true` 동작을 기본값으로 유지한다. 고급 제어는
opt-in runtime 설정으로 추가해 기존 사용자의 source 및 동작 호환성을 보존한다.

`aws-ktor`에 Micrometer 의존성을 추가하지 않고 가벼운 observer event를 사용한다.
애플리케이션은 관찰 결과를 Micrometer, OpenTelemetry, log 또는 테스트에 연결할 수 있다.

## 결과

이제 SQS runtime은 다음 기능을 지원한다.

- Handler 호출 전 변환 실패를 위한 `SqsConversionFailurePolicy`
- 수동 acknowledgement flow를 위한 `SqsMessageContext.ack()` 및 `nack(timeoutSeconds)`
- 고정 및 receive-count 선형 구현을 포함한 `SqsFailureVisibilityStrategy`
- receive, invoke, ack, nack 전후의 `SqsConsumerInterceptor` hook
- receive, convert, invoke, ack, nack 결과를 위한 `SqsConsumerObserver` event

## 검증

대상 검증에서 runtime 설정 validation, 변환 실패 delete policy, 수동 ack/nack,
interceptor 순서, observer/failure visibility 동작을 확인했다.

## 향후 보호 장치

SQS 고급 제어는 opt-in으로 유지한다. Ktor 모듈에 Spring형 annotation이나 metric
의존성을 추가하지 않는다. 작은 runtime hook을 제공하고 애플리케이션이 적용하게 한다.
