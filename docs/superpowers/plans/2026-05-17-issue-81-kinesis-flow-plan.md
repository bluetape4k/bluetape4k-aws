# Implementation Plan — Kinesis Coroutine `Flow<Record>` (#81)

**Spec**: `docs/superpowers/specs/2026-05-17-issue-81-kinesis-flow-design.md` (v5)  
**Branch**: `feat/81-kinesis-dynamodb-streams-flow`  
**Date**: 2026-05-17  
**Module**: `aws-kotlin`  
**Plan version**: v2.1 (post Step 3-R advisor follow-up — ExpiredIteratorException recovery fetch retry scope fix)

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
- [ ] `AtSequenceNumber` + `AfterSequenceNumber`: `init { sequenceNumber.requireNotBlank("sequenceNumber") }` + `private fun readObject(stream: ObjectInputStream)` that calls `stream.defaultReadObject()` then re-runs `requireNotBlank`
- [ ] English KDoc on all variants (behavior, checkpoint guidance, deserialization safety)

---

### T2 — `KinesisRecordFlowOptions.kt`
**complexity: low**  
**file**: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlowOptions.kt`

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
- [ ] `init` validates all 7 invariants with descriptive messages (spec §3.2)
- [ ] `companion object` declares `serialVersionUID` plus all **9** named constants:
  - `MAX_KINESIS_BATCH_LIMIT = 10_000`
  - `MIN_POLL_INTERVAL = 200.milliseconds`
  - `DEFAULT_BATCH_LIMIT = 100`
  - `DEFAULT_POLL_INTERVAL = 200.milliseconds`
  - `DEFAULT_EMPTY_BACKOFF = 1.seconds`
  - `DEFAULT_MAX_ITERATOR_RETRIES = 3`
  - `DEFAULT_INITIAL_THROTTLE_BACKOFF = 500.milliseconds`
  - `DEFAULT_MAX_THROTTLE_BACKOFF = 30.seconds`
  - `DEFAULT_MAX_THROTTLE_RETRIES = 5`
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

#### Implementation structure (corrected — P0 fixes applied):

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
            log.warn {
                "Shard iterator expired (attempt $iteratorRetryCount/${options.maxIteratorRetries}): " +
                "stream=$streamName shard=$shardId"
            }
            // Update currentPosition and reset shardIterator to null.
            // Re-fetch happens in the NEXT iteration's null-check INSIDE the try block (retry scope),
            // so retryable KinesisException from fetchShardIterator is handled by the KinesisException catch.
            currentPosition = lastSeenSequenceNumber
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

#### Private helpers:

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

#### Checklist (T3):
- [ ] `currentPosition` is `var` (not `val`) — updated in `ExpiredIteratorException` recovery without direct fetch
- [ ] `shardIterator` initialized as `null`; initial `fetchShardIterator` inside `try {}` block (retry scope)
- [ ] `ExpiredIteratorException` catch: sets `currentPosition = recoveryPosition; shardIterator = null` — no direct fetch call in catch block (fetch delegated to next iteration's null-check inside try, so KinesisException retry scope applies)
- [ ] `getShardIterator` (via `fetchShardIterator`) called INSIDE `flow {}` lambda, not in `recordFlow` body
- [ ] `lastSeenSequenceNumber` updated AFTER `emit(record)` returns
- [ ] `CancellationException` rethrown as FIRST catch (before all retry logic)
- [ ] `catch (e: KinesisException)` uses inner guard: `if (!e.sdkErrorMetadata.isRetryable) throw e`
- [ ] NO Kotlin catch-guard syntax (`if (condition)` after catch type) — not supported in Kotlin
- [ ] `e.sdkErrorMetadata.isRetryable` (not `e.isRetryable` — property doesn't exist on `KinesisException`)
- [ ] Retry counters reset to zero on every successful `getRecords` call
- [ ] `pollInterval` delay after non-empty batch; `emptyBackoff` after empty batch
- [ ] WARN log per iterator-expiry attempt; ERROR log on exhaustion (streamName, shardId, count)
- [ ] WARN log per throttle attempt; ERROR log on exhaustion (streamName, shardId, count, last error msg)
- [ ] Logging policy: seq numbers DEBUG only; `Record.data` never logged; error messages include type name only (not seq number value)
- [ ] `fetchShardIterator` is `private suspend fun KinesisClient.fetchShardIterator(...)`
- [ ] `jitteredBackoff` is `internal fun jitteredBackoff(...)` (testable, not public API)
- [ ] `jitteredBackoff` uses `kotlin.random.Random.Default.nextLong()` (NOT `ThreadLocalRandom`)
- [ ] Jitter formula: `coerceAtMost(maxThrottleBackoff)` BEFORE `nextLong` to prevent overflow
- [ ] `fetchShardIterator` — exhaustive `when` covers all 5 variants; null response → `IllegalStateException` with `streamName` + `shardId` (no seq number value)
- [ ] `AtTimestamp` conversion: `Instant.fromEpochSeconds(javaInstant.epochSecond, javaInstant.nano)`
- [ ] English KDoc on `recordFlow()` (cold contract, client lifetime, shard-end, resharding note)
- [ ] IDE diagnostics clean after implementation (zero errors, no unresolved deprecations)

---

### T4 — `KinesisStartingPositionTest.kt`
**complexity: low**  
**file**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisStartingPositionTest.kt`

