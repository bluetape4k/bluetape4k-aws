# 설계 명세 — Kinesis coroutine `Flow<Record>` 지원
<!-- 이슈 #81 | bluetape4k-aws aws-kotlin 모듈 -->

**상태**: 초안 v6 (Codex 단계 3 이후 — Latest+checkpoint 없음 fail-fast guard)
**작성자**: debop<br>
**작성일**: 2026-05-17<br>
**모듈**: `aws-kotlin`<br>
**브랜치**: `feat/81-kinesis-dynamodb-streams-flow`

---

## 0. 배경

이슈 #81은 Spring Cloud AWS 4.0 Kinesis binder 기능을 대체하는 bluetape4k 방식으로 coroutine-native Kinesis와 DynamoDB Streams 소비 기능을 요청한다.

**의도적인 v1 결정으로 범위를 축소했다.**
- `aws.sdk.kotlin:dynamodbstreams`는 Maven Central에 **실제로 존재한다**(2026-05-17 확인).
  하지만 DynamoDB Streams에는 서로 다른 shard-list API, 별도의 iterator lifecycle 의미, 다른 record type을 다루는 독립 설계가 필요하다. 같은 PR에 포함하면 변경이 지나치게 커지므로 별도 후속 이슈로 추적한다.
- 이 PR은 **AWS Kotlin SDK Kinesis 기반 single-shard `Flow<Record>` 소비 기능**만 제공한다.

---

## 1. 문제 정의

기존 `aws-kotlin` Kinesis API(`KinesisClientExtensions.kt`)는 개별 `suspend` 호출(`getShardIterator`, `getRecords`)을 제공하지만, 호출자가 polling loop, iterator 만료 복구, backoff, cooperative cancellation을 직접 작성해야 한다. 이는 오류가 발생하기 쉽고 여러 consumer에서 DRY 원칙을 위반한다.

**목표**: 다음 동작을 처리하는 cold `Flow<Record>` primitive를 제공한다.
- iterator lifecycle(최초 조회 + 만료 복구)
- 빈 batch backoff
- 제한된 재시도를 사용하는 throttle exception backoff
- 표준 coroutine 메커니즘을 통한 cooperative cancellation
- shard 종료 감지(`nextShardIterator == null`) → 자연스러운 Flow 완료

---

## 2. 설계 결정

### 2.1 Flow model: `channelFlow {}` 대신 cold `flow {}`

**결정**: 일반 `flow {}` builder를 사용한다.

**근거**:
- single-shard 소비는 한 번에 하나의 `getRecords`만 실행하는 엄격한 순차 처리다.
- `flow {}`의 `emit(record)`은 collector가 느리면 중단되므로 추가 rate-limiting code 없이 GetRecords 호출을 제한하는 자연스러운 backpressure를 제공한다.
- AWS Kinesis 제한은 shard당 초당 `GetRecords` 5회이므로 최소 poll interval은 200ms다. consumer가 200ms보다 느릴 때 `flow {}`의 자연스러운 backpressure가 이 제한을 지키는 데 도움을 준다.
- `channelFlow {}`는 record를 buffering하고 producer rate와 collector rate를 분리하므로 collector가 유휴 상태일 때 초당 5회 제한을 위반하는 busy-polling이 발생할 수 있다.

**검토한 대안**:
- `channelFlow {}` — 기각: buffering으로 producer/collector rate를 분리해 collector가 유휴 상태일 때 초당 5회 제한을 넘을 위험이 있고, single-shard 순차 사용 사례에 이점 없이 내부 channel lifecycle 복잡도만 더한다.
- `callbackFlow {}` — 기각: Kinesis SDK는 push callback이 아니라 polling 기반 `getRecords`를 제공한다. `callbackFlow`는 listener 기반(push) source에 적합하므로 여기서는 이점 없는 인위적인 callback wrapper가 필요하다.

### 2.2 시작 위치: sealed interface `KinesisStartingPosition`

