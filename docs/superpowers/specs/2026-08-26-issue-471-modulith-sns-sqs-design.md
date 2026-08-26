---
title: "Issue #471 Spring Modulith SNS·SQS event externalization 설계"
issue: 471
epic: 500
status: reviewed-design
date: 2026-08-26
---

# Issue #471 Spring Modulith SNS·SQS event externalization 설계

## 결정 요약

`bluetape4k-aws-spring-boot`에 Spring Modulith 2.1의
`EventExternalizationTransport`를 구현하는 선택적 SNS·SQS transport와 SQS inbound
consumer를 추가한다. 기존 `SnsOperations`, `SqsOperations`, `@SqsListener`의
재시도·ack·lifecycle을 재사용하며 AWS client나 listener container를 새로 소유하지
않는다.

기능은 `bluetape4k.aws.modulith.events.enabled=true`일 때만 활성화한다. Spring
Modulith가 classpath에 없거나 사용자가 다른 `EventExternalizationTransport`를
제공하면 built-in producer transport만 back-off하고 독립적으로 활성화한 inbound
consumer는 유지한다. 외부화 대상은 임의 ARN·URL을 payload에서 받지 않고 설정에 등록한
논리 alias로만 찾는다.

outbound payload는 명시적 event type registry로 allowlist한 타입만 versioned
envelope로 직렬화한다. Spring Modulith publication은 SNS/SQS publish 응답이 실제로
완료된 뒤에만 완료된다. inbound SQS listener는 direct SQS body와 SNS→SQS
`Notification` body를 같은 envelope로 복원하고, 동기 local event dispatch와
idempotency 완료 뒤에만 ack한다. 타입·버전·envelope 오류와 진행 중인 중복은 예외로
남겨 기존 SQS retry/redrive/DLQ 경로에서 다시 처리할 수 있게 한다.

## 1. 문제와 현재 근거

Issue #471은 local application event를 SNS topic 또는 SQS queue로 외부화하고,
remote message를 다시 local handler로 전달하는 Spring Modulith adapter를 요구한다.
현재 저장소에는 필요한 AWS transport와 consumer 기반이 이미 있다.

| 현재 근거 | 유지할 계약 | 설계 영향 |
| --- | --- | --- |
| `SnsOperations` | topic name/ARN 확인과 coroutine `publish` | SNS transport가 새 client를 만들지 않고 `findTopicArn`과 `publish`를 호출한다. |
| `SqsOperations` | queue name 해석, full `SqsSendRequest`, receive/delete | SQS transport와 inbound consumer가 기존 operations를 사용한다. |
| `SqsFullRequestOperations` | FIFO group/deduplication id와 message attributes 보존 | SQS producer는 이 capability가 없으면 fail closed한다. |
| `SnsMessageConverter` | SNS→SQS `Notification` envelope에서 payload와 attributes 복원 | inbound consumer가 direct SQS와 SNS subscription delivery를 함께 처리한다. |
| `@SqsListener` / `SqsMessageListenerContainer` | retry, visibility heartbeat, success/manual ack, graceful stop | consumer가 별도 polling loop나 retry scheduler를 만들지 않는다. |
| Spring Modulith 2.1 | `EventExternalizationTransport.externalize(payload, target)`가 `CompletableFuture`로 실제 결과를 표현 | deprecated `DelegatingEventExternalizer` 대신 2.1 transport SPI와 module listener를 사용한다. |
| managed catalog | Spring Modulith BOM 2.1.0과 events api/core/jackson 좌표 제공 | library dependency는 `compileOnly`, 테스트는 `testImplementation`으로 둔다. |

Spring Modulith의 `RoutingTarget`은 `target::key`를 표현한다. target은 logical alias,
key는 FIFO `messageGroupId`로 해석한다. Event externalization configuration이 event를
먼저 map한 뒤 transport에 payload를 넘기므로 registry에는 원본 event가 아니라
**transport가 실제로 받는 mapped payload 타입**을 등록한다.

## 2. 목표와 범위

### 목표

1. Spring Modulith application event publication을 SNS 또는 SQS 완료와 연결한다.
2. event type, schema version, stable event ID, serialized payload, 제한된 header를
   명시적인 wire envelope와 AWS message attributes로 매핑한다.
3. `RoutingTarget.key`를 FIFO `messageGroupId`로 전달하고 stable event ID로
   deduplication id를 만든다.
4. direct SQS와 SNS→SQS delivery를 allowlist registry로 역직렬화해 local Spring
   event handler로 전달한다.
5. publish failure, unknown type/version, malformed envelope, duplicate, shutdown을
   재시도 가능한 상태로 보존한다.
6. Floci에서 SNS/SQS round-trip과 FIFO 경계를 검증하고 real AWS 계정 없이 완료한다.

### 범위 밖

- Spring Modulith event publication repository나 event store 구현
- SNS subscription, SQS queue, DLQ, redrive policy의 프로비저닝
- Spring Integration 또는 Spring Cloud AWS adapter 의존
- SNS HTTP signature adapter와 SQS throughput/batch 개선
- arbitrary class name 기반 역직렬화 또는 runtime classpath scanning
- distributed exactly-once 처리 보장
- 한 application context에서 둘 이상의 built-in inbound queue/source 동시 등록
- real AWS 계정, IAM, cross-account 운영 smoke test

## 3. 대안과 선택

### A안 — Spring Cloud AWS의 Modulith adapter를 직접 의존

upstream 구현을 재사용할 수 있지만 이 저장소의 awspring 비의존 원칙과 coroutine-first
operations를 우회한다. AWS client, 자동 설정, 속성 namespace가 중복되고 Floci-first
검증 경계도 흐려진다. 선택하지 않는다.

### B안 — Modulith SPI 없이 일반 `ApplicationListener`를 직접 구현

코드는 단순하지만 publication registry가 publish future의 성공·실패를 추적하지 못한다.
resubmission과 completion semantics가 사라져 Issue #471의 핵심을 충족하지 못한다.
선택하지 않는다.

### C안 — 별도 publishable module 추가

dependency 격리는 가장 분명하지만 단일 optional adapter 때문에 BOM, publishing,
manual inventory와 consumer 좌표가 늘어난다. 현재 `aws-spring-boot`가 이미 SNS/SQS
operations와 listener를 소유하므로 이 이슈에서는 과도하다. 선택하지 않는다.

### D안 — `aws-spring-boot`의 optional Modulith transport와 consumer

Modulith 2.1 SPI를 사용하면서 기존 coroutine operations와 SQS listener lifecycle을
그대로 재사용한다. `compileOnly`와 classpath condition으로 기존 소비자 ABI/runtime을
바꾸지 않고, 하나의 logical target registry에서 SNS/SQS를 명확히 나눈다. 이 안을
선택한다.

## 4. public API와 wire contract

### 4.1 event type registry

consumer가 임의 class name을 신뢰하지 않도록 사용자가 immutable registration을
명시적으로 제공한다.

```kotlin
@Bean
fun awsModulithEventTypeRegistry(): AwsModulithEventTypeRegistry =
    AwsModulithEventTypeRegistry.of(
        AwsModulithEventTypeRegistration(
            type = "order.placed",
            version = 1,
            eventClass = OrderPlaced::class.java,
            eventId = { it.eventId },
            allowedHeaderNames = setOf("tenant"),
            headers = { mapOf("tenant" to it.tenantId) },
        ),
    )
```

