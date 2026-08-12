# #485 Bedrock ConverseStream callback coordination 설계

## 결정 상태

- 상태: 설계 review PASS 및 사용자 승인 완료
- 구현/커밋/PR: 사용자 승인에 따라 구현 계획과 TDD 단계로 진행한다.
- 범위: `aws-java`의 private `StreamCoordinator` callback 상태 조정
- 관련 이슈: [#485](https://github.com/bluetape4k/bluetape4k-aws/issues/485)

이 문서는 현재 구현과 이슈의 완료 조건을 기준으로 작성했다. 사용자 승인 후에만
구현 계획과 TDD 작업으로 넘어간다.

### 근거 원본

- live GitHub issue: `#485`, `refactor(aws-java): Bedrock ConverseStream callback 동기화 monitor 제거`
- GNO `bluetape4k-github`: #484/#486의 0.6.0 SDK·Kotlin 테스트 계약과 기존 테스트/fixture 경계
- AWS SDK 공식 callback 계약: <https://sdk.amazonaws.com/java/api/2.0.0/software/amazon/awssdk/awscore/eventstream/EventStreamResponseHandler.html>
- 현재 코드: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt:62-232`
- 현재 테스트: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensionsTest.kt`
- 저장소 패턴: `aws-exposed/.../AwsRdsIamAuthentication.kt`, `aws-spring-boot/.../DataKeyCache.kt`,
  `aws-spring-boot/.../AwsEnvironmentPropertySourceSupport.kt`의 `ReentrantLock` + `withLock`
- fresh baseline: `./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest' --no-build-cache --no-daemon`
  결과 `BUILD SUCCESSFUL`, 20개 테스트 통과

로컬 Gradle cache의 AWS SDK source에서는 `EventStreamAsyncResponseTransformer`가
`exceptionOccurred`에서 사용자 handler를 호출한 뒤 `transformFuture`를 예외로
완료하는 흐름을 확인했다. 따라서 구현 단계에서는 현재 외부 dependency catalog가
resolve하는 SDK 버전에서도 operation future가 이 원인을 전달하는지 dependency
resolution과 회귀 테스트로 재확인한다. 이 source 확인은 SDK retry 순서를 보장하는
근거로 사용하지 않는다.

이 문서의 제안은 위 원본과 현재 checkout을 대조한 결과다. 외부 AWS SDK 동작을 새로
정의하지 않으며, callback 상태 경계와 저장소의 기존 lock 사용 패턴만 적용한다.

## 문제와 근거

`BedrockRuntimeFlowExtensions.kt`의 `StreamCoordinator`는 AWS SDK callback 경계에서
`callbackLock`과 `synchronized(callbackLock)`을 사용한다. 현재 동작은 다음 네 가지
callback 상태를 하나의 짧은 임계 구역으로 보호한다.

1. callback sequence 증가와 completion 등록
2. callback 수락 종료와 completion snapshot

`handlerFailure`를 generation별로 저장하는 것은 이 설계에서 유지하지 않는다. AWS
SDK의 `exceptionOccurred(Throwable)`에는 publisher 또는 generation 식별자가 없으므로,
현재 sequence에 원인을 저장하면 replacement 이후 오래된 예외를 새 generation에
오귀속할 수 있다. publisher의 `StreamAttempt.completion`과 SDK operation future를
generation/operation 오류의 기준 원본으로 삼고, handler callback은 해당 오류를 SDK
future가 전달하도록만 둔다.

이 경계는 suspend할 수 없는 AWS SDK callback에서 실행되므로, callback 임계 구역 안에서
`await`, `emit`, AWS 호출, 또는 coroutine 재개를 수행하면 안 된다. 반면 stream 상태와
publisher 수명은 이미 suspend 가능한 `Mutex`가 소유한다. 이 두 상태 영역을 하나의
동기화 수단으로 합치면 callback에서 suspend를 요구하거나 deadlock 위험을 만들 수 있다.

현재 `BedrockRuntimeFlowExtensionsTest`의 fresh baseline은 20개 테스트가 통과했으며,
replacement, late signal, callback/취소 경합, future/publisher terminal 경합을 포함한다.
이슈의 현재 설명에 기록된 테스트 수(44개)는 현재 checkout의 실행 결과와 다르므로,
설계와 검증 기준은 실행 가능한 테스트 목록과 결과를 우선한다.

## 목표와 비목표

### 목표

- JVM monitor를 명시적인 non-suspending coordination primitive로 교체한다.
- callback ordering, generation 우선순위, exactly-once 취소, backpressure, terminal
  error 전파의 현재 의미를 유지한다.
- callback 임계 구역을 메모리 상태 조작으로 제한하고, suspend 경계와 분리한다.
- public API, AWS SDK 호출 방식, 의존성, Flow buffer 정책을 변경하지 않는다.
- 기존 경합 테스트를 유지하고 callback 상태 전환의 회귀 증거를 보강한다.

### 비목표

- 새로운 retry, deduplication, replay, exactly-once event delivery를 도입하지 않는다.
- AWS SDK callback 계약이나 publisher lifecycle을 재설계하지 않는다.
- `Mutex`를 제거하거나 stream state machine을 재작성하지 않는다.
- `RecordingSdkPublisher`의 동기화 방식을 이 이슈의 production 변경과 함께 확장하지
  않는다. 테스트 helper 변경은 새 회귀 증거에 꼭 필요한 경우에만 허용한다.

## 현재 동시성 계약

다음 불변식을 구현 전후에 동일하게 유지한다.

| 영역 | 계약 |
|---|---|
| callback sequence | 수락된 callback만 단조 증가하는 sequence를 받고 completion 목록에 등록된다. |
| callback acceptance | `acceptingCallbacks == false`이거나 scope가 비활성이면 publisher를 즉시 취소하고 coroutine을 만들지 않는다. |
| generation | 가장 최신 sequence만 publisher를 claim하며 이전 attempt는 한 번만 취소한다. 늦은 signal은 무시한다. |
| handler failure | publisher 식별자가 없는 `exceptionOccurred`를 generation에 직접 귀속하지 않는다. SDK operation future 또는 해당 `StreamAttempt.completion`이 원인을 전달한다. |
| close | close는 신규 callback을 차단하고 completion snapshot을 반환한다. snapshot 밖의 callback은 수락하지 않는다. |
| suspension boundary | coordination lock은 `await`, `join`, `emit`, `collect`, AWS SDK 호출을 감싼 채 유지하지 않는다. |
| cancellation | 기존 `AtomicBoolean` 기반 `cancelOnce()` 의미와 collector cancellation 전파를 유지한다. |

### lock ordering

새 coordination lock은 callback 메모리 상태만 보호한다. callback lock을 잡은 채
`mutex`를 획득하지 않으며, `mutex`를 잡은 채 callback lock을 장시간 보유하지 않는다.
handler failure map을 조회하기 위한 교차 잠금은 제거한다. callback lock과 `Mutex`는
어느 방향으로도 중첩하지 않는다. callback lock을 잡은 경로가 `await`, `join`, `emit`,
`collect`, AWS 호출, `publisher.cancelImmediately()` 또는 `mutex` 획득을 수행하지
않고, `Mutex`를 잡은 경로도 callback lock을 획득하지 않는다는 점을 구조적 review와
정적 검색으로 확인한다.

### 오류와 취소 우선순위

- SDK operation future 또는 현재 `StreamAttempt.completion`에서 온 원인이 primary
  error다. post-handoff 취소는 `job.cancel()` 요청만 반환하지 않고 `job.join()` 뒤
  `StreamAttempt.completion`의 취소 outcome을 회수해 실제 subscription cleanup failure
  만 같은 primary/suppressed accumulator에 병합한다. 요청 취소로 발생한 정상적인
  `CancellationException`은 cleanup failure가 아니므로 제외한다. pre-handoff 취소는 직접
  `publisher.cancelImmediately()` 결과를 callback completion result에 기록한다.
- `cancelImmediately()`가 primary error가 이미 있는 경로에서 실패하면 cancellation
  failure를 bounded suppression accumulator에 기록하고 원래 primary를 다시 던진다.
  accumulator는 처음 `MAX_RETAINED_SUPPRESSED_FAILURES`개의 서로 다른 원인만
  `addSuppressed`로 보존하고 초과 원인은 count만 세어 단일 overflow marker로 한 번
  요약한다. 두 참조가 같은 `Throwable`이면 self-suppression을 시도하지 않고 기존
  primary를 유지한다. 이 구현이 직접 보관하는 root `Throwable` reference는 primary
  1개, 서로 다른 suppressed sample 최대 16개, stackless overflow marker 1개로
  제한한다. 외부 원인의 내부 cause/suppressed graph 전체 크기는 이 계약의 상한에
  포함하지 않는다.
- primary error가 없는 rejection/cancellation 경로에서만 cancellation failure를
  primary로 전달한다. rejection은 callback completion을 등록할 수 없으므로 lock 밖에서
  direct cancel failure를 SDK callback 호출자에게 동기적으로 다시 던진다. AWS SDK
  transformer의 `onStream` handler 호출은 이 예외를 catch하지 않으므로 operation
  future가 같은 원인을 전달한다고 보장하지 않는다. 오류를 조용히 버리거나 원래
  원인을 cancellation failure로 덮어쓰지 않는다.
- `exceptionOccurred` handler는 generation map에 원인을 저장하지 않으며, SDK가
  operation future로 전달하는 오류를 기준으로 삼는다. handler callback 자체에서 새
  예외를 만들지 않는다.

취소/콜백 오류 accumulator는 다음 bounded contract를 사용한다. 구현은 private
`MAX_RETAINED_SUPPRESSED_FAILURES` 상수(계획에서 16으로 고정)를 두고, primary가
없으면 첫 non-CE failure를 primary로 선택한다. primary가 있으면 identity가 다른
failure 중 최대 16개만 `primary.addSuppressed(failure)`로 보존하고, 그 이후 failure는
Throwable reference를 보관하지 않은 채 overflow count만 증가시킨다. 결과를 외부로
노출하는 시점에 overflow count가 양수이면 stack trace를 채우지 않는 private marker
Throwable 하나를 추가해 누락된 개수를 message로 기록하고, 동일 accumulator의 후속
결과 호출에서는 marker를 다시 추가하지 않는다. 정상 `CancellationException`과
primary 자신은 count에도 포함하지 않는다. 따라서 기존의 첫/둘째 원인 identity와
  suppression 순서는 유지하면서, 실패 수가 커져도 이 구현이 직접 보관하는 root
  `Throwable` 수는 상수로 제한된다.

accumulator의 모든 mutation, snapshot/clear, one-shot materialization은 동일한
non-suspending callback lock으로 직렬화한다. callback lock 안에서는 Mutex를
획득하거나 await/join/emit/외부 publisher 호출을 수행하지 않으며, callback lock과
Mutex를 어느 방향으로도 중첩하지 않는다. rejection-local accumulator처럼 단일
호출자가 소유하는 인스턴스는 그 호출자의 lock 경계에서만 사용한다.

구현은 모든 publisher 취소를 하나의 non-locking cancellation boundary로 통과시킨다
(`cancelOnce()`가 cleanup outcome을 반환하고 호출자가 bounded accumulator에 병합하는
방식 또는 동등한 구현). 경로별 우선순위는 다음과 같다.

| 경로 | primary | cancellation failure |
|---|---|---|
| operation/publisher failure | operation 또는 publisher 원인 | bounded `addSuppressed` 후 원인 재전파 |
| collector cancellation | 기존 `CancellationException` | bounded `addSuppressed` 후 같은 CE 재전파 |
| rejected callback, primary 없음 | cancellation failure | primary로 전달 |
| 이미 선택된 primary가 있는 후속 취소 | 기존 primary | bounded sample/overflow count로 병합 |
| outer `finally` cleanup | 진행 중인 primary를 변경하지 않음 | primary가 있으면 suppressed, 없으면 cleanup failure를 전달 |

## 대안 검토

### A. `ReentrantLock` + 기존 `Mutex` (권장)

`java.util.concurrent.locks.ReentrantLock`을 callback 상태 전용 lock으로 두고
`kotlin.concurrent.withLock`의 import alias(예: `withReentrantLock`)로 세 개의 짧은
임계 구역을 표현한다. 대상 파일에는 이미 `kotlinx.coroutines.sync.withLock`이 있으므로
alias로 두 lock의 suspension 의미를 코드에서 구분한다. `Mutex`는 기존과 같이
suspend 가능한 stream state 전용으로 남긴다.

장점:

- monitor 대신 명시적인 JVM coordination primitive를 사용한다.
- callback API의 non-suspending 제약을 직접 표현한다.
- callback 상태의 atomic snapshot/order 보장을 mutable collection과 함께 유지한다.
- publisher identity가 없는 handler failure map을 제거해 stale failure 오귀속과
  `Throwable` retention을 함께 없앤다.
- API와 의존성 변경 없이 기존 코드의 최소 diff로 적용할 수 있다.
- 동일 저장소의 `ReentrantLock` + `withLock` 사용 패턴과 일치한다.

주의점과 완화:

- lock은 여전히 blocking primitive이므로 임계 구역을 메모리 연산으로만 제한하고,
  benchmark/경합 테스트에서 대기 시간을 관찰한다.
- fairness 옵션은 외부 계약이 아니며 기본 non-fair lock을 사용한다. callback 처리량과
  현재 ordering 계약을 불필요하게 바꾸지 않기 위해 공정성 정책을 새로 도입하지 않는다.

### B. atomics와 concurrent collection으로 분산

`AtomicLong`, `ConcurrentHashMap`, `CopyOnWriteArrayList` 등을 조합해 lock을 없앤다.

반려 이유: sequence와 completion 목록의 동일 snapshot, close와 신규 callback의 선형화
지점, generation별 failure 조회를 여러 자료구조에 걸쳐 재구성해야 한다. 현재 동작을
보존하는 데 필요한 ordering proof와 allocation 비용이 커지고, callback 경합 오류를
재현하기도 어려워진다. 이 이슈의 monitor 제거 목적에 비해 변경 위험이 크다.

### C. callback 상태까지 `Mutex` 하나로 통합

모든 상태를 기존 `Mutex.withLock`으로 통합한다.

반려 이유: AWS SDK callback은 suspend 함수가 아니므로 callback에서 `Mutex.withLock`을
호출할 수 없다. `runBlocking`, 별도 coroutine, `tryLock` 우회는 callback ordering과
취소 의미를 바꾸고 deadlock 또는 callback 누락을 만들 수 있다. non-suspending callback
상태와 suspendable stream 상태를 분리하는 현재 경계를 유지해야 한다.

## 권장 설계

1. `callbackLock: Any`를 `ReentrantLock` 인스턴스로 교체하고
   `kotlin.concurrent.withLock`을 `withReentrantLock`으로 alias import한다.
2. `replaceFromCallback`에서 acceptance 판정, sequence 증가, completion 등록을 한
   `withLock` 블록으로 유지한다. 블록 밖에서 publisher 취소와 coroutine launch를 한다.
3. `handlerFailureFromCallback`은 generation별 map에 예외를 저장하지 않는다. SDK
   operation future와 `StreamAttempt.completion`이 원본 오류를 전달하는지 회귀 테스트로
   고정한다. 기존 replacement 테스트는 replacement 뒤 late handler exception도
   성공을 오염하지 않는지 검증한다.
4. callback 등록, logical completion 표시와 pending 항목 제거, `closeCallbacks`의 신규
   callback 차단·pending snapshot·backing collection clear는 모두 같은 callback lock
   안에서 선형화한다. `CompletableDeferred.complete`는 continuation을 재개할 수 있으므로
   lock 안에서 호출하지 않는다. callback completion 항목은 sequence를 key로 하는
   insertion-ordered map에서 `logicallyCompleted`와 `drainClaimed` 상태를 가진다.
   callback이 먼저 logical completion을 선형화하면 pending map에서 O(1)으로 제거하고
   failure를 bounded 단일 operation-level accumulator에 병합한 뒤 lock 밖에서
   결과(`Throwable?`)를 signal한다. close가 먼저 snapshot을 소유하면 해당 항목을
   `drainClaimed`로 표시하고 snapshot에 남기며, callback은 lock 밖에서 같은 deferred를
   signal하고 close가 결과를 await한다. 동일 항목의 중복 completion은 lock 안에서
   무시한다. 외부 cancel/launch와 snapshot completion의 `await`는 lock 밖에서 수행한다.
   pending snapshot의 suppression 순서는 insertion-ordered sequence 순서로 await해
   실행 thread 순서에 의존하지 않게 한다. accumulator는
   `MAX_RETAINED_SUPPRESSED_FAILURES`개의 원인 sample과 overflow count만 보관하고,
   close/terminal 경계에서 overflow marker를 최대 한 번만 primary에 붙인 뒤 상태를
   초기화한다.
5. `cancelImmediately()`는 callback lock과 `Mutex` 밖에서 호출한다. post-handoff
   `cancelOnce`는 active job의 cancel 요청, join, completion-result await를 하나의
   suspend 가능한 경계로 제공하며 `asFlow`가 finally에서 호출한 subscription cancel
   failure를 회수한다. cancellation publisher가 예외를 던질 때 primary
   terminal/cancellation 원인을 덮지 않도록 suppressed-cause 정책을 정의하고,
   pre/post-handoff throwing publisher 회귀 테스트로 고정한다.
   `futureSucceeded`, `futureFailed`, `cancel`은 callback close snapshot과 terminal 상태를
   먼저 선형화한 뒤 lock 밖에서 callback result를 await한다. 현재 attempt의 취소는
   outer `finally`의 단일 `cancelActiveAttempt()` owner가 claim한 뒤 수행하고,
   replacement는 새 attempt를 publish하기 전에 이전 attempt를 한 번만 claim한다.
   `cancelOnce`의 첫 호출이 취소를 시작하면 후속 호출은 동일한 in-flight cancellation
   result deferred를 await해 결과를 공유하며, 동일 attempt의 cleanup failure를 같은
   primary에 두 번 `addSuppressed`하지 않는다. AtomicBoolean만 보고 즉시 반환하지 않는다.
   collector cancellation에서 이 request→join→result await와 callback drain은
   `withContext(NonCancellable)` 경계 안에서 수행해 이미 취소된 caller가 cleanup을
   중단하지 않게 한다. 이 경계도 callback lock과 `Mutex` 밖에 둔다.
   `StreamAttempt.completion`은 publisher collect의 정상/예외/취소 및 cancellation
   `finally` failure, early return, launch failure를 모두 하나의 typed outcome으로
   완료해 request→join 뒤 결과 await가 영원히 대기하지 않도록 한다. private outcome은
   `Succeeded`, `Failed(cause)`, `Cancelled(cleanupFailure?)`를
   구분하며 `Cancelled(null)`과 요청 취소로 발생한 `CancellationException`은
   cleanup failure로 병합하지 않는다. non-CE subscription cancel failure만
   `Cancelled(cleanupFailure)`에 기록한다.
6. callback lock의 보유 범위와 lock ordering을 KDoc/코드 주석이 아니라 명확한 함수
   경계와 테스트 이름으로 드러낸다. 불필요한 주석이나 새 추상화는 추가하지 않는다.

## 회귀 테스트 설계

기존 20개 Bedrock flow 테스트를 먼저 유지하고, 아래 계약을 별도 증거로 고정한다.

| 시나리오 | 검증 내용 |
|---|---|
| callback 수락/종료 경합 | terminal close 이후 late publisher가 즉시 취소되고 callback coroutine이 실행되지 않는다. |
| callback failure sequence | 이전 generation failure가 replacement 성공 결과를 덮지 않는다. |
| handler failure propagation | current stream handler exception은 SDK operation future 또는 publisher completion의 원인으로 전달되고, generation map 없이도 primary error가 보존된다. |
| 동시 replacement | newest generation만 emit하고 old publisher는 한 번만 취소된다. |
| 취소 경합 | callback handoff 중 collector cancellation이 operation과 publisher를 각각 한 번만 취소한다. |
| terminal ordering | future success/failure와 publisher terminal 순서가 기존 error precedence를 유지한다. |
| static monitor 검토 | production 대상 파일에 `synchronized(callbackLock)` 또는 callback monitor 패턴이 남지 않았음을 diff/정적 검색으로 확인한다. |
| lock boundary 검토 | `withReentrantLock` 블록 안에 `await`, `join`, `emit`, `collect`, `Mutex`, AWS 호출, `cancelImmediately`가 들어가지 않음을 source review로 확인한다. |
| callback retention | 고빈도 deterministic replacement 뒤 pending callback map이 비고, bounded failure accumulator가 sample limit과 overflow count를 넘는 Throwable 참조를 보관하지 않는다. |

새 테스트는 `runTest`, 기존 `RecordingSdkPublisher`, deterministic scheduler를
우선 사용한다. 실제 thread pool이나 sleep 기반 경합은 deterministic 증거로 대체할 수
없을 때만 추가한다. 대상 테스트와 전체 `aws-java` 테스트를 순차 실행하고, Detekt와
`git diff --check`를 함께 수행한다.

## 호환성, 성능, 운영

- public signature와 binary surface는 변경하지 않는다.
- 새 dependency와 Gradle catalog 변경은 없다.
- `ReentrantLock`은 callback 임계 구역에만 사용하므로 AWS network, publisher
  collection, Flow emission의 latency 경로를 lock으로 확장하지 않는다.
- callback 목록은 sequence-keyed pending map으로 유지하고 close 시 drain한다. callback
  cleanup failure는 generation map이 아닌 operation-level bounded accumulator로만
  잠시 보관한다. `MAX_RETAINED_SUPPRESSED_FAILURES`개의 sample과 overflow count를
  close에서 소비·초기화한다. 이 구현이 직접 보관하는 root Throwable reference는 primary 1개,
  sample 최대 16개, stackless overflow marker 1개로 제한한다. 실제 heap 수치와 throughput은
  이 이슈의 검증 범위로 주장하지 않는다.
- rollback은 production 파일의 lock 교체를 revert하는 단일 변경으로 가능하다. 테스트
  보강은 동작을 바꾸지 않으므로 별도로 되돌릴 수 있다.
- 운영 설정, metrics, 로그, migration 문서는 추가하지 않는다. 이 변경은 내부
  coordination 구현 교체이며 외부 rollout 단계가 없다.

## 승인 후 DoD 초안

- [ ] 사용자 승인된 설계와 일치하는 최소 production diff
- [ ] callback acceptance/failure/close 및 cancellation/terminal 경합 회귀 테스트
- [ ] targeted Bedrock 테스트 통과
- [ ] 전체 `aws-java` 테스트 통과
- [ ] Detekt와 `git diff --check` 통과
- [ ] 대상 파일의 callback monitor 부재를 정적 검색으로 확인
- [ ] Type A 구현 review와 DoD evidence 기록

### 명시적 보류

외부 `SdkPublisher.subscribe()`/`cancel()`이 임의로 오래 걸리는 경우를 내부 lock
교체만으로 bounded하게 만들 수는 없다. 이 이슈에서는 해당 호출을 callback lock과
`Mutex` 밖에서 수행하고, primary terminal/cancellation 원인을 보존하는 예외 정책과
throwing publisher 테스트를 구현한다. 외부 publisher의 응답 시간 자체를 제한하는
timeout/dispatcher 정책은 SDK lifecycle을 변경하므로 별도 follow-up 범위로 보류한다.

## 승인 기록

2026-08-12 사용자가 설계 review 결과와 material change(`handlerFailures` 제거,
pending callback drain, cancellation primary/suppressed precedence)를 확인하고
구현 진행을 승인했다. 따라서 다음 단계는 이 설계에 고정된 implementation plan 작성,
plan review, TDD 순서이며 public API와 외부 publisher timeout 정책은 여전히 범위 밖이다.
