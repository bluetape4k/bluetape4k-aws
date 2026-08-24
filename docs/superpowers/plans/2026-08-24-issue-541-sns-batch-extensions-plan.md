# SNS 배치 실행 전략·메시지 변환기 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 SNS batch 실행 안전성을 보존하면서 명시적 `SnsBatchExecutionStrategy` 주입과 Spring `Message<*>` 기반 `SnsBatchMessageConverter`를 `aws-spring-boot`에 추가한다.

**Architecture:** `SnsCoroutinesTemplate`은 기존 2-인자 생성자를 유지하고 명시적 3-인자 strategy 생성자를 추가한다. strategy에는 AWS client가 아니라 library-owned guarded port만 전달하며, 내부 generic coordinator가 10개 분할·bounded 실행·입력 순서·취소·transport/protocol redaction을 공통으로 소유한다. converter는 `spring-messaging`을 `compileOnly`로만 참조하고, 전송 없이 allowlist header와 명시적 suspend serializer를 typed `SnsPublishBatchRequest`로 변환한다.

**Tech Stack:** Kotlin 2.x, Kotlin Coroutines, AWS SDK for Java v2 SNS, Spring Boot 4 `Message`, Gradle version catalog, JUnit 5, MockK, Kluent-compatible bluetape assertions, Floci 우선 emulator 정책.

---

## 구현 범위와 파일 책임

### 새 production 파일

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutionStrategy.kt`
  - public `SnsBatchExecutionPort`, `SnsBatchExecutionStrategy`, contract error enum/exception.
  - 기존 생성자 기본 경로가 사용할 `DefaultSnsBatchExecutionStrategy` adapter.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutionCoordinator.kt`
  - raw SDK response와 typed port result 양쪽이 공유하는 내부 generic chunk coordinator.
  - 10개 chunk, bounded worker, no unbounded pending queue, ordered collector, sibling cancellation, completed-entry tracking을 소유한다.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutionGuard.kt`
  - request ID subset, 1..10 chunk, duplicate claim, `maxInFlightBatches` no-queue, SDK mapping, protocol validation, redaction, claim release를 소유한다.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchResponseMapper.kt`
  - `PublishBatchResponse`를 typed result로 바꾸고 unknown/duplicate/missing ID를 검증하는 내부 순수 mapper.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchMessageConverter.kt`
  - `SnsPayloadSerializer`, `SnsBatchMessageConversionOptions`, `SnsBatchMessageHeaders`, conversion error enum/exception, `SnsBatchMessageConverter` public API.

### 수정 production 파일

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsCoroutinesTemplate.kt`
  - strategy 필드와 2-/3-인자 JVM 생성자를 추가하고, `publishBatch`를 guarded port + strategy 경로로 전환한다. 단건 API와 `SnsOperations` fallback은 바꾸지 않는다.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutor.kt`
  - 기존 외부 동작을 유지하면서 공통 coordinator/response mapper adapter로 정리한다. 기존 terminal-ID, cancellation, no-retry semantics를 회귀 테스트로 고정한다.
- `gradle/libs.versions.toml`
  - `spring-messaging = { module = "org.springframework:spring-messaging" }` alias를 추가한다.
- `aws-spring-boot/build.gradle.kts`
  - `compileOnly(libs.spring.messaging)`만 추가한다. runtime/api 의존성이나 auto-configuration bean은 추가하지 않는다.

### 새·수정 테스트 파일

- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutionStrategyTest.kt`
  - public strategy contract, guarded port, result normalization, redaction, cancellation, concurrency/no-queue, constructor ABI를 검증한다.
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchMessageConverterTest.kt`
  - header allowlist, UUID ID 우선순위, attribute copy/type guard, serializer, FIFO, preflight atomicity, cancellation, safe errors를 검증한다.
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsCoroutinesTemplateTest.kt`
  - 기존 SDK mapping 회귀와 injected strategy 실제 호출을 함께 검증한다.
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutorTest.kt`
  - coordinator 추출 후에도 기존 12개 안전성 테스트가 같은 의미로 통과하는지 검증한다.
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsSpringMessagingClasspathTest.kt`
  - `spring-messaging`이 compileOnly이고 SNS 기존 public classpath에 runtime 강제가 없음을 Gradle classpath로 확인한다.

### 사용자 문서

- `README.md`, `README.ko.md`: SNS batch strategy/converter 사용법, opt-in `spring-messaging`, byte-size preflight 후속 범위를 양 언어로 같은 구조에 반영한다.
- `docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`, `docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`: SNS 섹션에 동일한 API 계약·의존성·실패 경계 예제를 추가한다. `docs/manual/manifest.yaml`의 `releaseRef: 0.5.0`은 변경하지 않는다.
- `docs/review/2026-08-24-issue-541-sns-batch-extensions-implementation-review.md`: 구현 결과, canary/rollback/telemetry 범위, release-pinned manual 검증, follow-up 후보를 한국어로 기록한다.

### 구현 금지 범위