public generic/heterogeneous shape은 다음으로 고정한다.

```kotlin
data class AwsModulithEventTypeRegistration<T : Any>(
    val type: String,
    val version: Int,
    val eventClass: Class<T>,
    val eventId: (T) -> String,
    val allowedHeaderNames: Set<String> = emptySet(),
    val headers: (T) -> Map<String, String> = { emptyMap() },
)

class AwsModulithEventTypeRegistry private constructor(
    registrations: List<AwsModulithEventTypeRegistration<*>>,
) {
    internal fun registrationFor(event: Any): AwsModulithResolvedRegistration
    internal fun registrationFor(type: String, version: Int): AwsModulithResolvedRegistration

    companion object {
        fun of(vararg registrations: AwsModulithEventTypeRegistration<*>):
            AwsModulithEventTypeRegistry
    }
}
```

registry는 exact runtime class로 registration을 찾고 내부 resolved wrapper가
`eventClass.cast(event)` 뒤 typed lambdas를 호출한다. subclass/proxy 또는 잘못된 mapped
payload는 unchecked cast가 아니라 `AwsModulithEventRegistrationMismatchException`
(`BT4K-MOD-102`)으로 publication별 실패가 된다. duplicate/invalid registration은 registry
생성 시 configuration error다. 두 lookup 함수와 `AwsModulithResolvedRegistration`은
adapter 구현 전용 `internal` API이며 consumer public ABI에 노출하지 않는다.

각 registration은 다음 불변 조건을 가진다.

- `type`: `[a-z0-9][a-z0-9._-]{0,127}` 형식의 안정적인 logical name
- `version`: 1 이상의 정수
- `eventClass`: outbound mapped payload와 inbound 복원 타입
- `eventId`: 같은 business event의 재게시에서도 동일한 non-blank ID를 반환
- `allowedHeaderNames`: wire로 내보낼 business header의 명시적 allowlist
- `headers`: 선택적 문자열 map. allowlist 안의 안전한 값만 반환

동일 class, `(type, version)`, type의 복수 current version 등록은 생성 시 거부한다.
이 이슈는 upcaster를 제공하지 않는다. consumer는 envelope의 정확한 `(type,
version)`이 없으면 `AwsModulithUnknownEventTypeException` 또는
`AwsModulithUnsupportedEventVersionException`을 발생시킨다.

### 4.2 envelope

wire body는 다음 versioned JSON envelope다.

```json
{
  "specVersion": 1,
  "id": "01K3M8Y1X2P6V4Q9G7W3N5R8S0",
  "type": "order.placed",
  "version": 1,
  "payload": "{\"orderId\":\"42\",\"amount\":12000}",
  "headers": {
    "tenant": "acme"
  }
}
```

`payload`는 Spring Modulith `EventSerializer`가 만든 문자열을 그대로 담는다.
`EventSerializer.serialize`의 선언 반환형은 `Object`이므로 adapter는 반환값이
`String`일 때만 허용하고, 다른 타입은 시작/직렬화 오류로 fail closed한다. JSON을
문자열로 한 번 감싸는 이유는 serializer 구현과 payload JSON shape를 envelope codec이
재해석하지 않게 하기 위해서다. inbound는 registry가 고른 class에만
`EventSerializer.deserialize(payload, eventClass)`를 호출한다.

inbound는 SNS `Notification`, outer envelope, payload의 각 JSON layer를 제한된 parser로
검사한다. 최대 nesting 32, token
100,000개, 문자열 196,608 byte, 숫자 1,000자, duplicate key 금지를 적용하고
`@class`, `@type`, `@c`, `javaClass` 같은 polymorphic type-id property를 깊이와 무관하게
거부한다. Notification과 envelope는 정의되지 않은 field도 거부한다. payload business
field는 concrete event serializer가 처리한다. 그 뒤 registry가 선택한 final concrete
`eventClass`만 serializer에 넘긴다.
classpath scanning, payload subtype 선택, Jackson default typing은 허용하지 않는다.
사용자 제공 serializer는 이 concrete-target 계약을 지켜야 하며, adapter의 사전 JSON
검사를 우회할 수 없다.

system metadata `bt4k-event-id`, `bt4k-event-type`, `bt4k-event-version`은 같은 값을
AWS String message attributes에도 복제한다. consumer는 body를 authoritative source로
사용하고, 동일한 AWS attribute가 있으면 envelope 값과 일치하는지 검증한다. SNS→SQS
delivery에서는 SNS `MessageAttributes`, direct SQS에서는 SQS message attributes를
읽는다. 불일치는 변조·routing 오류로 보고 ack하지 않는다.

event ID는 UTF-8 1..128 byte와 제어 문자 금지 조건을 추가로 만족해야 한다. header
이름은 ASCII `[A-Za-z0-9_.-]` 1..128자, 값은 UTF-8 1,024 byte 이하로 제한한다.
system prefix `bt4k-`와 대소문자를 무시한 `authorization`, `cookie`, `credential`,
`password`, `secret`, `token` 포함 이름은 거부한다. system attribute를 포함해 최대
10개만 허용한다. serializer 결과는 envelope 조립 전에 기본 196,608 byte 이하인지
검증하고, 최종 envelope는 기본 262,144 byte 이하인지 다시 검증한다. codec은 payload를
한 번만 직렬화하고 같은 문자열을 재사용한다. payload, header 값, AWS response 원문은
`toString()`과 정상 로그에 남기지 않는다.

business header는 registration의 `allowedHeaderNames`에 열거한 이름만 허용한다. payload
내용의 data classification, encryption, 최소 수집은 application 책임이며 adapter가
field name denylist로 보안을 약속하지 않는다. adapter exception/log/metric은 diagnostic
code, target alias, type, version, byte count만 사용하고 event ID, payload, header 값,
AWS request/response 객체는 출력하지 않는다.

### 4.3 logical target

Spring Modulith annotation/configuration은 AWS 식별자가 아닌 logical alias를 사용한다.

```kotlin
@Externalized("order-events::#{#this.orderId}")
data class OrderPlaced(...)
```

```yaml
bluetape4k:
  aws:
    modulith:
      events:
        enabled: true
        targets:
          order-events:
            service: sns
            destination: order-events.fifo
          audit-events:
            service: sqs
            destination: audit-events
```

target alias는 설정에 반드시 있어야 한다. SNS destination은 topic name만, SQS
destination은 queue name만 허용한다. SNS는 `SnsOperations.findTopicArn`, SQS는
`SqsOperations.getQueueUrl`로 현재 client의 region/account 안에서 해석한다. ARN, queue
URL, partition-qualified 식별자는 설정에서도 거부하므로 cross-account publish는 이
adapter의 범위 밖이다. payload나 event header가 ARN, queue URL, service 종류를 바꿀
수 없다.

`.fifo` destination은 non-blank key가 필수다. key는 AWS group ID 제한인 UTF-8
128 byte 이하와 제어 문자 금지를 검증해 그대로 `messageGroupId`에 넣는다. standard
destination에 key를 지정하면 조용히 버리지 않고 configuration exception을 발생시킨다.
FIFO `messageDeduplicationId`는 event ID의 SHA-256 hex로 고정해 publication retry가
동일 deduplication ID를 사용하게 한다.

