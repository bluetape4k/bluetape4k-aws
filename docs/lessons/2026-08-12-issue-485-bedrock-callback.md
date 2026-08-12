# #485 Bedrock callback coordinator lesson

## 핵심 결정

AWS `exceptionOccurred(Throwable)` callback에는 publisher 또는 generation identity가
없다. 따라서 handler failure를 generation map에 저장하면 replacement 이후 오래된
Throwable을 새 publisher에 잘못 귀속할 수 있다. 구현은 handler를 no-op 경계로 두고 SDK
operation future와 현재 `StreamAttempt` completion을 오류 authority로 사용한다.

callback API는 suspend할 수 없으므로 callback metadata는 `ReentrantLock`으로, publisher
generation과 terminal state는 기존 `Mutex`로 분리했다. 두 lock은 어느 방향으로도
중첩하지 않으며, lock 안에서는 collection mutation과 snapshot만 수행한다. deferred
signal, `await`, `join`, `emit`, `collect`, 외부 publisher cancel은 lock 밖에 둔다.

## 오류 우선순위에서 얻은 것

RED 테스트는 operation failure가 publisher cancellation failure로 덮이는 경계와,
collector cancellation 중 정상 `CancellationException`이 cleanup failure로 잘못
취급될 수 있는 경계를 드러냈다. `cancelOnce()`를 request/join/completion outcome
경계로 만들고, bounded accumulator가 primary를 보존하면서 non-CE cleanup만 suppressed로
합치도록 고정했다. 동일 Throwable identity는 한 번만 보관하고, 서로 다른 failure는
최대 16개 sample과 saturating overflow count로 제한한다.

callback completion을 단순 list로 두면 close와 logical completion 사이의 interleaving에서
대기 항목이 사라질 수 있다. sequence-keyed map의 register/remove/snapshot/clear를 같은
callback lock에서 선형화하고, close가 소유한 pending snapshot만 terminal drain에서
sequence 순서로 await한다. 실제 JVM dispatcher race도 이 경계를 검증한다.

## 검증 결과와 후속 범위

- 구현 전 RED: cancellation/authority 7개, drain/race 2개 실패.
- 구현 후 `BedrockRuntimeFlowExtensionsTest` 38개, 전체 `aws-java` test, module detekt
  모두 통과했다.
- `synchronized(callbackLock)`와 stale `handlerFailures` map은 제거됐다.
- 외부 publisher latency/cleanup telemetry와 실제 heap/throughput 실측은 별도 lifecycle
  이슈로 보류한다. 이번 구현이 직접 보관하는 root Throwable reference 상한만 계약으로
  고정하며 외부 Throwable graph 전체의 크기는 보장하지 않는다.
