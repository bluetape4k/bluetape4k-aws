# Design Spec — Kinesis Coroutine `Flow<Record>` Support
<!-- Issue #81 | bluetape4k-aws aws-kotlin module -->

**Status**: Draft v4 (post Phase 3 Codex + 6-tier advisor review)
**Author**: debop  
**Date**: 2026-05-17  
**Module**: `aws-kotlin`  
**Branch**: `feat/81-kinesis-dynamodb-streams-flow`

---

## 0. Context

Issue #81 requests coroutine-native Kinesis and DynamoDB Streams consumption as a bluetape4k
alternative to Spring Cloud AWS 4.0 Kinesis binder features.

**Scope narrowed by deliberate v1 decision:**
- `aws.sdk.kotlin:dynamodbstreams` **does exist** on Maven Central (confirmed 2026-05-17).
  However, DynamoDB Streams requires a separate design: different shard-list API, distinct
  iterator lifecycle semantics, and a different record type. Including it in the same PR would
  create an oversized change. DynamoDB Streams is tracked in a separate follow-up issue.
- This PR delivers **single-shard `Flow<Record>` consumption over AWS Kotlin SDK Kinesis** only.

---

## 1. Problem Statement

The existing `aws-kotlin` Kinesis API (`KinesisClientExtensions.kt`) exposes individual `suspend`
calls (`getShardIterator`, `getRecords`) but requires callers to hand-code the polling loop,
iterator-expiry recovery, backoff, and cooperative cancellation. This is error-prone and violates
the DRY principle across consumer sites.

**Goal**: Expose a cold `Flow<Record>` primitive that handles:
- Iterator lifecycle (initial fetch + expiry recovery)
- Empty-batch backoff
- Throttle-exception backoff with bounded retries
- Cooperative cancellation via standard coroutine mechanics
- Shard-end detection (`nextShardIterator == null`) → natural Flow completion

---

## 2. Design Decisions

### 2.1 Flow model: cold `flow {}` over `channelFlow {}`

**Decision**: Use plain `flow {}` builder.

**Rationale**:
- Single-shard consumption is strictly sequential: one in-flight `getRecords` at a time.
- `emit(record)` in `flow {}` suspends when the collector is slow, providing natural backpressure
  that throttles GetRecords calls without any additional rate-limiting code.
- AWS Kinesis limit: 5 `GetRecords` calls/shard/second → minimum 200 ms poll interval. Natural
  backpressure from `flow {}` helps stay within this limit when the consumer is slower than 200ms.
- `channelFlow {}` would buffer records and decouple producer rate from collector rate, which could
  cause busy-polling against the 5-calls/sec limit when the collector is idle.

**Alternatives considered**:
- `channelFlow {}` — rejected: buffering decouples producer/collector rates, risks exceeding the
  5-calls/sec limit when the collector is idle, and adds internal channel lifecycle complexity with
  no benefit for the single-shard sequential use case.
- `callbackFlow {}` — rejected: Kinesis SDK provides polling-based `getRecords`, not push callbacks.
  `callbackFlow` is suited to listener-based (push) sources; using it here would require introducing
  an artificial callback wrapper with no benefit.

### 2.2 Starting position: sealed interface `KinesisStartingPosition`

**Decision**: Introduce `sealed interface KinesisStartingPosition` with five variants instead of
individual nullable parameters (`type: ShardIteratorType, startingSequenceNumber: String? = null`).

**Rationale**:
- Eliminates invalid combinations (e.g. `TrimHorizon` + `startingSequenceNumber`).
- Makes iterator-expiry recovery a single `when` branch (clear, testable).
- CLAUDE.md "Same-type parameters" rule: wrapping multi-param combos in a named type.

**Alternatives considered**:
- Flat nullable parameters `(type: ShardIteratorType, sequenceNumber: String? = null,
  timestamp: java.time.Instant? = null)` — rejected: invalid combinations are allowed at compile
  time and must be caught at runtime with error-prone validation. Sealed types make illegal states
  unrepresentable.
- `enum class` with a pair `(ShardIteratorType, String?)` — rejected: same invalidity problem as
  flat parameters, and adds awkward null-carrying enum values.

### 2.3 Tuning options: `KinesisRecordFlowOptions` data class

**Decision**: All polling tunables in a single `data class` with constructor-time validation.

**Rationale**: Avoids a 7-argument extension function signature. Centralises validation.

### 2.4 AtTimestamp uses `java.time.Instant`

**Decision**: `AtTimestamp.timestamp` is `java.time.Instant` (JVM standard), converted internally
to `aws.smithy.kotlin.runtime.time.Instant` via
`aws.smithy.kotlin.runtime.time.Instant.fromEpochSeconds(javaInstant.epochSecond, javaInstant.nano)`.