## 5. outbound 구조와 publication completion

`AwsModulithEventExternalizationTransport`는 Spring Modulith 2.1의
`EventExternalizationTransport`를 구현한다. composite transport는 AWS service
class를 직접 참조하지 않고 내부 `AwsModulithTargetPublisher` map만 의존한다. SNS와
SQS publisher adapter는 각각 별도 nested configuration에 두고 service SDK와
operations class가 있을 때만 로드한다. 이 분리는 `compileOnly` service SDK를 쓰는
SNS-only 또는 SQS-only application이 사용하지 않는 service class 때문에
`NoClassDefFoundError`를 내는 것을 막는다.

Spring Modulith 2.1의 실제 override는 다음 signature를 그대로 사용한다.

```kotlin
override fun externalize(payload: Any, target: RoutingTarget): CompletableFuture<*>
```

구현 future의 성공값은 public API가 아닌 immutable internal
`AwsModulithPublishResult(service, targetAlias, providerMessageIdPresent)`다. 호출자는 성공값
shape에 의존하지 않고 completion만 관찰한다. transport는 `AutoCloseable`이며 Spring bean
destroy method가 synchronous `close()`를 한 번 호출한다. `close()`는 설정 timeout까지
block한 뒤 shared close completion의 결과를 반환 없이 확정한다. coroutine에서 별도
suspend close API를 제공하지 않는다.

1. target alias를 immutable property map에서 찾는다.
2. mapped payload의 registration을 찾고 stable event ID와 header를 계산한다.
3. `EventSerializer`와 envelope codec으로 bounded body를 만든다.
4. target service에 따라 기존 operations를 호출한다.
   - SNS: `findTopicArn` → `SnsPublishRequest` → `SnsOperations.publish`
   - SQS: `getQueueUrl` → `SqsSendRequest` → `SqsFullRequestOperations.send`
5. AWS call이 끝난 뒤 response summary로 `CompletableFuture`를 완료한다.

transport는 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`를 직접 소유하고 각
externalization을 child job으로 실행한다. `GlobalScope`와 common pool은 사용하지
않는다. `producer.max-in-flight` 크기의 semaphore를 두며 queue 없이 `tryAcquire`한다.
permit을 얻지 못하면 AWS call이나 child job을 만들지 않고 retryable
`AwsModulithProducerCapacityException`으로 완료한 future를 반환한다. lifecycle lock
안에서 `OPEN` 확인, permit 획득, future/job 등록을 하나의 admission으로 수행해 close와
선형화한다. permit과 job registration은 단일 completion callback에서 정확히 한 번
해제한다.

반환 future 취소는 child job에 전달하고, child failure는 future를 exceptionally
complete한다. AWS success, AWS failure, caller cancellation, shutdown cancellation은
atomic first-terminal-wins로 future 결과를 한 번만 결정한다. 이미 관찰된 AWS 결과를
뒤늦은 cancellation이 덮어쓰지 않는다. AWS publish가 실패하거나 취소되면 Spring
Modulith publication은 완료 처리되지 않으므로 기존 incomplete/failed publication
resubmission 경로에 남는다.

adapter는 내부 publish retry를 추가하지 않는다. AWS SDK retry와 Spring Modulith
publication resubmission 사이에 또 다른 retry를 넣으면 duplicate와 latency가
증가하기 때문이다. target name/ARN과 resolved queue URL은 설정 target 수로 상한이
정해진 lifecycle cache에 저장한다. SNS의 기존 bounded ARN cache는 그대로 재사용한다.
SQS resolution은 alias별 single-flight entry를 atomic하게 등록해 동시 최초 요청도
`getQueueUrl`을 한 번만 호출한다. 실패 entry는 제거해 다음 publication이 다시 해석할
수 있게 한다.

outbound listener는 Spring Modulith의 기본 `EventExternalizerModuleListener`를 그대로
사용한다. inbound consumer는 복원한 event를 publish하기 직전에
`EventExternalizationConfiguration.supports(restoredEvent)`를 검사한다. `true`이면
`AwsModulithInboundLoopRiskException`으로 fail closed하고 claim을 release한 뒤 ack하지
않는다. 따라서 round-trip application은 outbound domain event를 remote handler용
non-externalized integration DTO로 mapping해야 한다. thread-local marker나 listener
subclass는 Modulith의 async listener 및 persistent multicaster selection 경계에서
안전하지 않으므로 사용하지 않는다.

transport에 전달된 mapped payload의 registration header만 wire header로 사용한다.
`EventExternalizationConfiguration.getHeadersFor(originalEvent)`는 mapping 전 원본 event를
요구하지만 transport SPI에는 원본이 전달되지 않으므로 이 adapter가 자동 병합하지
않는다. 필요한 header는 mapped payload registration에 명시한다.

## 6. inbound consumer, idempotency, ack

### 6.1 consumer 흐름

`AwsModulithSqsEventConsumer`는 ack를 모르는 public coroutine API로 제공하고, 자동
설정된 package-private listener가 이를 호출한다.

```kotlin
enum class AwsModulithConsumeOutcome { PROCESSED, COMPLETED_DUPLICATE }

class AwsModulithSqsEventConsumer internal constructor(
    sourceDecoder: AwsModulithInboundSourceDecoder,
    registry: AwsModulithEventTypeRegistry,
    store: AwsModulithEventIdempotencyStore,
    externalization: org.springframework.modulith.events.EventExternalizationConfiguration,
    eventPublisher: org.springframework.context.ApplicationEventPublisher,
    properties: AwsModulithEventsProperties.Consumer,
    metrics: AwsModulithMetrics,
    clock: java.time.Clock,
) {
    suspend fun consume(message: SqsReceivedMessage): AwsModulithConsumeOutcome
}