- `SnsOperations` overload, factory, auto-configuration bean, runtime `spring-messaging` 의존성을 만들지 않는다.
- strategy에 `SnsAsyncClient`, `CompletableFuture`, credential, retry/backoff, raw thread, `GlobalScope`를 노출하지 않는다.
- 기본 serializer에 Jackson/Jackson3 또는 `ByteArray` 정책을 암묵적으로 추가하지 않는다.
- SNS 262,144-byte wire-size preflight는 후속 이슈 후보로만 기록하고 이번 구현에서 정확한 계산을 주장하지 않는다.
- 기존 `SnsBatchTransportException.completedEntryIds`와 `SnsBatchProtocolException.completedEntryIds` semantics는 변경하지 않는다. 큰 batch의 전체 terminal ID 보존은 기존 recovery 계약이며, selective recovery 정보가 필요한 호출자를 위해 임의 cap을 도입하지 않는다. payload·credential을 ID로 넣는 호출자 오류를 새 API가 자동으로 정화한다고 주장하지 않으며, 새 conversion/contract 예외에는 raw ID를 넣지 않는다.
- PR 생성·merge·publish·tag·remote branch 삭제는 이번 구현 계획의 실행 범위가 아니다.

## 실행 전 공통 증거와 안전 규칙

- 작업 branch는 `feat/issue-541-sns-batch-extensions`, 기준 commit은 `fe24e60204d74d730bd189d2c67f260b1d834f79`, 현재 설계 commit은 `4eac62de1de8c1979cf8379fd2e1e6ffc9771530`이다.
- 각 production 변경 전에 해당 behavior의 failing test를 먼저 추가한다. 아래 각 task는 실제 test class와 Gradle selector를 명시하며, red 단계의 기대 결과는 새 API/타입/동작이 없다는 `FAIL`이다. 실패 output을 읽은 뒤에만 최소 구현을 추가한다.
- 모든 변경 후 `git diff --check`를 실행한다. 문서 변경은 `ruby scripts/manual/manual_contract_test.rb`와 `ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check`로 확인한다.
- AWS emulator 테스트는 repository 기본값인 Floci를 사용한다. Docker-backed 테스트를 추가할 경우 공유 emulator 자원 때문에 병렬 실행하지 않는다.
- 실패가 발생하면 테스트를 삭제하거나 skip하지 않고 원인을 고친 뒤 같은 대상 테스트를 강제 rerun한다.

## Task 1: public strategy 계약과 ABI 회귀 테스트를 먼저 고정

**Files:**
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutionStrategyTest.kt`

- [ ] **Step 1: failing test — public constructors and public contract compilation**

  Add a compile/reflection test for the public contract and assert the `SnsCoroutinesTemplate` constructor parameter lists are exactly:

  ```kotlin
  listOf(
      listOf(SnsAsyncClient::class.java, SnsProperties::class.java),
      listOf(SnsAsyncClient::class.java, SnsProperties::class.java, SnsBatchExecutionStrategy::class.java),
  )
  ```

  The test body must filter public constructors and compare the exact JVM types:
  ```kotlin
  @Test
  fun `template keeps additive constructor descriptors`() {
      val signatures = SnsCoroutinesTemplate::class.java.constructors
          .map { it.parameterTypes.toList() }
          .toSet()
      signatures shouldBeEqualTo setOf(
          listOf(SnsAsyncClient::class.java, SnsProperties::class.java),
          listOf(SnsAsyncClient::class.java, SnsProperties::class.java, SnsBatchExecutionStrategy::class.java),
      )
  }
  ```

  Do not invoke strategy or port in this task and do not add an unguarded adapter. Do not accept a synthetic public default-argument constructor as the contract; strategy invocation moves to Task 3 after the guard exists.

- [ ] **Step 2: run the concrete red test**

  Run:
  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --rerun-tasks \
    --tests 'io.bluetape4k.aws.spring.sns.SnsBatchExecutionStrategyTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsCoroutinesTemplateTest'
  ```
  Expected: `FAIL` because `SnsBatchExecutionStrategy` and the three-argument constructor do not yet exist.

- [ ] **Step 3: add the minimal public contract**

  Create `SnsBatchExecutionStrategy.kt` with these public signatures and no raw AWS types:

  ```kotlin
  public interface SnsBatchExecutionPort {
      public suspend fun publishChunk(entries: List<SnsPublishBatchEntry>): SnsPublishBatchResult
  }

  public fun interface SnsBatchExecutionStrategy {
      public suspend fun execute(
          request: SnsPublishBatchRequest,
          options: SnsBatchExecutionOptions,
          port: SnsBatchExecutionPort,
      ): SnsPublishBatchResult
  }

  public enum class SnsBatchExecutionContractError {
      INVALID_CHUNK,
      DUPLICATE_CLAIM,
      TOO_MANY_IN_FLIGHT,
      INVALID_RESULT,
      STRATEGY_FAILURE,
      PORT_CLOSED,
      OUTSTANDING_CLAIM,
  }

  public class SnsBatchExecutionContractException(
      public val error: SnsBatchExecutionContractError,
  ) : IllegalStateException("SNS batch execution contract failed: error=$error")
  ```

  Public KDoc for `SnsBatchExecutionStrategy` must state that one strategy instance may be invoked concurrently by a Spring singleton and therefore implementations must be stateless or thread-safe; request-local claims and completed metadata never cross invocation boundaries.

  Preserve the existing public two-argument `SnsCoroutinesTemplate` descriptor and add the explicit three-argument descriptor without a public default parameter. Use a private marker constructor or equivalent compiler-safe arrangement; verify with reflection rather than relying on source appearance.

- [ ] **Step 4: run the constructor/contract tests green**

  Rerun the command from Step 2. Expected: the public contract and constructor reflection tests pass; no SDK call is expected from this API-only task.

## Task 2: extract the bounded coordinator without changing existing executor behavior

