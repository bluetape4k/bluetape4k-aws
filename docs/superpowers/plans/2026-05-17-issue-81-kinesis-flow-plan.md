# 구현 계획 — Kinesis Coroutine `Flow<Record>` (#81)

**명세**: `docs/superpowers/specs/2026-05-17-issue-81-kinesis-flow-design.md` (v5)
**Branch**: `feat/81-kinesis-dynamodb-streams-flow`
**작성일**: 2026-05-17
**Module**: `aws-kotlin`
**계획 version**: v2.2 (Step 3-R Codex Phase 3 이후 — Latest+checkpoint 없음 fail-fast guard)

DynamoDB Streams는 의도적으로 후속 issue로 보류한다. 이 PR은 Kinesis만 다룬다.

---

## 작업 목록

### T1 — `KinesisStartingPosition.kt`
**복잡도: 낮음**
**파일**: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisStartingPosition.kt`

```
sealed interface KinesisStartingPosition : Serializable {
  data object TrimHorizon     { serialVersionUID; readResolve }
  data object Latest          { serialVersionUID; readResolve }
  data class  AtSequenceNumber(val sequenceNumber: String)  { init + readObject }
  data class  AfterSequenceNumber(val sequenceNumber: String) { init + readObject }
  data class  AtTimestamp(val timestamp: java.time.Instant)   { serialVersionUID }
}
```

Checklist:
- [ ] variant 5개 모두 `KinesisStartingPosition : java.io.Serializable` 구현
- [ ] `data object` variant: `private const val serialVersionUID: Long = 1L` + `private fun readResolve(): Any = <This>`
- [ ] `data class` variant: `companion object { private const val serialVersionUID: Long = 1L }`
- [ ] `AtSequenceNumber` + `AfterSequenceNumber`: `init { sequenceNumber.requireNotBlank("sequenceNumber") }` + `stream.defaultReadObject()` 호출 후 `requireNotBlank`를 다시 실행하는 `private fun readObject(stream: ObjectInputStream)`
- [ ] 모든 variant에 영문 KDoc 작성(동작, checkpoint 안내, deserialization 안전성)

---

### T2 — `KinesisRecordFlowOptions.kt`
**복잡도: 낮음**
**파일**: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlowOptions.kt`

```kotlin
data class KinesisRecordFlowOptions(
    val batchLimit: Int = DEFAULT_BATCH_LIMIT,
    val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    val emptyBackoff: Duration = DEFAULT_EMPTY_BACKOFF,
    val maxIteratorRetries: Int = DEFAULT_MAX_ITERATOR_RETRIES,
    val initialThrottleBackoff: Duration = DEFAULT_INITIAL_THROTTLE_BACKOFF,
    val maxThrottleBackoff: Duration = DEFAULT_MAX_THROTTLE_BACKOFF,
    val maxThrottleRetries: Int = DEFAULT_MAX_THROTTLE_RETRIES,
) : java.io.Serializable
```

Checklist:
- [ ] `init`에서 설명적인 message로 invariant 7개 모두 검증(명세 §3.2)
- [ ] `companion object`에서 `serialVersionUID`와 이름 있는 상수 **9개** 모두 선언:
  - `MAX_KINESIS_BATCH_LIMIT = 10_000`
  - `MIN_POLL_INTERVAL = 200.milliseconds`
  - `DEFAULT_BATCH_LIMIT = 100`
  - `DEFAULT_POLL_INTERVAL = 200.milliseconds`
  - `DEFAULT_EMPTY_BACKOFF = 1.seconds`
  - `DEFAULT_MAX_ITERATOR_RETRIES = 3`
  - `DEFAULT_INITIAL_THROTTLE_BACKOFF = 500.milliseconds`
  - `DEFAULT_MAX_THROTTLE_BACKOFF = 30.seconds`
  - `DEFAULT_MAX_THROTTLE_RETRIES = 5`
- [ ] class 및 각 parameter에 영문 KDoc 작성(특히 Flow의 jitter를 설명하는 throttle backoff field)

---