@SqsListener(
    queue = "\${bluetape4k.aws.modulith.events.consumer.queue}",
    acknowledgementMode = SqsAcknowledgementMode.MANUAL,
)
internal suspend fun onMessage(
    message: SqsReceivedMessage,
    acknowledgement: SqsAcknowledgement,
) {
    consumer.consume(message)
    acknowledgement.acknowledge()
}
```

public consumer는 `PROCESSED` 또는 `COMPLETED_DUPLICATE`만 정상 반환하고 retry/no-ack
상태는 typed exception 또는 원래 cancellation로 전파한다. internal listener만 정상
outcome 뒤 ack를 소유하며 exception/cancellation을 catch해 성공으로 바꾸지 않는다.
constructor는 Spring 자동 설정 전용 `internal` 경계다. application caller는 bean을
주입받아 `consume`만 호출하며 직접 생성 API를 제공하지 않는다.
`AwsModulithInboundSourceDecoder`와 `AwsModulithMetrics`도 같은 package의 `internal`
구현 타입이다.

처리 순서는 다음과 같다.

1. configured source mode가 `DIRECT`이면 SNS-like JSON을 거부하고 direct body만,
   `SNS`이면 strict `Notification` discriminator와 필수 SNS field가 있는 body만 받는다.
2. `SNS` mode는 exact expected topic ARN allowlist를 검사하고
   `SnsHttpMessageVerifier`로 signature와 certificate trust를 검증한 뒤 inner envelope와
   attributes를 추출한다.
3. size, `specVersion`, event ID, type/version, header와 attribute 일치를 검증한다.
4. registry allowlist로 class를 찾고 `EventSerializer`로 payload를 복원한다.
5. idempotency store에서 `(type, eventId)` lease claim을 얻는다.
6. `EventExternalizationConfiguration.supports(restoredEvent)`가 `false`인지 확인한다.
7. claim lease heartbeat를 구조화된 child coroutine으로 유지하면서
   `ApplicationEventPublisher.publishEvent(restoredEvent)`를 호출한다.
8. 동기 dispatch가 정상 반환하면 fencing token으로 claim을 completed로 바꾼다.
9. listener가 `acknowledgement.acknowledge()`를 호출한다.

이미 completed인 duplicate는 local event를 다시 publish하지 않고 ack한다. 다른
handler가 같은 key를 처리 중이면 `AwsModulithEventInProgressException`을 발생시켜
visibility/retry 경로에 남긴다. decode, unknown type/version, claim capacity, local
handler 오류도 예외로 전파해 ack하지 않는다.

`DIRECT` mode의 출처 신뢰는 queue resource policy와 IAM `sqs:SendMessage` allowlist가
담당하며 mode 선택 자체가 unsigned direct input의 명시적 opt-in이다. `SNS` mode는
구조만 맞는 unsigned `Notification`, 예상 밖 `TopicArn`, invalid signature를 모두
거부한다. Floci transport test가 서명 가능한 notification을 제공하지 않으면 verifier
contract는 mock certificate fixture로 별도 증명하고, Floci test profile에서만 명시적
test verifier를 주입한다. 이를 production signature 검증 성공으로 표기하지 않는다.

`ApplicationEventPublisher`는 비동기 `@EventListener`나 transaction commit 이후의
handler 완료를 기다리지 않는다. 따라서 이 설계의 success-only ack는 **동기 Spring
event dispatch가 정상 반환한 시점**까지를 뜻한다. async handler의 최종 성공이나
local DB transaction과의 원자성은 보장하지 않는다.

### 6.2 idempotency SPI와 기본 구현

```kotlin
data class AwsModulithEventKey(val type: String, val eventId: String)

data class AwsModulithClaimToken(
    val key: AwsModulithEventKey,
    val ownerId: String,
    val generation: Long,
    val leaseUntil: java.time.Instant,
)

sealed interface AwsModulithClaimResult {
    data class Acquired(val token: AwsModulithClaimToken) : AwsModulithClaimResult
    data object Completed : AwsModulithClaimResult
    data class InProgress(val leaseUntil: java.time.Instant) : AwsModulithClaimResult
}

enum class AwsModulithStoreMutation {
    APPLIED,
    ALREADY_APPLIED,
    NOT_FOUND,
    STALE,
}

interface AwsModulithEventIdempotencyStore {
    suspend fun claim(key: AwsModulithEventKey, leaseDuration: java.time.Duration): AwsModulithClaimResult
    suspend fun renew(token: AwsModulithClaimToken, leaseDuration: java.time.Duration): AwsModulithClaimToken
    suspend fun complete(token: AwsModulithClaimToken): AwsModulithStoreMutation
    suspend fun release(token: AwsModulithClaimToken): AwsModulithStoreMutation
    suspend fun recoverExpired(now: java.time.Instant): Int
}
```

모든 함수는 non-blocking suspend 계약이다. durable adapter가 blocking I/O를 쓰면 자기
구현 안에서 `Dispatchers.IO`로 격리한다. `renew`의 missing/stale token은 typed
`AwsModulithStaleClaimException`이고, 성공 시 같은 generation과 갱신된 deadline의 새
token을 반환한다. `complete` 반복은 `ALREADY_APPLIED`, `release` 반복은 `NOT_FOUND`로
안전하게 수렴한다. 더 새 generation이 있으면 두 mutation 모두 `STALE`이며 consumer는
이를 성공으로 간주하지 않는다. `recoverExpired`는 `now` 이전 claim만 reclaimable로
전환하고 전환 개수를 반환하며 active claim과 completed entry를 바꾸지 않는다.

claim token은 monotonic fencing generation, owner ID, lease deadline을 포함한다. 모든
mutation은 key와 generation을 atomic compare-and-set하며 stale token은 renew, complete,
release를 할 수 없다. `claim`은 한 key에 대해 linearizable한 단일 승자만
`ACQUIRED(token)`을 받고, 나머지는 `COMPLETED` 또는 `IN_PROGRESS(deadline)`을 받는다.
만료된 claim은 같은 atomic operation 안에서 generation을 증가시킨 새 owner에게만
takeover된다.

consumer는 lease의 1/3 간격으로 `renew`하고 handler 종료 시 heartbeat child를 먼저
취소한다. heartbeat는 listener handler의 구조화된 scope 안에만 존재하며 concurrent
consumer 상한을 넘지 않는다. `renew` 실패나 stale token은 handler 성공 여부와 무관하게
no-ack 오류가 된다. startup의 `recoverExpired(now)`는 만료 claim만 reclaimable로
표시하며 active lease를 훔치지 않는다. durable store는 이 fencing/lease 계약을
구현해야 하고, 단순 `putIfAbsent` 구현은 허용하지 않는다.

library는 사용자 store를 직접 닫지 않고 Spring bean lifecycle에 맡긴다. 기본 in-memory
store만 context close callback에서 신규 claim을 막고 local state를 폐기한다. 공개
contract TCK는 모든 store 구현에 atomic single-winner, repeat mutation, stale fencing,
lease takeover, recoverExpired active-preservation 시나리오를 동일하게 적용한다.

기본 `InMemoryAwsModulithEventIdempotencyStore`는 process-local 편의 구현이다.

- completed retention 기본 24시간, 최대 entry 기본 10,000
- active in-progress 기본 256, type+event ID key 전체 기본 2 MiB의 별도 상한
- completed entry만 TTL/LRU로 제거하고 active in-progress entry는 자동 축출하지 않음
- expired in-progress entry는 fencing generation을 올린 다음에만 takeover 가능
- capacity가 in-progress entry로 가득 차면 retryable capacity exception
- local dispatch 실패 시 claim release, 정상 반환 시 complete
- context close 시 신규 claim을 막고 보유 상태를 폐기

entry count, active claim 수, key byte 사용량, claim 거부, lease takeover를 Micrometer가
있을 때만 bounded meter로 노출한다. event ID나 type을 metric tag로 사용하지 않는다.

애플리케이션 재시작, multi-instance race, local side effect와 claim commit의 원자성이
필요하면 사용자가 durable store bean을 제공해야 한다. crash가 local handler 성공과
`complete` 사이에 발생하면 lease 만료 후 redelivery가 새 generation으로 handler를 다시
호출할 수 있다. 이전 process의 stale token은 새 claim을 complete/release할 수 없다.
반대로 `complete` 뒤 SQS ack가 실패하면 redelivery는 completed duplicate로 판단해 ack만
재시도한다. 이 경계는 at-least-once transport 위의 bounded duplicate suppression이지
exactly-once 보장이 아니다.

### 6.3 inbound failure precedence

| 경계 | primary 결과 | cleanup/state | ack |
| --- | --- | --- | --- |
| handler 일반 실패 | 원문 cause 없는 bounded `AwsModulithDispatchException` (`BT4K-MOD-204`) | bounded `NonCancellable` release, release 실패도 sanitized suppressed exception | 하지 않음 |
| handler cancellation | 원래 `CancellationException` 재전파 | bounded `NonCancellable` release, 실패는 sanitized suppressed exception | 하지 않음 |
| handler JVM `Error` | 원래 `Error` 재전파 | bounded `NonCancellable` release, 실패는 sanitized suppressed exception | 하지 않음 |
| handler 성공, heartbeat/renew 실패 | lease 오류 | stale/만료 claim을 complete하지 않음 | 하지 않음 |
| handler 성공, complete 실패 | complete 오류 | claim은 lease 만료 후 takeover 가능한 상태 | 하지 않음 |
| complete 성공, ack 실패 | ack 오류 | completed 유지, redelivery는 handler 없이 ack 재시도 | 실패 |
| duplicate completed, ack 실패 | ack 오류 | completed 유지 | 실패 |

일반 handler throwable은 원문 message/cause chain을 보존하지 않고 cause class와 bounded
phase만 안전한 internal summary로 바꾼 `AwsModulithDispatchException`이 primary가 된다.
cleanup failure도 raw throwable 대신 bounded phase/code만 가진 sanitized exception으로
suppressed에 붙인다. `CancellationException`과 JVM `Error`만 원래 객체 identity를
재전파한다. cleanup failure는 이 primary를 덮지 않는다. 정상 handler 뒤 complete가
실패하면 sanitized complete 오류가 primary다. `complete`가 성공한 뒤에만 ack할 수 있으며,
ack가 실패해도 completed 상태를 release하지 않는다. adapter가 생성한 일반 typed
exception, sanitized suppressed array, adapter 소유 운영 로그 어디에도 handler/cleanup의
hostile message, payload, event ID, header, ARN/URL, AWS request/response가 노출되지 않아야
한다. identity를 보존하는 `CancellationException`과 JVM `Error`의 자체 message/cause는 이
adapter-generated no-leak claim에서 제외한다. adapter는 두 원본 객체를 logger에 전달하거나
직접 렌더링하지 않으며 framework/user logger의 렌더링까지 비노출로 주장하지 않는다.

### 6.4 retry와 dead-letter

consumer는 자체 retry/DLQ publisher를 만들지 않는다. 현재
`bluetape4k.aws.sqs.listener.retry.*`, error visibility timeout, queue redrive policy를
그대로 사용한다. 최대 수신 횟수를 넘긴 메시지의 DLQ 이동은 SQS/Floci redrive
configuration 책임이다. typed exception은 payload나 secret을 메시지에 포함하지 않고
diagnostic code, bounded phase, retryable, caller action만 노출한다. event type/version은
검증된 내부 관측 field로만 다루고 exception message/property에는 넣지 않는다.

diagnostic code는 bounded enum으로 고정한다.

```kotlin
enum class AwsModulithCallerAction {
    STOP_DEPLOYMENT,
    FIX_PAYLOAD,
    RESUBMIT_PUBLICATION,
    CHECK_AWS_AND_RESUBMIT,
    QUARANTINE_SOURCE,
    DEPLOY_COMPATIBLE_CONSUMER,
    RECOVER_STORE_AND_RETRY,
    INSPECT_DISPATCH_OR_ACK,
}

