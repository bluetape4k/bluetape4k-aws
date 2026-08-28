---
title: "Issue #473 SQS ObservationRegistry와 coroutine context 전파 설계"
issue: 473
epic: 500
status: reviewed-design
date: 2026-08-27
---

# Issue #473 SQS ObservationRegistry와 coroutine context 전파 설계

## 결정 요약

`bluetape4k-aws-spring-boot`의 SQS listener에 opt-in Micrometer Observation 계층을
추가한다. 선택한 A안은 메시지 처리 observation을 중심으로 두고 receive/poll과
acknowledgement·visibility I/O를 별도 observation으로 측정하는 Hybrid lifecycle이다.
conversion과 retry는 독립 span을 반복 생성하지 않고 process observation의 event와
최종 outcome으로 기록한다.

기능은 `bluetape4k.aws.sqs.observation.enabled=true`이고 실제 `ObservationRegistry`,
Micrometer Context Propagation, process context를 지원하는 `ObservationHandler`가 모두 있을
때만 활성화한다. 기본값은 `false`다. 별도 `SqsObservationProperties`를 사용해 기존
`SqsProperties.Listener` 생성자 ABI를 바꾸지 않는다. 사용자 factory도 같은 prerequisite를
통과해야 하며, factory bean만으로 기능이 활성화되지는 않는다.

coroutine suspension 구간에 `Observation.Scope`를 열어 둔 채 넘기지 않는다. observation
scope 안에서 `ObservationRegistry.asContextElement()`를 캡처하고 scope를 같은 스레드에서
닫은 뒤, `withContext(capturedContext) { ... }`에서 conversion, handler, retry 판정과
자동 acknowledgement를 실행한다. 성공, 재시도 후 성공, 예외, cancellation, partial
acknowledgement를 서로 다른 bounded outcome으로 남긴다.

기본 convention은 message body, receipt handle, 전체 queue URL, 임의 message
attribute/header, exception message를 절대 tag에 넣지 않는다. 공개 customization
context에는 정제한 immutable metadata만 노출한다. 기존
`MicrometerSqsListenerInterceptor`는 실제 observation runtime이 활성화된 경우에만 억제해
빈 registry 때문에 listener meter가 사라지지 않게 한다. `MicrometerSqsOperations`
계측은 그대로 유지한다.

## 1. 문제와 현재 근거

Issue #473은 기존 timer/counter hook을 대체하지 않으면서 Spring `ObservationRegistry`를
통해 SQS listener와 coroutine downstream 호출의 현재 observation을 연결하도록 요구한다.
현재 구현에는 단계별 interceptor가 있지만, suspend hook이 반환된 뒤 호출자의 coroutine
context를 바꿀 수 없으므로 interceptor만으로 handler 전체에 observation을 안전하게
전파할 수 없다.

| 현재 근거 | 확인한 계약 | 설계 영향 |
| --- | --- | --- |
| `SqsMessageListenerContainer` | receive, 단건·batch 처리, retry, heartbeat, cancellation 수명을 소유 | observation의 시작·종료와 coroutine context 전파는 container 내부 around 경계에서 수행한다. |
| `SqsListenerMethodInvoker` | argument conversion과 reflective handler 호출을 함께 수행 | process observation이 conversion과 handler를 모두 포함한다. |
| `SqsAcknowledgement` / `SqsBatchAcknowledgement` | ACK, NACK, visibility 변경과 partial 결과를 소유 | acknowledgement observation은 실제 AWS I/O와 결과 확정 경계에 둔다. |
| `SqsListenerInterceptor` | receive, handle, acknowledgement, retry, cancellation hook 제공 | 사용자 interceptor 호환성은 유지하되 observation 전파 책임은 부여하지 않는다. |
| `SqsMicrometerAutoConfiguration` | `MeterRegistry`가 있으면 listener interceptor를 자동 생성 | Observation opt-in 시 자동 listener interceptor만 back-off시켜 중복 측정을 막는다. |
| Micrometer 1.17 | `ObservationRegistry.asContextElement()`가 현재 observation을 coroutine context element로 캡처 | scope를 suspension에 걸쳐 직접 유지하지 않는다. |
| Micrometer Context Propagation 1.2.1 | 저장소 BOM이 버전을 관리하지만 현재 module compile classpath에는 없음 | `implementation(bt4k.micrometer.context.propagation)`을 직접 추가한다. |
| Spring Cloud AWS | registry/convention opt-in, low/high cardinality 분리, 비동기 scope 동일 스레드 종료 주의 | API 복제가 아니라 lifecycle과 안전 경계만 차용한다. |

공식 source의 observation 이름도 그대로 복제하지 않는다.
`SqsListenerObservation.Convention#getName()`의 interface 기본값은
`spring.cloud.aws.sqs.listener`이지만 상속된 기본 구현, 테스트와 문서는
`spring.aws.sqs.listener`를 사용한다. Bluetape 이름은 아래 세 값으로 명시하고 contract
test로 고정한다.

외부 근거는 2026-08-27에 조회했고 이동 가능한 branch URL 대신 다음 commit에 고정했다.