### T3 — `KinesisRecordFlow.kt`  ← 핵심 logic
**복잡도: 높음**
**파일**: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlow.kt`

Public function:
```kotlin
fun KinesisClient.recordFlow(
    streamName: String,
    shardId: String,
    position: KinesisStartingPosition = TrimHorizon,
    options: KinesisRecordFlowOptions = KinesisRecordFlowOptions(),
): Flow<Record>
```

#### 구현 구조(P0 수정 반영):

```kotlin
fun KinesisClient.recordFlow(
    streamName: String,
    shardId: String,
    position: KinesisStartingPosition = KinesisStartingPosition.TrimHorizon,
    options: KinesisRecordFlowOptions = KinesisRecordFlowOptions(),
): Flow<Record> = flow {
    var currentPosition = position   // var — updated on ExpiredIteratorException recovery
    var lastSeenSequenceNumber: String? = null
    var iteratorRetryCount = 0
    var throttleRetryCount = 0
    // null = initial fetch not yet done; inside try block → throttle recovery applies to initial fetch
    var shardIterator: String? = null

    while (true) {
        currentCoroutineContext().ensureActive()   // cancellation checkpoint

        try {
            // Initial fetch on first iteration (and after null-reset on throttle).
            // Must be inside the try block so throttle/retryable KinesisException is handled.
            if (shardIterator == null) {
                shardIterator = fetchShardIterator(streamName, shardId, currentPosition)
            }

            val response = getRecords {
                limit = options.batchLimit
                this.shardIterator = shardIterator!!
            }

            // Reset retry counters on every successful getRecords call
            iteratorRetryCount = 0
            throttleRetryCount = 0

            // Emit records — update lastSeenSequenceNumber AFTER emit() returns
            for (record in response.records()) {
                emit(record)
                lastSeenSequenceNumber = record.sequenceNumber()
            }

            // Shard end: nextShardIterator == null → Flow completes normally
            if (response.nextShardIterator() == null) return@flow
            shardIterator = response.nextShardIterator()!!

            // Poll interval enforcement (always, even on non-empty batches)
            val pollDelay = if (response.records().isEmpty()) options.emptyBackoff else options.pollInterval
            delay(pollDelay)

        } catch (e: CancellationException) {
            throw e   // always first — never retry on cancellation

        } catch (e: ExpiredIteratorException) {
            iteratorRetryCount++
            if (iteratorRetryCount > options.maxIteratorRetries) {
                log.error {
                    "Shard iterator expired after $iteratorRetryCount attempts: " +
                    "stream=$streamName shard=$shardId"
                }
                throw e
            }
            // Fail-fast for Latest with no checkpoint: re-fetching Latest silently skips records
            // written between original iterator creation and recovery (5-min TTL window).
            val lastSeen = lastSeenSequenceNumber
            if (lastSeen == null && currentPosition is KinesisStartingPosition.Latest) {
                log.error {
                    "Iterator expired for Latest position with no checkpoint: " +
                    "stream=$streamName shard=$shardId — cannot recover without data loss"
                }
                throw e
            }
            log.warn {
                "Shard iterator expired (attempt $iteratorRetryCount/${options.maxIteratorRetries}): " +
                "stream=$streamName shard=$shardId"
            }
            // Update currentPosition and reset shardIterator to null.
            // Re-fetch happens in the NEXT iteration's null-check INSIDE the try block (retry scope),
            // so retryable KinesisException from fetchShardIterator is handled by the KinesisException catch.
            currentPosition = lastSeen
                ?.let { AfterSequenceNumber(it) }
                ?: currentPosition
            shardIterator = null

        } catch (e: KinesisException) {
            // Guard: non-retryable exceptions propagate immediately (P0 fix — no Kotlin guard clause syntax)
            if (!e.sdkErrorMetadata.isRetryable) throw e
            throttleRetryCount++
            if (throttleRetryCount > options.maxThrottleRetries) {
                log.error {
                    "Throttle retries exhausted after $throttleRetryCount attempts: " +
                    "stream=$streamName shard=$shardId error=${e.message}"
                }
                throw e
            }
            log.warn {
                "Throttle retry $throttleRetryCount/${options.maxThrottleRetries}: " +
                "stream=$streamName shard=$shardId"
            }
            val backoff = jitteredBackoff(throttleRetryCount, options)
            delay(backoff)
        }
    }
}
```

#### Private helper:

```kotlin
/**
 * Maps KinesisStartingPosition → ShardIteratorType and calls getShardIterator.
 * AtTimestamp uses Instant.fromEpochSeconds(epochSecond, nano) for nanosecond precision.
 * Returns non-null iterator string or throws IllegalStateException.
 * Visibility: private (file-local helper, not part of public API)
 */