Assertion style: `shouldBeEqualTo`, `shouldBeTrue`, `shouldBeFalse`, `shouldBeNull`, `shouldNotBeNull` from bluetape4k-assertions. Use `assertFailsWith<T> {}` from bluetape4k for exception checks.

Tests:
- [ ] All 5 variants instantiate successfully
- [ ] `AtSequenceNumber("")` and `AfterSequenceNumber("")` throw `IllegalArgumentException`
- [ ] Java serialization round-trip: `TrimHorizon`, `Latest` → `readResolve()` returns singleton (use `===` check)
- [ ] Java serialization round-trip: `AtSequenceNumber("seq")` → equals original
- [ ] **Tampered deserialization**: serialize `AtSequenceNumber("unique-seq-marker")`, find and replace the marker bytes with same-length blank spaces in the byte array (preserves Java serialization format length prefix), deserialize → expect `IllegalArgumentException`
  - Helper: serialize to `ByteArray` via `ObjectOutputStream`; replace `"unique-seq-marker".toByteArray(Charsets.UTF_8)` with `" ".repeat(marker.size).toByteArray(Charsets.UTF_8)` (same length, passes `isBlank()` check); deserialize via `ObjectInputStream`
- [ ] `AtTimestamp` with `Instant.now()` round-trips via serialization

---

### T5 — `KinesisRecordFlowOptionsTest.kt`
**complexity: low**  
**file**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlowOptionsTest.kt`

Assertion style: same as T4.

Tests:
- [ ] Default instance creates with no errors
- [ ] `batchLimit=0` → `IllegalArgumentException`
- [ ] `batchLimit=10_001` → `IllegalArgumentException`
- [ ] `pollInterval=100.ms` (< 200 ms floor) → `IllegalArgumentException`
- [ ] `emptyBackoff < pollInterval` → `IllegalArgumentException`
- [ ] `initialThrottleBackoff=0` → `IllegalArgumentException`
- [ ] `maxThrottleBackoff < initialThrottleBackoff` → `IllegalArgumentException`
- [ ] Serialization round-trip: default options preserve all 7 values

---

### T6 — `KinesisRecordFlowUnitTest.kt`
**complexity: high**  
**file**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlowUnitTest.kt`

Use `kotlinx-coroutines-test` (`runTest`) + MockK for `KinesisClient`.

**Assertion style**: `shouldBeEqualTo`, `shouldBeTrue`, `shouldBeNull`, `shouldNotBeNull`, `shouldBeGreaterThan`, `assertFailsWith<T>{}` from bluetape4k-assertions. Do NOT use JUnit `assertThrows` or `kotlin.test.assertFailsWith`.

**Virtual-time pattern** (for delay verification):
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

`currentTime` in `runTest` refers to `TestScope.testScheduler.currentTime`. Virtual time advances automatically when `delay()` is called inside the flow under test. No manual `advanceTimeBy()` is needed when the flow runs to completion inside `runTest`.