**Files:**
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutionCoordinator.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchResponseMapper.kt`
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutor.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutorTest.kt`

- [ ] **Step 1: write a coordinator regression test for the current behavior**

  Keep the existing matrix, order/mixed result, terminal-entry-before-mapping, active-worker, first-sequence-stall, cancellation identity, sibling cancellation, and non-positive option tests. First add a successful `1000`-entry characterization test for `maxInFlightBatches` values `1`, `2`, and `8`; assert exactly `ceil(1000 / 10) == 100` calls, maximum active calls at or below the option, fixed worker count `minOf(option, 100)`, pending result count at or below the option, resident in-flight entries at or below `10 * option`, and distinct submitted IDs. Keep transport failure/no-retry as a separate test: assert failure stops new work, duplicate submitted IDs are zero, and call count is at most `100` rather than asserting successful completion.

  The success characterization uses the existing `RecordingPublisher` shape:
  ```kotlin
  listOf(1, 2, 8).forEach { concurrency ->
      val publisher = RecordingPublisher()
      val result = SnsBatchExecutor(publisher::publish)
          .execute(request(1_000), SnsBatchExecutionOptions(concurrency))
      publisher.chunks shouldHaveSize 100
      publisher.maxActive shouldBeLessOrEqualTo concurrency
      publisher.chunks.flatten().distinct().size shouldBeEqualTo 1_000
      result.successful shouldHaveSize 1_000
  }
  ```

- [ ] **Step 2: run the concrete red performance/regression test**

  Run:
  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --rerun-tasks \
    --tests 'io.bluetape4k.aws.spring.sns.SnsBatchExecutorTest'
  ```
  Expected: `BUILD SUCCESSFUL` against the existing executor. This is a characterization baseline, so the new success and failure tests must already pass before the refactor; no implementation behavior is changed in this step.

- [ ] **Step 3: extract the generic algorithm**

  Implement `SnsBatchExecutionCoordinator<T>` and its internal result value with:

  ```kotlin
  internal class SnsBatchExecutionCoordinator<T>(
      private val publishChunk: suspend (topicArn: String, entries: List<SnsPublishBatchEntry>) -> T,
      private val mapChunk: (entries: List<SnsPublishBatchEntry>, response: T) -> SnsBatchChunkResult,
  )
  ```

  ```kotlin
  internal data class SnsBatchChunkResult(
      val successful: List<SnsPublishBatchSuccess>,
      val failed: List<SnsPublishBatchFailure>,
  )
  ```

  The coordinator must create exactly `minOf(maxInFlightBatches, chunkCount)` long-lived workers, let each worker claim the next chunk only when it is ready, and never create one coroutine/job per input chunk. Use a rendezvous result channel, collect by sequence, and release the worker slot only after ordered collection. When `publishChunk` returns, append that chunk's entry IDs to the bounded completed-ID list before `mapChunk` so protocol/mapping failures retain terminal IDs. Re-throw `CancellationException`, `SnsBatchTransportException`, `SnsBatchProtocolException`, and `SnsBatchExecutionContractException` unchanged; wrap other runtime errors with `SnsBatchTransportException.from(cause, completedIds)`. Close/cancel channels and sibling jobs in `finally` and use `NonCancellable` for terminal bookkeeping. The acceptance counters must distinguish the final O(N) result/completed-ID lists from the in-flight resident bound; while work is active, claimed chunks, pending results, and resident entries must not exceed the worker count and `10 * workerCount` respectively.

  Move the existing response mapping into `SnsBatchResponseMapper.kt` as an internal function that checks unknown, duplicate, and missing IDs before ordering `successful` and `failed` entries relative to the submitted chunk. `SnsBatchExecutor` becomes a thin AWS-response adapter that supplies `publishChunk` and this mapper. Do not change `SnsOperations`.

- [ ] **Step 4: preserve existing transport/protocol metadata semantics**

  Do not modify `SnsBatchExceptions.kt`: existing callers receive all terminal `completedEntryIds` from transport failures and the existing protocol exception shape remains unchanged. Add a characterization assertion to `SnsBatchExecutorTest` that a terminal response followed by a mapper failure preserves every completed ID, while a protocol mismatch remains explicitly uncertain with an empty completed-ID list. The new strategy/converter contract and conversion exceptions remain cause-free and never add raw IDs or payloads; the template never retries an uncertain whole request automatically.

- [ ] **Step 5: rerun the regression suite**

  Run the command from Step 2 and then the baseline pair:
  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --rerun-tasks \
    --tests 'io.bluetape4k.aws.spring.sns.SnsOperationsBatchCompatibilityTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsBatchExecutorTest'
  ```
  Expected: `BUILD SUCCESSFUL`; existing 12 baseline tests plus the new 1000-entry tests pass, with no retry and no secret text in exception output.

## Task 3: guarded port and default/injected template path