**결정**: 개별 nullable parameter(`type: ShardIteratorType, startingSequenceNumber: String? = null`) 대신 다섯 variant를 갖는 `sealed interface KinesisStartingPosition`을 도입한다.

**근거**:
- 잘못된 조합(예: `TrimHorizon` + `startingSequenceNumber`)을 제거한다.
- iterator 만료 복구를 하나의 `when` 분기로 표현해 명확하고 test하기 쉽게 만든다.
- CLAUDE.md의 "Same-type parameters" 규칙에 따라 여러 parameter 조합을 이름 있는 type으로 감싼다.

**검토한 대안**:
- flat nullable parameter `(type: ShardIteratorType, sequenceNumber: String? = null,
  timestamp: java.time.Instant? = null)` — 기각: compile time에 잘못된 조합을 허용하므로 오류가 발생하기 쉬운 runtime validation으로 잡아야 한다. sealed type은 잘못된 상태를 표현할 수 없게 만든다.
- `(ShardIteratorType, String?)` pair를 갖는 `enum class` — 기각: flat parameter와 같은 유효성 문제가 있고 null을 담는 어색한 enum value가 추가된다.

### 2.3 조정 option: `KinesisRecordFlowOptions` data class

**결정**: 모든 polling 조정 값을 constructor-time validation이 있는 하나의 `data class`에 둔다.

**근거**: 인자 7개를 받는 extension function signature를 피하고 validation을 한곳에 모은다.

### 2.4 AtTimestamp는 `java.time.Instant` 사용

**결정**: `AtTimestamp.timestamp`는 JVM 표준인 `java.time.Instant`를 사용하고, 내부에서 `aws.smithy.kotlin.runtime.time.Instant.fromEpochSeconds(javaInstant.epochSecond, javaInstant.nano)`를 통해 `aws.smithy.kotlin.runtime.time.Instant`로 변환한다.

**근거**: public sealed type에 Smithy의 `Instant`를 노출하면 호출자가 구현 세부 사항인 Smithy runtime dependency를 선언해야 한다. `java.time.Instant`는 JVM 표준이며 모든 호출자의 classpath에 존재한다.

**정밀도 참고**: 변환은 `fromEpochSeconds(seconds, ns: Int)`를 사용해 nanosecond 정밀도를 보존한다(Smithy runtime-core 1.6.14 source 확인). sub-millisecond 정밀도를 조용히 잘라내는 `fromEpochMilliseconds(toEpochMilli())`를 사용하지 않는다.

### 2.5 DynamoDB Streams: 의도적으로 연기

`aws.sdk.kotlin:dynamodbstreams`는 다른 polling model(Kinesis client가 아니라 DynamoDB Streams client를 통해 `getShardIterator` + `getRecords` 사용), 다른 record type(`aws.sdk.kotlin.services.dynamodbstreams.model.Record`), 다른 iterator 만료 의미(24시간 shard window, Kinesis는 7일)를 갖는 별도의 Maven artifact다.

둘을 하나의 PR에 합치면 독립적인 polling loop 두 개, sealed-type hierarchy 두 개, test matrix 확장 두 개를 포함한 지나치게 큰 변경이 된다. DynamoDB Streams는 여기서 확립한 같은 설계 pattern을 사용하며 전용 후속 이슈에서 추적한다.

**참고**: 이전 조사에서는 이 artifact가 "존재하지 않는다"고 잘못 기록했다. 2026-05-17 Maven Central 검색으로 `aws.sdk.kotlin:dynamodbstreams-jvm`이 배포됨을 확인했다.

---

## 3. 공개 API

### 3.1 `KinesisStartingPosition` (새 파일)

이 sealed interface는 의도적으로 **완전하게 열거**한다. AWS SDK의 다섯 `ShardIteratorType` enum value와 1:1로 대응한다. 새 variant 추가는 `else` 분기가 없는 downstream `when` 식에 **source-breaking change**이므로 엄격한 SemVer에서는 **major version bump**가 필요하다. minor version 사이의 forward compatibility가 필요한 downstream 호출자는 `else -> error("unsupported")` 분기를 추가해야 한다. AWS가 새 iterator type을 추가하면 이 library는 major release에서 새 variant를 추가한다.

