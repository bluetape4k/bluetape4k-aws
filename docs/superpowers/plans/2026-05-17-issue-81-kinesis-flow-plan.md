# Implementation Plan — Kinesis Coroutine `Flow<Record>` (#81)

**Spec**: `docs/superpowers/specs/2026-05-17-issue-81-kinesis-flow-design.md` (v4)  
**Branch**: `feat/81-kinesis-dynamodb-streams-flow`  
**Date**: 2026-05-17  
**Module**: `aws-kotlin`

DynamoDB Streams: intentionally deferred to follow-up issue. This PR: Kinesis only.

---

## Task List

### T1 — `KinesisStartingPosition.kt`
**complexity: low**  
**file**: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisStartingPosition.kt`

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
- [ ] All 5 variants implement `KinesisStartingPosition : java.io.Serializable`
- [ ] `data object` variants: `private const val serialVersionUID: Long = 1L` + `private fun readResolve(): Any = <This>`
- [ ] `data class` variants: `companion object { private const val serialVersionUID: Long = 1L }`
- [ ] `AtSequenceNumber` + `AfterSequenceNumber`: `init { sequenceNumber.requireNotBlank("sequenceNumber") }` + `private readObject` that calls `defaultReadObject()` then `requireNotBlank`
- [ ] English KDoc on all variants (behavior, checkpoint guidance, deserialization safety)

---

### T2 — `KinesisRecordFlowOptions.kt`
**complexity: low**  
**file**: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlowOptions.kt`

```
data class KinesisRecordFlowOptions(
    batchLimit: Int = 100,
    pollInterval: Duration = 200.ms,
    emptyBackoff: Duration = 1.s,
    maxIteratorRetries: Int = 3,
    initialThrottleBackoff: Duration = 500.ms,
    maxThrottleBackoff: Duration = 30.s,
    maxThrottleRetries: Int = 5,
) : Serializable
```

Checklist:
- [ ] `init` validates all 7 invariants with descriptive messages (spec §3.2)
- [ ] `companion object`: `serialVersionUID`, all 5 constants (`MAX_KINESIS_BATCH_LIMIT`, `MIN_POLL_INTERVAL`, etc.)
- [ ] English KDoc on class + each parameter (especially throttle backoff fields explaining jitter in Flow)

---

### T3 — `KinesisRecordFlow.kt`  ← core logic
**complexity: high**  
**file**: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlow.kt`

Public function:
```kotlin
fun KinesisClient.recordFlow(
    streamName: String,
    shardId: String,
    position: KinesisStartingPosition = TrimHorizon,
    options: KinesisRecordFlowOptions = KinesisRecordFlowOptions(),
): Flow<Record>
```

Internal implementation structure:
```
flow {
    var currentPosition = position
    var lastSeenSequenceNumber: String? = null
    var iteratorRetryCount = 0
    var throttleRetryCount = 0

    // getShardIterator called INSIDE flow {} — cold contract
    var shardIterator = fetchShardIterator(streamName, shardId, currentPosition)

    while (true) {
        currentCoroutineContext().ensureActive()   // cancellation checkpoint

        try {
            val response = kinesisClient.getRecords { ... limit = options.batchLimit; shardIterator = shardIterator }
            
            // reset retry counters on success
            iteratorRetryCount = 0
            throttleRetryCount = 0
            
            // emit records — update lastSeenSequenceNumber AFTER emit returns
            for (record in response.records()) {
                emit(record)
                lastSeenSequenceNumber = record.sequenceNumber()
            }
            
            // shard end
            if (response.nextShardIterator() == null) return@flow
            shardIterator = response.nextShardIterator()!!
            
            // poll interval enforcement (always, even on non-empty batches)
            val delay = if (response.records().isEmpty()) options.emptyBackoff else options.pollInterval
            delay(delay)
            
        } catch (e: CancellationException) {
            throw e  // always first
        } catch (e: ExpiredIteratorException) {
            iteratorRetryCount++
            if (iteratorRetryCount > options.maxIteratorRetries) {
                log.error { "..." }
                throw e
            }
            log.warn { "..." }
            val recoveryPosition = lastSeenSequenceNumber
                ?.let { AfterSequenceNumber(it) }
                ?: currentPosition
            shardIterator = fetchShardIterator(streamName, shardId, recoveryPosition)
        } catch (e: KinesisException) if (e.isRetryable) {
            throttleRetryCount++
            if (throttleRetryCount > options.maxThrottleRetries) {
                log.error { "..." }
                throw e
            }
            log.warn { "..." }
            val backoff = jitteredBackoff(throttleRetryCount, options)
            delay(backoff)
        }
    }
}
```

Private helpers:
- `fetchShardIterator(client, streamName, shardId, position): String` — maps `KinesisStartingPosition` → `ShardIteratorType` + converts `AtTimestamp.timestamp` via `Instant.fromEpochSeconds(epochSecond, nano)`
- `jitteredBackoff(attempt, options): Duration` — `ThreadLocalRandom.current().nextLong(0, min(initial × 2L.shl(attempt-1), max).inWholeMilliseconds + 1).milliseconds` with `coerceAtMost(max)` to prevent overflow

Checklist:
- [ ] `getShardIterator` inside `flow {}` lambda, not in `recordFlow` body
- [ ] `lastSeenSequenceNumber` updated AFTER `emit(record)` returns
- [ ] `CancellationException` rethrown before retry logic in every catch block
- [ ] Retry counters reset to zero on every successful `getRecords`
- [ ] `pollInterval` delay applied after non-empty batch; `emptyBackoff` after empty batch
- [ ] WARN log per iterator-expiry attempt; ERROR log on exhaustion (streamName, shardId, count)
- [ ] WARN log per throttle attempt; ERROR log on exhaustion (streamName, shardId, count, last error msg)
- [ ] Logging policy: seq numbers DEBUG only; `Record.data` never logged; error messages redact seq number value
- [ ] Sequence number interpolation in logs uses `KinesisStartingPosition::class.simpleName` only
- [ ] Jitter formula uses `coerceAtMost(maxThrottleBackoff)` before `nextLong` to prevent overflow
- [ ] `fetchShardIterator` null response → `IllegalStateException` with `streamName` + `shardId` (no seq number)
- [ ] `AtTimestamp` conversion: `Instant.fromEpochSeconds(javaInstant.epochSecond, javaInstant.nano)`
- [ ] English KDoc on `recordFlow()` (cold contract, client lifetime, shard-end, resharding note)

---

### T4 — `KinesisStartingPositionTest.kt`
**complexity: low**  
**file**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisStartingPositionTest.kt`

