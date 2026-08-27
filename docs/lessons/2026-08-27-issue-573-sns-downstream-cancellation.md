# 이슈 #573 SNS WebFlux downstream cancellation lesson

## 배경

기존 `SnsHttpMessageWebFilterTest`는 입력 publisher가 handler 전에 취소되는
경우와 정상 replay를 검증했지만, prepare가 끝난 뒤 decorated request를
downstream에 넘기고 body를 읽은 다음 취소하는 경계는 직접 고정하지 않았다.

## 결정

- production cancellation API나 새로운 동시성 계층을 만들지 않고 filter 전용
  in-process 테스트만 추가한다.
- `DefaultServerWebExchange`와 `MockServerHttpResponse(NettyDataBufferFactory)`를
  사용해 입력과 replay body 모두 실제 `PooledDataBuffer` lifecycle을 노출한다.
- `CountDownLatch`로 replay body read와 upstream body termination을 먼저
  완료시킨 뒤 `StepVerifier.thenCancel()`을 호출해 timing race와 조기 취소를
  구분한다.
- downstream chain과 body subscription 수, 활성 body subscription 수를
  계측한다. handler는 `Sinks.one` gate 뒤에 두어 gate를 방출하지 않은 취소가
  실제 handler 호출로 이어지지 않음을 검증한다.
- relaxed `SnsOperations` mock으로 `confirmSubscription` 0회를 명시해
  cancellation이 confirmation side effect를 만들지 않도록 한다.

## 결과

새 회귀 테스트는 chain/body를 각각 한 번만 구독하고 replay read 후 활성 body
구독이 0이 되는 것을 확인한다. 입력과 replay pooled buffer는 모두 해제되며,
handler·confirmation 호출은 0회다. `thenCancel().verify()`와 `null` response
status로 취소가 400 오류 응답으로 바뀌지 않는 경계도 함께 고정한다.

## 검증

- `SnsHttpMessageWebFilterTest`: 4개 테스트 통과
- `SnsWebFluxHttpEndpointTest` + `SnsHttpMessageWebFilterTest`: 12개 테스트 통과
- SNS WebFlux module: 1,399개 테스트 통과, 2개 skip
- `detekt`: 통과
- `git diff --cached --check`: 통과
- Korean 용어 감사: findings 0

## 놓친 점과 경계

`MockServerWebExchange.from(request)`의 기본 response factory는 pooled replay
buffer를 보장하지 않으므로, replay lifecycle을 주장하는 테스트에서는 Netty
factory를 주입한 `MockServerHttpResponse`를 사용해야 한다. 또한 replay read
signal만 기다리면 upstream `doFinally`가 뒤늦게 실행될 수 있어 termination
latch를 별도로 기다린다.

Floci는 signed SNS HTTP delivery를 만들지 않기 때문에 이번 테스트의 대상이
아니다. 실제 AWS credential·network 검증으로 범위를 확장하지 않는다.

## 향후 guard

WebFlux filter의 cancellation 테스트는 prepare/replay 이후 단계에서 실제
`thenCancel()`을 사용하고, 입력·replay pooled buffer release, active
subscription 0, handler·confirmation side effect 0, response error 미정규화를
동시에 검증해야 한다. 기존 정상 replay와 handler 이전 입력 취소 테스트도
함께 유지해 lifecycle의 앞·뒤 경계를 모두 덮는다.