**resharding / child-shard 참고**: resharding(split 또는 merge)으로 shard가 끝에 도달하면 `GetRecords`는 `nextShardIterator == null`을 반환하고 response에서 child shard ID를 제공한다. v1은 child shard를 따라가지 **않으며**, `nextShardIterator == null`일 때 Flow가 정상 완료된다. multi-shard fan-out이 필요한 호출자는 `ListShards`를 호출하고 shard마다 하나의 `recordFlow`를 만들어야 한다. 이 동작은 KDoc과 README에 문서화한다.

```kotlin
sealed interface KinesisStartingPosition : java.io.Serializable {

    /**
     * Starts reading from the oldest record in the shard (trim horizon).
     */
    data object TrimHorizon : KinesisStartingPosition {
        private const val serialVersionUID: Long = 1L
        private fun readResolve(): Any = TrimHorizon
    }

    /**
     * Starts reading from the newest record (records written after the iterator was obtained).
     *
     * ## Behavior / Contract
     * On iterator-expiry recovery with no prior checkpoint (`lastSeenSequenceNumber == null`),
     * the Flow throws `ExpiredIteratorException` immediately rather than re-fetching with `Latest`.
     * Re-fetching `Latest` at recovery time would silently skip records produced between the
     * original iterator creation and the recovery point (AWS LATEST semantics move forward in time).
     * Callers must handle this exception and restart the flow if silent data loss is unacceptable.
     */
    data object Latest : KinesisStartingPosition {
        private const val serialVersionUID: Long = 1L
        private fun readResolve(): Any = Latest
    }

    /**
     * Starts reading from the record with the given sequence number (inclusive).
     *
     * ## Behavior / Contract
     * Use when resuming from a checkpoint that holds the last **processed** record's sequence
     * number and you want to re-process it (idempotent consumer with deduplication in your
     * handler).
     *
     * ## Deserialization safety
     * Java deserialization bypasses the `init` block. A `private readObject` method re-runs
     * validation after deserialization to maintain the invariant that `sequenceNumber` is never
     * blank, even when instances are constructed via Java serialization.
     */
    data class AtSequenceNumber(val sequenceNumber: String) : KinesisStartingPosition {
        init { sequenceNumber.requireNotBlank("sequenceNumber") }
        @Suppress("UnusedPrivateMember")
        private fun readObject(stream: java.io.ObjectInputStream) {
            stream.defaultReadObject()
            sequenceNumber.requireNotBlank("sequenceNumber")
        }
        companion object { private const val serialVersionUID: Long = 1L }
    }

    /**
     * Starts reading from the record after the given sequence number (exclusive).
     *
     * ## Behavior / Contract
     * Use when resuming from a checkpoint that holds the last **processed** record's sequence
     * number and you want to skip it (within a single collection session, no record is emitted
     * twice; cross-session deduplication requires external checkpoint storage).
     *
     * ## Deserialization safety
     * Same as `AtSequenceNumber` — `readObject` re-validates after deserialization.
     */
    data class AfterSequenceNumber(val sequenceNumber: String) : KinesisStartingPosition {
        init { sequenceNumber.requireNotBlank("sequenceNumber") }
        @Suppress("UnusedPrivateMember")
        private fun readObject(stream: java.io.ObjectInputStream) {
            stream.defaultReadObject()
            sequenceNumber.requireNotBlank("sequenceNumber")
        }
        companion object { private const val serialVersionUID: Long = 1L }
    }

    /**
     * Starts reading from the record at or after the given timestamp.
     *
     * @param timestamp Standard JVM `java.time.Instant`. Converted internally to the AWS SDK
     *   Smithy `Instant` via `Instant.fromEpochSeconds(epochSecond, nano)` (nanosecond precision
     *   preserved). Callers do not need a direct dependency on Smithy runtime.
     */
    data class AtTimestamp(val timestamp: java.time.Instant) : KinesisStartingPosition {
        companion object { private const val serialVersionUID: Long = 1L }
    }
}
```