private suspend fun KinesisClient.fetchShardIterator(
    streamName: String,
    shardId: String,
    position: KinesisStartingPosition,
): String {
    // Full 5-variant mapping (exhaustive when expression — no else branch needed):
    // TrimHorizon       → ShardIteratorType.TrimHorizon,       seq=null,  ts=null
    // Latest            → ShardIteratorType.Latest,            seq=null,  ts=null
    // AtSequenceNumber  → ShardIteratorType.AtSequenceNumber,  seq=pos.sequenceNumber, ts=null
    // AfterSequenceNumber → ShardIteratorType.AfterSequenceNumber, seq=pos.sequenceNumber, ts=null
    // AtTimestamp       → ShardIteratorType.AtTimestamp,       seq=null,
    //                     ts=Instant.fromEpochSeconds(pos.timestamp.epochSecond, pos.timestamp.nano)
    return getShardIterator {
        this.streamName = streamName
        this.shardId = shardId
        when (position) {
            is TrimHorizon       -> shardIteratorType = ShardIteratorType.TrimHorizon
            is Latest            -> shardIteratorType = ShardIteratorType.Latest
            is AtSequenceNumber  -> {
                shardIteratorType = ShardIteratorType.AtSequenceNumber
                startingSequenceNumber = position.sequenceNumber
            }
            is AfterSequenceNumber -> {
                shardIteratorType = ShardIteratorType.AfterSequenceNumber
                startingSequenceNumber = position.sequenceNumber
            }
            is AtTimestamp -> {
                shardIteratorType = ShardIteratorType.AtTimestamp
                // nanosecond precision — do NOT use fromEpochMilliseconds(toEpochMilli())
                timestamp = aws.smithy.kotlin.runtime.time.Instant
                    .fromEpochSeconds(position.timestamp.epochSecond, position.timestamp.nano)
            }
        }
    }.shardIterator ?: error(
        "getShardIterator returned null for stream=$streamName shard=$shardId"
    )
}

/**
 * Full-jitter exponential backoff.
 * delay = random(0, min(initialThrottleBackoff × 2^(attempt-1), maxThrottleBackoff))
 * coerceAtMost applied BEFORE nextLong to prevent Duration overflow when attempt is large.
 * Uses kotlin.random.Random.Default (coroutine/virtual-thread safe — not ThreadLocalRandom).
 * Visibility: internal (testable from unit tests without exposing to consumers)
 *
 * @param attempt 1-indexed retry count (1 = first retry)
 */