Tests:
- [ ] **Cold**: two `collect {}` calls each get a fresh iterator (`getShardIterator` called twice; verify with MockK `verify(exactly = 2)`)
- [ ] **Shard end**: `nextShardIterator == null` → Flow completes normally, no exception
- [ ] **Empty-batch backoff**: empty response → `emptyBackoff` delay applied; `currentTime` advances by at least `emptyBackoff.inWholeMilliseconds`
- [ ] **pollInterval enforcement**: non-empty batch → `pollInterval` delay applied (not 0); `currentTime` advances by at least `pollInterval.inWholeMilliseconds`
- [ ] **Cancellation during emit**: collector throws `CancellationException` inside `collect` → Flow stops; verify `lastSeenSequenceNumber` NOT updated for cancelled record (via MockK interaction count)
- [ ] **Cancellation during delay**: coroutine cancelled while in `delay()` → stops cleanly, no exception leaks
- [ ] **Iterator expiry Case A**: 1 record emitted, then `ExpiredIteratorException` → re-fetches with `AfterSequenceNumber(lastSeen)` → continues; verify `getShardIterator` called twice
- [ ] **Iterator expiry Case B**: no record emitted, then `ExpiredIteratorException` → re-fetches with original position → continues; verify re-fetch uses original `KinesisStartingPosition` type
- [ ] **Iterator expiry exhaustion**: `maxIteratorRetries` consecutive failures → `ExpiredIteratorException` propagates; verify `assertFailsWith<ExpiredIteratorException>`
- [ ] **Retry counter reset**: successful `getRecords` after partial failures → counter resets to 0; next expiry starts fresh budget (simulate: 1 failure → 1 success → 1 failure; total should NOT exhaust budget if `maxIteratorRetries=1`)
- [ ] **Throttle recovery**: `ProvisionedThroughputExceededException` (retryable) → jittered delay → continues; virtual time advances
- [ ] **Throttle exhaustion**: `maxThrottleRetries` consecutive retryable failures → exception propagates; verify `assertFailsWith<ProvisionedThroughputExceededException>`
- [ ] **Non-retryable KinesisException**: `KinesisException` with `sdkErrorMetadata.isRetryable == false` → propagates immediately (no delay, no throttle count increment)
- [ ] **`getShardIterator` null response**: `fetchShardIterator` returns null from SDK → `IllegalStateException`; verify message contains `streamName` and `shardId`
- [ ] **Logging policy**: verify `Record.data` not in any log output; seq numbers appear only at DEBUG (use `ListAppender` or `MockK` on logger); error messages do not interpolate raw seq number values
- [ ] **SDK retry vs Flow retry**: when SDK throws directly (as if SDK retry exhausted), Flow-level retry applies

---