### 3.2 `KinesisRecordFlowOptions` (새 파일)

`init`의 모든 `require(...)` 호출에는 다음처럼 설명이 분명한 message를 포함해야 한다.
```kotlin
require(pollInterval >= MIN_POLL_INTERVAL) {
    "pollInterval must be >= $MIN_POLL_INTERVAL (AWS 5 GetRecords/sec/shard limit), got $pollInterval"
}
```

```kotlin
data class KinesisRecordFlowOptions(
    /** Maximum records returned per GetRecords call. AWS ceiling: 10,000. Default: 100. */
    val batchLimit: Int = DEFAULT_BATCH_LIMIT,
    /**
     * Minimum delay between GetRecords calls when records are returned.
     * AWS rate limit: 5 calls/shard/second → floor of 200 ms.
     */
    val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    /**
     * Delay applied when GetRecords returns an empty batch (shard idle).
     * Must be >= pollInterval. Default: 1 s. Tune up for price-sensitive consumers,
     * down for latency-sensitive ones (never below pollInterval).
     */
    val emptyBackoff: Duration = DEFAULT_EMPTY_BACKOFF,
    /** Maximum consecutive iterator-expiry recoveries before propagating the exception. */
    val maxIteratorRetries: Int = DEFAULT_MAX_ITERATOR_RETRIES,
    /**
     * Initial delay for exponential throttle backoff.
     * Grows as min(initialThrottleBackoff × 2^attempt, maxThrottleBackoff).
     * Default: 500 ms (appropriate for typical provisioned-throughput shards;
     * reduce to 100 ms for bursty producers).
     */
    val initialThrottleBackoff: Duration = 500.milliseconds,
    /**
     * Upper bound on throttle backoff delay. Default: 30 s.
     * Increase to 60 s for steady-state or price-sensitive consumers.
     */
    val maxThrottleBackoff: Duration = 30.seconds,
    /** Maximum consecutive throttle retries (applies to all retryable KinesisExceptions). */
    val maxThrottleRetries: Int = 5,
) : java.io.Serializable {
    init {
        require(batchLimit in 1..MAX_KINESIS_BATCH_LIMIT) {
            "batchLimit must be in 1..$MAX_KINESIS_BATCH_LIMIT, got $batchLimit"
        }
        require(pollInterval >= MIN_POLL_INTERVAL) {
            "pollInterval must be >= $MIN_POLL_INTERVAL (AWS 5 GetRecords/sec/shard limit), got $pollInterval"
        }
        require(emptyBackoff >= pollInterval) {
            "emptyBackoff ($emptyBackoff) must be >= pollInterval ($pollInterval); emptyBackoff replaces pollInterval on empty batches"
        }
        require(maxIteratorRetries >= 0) { "maxIteratorRetries must be >= 0, got $maxIteratorRetries" }
        require(initialThrottleBackoff > Duration.ZERO) { "initialThrottleBackoff must be > 0" }
        require(maxThrottleBackoff >= initialThrottleBackoff) {
            "maxThrottleBackoff ($maxThrottleBackoff) must be >= initialThrottleBackoff ($initialThrottleBackoff)"
        }
        require(maxThrottleRetries >= 0) { "maxThrottleRetries must be >= 0, got $maxThrottleRetries" }
    }
    companion object {
        private const val serialVersionUID: Long = 1L
        const val MAX_KINESIS_BATCH_LIMIT = 10_000
        val MIN_POLL_INTERVAL = 200.milliseconds
        val DEFAULT_POLL_INTERVAL = 200.milliseconds
        val DEFAULT_EMPTY_BACKOFF = 1.seconds
        const val DEFAULT_BATCH_LIMIT = 100
        const val DEFAULT_MAX_ITERATOR_RETRIES = 3
    }
}
```