**Files:**
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutionGuard.kt`
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutionStrategy.kt`
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsCoroutinesTemplate.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutionStrategyTest.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsCoroutinesTemplateTest.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsAutoConfigurationTest.kt`

- [ ] **Step 1: write failing guard tests**

  Add tests for these exact cases:

  1. `publishChunk` with 0 or 11 entries throws `SnsBatchExecutionContractException(INVALID_CHUNK)` and makes zero SDK calls.
  2. An entry ID outside `request.entries` throws `INVALID_CHUNK`; repeating a previously successful ID throws `DUPLICATE_CLAIM`.
  3. A caller list with 10,000 entries is rejected as `INVALID_CHUNK` before the guard copies any entry or calls the SDK; assert the guard's copy counter and SDK counter are both zero.
  4. With `maxInFlightBatches = 1`, a first suspended port call keeps the claim active and a second concurrent call fails immediately with `TOO_MANY_IN_FLIGHT`; assert no queued call starts after release.
  5. A custom strategy result with unknown, duplicate, or missing aggregate IDs throws `INVALID_RESULT`; valid mixed results are normalized to request order.
  6. A custom strategy `IllegalStateException("payload-secret")` becomes `STRATEGY_FAILURE`, has no cause, and its `toString()` does not contain `payload-secret`.
  7. A custom strategy-thrown `CancellationException` is the exact same instance; a port transport/protocol exception passes through unchanged.
  8. Caller cancellation while SDK future is suspended cancels sibling work, releases claims in `NonCancellable`, and preserves the caller cancellation identity.
  9. A strategy that catches a port `SnsBatchTransportException` and throws a generic exception is reported as redacted `STRATEGY_FAILURE`; the template performs no automatic whole-request retry, and the test records that the port was called once for each attempted ID.
  10. A strategy that launches a child in the caller scope must be drained before return; a detached `GlobalScope`/raw executor is documented as unsupported, and the injected `SnsAsyncClient` remains caller/Spring-bean owned and is never closed by the template.
  11. After a strategy returns, a retained port is closed in `finally`; a post-return `publishChunk` receives `PORT_CLOSED` and makes zero SDK calls. An in-flight claim is cancelled/awaited before close; if it cannot drain, the template reports `OUTSTANDING_CLAIM`.
  12. The same stateless strategy instance can serve two concurrent requests without mixing request IDs or claims.

  The no-queue assertion must observe immediate rejection rather than delayed execution inside one strategy invocation:
  ```kotlin
  val firstStarted = CompletableDeferred<Unit>()
  val releaseFirst = CompletableDeferred<Unit>()
  val strategy = SnsBatchExecutionStrategy { request, _, port ->
      coroutineScope {
          val first = async {
              firstStarted.complete(Unit)
              port.publishChunk(request.entries.take(1))
          }
          firstStarted.await()
          val error = assertFailsWith<SnsBatchExecutionContractException> {
              port.publishChunk(request.entries.drop(1))
          }
          error.error shouldBeEqualTo SnsBatchExecutionContractError.TOO_MANY_IN_FLIGHT
          releaseFirst.complete(Unit)
          first.await()
          SnsPublishBatchResult(emptyList(), emptyList())
      }
  }
  template(strategy).publishBatch(request(2), SnsBatchExecutionOptions(1))
  ```

- [ ] **Step 2: run the concrete red guard test**

  Run:
  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --rerun-tasks \
    --tests 'io.bluetape4k.aws.spring.sns.SnsBatchExecutionStrategyTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsCoroutinesTemplateTest'
  ```
  Expected: `FAIL` because the port is not yet guarded/wired.

- [ ] **Step 3: implement the guard and default strategy**

  Implement an internal `SnsBatchExecutionGuard` with lifecycle `OPEN -> CLOSING -> CLOSED`, created per template invocation with the request ID set and options. Check `entries.size in 1..10` before copying anything; this rejects an oversized caller list without an allocation proportional to its size. Only after the size check, make a bounded copy of at most ten entries and protect active claim count and claimed IDs with one `Mutex`; reject request-ID subset, duplicate IDs, and active-count overflow while holding the mutex, never suspending as a queue. Keep invocation-wide `attemptedEntryIds` separate from `activeClaims`: releasing a permit must never remove an attempted ID, so a strategy cannot retry the same entry through the same invocation. Build the AWS `PublishBatchRequest` from `request.topicArn`, call `snsAsyncClient.publishBatch(...).await()`, record the chunk IDs before response mapping, map via `SnsBatchResponseMapper`, and release the claim in a `withContext(NonCancellable)` block. Catch `CancellationException` first; pass existing redacted contract/transport/protocol exceptions through; convert only expected `Exception`/`RuntimeException` failures to `SnsBatchTransportException.from` and let fatal `Error` values propagate unchanged. In template `finally`, enter `withContext(NonCancellable)` before transitioning the guard to `CLOSING`, cancel and await tracked in-flight child jobs and SDK futures, verify active claims are zero, then transition to `CLOSED`; post-close port calls fail with `PORT_CLOSED`, and a failed drain uses `OUTSTANDING_CLAIM`. Add a test that cancels immediately before `finally`, proves drain completes without timeout, preserves the caller cancellation instance, and observes post-close `PORT_CLOSED` with zero SDK calls. The guard's KDoc states that the injected client is caller/Spring-bean owned, the template never closes it, and strategy work must remain in the structured caller scope.

  Implement `DefaultSnsBatchExecutionStrategy` by running `SnsBatchExecutionCoordinator<SnsPublishBatchResult>` against `port.publishChunk`; it must not know the SDK client. In `SnsCoroutinesTemplate`, preserve every existing method and make `publishBatch` return an empty typed result without strategy/client work for an empty request, otherwise create the guard and call the selected strategy. After strategy completion, assert the guard has no outstanding claims and validate/normalize the aggregate result against the request's exact ID set. Convert only non-contract/non-transport/non-protocol/non-cancellation strategy failures to `SnsBatchExecutionContractException(STRATEGY_FAILURE)`, never retrying an uncertain partial publish. Keep `SnsAutoConfiguration` on the two-argument default constructor. Public KDoc must explain that strategy errors after a port call can leave an uncertain remote state and callers must not replay the whole request automatically.