**Rationale**: Exposing Smithy's `Instant` in a sealed public type forces callers to declare a
dependency on the Smithy runtime, which is an implementation detail. `java.time.Instant` is the
standard on JVM and present in every caller's classpath.

**Precision note**: The conversion uses `fromEpochSeconds(seconds, ns: Int)` (verified from
Smithy runtime-core 1.6.14 source), preserving nanosecond precision. Do **not** use
`fromEpochMilliseconds(toEpochMilli())` — that silently truncates sub-millisecond precision.

### 2.5 DynamoDB Streams: intentionally deferred

`aws.sdk.kotlin:dynamodbstreams` is a distinct Maven artifact with a different polling model
(DynamoDB Streams uses `getShardIterator` + `getRecords` via the DynamoDB Streams client, not
the Kinesis client), different record type (`aws.sdk.kotlin.services.dynamodbstreams.model.Record`),
and different iterator expiry semantics (24-hour shard window vs Kinesis 7-day).

Combining both in a single PR creates an oversized change with two independent polling loops,
two sealed-type hierarchies, and two test matrix expansions. DynamoDB Streams will use the
same design pattern established here and is tracked in a dedicated follow-up issue.

**Note**: An earlier research pass incorrectly noted this artifact "does not exist". Confirmed on
2026-05-17 via Maven Central search: `aws.sdk.kotlin:dynamodbstreams-jvm` is published.

---

## 3. Public API

### 3.1 `KinesisStartingPosition` (new file)

This sealed interface is **intentionally exhaustive**: it maps 1:1 to the five `ShardIteratorType`
enum values in the AWS SDK. If AWS adds a new iterator type, a new variant must be added here
and a minor version bump is required — a `when` without `else` will correctly fail to compile.

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
     * On iterator-expiry recovery with no prior emitted record (Case B), recovery re-fetches
     * using `Latest` at recovery time. Records produced between the original fetch and recovery
     * are permanently skipped — this is the documented AWS `LATEST` semantics.
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

### 3.2 `KinesisRecordFlowOptions` (new file)

All `require(...)` calls in `init` must include descriptive messages, e.g.:
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

### 3.3 `KinesisClient.recordFlow()` (new file)

```kotlin
fun KinesisClient.recordFlow(
    streamName: String,
    shardId: String,
    position: KinesisStartingPosition = KinesisStartingPosition.TrimHorizon,
    options: KinesisRecordFlowOptions = KinesisRecordFlowOptions(),
): Flow<Record>
```

**Behavior / Contract**:

1. **Cold**: Each `collect {}` starts an independent poll loop with its own shard iterator and
   checkpoint state. No shared mutable state between subscriptions. `getShardIterator` is called
   **inside the `flow { }` lambda**, never in the `recordFlow` function body — the Flow is cold.

2. **Client lifetime**: The `KinesisClient` must remain open for the **entire duration** of every
   active `collect {}` call. Closing the client while a Flow is being collected results in
   SDK-level exceptions from the underlying connection pool. If using `withKinesisClient { }`,
   ensure all `collect` calls complete before the block exits.

3. **Iterator expiry recovery** (`ExpiredIteratorException`):
   - **Case A** — at least one record was emitted (`lastSeenSequenceNumber != null`): re-fetch
     via `AfterSequenceNumber(lastSeenSequenceNumber)`. Within a single `collect {}` call, **no
     record is emitted twice** (at-most-once re-emission per session). A new `collect {}` starts
     fresh from the original position — callers must persist checkpoints externally for
     cross-session at-most-once semantics.
   - **Case B** — no record emitted yet: re-fetch using the original `KinesisStartingPosition`.
   - Both retry counters (`iteratorRetryCount`, `throttleRetryCount`) **reset to zero** after
     every successful `getRecords` call. `maxIteratorRetries` therefore limits **consecutive**
     failures, not total lifetime failures.
   - After `options.maxIteratorRetries` consecutive failures, an ERROR log including
     `streamName`, `shardId`, and retry count is emitted; then the exception propagates.

4. **Throttle recovery** (`ProvisionedThroughputExceededException`, all `isRetryable=true`
   exceptions share the same budget):

   **SDK retry layer**: The AWS Kotlin SDK applies its own retry strategy before any exception
   reaches this library's code. Flow-level retry operates **after** the SDK has exhausted its
   own budget. Callers who configure a no-retry SDK strategy (e.g.
   `RetryStrategy.None` or `maxAttempts = 1`) will see SDK failures flow directly into the
   Flow-level retry; callers using SDK defaults will see compound retry. The README must document
   this interaction.

   **Flow-level retry**: Exponential backoff with full jitter:
   `delay = random(0, min(initialThrottleBackoff × 2^attempt, maxThrottleBackoff))`.
   Full jitter prevents thundering-herd when multiple consumers retry simultaneously.
   A WARN log is emitted on each retry attempt with the attempt count and delay. After
   `options.maxThrottleRetries` consecutive throttle failures at the Flow level, the exception
   propagates with an ERROR log including `streamName`, `shardId`, attempt count, and last
   exception message.