### 3.3 `KinesisClient.recordFlow()` (새 파일)

```kotlin
fun KinesisClient.recordFlow(
    streamName: String,
    shardId: String,
    position: KinesisStartingPosition = KinesisStartingPosition.TrimHorizon,
    options: KinesisRecordFlowOptions = KinesisRecordFlowOptions(),
): Flow<Record>
```

**동작 / 계약**:

1. **Cold**: 각 `collect {}`는 자체 shard iterator와 checkpoint state를 갖는 독립적인 poll loop를 시작한다. subscription 사이에 shared mutable state는 없다. `getShardIterator`는 `recordFlow` function body가 아니라 반드시 **`flow { }` lambda 내부**에서 호출해 Flow를 cold로 유지한다.

2. **client lifetime**: `KinesisClient`는 활성화된 모든 `collect {}` 호출의 **전체 실행 시간** 동안 열려 있어야 한다. Flow를 collect하는 중 client를 닫으면 기반 connection pool에서 SDK-level exception이 발생한다. `withKinesisClient { }`를 사용한다면 block이 끝나기 전에 모든 `collect` 호출이 완료되어야 한다.

3. **iterator 만료 복구** (`ExpiredIteratorException`):
   - **사례 A** — 하나 이상의 record를 emit했다(`lastSeenSequenceNumber != null`): `AfterSequenceNumber(lastSeenSequenceNumber)`로 다시 조회한다. 한 번의 `collect {}` 호출 안에서는 **같은 record를 두 번 emit하지 않는다**(하나의 collection session에서 각 record를 최대 한 번 emit). 새로운 `collect {}`는 원래 위치에서 새로 시작하므로 호출자는 session 간 중복 제거를 위해 checkpoint를 외부에 저장해야 한다.
   - **사례 B** — 아직 record를 emit하지 않았다: 원래 `KinesisStartingPosition`으로 다시 조회한다.
   - **`lastSeenSequenceNumber` 갱신 시점**: collector가 record를 수락한 뒤인 **`emit(record)` 반환 후** 갱신한다. `emit`이 `CancellationException`을 던지면 갱신하지 않아야 한다. 취소된 emit이 checkpoint를 진행시키지 않는지 unit test로 검증한다.
   - 두 retry counter(`iteratorRetryCount`, `throttleRetryCount`)는 성공한 `getRecords` 호출마다 **0으로 초기화**한다. 따라서 `maxIteratorRetries`는 전체 lifetime failure가 아니라 **연속** failure를 제한한다.
   - `options.maxIteratorRetries` 횟수만큼 연속 실패하면 `streamName`, `shardId`, retry count를 포함한 ERROR log를 남긴 뒤 exception을 전파한다.

4. **throttle 복구** (`ProvisionedThroughputExceededException`, `e.sdkErrorMetadata.isRetryable == true`인 모든 exception은 같은 budget 공유):

   **SDK retry layer**: AWS Kotlin SDK는 exception이 이 library code에 도달하기 전에 자체 retry strategy를 적용한다. Flow-level retry는 SDK가 자체 budget을 모두 소진한 **뒤에** 동작한다. retry하지 않는 SDK strategy(예: `RetryStrategy.None` 또는 `maxAttempts = 1`)를 설정하면 SDK failure가 Flow-level retry로 바로 전달되고, SDK 기본값을 사용하면 retry가 중첩된다. README에 이 상호작용을 문서화해야 한다.

   **Flow-level retry**: full jitter를 사용하는 exponential backoff:
   `delay = random(0, min(initialThrottleBackoff × 2^attempt, maxThrottleBackoff))`.
   `attempt`가 클 때 `Duration` overflow를 방지하도록 jitter 호출 전에 saturating arithmetic(`coerceAtMost(maxThrottleBackoff)`)을 사용한다. full jitter는 여러 consumer가 동시에 retry할 때 thundering herd를 방지한다. retry할 때마다 attempt count와 delay를 포함한 WARN log를 남긴다. Flow level에서 `options.maxThrottleRetries` 횟수만큼 연속 throttle failure가 발생하면 `streamName`, `shardId`, attempt count, 마지막 exception message를 포함한 ERROR log와 함께 exception을 전파한다.