Tests:
- [ ] All 5 variants instantiate successfully
- [ ] `AtSequenceNumber("")` and `AfterSequenceNumber("")` throw `IllegalArgumentException`
- [ ] Java serialization round-trip: `TrimHorizon`, `Latest` → `readResolve()` returns singleton
- [ ] Java serialization round-trip: `AtSequenceNumber("seq")` → equals original
- [ ] Tampered deserialization: `AtSequenceNumber` with blank seq → `readObject` throws `IllegalArgumentException`
- [ ] `AtTimestamp` with `Instant.now()` round-trips via serialization

---

### T5 — `KinesisRecordFlowOptionsTest.kt`
**complexity: low**  
**file**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlowOptionsTest.kt`

Tests:
- [ ] Default instance creates with no errors
- [ ] `batchLimit=0` → `IllegalArgumentException`
- [ ] `batchLimit=10_001` → `IllegalArgumentException`
- [ ] `pollInterval=100.ms` (< 200 ms floor) → `IllegalArgumentException`
- [ ] `emptyBackoff < pollInterval` → `IllegalArgumentException`
- [ ] `initialThrottleBackoff=0` → `IllegalArgumentException`
- [ ] `maxThrottleBackoff < initialThrottleBackoff` → `IllegalArgumentException`
- [ ] Serialization round-trip: default options preserve all values

---

### T6 — `KinesisRecordFlowUnitTest.kt`
**complexity: high**  
**file**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlowUnitTest.kt`

Use `kotlinx-coroutines-test` (`runTest`, `TestCoroutineScheduler`) + MockK for `KinesisClient`.

