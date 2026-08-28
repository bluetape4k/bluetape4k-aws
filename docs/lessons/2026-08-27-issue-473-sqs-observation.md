# Issue #473 SQS Observation 경계와 검증 교훈

## 배경

[Issue #473](https://github.com/bluetape4k/bluetape4k-aws/issues/473)은 기존 SQS listener metric hook을
유지하면서 `ObservationRegistry`를 listener 처리와 coroutine downstream 호출에 연결하는 작업이다. 이번
구현의 핵심은 observation을 많이 만드는 데 있지 않다. 메시지 처리, 실제 acknowledgement I/O, heartbeat
정책의 책임 경계를 섞지 않고, 관측성 실패가 기존 처리 결과를 바꾸지 않게 하는 데 있다.

이 문서는 구현·테스트·benchmark·독립 review에서 확인한 재사용 가능한 판단 기준을 기록한다. 확인 기준은
Issue #473 설계·실행 계획과 현재 branch의 다음 소스다.

| 근거 | 확인한 계약 |
| --- | --- |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationRuntime.kt:42-88,250-268` | observation scope 캡처, coroutine context element 설치·복원, cancellation cleanup |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationAutoConfiguration.kt:31-143` | optional classpath, property, registry, supporting `ObservationHandler` prerequisite |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsAcknowledgement.kt:56-177` | 단건 ACK/NACK/visibility의 실제 I/O observation과 terminal 상태 경계 |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchAcknowledgement.kt:214-320,574-609` | batch actual-I/O observation, `IN_FLIGHT` rollback, waiter 해제 순서 |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainer.kt:841-893` | #453 heartbeat child 수명과 `BT4K-SQS-OBS-202` 진단 |
| `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationRuntimeTest.kt` | thread 전환, parent 복원, cancellation 및 NOOP fast path |
| `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationAutoConfigurationTest.kt` | class/registry/handler/factory 조합과 bounded condition reason |
| `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsAcknowledgementTest.kt`, `SqsBatchAcknowledgementTest.kt` | 실제 I/O 횟수, duplicate/wait 경로, cancellation rollback |
| `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationAwsEmulatorTest.kt` | `FlociServer.Launcher.floci` listener 수명주기와 in-memory handler 수용 기준 |
| `.bluetape/inputs/issue473-task9-*-result*.json` | benchmark, allocation, 독립 review의 fresh 결과 |

## 결정

### 1. coroutine scope와 context element의 수명을 분리한다

`Observation.Scope`는 thread-local binding을 위한 짧은 수명 객체다. runtime은
`observation.openScope().use { ... snapshotFactory.captureAll() }` 안에서 registry binding을 확인하고
`ContextSnapshot`만 캡처한다. `use` 블록이 끝난 뒤에는 suspend block을 그 scope로 감싸지 않는다.

실제 `conversion`, handler, retry, 자동 acknowledgement는 캡처한 `ContextSnapshot`을
`withContext(snapshot.asContextElement())`로 설치한 coroutine context에서 실행한다. 이 element는
`ThreadContextElement`이므로 coroutine이 재개되는 thread마다 `updateThreadContext`에서 thread-local을
설치하고 `restoreThreadContext`에서 이전 상태를 닫는다. 따라서 scope의 thread 수명과 coroutine context
element의 structured-concurrency 수명을 같은 것으로 취급하면 안 된다.

이 구분은 세 가지 규칙으로 재사용한다.

- suspend 경계를 넘어 `Observation.Scope`를 저장하거나 다른 thread에서 닫지 않는다.
- 현재 observation을 field나 전역 map에 보관해 detached coroutine의 parent로 재사용하지 않는다.
- scope/capture 실패 시 원래 parent scope를 복원하고, 시작된 observation만 한 번 종료한다.

### 2. optional classpath와 실제 handler prerequisite를 분리한다

관찰 기능은 `bluetape4k.aws.sqs.observation.enabled`가 명시적으로 `true`일 때만 후보가 된다. 외부
타입을 eager link하지 않도록 `SqsObservationAutoConfiguration`의 outer condition은
`io.micrometer.observation.ObservationRegistry`만 확인하고, 내부 prerequisite condition은
`io.micrometer.context.ContextSnapshot`을 class 이름으로 확인한다. class가 없으면 application
context가 linkage error 없이 bounded reason을 남기고 back off해야 한다.

class가 있어도 다음 bean 조건이 모두 필요하다.

- `ObservationRegistry` bean이 존재한다.
- registry가 `ObservationRegistry.NOOP`가 아니다.
- Spring bean으로 등록된 `ObservationHandler` 중 정제된 `SqsObservationContext` probe를 지원하는 handler가 하나 이상 있다.

`SqsObservationFactory`는 default factory만 대체한다. factory bean을 등록해도 registry 또는 supporting
handler prerequisite를 우회하지 않는다. 조건이 충족되지 않으면 activation marker와 runtime을 만들지
않고 기존 listener 및 legacy listener metric 경로를 유지한다. condition report에는 현재 구현이
`BT4K-SQS-OBS-101 registry-missing`, `registry-noop`, `handler-missing`,
`context-propagation-missing` 같은 bounded reason을 남긴다.

이 dependency는 runtime에서 `context-propagation:1.2.1`을 사용하지만 public observation signature에는
`io.micrometer.context` 타입을 노출하지 않는다. 선택적 capability의 classpath 조건과 public ABI 경계는
각각 별도 테스트해야 한다.

### 3. ACK observation은 실제 I/O와 결과 확정 경계에 둔다

단건 `SqsAcknowledgement`는 `operations.delete`, `operations.changeVisibility`가 실행되는 구간을
`ACKNOWLEDGEMENT` observation으로 감싼다. batch도 `deleteBatch` 또는 `changeVisibilityBatch`의 실제
호출과 응답 결과를 같은 observation에 기록한다. duplicate, wait, already-terminal처럼 외부 I/O가 없는
분기는 새 observation을 만들지 않는다.

terminal 상태는 호출 진입 시 미리 확정하지 않는다. 단건 ACK는 delete가 성공한 뒤 `completed`를 true로
변경하고, 실패나 cancellation에서는 다시 호출할 수 있는 상태를 유지한다. batch는 실행 전에 항목을
`IN_FLIGHT`로 예약하지만, 외부 호출 중 `CancellationException`이 발생하면 다음 순서를 지킨다.

1. `NonCancellable` 안에서 mutex를 획득해 해당 항목을 `PENDING`으로 되돌리고 `inFlight`를 비운다.
2. mutex를 놓은 뒤 공유 `Deferred`를 완료해 대기 중인 caller를 깨운다.
3. cancellation hook과 observation 종료를 수행하고, cleanup 오류는 원래 `CancellationException`의 suppressed exception으로 붙인다.
4. 원래 cancellation instance를 재전파한다.

이 순서를 바꾸어 waiter를 mutex 안에서 깨우거나 observation cleanup을 먼저 수행하면, waiter가 영원히
기다리거나 재호출이 stale `IN_FLIGHT` 상태에 막힐 수 있다. observation의 성공 여부와 SQS 외부 호출의
성공 여부를 별도 상태로 기록하되, 실제 ACK 결과가 process retry 판단을 임의로 바꾸지 않게 한다.

### 4. heartbeat 정책은 #453에 남기고 telemetry 경계만 추가한다

[Issue #453](https://github.com/bluetape4k/bluetape4k-aws/issues/453)은
`messageVisibilityHeartbeatIntervalSeconds`·`messageVisibilityHeartbeatSeconds`의 생성, 주기, child
coroutine 수명, cancellation 및 visibility 정책을 소유한다. #473은 이 설정이나 주기를 바꾸지 않는다.
이미 실행되는 `ChangeMessageVisibility` 호출만 `CHANGE_VISIBILITY` action의 acknowledgement
observation으로 감싼다.

heartbeat는 handler 수명에 묶인 child coroutine으로 실행하고, `withVisibilityHeartbeat`의 `finally`에서
`NonCancellable` `cancelAndJoin`으로 종료한다. background heartbeat에 stale process parent를 연결하지
않는다. foreground telemetry setup 실패와 heartbeat observation의 `error()` 또는 `stop()` 실패는
원본 throwable이나 전체 queue URL 없이 `BT4K-SQS-OBS-202` bounded warning만
남기고 #453이 정한 visibility 연장 결과와 handler 결과는 유지한다.

## 결과

- enabled 경로는 receive, process, 실제 acknowledgement·visibility I/O를 서로 구분하며, process 내부의
  conversion·handler·retry와 downstream coroutine에는 현재 observation context를 전달한다.
- disabled, runtime 없음, 또는 NOOP registry 경로는 observation context·scope·capture·event를 만들지
  않는 direct fast path를 사용한다.
- 기본 context는 message body, receipt handle, 전체 queue URL, 임의 attribute/header, exception message를
  제공하거나 tag로 만들지 않는다. batch는 batch size가 1이어도 개별 message/FIFO ID를 노출하지 않는다.
- ACK cancellation 뒤 batch waiter가 완료되고 재호출할 수 있다. 원래 business cancellation·외부
  오류는 primary로 보존하고 cleanup 오류는 suppressed로 붙인다. 단, business block이 성공한 뒤
  foreground observation `stop()`이 실패하면 fail-closed 규칙에 따라 cleanup 오류를 호출자에게 전달한다.
- heartbeat 주기와 visibility 정책의 소유권은 #453에 남아 있어, #473을 수정할 때 heartbeat 정책까지
  함께 변경하지 않아도 된다.

## 검증

### 소스·단위·Floci 검증

`SqsObservationRuntimeTest`는 suspension과 worker thread 전환 뒤 현재 observation이 downstream에
전달되고 원래 parent가 복원되는지 확인한다. cancellation, setup failure, redacted telemetry error,
NOOP fast path도 같은 suite에서 확인한다.

`SqsObservationAutoConfigurationTest`는 기본 disabled, missing/NOOP registry, supporting handler 유무,
`FilteredClassLoader`로 제거한 `ContextSnapshot`, user factory만 등록한 경우를 각각 확인한다.
`SqsAcknowledgementTest`와 `SqsBatchAcknowledgementTest`는 actual I/O count, duplicate observation 부재,
terminal ACK 경합, 1,000회 cancellation rollback과 waiter 재시도를 고정한다.

`SqsObservationAwsEmulatorTest`는 실제 AWS credential 대신 `FlociServer.Launcher.floci`와 in-memory
`ObservationHandler`를 사용한다. 다음 수용 범위를 확인했다.

- process context 전파, manual ACK, heartbeat visibility observation;
- retry와 redelivery, empty poll의 receive-only 경로;
- FIFO 단건 high-cardinality와 batch partial acknowledgement;
- ACK I/O failure, heartbeat telemetry cleanup failure, queue resolution `BT4K-SQS-OBS-201`, redaction.

### benchmark·allocation·review 검증

Task 9 benchmark는 managed `kotlinx-benchmark 0.4.17`에 sample mode 설정이 없어
`org.openjdk.jmh.Main`을 직접 호출하는 `JavaExec` 경로를 사용했다. fast path는 direct/disabled/active를
비교하고, contention은 single ACK, batch ACK, heartbeat를 측정한다. 각 invocation의 teardown은
sentinel, observation start/stop, active observation, registry current state, 실제 I/O 횟수를 검증한다.
JMH는 `-foe true`를 사용하고, 별도 verification task가 강제로 teardown을 실패시켜 process exit가
비정상인지 확인한다. concurrent workload는 bounded readiness, `withTimeout`, cancellation 및
`joinAll`을 사용해 orphan coroutine을 남기지 않는다.

allocation suite는 30개 paired sample에서 disabled fast path의 bootstrap upper 95% bound를
`0.0 B/op`으로 확인했다. 이 값은 측정한 환경의 회귀 기준이지 모든 JVM·workload의 성능 보장은 아니다.

첫 독립 Task 9 review는 P0=0, P1=1, P2=2로 `REQUEST CHANGES`를 반환했다. JMH teardown 실패가 숨겨질
수 있음, readiness·cleanup 상한 부재, contention sentinel 부족이 구체적 finding이었다. 수정 뒤 exact-head
재검토는 P0=0, P1=0, P2=0, P3=0으로 `APPROVE`했고, forced teardown non-zero, 15개 focused test,
allocation, compile, detekt, six JMH result와 hidden-failure scan을 다시 확인했다.

### 이번 단계의 CI 경계

이번 local 작업은 Floci와 Gradle 증거를 수집했지만 hosted PR CI를 실행하지 않았다. 따라서 local PASS는
exact-head CI PASS를 대신하지 않는다. PR을 만들 때는 변경된 exact commit의 모든 applicable job이 terminal
success인지, path-filtered skip이 없는지, `gh run view`에서 실제 로그와 artifact를 읽었는지를 별도 확인해야
한다. 사람 review를 N/A로 두더라도 독립 model review와 exact-head 검증은 생략하지 않는다.

## 놓친 점과 예상 밖의 발견

1. benchmark process가 exit code 0을 반환해도 JMH teardown의 assertion이 관측 결과를 숨길 수 있었다.
   benchmark는 숫자만 남기지 말고 invocation별 invariant와 fail-closed process exit를 함께 검증해야 한다.
2. user factory를 제공하면 관찰 runtime을 구성할 수 있을 것처럼 보이지만, supporting `ObservationHandler`
   없이는 실제 observation 처리 경로가 준비되지 않는다. factory back-off와 activation prerequisite는
   별도의 조건이다.
3. ACK cancellation은 예외를 다시 던지는 것만으로 끝나지 않는다. batch 상태와 공유 waiter를 먼저
   복구해야 다음 caller가 같은 메시지를 재시도할 수 있다.
4. Floci listener acceptance는 coroutine context, handler lifecycle, acknowledgement I/O와 redaction을
   검증하는 데 충분하지만, actual AWS service semantics나 OpenTelemetry exporter 전달까지 증명하지는
   않는다. 이 둘을 같은 PASS로 표현하면 검증 범위를 과장하게 된다.

## 재사용할 교훈

- coroutine instrumentation은 `scope`와 `context element`의 수명을 먼저 도식화한 뒤 구현한다. suspend
  block을 thread-bound scope로 감싸지 말고 capture 후 context element로 설치한다.
- optional dependency를 도입할 때는 outer class condition, bean prerequisite, public ABI scan,
  `FilteredClassLoader` negative test를 한 세트로 만든다. user extension이 prerequisite를 우회하지
  않는지도 고정한다.
- acknowledgement metric/span은 method entry가 아니라 실제 외부 I/O와 결과 확정 지점에 둔다.
  no-I/O duplicate/wait path와 actual I/O path의 count budget을 각각 검증한다.
- cancellation 경로는 `CancellationException` identity, 상태 rollback, waiter completion, hook,
  observation cleanup의 순서를 테스트한다. cleanup은 필요한 부분만 `NonCancellable`로 감싸고, 원래
  business cancellation을 primary로 보존한다.
- emulator integration, in-memory handler, benchmark, hosted CI, independent review를 서로 다른 증거로
  기록한다. Floci PASS는 actual AWS 또는 exporter PASS의 대체 증거가 아니다.
- benchmark는 결과 파일뿐 아니라 sentinel·teardown·exit code를 검증한다. readiness와 cleanup에는
  bounded timeout을 두고, contention workload에는 expected count를 명시한다.
- 독립 review가 P1/P2를 찾으면 수정한 exact head에서 같은 관점으로 재검토한다. 첫 review의 승인이나
  사람 review N/A만으로 수정 후 상태를 추정하지 않는다.

## 후속 책임

| 책임 | 소유자 | 범위 |
| --- | --- | --- |
| observation activation, canary 승인·중단, dashboard/alert 전환 | `debop` | 전체 canary window와 abort signal을 확인한 뒤 meter를 전환한다. |
| rollback과 `BT4K-SQS-OBS-101/201/202` 진단 확인 | `debop` | receive stop → in-flight drain → `STOPPED` → property 변경 → restart/redeploy 순서를 확인한다. |
| heartbeat interval·timeout·visibility 정책 | [#453](https://github.com/bluetape4k/bluetape4k-aws/issues/453) | #473은 기존 heartbeat I/O를 observation으로 감싸는 경계만 소유한다. |
| actual AWS, IAM, OpenTelemetry SDK/exporter/collector smoke | 별도 credential-gated 범위 | 이번 작업에서는 `N/A`이며 Floci acceptance로 대체하지 않는다. |

## 파일 요약

이 파일은 Issue #473의 coroutine context 전파, optional observation activation, actual-I/O ACK 경계,
cancellation rollback, #453 heartbeat ownership, Floci·benchmark·CI·independent review의 재사용 교훈을
현재 소스와 evidence artifact에 연결해 기록한다.

## 남은 위험

- hosted exact-head CI와 실제 AWS/OpenTelemetry exporter smoke는 아직 실행하지 않았으므로 PR·release
  근거로 사용할 수 없다.
- Floci가 재현하지 않는 AWS throttling, 네트워크 오류, IAM·quota, exporter backpressure와 실제 운영
  cardinality는 호출자가 별도 canary에서 확인해야 한다.
- `SqsObservationFactory`와 `SqsObservationContextCustomizer`로 임의 데이터를 추가하면 privacy와
  cardinality 책임이 사용자에게 있으므로, 기본 allowlist 검증만으로 사용자 확장 데이터의 안전성을
  보장할 수 없다.