5. **poll interval 강제**: record 반환 여부와 관계없이 모든 `getRecords` 호출 후 다음 호출 전에 최소 `options.pollInterval`만큼 기다린다. collector 속도나 downstream `buffer()`/`flowOn` 사용과 무관하게 AWS의 shard당 초당 5회 호출 제한을 지킨다. `flow {}`의 자연스러운 backpressure는 명시적인 delay를 대체하는 것이 아니라 _추가_ throttle이다. **empty-batch backoff**: `getRecords`가 빈 list와 null이 아닌 `nextShardIterator`를 반환하면 다음 poll 전에 항상 `pollInterval` 이상인 `options.emptyBackoff`만큼 기다린다.

6. **shard 종료**: `nextShardIterator == null`이면 Flow가 exception 없이 정상 완료된다.

7. **cancellation**: retry logic보다 먼저 항상 `CancellationException`을 다시 던진다. 모든 suspension point(`emit`, `delay`, SDK 호출)는 cooperative cancellation checkpoint다.

8. **logging 정책**:
   - sequence number와 shard iterator는 DEBUG level에서만 기록하고 INFO나 WARN에는 기록하지 않는다.
   - library는 어떤 level에서도 record data(`Record.data`)를 **절대** 기록하지 않는다.
   - `KinesisStartingPosition`을 보간하는 error message에는 sequence number value가 아니라 type name(예: `"AfterSequenceNumber"`)만 포함한다.

9. **SDK timeout policy**: 이 library는 개별 SDK 호출에 timeout을 설정하지 않는다. network partition에서 무기한 중단되는 일을 방지하려면 운영자가 `KinesisClient`의 HTTP client에 적절한 read timeout을 설정해야 한다. custom `HttpClientEngine`을 전달하거나 client-level setting을 override하려면 `kinesisClientOf(httpClient = ..., builder = { ... })`를 사용한다. `withKinesisClient {}`는 HTTP client parameter를 받지 **않고** 내부에서 `kinesisClientOf()`에 위임한다. 이 내용은 README tuning section에 문서화한다.

10. **`getShardIterator` null response**: `streamName`과 `shardId`를 포함하되 position의 sequence number value는 제외한 diagnostic message와 함께 `IllegalStateException`을 던진다. 유효한 AWS endpoint는 유효한 stream/shard 조합에 null을 반환하지 않으므로 재시도할 수 없는 programming error다.

---

## 4. 오류 처리 matrix

| exception | 동작 |
|---|---|
| `CancellationException` | 조건 없이 다시 던진다(첫 번째 catch). |
| `ExpiredIteratorException` | 시도마다 WARN log를 남기고 `maxIteratorRetries`까지 복구한 뒤 ERROR log와 함께 전파한다. **특수 사례**: `lastSeenSequenceNumber == null`이고 `currentPosition is Latest`이면 재시도 없이 즉시 던진다. `Latest`로 다시 조회하면 원래 iterator 생성 시점과 재조회 사이(5분 TTL window)에 작성된 record를 조용히 건너뛸 수 있다. |
| `ProvisionedThroughputExceededException` | backoff delay와 함께 WARN log를 남기고 `maxThrottleRetries`까지 exponential backoff한 뒤 ERROR log와 함께 전파한다. |
| `sdkErrorMetadata.isRetryable == true`인 기타 `KinesisException` | throttle과 동일하게 처리한다(`maxThrottleRetries` budget 공유). |
| `sdkErrorMetadata.isRetryable == false`인 기타 `KinesisException` | catch block 내부의 `if (!e.sdkErrorMetadata.isRetryable) throw e` guard에서 즉시 전파한다. |
| `nextShardIterator == null` | 오류가 아니며 Flow가 정상 완료된다. |
| `getShardIterator`가 null 반환 | sequence number 없이 `streamName` + `shardId` context를 포함한 `IllegalStateException`을 던진다. |