Tests:
- [ ] **Cold**: two `collect {}` calls each get a fresh iterator (getShardIterator called twice)
- [ ] **Shard end**: `nextShardIterator == null` → Flow completes normally, no exception
- [ ] **Empty-batch backoff**: empty response → `emptyBackoff` delay applied; virtual time advances
- [ ] **pollInterval enforcement**: non-empty batch → `pollInterval` delay applied (not 0); virtual time check
- [ ] **Cancellation during emit**: collector throws `CancellationException` → Flow stops; `lastSeenSequenceNumber` not updated for cancelled record
- [ ] **Cancellation during delay**: coroutine cancelled while in `delay()` → stops cleanly
- [ ] **Iterator expiry Case A**: emitted 1 record, then `ExpiredIteratorException` → re-fetches with `AfterSequenceNumber(lastSeen)` → continues
- [ ] **Iterator expiry Case B**: no record emitted, then `ExpiredIteratorException` → re-fetches with original position → continues
- [ ] **Iterator expiry exhaustion**: `maxIteratorRetries` consecutive failures → ERROR log + `ExpiredIteratorException` propagates
- [ ] **Retry counter reset**: successful getRecords after partial failures → counter resets to 0; next expiry starts fresh budget
- [ ] **Throttle recovery**: `ProvisionedThroughputExceededException` → jittered delay → continues
- [ ] **Throttle exhaustion**: `maxThrottleRetries` consecutive → ERROR log + exception propagates
- [ ] **`getShardIterator` null response**: `IllegalStateException` with streamName + shardId
- [ ] **Logging policy**: verify no `Record.data` logged; verify seq numbers only at DEBUG; verify error messages do not contain seq number values (check captured log output)
- [ ] **SDK retry vs Flow retry**: when SDK throws directly (as if SDK retry exhausted), Flow-level retry applies

---

### T7 — `KinesisRecordFlowTest.kt`
**complexity: medium**  
**file**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlowTest.kt`

LocalStack integration. Extends `AbstractKinesisKotlinTest`.

Tests (ordered — SAME_THREAD execution):
- [ ] **Order 1**: Create stream; wait ACTIVE (Awaitility ≤ 30 s)
- [ ] **Order 2**: Put 10 records (unique partition keys, known data payloads)
- [ ] **Order 3**: `recordFlow(streamName, shardId, TrimHorizon).take(10).toList()` → 10 records, correct order
- [ ] **Order 4**: `recordFlow(…, AfterSequenceNumber(seq0)).take(9).toList()` → 9 records (first skipped)
- [ ] **Order 5**: Cancellation mid-collection: `withTimeoutOrNull(500.ms) { recordFlow(…).collect { … } }` → no exception
- [ ] **Order 6**: Delete stream

---

### T8 — README updates
**complexity: low**  
**files**: `aws-kotlin/README.md`, `aws-kotlin/README.ko.md`

Both files: add "Kinesis Flow" section after existing Kinesis section.

Section content:
- Overview: `recordFlow()` usage snippet (TrimHorizon, AfterSequenceNumber resume)
- Tuning reference table:

| Option | Default | Guidance |
|---|---|---|
| `batchLimit` | 100 | Increase to 10,000 for throughput; decrease for memory |
| `pollInterval` | 200 ms | AWS floor; increase to save cost on quiet shards |
| `emptyBackoff` | 1 s | Tune up for price, down for latency (floor = pollInterval) |
| `maxIteratorRetries` | 3 | Consecutive expiry failures before propagation |
| `initialThrottleBackoff` | 500 ms | Reduce to 100 ms for bursty producers |
| `maxThrottleBackoff` | 30 s | Cap for steady-state or price-sensitive consumers |
| `maxThrottleRetries` | 5 | Consecutive Flow-level throttle failures before propagation |

- Client lifetime warning (collector must complete before `withKinesisClient` block exits)
- Checkpoint guidance (persist `record.sequenceNumber()` externally; resume with `AfterSequenceNumber`)
- SDK timeout policy: configure via `kinesisClientOf(httpClient = …)` (not `withKinesisClient`); SDK retry interaction
- Resharding note: `nextShardIterator == null` = Flow complete; child shards not followed (v1)

---

### T9 — Gradle dependency verification
**complexity: low**

- [ ] `aws-kotlin/build.gradle.kts`: confirm `aws.sdk.kotlin:kinesis` is declared (compileOnly or testImplementation)
- [ ] No new dependencies needed for `recordFlow` (uses existing `KinesisClient` + `kotlinx-coroutines-core`)
- [ ] `kotlinx-coroutines-test` present in `testImplementation` (for virtual-time tests)
- [ ] `mockk` present in `testImplementation` (for unit tests)

---

## Build Sequence

```
T1 → T2 → T3 (depends on T1, T2)
T4 (depends on T1)
T5 (depends on T2)
T6 (depends on T3 — mock-based, no LocalStack)
T7 (depends on T3 — LocalStack)
T8 (independent, write last)
T9 (verify before T3)
```

Practical order: T9 → T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8

---

## Verification Commands

```bash
./gradlew :aws-kotlin:test --tests "*.kinesis.*" --info
./gradlew :aws-kotlin:test --tests "*.kinesis.KinesisRecordFlowUnitTest"
./gradlew :aws-kotlin:test --tests "*.kinesis.KinesisRecordFlowTest"
./gradlew :aws-kotlin:test
```
