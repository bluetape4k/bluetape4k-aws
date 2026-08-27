# 이슈 #572 SNS WebFlux bounded concurrency smoke lesson

## 배경

PR #568의 WebFlux 테스트는 단일 요청과 replay body를 검증했지만, 서로 다른
SNS envelope가 동시에 처리될 때 body와 parsed message가 섞이지 않는다는
증거가 없었다. 이슈 #572는 생산 동시성 정책을 추가하지 않고 고정된 소규모
동시성 smoke를 요구했다.

## 결정

- 새 dependency나 production semaphore를 추가하지 않고 기존
  `MultithreadingTester`를 재사용한다.
- 4 workers × 2 rounds의 총 8개 요청으로 부하를 bounded하게 고정한다.
- 각 요청에 고유한 `orderId`와 `messageId`를 넣고, thread-safe queue에
  handler 관찰 결과를 저장해 body/envelope pair를 검증한다.
- verifier 진입부에 2-way bounded barrier와 `maxInFlight > 1` assertion을 두어
  worker 스케줄링이 우연히 직렬화되어도 smoke가 false-green이 되지 않게 한다.
- counting resolver wrapper로 허용 요청 5건의 notification parameter 4개, 총
  20회 resolver 호출도 함께 고정한다.
- 허용 topic과 차단 topic을 같은 행렬에 넣어 성공·403 failure boundary,
  verifier·handler·confirmation 호출 수를 함께 확인한다.

## 결과

현재 `SnsHttpMessageWebFilter`와 resolver는 요청별 prepared message와 replay
body를 분리해 보존했다. 허용 요청 5건의 `(orderId, messageId)` pair가 모두
정확히 유지됐고, 차단 요청 3건은 handler에 도달하지 않았다.

## 검증

- `SnsWebFluxHttpEndpointTest`: 8개 테스트 통과
- 동시성 smoke: 8개 요청, 허용 5건, 차단 3건, verifier 5회,
  confirmation 0회
- SNS WebFlux 모듈 전체 테스트: 1,398건 통과, 2건 skip
- `detekt`: 통과
- `git diff --check`: 통과

## 놓친 점과 경계

기존 fixture의 단일 `invocations` 카운터와 마지막 payload 필드는 동시 요청
검증에 충분하지 않았다. 카운터는 `AtomicInteger`, 관찰 결과는
`ConcurrentLinkedQueue`로 바꾸고 요청별 pair를 비교해야 했다. 또한
`WebTestClient.bindToController`는 pooled transport buffer를 직접 노출하지
않으므로 pooled release는 WebFilter 전용 테스트에서 별도로 고정한다.

Floci는 signed SNS HTTP delivery를 만들지 않기 때문에 이번 smoke의 대상이
아니다. 이 경계를 실제 AWS credential smoke로 확장하지 않는다.

## 향후 guard

WebFlux adapter의 동시성 테스트는 반드시 bounded tester, 고유 body/envelope
식별자, thread-safe 관찰 수집, 성공·실패 호출 수를 포함해야 한다. pooled
buffer lifecycle을 주장하려면 in-process controller 테스트가 아니라
`SnsHttpMessageWebFilterTest`에서 실제 `PooledDataBuffer` release를
검증한다.