- [ ] **Step 4: run phase-one targeted tests**

  Run:
  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --rerun-tasks \
    --tests 'io.bluetape4k.aws.spring.sns.SnsBatchExecutionStrategyTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsCoroutinesTemplateTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsBatchExecutorTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsOperationsBatchCompatibilityTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsAutoConfigurationTest'
  ```
  Expected: `BUILD SUCCESSFUL`; SDK call counts, input order, partial results, contract errors, no-queue bound, cancellation identity, and redaction all pass.

- [ ] **Step 5: create the Phase 1 rollback checkpoint**

  After the phase-one targeted matrix is green, run `git diff --check`, inspect the exact strategy/coordinator/guard/template/test diff, and commit the strategy checkpoint before starting converter code. Use this Korean Lore message and record the resulting SHA in the implementation review:
  ```text
  SNS batch strategy 실행 경계를 고정해 기존 안전성을 보존한다

  Constraint: 기존 2-인자 생성자와 SnsOperations fallback을 유지한다
  Rejected: strategy에 raw client와 unbounded queue 노출 | guarded port로 제한
  Confidence: high
  Scope-risk: moderate
  Directive: converter 작업은 이 검증된 Phase 1 head 위에서만 진행한다
  Tested: strategy, coordinator, guard, executor, auto-configuration focused tests
  Not-tested: converter와 release-pinned manual은 Phase 2에서 검증한다
  ```
  Expected: a clean Phase 1 commit exists locally; no PR, merge, remote push, or branch deletion occurs.

## Task 4: converter contract tests and implementation

**Files:**
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchMessageConverterTest.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchMessageConverter.kt`

- [ ] **Step 1: write failing converter tests**

  Use `MessageBuilder.withPayload(...)` and `MessageHeaders.ID` with fixed UUIDs. Add tests for:

  - default constructor accepts a `String`, uses UUID from `MessageHeaders.ID`, and rejects a non-String payload with `SERIALIZATION_FAILED`;
  - explicit `SnsPayloadSerializer { payload -> ... }` serializes a structured payload without adding Jackson;
  - `SnsBatchMessageHeaders.MESSAGE_ID` UUID wins over fallback, while a present wrong-type explicit ID fails `INVALID_ID_TYPE` even if fallback is valid; missing or wrong fallback fails safely;
  - subject requires `String`; message attributes require `Map<String, MessageAttributeValue>`, reject blank keys/null/wrong values, and are defensively copied;
  - `MessageGroupId` and `MessageDeduplicationId` map only for `.fifo` topic; standard topic rejects them as `INVALID_FIFO` and FIFO without group rejects safely;
  - blank topic, `maxMessages <= 0`, and collection size over the limit fail before serializer invocation;
  - duplicate IDs and a serializer/iterator failure produce `DUPLICATE_ID`, `SERIALIZATION_FAILED`, or `ITERATION_FAILED` with only `entryIndex`, enum error, and allowlisted field; `cause == null`, raw payload/secret/ARN absent from `toString()`;
  - cancellation before/after an item and inside serializer rethrows the exact `CancellationException` and no request is returned;
  - `convertAll` returns a defensive typed request and never invokes an SNS client (the test should have no client at all).
  - with `messages.size <= maxMessages`, the serializer is called exactly once per message, the ID set and output list are O(N), each attribute map is copied once, and unrelated Spring headers are never copied. A `Collection` whose size is `maxMessages + 1` fails before the first serializer invocation. The public signature remains `Collection<Message<*>>`; no unbounded `Iterable` or `Sequence` overload is added.

  The minimum valid conversion fixture is concrete:
  ```kotlin
  val messageId = UUID.fromString("00000000-0000-0000-0000-000000000001")
  val message = MessageBuilder.withPayload("hello")
      .setHeader(MessageHeaders.ID, messageId)
      .build()
  val entry = SnsBatchMessageConverter().convert(message)
  entry.id shouldBeEqualTo messageId.toString()
  entry.message shouldBeEqualTo "hello"
  ```