---

## 5. 파일 계획

| 파일 | 유형 |
|---|---|
| `…/kinesis/KinesisStartingPosition.kt` | 신규 — sealed type |
| `…/kinesis/KinesisRecordFlowOptions.kt` | 신규 — option data class |
| `…/kinesis/KinesisRecordFlow.kt` | 신규 — Flow extension + private helper |
| `…/kinesis/KinesisStartingPositionTest.kt` | 신규 — unit test |
| `…/kinesis/KinesisRecordFlowOptionsTest.kt` | 신규 — validation unit test |
| `…/kinesis/KinesisRecordFlowUnitTest.kt` | 신규 — virtual-time test(fake/MockK) |
| `…/kinesis/KinesisRecordFlowTest.kt` | 신규 — LocalStack integration test |
| `aws-kotlin/README.md` | 수정 — tuning table을 포함한 Kinesis Flow section |
| `aws-kotlin/README.ko.md` | 수정 — 같은 section(한국어) |

---

## 6. 범위 제외(v1)

- multi-shard fan-out / `ListShards` 자동 탐색(v2)
- lag monitoring용 `millisBehindLatest`를 전달하는 `Flow<GetRecordsResponse>` variant(v2)
- DynamoDB Streams(별도 후속 이슈 — 의도적인 v1 범위 결정, SDK는 `aws.sdk.kotlin:dynamodbstreams-jvm`에 존재, §2.5 참고)
- enhanced fan-out(`SubscribeToShard`) — 다른 runtime model을 위한 별도 설계
- metrics/tracing integration(v2) — v1 signal은 retry event의 WARN/ERROR logging

---

## 7. DoD 확인 목록

- [ ] `KinesisStartingPosition.kt` 생성, variant 다섯 개, `Serializable` + `serialVersionUID`, `data object`의 `readResolve()`, `java.time.Instant`를 사용하는 `AtTimestamp`, deserialization 후 validation을 다시 실행하는 `private readObject`가 있는 `AtSequenceNumber`와 `AfterSequenceNumber`.
- [ ] `KinesisRecordFlowOptions.kt` 생성, 설명이 분명한 message와 함께 모든 invariant를 `init`에서 검증.
- [ ] `KinesisRecordFlow.kt` 생성, cold `Flow<Record>`를 반환하는 `recordFlow()` extension.
- [ ] **`getShardIterator` 호출이 `recordFlow` function body가 아니라 `flow { }` lambda body 안에 있음**(cold 계약).
- [ ] `recordFlow()`의 KDoc에 client lifetime 문서화.
- [ ] iterator-expiry 복구의 사례 A(마지막 sequence 이후)와 사례 B(원래 position) 구현 및 test.
- [ ] 성공한 `getRecords` 호출마다 retry counter를 0으로 초기화.
- [ ] iterator-expiry 시도마다 WARN log, 소진 시 `streamName`, `shardId`, count를 포함한 ERROR log.
- [ ] throttle 시도마다 WARN log, 소진 시 `streamName`, `shardId`, count, 마지막 error를 포함한 ERROR log.
- [ ] logging policy: sequence number는 DEBUG에서만 기록, `Record.data`는 기록하지 않음, error message에서 sequence number value redaction.
- [ ] cancellation: 모든 catch block에서 retry logic보다 먼저 `CancellationException`을 다시 던짐.
- [ ] throttle backoff: **full jitter** exponential인 `random(0, min(initial × 2^attempt, max))`, `maxThrottleBackoff`로 제한, `maxThrottleRetries`로 횟수 제한, SDK retry layer 상호작용을 README에 문서화.
- [ ] 모든 `getRecords` 호출 후(non-empty와 empty batch) `pollInterval` 강제, 빠른 collector에서도 delay가 적용되는지 unit test로 검증.
- [ ] empty-batch backoff: `records.isEmpty()`일 때 `emptyBackoff` delay.
- [ ] shard 종료: `nextShardIterator == null` → Flow 정상 완료.
- [ ] virtual-time unit test: cancellation, empty-batch, 만료 사례 A+B, throttle, retry 초과, shard 종료, retry-counter 초기화.
- [ ] LocalStack integration test: 생성→active→putRecords(N records)→recordFlow.take(N).toList()→N개 record 수신 검증→삭제. open shard는 `nextShardIterator == null`을 emit하지 않으므로 `take(N)`으로 collection을 제한하고 shard 종료는 mock unit test로 검증한다. 중간에 Flow를 취소하는 cancellation path도 integration test한다.
- [ ] `./gradlew :aws-kotlin:test` 통과.
- [ ] `README.md` + `README.ko.md`: Kinesis Flow section + tuning reference table + client-lifetime warning + checkpoint 지침.
- [ ] 세 public API 파일 모두 영문 KDoc 제공.
- [ ] README에 SDK timeout policy 문서화: 내장 timeout 없음, `withKinesisClient`가 아닌 `kinesisClientOf(httpClient = ...)`로 설정, README tuning section에서 명시적으로 참조.