enum class AwsModulithDiagnosticCode(
    val value: String,
    val retryable: Boolean,
    val callerAction: AwsModulithCallerAction,
)

enum class AwsModulithFailurePhase {
    CONFIGURATION,
    SERIALIZATION,
    LIFECYCLE,
    RESOLUTION,
    PUBLISH,
    SOURCE,
    DECODE,
    CLAIM,
    DISPATCH,
    ACK,
    CLEANUP,
}

sealed class AwsModulithEventException protected constructor(
    val code: AwsModulithDiagnosticCode,
    val phase: AwsModulithFailurePhase,
) : RuntimeException("${code.value}:${phase.name}", null, true, true) {
    val retryable: Boolean get() = code.retryable
    val callerAction: AwsModulithCallerAction get() = code.callerAction
}
```

public concrete exception type의 constructor는 모두 `internal`이며 library code만
생성한다. base hierarchy는 sealed라 consumer module이 code, retryability, caller action,
message, cause를 임의로 조합하거나 subclass로 우회할 수 없다. 일반 typed exception의
`cause`는 항상 `null`이고 message는 code와 bounded phase로만 생성한다. 4-인자
`RuntimeException` constructor가 cause를 이미 `null`로 초기화하므로 consumer의
`initCause(hostileThrowable)`도 `IllegalStateException`으로 거부된다. suppression은
sanitized cleanup exception을 붙이기 위해 활성화한다.

public catch ABI와 고정 mapping은 다음 catalog로 닫는다. 각 public class는
`AwsModulithEventException`을 상속하며 exact constructor는 인자 없는
`internal constructor()`다. 표에 없는 public concrete exception은 추가하지 않는다.

| Public exception type | Diagnostic code | Fixed phase |
| --- | --- | --- |
| `AwsModulithConfigurationException` | `BT4K-MOD-101` | `CONFIGURATION` |
| `AwsModulithEventRegistrationMismatchException` | `BT4K-MOD-102` | `SERIALIZATION` |
| `AwsModulithOutboundEnvelopeException` | `BT4K-MOD-102` | `SERIALIZATION` |
| `AwsModulithProducerCapacityException` | `BT4K-MOD-103` | `LIFECYCLE` |
| `AwsModulithProducerClosedException` | `BT4K-MOD-103` | `LIFECYCLE` |
| `AwsModulithTargetResolutionException` | `BT4K-MOD-104` | `RESOLUTION` |
| `AwsModulithPublishException` | `BT4K-MOD-104` | `PUBLISH` |
| `AwsModulithSourceException` | `BT4K-MOD-201` | `SOURCE` |
| `AwsModulithInboundEnvelopeException` | `BT4K-MOD-202` | `DECODE` |
| `AwsModulithUnknownEventTypeException` | `BT4K-MOD-202` | `DECODE` |
| `AwsModulithUnsupportedEventVersionException` | `BT4K-MOD-202` | `DECODE` |
| `AwsModulithInboundLoopRiskException` | `BT4K-MOD-202` | `DECODE` |
| `AwsModulithClaimCapacityException` | `BT4K-MOD-203` | `CLAIM` |
| `AwsModulithEventInProgressException` | `BT4K-MOD-203` | `CLAIM` |
| `AwsModulithStaleClaimException` | `BT4K-MOD-203` | `CLAIM` |
| `AwsModulithClaimMutationException` | `BT4K-MOD-203` | `CLAIM` |
| `AwsModulithDispatchException` | `BT4K-MOD-204` | `DISPATCH` |
| `AwsModulithAcknowledgementException` | `BT4K-MOD-204` | `ACK` |

cleanup failure는 public catch ABI가 아닌
`internal class AwsModulithCleanupException internal constructor()`로 두고
`BT4K-MOD-204`/`CLEANUP`에 고정한다. public catalog의 grouping은 source/signature를
`AwsModulithSourceException`, renew/complete/release를
`AwsModulithClaimMutationException`으로 모은다.

| Code | `retryable` | 경계 | caller/운영 조치 |
| --- | --- | --- | --- |
| `BT4K-MOD-101` | `false` | 설정·classpath·target 오류 | 시작/배포 중단, condition report 확인 |
| `BT4K-MOD-102` | `false` | 직렬화·envelope 제한 | 자동 재시도 금지, registration/payload 수정 전 DLQ 보존 |
| `BT4K-MOD-103` | `true` | producer capacity·shutdown 거부 | Modulith publication 미완료 유지, in-flight/latency 확인 뒤 resubmission |
| `BT4K-MOD-104` | `true` | target resolution·AWS publish | publication 미완료 유지, endpoint/권한/SDK retry 확인 |
| `BT4K-MOD-201` | `false` | source mode·TopicArn·signature | no-ack로 DLQ 이동, queue policy/source 확인 후 격리 |
| `BT4K-MOD-202` | `false` | malformed·unknown type/version·loop risk | 자동 재시도 금지, 호환 consumer 선배포 또는 DLQ 분석 |
| `BT4K-MOD-203` | `true` | claim·lease·complete | no-ack 유지, store health/lease takeover 확인 |
| `BT4K-MOD-204` | `true` | local dispatch·ack | no-ack 유지, handler/SQS delete와 completed 여부로 안전한 재처리 결정 |

같은 code의 concrete exception은 위 `retryable`과 `callerAction`을 바꾸지 않는다.
configuration과 non-retryable inbound 오류도 listener가 ack로 삼지 않으며 SQS
redrive/DLQ가 반복 수신을 종료한다.

consumer fixture는 모든 public catch type과 base accessor를 외부 source set에서
compile한다. 별도 forbidden fixture는 public concrete constructor 호출을 시도하고 외부
Kotlin compile이 `internal` 접근 오류로 실패하는지 확인한다. 같은 module의 friend-path
test만으로 visibility를 증명하지 않는다.

구조화 로그는 code, retryable, target alias, type, version, phase만 허용한다. Micrometer가
있으면 publish latency/success/failure, resolution failure, in-flight/capacity reject,
consumer redelivery/source reject, claim state/takeover, ack failure를 bounded tag로 노출한다.
event ID, destination ARN/URL, queue message ID는 tag로 쓰지 않는다. capacity reject,
source reject, ack failure, DLQ 증가의 alert threshold는 application 운영 정책이며 README에
code별 점검 순서를 제공한다.

## 7. configuration과 자동 설정

```yaml
bluetape4k:
  aws:
    modulith:
      events:
        enabled: false
        producer:
          enabled: false
          max-in-flight: 64
          max-serialized-payload-bytes: 196608
          max-envelope-bytes: 262144
          shutdown-timeout: 25s
        consumer:
          enabled: false
          queue: order-events-consumer
          source-mode: direct
          expected-topic-arns: []
          redrive-required: true
          idempotency:
            max-entries: 10000
            max-in-progress: 256
            max-key-bytes: 2097152
            retention: 24h
            lease-duration: 2m
        targets: {}