- [ ] **Step 2: run the concrete red converter test**

  Run:
  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --rerun-tasks \
    --tests 'io.bluetape4k.aws.spring.sns.SnsBatchMessageConverterTest'
  ```
  Expected: `FAIL` because `spring-messaging` and converter types do not yet exist.

- [ ] **Step 3: add the compileOnly dependency and public converter types**

  Add the catalog alias and dependency exactly as follows:

  ```toml
  spring-messaging = { module = "org.springframework:spring-messaging" }
  ```

  ```kotlin
  compileOnly(libs.spring.messaging)
  ```

  Fix the public descriptors in the source before writing implementation details:

  ```kotlin
  public fun interface SnsPayloadSerializer {
      public suspend fun serialize(payload: Any?): String
  }

  public data class SnsBatchMessageConversionOptions(
      public val maxMessages: Int = 10_000,
  )

  public class SnsBatchMessageConverter(
      private val serializer: SnsPayloadSerializer,
  ) {
      public constructor() : this(SnsPayloadSerializer { payload ->
          require(payload is String) { "SNS batch payload must be String or use an explicit serializer." }
          payload
      })
      public suspend fun convert(message: Message<*>): SnsPublishBatchEntry
      public suspend fun convertAll(
          topicArn: String,
          messages: Collection<Message<*>>,
          options: SnsBatchMessageConversionOptions = SnsBatchMessageConversionOptions(),
      ): SnsPublishBatchRequest
  }
  ```

  The constructor descriptors are `()` and `(SnsPayloadSerializer)`; `convert` and `convertAll` are suspend methods and `convertAll` accepts `Collection<Message<*>>`, not `Iterable` or `Sequence`. Define the exact public header constants and signatures from the approved spec. The default serializer accepts only `String`; the injected serializer is `suspend`. Use `currentCoroutineContext().ensureActive()` before and after each message, validate `Collection` size and options before serializer calls, and construct `SnsPublishBatchRequest` only after all entries are converted. Resolve ID in this order: explicit `MESSAGE_ID` (must be `UUID`), fallback `MessageHeaders.ID` (must be `UUID`); use `UUID.toString()` only after the type check. Copy `messageAttributes.toMap()` and validate every key/value before constructing the entry. Catch `CancellationException` first and rethrow it unchanged; normalize only expected `Exception`/`RuntimeException` values to a cause-free `SnsBatchMessageConversionException` with enum, entry index, and one of `topicArn`, `options`, `id`, `subject`, `messageAttributes`, `messageGroupId`, `messageDeduplicationId`, or `payload` as field. Let fatal `Error` values propagate unchanged. Do not store payload, headers, ARN, serializer exception, or cause.

- [ ] **Step 4: run converter tests green**

  Rerun the command from Step 2. Expected: `BUILD SUCCESSFUL`; test output must show no client/network setup and no leaked raw secret in exception text.

## Task 5: compileOnly/classpath and ABI verification

**Files:**
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsSpringMessagingClasspathTest.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutionStrategyTest.kt`
- Use: `aws-spring-boot/src/test/resources/sns-abi/io/bluetape4k/aws/spring/sns/consumer/LegacySnsOperationsFixture.class`

- [ ] **Step 1: add failing classpath/ABI checks**

  Assert the public constructors of `SnsCoroutinesTemplate` and `SnsBatchMessageConverter` are exactly the approved descriptors; assert `SnsBatchExecutionPort`, `SnsBatchExecutionStrategy`, `SnsPayloadSerializer`, `convert`, and `convertAll` expose only the typed SNS/Spring contracts and exact suspend JVM descriptors. Assert `DefaultSnsBatchExecutionStrategy`, `SnsBatchExecutionCoordinator`, `SnsBatchExecutionGuard`, `SnsBatchResponseMapper`, and `SnsBatchChunkResult` are `internal` and absent from the public API dump. Add two isolated classloader fixtures: (a) deny `org.springframework.messaging.` and load the precompiled legacy `SnsOperations`/two-argument template consumer to prove existing SNS usage works without messaging, and (b) load a converter fixture only when an explicit `spring-messaging` URL is supplied. Inspect Gradle resolved configurations and generated POM/module metadata rather than assuming the version catalog alias is enough; `spring-messaging` must appear under `compileOnly` and never under `api` or runtime dependencies. Include `SnsAutoConfigurationTest` to prove the default bean still uses the two-argument constructor and does not auto-inject a custom strategy.

  The reflection assertion for converter descriptors is:
  ```kotlin
  SnsBatchMessageConverter::class.java.constructors
      .map { it.parameterTypes.toList() }
      .toSet() shouldBeEqualTo setOf(
          emptyList(),
          listOf(SnsPayloadSerializer::class.java),
      )
  ```

- [ ] **Step 2: run the concrete checks**

  Run:
  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --rerun-tasks \
    --tests 'io.bluetape4k.aws.spring.sns.SnsSpringMessagingClasspathTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsBatchExecutionStrategyTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsAutoConfigurationTest'
  ```
  Expected: `BUILD SUCCESSFUL`; no unexpected public constructor, raw SDK leak, or runtime dependency appears.

- [ ] **Step 3: run consumer and module ABI/build checks**

  Run:
  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:compileKotlin \
    :bluetape4k-aws-spring-boot:binaryCompatibilityValidator \
    :bluetape4k-aws-spring-boot:test --rerun-tasks \
    --tests 'io.bluetape4k.aws.spring.sns.SnsOperationsBatchCompatibilityTest'
  ```
  Expected: `BUILD SUCCESSFUL`; the existing legacy `SnsOperations` fixture still loads and default auto-configuration still creates the same operation bean. If the repository does not expose `binaryCompatibilityValidator`, run the module's available ABI task discovered with `./gradlew :bluetape4k-aws-spring-boot:tasks --all` and record that exact task in the review evidence.

- [ ] **Step 4: verify the published dependency surface**

  Run the module's generated-POM task discovered from `./gradlew :bluetape4k-aws-spring-boot:tasks --all`, then inspect the generated POM and Gradle module metadata for `org.springframework:spring-messaging`; it must be absent from runtime dependencies. Run the release-pinned manual validator with the known 0.5.0 peeled commit:
  ```bash
  TAG=0.5.0
  SHA=664e4dfb544a3c19db484b0f9a8e023a73774b49
  ruby scripts/manual/validate_release_manuals.rb "$TAG" "$SHA"
  ./gradlew exportManualModuleInventory --no-daemon
  ```
  Expected: generated consumer metadata has no mandatory `spring-messaging`, and the 0.5.0 release-tree links remain valid while new content is explicitly marked `Unreleased/develop`.