### T7 — `KinesisRecordFlowTest.kt`
**complexity: medium**  
**file**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlowTest.kt`

LocalStack integration. Extends **`AbstractKotlinKinesisTest`** (NOT `AbstractKinesisKotlinTest`).

Use `runSuspendIO { }` (not `runTest`) for LocalStack tests — `runTest` uses virtual time and must not be used for real I/O. Wrap `take(N).toList()` in `withTimeout(30.seconds)` to prevent indefinite hang if Kinesis stream doesn't fill.

Tests (ordered — SAME_THREAD execution):
- [ ] **Order 1**: Create stream; wait ACTIVE (Awaitility ≤ 30 s)
- [ ] **Order 2**: Put 10 records (unique partition keys, known data payloads as `SdkBytes`)
- [ ] **Order 3**: `withTimeout(30.seconds) { recordFlow(streamName, shardId, TrimHorizon).take(10).toList() }` → 10 records; verify count and order via `shouldBeEqualTo`
- [ ] **Order 4**: `withTimeout(30.seconds) { recordFlow(…, AfterSequenceNumber(seq0)).take(9).toList() }` → 9 records (first skipped); verify count via `shouldBeEqualTo 9`
- [ ] **Order 5**: Cancellation mid-collection: `withTimeoutOrNull(500.milliseconds) { recordFlow(…).collect { … } }` → returns `null`, no exception propagated
- [ ] **Order 6**: Delete stream

---

### T8a — `aws-kotlin/README.md` (English)
**complexity: low**

Add "Kinesis Flow" section after the existing Kinesis section.

Section content:
- Overview: `recordFlow()` usage snippet showing `TrimHorizon` start and `AfterSequenceNumber` resume
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

### T8b — `aws-kotlin/README.ko.md` (Korean)
**complexity: low**

Mirror the Kinesis Flow section from T8a in Korean. Check T8a content first and translate section-by-section. Ensure:
- [ ] Section appears in the same structural position as T8a (after existing Kinesis section)
- [ ] Tuning table columns and values identical; only prose translated
- [ ] Code snippets identical to T8a (code is not translated)
- [ ] All warnings, notes, and guidance present in both files

---

### T9 — Gradle dependency verification
**complexity: low**

- [ ] `aws-kotlin/build.gradle.kts`: confirm `aws.sdk.kotlin:kinesis` is declared (`compileOnly` or `testImplementation`)
- [ ] No new dependencies needed for `recordFlow` (uses existing `KinesisClient` + `kotlinx-coroutines-core`)
- [ ] `kotlinx-coroutines-test` present in `testImplementation` (for virtual-time tests)
- [ ] `mockk` present in `testImplementation` (for unit tests)

---

### T10 — KDoc verification
**complexity: low**

After completing T1–T8, verify English KDoc is present and complete on all public API:
- [ ] `KinesisStartingPosition` sealed interface and all 5 variants
- [ ] `KinesisRecordFlowOptions` data class and all 7 parameters
- [ ] `KinesisClient.recordFlow()` extension function
- [ ] KDoc covers: cold contract, client lifetime, iterator-expiry Cases A+B, throttle behavior, shard-end, resharding v1 limitation
- [ ] No Korean text in newly written KDoc

---

### T11 — Lessons document
**complexity: low**  
**file**: `docs/lessons/2026-05-17-kinesis-flow.md`

Required by `bluetape4k-workflow` before PR creation. Commit to feature branch before Step 7-P.

Content:
- Root cause: polling loop + iterator-expiry + throttle recovery patterns are boilerplate repeated at every consumer site
- Key decisions: cold `flow {}` vs `channelFlow`; sealed position type; `sdkErrorMetadata.isRetryable` accessor; full-jitter backoff; pollInterval enforcement after every call; `lastSeenSequenceNumber` updated after emit
- Non-obvious pitfalls: Kotlin has no catch-guard clauses; `e.isRetryable` doesn't exist; initial `fetchShardIterator` must be inside retry scope; `runTest` cannot be used for LocalStack tests
- Review misses caught in Step 3-R: P0 catch syntax; `sdkErrorMetadata.isRetryable`; initial fetch retry scope; `AbstractKotlinKinesisTest` class name

---

## Build Sequence

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

Practical order: **T9 → T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8a → T8b → T10 → T11**

After T3: run IDE diagnostics (`ide_diagnostics`) and fix all errors before proceeding to T4.

---

## Verification Commands

```bash
./gradlew :aws-kotlin:test --tests "*.kinesis.*" --info
./gradlew :aws-kotlin:test --tests "*.kinesis.KinesisRecordFlowUnitTest"
./gradlew :aws-kotlin:test --tests "*.kinesis.KinesisRecordFlowTest"
./gradlew :aws-kotlin:test
```

---

## Step 3-R Review History

| Round | Reviewer | P0 | P1 | P2 | P3 | Action |
|---|---|---|---|---|---|---|
| Phase 1 (Developer) | Developer perspective | 1 | 4 | 2 | 0 | — |
| Phase 1 (Security) | Security perspective | 0 | 1 | 1 | 0 | — |
| Phase 1 (Ops/SRE) | Ops/SRE perspective | 1 | 5 | 1 | 0 | — |
| Phase 1 (User/Caller) | Caller perspective | 1 | 5 | 2 | 0 | — |
| Phase 1 (6-tier Advisor) | Claude Code advisor | 0 | 3 | 2 | 0 | — |
| Phase 2 Critic | Critic consolidation | 3 | 15 | — | — | plan v2 |
| Phase 2 → plan v2 | All P0/P1 applied | catch guard syntax; sdkErrorMetadata; initial fetch retry scope; 9 constants; AbstractKotlinKinesisTest; runSuspendIO; withTimeout; byte-patch tamper test; TestCoroutineScheduler pattern; Non-retryable test; T8a/T8b split; T10/T11 added | 0 | 0 | — | — | plan v2 |
| Advisor follow-up | Claude Code 6-tier advisor | 1 new P0: recovery `fetchShardIterator` in ExpiredIteratorException catch is outside retry scope (symmetric issue to initial fetch P0) | 1 | 0 | — | — | — |
| Advisor → plan v2.1 | Recovery fetch retry scope fix | `val currentPosition` → `var currentPosition`; catch block: `currentPosition = recoveryPosition; shardIterator = null` (fetch delegated to next iteration's try-block null-check) | 0 | 0 | — | — | plan v2.1 |