internal fun jitteredBackoff(attempt: Int, options: KinesisRecordFlowOptions): Duration {
    val cappedBase = (options.initialThrottleBackoff * (1L shl (attempt - 1).coerceAtMost(30)))
        .coerceAtMost(options.maxThrottleBackoff)
    return Random.Default.nextLong(0L, cappedBase.inWholeMilliseconds + 1L).milliseconds
}
```

#### Checklist(T3):
- [ ] `currentPosition`은 `val`이 아닌 `var`이며 직접 fetch하지 않고 `ExpiredIteratorException` 복구 시 갱신
- [ ] `ExpiredIteratorException` catch의 fail-fast guard: `lastSeenSequenceNumber == null && currentPosition is Latest`이면 즉시 throw(복구 시 `Latest` 재조회로 인한 조용한 data loss 방지)
- [ ] `shardIterator`를 `null`로 초기화하고 최초 `fetchShardIterator`를 `try {}` block(retry scope) 안에서 실행
- [ ] `ExpiredIteratorException` catch에서 `currentPosition = recoveryPosition; shardIterator = null` 설정. catch block에서 직접 fetch하지 않고 다음 iteration의 try 내부 null-check에 위임해 KinesisException retry scope 적용
- [ ] `getShardIterator`는 `recordFlow` body가 아니라 `flow {}` lambda 내부에서 `fetchShardIterator`를 통해 호출
- [ ] `emit(record)` 반환 후 `lastSeenSequenceNumber` 갱신
- [ ] 모든 retry logic보다 먼저 `CancellationException`을 catch해 다시 throw
- [ ] `catch (e: KinesisException)`에서 `if (!e.sdkErrorMetadata.isRetryable) throw e` inner guard 사용
- [ ] Kotlin이 지원하지 않는 catch type 뒤 catch-guard 구문(`if (condition)`)을 사용하지 않음
- [ ] `KinesisException`에 없는 `e.isRetryable` 대신 `e.sdkErrorMetadata.isRetryable` 사용
- [ ] `getRecords` 호출 성공마다 retry counter를 0으로 reset
- [ ] non-empty batch 뒤 `pollInterval`, empty batch 뒤 `emptyBackoff` delay
- [ ] iterator-expiry 시도마다 WARN, 소진 시 ERROR log(streamName, shardId, count)
- [ ] throttle 시도마다 WARN, 소진 시 ERROR log(streamName, shardId, count, 마지막 error message)
- [ ] Logging 정책: sequence number는 DEBUG에만 기록하고 `Record.data`는 기록하지 않으며 error message에는 sequence number 값이 아닌 type 이름만 포함
- [ ] `fetchShardIterator` is `private suspend fun KinesisClient.fetchShardIterator(...)`
- [ ] `jitteredBackoff`는 test할 수 있지만 public API가 아닌 `internal fun jitteredBackoff(...)`
- [ ] `jitteredBackoff`는 `ThreadLocalRandom`이 아닌 `kotlin.random.Random.Default.nextLong()` 사용
- [ ] overflow 방지를 위해 `nextLong`보다 먼저 `coerceAtMost(maxThrottleBackoff)`를 적용하는 jitter 공식
- [ ] `fetchShardIterator`의 exhaustive `when`이 variant 5개 모두 처리. null response → `streamName` + `shardId`를 포함하고 sequence number 값은 제외한 `IllegalStateException`
- [ ] `AtTimestamp` 변환: `Instant.fromEpochSeconds(javaInstant.epochSecond, javaInstant.nano)`
- [ ] `recordFlow()`에 영문 KDoc 작성(cold 계약, client lifetime, shard-end, resharding 참고)
- [ ] 구현 후 IDE diagnostic clean(error 0, unresolved deprecation 없음)

---

### T4 — `KinesisStartingPositionTest.kt`
**복잡도: 낮음**
**파일**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisStartingPositionTest.kt`

Assertion style: bluetape4k-assertions의 `shouldBeEqualTo`, `shouldBeTrue`, `shouldBeFalse`, `shouldBeNull`, `shouldNotBeNull`을 사용한다. exception 검사에는 bluetape4k의 `assertFailsWith<T> {}`를 사용한다.

Test:
- [ ] variant 5개 모두 정상 생성
- [ ] `AtSequenceNumber("")`와 `AfterSequenceNumber("")`가 `IllegalArgumentException` throw
- [ ] Java serialization round-trip: `TrimHorizon`, `Latest` → `readResolve()`가 singleton 반환(`===` 검사)
- [ ] Java serialization round-trip: `AtSequenceNumber("seq")` → 원본과 같음
- [ ] **변조된 deserialization**: `AtSequenceNumber("unique-seq-marker")`를 serialize하고 byte array에서 marker byte를 같은 길이의 blank space로 교체(Java serialization format length prefix 보존)한 뒤 deserialize → `IllegalArgumentException` 예상
  - Helper: `ObjectOutputStream`으로 `ByteArray`에 serialize. `"unique-seq-marker".toByteArray(Charsets.UTF_8)`를 `" ".repeat(marker.size).toByteArray(Charsets.UTF_8)`로 교체(같은 길이이며 `isBlank()` 검사 통과). `ObjectInputStream`으로 deserialize
- [ ] `Instant.now()`를 사용하는 `AtTimestamp`가 serialization round-trip 통과

---