## Task 6: documentation and follow-up issue linkage

**Files:**
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`
- Modify: `docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`
- Do not modify: `docs/manual/manifest.yaml` release pin

- [ ] **Step 1: add synchronized English/Korean usage sections**

  Document the two constructors, guarded strategy limitations, ordered typed result, default String serializer, explicit serializer example, exact header constants, direct `spring-messaging` runtime requirement for converter users, and no-network-on-conversion-failure. Keep manual release metadata at `releaseRef: 0.5.0`, label new material as `Unreleased`/`develop`, and preserve all API names, commands, URLs, and identifiers exactly. State that byte-size preflight, Jackson3 adapter, and `ByteArray` support are follow-up scope rather than implemented behavior.

- [ ] **Step 2: run documentation contracts**

  Run:
  ```bash
  git diff --check
  ruby scripts/manual/manual_contract_test.rb
  ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
  node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
    README.ko.md docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md --json
  ```
  Expected: no whitespace findings, manual contract PASS, generated manifest check PASS, and Korean terminology `findings: []`. English/Korean headings, links, code fences, and examples must remain structurally aligned.

  Run this deterministic parity receipt in addition to the manual contracts; it compares heading-depth sequence, code-fence count, and Markdown link count without requiring English and Korean heading text to be identical:
  ```bash
  ruby -e '
    pairs = {
      "README" => ["README.md", "README.ko.md"],
      "manual" => ["docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md", "docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md"]
    }
    pairs.each do |name, files|
      shapes = files.map do |file|
        text = File.read(file)
        [text.scan(/^(#+) /).map { |m| m.first.length }, text.scan(/^\x60\x60\x60/).length, text.scan(/\[[^\]]+\]\([^\)]+\)/).length]
      end
      abort "#{name} parity mismatch: #{shapes.inspect}" unless shapes.uniq.one?
    end
  '
  ```

- [ ] **Step 3: audit public KDoc and operational boundary**

  Add Korean KDoc to every new public interface, enum, exception, serializer, options data class, header object, converter, and constructor. The KDoc must state typed inputs, cancellation identity, no-network conversion, redaction/no-cause, caller-owned client lifecycle, no automatic retry after uncertain partial publish, and `spring-messaging` opt-in. Record a declaration checklist in the implementation review and run:
  ```bash
  rg -n "public (interface|fun interface|class|enum class|object|data class|suspend fun|constructor)" \
    aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutionStrategy.kt \
    aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchMessageConverter.kt
  ```
  The operational scope is explicit: this change adds no runtime telemetry or IAM mutation. The canary is the explicit-constructor/isolated-consumer test, publish stop and in-flight drain are the guard lifecycle tests, rollback is the Phase 1 checkpoint revert to the default two-argument path, and low-cardinality strategy/chunk/protocol/transport counts remain a follow-up issue. No payload, ARN, credential, or raw SDK message may be used as a metric/log tag.

- [ ] **Step 4: record the follow-up candidate without changing this implementation**

  In the implementation review/DoD evidence, record the follow-up candidate for SNS 262,144-byte batch preflight, Jackson3 opt-in serializer, `ByteArray` support, and low-cardinality strategy telemetry with concrete acceptance criteria: individual and aggregate byte accounting, serializer media type, no raw payload in metrics, and benchmark evidence. The later Converter PR is based on the exact Strategy PR head; Strategy PR base is `develop`, Converter PR base is the merged Strategy head, and both PR bodies end with `## DoD Status`. Do not create a GitHub issue or mutate remote metadata in this plan execution; issue creation remains a separate explicit remote-mutation gate.

## Task 7: full verification, static analysis, and implementation checkpoint

**Files:**
- Modify: none unless verification exposes a defect
- Evidence: `.bluetape` workflow receipts and `docs/review/2026-08-24-issue-541-sns-batch-extensions-plan-review.md` plus implementation review artifact

- [ ] **Step 1: run focused implementation matrix**

  Run sequentially:
  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --rerun-tasks \
    --tests 'io.bluetape4k.aws.spring.sns.SnsBatchExecutionStrategyTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsBatchMessageConverterTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsBatchExecutorTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsCoroutinesTemplateTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsOperationsBatchCompatibilityTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsSpringMessagingClasspathTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsBatchExceptionsTest' \
    --tests 'io.bluetape4k.aws.spring.sns.SnsAutoConfigurationTest'
  ```
  Expected: `BUILD SUCCESSFUL`, all targeted strategy/converter/legacy tests pass, no emulator test is silently counted as coverage.

- [ ] **Step 1b: run the explicit Floci SNS batch smoke**

  Add `SnsBatchExecutionFlociTest` only when the existing emulator test base can create a topic and publish batch; otherwise record the capability gap without skipping a test that claims coverage. Run sequentially:
  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    -Dbluetape4k.aws.emulator=floci \
    --max-workers=1 --rerun-tasks \
    --tests 'io.bluetape4k.aws.spring.sns.SnsBatchExecutionFlociTest'
  ```
  Expected: `BUILD SUCCESSFUL` with Floci startup/backend evidence, or an explicit `PENDING` receipt naming the unsupported SNS batch capability and keeping fake-publisher evidence separate.

- [ ] **Step 2: run module and static checks**

  Run:
  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:check --rerun-tasks
  ./gradlew detekt --rerun-tasks
  ./gradlew build -x test --parallel
  ```
  Expected: `BUILD SUCCESSFUL` for each command. Read warnings and classify them as pre-existing or fixed; do not suppress new detekt findings without a reason.

- [ ] **Step 2b: collect changed-class coverage when the task is available**

  Run `./gradlew :bluetape4k-aws-spring-boot:tasks --all` and, if a Kover XML task is present, run `./gradlew :bluetape4k-aws-spring-boot:koverXmlReport --rerun-tasks`; inspect the XML for the strategy, guard, coordinator, mapper, and converter classes. If no Kover task exists, record the task discovery output and the reason coverage is qualitative rather than treating the omission as a pass.

- [ ] **Step 3: run repository-wide contract checks and inspect the diff**

  Run:
  ```bash
  git diff --check
  git status --short
  git diff --stat
  git diff -- aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns \
    aws-spring-boot/build.gradle.kts gradle/libs.versions.toml \
    README.md README.ko.md docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md \
    docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md
  ```
  Expected: only the planned SNS strategy/converter, dependency, tests, and synchronized docs are changed; no credentials, payloads, generated build outputs, or unrelated module edits appear.

- [ ] **Step 4: update Type-A workflow evidence and commit the implementation checkpoint**

  Run the repository flow helper for the implementation-plan, implementation, and verification components only after fresh command evidence is read. Use `mutation-check` before receipt mutation, `check-result` for requirement/writer/review/compile/test/ABI/static/manual/Floci checks, `component-evidence` with the changed-path manifest, and `lane-complete` with the exact changed path list. The `verification` component must be PASS only when focused tests, ABI/classpath, manual contracts, static checks, and Floci status are each linked to fresh evidence; `delivery` remains PENDING until a separately authorized PR/merge stage. If the expired design lane blocks a receipt mutation, inspect `resume-check` and `receipt-diagnose`, recover through the helper's supported lane mechanism, and never edit receipt files manually.

  After Phase 2 converter, dependency, docs, ABI, and full verification are green, create the final local Lore commit with the message in the next section. Record both Phase 1 and Phase 2 SHAs so a converter-only rollback can return to the strategy checkpoint.

  Commit only after `git diff --check`, focused tests, module/static checks, ABI/classpath checks, manual contracts, and the independent plan/implementation review have passed. The commit message must be Korean Lore format:

  ```text
  SNS batch 전략과 Message 변환 경계를 구현해 안전한 확장 경로를 제공한다

  Constraint: 기존 SnsOperations fallback과 compileOnly 의존성 경계를 보존한다
  Rejected: 자동 Jackson/ByteArray와 wire-size preflight | 후속 이슈 범위로 분리
  Confidence: high
  Scope-risk: broad
  Directive: strategy에는 guarded port 외 AWS lifecycle을 노출하지 않는다
  Tested: focused SNS tests, module check, detekt, ABI/classpath, manual contracts
  Not-tested: 실제 AWS throughput과 byte-size preflight는 이번 범위가 아니다
  ```

  Expected final checkpoint: clean worktree except ignored build output, implementation and verification components marked PASS with fresh evidence, delivery marked PENDING, no PR/merge/release side effect, and DoD report states exactly what remains pending.

## Rollback and stop conditions

- If a new strategy test reveals a contract bypass, stop the converter work, keep the failing test, repair the guard/coordinator, and rerun the phase-one matrix before continuing.
- If constructor ABI or compileOnly classpath checks fail, stop documentation edits, restore the public descriptor/Gradle boundary, and rerun the ABI task; do not ship a synthetic overload or runtime dependency as a workaround.
- If any cancellation test leaves an active claim or SDK future, stop and repair `NonCancellable` release/sibling cleanup before adding performance claims.
- If a retained port can publish after `publishBatch` returns, stop and repair the `OPEN -> CLOSING -> CLOSED` guard lifecycle before proceeding; a post-return call must be `PORT_CLOSED` with zero SDK calls.
- If an invocation-wide duplicate claim, protocol mismatch, or generic strategy failure loses attempted/terminal state, stop and repair the bounded metadata and no-automatic-retry contract before documenting partial success.
- If manual contract or Korean terminology audit fails, repair both language pages together and rerun all document checks.
- If full verification cannot run because Docker/Floci is unavailable, mark only the affected emulator evidence `PENDING`, report the exact command and environment blocker, and do not claim the full module is green.
- Do not create PRs, merge, publish, tag, delete branches, or create follow-up GitHub issues in this implementation checkpoint.

## Plan self-review checklist

- [x] Every approved design section has a concrete task: strategy API, constructor ABI, guarded port, bounded coordinator, typed result validation, converter, headers, serializer, compileOnly dependency, docs, follow-up scope, and verification.
- [x] Every production-code task starts with a named failing test and a concrete Gradle selector.
- [x] Every step names its error class, field, bound, and expected result directly; there are no unfinished markers or unspecified handling branches.
- [x] Public signatures are consistent across tasks: `SnsBatchExecutionPort.publishChunk`, `SnsBatchExecutionStrategy.execute`, `SnsBatchMessageConverter.convert/convertAll`, and the 2-/3-argument template constructors.
- [x] Public KDoc, isolated consumer classloaders, generated POM/module metadata, release-pinned manual links, README/manual parity, and Floci capability evidence are explicit verification outputs.
- [x] Safety claims are bounded to fake/coordinator fixtures; no AWS latency or byte-size throughput claim is made.
- [x] Korean reader-facing documentation and Lore commit output remain separate from English agent-facing instruction files.