```

| Property | 기본값 | 계약 |
| --- | --- | --- |
| `...events.enabled` | `false` | 전체 기능의 명시적 opt-in |
| `...events.producer.enabled` | `false` | root enablement 안에서 outbound transport 명시적 활성화 |
| `...events.producer.max-in-flight` | `64` | queue 없는 outbound admission 상한, `1..1024` |
| `...events.producer.max-serialized-payload-bytes` | `196608` | envelope 조립 전 serializer 결과 UTF-8 상한, `1..262144` |
| `...events.producer.max-envelope-bytes` | `262144` | SNS/SQS 공통 보수 상한, `1..262144` |
| `...events.producer.shutdown-timeout` | `25s` | 신규 publish 차단 후 in-flight drain 상한, `1s..5m` |
| `...events.consumer.enabled` | `false` | inbound listener 명시적 opt-in |
| `...events.consumer.queue` | `null` | consumer가 poll할 queue name. enabled이면 필수 |
| `...events.consumer.source-mode` | `null` | `DIRECT` 또는 `SNS`; consumer enabled이면 명시 필수 |
| `...events.consumer.expected-topic-arns` | `[]` | `SNS` mode exact source allowlist; 비어 있으면 startup 실패 |
| `...events.consumer.redrive-required` | `true` | queue `RedrivePolicy`가 없으면 listener 시작 전 실패 |
| `...events.consumer.idempotency.max-entries` | `10000` | 기본 in-memory store의 bounded capacity |
| `...events.consumer.idempotency.max-in-progress` | `256` | active claim 별도 상한, `1..max-entries` |
| `...events.consumer.idempotency.max-key-bytes` | `2097152` | type+event ID key의 aggregate UTF-8 상한 |
| `...events.consumer.idempotency.retention` | `24h` | completed duplicate retention, `1m..7d` |
| `...events.consumer.idempotency.lease-duration` | `2m` | claim lease와 heartbeat 기준, `30s..30m` |
| `...events.targets` | `{}` | logical alias별 SNS/SQS destination. producer enabled이면 비어 있을 수 없음 |

### 7.1 runtime dependency와 최소 recipe

소비자는 root `bluetape4k-dependencies` BOM으로 버전을 정하고 개별 좌표에 버전을 쓰지
않는다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot")
    // 예시 publication repository backend이며 application이 JDBC/Mongo 등으로 바꿀 수 있다.
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    implementation("org.springframework.modulith:spring-modulith-events-jackson")

    // 사용하는 경로만 선택한다.
    runtimeOnly("software.amazon.awssdk:sns")                 // SNS producer
    runtimeOnly("software.amazon.awssdk:sqs")                 // SQS producer/consumer
    runtimeOnly("software.amazon.awssdk:sns-message-manager") // verified SNS consumer
}
```

producer-only는 root와 producer를 둘 다 켜고 target을 한 개 이상 둔다. consumer-only는
producer 기본값 `false`를 유지하므로 빈 targets로 시작할 수 있다.

```yaml
# producer-only
bluetape4k.aws.modulith.events:
  enabled: true
  producer.enabled: true
  targets.order-events:
    service: sns
    destination: order-events

# consumer-DIRECT
bluetape4k.aws.modulith.events:
  enabled: true
  consumer:
    enabled: true
    queue: direct-order-events
    source-mode: direct
    redrive-required: true

# consumer-SNS
bluetape4k.aws.modulith.events:
  enabled: true
  consumer:
    enabled: true
    queue: sns-order-events
    source-mode: sns
    expected-topic-arns:
      - arn:aws:sns:ap-northeast-2:123456789012:order-events
    redrive-required: true
```

built-in listener 하나는 application context당 queue 하나와 source mode 하나만 담당한다.
DIRECT와 SNS를 동시에 받는 multi-source auto-configuration은 이 이슈의 범위 밖이며,
서로 다른 application context/service로 분리한다. `SnsHttpMessageVerifier`는 existing
verification auto-configuration이 `SnsProperties.region`과 `sns-message-manager`로 만들고
Spring이 close한다. custom verifier bean은 이를 대체한다.