### T5 — `KinesisRecordFlowOptionsTest.kt`
**복잡도: 낮음**
**파일**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlowOptionsTest.kt`

Assertion style: T4와 동일.

Test:
- [ ] 기본 instance가 error 없이 생성
- [ ] `batchLimit=0` → `IllegalArgumentException`
- [ ] `batchLimit=10_001` → `IllegalArgumentException`
- [ ] `pollInterval=100.ms` (< 200 ms floor) → `IllegalArgumentException`
- [ ] `emptyBackoff < pollInterval` → `IllegalArgumentException`
- [ ] `initialThrottleBackoff=0` → `IllegalArgumentException`
- [ ] `maxThrottleBackoff < initialThrottleBackoff` → `IllegalArgumentException`
- [ ] Serialization round-trip: 기본 option이 값 7개 모두 보존

---

### T6 — `KinesisRecordFlowUnitTest.kt`
**복잡도: 높음**
**파일**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlowUnitTest.kt`

`KinesisClient`에는 `kotlinx-coroutines-test`(`runTest`)와 MockK를 사용한다.

**Assertion style**: bluetape4k-assertions의 `shouldBeEqualTo`, `shouldBeTrue`, `shouldBeNull`, `shouldNotBeNull`, `shouldBeGreaterThan`, `assertFailsWith<T>{}`를 사용한다. JUnit `assertThrows`나 `kotlin.test.assertFailsWith`는 사용하지 않는다.

**Virtual-time pattern**(delay 검증용):
```kotlin
@Test
fun `empty-batch backoff delays by emptyBackoff`() = runTest {
    val options = KinesisRecordFlowOptions(emptyBackoff = 2.seconds, pollInterval = 200.milliseconds)
    // ... MockK setup: first call returns empty; second call returns 1 record + null nextShardIterator
    val startTime = currentTime   // TestCoroutineScheduler.currentTime (ms)
    mockClient.recordFlow(streamName, shardId, options = options).take(1).toList()
    val elapsed = currentTime - startTime
    elapsed shouldBeGreaterThan 2000L   // emptyBackoff was applied before record was received
}
```

`runTest`의 `currentTime`은 `TestScope.testScheduler.currentTime`을 가리킨다. test 대상 flow 내부에서 `delay()`를 호출하면 virtual time이 자동으로 진행된다. `runTest` 안에서 flow가 완료될 때까지 실행되면 수동 `advanceTimeBy()`는 필요하지 않다.

