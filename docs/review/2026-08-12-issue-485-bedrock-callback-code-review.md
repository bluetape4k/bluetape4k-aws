# #485 Bedrock ConverseStream callback coordinator 구현 review

## 판정

- 상태: PASS (구현 범위)
- P0: 0
- P1: 0
- 남은 P2: 2
- 대상: `aws-java` private `StreamCoordinator` 및 회귀 테스트

설계 review에서 승인한 private coordinator 범위 안에서만 변경했다. public Flow
signature, AWS SDK 호출 방식, 의존성, `buffer(0)` backpressure 계약은 바꾸지 않았다.

## 구현 확인

- `callbackLock: ReentrantLock`과 `withReentrantLock` alias로 non-suspending callback
  metadata를 보호한다. `callbackCompletions`는 sequence-keyed `LinkedHashMap`이며
  close 시 snapshot 후 clear한다.
- callback logical completion과 `CompletableDeferred` signal을 분리했다. lock 안에서는
  map/flag/accumulator만 변경하고, `complete`, `await`, `join`, `emit`, `collect`,
  publisher cancel은 lock 밖에서 수행한다.
- `Mutex`는 generation/attempt/terminal 상태만 보호한다. callback lock과 `Mutex`를
  어느 방향으로도 중첩하지 않는다.
- `exceptionOccurred`는 generation map에 Throwable을 저장하지 않는 no-op 경계다. SDK
  operation future와 `AttemptCompletion.Failed`가 원래 publisher/operation cause의
  authority로 남는다.
- `StreamAttempt.cancelOnce()`는 `request -> join -> completion outcome`을 한 번만
  수행하고, 후속 호출은 같은 결과를 기다린다. 정상 `CancellationException`은 cleanup
  failure로 기록하지 않고, subscription cancel의 non-CE만 bounded accumulator에
  합친다.
- `BoundedFailureAccumulator`는 Throwable identity dedupe, suppressed sample 최대 16개,
  saturating overflow count, stackless marker 1개, one-shot materialization을 제공한다.
  operation/collector primary는 cleanup failure보다 우선하며, 동일 primary의 중복
  suppression은 만들지 않는다.
- terminal path는 callback close/drain과 active attempt cancellation을 완료한 뒤
  outer `finally`에서 operation failure를 한 번만 materialize한다. replacement와
  terminal 경합에서 pending callback을 기다린 채 cancellation owner가 교착하지 않도록
  active attempt claim/cancel 경계를 분리했다.

## 테스트와 증거

신규 회귀 시나리오는 cancellation primary/suppressed, 정상 CE 제외, pre-handoff rejection,
replacement failure 순서, bounded overflow/dedupe, handler authority, callback drain,
late callback, high-volume replacement, 실제 JVM concurrent terminal race를 포함한다.

- 구현 전 RED: Step 2 cancellation/authority 13개 중 기존 구현에서 7개 실패.
- 구현 전 RED: Step 2 drain/race 5개 중 기존 구현에서 2개 실패.
- 구현 후 targeted: `BedrockRuntimeFlowExtensionsTest` 38개 통과.
- 구현 후 module: `:bluetape4k-aws-java:test` 통과.
- 정적 분석: `:bluetape4k-aws-java:detekt` 통과(0 issues).
- source boundary: `synchronized(callbackLock)`, `handlerFailures`, lock 교차 취득
  marker가 없고, `callbackCompletions.clear`, `cancelOnce`가 존재한다.
- `git diff --check`와 문서별 whitespace 검사를 별도로 실행한다.

## 보류 범위

- 외부 publisher가 `cancel` 또는 terminal signal에서 무한히 지연되는 경우의 latency
  상한/telemetry는 이 이슈에서 정의하지 않는다.
- 실제 heap/throughput 수치 측정은 하지 않았다. 구현이 직접 보관하는 root Throwable
  reference 상한과 overflow marker 계약만 테스트했으며, 외부 Throwable 내부 graph의
  크기 상한을 주장하지 않는다.

## 결론

P0/P1 blocker는 없다. 구현은 #485 완료 조건의 monitor 제거, callback state ordering,
cancellation/terminal 회귀, virtual-thread-aware static boundary, targeted/full
`aws-java` 테스트를 충족한다. CI와 PR/merge는 별도 gate다.