5. **Poll interval enforcement**: After every `getRecords` call — regardless of whether records
   were returned — the flow waits at least `options.pollInterval` before the next call. This
   enforces the AWS 5-calls/shard/second rate limit independent of collector speed or downstream
   `buffer()`/`flowOn` usage. Natural backpressure from `flow {}` is an _additional_ throttle,
   not a substitute for the explicit delay. **Empty-batch backoff**: when `getRecords` returns an
   empty list but non-null `nextShardIterator`, the flow waits `options.emptyBackoff` (which is
   always ≥ `pollInterval`) before the next poll.

6. **Shard end**: When `nextShardIterator == null`, the Flow completes normally (no exception).

7. **Cancellation**: `CancellationException` is always rethrown before any retry logic. All
   suspension points (`emit`, `delay`, SDK calls) are cooperative cancellation checkpoints.

8. **Logging policy**:
   - Sequence numbers and shard iterators: DEBUG level only, never INFO or WARN.
   - Record data (`Record.data`) must **never** be logged by the library at any level.
   - Error messages interpolating `KinesisStartingPosition` must include only the type name
     (e.g., `"AfterSequenceNumber"`), not the sequence number value.

9. **SDK timeout policy**: This library does not set timeouts on individual SDK calls. Operators
   must configure the `KinesisClient`'s HTTP client with appropriate read timeouts to prevent
   indefinite suspension on network partitions. Use `kinesisClientOf(httpClient = ..., builder = { ... })`
   to pass a custom `HttpClientEngine` or override client-level settings. `withKinesisClient {}` does
   **not** accept an HTTP client parameter — it delegates to `kinesisClientOf()` internally.
   This is documented in the README tuning section.

10. **`getShardIterator` null response**: Throws `IllegalStateException` with diagnostic message
    including `streamName` and `shardId` (but not the sequence number value from the position).
    This is a non-retryable programming error; a valid AWS endpoint never returns null for a
    valid stream/shard combination.

---

## 4. Error Handling Matrix

| Exception | Behaviour |
|---|---|
| `CancellationException` | Rethrown unconditionally (first catch). |
| `ExpiredIteratorException` | WARN log on each attempt; recovered up to `maxIteratorRetries`; then ERROR log + propagates. |
| `ProvisionedThroughputExceededException` | WARN log with backoff delay; exponential backoff up to `maxThrottleRetries`; then ERROR log + propagates. |
| Other `KinesisException` with `isRetryable=true` | Same as throttle (shared budget `maxThrottleRetries`). |
| Other `KinesisException` with `isRetryable=false` | Propagates immediately. |
| `nextShardIterator == null` | Not an error; Flow completes normally. |
| `getShardIterator` returns null | `IllegalStateException` with `streamName` + `shardId` context (no sequence number). |

---

## 5. File Plan

| File | Type |
|---|---|
| `…/kinesis/KinesisStartingPosition.kt` | New — sealed type |
| `…/kinesis/KinesisRecordFlowOptions.kt` | New — options data class |
| `…/kinesis/KinesisRecordFlow.kt` | New — Flow extension + private helpers |
| `…/kinesis/KinesisStartingPositionTest.kt` | New — unit tests |
| `…/kinesis/KinesisRecordFlowOptionsTest.kt` | New — validation unit tests |
| `…/kinesis/KinesisRecordFlowUnitTest.kt` | New — virtual-time tests (fake/MockK) |
| `…/kinesis/KinesisRecordFlowTest.kt` | New — LocalStack integration tests |
| `aws-kotlin/README.md` | Modified — Kinesis Flow section with tuning table |
| `aws-kotlin/README.ko.md` | Modified — same section (Korean) |

---

## 6. Out of Scope (v1)

- Multi-shard fan-out / `ListShards` auto-discovery (v2).
- `Flow<GetRecordsResponse>` variant carrying `millisBehindLatest` for lag monitoring (v2).
- DynamoDB Streams (separate follow-up issue — no AWS Kotlin SDK support).
- Enhanced fan-out (`SubscribeToShard`) — different runtime model, separate design.
- Metrics/tracing integration (v2) — WARN/ERROR logging at retry events is the v1 signal.

---