- Micrometer coroutine context 구현:
  [`a4ba539`](https://github.com/micrometer-metrics/micrometer/blob/a4ba53986179631550d9d97c23ce88f049fdf9d8/micrometer-core/src/main/kotlin/io/micrometer/core/instrument/kotlin/AsContextElement.kt)
- Micrometer context element 수명:
  [`a4ba539`](https://github.com/micrometer-metrics/micrometer/blob/a4ba53986179631550d9d97c23ce88f049fdf9d8/micrometer-core/src/main/java/io/micrometer/core/instrument/kotlin/KotlinObservationContextElement.java)
- Spring Cloud AWS SQS 문서와 convention:
  [`98f0f4d`](https://github.com/awspring/spring-cloud-aws/blob/98f0f4dedf9b6fae9b28117adc9560fe4362aa1c/docs/src/main/asciidoc/sqs.adoc),
  [`SqsListenerObservation.java`](https://github.com/awspring/spring-cloud-aws/blob/98f0f4dedf9b6fae9b28117adc9560fe4362aa1c/spring-cloud-aws-sqs/src/main/java/io/awspring/cloud/sqs/support/observation/SqsListenerObservation.java)

상세 조사 기록은 `bluetape4k-wiki`의
`research/2026-08-27-sqs-observation-context-propagation.md`에 보존한다. 구현 시점에 upstream
head가 달라졌다면 pinned 계약과 최신 source의 차이를 다시 확인하되, 명시적 설계 변경 없이
live head의 동작을 자동 채택하지 않는다.

## 2. 목표와 범위

### 목표

1. receive/poll, message 또는 batch process, acknowledgement·visibility I/O를
   Observation으로 측정한다.
2. process observation 안에서 conversion, handler, retry 판정과 downstream coroutine
   호출에 현재 observation을 전파한다.
3. 성공, 재시도 후 성공, 예외, cancellation, partial acknowledgement를 안정적인
   low-cardinality outcome으로 구분한다.
4. 사용자 `ObservationRegistry`, convention, ordered context customizer와 factory 교체를
   지원한다.
5. 기본 tag를 allowlist로 제한하고 payload·secret·receipt handle·전체 queue URL을
   기록하지 않는다.
6. 기존 public constructor와 disabled 동작을 유지하고 실제 Observation runtime 활성화 시
   자동 생성된 legacy listener metric만 억제한다.
7. Floci와 in-memory `ObservationHandler`로 실제 AWS 계정 없이 lifecycle과 전파를
   검증한다.

### 범위 밖

- OpenTelemetry SDK, exporter, collector와 sampling 자동 구성
- AWS X-Ray SDK 또는 vendor-specific tracer 자동 구성
- inbound SQS message attribute에서 W3C/B3 trace carrier를 추출하는 propagation adapter
- `SqsListenerInterceptor`의 전체 대체 또는 제거
- operations-level `MicrometerSqsOperations` timer/counter 제거
- listener backpressure, FIFO dispatch, queue attributes, heartbeat 주기·정책 변경
- Spring Integration 또는 Spring Cloud AWS observation API 전체 복제
- message body, arbitrary attribute/header 또는 exception message tag 지원
- 실제 AWS 계정, IAM, cross-account와 운영 exporter smoke test

애플리케이션에 Micrometer Tracing handler가 있으면 새 observation은 span으로 표현되지만,
이 기능 자체는 tracing SDK를 필수 dependency로 만들지 않는다. inbound remote parent
추출은 별도 후속 이슈로 다룬다.

#453은 heartbeat 생성, 주기, cancellation과 visibility 정책을 소유한다. #473은 그 정책을
바꾸지 않고 이미 실행되는 `ChangeMessageVisibility` I/O를 acknowledgement observation으로
감싸는 역할만 소유한다.

## 3. 대안과 선택

### A안 — Hybrid lifecycle

`process`를 메시지 또는 batch의 중심 observation으로 사용하고 `receive`와
`acknowledgement`를 독립 I/O observation으로 둔다. conversion과 retry는 process event와
outcome으로 표현한다. span 수를 제한하면서 handler downstream context와 ACK 실패를
분리할 수 있다. **이 안을 선택한다.**

### B안 — 모든 phase를 독립 observation으로 분리

poll, receive, conversion, handler, retry attempt, delete, visibility heartbeat마다 별도
observation을 만들면 원인 분리는 쉽다. 그러나 long polling과 내부 retry가 많은 listener에서
span 수와 비용이 급증하고, conversion·handler의 하나인 처리 수명이 조각난다. 선택하지
않는다.

### C안 — 기존 `SqsListenerInterceptor`만으로 구현

기존 hook과 호환성이 가장 높지만 `beforeHandle`이 반환된 뒤 호출자 coroutine context를
변경할 수 없다. thread-local scope를 field/map에 저장하면 suspension 이후 다른 스레드에서
닫힐 수 있어 Micrometer의 scope 계약을 위반한다. 선택하지 않는다.

### D안 — Spring Cloud AWS observation adapter를 직접 의존

upstream convention 일부를 재사용할 수 있지만 awspring 비의존 원칙, 현재 listener
lifecycle과 API 이름이 충돌한다. raw AWS message context와 dependency graph도 함께 들어와
범위를 넓힌다. 선택하지 않는다.

## 4. public API

### 4.1 독립 속성

기존 `SqsProperties.Listener`에 필드를 추가하지 않고 별도 configuration properties를
등록한다.

```kotlin
@ConfigurationProperties(prefix = "bluetape4k.aws.sqs.observation")
data class SqsObservationProperties(
    val enabled: Boolean = false,
) : Serializable {
    companion object {
        @JvmField
        val serialVersionUID: Long = 1L
    }
}
```

사용 예시는 다음과 같다.

```yaml
bluetape4k:
  aws:
    sqs:
      observation:
        enabled: true
```

`enabled=false`이거나 property가 없으면 observation 관련 bean과 container 연결이 모두
없다. `SqsProperties.Listener`의 기존 JVM constructor descriptor와 serialization 계약은
그대로 유지한다.

### 4.2 정제한 metadata와 context

공개 customization 표면은 원본 `SqsReceivedMessage` 대신 정제한 immutable metadata를
사용한다.

```kotlin
enum class SqsObservationStage {
    RECEIVE,
    PROCESS,
    ACKNOWLEDGEMENT,
}

enum class SqsObservationOutcome {
    UNKNOWN,
    SUCCESS,
    RETRIED,
    ERROR,
    CANCELLED,
    PARTIAL,
}

enum class SqsObservationDelivery {
    UNKNOWN,
    FIRST,
    REDELIVERED,
}

class SqsObservationMetadata internal constructor(
    val listenerId: String,
    val queueName: String,
    val stage: SqsObservationStage,
    val batch: Boolean,
    val messageId: String? = null,
    val messageGroupId: String? = null,
    val messageDeduplicationId: String? = null,
    val initialAttempt: Int? = null,
    val batchSize: Int = 0,
    val acknowledgementAction: SqsAcknowledgementAction? = null,
    val delivery: SqsObservationDelivery = SqsObservationDelivery.UNKNOWN,
) : Serializable {
    override fun toString(): String =
        "SqsObservationMetadata(listenerId=$listenerId, queueName=$queueName, " +
            "stage=$stage, batch=$batch, batchSize=$batchSize, " +
            "acknowledgementAction=$acknowledgementAction, delivery=$delivery)"

    companion object {
        @JvmField
        val serialVersionUID: Long = 1L
    }
}

class SqsObservationContext internal constructor(
    val metadata: SqsObservationMetadata,
) : Observation.Context() {
    var outcome: SqsObservationOutcome = SqsObservationOutcome.UNKNOWN
        internal set

    var retryCount: Int = 0
        internal set

    var acknowledgementSuccessCount: Int = 0
        internal set

    var acknowledgementFailureCount: Int = 0
        internal set

    val attempt: Int?
        get() = currentAttempt

    internal var currentAttempt: Int? = metadata.initialAttempt

    internal var failureStage: String? = null
}
```

metadata 불변 조건은 다음과 같다.

- `queueName`은 resolved queue URL을 `URI`로 해석한 뒤 마지막 raw path segment가
  `(?=.{1,80}$)[A-Za-z0-9_-]+(?:\.fifo)?`를 만족할 때만 사용한다. percent decoding은 하지
  않는다.
  blank, `%`, `/`, query, fragment, user-info, host, account ID 또는 malformed input에서 온 값은
  contextual name에 재사용하지 않고 `unknown`으로 정규화한다. 동일 resolved URL의 정제
  결과는 container에서 한 번 계산해 재사용한다.
- `batch=true`이면 batch 크기가 1이어도 message ID, group ID, deduplication ID를 모두
  `null`로 고정한다. 단건 process/acknowledgement만 식별자를 가질 수 있다.
- `attempt`는 `currentAttempt`를 통해 retry마다 갱신한다. `RECEIVE`와 `UNKNOWN` stage에서는
  `null`을 허용하고 attempt tag를 생략한다. `PROCESS`와 `ACKNOWLEDGEMENT` stage에서는 항상
  1 이상이어야 하며, 이 불변식을 만족하지 않으면 observation 시작 전에 fail fast한다.
  receive count가 없거나 파싱에 실패하면 `delivery=UNKNOWN`, 1이면 `FIRST`, 2 이상이면
  `REDELIVERED`다.
- body, receipt handle, arbitrary message/system attribute map과 exception message를 담는
  필드는 제공하지 않는다.
- `toString()`은 message/FIFO ID와 exact attempt를 출력하지 않는다.

`Observation.Context`가 제공하는 generic key/value 저장소는 Micrometer 확장 계약 때문에
제거할 수 없다. 기본 구현은 금지 데이터를 넣지 않으며, 사용자가 customizer에서 외부
데이터를 추가해 생기는 cardinality와 보안 책임은 사용자에게 있다.

### 4.3 convention, customizer, factory

```kotlin
interface SqsObservationConvention : ObservationConvention<SqsObservationContext> {
    val stage: SqsObservationStage

    override fun supportsContext(context: Observation.Context): Boolean =
        context is SqsObservationContext && context.metadata.stage == stage
}

fun interface SqsObservationContextCustomizer {
    fun customize(context: SqsObservationContext)
}

fun interface SqsObservationFactory {
    fun createNotStarted(
        context: SqsObservationContext,
        registry: ObservationRegistry,
    ): Observation
}
```

runtime은 모든 `Ordered` customizer를 순서대로 정확히 한 번 적용한 뒤 factory를 호출하고,
반환된 observation의 `context`가 입력 instance와 동일한지 검증한다. factory는 supplied
registry에 연결한 not-started observation을 반환하며 `start/error/stop`은 runtime만
소유한다. 사용자가 `SqsObservationFactory` bean을 제공하면 default factory는
`@ConditionalOnMissingBean`으로 back-off한다. factory 교체 여부와 무관하게 property,
registry, handler와 Context Propagation prerequisite는 동일하다.

사용자 convention bean은 `stage`별 default convention보다 우선한다. 같은 stage의 사용자
convention이 둘 이상이면 시작 시 configuration error로 실패한다. convention은 observation
start와 stop 시점에 context를 다시 읽으므로 `outcome`, `attempt`, count의 terminal 값이 tag에
반영된다.

factory 교체 계약은 다음과 같다.

- 입력 context를 그대로 observation context로 사용한다.
- 반환 시 observation은 시작되지 않은 상태여야 한다. runtime은 정확히 한 번 `start()`한다.
- `Observation.NOOP` 반환은 허용하며 runtime은 scope, context capture 객체, retry event를 만들지
  않고 business block을 직접 실행한다.
- body, receipt handle과 임의 attribute를 다시 조회하기 위한 원본 message 접근은 제공하지
  않는다.

공개 `SqsObservationProperties`와 `SqsObservationMetadata`는 `Serializable`과 명시적
`serialVersionUID`를 갖는다. `SqsObservationContext`는 coroutine 수명 객체이므로
serialization 계약을 제공하지 않는다. 다른 context는 factory 반환 즉시 거부한다. runtime이
`start()`하고 scope를 연 뒤 supplied registry의 current observation이 반환 observation과
같지 않으면 다른 registry에 연결한 contract 위반으로 거부한다. Micrometer public API에는
started 상태 조회가 없으므로 이미 시작한 observation을 runtime에서 사전 검출한다고 약속하지
않는다. not-started 반환은 public KDoc와 factory contract test로 강제하며 위반한 사용자
factory의 동작은 지원하지 않는다.

manual에는 PROCESS stage convention 교체, `@Order`가 있는 customizer 두 개, supplied
registry를 사용하는 user factory와 prerequisite 누락 fallback 예시를 제공한다. 예시 source는
별도 복사하지 않고 compile test fixture에서 문서로 포함해 API drift를 차단한다.

기본 구현 class는 internal로 두고 위 interface와 context만 public ABI로 제공한다.

## 5. observation 이름과 semantic tag

### 5.1 이름

| 단계 | observation name | contextual name |
| --- | --- | --- |
| receive/poll | `bluetape4k.aws.sqs.receive` | `<queue> receive` |
| message 또는 batch 처리 | `bluetape4k.aws.sqs.process` | `<queue> process` |
| ACK/NACK/visibility | `bluetape4k.aws.sqs.acknowledgement` | `<queue> <action>` |

Spring Cloud AWS의 서로 다른 두 기본 이름을 alias로 제공하지 않는다. 위 값은 public
constant와 contract test로 고정한다.

### 5.2 low cardinality

기본 convention이 metric과 trace에 공통으로 넣는 tag는 allowlist 방식이다.

| key | 값 경계 |
| --- | --- |
| `messaging.system` | 항상 `sqs` |
| `messaging.operation` | `receive`, `process`, `acknowledgement` |
| `messaging.destination.name` | queue name 또는 `unknown`; URL 금지 |
| `bluetape4k.aws.sqs.listener.id` | 설정된 listener ID |
| `bluetape4k.aws.sqs.outcome` | `unknown`, `success`, `retried`, `error`, `cancelled`, `partial` |
| `bluetape4k.aws.sqs.ack.action` | `ack`, `nack`, `change_visibility`, 해당 없음은 `none` |
| `bluetape4k.aws.sqs.batch.size` | `0`, `1`, `2-5`, `6-10` bucket |
| `bluetape4k.aws.sqs.delivery` | `first`, `redelivered`, 누락·파싱 실패·batch/receive는 `unknown` |
| `bluetape4k.aws.sqs.failure.stage` | `none`, `receive`, `conversion`, `handler`, `acknowledgement`, `observation` |

`listener.id`와 queue name은 운영자가 정하는 bounded configuration 값이라는 전제다.
빈 값은 `unknown`으로 바꾸며 message에서 동적으로 생성하지 않는다.

### 5.3 high cardinality

trace 전용 key는 단건 process/acknowledgement에만 추가한다.

- `messaging.message.id`
- `messaging.sqs.message.group.id`
- `messaging.sqs.message.deduplication.id`
- `bluetape4k.aws.sqs.attempt`

batch에서는 `batchSize=1`이어도 개별 ID를 join하거나 반복 tag로 만들지 않는다. business
exception 원본은 `Observation.error`나 public context에 넘기지 않는다. runtime은 message,
cause와 stack trace가 없는 internal redacted telemetry exception과 bounded
`failure.stage`만 handler에 제공하고, 원래 exception은 변경 없이 호출자에게 재전파한다.
따라서 기본 handler도 payload를 포함할 수 있는 exception message나 stack trace를 exporter로
내보낼 수 없다. 사용자 factory/customizer가 별도 데이터를 추가하면 그 보안 책임은
사용자에게 있다.

## 6. coroutine lifecycle

### 6.1 공통 around 실행기

container에는 nullable `SqsObservationRuntime`만 전달한다. runtime이 없거나 registry가 정확히
`ObservationRegistry.NOOP`이면 context, capture 객체, scope와 event를 만들지 않고 business
block을 직접 실행한다. 활성 runtime은 다음 순서를 소유한다.

```kotlin
val observation = factory.createNotStarted(context, observationRegistry)
if (observation === Observation.NOOP) return block()

var primary: Throwable? = null
var started = false
try {
    observation.start()
    started = true
    val capturedContext = observation.openScope().use {
        observationRegistry.asContextElement()
    }
    return withContext(capturedContext) { block() }
        .also { context.outcome = successOutcome(context) }
} catch (e: CancellationException) {
    primary = e
    context.outcome = SqsObservationOutcome.CANCELLED
    throw e
} catch (e: Throwable) {
    primary = e
    context.outcome = SqsObservationOutcome.ERROR
    throw e
} finally {
    if (started) {
        finishObservationPreservingPrimary(observation, context, primary)
    }
}
```

실제 종료 helper는 business 원본을 `Observation.error`에 넘기지 않고 redacted telemetry
exception만 기록한다. `error()`나 `stop()`이 던지면 다음 우선순위를 지킨다.

1. handler/conversion/receive/ACK 오류가 있으면 그것이 primary이고 관측성 종료 오류는
   suppressed다.
2. `CancellationException`이 있으면 그것이 primary이며 종료 오류를 suppressed로 붙이고
   동일 instance를 재전파한다.
3. foreground receive/process/ACK business block이 성공했지만 `stop()`이 실패하면 stop
   오류가 primary가 되어 기존 fail-closed retry/redelivery 경로로 전달된다. #453이 소유한
   background heartbeat observation의 `error()`/`stop()` 실패에는 이 규칙을 적용하지 않고,
   bounded diagnostic을 기록한 뒤 기존 heartbeat 결과와 handler 결과를 바꾸지 않는다.
4. factory, customizer, `start()`, scope 또는 context capture 중 오류는 setup primary다.
   시작된 observation만 정확히 한 번 종료하고 나머지 lifecycle은 실행하지 않는다.

`Observation.Scope`는 `use` 블록 안에서 같은 스레드에 열고 닫으며 suspend block을 직접
감싸지 않는다. `withContext`가 coroutine resume마다 캡처한 thread-local 상태를 설치하고
복원한다. `CancellationException`은 일반 failure로 삼키지 않고 즉시 재전파한다.

factory/customizer/convention이 observation 시작 전에 던진 예외는 listener 처리 실패로
간주한다. 관측성 오류를 숨기고 business handler를 계속 실행하면 enabled 환경과 disabled
환경의 의미가 달라지므로 fail closed한다. `Observation.NOOP`은 정상 no-op으로 취급한다.
receive/process/batch/ACK cancellation cleanup 전체를 `NonCancellable`에서 수행한다. 여기에는
batch ACK rollback, `IN_FLIGHT` 상태 해제, 대기 `Deferred` 완료, interceptor `finally`와
cancellation hook, observation 종료가 모두 포함된다. ACK mutex 안에서 먼저
`state=PENDING`, `inFlight=null` rollback을 원자적으로 확정하고 mutex를 놓은 뒤 해당
`Deferred.complete()`로 waiter를 깨우며, 그 다음 hook → observation 종료 순서로 끝낸다.
각 cleanup 오류는 원래 동일한
`CancellationException`에 suppressed로 붙인다. cleanup 실패 때문에 waiter가 미완료로
남거나 ACK 재호출이 영구 대기해서는 안 된다. parent observation은 성공, 오류,
cancellation과 setup failure 뒤 모두 원래 값으로 복원한다.

### 6.2 receive

queue URL 해석이 끝난 뒤 실제 `operations.receive` 호출만 receive observation으로 감싼다.
queue not found resolution/retry는 AWS receive I/O가 아니므로 observation 범위 밖으로 두고,
새 bounded diagnostic `BT4K-SQS-OBS-201`과 운영 문서에서 구분한다. empty poll도
`batch.size=0`, `outcome=success`로 한 번 종료한다. receive 실패는 `error`, container stop
cancellation은 `cancelled`다.

receive observation은 poller I/O의 수명이며 handler job의 parent로 보관하지 않는다.
동시에 받은 메시지가 비동기 handler로 분기된 뒤 이미 종료된 receive span을 parent로
재사용하지 않는다.

### 6.3 단건 process와 retry

단건 process observation은 첫 attempt 전에 시작하고 다음을 모두 포함한다.

1. `SqsListenerMethodInvoker` argument conversion
2. reflective suspend/non-suspend handler 호출
3. listener retry 판정과 backoff
4. 자동 acknowledgement 호출

intermediate attempt failure가 재시도될 때 `retryCount`와 `currentAttempt`를 갱신한다.
첫 retry에서만 `Observation.Event.of("retry")`를 추가하고 이후 retry는 count만 올린다.
`maxAttempts` 값과 무관하게 event 수는 process observation당 최대 1이다. 별도 attempt
observation은 만들지 않는다.
최종 성공이며 retry가 한 번 이상 있었으면 `RETRIED`, 첫 시도 성공이면 `SUCCESS`다.
최종 handler/conversion 실패는 `ERROR`, container shutdown은 `CANCELLED`다.

기존 정책처럼 acknowledgement가 이미 terminal이면 handler 오류 뒤에도 추가 retry를 하지
않는다. 이 경우 실제 acknowledgement 결과가 process outcome보다 우선하지 않으며 process는
handler 오류를 `ERROR`로 기록한다.

### 6.4 batch process와 partial acknowledgement

batch는 receive response 하나의 현재 pending 집합에 process observation 하나를 만든다.
retry마다 새 span을 만들지 않고 `retryCount`, `attempt`와 retry event를 갱신한다.
개별 message ID/group/deduplication ID는 batch context에 넣지 않는다.

`SqsBatchAcknowledgementResult`가 `PARTIAL_FAILURE`이면 acknowledgement context에 성공·실패
개수를 기록하고 ACK outcome을 `PARTIAL`로 확정한다. manual partial ACK 뒤 handler가 정상
반환하면 process outcome은 `SUCCESS` 또는 retry가 있었다면 `RETRIED`다. ACK child의
`PARTIAL`을 process 오류로 승격하거나 stale pending 상태로 남기지 않는다. pending 항목을
listener가 재시도해 끝까지 실패한 경우에만 process outcome은 `ERROR`다. cancellation은
기존 `onBatchCancellation` hook을 유지하면서 process observation도 `CANCELLED`로 종료한다.

### 6.5 acknowledgement와 visibility

`DefaultSqsAcknowledgement.runAcknowledgement`, `DefaultSqsBatchAcknowledgement.execute`와
heartbeat visibility extension의 실제 operations 호출만 acknowledgement observation으로
감싼다. duplicate/wait/already-terminal 분기와 실제 I/O가 없는 호출은 observation을 만들지
않는다. action은 `ACK`, `NACK`, `CHANGE_VISIBILITY` 중 하나다.

process context 안에서 호출된 자동 또는 수동 acknowledgement는 호출 시점의 현재 process
observation의 child가 된다. handler가 acknowledgement 객체를 별도 비동기 작업으로 유출한
경우 cached parent를 보관하거나 종료된 process를 재사용하지 않는다. invocation coroutine에
구조적으로 observation context가 전파되어 있으면 그 현재 observation의 child, 없으면 새
root다. #473은 명시적 parent/context 전달 API를 추가하지 않는다.

heartbeat는 기존 주기와 cancellation 정책을 바꾸지 않고 각 실제 visibility I/O를
`CHANGE_VISIBILITY` observation으로 기록한다. background heartbeat에 현재 process가 없으면
root가 되며 stale parent를 연결하지 않는다. 지연·오류·cancellation과 parent 복원을
회귀 테스트한다. heartbeat lifecycle 자체는 #453, 기존 visibility 호출을 감싸는
observation 경계만 #473의 소유다. foreground observation과 달리 heartbeat observation의
`error()`/`stop()` 실패는 `BT4K-SQS-OBS-202` bounded diagnostic으로 기록하고 무시해,
#453이 정한 visibility 연장 결과나 listener handler 결과를 바꾸지 않는다.

## 7. 자동 설정과 기존 metric 공존

새 `SqsObservationAutoConfiguration`은 Spring Boot observation registry/handler 자동 설정
뒤이자 `SqsAutoConfiguration` 앞에 평가한다. 다음 조건을 모두 만족할 때만 내부
`SqsObservationActivation` marker와 `SqsObservationRuntime`을 만든다.

- `@ConditionalOnAwsEnabled`
- SQS 자체 `bluetape4k.aws.sqs.enabled=true`
- `bluetape4k.aws.sqs.observation.enabled=true`
- `ObservationRegistry`와 `io.micrometer.context.ContextSnapshot` class 존재
- `ObservationRegistry` bean 존재
- registry가 정확히 `ObservationRegistry.NOOP`이 아님
- `ObjectProvider<ObservationHandler<*>>`로 조회한 Spring bean 중 sanitized PROCESS probe
  context를 지원하는 handler가 하나 이상 존재

사용자 `SqsObservationFactory`가 있으면 기본 factory만 back-off하고 위 활성화 조건은
그대로 적용한다. disabled/no-registry/no-handler/no-context-propagation 상태에서 user factory
bean만 등록해도 runtime은 활성화되지 않는다. `ObservationRegistryCustomizer`나
`registry.observationConfig()` 호출로만 직접 등록하고 Spring bean으로 노출하지 않은 handler는
활성화 prerequisite로 인정하지 않으며 legacy listener meter를 유지한다. handler 구성은
startup 평가 결과이며 변경에는 restart/redeploy가 필요하다.

`ObservationRegistry`, `ContextSnapshot` 등 optional type은 eager-loaded
`SqsAutoConfiguration`, BPP constructor/signature와 outer conditional class에 노출하지 않는다.
name-only outer guard 아래 nested auto-configuration에서만 참조해 class가 없을 때 linkage
error가 없게 한다. `SqsAutoConfiguration`과 BPP/container는
`ObjectProvider<SqsObservationRuntime>` 또는 nullable internal runtime만 받아 없으면 기존
block을 직접 실행한다.

`SqsListenerAnnotationBeanPostProcessor`는 현재 public constructor descriptor를 유지한다.
nullable runtime을 받는 internal setter를 추가하되 기존 6-argument constructor와 runtime
미설정 경로는 기존 동작을 직접 유지한다. `SqsProperties.Listener`와 기존 interceptor
interface에는 새 abstract member를 추가하지 않는다.

`SqsAutoConfiguration.sqsListenerAnnotationBeanPostProcessor(Environment, SqsProperties,
SqsOperations, SqsMessageListenerContainerRegistry, ObjectProvider<SqsMessageConverter>,
ObjectProvider<SqsListenerInterceptor>)`의 public JVM method descriptor도 그대로 유지한다.
따라서 이 `@Bean` 메서드에 runtime 파라미터를 추가하지 않는다. optional nested observation
configuration의 전용 `BeanPostProcessor`가 생성된 BPP의 internal setter에 runtime을 연결한다.
현재 JVM에서 public으로 방출되는 `SqsMessageListenerContainer`의 5-argument constructor
descriptor도 유지하며 runtime constructor 파라미터를 추가하지 않는다. BPP가 container를
만든 직후 internal setter로 같은 runtime을 전달한다. 두 descriptor는 `javap`/binary
compatibility 회귀 검증 대상으로 고정하고, Kotlin `internal`은 비지원 ABI라는 주장으로
기존 emitted descriptor 제거를 정당화하지 않는다.

`SqsMicrometerAutoConfiguration.micrometerSqsListenerInterceptor`는 property가 아니라
`SqsObservationActivation` marker 부재를 조건으로 한다. 따라서 prerequisite가 빠졌거나 빈
registry면 기존 `bluetape4k.aws.sqs.listener` meter가 유지된다. 사용자가 직접 등록한
`MicrometerSqsListenerInterceptor`나 다른 `SqsListenerInterceptor`는 제거하지 않는다.
그 경우 중복 측정은 명시적인 사용자 선택이다. operations wrapper bean과 기존
`MicrometerSqsOperations` meter는 Observation 활성화 여부와 관계없이 유지한다.

`ApplicationContextRunner`와 `FilteredClassLoader`는 Spring Boot observation auto-config
뒤/SQS 앞 ordering, property, registry, NOOP registry, handler, context-propagation class,
user factory의 positive/negative 조합을 고정한다. prerequisite 불충족은 startup error나
warning을 만들지 않고 `ConditionEvaluationReport` negative match와 다음 bounded reason으로
확인한다: `disabled`, `registry-missing`, `registry-noop`, `handler-missing`,
`context-propagation-missing`. user factory back-off는 `user-factory`로 표시한다.

여기서 정의하는 새 observation activation/queue-resolution 진단은 payload, queue URL,
account ID와 exception text를 포함하지 않는다. #473은 기존 container 로그를 일괄 정제하는
cleanup 이슈가 아니며, 새 코드가 기존 raw log field를 observation context나 새 진단에
복제하지 않는 것을 검증한다.

| Code | 의미 | 확인 위치 |
| --- | --- | --- |
| `BT4K-SQS-OBS-101` | property는 enabled지만 observation prerequisite가 불충족 | condition report의 bounded reason |
| `BT4K-SQS-OBS-201` | receive observation 시작 전 queue URL resolution이 실패 | 기존 container error 경로와 manual troubleshooting |
| `BT4K-SQS-OBS-202` | background heartbeat observation의 `error()`/`stop()`이 실패 | bounded warning과 manual troubleshooting |

## 8. 실패 모드와 안전한 동작

| 실패 모드 | 요구 동작 |
| --- | --- |
| `ObservationRegistry`, supporting handler 또는 Context Propagation class 없음 | runtime marker가 생성되지 않고 기존 listener와 legacy listener meter가 그대로 동작한다. class linkage 오류가 없어야 한다. |
| property enabled지만 registry bean 없음 | runtime을 만들지 않고 직접 기존 listener 경로를 실행한다. 애플리케이션 시작을 막거나 warning을 남기지 않고 condition report로 진단한다. |
| disabled/runtime 없음/NOOP registry | context, scope, capture 객체, `withContext`, event를 만들지 않는 직접 fast path를 사용한다. |
| user factory/convention/customizer가 예외 발생 | 현재 receive/process/ACK 작업을 실패시키고 기존 retry/cancellation 경로로 전달한다. payload를 로그에 남기지 않는다. |
| coroutine suspension 뒤 다른 thread에서 재개 | 캡처한 context element가 현재 observation을 설치하고 복원한다. 직접 연 scope를 다른 thread에서 닫지 않는다. |
| handler cancellation | outcome을 `cancelled`로 기록하고 observation을 한 번 stop한 뒤 같은 `CancellationException`을 재전파한다. raw cancellation은 handler에 전달하지 않는다. |
| handler 또는 conversion exception | redacted telemetry error와 `error` outcome을 기록하며 원래 exception과 기존 retry/redrive 판단을 보존한다. |
| retry 뒤 성공 | process observation 하나만 존재하고 retry event와 `retried` outcome을 남긴다. |
| batch partial acknowledgement | acknowledgement outcome은 `partial`, 성공/실패 개수는 context에 기록하며 개별 ID 목록은 tag로 만들지 않는다. |
| acknowledgement I/O 실패 | acknowledgement observation은 `error`; process의 기존 retry/error visibility 동작은 유지한다. |
| observation error/stop 중 primary가 이미 존재 | 종료 오류를 primary의 suppressed exception으로 붙이고 원래 handler/receive/ACK 오류 또는 cancellation을 보존한다. |
| business 성공 뒤 observation stop 실패 | stop 오류를 primary로 전달해 fail-closed retry/redelivery 정책을 적용한다. |
| full queue URL 또는 secret header가 입력에 존재 | 기본 context/tag에는 queue name만 들어가며 임의 header/body/receipt는 접근 경로 자체가 없다. |
| Observation과 legacy listener metric 동시 자동 생성 | 실제 activation marker 조건으로 차단한다. 빈 registry/handler 없음이면 legacy metric을 보존하고 사용자 수동 등록도 유지한다. |
| container stop과 in-flight process 경쟁 | 각 started observation은 해당 coroutine의 `finally`에서 정확히 한 번 stop하고 기존 generation cancellation/join 순서를 바꾸지 않는다. |

## 9. 호환성과 migration

- 기본 `enabled=false`이므로 기존 애플리케이션의 observation bean graph, meter 이름과
  listener timing은 바뀌지 않는다. 다만 `context-propagation:1.2.1`은 disabled 상태에서도
  module의 새 transitive runtime dependency가 된다.
- `context-propagation`은 module 내부 구현 dependency이며 public signature에 해당 type을
  노출하지 않는다. dependency graph contract test로 존재와 pinned BOM version을 고정한다.
- 기존 `SqsProperties`, `SqsProperties.Listener`,
  `SqsListenerAnnotationBeanPostProcessor` constructor를 binary compatibility test로
  고정한다.
- `SqsListenerInterceptor`의 기존 default method와 호출 순서는 유지한다. Observation
  runtime이 interceptor를 호출하는 business lifecycle 바깥 순서를 바꾸지 않는다.
- Observation을 활성화한 사용자는 자동 listener timer/counter 대신 Observation handler가
  만든 meter/span을 사용한다. operations meter는 계속 존재한다.

| 상태 | legacy listener meter | operations meter | 새 observation |
| --- | --- | --- | --- |
| property false/missing | 유지 | 유지 | 없음 |
| enabled, prerequisite 불충족 | 유지 | 유지 | 없음; condition report negative match |
| enabled, activation marker 존재 | 자동 bean만 억제 | 유지 | receive/process/ACK·visibility |
| enabled, legacy interceptor 수동 등록 | 사용자 선택에 따라 중복 가능 | 유지 | 활성 |

활성화와 rollback은 runtime rebind가 아니라 restart/redeploy가 필요하다. canary listener에서
먼저 활성화하고 old/new meter, observation count, 처리 지연, redelivery, DLQ를 함께 비교한다.
rollback은 receive 중지 → in-flight drain → `STOPPING_RECEIVE → DRAINING → STOPPED` 확인 →
property false 배포 → 재시작 순서로 수행한다. schema migration이나 persisted state는 없다.
dashboard/alert는 새 meter가 실제 생성된 것을 확인한 뒤 전환하며, 빈 registry에서 legacy
meter가 사라지지 않는 것을 canary acceptance로 둔다.

## 10. 구현 경계와 예상 변경 파일

예상 production 변경은 `aws-spring-boot` 안으로 제한한다.

- `aws-spring-boot/build.gradle.kts`
  - `implementation(bt4k.micrometer.context.propagation)` 추가
- `.../sqs/SqsObservationProperties.kt`
- `.../sqs/SqsObservationContext.kt`
- `.../sqs/SqsObservationConvention.kt`
- `.../sqs/SqsObservationFactory.kt`
- `.../sqs/SqsObservationAutoConfiguration.kt`
- `.../sqs/SqsObservationRuntime.kt` 또는 동등한 internal lifecycle helper
- `.../sqs/SqsAutoConfiguration.kt`
- `.../sqs/SqsMicrometerAutoConfiguration.kt`
- `.../sqs/SqsListenerAnnotationBeanPostProcessor.kt`
- `.../sqs/SqsMessageListenerContainer.kt`
- `.../sqs/SqsAcknowledgement.kt`
- `.../sqs/SqsBatchAcknowledgement.kt`
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`
- `docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`
- `docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md`
- `docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md`
- root와 `aws-spring-boot`의 `README.md`/`README.ko.md` 요약·manual link

기존 utility와 lifecycle에 자연스럽게 통합할 수 있으면 파일 수를 줄인다. 새 module이나
관리되지 않은 외부 dependency는 추가하지 않는다. public API KDoc와 EN/KO manual은 opt-in,
privacy allowlist, inbound carrier 미지원, detached ACK parent 규칙, metric migration,
restart/rollback을 동일한 구조로 설명한다. README는 상세 절을 복제하지 않고 manual로
연결한다. customization 예시는 compile test fixture와 같은 source를 사용한다.

## 11. TDD와 검증 계획

### 11.1 unit·contract test

1. 기본/사용자 convention의 이름, contextual name, low/high cardinality key/value
2. queue URL의 query, fragment, user-info, host, account ID, encoded separator와 malformed 입력을
   contextual name/tag에서 제거하고 정제 결과를 한 번만 계산하는지 확인
3. body, receipt handle, full URL, arbitrary header, exception message/stack과 public
   `toString()` 식별자 노출 금지
4. registry/handler bean/context-propagation class/property/user factory별 auto-configuration
   matrix와 `FilteredClassLoader` linkage 경계; registry customizer로만 등록한 handler는
   비활성이고 legacy meter가 유지되는지 확인
5. active marker 기준의 legacy listener meter 보존/억제, operations meter 유지와 condition
   report reason
6. user factory back-off, 동일 registry/context identity, not-started 계약, ordered customizer,
   stage별 convention과 ambiguous convention fail-fast
7. public API snippet compile, `Observation.Event.of("retry")`, serialization UID, 기존 BPP와
   properties constructor, `SqsAutoConfiguration` Bean method 및 container constructor JVM
   descriptor binary compatibility
8. disabled/runtime 없음/NOOP observation direct fast path에 capture 객체, scope, `withContext`, event
   allocation이 없는지 확인
9. 새 transitive `context-propagation` dependency와 public signature 비노출 확인

### 11.2 coroutine lifecycle test

in-memory `ObservationRegistry`와 recording `ObservationHandler`를 사용해 다음을 검증한다.

1. suspend 전후와 dispatcher 전환 뒤에도 handler에서 같은 current observation 확인
2. downstream child observation이 process observation의 child인지 확인
3. 성공, retry 후 성공, conversion exception, handler exception, cancellation outcome
4. observation start/stop가 각 lifecycle에서 정확히 한 번인지 확인
5. setup/start/scope/error/stop 실패와 handler 오류 조합별 primary/suppressed 우선순위,
   cancellation cleanup이 동일 `CancellationException`을 보존하는지 확인
6. 취소된 batch ACK의 waiter 완료, `IN_FLIGHT` rollback, 재호출 가능, interceptor hook과
   observation 종료가 `NonCancellable`에서 끝나고 cleanup 오류가 suppressed인지 확인;
   mutex 안에서 `PENDING`/`inFlight=null` rollback을 확정한 뒤 mutex 밖에서 waiter를
   깨우는 순서도 경쟁 테스트로 고정
7. 종료 뒤 parent observation 복원이 성공/error/cancel/setup failure 모두에서 성립하는지 확인
8. 단건 ACK/NACK/change visibility와 batch success/partial/error outcome; 실제 AWS I/O가 없는
   duplicate/wait/already-terminal 호출에는 observation이 없는지 확인
9. batch 크기 1을 포함해 모든 batch context에 개별 ID가 없고 manual partial ACK 뒤 정상
   handler의 process outcome이 `SUCCESS`/`RETRIED`인지 확인
10. retry 횟수와 exact attempt는 모두 갱신하되 retry event는 최대 한 개인지, RECEIVE/
    UNKNOWN의 null attempt tag 생략과 PROCESS/ACK의 1 이상 불변식이 유지되는지 확인
11. detached manual ACK는 invocation 시점의 current observation만 parent로 사용하고 stale
    process parent를 재사용하지 않는지 확인
12. #453 lifecycle을 변경하지 않은 heartbeat visibility I/O의 success/error/cancellation,
    지연과 parent 복원, observation `error()`/`stop()` 실패가 bounded diagnostic만 남기고
    visibility/handler 결과를 바꾸지 않는지 확인
13. receive empty/success/error/cancellation과 queue resolution diagnostic 구분

### 11.3 Floci integration test

`bluetape4k-testcontainers`의 `FlociServer`와 실제 listener container를 사용해 다음을 순차
검증한다.

1. 표준 queue 단건 message가 process observation 안에서 handler까지 전달됨
2. FIFO message group과 message ID가 high-cardinality trace key에만 존재함
3. handler가 child observation을 만들면 process observation 아래 연결됨
4. retry 후 성공과 error visibility 경로가 기존 delivery 동작을 보존함
5. manual batch partial acknowledgement가 `partial` outcome을 남기고 성공 항목만 삭제함
6. tag allowlist에 body, receipt handle, full queue URL과 사용자 secret attribute가 없음
7. heartbeat visibility 변경과 acknowledgement 실패가 실제 I/O observation으로 기록됨
8. redacted telemetry error에 원래 exception message, cause와 stack trace가 없음

observation 수 예산은 결정적으로 고정한다.

- empty poll: receive 1개
- 단건 `n`개: receive 1개 + process `n`개 + 실제 ACK/visibility I/O 횟수
- batch: receive 1개 + process 1개 + 실제 ACK/visibility I/O 횟수
- retry: process observation 추가 없음, retry event는 process당 최대 1개
- heartbeat: 실제 visibility I/O마다 acknowledgement observation 1개

활성/비활성 micro benchmark 또는 allocation counter는 direct fast path가 기존 기준선 대비
통계적으로 유의한 추가 allocation을 만들지 않는지 확인한다. 활성 경로는 위 count budget과
기존 listener timeout을 지키는 수준을 acceptance로 하고 절대 시간 threshold는 두지 않는다.

실제 AWS 계정 검증은 `N/A`다. Docker-backed test는 건강한 Colima와 기존
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 환경에서 순차 실행한다.

### 11.4 검증 명령

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests "io.bluetape4k.aws.spring.sqs.*Observation*"
./gradlew :bluetape4k-aws-spring-boot:test \
  -Dbluetape4k.aws.emulator=floci
./gradlew :bluetape4k-aws-spring-boot:compileKotlin \
  :bluetape4k-aws-spring-boot:compileTestKotlin
./gradlew detekt
```

## 12. 수용 기준

- [ ] Observation은 property, required class, non-NOOP registry와 supporting Spring handler
      bean이 모두 있을 때만 활성화되며 registry에만 직접 등록한 handler를 포함해 activation
      marker가 없으면 legacy meter가 유지된다.
- [ ] 사용자 `SqsObservationFactory`가 기본 factory만 대체하며 prerequisite를 우회하지
      않는다.
- [ ] process observation이 conversion, handler, retry 판정과 자동 acknowledgement를
      포함하고 coroutine dispatcher 전환 뒤에도 current observation이 유지된다.
- [ ] receive와 실제 acknowledgement/heartbeat visibility I/O만 각각 독립 observation으로
      기록되고 duplicate/wait/already-terminal 호출은 기록되지 않는다.
- [ ] 성공, retry 후 성공, exception, cancellation, partial acknowledgement가 서로 다른
      outcome으로 검증된다.
- [ ] message body, receipt handle, full queue URL, arbitrary/secret header와 exception
      message가 기본 tag에 없다.
- [ ] message ID, group ID, deduplication ID와 exact attempt는 high cardinality에만 있다.
- [ ] batch는 개별 message 식별자 목록을 tag로 만들지 않는다.
- [ ] batch 크기 1도 식별자를 노출하지 않고 metadata `toString()`도 high-cardinality 값을
      출력하지 않는다.
- [ ] business exception/cancellation 원본은 handler에 전달되지 않으며 redacted telemetry
      error에 message, cause와 stack trace가 없다.
- [ ] 성공/error/cancel/setup/stop 조합에서 primary throwable, suppressed 오류, parent 복원과
      exactly-once stop 계약이 유지된다.
- [ ] cancellation에서 batch ACK waiter, `IN_FLIGHT` rollback, interceptor hook과 observation
      cleanup이 `NonCancellable`로 완료되고, mutex 안에서 rollback한 뒤 waiter를 깨워 같은
      ACK를 다시 호출할 수 있다.
- [ ] retry event는 process당 최대 1개이고 observation 수가 정의된 count budget과 일치한다.
- [ ] Observation 활성화 시 자동 legacy listener metric은 억제되고 operations metric은
      유지된다.
- [ ] disabled/no-registry/no-context-propagation 경로와 기존 BPP/properties constructor,
      `SqsAutoConfiguration` Bean method, container constructor JVM descriptor가 유지된다.
- [ ] prerequisite negative reason, queue resolution diagnostic, canary 활성화와 restart 기반
      rollback이 EN/KO manual과 compile-verified customization example에 문서화된다.
- [ ] `context-propagation` transitive runtime dependency와 public signature 비노출을 contract
      test로 검증한다.
- [ ] Floci listener와 in-memory observation handler test가 통과한다.
- [ ] 실제 AWS 계정과 OpenTelemetry SDK/exporter 검증은 `N/A`로 명시된다.

## 13. 최초 독립 검토와 반영

2026-08-27의 동일 draft를 여섯 관점에서 독립 검토했다. 최초 합계는 `P0=0`, `P1=19`,
`P2=20`, `P3=1`이다.

| 관점 | P0 | P1 | P2 | P3 | 주요 반영 |
| --- | ---: | ---: | ---: | ---: | --- |
| Security/privacy | 0 | 2 | 2 | 0 | batch 식별자 전면 차단, queue name 정제, redacted error, 안전한 `toString()` |
| Performance | 0 | 0 | 4 | 1 | direct fast path, actual-I/O ACK 경계, retry event 상한, count budget과 queue name cache |
| Stability/cancellation | 0 | 4 | 2 | 0 | activation marker, primary/suppressed 우선순위, `NonCancellable` cleanup, mutable attempt와 partial 계약 |
| User/caller | 0 | 3 | 4 | 0 | compile-valid 기본값, detached ACK parent, transitive dependency와 manual/migration 계약 |
| Operator/ops | 0 | 6 | 5 | 0 | heartbeat visibility, handler prerequisite, optional class 격리, canary/drain/restart rollback |
| Developer/API | 0 | 4 | 3 | 0 | `createNotStarted`, `Event.of`, stage convention, serialization과 auto-config ordering |

P1 영향 관점인 Security/privacy, Stability/cancellation, User/caller, Operator/ops,
Developer/API는 수정본을 새 reviewer에게 재검토했다. Performance 관점의 P2/P3도 모두
수정했지만 최초 검토부터 P1이 없어 별도 재검토 gate 대상은 아니다.

| 재검토 관점 | 중간 결과 | 최종 결과 | 종결 근거 |
| --- | --- | --- | --- |
| Security/privacy | P0=0, P1=0, P2=1 | P0=0, P1=0 | 새 observation diagnostic만 민감정보 금지 범위로 한정해 P2 반영 |
| User/caller | P0=0, P1=0, P2=0, P3=0 | 동일 | public default, detached ACK, dependency/manual 계약 승인 |
| Stability/cancellation | v2 P0=0, P1=2, P2=1; v3 P0=0, P1=0, P2=3 | v4 P0=0, P1=0, P2=0, P3=0 | 전체 cancellation cleanup, handler prerequisite, heartbeat 오류 정책, rollback 순서, attempt 불변식 승인 |
| Operator/ops | P0=0, P1=0, P2=0, P3=0 | 동일 | 활성화, optional classpath, rollback, 진단 및 manual 계약 승인 |
| Developer/API | v2 P0=0, P1=1, P2=1 | v3 P0=0, P1=0, P2=0, P3=0 | 기존 Bean method와 container constructor JVM descriptor 보존 승인 |

최종 명세 승인 gate 합계는 `P0=0`, `P1=0`이다. 모든 blocking finding과 반영하기로 한
non-blocking finding을 명세에 통합했으므로 front matter를 `reviewed-design`으로 확정하고
사용자 명세 승인 단계로 이동한다.

## 14. DoD

- 설계 명세가 6개 독립 관점 검토를 통과하고 P0/P1 finding이 없다.
- 구현 계획과 TDD 순서는 설계 승인 뒤 별도 승인받는다.
- production code는 실패하는 test 이후에만 추가한다.
- targeted observation test, 전체 `aws-spring-boot` Floci test, compile과 detekt가
  fresh 실행에서 통과한다.
- public API와 auto-configuration metadata/KDoc, EN/KO manual과 README 연결이 reader contract를
  지킨다.
- commit은 Lore trailer를 포함한 한국어 decision record 형식이다.
- PR은 한국어 제목·본문, `debop` assignee, Issue #473의 milestone/labels를 유지하고 마지막
  H2를 `## DoD Status`로 둔다.
- human review는 1인 개발 정책에 따라 `N/A`; independent model review와 exact-head CI는
  별도 증거로 남긴다.
- 실제 AWS 검증은 `N/A`; Floci 검증이 emulator acceptance evidence다.