---

## 부록 — 검토 iteration 기록

| round | 단계 | 검토자 | P0 | P1 | P2 | P3 | commit |
|---|---|---|---|---|---|---|---|
| R1 | 단계 1(4개 관점) | 개발자 | 0 | 2 | 3 | 2 | (R1 수정 전) |
| R1 | 단계 1(4개 관점) | 보안 | 0 | 0 | 2 | 1 | (R1 수정 전) |
| R1 | 단계 1(4개 관점) | Ops/SRE | 0 | 3 | 3 | 1 | (R1 수정 전) |
| R1 | 단계 1(4개 관점) | 사용자/호출자 | 0 | 3 | 2 | 0 | (R1 수정 전) |
| R1 합계 | — | 종합 | 0 | 8 | 10 | 4 | spec v2 |
| R1 → v2 | 단계 1의 HIGH 모두 반영 | — | 0 | 0 | — | — | spec v2 |
| R2 | 단계 2 비평 | 비평가 | 0 | 2 | 2 | 0 | spec v2 |
| R2 → v3 | 단계 2의 HIGH+MEDIUM 모두 반영 | H-A: ERROR log, H-B: kinesisClientOf, M-A: 대안, M-B: DoD | 0 | 0 | 0 | — | spec v3 |
| R3 | 단계 3 Codex(gpt-5.5) | Codex | 0 | 4 | 7 | 3 | spec v3 |
| R3 | 6단계 자문(Opus) | 자문 | 0 | 2 | 4 | 1 | spec v3 |
| R3 종합 | — | 종합 | 0 | 6 | 11 | 4 | spec v3 |
| R3 → v4 | P1 모두 반영 | DynamoDB Streams 근거, AtTimestamp ns 정밀도, pollInterval 강제, SDK/Flow retry layer, readObject, LocalStack take(N), jitter | 0 | 0 | — | — | spec v4 |
| R4 | 단계 3-R 단계 2 비평(계획 검토) | 비평가 | 0 | 2 | 0 | 0 | plan v1 |
| R4 → v5 | spec §3.3/#4/§4/§6 수정 | `sdkErrorMetadata.isRetryable` accessor, DynamoDB Streams §6 명확화 | 0 | 0 | — | — | spec v5 |
| R5 | Codex 단계 3(plan v2.1 검토) | Codex gpt-5.5 | 0 | 1 | 0 | 0 | plan v2.1 |
| R5 → v6 | Latest+checkpoint 없음 fail-fast | §3.3 ExpiredIteratorException 행, Latest KDoc, plan v2.2 T3/T6 checklist | 0 | 0 | — | — | spec v6 |