Test:
- [ ] **Cold**: 두 `collect {}` 호출이 각각 새 iterator를 조회(`getShardIterator` 두 번 호출, MockK `verify(exactly = 2)`로 검증)
- [ ] **Shard end**: `nextShardIterator == null` → exception 없이 Flow 정상 완료
- [ ] **Empty-batch backoff**: 빈 response → `emptyBackoff` delay 적용, `currentTime`이 최소 `emptyBackoff.inWholeMilliseconds`만큼 진행
- [ ] **pollInterval 강제**: non-empty batch → 0이 아닌 `pollInterval` delay 적용, `currentTime`이 최소 `pollInterval.inWholeMilliseconds`만큼 진행
- [ ] **emit 중 취소**: collector가 `collect` 내부에서 `CancellationException` throw → Flow 중단, 취소된 record에 대해 `lastSeenSequenceNumber`가 갱신되지 않음을 MockK interaction count로 검증
- [ ] **delay 중 취소**: `delay()` 중 coroutine 취소 → exception 유출 없이 정상 중단
- [ ] **Iterator expiry case A**: record 1개 emit 후 `ExpiredIteratorException` → `AfterSequenceNumber(lastSeen)`으로 다시 fetch하고 계속 진행, `getShardIterator` 두 번 호출 검증
- [ ] **Iterator expiry case B**: record emit 전 `ExpiredIteratorException` → 원래 position으로 다시 fetch하고 계속 진행, 원래 `KinesisStartingPosition` 타입을 사용하는지 검증
- [ ] **Latest + checkpoint 없음 + expiry**: `Latest`로 시작하고 아직 record를 emit하지 않은 상태(`lastSeenSequenceNumber == null`)에서 `ExpiredIteratorException` → retry나 재조회로 인한 data loss 없이 즉시 throw, `assertFailsWith<ExpiredIteratorException>` 검증
- [ ] **Iterator expiry 소진**: `maxIteratorRetries`회 연속 실패 → `ExpiredIteratorException` 전파, `assertFailsWith<ExpiredIteratorException>` 검증
- [ ] **Recovery fetch throttling 후 성공**: record 1개를 본 뒤 `ExpiredIteratorException`, 다음 iteration의 `fetchShardIterator`가 첫 시도에서 retry 가능한 `KinesisException`을 throw하고 두 번째 시도에서 성공 → recovery용 `getShardIterator`가 두 번 호출되고 `getRecords`가 두 번째 `getShardIterator` 호출의 iterator를 사용하며 recovery fetch의 `currentPosition`이 원래 position이 아닌 `AfterSequenceNumber(lastSeen)`인지 검증
- [ ] **Retry counter reset**: 일부 실패 후 `getRecords` 성공 → counter를 0으로 reset하고 다음 expiry가 새 budget으로 시작(실패 1회 → 성공 1회 → 실패 1회를 simulate하며 `maxIteratorRetries=1`이어도 전체 budget을 소진하지 않아야 함)
- [ ] **Throttle 복구**: retry 가능한 `ProvisionedThroughputExceededException` → jittered delay 후 계속 진행, virtual time 진행
- [ ] **Throttle 소진**: `maxThrottleRetries`회 연속 retry 가능 failure → exception 전파, `assertFailsWith<ProvisionedThroughputExceededException>` 검증
- [ ] **Retry 불가능 KinesisException**: `sdkErrorMetadata.isRetryable == false`인 `KinesisException` → delay나 throttle count 증가 없이 즉시 전파
- [ ] **`getShardIterator` null response**: `fetchShardIterator`가 SDK에서 null 반환 → `IllegalStateException`, message에 `streamName`과 `shardId` 포함 검증
- [ ] **Logging 정책**: 어떤 log output에도 `Record.data`가 없고 sequence number는 DEBUG에만 표시되며(`ListAppender` 또는 logger `MockK` 사용), error message가 raw sequence number 값을 interpolate하지 않음을 검증
- [ ] **SDK retry와 Flow retry**: SDK retry가 소진된 것처럼 SDK가 직접 throw하면 Flow-level retry 적용

---