## 7. DoD Checklist

- [ ] `KinesisStartingPosition.kt` created; all five variants; `Serializable` + `serialVersionUID`; `readResolve()` on `data object`s; `AtTimestamp` uses `java.time.Instant`; `AtSequenceNumber` and `AfterSequenceNumber` have `private readObject` that re-runs validation after deserialization.
- [ ] `KinesisRecordFlowOptions.kt` created; all invariants in `init` with descriptive messages.
- [ ] `KinesisRecordFlow.kt` created; `recordFlow()` extension returns cold `Flow<Record>`.
- [ ] **`getShardIterator` call is inside the `flow { }` lambda body** (not in `recordFlow` function body — cold contract).
- [ ] Client lifetime documented in KDoc of `recordFlow()`.
- [ ] Iterator-expiry recovery: Case A (after last seq) and Case B (original position) implemented and tested.
- [ ] Retry counters reset to zero on each successful `getRecords` call.
- [ ] WARN log on each iterator-expiry attempt; ERROR log on exhaustion (with `streamName`, `shardId`, count).
- [ ] WARN log on each throttle attempt; ERROR log on exhaustion (with `streamName`, `shardId`, count, last error).
- [ ] Logging policy: sequence numbers at DEBUG only; `Record.data` never logged; error messages redact seq number value.
- [ ] Cancellation: `CancellationException` rethrown before retry logic in all catch blocks.
- [ ] Throttle backoff: **full jitter** exponential — `random(0, min(initial × 2^attempt, max))`; bounded by `maxThrottleBackoff`; capped by `maxThrottleRetries`; SDK retry layer interaction documented in README.
- [ ] `pollInterval` enforced after every `getRecords` call (non-empty and empty batches); unit test verifies delay is applied even with fast collector.
- [ ] Empty-batch backoff: `emptyBackoff` delay when `records.isEmpty()`.
- [ ] Shard-end: `nextShardIterator == null` → Flow completes normally.
- [ ] Virtual-time unit tests: cancellation, empty-batch, expiry Cases A+B, throttle, retries-exceeded, shard-end, retry-counter-reset.
- [ ] LocalStack integration test: create→active→putRecords(N records)→recordFlow.take(N).toList()→verify N records received→delete. Use `take(N)` to bound collection (open shards never emit `nextShardIterator == null` — shard-end is unit-tested with a mock). Cancellation path (Flow cancelled mid-stream) also integration-tested.
- [ ] `./gradlew :aws-kotlin:test` passes.
- [ ] `README.md` + `README.ko.md`: Kinesis Flow section + tuning reference table + client-lifetime warning + checkpoint guidance.
- [ ] English KDoc on all three public API files.
- [ ] SDK timeout policy documented in README: no built-in timeout; configure via `kinesisClientOf(httpClient = ...)` (not `withKinesisClient`); README tuning section references this explicitly.

---

## Appendix — Review Iteration Log

| Round | Phase | Reviewer | P0 | P1 | P2 | P3 | Commit |
|---|---|---|---|---|---|---|---|
| R1 | Phase 1 (4 perspectives) | Developer | 0 | 2 | 3 | 2 | (pre-R1 fix) |
| R1 | Phase 1 (4 perspectives) | Security | 0 | 0 | 2 | 1 | (pre-R1 fix) |
| R1 | Phase 1 (4 perspectives) | Ops/SRE | 0 | 3 | 3 | 1 | (pre-R1 fix) |
| R1 | Phase 1 (4 perspectives) | User/Caller | 0 | 3 | 2 | 0 | (pre-R1 fix) |
| R1 total | — | Combined | 0 | 8 | 10 | 4 | spec v2 |
| R1 → v2 | All Phase 1 HIGH applied | — | 0 | 0 | — | — | spec v2 |
| R2 | Phase 2 Critic | Critic | 0 | 2 | 2 | 0 | spec v2 |
| R2 → v3 | All Phase 2 HIGH+MEDIUM applied | H-A: ERROR log; H-B: kinesisClientOf; M-A: alternatives; M-B: DoD | 0 | 0 | 0 | — | spec v3 |
| R3 | Phase 3 Codex (gpt-5.5) | Codex | 0 | 4 | 7 | 3 | spec v3 |
| R3 | 6-tier Advisor (Opus) | Advisor | 0 | 2 | 4 | 1 | spec v3 |
| R3 combined | — | Combined | 0 | 6 | 11 | 4 | spec v3 |
| R3 → v4 | All P1 applied | DynamoDB Streams rationale; AtTimestamp ns precision; pollInterval enforcement; SDK/Flow retry layers; readObject; LocalStack take(N); jitter | 0 | 0 | — | — | spec v4 |