자동 설정은 `SnsAutoConfiguration`, `SqsAutoConfiguration`, Spring Modulith event
externalization/serialization 설정 뒤에 실행한다. 다음 조건을 지킨다.

outer auto-configuration은 Modulith/AWS service type을 field, method signature, generic,
annotation class literal로 참조하지 않고 `@ConditionalOnClass(name = [...])` 문자열만
사용한다. 조건이 통과한 뒤 import하는 nested Modulith configuration에서만
`EventExternalizationTransport`, `RoutingTarget`, `EventSerializer`를 참조하고, 그 아래
SNS/SQS nested configuration이 각 service type을 참조한다. 따라서 outer metadata를 읽는
것만으로 optional class가 load되지 않는다.

1. Modulith `EventExternalizationTransport`, `EventExternalizerModuleListener`,
   `EventSerializer`가 classpath에 없으면 모든 bean을 건너뛴다.
2. root property가 false이면 registry를 포함한 기존 application bean을 건드리지 않는다.
3. 사용자가 `EventExternalizationTransport`를 제공하면 producer transport가 back-off하고
   Spring Modulith의 기본 module listener 구성을 방해하지 않는다.
4. producer를 켰는데 type registry, `EventSerializer`, configured target에 필요한
   service publisher가 없으면 startup을 명확한 configuration error로 실패시킨다.
5. SQS producer는 `SqsFullRequestOperations`가 있어야 한다. markerless custom
   `SqsOperations`에 FIFO/header를 조용히 버리지 않는다.
6. consumer는 `SqsOperations`, type registry, serializer, application publisher,
   `EventExternalizationConfiguration`이 모두 있어야 하고 queue가 없으면 startup을
   실패시킨다.
7. user idempotency store bean은 기본 in-memory store를 대체한다.
8. SNS/SQS publisher nested configuration은 각각 해당 AWS SDK model class와 operations를
   문자열 class condition으로 격리한다. 미사용 service SDK가 없어도 다른 service만
   사용하는 context는 시작해야 한다.
9. consumer는 source mode를 요구한다. `SNS` mode는 verifier와 expected topic ARN이,
   `DIRECT` mode는 queue name이 필수다. `redrive-required=true`이면 listener 시작 전에
   queue `RedrivePolicy`를 읽어 poison-message 상한이 없는 구성을 거부한다.

`spring-modulith-events-api`, `spring-modulith-events-core`,
`spring-modulith-events-jackson`은 `compileOnly`다. 소비자는 사용하는 Spring Modulith
starter/event publication repository와 Jackson serializer를 runtime에 추가한다.
library가 event store 종류를 선택하거나 transitively 강제하지 않는다.

## 8. lifecycle, concurrency, failure precedence

### outbound close

transport lifecycle은 `OPEN → CLOSING → CLOSED` 단방향 상태 머신이다.

1. 첫 close가 lifecycle lock에서 `CLOSING`으로 전환하고 shared close completion을 만든다.
2. externalization admission은 같은 lock을 사용하므로 전환 뒤에는 future/job/permit을 새로
   등록할 수 없고 failed future로 거부된다.
3. 전환 전에 등록된 in-flight child만 `shutdown-timeout`까지 기다린다.
4. 완료된 publish는 정상 future를 유지한다.
5. timeout 시 남은 job/future를 취소해 Modulith publication을 incomplete로 남긴다.
6. owned scope만 cancel하고 `SnsOperations`, `SqsOperations`, AWS client는 닫지 않은 뒤
   `CLOSED`와 shared completion을 완료한다.

publish failure가 이미 존재하면 shutdown cancellation이 그 원인을 덮어쓰지 않는다.
JVM `Error`와 coroutine `CancellationException`은 일반 retry exception으로 바꾸지 않는다.
동시·반복 close는 모두 같은 close completion을 기다리며 별도 in-flight 목록이나 cancellation을
시작하지 않는다. AWS completion과 cancel race는 outbound의 atomic first-terminal-wins를
따른다.

### inbound stop

자동 listener는 기존 `SqsMessageListenerContainer`의 phase와 `stopTimeoutMillis`를
따른다. stop 중 새 poll을 막고 시작된 handler를 drain한다. handler가 stop timeout을
넘으면 기존 container cancellation이 우선하며 메시지를 ack하지 않는다. idempotency
claim은 bounded `NonCancellable` cancellation cleanup에서 release해 redelivery가 다시
claim할 수 있게 한다. release가 실패하면 원래 cancellation을 보존하고 lease 만료와
fencing takeover로 복구한다.

### bounded state

- target resolution cache 상한 = configured target 수, 최대 100
- type registry = 생성 시 고정된 immutable map, 최대 256 registration
- envelope/header = byte/count 상한
- idempotency = max entries/in-progress/key bytes/retention 상한
- coroutine job set = `max-in-flight` 이하이며 completion callback에서 permit과 함께 제거

새 unbounded queue, background retry loop, polling thread는 추가하지 않는다.

## 9. 검증 전략

### unit / contract

- registry duplicate class/type/version, invalid type/version/event ID byte 경계
- envelope round-trip, non-String serializer, serializer payload/final envelope size 경계와
  단일 serializer 호출
- malformed JSON, duplicate key, depth/token/number/string 상한, polymorphic type-id payload
- header allowlist/name/value/count, sensitive/reserved header, attribute mismatch
- logical target miss, SNS/SQS dispatch, standard key reject, FIFO key/group mapping
- ARN/queue URL destination 거부와 topic/queue name 해석
- stable event ID의 SHA-256 deduplication ID
- operations success 전 future 미완료, success 후 완료, failure exceptional completion
- max-in-flight까지 admission, 초과 future 거부, max active/AWS call-count invariant
- 동시 최초 SQS resolution single-flight와 실패 entry eviction
- future cancellation ↔ child job cancellation, cancel-vs-AWS-success first-terminal-wins
- externalize-close registration barrier, close drain/timeout, 동시·반복 close shared completion,
  post-close child 0
- idempotency atomic single-winner, completed/in-progress/release/capacity/TTL/key-byte 경계
- lease heartbeat, orphan restart takeover, stale-token fencing, concurrent duplicate delivery
- reusable idempotency store TCK로 suspend/mutation/recovery contract 검증
- handler+release, cancellation+release, complete, ack의 단일·복합 실패 precedence
- public consumer outcome과 internal listener success-only ack/cancellation 전파
- externalizable inbound type은 typed loop-risk 오류로 no-ack되고, mapped integration DTO만
  local handler를 호출함
- log capture에서 event ID/payload/header/AWS object와 secret marker가 노출되지 않음

### auto-configuration

- `FilteredClassLoader("org.springframework.modulith")`에서 모든 adapter bean 없음
- Spring Modulith 2.1 실제 `EventExternalizationTransport` compile contract와
  `EventExternalizerModuleListener`/publication repository completion behavior
- root/producer/consumer enablement와 user transport/store back-off
- custom outbound transport가 있어도 enabled inbound consumer는 유지됨
- missing registry/serializer/target/queue/full-request capability의 fail-closed 오류
- SNS SDK를 뺀 SQS-only, SQS SDK를 뺀 SNS-only, 양쪽 target context의 classloading
- DIRECT/SNS source mode 필수값, SNS verifier/topic allowlist, queue redrive policy 진단
- context close에서 owned scope/store만 닫고 AWS operations/client는 닫지 않음

