# 이슈 #573 SNS WebFlux downstream cancellation lesson

## 배경

기존 `SnsHttpMessageWebFilterTest`는 입력 publisher가 handler 전에 취소되는
경우와 정상 replay를 검증했지만, prepare가 끝난 뒤 decorated request를
downstream에 넘기고 body를 읽은 다음 취소하는 경계와 replay queue discard는
직접 고정하지 않았다.

## 결정

- production cancellation API나 새로운 동시성 계층을 만들지 않고 기존 filter
  discard semantics를 그대로 검증하는 in-process 테스트만 추가한다.
- `DefaultServerWebExchange`와 `MockServerHttpResponse(NettyDataBufferFactory)`를
  사용해 입력과 replay body 모두 실제 `PooledDataBuffer` lifecycle을 노출한다.
- `CountDownLatch`로 replay body read와 upstream body termination을 먼저
  완료시킨 뒤 `StepVerifier.thenCancel()`을 호출해 timing race와 조기 취소를
  구분한다.
- in-flight replay fixture는 `hide()`로 fusion을 끊고 수동 scheduler가 drain
  task를 취소 이후 재개하게 해 `FluxPublishOn` queue의 실제 discard callback과
  pooled buffer release를 관찰한다.
- downstream chain과 body subscription 수, 활성 body subscription 수를
  계측한다. handler는 `Sinks.one` gate 뒤에 두어 gate를 방출하지 않은 취소가
  실제 handler 호출로 이어지지 않음을 검증한다.
- relaxed `SnsOperations` mock으로 `confirmSubscription` 0회를 명시해
  cancellation이 confirmation side effect를 만들지 않도록 한다.

## 결과

새 회귀 테스트는 chain/body를 각각 한 번만 구독하고 replay read 후 활성 body
구독이 0이 되는 것을 확인한다. 정상 replay와 in-flight discard 양쪽에서 입력과
replay pooled buffer가 모두 해제되며, handler·confirmation 호출은 0회다.
`thenCancel().verify()`와 `null` response status로 취소가 400 오류 응답으로
바뀌지 않는 경계도 함께 고정한다.

## 검증

- `SnsHttpMessageWebFilterTest`: 5개 테스트 통과
- `SnsWebFluxHttpEndpointTest` + `SnsHttpMessageWebFilterTest`: 13개 테스트 통과
- SNS WebFlux module: 1,400개 테스트 통과, 2개 skip
- `detekt`: 통과
- `git diff --cached --check`: 통과
- Korean 용어 감사: findings 0

## 놓친 점과 경계

`MockServerWebExchange.from(request)`의 기본 response factory는 pooled replay
buffer를 보장하지 않으므로, replay lifecycle을 주장하는 테스트에서는 Netty
factory를 주입한 `MockServerHttpResponse`를 사용해야 한다. 또한 replay read
signal만 기다리면 upstream `doFinally`가 뒤늦게 실행될 수 있어 termination
latch를 별도로 기다린다. 단순히 replay read 완료 후 취소하면 queue discard를
실행하지 않아 false positive가 될 수 있으므로, in-flight queue와 취소 후 drain
재개를 별도 fixture로 고정해야 한다.

Floci는 signed SNS HTTP delivery를 만들지 않기 때문에 이번 테스트의 대상이
아니다. 실제 AWS credential·network 검증으로 범위를 확장하지 않는다.

## 향후 guard

WebFlux filter의 cancellation 테스트는 prepare/replay 이후 단계에서 실제
`thenCancel()`을 사용하고, 입력·replay pooled buffer release, active
subscription 0, handler·confirmation side effect 0, response error 미정규화를
동시에 검증해야 한다. 기존 정상 replay와 handler 이전 입력 취소 테스트도
함께 유지해 lifecycle의 앞·뒤 경계를 모두 덮는다. 기존 filter의 outer
`doOnDiscard`가 downstream prefetch queue가 보유한 `DataBuffer`를 안전하게
release하는지 in-flight fixture로 계속 검증한다.