### T7 — `KinesisRecordFlowTest.kt`
**복잡도: 중간**
**파일**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlowTest.kt`

LocalStack integration. **`AbstractKinesisKotlinTest`가 아니라 `AbstractKotlinKinesisTest`**를 상속한다.

LocalStack test에는 `runTest`가 아닌 `runSuspendIO { }`를 사용한다. `runTest`는 virtual time을 사용하므로 실제 I/O에 사용하면 안 된다. Kinesis stream이 채워지지 않을 때 무기한 멈추지 않도록 `take(N).toList()`를 `withTimeout(30.seconds)`로 감싼다.

Test(SAME_THREAD 순차 실행):
- [ ] **순서 1**: stream 생성, ACTIVE 대기(Awaitility ≤ 30초)
- [ ] **순서 2**: record 10개 저장(고유 partition key, `SdkBytes`로 된 알려진 data payload)
- [ ] **순서 3**: `withTimeout(30.seconds) { recordFlow(streamName, shardId, TrimHorizon).take(10).toList() }` → record 10개, `shouldBeEqualTo`로 count와 순서 검증
- [ ] **순서 4**: `withTimeout(30.seconds) { recordFlow(…, AfterSequenceNumber(seq0)).take(9).toList() }` → 첫 항목을 건너뛴 record 9개, `shouldBeEqualTo 9`로 count 검증
- [ ] **순서 5**: collection 중 취소: `withTimeoutOrNull(500.milliseconds) { recordFlow(…).collect { … } }` → exception 전파 없이 `null` 반환
- [ ] **순서 6**: stream 삭제

---

### T8a — `aws-kotlin/README.md`(영문)
**복잡도: 낮음**

기존 Kinesis section 뒤에 "Kinesis Flow" section을 추가한다.

Section 내용:
- 개요: `TrimHorizon` 시작과 `AfterSequenceNumber` 재개를 보여 주는 `recordFlow()` 사용 snippet
- Tuning reference table:

| Option | 기본값 | 안내 |
|---|---|---|
| `batchLimit` | 100 | 처리량을 높이려면 10,000까지 늘리고 memory를 줄이려면 낮춤 |
| `pollInterval` | 200 ms | AWS 하한. 조용한 shard의 비용을 줄이려면 늘림 |
| `emptyBackoff` | 1 s | 비용을 줄이려면 늘리고 latency를 줄이려면 낮춤(하한 = pollInterval) |
| `maxIteratorRetries` | 3 | 전파하기 전 연속 expiry failure 횟수 |
| `initialThrottleBackoff` | 500 ms | bursty producer에서는 100 ms로 낮춤 |
| `maxThrottleBackoff` | 30 s | steady-state 또는 비용 민감 consumer용 상한 |
| `maxThrottleRetries` | 5 | 전파하기 전 연속 Flow-level throttle failure 횟수 |

- Client lifetime 경고(`withKinesisClient` block이 끝나기 전에 collector가 완료되어야 함)
- Checkpoint 안내(`record.sequenceNumber()`를 외부에 저장하고 `AfterSequenceNumber`로 재개)
- SDK timeout 정책: `withKinesisClient`가 아닌 `kinesisClientOf(httpClient = …)`를 통해 구성, SDK retry 상호작용
- Resharding 참고: `nextShardIterator == null` = Flow 완료, child shard는 추적하지 않음(v1)

---

### T8b — `aws-kotlin/README.ko.md`(한글)
**복잡도: 낮음**

T8a의 Kinesis Flow section을 한글로 반영한다. 먼저 T8a 내용을 확인하고 section별로 번역한다. 다음을 보장한다.
- [ ] T8a와 같은 구조 위치(기존 Kinesis section 뒤)에 section 배치
- [ ] Tuning table column과 값은 동일하고 prose만 번역
- [ ] code를 번역하지 않고 T8a와 code snippet 일치
- [ ] 두 파일 모두에 모든 경고, 참고, 안내 포함

---

### T9 — Gradle 의존성 검증
**복잡도: 낮음**

- [ ] `aws-kotlin/build.gradle.kts`: `aws.sdk.kotlin:kinesis` 선언 확인(`compileOnly` 또는 `testImplementation`)
- [ ] 기존 `KinesisClient` + `kotlinx-coroutines-core`를 사용하므로 `recordFlow`에 새 dependency가 필요하지 않음
- [ ] virtual-time test용 `kotlinx-coroutines-test`가 `testImplementation`에 존재
- [ ] unit test용 `mockk`가 `testImplementation`에 존재

---

### T10 — KDoc 검증
**복잡도: 낮음**

T1–T8 완료 후 모든 public API에 완전한 영문 KDoc이 있는지 검증한다.
- [ ] `KinesisStartingPosition` sealed interface와 variant 5개 모두
- [ ] `KinesisRecordFlowOptions` data class와 parameter 7개 모두
- [ ] `KinesisClient.recordFlow()` extension function
- [ ] KDoc 범위: cold 계약, client lifetime, iterator-expiry case A+B, throttle 동작, shard-end, resharding v1 제한
- [ ] 새로 작성한 KDoc에 한글 text 없음

---

### T11 — lesson 문서
**복잡도: 낮음**
**파일**: `docs/lessons/2026-05-17-kinesis-flow.md`

PR 생성 전에 `bluetape4k-workflow`에서 요구한다. Step 7-P 전에 feature branch에 commit한다.

내용:
- 근본 원인: polling loop + iterator-expiry + throttle recovery pattern이 모든 consumer 위치에서 반복되는 boilerplate
- 주요 결정: cold `flow {}`와 `channelFlow` 비교, sealed position 타입, `sdkErrorMetadata.isRetryable` accessor, full-jitter backoff, 모든 호출 후 pollInterval 강제, emit 후 `lastSeenSequenceNumber` 갱신
- 분명하지 않은 함정: Kotlin에는 catch-guard clause가 없고 `e.isRetryable`도 없음. 최초 `fetchShardIterator`는 retry scope 안에 있어야 하며 LocalStack test에 `runTest`를 사용할 수 없음
- Step 3-R에서 발견한 review 누락: P0 catch 구문, `sdkErrorMetadata.isRetryable`, 최초 fetch retry scope, `AbstractKotlinKinesisTest` class 이름

---

## Build 순서

```
T9 (verify deps — before any code)
T1 (KinesisStartingPosition — needed by T3, T4)
T2 (KinesisRecordFlowOptions — needed by T3, T5)
T3 (KinesisRecordFlow — depends on T1, T2; includes IDE diagnostics gate)
T4 (KinesisStartingPositionTest — depends on T1)
T5 (KinesisRecordFlowOptionsTest — depends on T2)
T6 (KinesisRecordFlowUnitTest — depends on T3; virtual-time + MockK)
T7 (KinesisRecordFlowTest — depends on T3; LocalStack)
T8a (README.md English — independent, write after T3)
T8b (README.ko.md Korean — write after T8a, verify alignment)
T10 (KDoc verification — verify after T1–T3)
T11 (Lessons document — write after T6+T7 pass; commit before PR)
```

실행 순서: **T9 → T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8a → T8b → T10 → T11**

T3 이후 IDE diagnostic(`ide_diagnostics`)을 실행하고 모든 error를 수정한 뒤 T4로 진행한다.

---

## 검증 command

```bash
./gradlew :aws-kotlin:test --tests "*.kinesis.*" --info
./gradlew :aws-kotlin:test --tests "*.kinesis.KinesisRecordFlowUnitTest"
./gradlew :aws-kotlin:test --tests "*.kinesis.KinesisRecordFlowTest"
./gradlew :aws-kotlin:test
```

---

## Step 3-R review 이력

| Round | Reviewer | P0 | P1 | P2 | P3 | 조치 |
|---|---|---|---|---|---|---|
| Phase 1(Developer) | Developer 관점 | 1 | 4 | 2 | 0 | — |
| Phase 1(Security) | Security 관점 | 0 | 1 | 1 | 0 | — |
| Phase 1(Ops/SRE) | Ops/SRE 관점 | 1 | 5 | 1 | 0 | — |
| Phase 1(User/Caller) | Caller 관점 | 1 | 5 | 2 | 0 | — |
| Phase 1(6-tier Advisor) | Claude Code 자문 | 0 | 3 | 2 | 0 | — |
| Phase 2 Critic | Critic 통합 | 3 | 15 | — | — | plan v2 |
| Phase 2 → plan v2 | 모든 P0/P1 반영 | catch guard 구문, sdkErrorMetadata, 최초 fetch retry scope, 상수 9개, AbstractKotlinKinesisTest, runSuspendIO, withTimeout, byte-patch 변조 test, TestCoroutineScheduler pattern, retry 불가능 test, T8a/T8b 분리, T10/T11 추가 | 0 | 0 | — | — | plan v2 |
| Advisor 후속 | Claude Code 6-tier 자문 | 새 P0 1건: ExpiredIteratorException catch의 recovery `fetchShardIterator`가 retry scope 밖에 있음(최초 fetch P0와 대칭인 문제) | 1 | 0 | — | — | — |
| Advisor → plan v2.1 | Recovery fetch retry scope 수정 | `val currentPosition` → `var currentPosition`, catch block: `currentPosition = recoveryPosition; shardIterator = null`(다음 iteration의 try-block null-check로 fetch 위임) | 0 | 0 | — | — | plan v2.1 |
| Codex Phase 3 | 독립 reviewer(gpt-5.5) | P0: 0, P1: 1 — `Latest` + checkpoint 없음 + ExpiredIteratorException → 재조회로 조용한 data loss 발생(5분 TTL window) | 0 | 1 | — | — | — |
| Codex P1 → plan v2.2 | Latest+checkpoint 없음에 대한 fail-fast guard | catch block: `if (lastSeen == null && currentPosition is Latest) throw e`, 명세 §3.3 error matrix와 Latest KDoc, T3/T6 checklist 갱신 | 0 | 0 | — | — | plan v2.2 |