### Floci integration

Docker-backed test는 순차 실행하고
`-Dbluetape4k.aws.emulator=floci`를 명시한다.

1. local `@Externalized` event → SNS topic publish → subscribed SQS queue → inbound
   consumer → local handler 1회 호출
2. local event → direct SQS target → inbound consumer → local handler
3. SNS FIFO와 SQS FIFO에서 routing key가 message group ID로 보존되고 stable
   deduplication ID가 적용됨
4. 동일 envelope를 두 번 전달하면 local handler는 한 번만 호출되고 두 delivery는
   최종 ack됨
5. unknown type/version 및 malformed envelope는 삭제되지 않아 visibility 후 다시 수신됨
6. publish failure와 shutdown 중 in-flight future가 exceptional/incomplete로 남음
7. configured redrive policy에서 poison message가 최대 수신 횟수 뒤 DLQ에 도달함

추가 deterministic bounded-load test는 blocking fake publisher에 `max-in-flight + 32`개를
동시에 넣고 active job이 설정값을 넘지 않음, 초과 future가 즉시 거부됨, 허용된 수만큼만
AWS operations가 호출됨, release 후 재-admission됨을 검증한다. serializer 경계 test는
payload를 한 번만 만들고 제한 초과 시 envelope copy/AWS call이 발생하지 않음을 함께
검증한다.

Floci가 특정 SNS FIFO subscription/redrive/signature API를 제공하지 않으면 동일 request
contract를 mock certificate/request fixture로 증명하고, Floci가 지원하는 direct FIFO SQS
round-trip을 필수 hosted/local evidence로 남긴다. test verifier를 쓴 Floci SNS 결과는
signature 검증 성공으로 표기하지 않는다. 성공하지 않은 emulator test를 skip으로
간주하지 않는다.

### 정적·회귀 검증

```bash
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon --no-build-cache \
  -Dbluetape4k.aws.emulator=floci
./gradlew :bluetape4k-aws-spring-boot:detekt --no-daemon
./gradlew build -x test --parallel --no-daemon
git diff --check
```

PR exact-head에서 필수 hosted evidence는 GitHub Actions `Test / aws-spring-boot` terminal
success와 `test-results-aws-spring-boot` artifact다. module test는 shared Docker 때문에
순차 실행하며 각 Testcontainers resource를 `finally`에서 닫는다. 로컬/CI 모두 JUnit XML,
Floci container log, 실패 시 classified-retry artifact를 보존하고, skip·old SHA·path-filter된
job을 exact-head Floci 성공으로 대체하지 않는다.

## 10. 호환성, 운영, rollback

- 기존 property와 bean 이름을 변경하지 않는 additive API다.
- root enablement 기본값이 false이므로 upgrade만으로 event publish/poll을 시작하지 않는다.
- optional Modulith dependency는 consumer runtime classpath에 자동으로 추가되지 않는다.
- SNS/SQS operations와 AWS client의 bean ownership, close order, endpoint/credential 설정을
  그대로 유지한다.
- IAM은 producer target에 필요한 `sns:Publish` 또는 `sqs:SendMessage`, consumer에
  `sqs:ReceiveMessage`, `sqs:DeleteMessage`, `sqs:ChangeMessageVisibility` 최소 권한만
  요구한다. DIRECT queue는 producer principal만 `sqs:SendMessage`하도록 resource policy를
  제한하고, SNS queue는 expected topic ARN의 `aws:SourceArn`/`aws:SourceAccount` 조건을
  둔다. credential이나 signed request는 로그에 남기지 않는다.
- DLQ/redrive와 durable idempotency store는 애플리케이션 운영 설정이다. 기본 consumer는
  redrive policy 부재를 시작 오류로 처리하며 condition
  report와 typed diagnostic code로 누락을 확인할 수 있게 한다.
- 새 `(type, version)`은 해당 registration을 이해하는 consumer를 먼저 전 instance에
  배포한 뒤 producer가 내보낸다. 이 이슈는 upcaster가 없으므로 old consumer가 남아 있는
  동안 새 version을 발행하지 않는다. consumer downgrade도 새 version queue/DLQ가 0임을
  확인한 뒤에만 한다.
- rollback은 producer enablement를 먼저 끄고 in-flight gauge 0과 shared close completion을
  확인한다. timeout/failed publication이 있으면 rollback을 중단하고 Modulith incomplete
  publication을 보존한다. 그 뒤 queue와 DLQ의 supported version을 drain하고 durable
  idempotency state를 유지한 채 consumer를 끈다. 이미 발생한 incomplete publication,
  SQS message, DLQ message를 library가 삭제하거나 store를 truncate하지 않는다.

README/README.ko.md에는 BOM 기반 dependency matrix, registry `@Bean`, producer-only,
consumer-DIRECT, consumer-SNS의 copy-paste 가능한 코드/설정, verifier ownership, queue
policy/redrive 전제, logical target, Floci example, diagnostic catalog, consumer-first version
rollout과 rollback, at-least-once/idempotency 경계를 같은 구조로 요약한다. custom
transport/store/verifier back-off도 각 recipe에 표시하고 상세 구현 KDoc은 한국어로 쓴다.

## 11. 수용 기준 매핑

| Issue #471 수용 기준 | 설계/검증 |
| --- | --- |
| local event가 topic/queue로 외부화 | Modulith 2.1 transport + SNS/SQS Floci round-trip |
| remote message가 local handler로 복원 | SQS inbound consumer + direct/SNS notification decode + publisher integration test |
| event type, route key, payload, header mapping | allowlist registry, versioned envelope, bounded attributes, logical target/FIFO key |
| publication completion/retry/dead-letter | actual AWS future completion, Modulith resubmission, 기존 listener retry/redrive 재사용 |
| idempotency/consumer ack boundary | lease/fencing claim SPI, completed duplicate fast-ack, 동기 dispatch 후 ack |
| type/version/unknown 오류 재처리 | typed exception 전파, no ack, visibility 후 Floci redelivery |
| FIFO, publish failure, duplicate, graceful shutdown | unit/contract + Floci matrix |
| Modulith 없을 때 auto-config 없음 | compileOnly + `FilteredClassLoader` context test |

## 12. 알려진 한계와 후속 경계

- real AWS account가 없으므로 IAM resource policy와 실제 AWS redrive timing은 검증하지
  않는다. cross-account ARN은 지원하지 않으며 사용자 합의에 따라 Floci proof를 완료
  근거로 사용한다.
- process-local idempotency는 restart/multi-instance duplicate를 막지 않는다.
- async/transactional local handler의 최종 완료와 SQS ack는 원자적이지 않다.
- SNS subscription provisioning과 DLQ 생성은 애플리케이션/인프라 책임이다.
- Floci API coverage gap은 mock contract로 대체할 수 있지만 해당 항목을 Floci
  round-trip 성공으로 표기하지 않는다.

구현 계획은 이 설계 문서의 독립 6관점 검토와 사용자 승인을 통과한 뒤 별도 문서로
작성한다.
