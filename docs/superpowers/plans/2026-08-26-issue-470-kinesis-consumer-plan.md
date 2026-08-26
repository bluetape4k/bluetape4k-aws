# Kinesis Multi-Shard Consumer 구현 계획

> **구현자 안내:** 이 계획은 승인된 설계를 task 단위로 실행한다. 각 단계는 체크박스로 추적하며, Kotlin 코드는 `$bluetape-kotlin-patterns`와 TDD를 적용하고 실제 AWS 대신 Floci/fake 경계를 사용한다.

**목표:** `aws-java`와 `aws-kotlin`에 shard discovery·순차 polling·lease fencing·durable checkpoint·reshard ordering을 제공하는 공통 개념의 Kinesis `consumerFlow`를 추가한다.

**구조:** 두 SDK 모듈에 동일한 계약 타입을 각각 정의하고, Kotlin은 기존 cold `recordFlow` polling 패턴을 재사용하며 Java는 private async poller를 둔다. public Flow는 `KinesisShardKey`, `KinesisCheckpoint`, `KinesisLease`를 중심으로 하고, shard별 독립 heartbeat와 `buffer(0)` rendezvous로 at-least-once 경계를 지킨다. 영속 adapter는 이슈 범위 밖이며 in-memory/no-op 구현만 제공한다.

**기술 스택:** Kotlin coroutines/Flow, AWS SDK v2 Java Kinesis, AWS SDK for Kotlin Kinesis, JUnit 5, MockK, Kluent, Testcontainers `FlociServer` 1.6.0, Gradle. 새 dependency·BOM·version pin은 추가하지 않는다.

---

## Task 1: 구현 기준과 위험 예측 고정

**Files:**

- Create: `docs/superpowers/reviews/2026-08-26-issue-470-kinesis-consumer-plan-review.md`
- Create: `docs/superpowers/risk/2026-08-26-issue-470-kinesis-consumer-risk.md`
- Modify: `docs/superpowers/specs/2026-08-26-issue-470-kinesis-consumer-design.md` (승인 상태·Floci wrapper 사실 동기화)
- Modify: `.omx/issue-470/` workflow evidence only through `bluetape-flow.py`

- [ ] **Step 1: 승인된 설계와 구현 plan의 추적표 작성**

  `consumerGroup/streamIdentity`, `KinesisShardKey`, `Sequence/ShardEnd`, fenced save,
  heartbeat, graph timeout, `buffer(0)`, Floci gaps, Java/Kotlin parity 각각에 구현 파일과
  테스트 파일을 매핑한다. 설계의 `§8` acceptance row마다 최소 하나의 test command를
  적고, public signature·fixture RED/GREEN·manual contract·Floci selector를 별도
  evidence gate로 둔다. plan-review artifact에는 performance, stability, security,
  operator/Ops, developer/API, user/caller의 독립 판정과 main 통합 판정을 남긴다.

- [ ] **Step 2: 위험 예측 작성**

  다음 위험을 신호·완화·재실행 지점과 함께 기록한다.

  1. lease 만료 중 stale emit/save — virtual-time heartbeat/lease-loss test, fenced save 거부
  2. parent graph 누락/중복 — delayed full shard-list·unknown-parent unit test, full shard-list atomic apply
  3. Floci false-green — `ExplicitHashKey`와 `PutRecordResponse.shardId` 검증, pagination/LATEST는 fake
  4. Java/Kotlin parity drift — 동일 이름/인자/오류 fixture compile test
  5. bounded resource regression — `maxDiscoveredShards`, `maxListShardsPages`, `maxRecordsPerPoll` 검증
  6. cancellation cleanup — `NonCancellable` release timeout과 원래 예외 보존 test
  7. stale takeover race — lease/checkpoint/ShardEnd를 하나의 consistency domain으로
     구현하는 persistent adapter 책임과 takeover→new-owner-first-save→stale-save barrier
     test를 고정한다. 임의로 분리된 backend의 원자성을 런타임이 주장하지 않는다.
  8. Flow context invariant — shard child가 직접 `emit`하지 않고 rendezvous pending/ack와
     단일 outer emitter를 사용하며 collector `emit` 반환 전에 checkpoint가 앞서지 않는지
     검증한다.
  9. blocked collector/heartbeat race — virtual time lease loss 중 blocked collector가
     bounded termination하고 모든 lease가 release되는지 확인한다.
  10. discovery budget — retryable success, budget exhaustion, partial shard-list discard,
      `maxDiscoveredShards` cap을 각각 독립 검증한다.
  11. public compatibility — sealed metrics subtype 추가는 major-version source break임을
      KDoc/fixture에 기록하고 serialization round-trip·`readResolve`·invalid payload를
      두 모듈에서 같은 assertion으로 검증한다.
  12. polling quota — `emptyBackoff=1ms` 입력에서도 consumer effective delay가
      `max(emptyBackoff, MIN_POLL_INTERVAL=200ms)`가 되는 virtual-time cadence test를 둔다.
  13. metrics 운영 cardinality/실패 — event kind/outcome/reason/retryClass만 유한 label로
      허용하고 stream/shard/owner는 길이 제한 deterministic redacted token으로만 전달한다.
      고유 ID 다량 입력, callback throw/hang/cancellation에서 원인 보존·one-time lease release·
      consumer 종료를 검증한다.
  14. rollback checkpoint 호환성 — rollback 전에 consumer를 중지·drain하고 checkpoint를
      삭제/rewind하지 않는다. target binary의 기존 `Sequence`/`ShardEnd` read compatibility를
      cross-version fixture로 검증하고, 호환되지 않으면 controlled replay/migration 없이는
      rollback하지 않는 운영 정책을 고정한다.

- [ ] **Step 3: plan 자체 검증**

  계획 파일에 unresolved placeholder 표기가 없는지 확인하고, 아래 task의 타입/파일명이 일관되어야
  한다. public API는 승인된 spec의 split lease/checkpoint SPI를 유지하되, 영속 adapter가
  같은 consistency domain에서 조건부 commit을 구현한다는 경계를 명시한다. Flow 구현은
  단일 outer emitter 계약을 지킨다. “Java ABI fixture”는 Java SDK 모듈을 소비하는 Kotlin
  fixture라는 용어로 고정한다. 이 library는 health/readiness/liveness endpoint나 Actuator
  integration을 제공하지 않으며 probe와 graceful shutdown은 caller 책임이다. rollback은
  durable store의 기존 checkpoint를 target binary가 읽을 수 있는지 확인한 뒤에만 허용하고,
  비호환이면 controlled replay/migration 전까지 금지한다.

  Step 3-R 조건부 항목도 명시한다. streaming은 logical EOF, ending range가 있는 truncated
  final batch, terminal 이후 재사용/중복 terminal 호출을 unit/fake로 검증한다. suspend API는
  discovery·polling·backoff·store 호출의 cancellation 전파를 검증한다. client resource는
  호출자 생성·소유·close와 선행 close 오류 전파를 검증한다. 새 module/auto-configuration/
  Exposed/JDK preview surface는 범위 밖이라 N/A로 기록한다. 두 모듈의 계약 타입은 SDK
  model 차이 때문에 각각 유지하고, 공통 이름·인자·fixture·동일 assertion으로 parity를
  보장하는 extraction decision을 남긴다.

- [ ] **Step 4: 커밋**

  Run:

  ```bash
  git add docs/superpowers/specs/2026-08-26-issue-470-kinesis-consumer-design.md docs/superpowers/plans docs/superpowers/reviews/2026-08-26-issue-470-kinesis-consumer-plan-review.md docs/superpowers/risk
  git commit -F - <<'EOF'
  #470 구현 순서와 동시성 위험을 실행 전에 고정한다

  Constraint: 승인된 split SPI와 Floci-first/no-real-AWS 경계를 유지한다.
  Rejected: public 통합 state SPI 추가 | 설계 승인 범위를 넓히고 adapter 책임을 침범한다.
  Confidence: high
  Scope-risk: broad
  Directive: consistency-domain fencing과 단일 outer emitter를 구현에서 약화하지 않는다.
  Tested: git diff --check, plan/spec/review/risk read-back, Korean terminology audit findings=0
  Not-tested: Kotlin/Java compile, Floci, 실제 AWS
  EOF
  ```

  Expected: Lore trailers 포함 커밋, 코드 파일 변경 없음.

## Task 2: Kotlin 계약 타입과 store 구현 (TDD)

**Files:**

- Modify: `aws-java/src/consumerFixture/kotlin/io/bluetape4k/aws/consumer/JavaServiceConsumerFixture.kt`
- Modify: `aws-kotlin/src/consumerFixture/kotlin/io/bluetape4k/aws/kotlin/consumer/KotlinServiceConsumerFixture.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisShardKey.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisCheckpoint.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisLease.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisCheckpointStore.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisLeaseStore.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/InMemoryKinesisCheckpointStore.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/InMemoryKinesisLeaseStore.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/NoopKinesisCheckpointStore.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/NoopKinesisLeaseStore.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisConsumerOptions.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisShardRecord.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisFlowMetrics.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisConsumerExceptions.kt`
- Test: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisConsumerStateUnitTest.kt`
- Test: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/FencedKinesisStateStoreTestDouble.kt`

- [ ] **Step 0: public consumer fixture RED 고정**

  두 외부 consumer fixture에 `consumerGroup`, stable `streamIdentity`, `ownerId`,
  `KinesisConsumerOptions`, in-memory stores, `take(n)`/cancellation 호출을 추가한다. Java
  fixture는 `KinesisAsyncClient.consumerFlow`, Kotlin fixture는 `KinesisClient.consumerFlow`의
  실제 public signature를 사용한다. 구현 전에는 새 타입이 없어 실패해야 한다.

  Run: `./gradlew --no-parallel :bluetape4k-aws-java:jar :bluetape4k-aws-kotlin:jar \
  compileAwsJavaServiceConsumerFixture compileAwsKotlinServiceConsumerFixture \
  verifyAwsConsumerFixturePublication`

  Expected: 새 API가 아직 없으므로 fixture compile이 RED; 이 RED 결과가 구현 후 같은
  명령으로 GREEN이 되는 ABI 증거가 된다. fixture 파일은 이후 문서/fixture task에서
  재수정하지 않고 이 단계의 public compile contract를 유지한다.

- [ ] **Step 1: failing value-object/store tests 작성**

  다음을 `runTest`로 먼저 고정한다.

  ```kotlin
  @Test
  fun `stale lease counter cannot overwrite checkpoint`() = runTest {
      val key = KinesisShardKey("stream-v1", "orders", "shard-0")
      val store = InMemoryKinesisCheckpointStore()
      store.save(key, KinesisCheckpoint.Sequence("20"), lease(key, "new", 2))
      assertFailsWith<KinesisLeaseLostException> {
          store.save(key, KinesisCheckpoint.Sequence("30"), lease(key, "old", 1))
      }
      store.load(key) shouldBe KinesisCheckpoint.Sequence("20")
  }
  ```

  값 객체의 blank/control-character/길이/negative counter, `ShardEnd`, 다른 owner의
  유효 lease `acquire == null`, 만료 takeover counter 증가, owner+counter CAS renew/release,
  같은 lease에서 numeric sequence가 역행하지 않는지, `ShardEnd` 이후 `Sequence` 저장이
  거부되는지, release 후 stale owner release가 새 owner lease를 삭제하지 않는지,
  payload·credential·request token·raw exception·endpoint를 포함하지 않는 metrics event,
  opaque/malformed/oversized record가 deserialize·execute·log되지 않는지를 포함한다. key,
  checkpoint, lease, options, starting position의 serialization round-trip과 singleton
  identity/readResolve, invalid deserialization 재검증도 포함한다. takeover → 새 owner의
  첫 save 전 stale save 경쟁은 두 store를 하나의 consistency domain으로 묶은 test double로
  검증하고, 분리 backend를 원자적이라고 가장하지 않는다. event label은
  `eventKind/outcome/reason/retryClass` 같은 유한 값만 허용하며 stream/shard/owner는 길이
  제한 deterministic redacted token으로만 전달한다. 고유 ID 다량 입력으로 cardinality가
  bounded인지, metrics callback의 throw/hang/cancellation이 원래 cause를 보존하고 lease
  release를 정확히 한 번 수행한 뒤 consumer를 종료하는지 확인한다.

- [ ] **Step 2: 테스트가 RED인지 확인**

  Run: `./gradlew :bluetape4k-aws-kotlin:test --tests '*KinesisConsumerStateUnitTest'`

  Expected: 새 타입이 없어 컴파일 또는 테스트 실패.

- [ ] **Step 3: 최소 계약 구현**

  `KinesisShardKey`가 tuple canonicalization과 검증을 담당하고, `KinesisLease`는 key를
  포함한다. canonical key는 delimiter를 raw concatenate하지 않고 length-prefixed tuple로
  만들어 delimiter collision을 막는다. `streamName`(최대 128), `streamIdentity`,
  `consumerGroup`, `shardId`, `ownerId`, sequence number는 blank/control character/최대
  길이를 양 모듈에서 동일하게 검증한다. 형식 검증은 생성자 책임이고 live worker마다
  `ownerId`가 전역적으로 유일한지는 호출자와 배포 환경의 책임이며 API가 확인할 수 없다.
  `InMemory*Store`는 `Mutex`/thread-safe map으로 CAS를 원자화한다. Noop은
  단일 프로세스 제한을 KDoc에 명시한다. 옵션은 `ownerId`, duration 순서, page/shard/record
  hard cap을 `require`한다. 구현 기본값은 `maxShardConcurrency=4`, `discoveryInterval=5s`,
  `leaseDuration=60s`, `leaseRenewInterval=20s`, `maxListShardsPages=100`,
  `maxDiscoveryRetries=3`, `maxUnknownParentDiscoveries=3`, `maxDiscoveredShards=10_000`,
  `maxRecordsPerPoll=100`, `leaseReleaseTimeout=5s`로 고정하고 단위를 KDoc/manual에
  기록한다. `maxRecordsPerPoll`은 기존 `recordOptions.batchLimit`보다 작거나 같은
  경우에만 추가 AWS record를 허용하며, 더 큰 batch는 이 상한에서 잘라낸다.
  Java `KinesisRecordFlowOptions`도 Kotlin의 `batchLimit`, `pollInterval`, `emptyBackoff`,
  iterator/throttle retry 필드와 동일한 기본값·invariant를 제공한다. `pollInterval`은
  `200ms` 이상, 기존 `recordFlow`의 `emptyBackoff` 검증은 보존하되 `consumerFlow`의
  effective empty delay는 `max(emptyBackoff, MIN_POLL_INTERVAL=200ms)`로 clamp한다.
  `emptyBackoff=1ms` 입력에서도 실제 `GetRecords` 호출 간격이 200ms 이상이어야 한다.
  non-empty/empty 응답 모두 shard별 cadence를 virtual time으로 검증한다. `T` 동안 discovery/page/renew/acquire 호출 상한을 식으로
  기록하고, 같은 `nextToken` 또는 진전 없는 page는 즉시 bounded failure로 처리한다.
  metrics는 payload 없는 `sealed interface KinesisFlowEvent`와 `suspend fun onEvent`를
  제공한다. sealed subtype 추가는 exhaustive `when` 소비자에게 source-breaking이므로
  다음 major version에서만 허용하며 양 모듈 fixture가 이를 검증한다. event가 허용하는
  값은 bounded opaque/redacted stream·shard·owner ID, event kind, count, duration, retry
  count뿐이다. raw record/data, credential, request token, endpoint, raw exception message,
  full ARN/account identifier는 event와 로그에 넣지 않으며 sentinel redaction test로
  확인한다. 영속 adapter는
  lease/checkpoint/ShardEnd를 같은 consistency domain에서 조건부 commit해야 하며,
  분리된 임의 backend 조합의 원자성을 런타임이 대신 보장한다고 주장하지 않는다.

- [ ] **Step 4: 계약 테스트 GREEN 확인**

  Run: `./gradlew :bluetape4k-aws-kotlin:test --tests '*KinesisConsumerStateUnitTest'`

  Expected: PASS; sequence/ShardEnd/fencing/lease lifecycle 증거를 기록한다.

- [ ] **Step 5: Kotlin checkpoint commit**

  Run: Kotlin 계약 파일·state test만 stage한 뒤 Lore trailers를 포함한
  `#470 Kotlin consumer 계약을 검증 가능하게 고정` intent commit을 만든다.

  Expected: commit body에 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`,
  `Tested`, `Not-tested`가 있고 Java/docs 파일은 포함하지 않는다. 실패 시 이 commit을
  rollback target으로 사용하고 state/flow fake test부터 재실행한다.

## Task 3: Kotlin shard graph와 consumer Flow (TDD)

**Files:**

- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisShardGraph.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisConsumerFlow.kt`
- Test: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisConsumerFlowUnitTest.kt`
- Test: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisConsumerFlociTest.kt`

- [ ] **Step 1: fake client 기반 RED 테스트 작성**

  `ListShards` token 누적/limit/token expiry restart, retryable discovery 오류·retry budget
  소진·partial shard-list 폐기·`maxDiscoveredShards` 상한, delayed parent, adjacent parent 양쪽
  `ShardEnd`, duplicate launch, `ShardEnd` skip, checkpoint inclusive resume, local iterator
  expiry, unknown-parent timeout, logical EOF와 truncated final batch, terminal 이후 재사용과
  double-terminal call, `buffer(0)`, 느린 collector와 heartbeat loss의 교차 취소,
  post-loss emit/save fencing, in-flight duplicate 허용과 stale checkpoint 거부,
  collector exception 전체 취소를 `runTest`와 virtual time으로 고정한다. 느린 collector에서
  `GetRecords` 요청 `limit <= maxRecordsPerPoll`, 호출 수와 shard/concurrency/page/record/
  pending-envelope 상한을 call-count로 검증한다. `N` records에 `N` save와 metrics callback이
  발생하고 slow save/metrics가 다음 poll보다 앞서지 않는지도 확인한다. throughput/allocation
  benchmark는 `aws-kotlin`에 Kinesis-specific benchmark source가 없어 N/A로 기록하고,
  structural in-flight/contention counter를 대체 증거로 남긴다. event label은
  `eventKind/outcome/reason/retryClass` 유한 집합만 사용하고 고유 stream/shard/owner ID를
  deterministic redacted token으로 제한한다. 고유 ID 다량 입력의 bounded cardinality와
  callback throw/hang/cancellation의 원인 보존·one-time release·consumer 종료를 검증한다.

- [ ] **Step 2: RED 실행**

  Run: `./gradlew :bluetape4k-aws-kotlin:test --tests '*KinesisConsumerFlowUnitTest'`

  Expected: consumer/graph symbols가 없어 실패.

- [ ] **Step 3: discovery와 graph 최소 구현**

  완전한 샤드 목록만 atomic apply하고, non-null parent 누락은 root로 승격하지 않는다.
  `maxUnknownParentDiscoveries` 초과 시 `KinesisShardGraphException`을 던진다. child는
  checkpoint store의 durable `ShardEnd`를 모두 확인한 뒤에만 시작한다.

- [ ] **Step 4: shard job 구현**

  `coroutineScope` 안에서 shard별 `launch`와 독립 heartbeat를 사용한다. `GetRecords`의
  shard job은 일반 `flow {}`에서 직접 `emit`하지 않는다. 내부 rendezvous
  `Channel<PendingRecord>`와 단일 outer emitter를 사용해 outer `emit`이 반환된 뒤에만
  shard job이 ack를 받아 fenced `save`한다. 이 구조로 Flow context invariant와
  checkpoint 선행 저장을 동시에 막는다. 각 record는 collector `emit` 직전에 lease를
  재검증하고, lease loss를 관측한 뒤에는 새 `emit`/`save`를 시작하지 않는다. 검증 직후
  takeover되는 TOCTOU 구간에서 이미 시작된 in-flight duplicate `emit`은 at-least-once
  경계상 허용될 수 있으며, fenced `save`는 거부되고 새 owner가 inclusive replay한다.
  barrier test는 zero-stale-delivery를 주장하지 않고 `emit 가능 + stale checkpoint 거부 +
  새 owner replay`를 검증한다. heartbeat loss는 job/consumer를 취소한다.
  `endingSequenceNumber`와 마지막 sequence 또는 null
  iterator로 `ShardEnd`를 저장한다. `buffer(0)`과 `maxRecordsPerPoll`을 사용하고
  empty response delay는 `max(recordOptions.emptyBackoff, MIN_POLL_INTERVAL)`로 clamp하며,
  기존 single-shard `recordFlow`의 public validation/semantics는 변경하지 않는다.
  `NonCancellable` bounded release를 finally에 둔다. 모든 suspend store SPI 호출은
  cancellation-cooperative 계약으로 두고, blocking fake가 취소·bounded 종료를 방해하지
  않는지 검증한다.

  정상 cancellation, collector/poison 예외, lease loss, checkpoint save 실패 각각에서
  `NonCancellable` release가 한 번 수행되고 원래 cause가 보존되는지 확인한다. retry/backoff
  중 cancellation과 정상 `ShardEnd` 후 heartbeat job 종료도 virtual time으로 고정하며,
  client를 consumer가 닫지 않고 client 선행 close의 SDK 오류를 호출자에게 전달하는지
  검증한다.

- [ ] **Step 5: unit GREEN 확인**

  Run: `./gradlew :bluetape4k-aws-kotlin:test --tests '*KinesisConsumerFlowUnitTest'`

  Expected: 모든 fake/virtual-time contract PASS.

- [ ] **Step 6: Kotlin runtime checkpoint commit**

  Run: Kotlin graph/consumer와 Kotlin unit/Floci test만 stage한 뒤 Lore trailers를 포함한
  `#470 Kotlin multi-shard runtime을 shard ordering 계약에 맞춰 고정` intent commit을 만든다.

  Expected: commit에 Kotlin runtime 파일과 해당 test만 포함되고 `Constraint`, `Rejected`,
  `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested`가 있다. Floci 실패 시
  unit/fake 증거를 보존한 채 이 commit을 rollback target으로 삼는다.

- [ ] **Step 7: Floci RED→GREEN 통합 경계**

  Floci test source는 `FlociServer.Launcher.floci`의 dynamic `awsEndpoint`와
  `StaticCredentialsProvider`(emulator access/secret key)를 client builder에 직접 주입하고,
  default AWS credential chain 또는 기본 AWS endpoint를 사용하지 않는 guard를 둔다. 고유 stream에 `ExplicitHashKey`를 넣고
  `PutRecordResponse.shardId`가 두 shard인지 확인한다. pinned image가 제공하지 않는
  pagination/closed iterator/LATEST는 test에서 요구하지 않고 unit gap으로 남긴다.
  실제 Floci 실행은 Task 4의 Java 통합 다음에 Java → Kotlin 순서로 한 번에 수행한다.
  이 단계에서는 Kotlin Floci fixture가 해당 순차 gate에 포함되는지와 기존
  `@Execution(SAME_THREAD)`/selector 경계를 확인한다.

## Task 4: Java 계약 타입과 async consumer 구현 (TDD)

**Files:**

- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/KinesisStartingPosition.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/KinesisRecordFlowOptions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/KinesisShardKey.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/KinesisCheckpoint.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/KinesisLease.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/KinesisCheckpointStore.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/KinesisLeaseStore.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/InMemoryKinesisCheckpointStore.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/InMemoryKinesisLeaseStore.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/NoopKinesisCheckpointStore.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/NoopKinesisLeaseStore.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/KinesisConsumerOptions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/KinesisShardRecord.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/KinesisFlowMetrics.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/KinesisConsumerExceptions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/KinesisShardGraph.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/KinesisAsyncConsumerFlow.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/kinesis/KinesisConsumerStateUnitTest.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/kinesis/KinesisStartingPositionTest.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/kinesis/KinesisRecordFlowOptionsTest.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/kinesis/KinesisConsumerFlowUnitTest.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/kinesis/KinesisConsumerFlociTest.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/kinesis/FencedKinesisStateStoreTestDouble.kt`

- [ ] **Step 1: Java contract RED 테스트 작성**

  Kotlin 모듈과 같은 state/lease/checkpoint assertions를 Java SDK model에 맞춰 작성하고,
  Java starting position/options의 serialization round-trip·invalid deserialization·singleton
  identity를 검증하며,
  “Java ABI fixture”는 Java SDK 모듈을 소비하는 Kotlin consumer fixture를 뜻하며 실제
  Java source ABI fixture를 범위에 추가하지 않는다. `KinesisAsyncClient.consumerFlow`의
  인자·반환 타입 compile fixture를 추가한다. retry 중
  cancellation, blocked collector와 heartbeat loss race, post-loss fencing, collector/poison
  failure, release timeout/cause 보존, client lifecycle도 Kotlin과 같은 fixture로 고정한다.
  metrics label cardinality, callback throw/hang/cancellation 및 callback 종료 후 one-time
  release도 Kotlin과 동일한 assertion으로 고정한다.

- [ ] **Step 2: Java 계약 구현 및 GREEN**

  Java SDK의 `ListShardsRequest/Response`, `GetShardIteratorRequest`, `GetRecordsRequest`
  를 private async poller에서 사용하고 기존 `await()` coroutine helper를 재사용한다.
  Java도 shard job의 직접 `emit`을 금지하고 내부 rendezvous pending-record/ack와 단일
  outer emitter를 사용해 Kotlin과 동일한 collector-return 후 checkpoint 계약을 보장한다.
  public primitive wrapper signature는 변경하지 않는다. Java poller는 Kotlin과 동일하게
  non-empty 응답 사이 `pollInterval >= 200ms`, empty 응답의 effective delay
  `max(emptyBackoff, 200ms)`, iterator/
  throttle retry budget을 적용한다. fake client는 request `limit <= maxRecordsPerPoll`,
  `N records -> N fenced save/metrics callback`, active shard별 renew cadence와 slow
  save/metrics backpressure를 계수한다. metrics label은 유한 집합과 deterministic redacted
  token만 허용하며 고유 ID cardinality·callback throw/hang/cancellation의 원인 보존과
  one-time release를 검증한다.

  Run: `./gradlew :bluetape4k-aws-java:test --tests '*KinesisConsumerStateUnitTest' --tests '*KinesisStartingPositionTest' --tests '*KinesisRecordFlowOptionsTest' --tests '*KinesisConsumerFlowUnitTest'`

  Expected: state, graph, fencing, heartbeat, cancellation, collector/poison failure,
  release timeout 보존, client lifecycle, parity fixture PASS.

- [ ] **Step 3: Java runtime checkpoint commit**

  Run: Java contract/runtime와 Java unit test만 stage한 뒤 Lore trailers를 포함한
  `#470 Java async consumer runtime을 Kotlin parity로 고정` intent commit을 만든다.

  Expected: Java runtime 파일과 해당 test만 포함되고 일곱 Lore field가 있다. Java unit이
  통과하기 전에는 Floci gate로 진행하지 않는다.

- [ ] **Step 4: Java Floci 통합**

  Run: `./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 --no-daemon --console=plain :bluetape4k-aws-java:test --tests '*KinesisConsumerFlociTest'`

  Expected: Java Floci single-page multi-shard contract PASS; fixture가 `awsEndpoint`와
  static emulator credentials를 사용하므로 실제 AWS credential chain으로 탈출하지 않는다.
  AWS-only/Floci gap은 기록한다.

- [ ] **Step 5: Java → Kotlin 순차 Floci gate**

  Java 통합이 통과한 뒤 같은 실행 흐름에서 Kotlin 통합을 실행한다. 두 모듈은 공유
  Docker 자원을 병렬로 사용하지 않으며, 각 test class는 `@Execution(SAME_THREAD)`와
  고유 stream cleanup을 유지한다.

  Run: `./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 --no-daemon --console=plain :bluetape4k-aws-kotlin:test --tests '*KinesisConsumerFlociTest'`

  Expected: Kotlin Floci single-page multi-shard contract PASS; fixture가 dynamic Floci
  endpoint와 static emulator credentials를 사용한다. Java 선행 결과와 selector/image/실행
  순서를 함께 기록한다. Kotlin runtime checkpoint는 Task 3 Step 6에서
  이미 고정되어 있어 이 gate는 shared emulator evidence만 추가한다.

## Task 5: 모듈 parity·회귀·문서 구현

**Files:**

- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `docs/manual/en/modules/bluetape4k-aws-java.md`
- Modify: `docs/manual/ko/modules/bluetape4k-aws-java.md`
- Modify: `docs/manual/en/modules/bluetape4k-aws-kotlin.md`
- Modify: `docs/manual/ko/modules/bluetape4k-aws-kotlin.md`
- Test: existing `KinesisRecordFlow*Test` files (no signature change)

- [ ] **Step 1: module-specific migration and fixture GREEN contract 고정**

  Kotlin은 기존 `KinesisClient.recordFlow(streamName, shardId, ...)`를 그대로 유지하고,
  새 `consumerFlow`를 dynamic multi-shard 용도로 추가한다. Java 모듈에는 현재 public
  `recordFlow`가 없으므로 `KinesisAsyncClient.consumerFlow`가 첫 multi-shard Flow API이며,
  Java manual 예제는 Java SDK v2 client를 Kotlin coroutine extension으로 소비하는 source임을
  제목에 명시한다. 구현 뒤 다음 명령이 같은 public signature를 GREEN으로 증명해야 한다.

  ```bash
  ./gradlew --no-parallel :bluetape4k-aws-java:jar :bluetape4k-aws-kotlin:jar \
    compileAwsJavaServiceConsumerFixture compileAwsKotlinServiceConsumerFixture \
    verifyAwsConsumerFixturePublication
  ```

  예제와 fixture에는 `consumerGroup`, stable `streamIdentity`, worker별 고유 `ownerId`,
  명시적 checkpoint/lease store, `take(n)` 또는 scope cancellation, 호출자 소유 client의
  close를 포함한다. live consumer에 `toList()`를 사용하지 않는다. durable store를 주입한
  경우에만 restart at-least-once가 성립하며, Noop 조합은 process-local delivery와 명시적
  단일 프로세스 제한만 제공한다.

  migration runbook은 `recordFlow`/Noop에서 durable `consumerFlow`로 전환할 때
  **stop → drain → canary → scale** 순서를 사용한다. `streamIdentity`는 안정적으로 유지하고
  동시에 실행하는 worker마다 `ownerId`를 재사용하지 않으며, durable store 전환 시 inclusive
  replay가 발생할 수 있음을 고정한다. release timeout 뒤에는 lease가 즉시 해제됐다고
  가정하지 않고 lease expiry를 기다린 뒤 재기동한다.

- [ ] **Step 2: 문서 구현**

  README/manual은 `compileOnly` Kinesis service SDK 추가를 안내하고, 문서의 첫 의미론
  문장을 “durable checkpoint/lease store를 사용할 때 restart at-least-once를 제공한다”로
  고정한다. 이어 Noop 단일 프로세스 제한, inclusive duplicate, lease/checkpoint ownership,
  Floci wrapper의 pinned 1.6.0 동작, `recordFlow`→`consumerFlow` migration, client lifecycle을
  English/Korean 구조로 맞춘다. 현재 `FlociServer.Launcher.floci` wrapper는 모든 서비스를
  활성화하므로 기본 test에는 `FLOCI_SERVICES_KINESIS_ENABLED=true` selector export가
  필요하지 않다고 적고, 외부 image를 직접 실행하는 경우에만 해당 image 문서를 따르도록
  조건부로 남긴다.
  LocalStack은 `-Dbluetape4k.aws.emulator=localstack`을 선택하는 명시적 fallback으로만
  기록하며 이 작업에서는 실행하지 않는다. Spring Boot 모듈에는 Kinesis consumer runtime이
  아직 없다는 사실과 low-level `aws-java`/`aws-kotlin` `consumerFlow` 연결을 함께 적는다.
  public key/checkpoint/lease/store/options/event/envelope/exception과 양쪽
  `consumerFlow` KDoc에는 ownership, threading, cancellation, fencing, at-least-once와
  Noop 제한을 각각 명시하고, sealed metrics subtype 추가는 major-version source break임을
  경고한다. record payload는 신뢰하지 않는 opaque data이며 library가 deserialize·execute·log하지
  않고, poison isolation/DLQ는 caller adapter 책임임을 KDoc/manual/example에 적는다.
  `consumerGroup`은 namespace일 뿐 authorization boundary가 아니며 stream 접근 제어는
  caller IAM/credential policy 책임임을 명시한다. metrics callback은 payload를 받지 않고
  blocking 작업은 caller가 별도 queue/dispatcher에서 처리해야 한다. metrics label은
  `eventKind/outcome/reason/retryClass` 유한 값과 길이 제한 deterministic redacted token만
  허용하며, 고유 ID cardinality·callback throw/hang/cancellation에서 원인 보존, one-time
  lease release, consumer 종료를 문서와 test로 연결한다. 이 단계에서 “Java ABI fixture”가
  Java SDK module용 Kotlin consumer compile fixture라는 용어도 고정한다. 이 library에는
  health/readiness/liveness endpoint나 Actuator integration이 없고 probe·graceful shutdown은
  caller 책임이다. rollback은 consumer 중지·drain 후 target binary의 기존
  `Sequence`/`ShardEnd` read compatibility를 확인하고, 비호환이면 controlled replay/migration
  없이는 금지한다.

  Manual은 현재 `releaseRef`와 release source link를 변경하지 않고 `Unreleased/develop`
  절에만 새 API를 추가한다. 다음 검증을 실행한다.

  ```bash
  ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
  ruby scripts/manual/manual_contract_test.rb
  ```

- [ ] **Step 3: 회귀 검증**

  Run: `./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 --no-daemon --console=plain :bluetape4k-aws-java:test --tests '*Kinesis*'` then
  `./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 --no-daemon --console=plain :bluetape4k-aws-kotlin:test --tests '*Kinesis*'`

  Expected: 기존 single-shard `recordFlow`와 새 consumer parity PASS; emulator 자원은 순차 사용.

- [ ] **Step 4: docs/fixture checkpoint commit**

  Run: README/manual, fixture GREEN 결과, 기존 Kinesis regression test와 docs validation만
  stage한 뒤 Lore trailers를 포함한 `#470 consumer 사용법과 ABI 경계를 문서화` intent commit을
  만든다.

  Expected: docs/fixture 파일과 해당 증거만 포함되며 일곱 Lore field가 있다. `releaseRef`
  와 release source link는 변경하지 않는다.

## Task 6: 검증·정리·lesson

**Files:**

- Create: `docs/lessons/2026-08-26-kinesis-consumer.md`
- Modify: `CHANGELOG.md` `[미출시]`의 `추가` 절에 #470 항목 추가
- No new dependency or version pin

- [ ] **Step 1: targeted compile/test**

  Run in order (Floci/Testcontainers 명령은 Java → Kotlin 순서를 유지):

  ```bash
  ./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 --no-daemon --console=plain :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-java:test
  ./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 --no-daemon --console=plain :bluetape4k-aws-kotlin:compileKotlin :bluetape4k-aws-kotlin:test
  ```

  Expected: both modules compile and unit/Floci tests pass without real AWS credentials.

- [ ] **Step 2: static and broader validation**

  Run: `./gradlew detekt`; then `./gradlew build -x test --parallel`; then
  `git diff --check`.

  Expected: no new detekt/build/diff errors. If a container test is skipped or path-filtered,
  record it as a gap rather than treating it as runtime proof.

- [ ] **Step 3: acceptance traceability read-back**

  Verify each §8 row against a fresh test output, public KDoc/manual, and Floci/fake boundary.
  Confirm no AWS credentials, KCL dependency, Spring Binder, or unrelated module edits.
  Performance benchmark는 공개 throughput을 주장하지 않는 streaming API이며
  `aws-java`/`aws-kotlin`에 Kinesis-specific benchmark source가 없다는 범위 근거를 함께
  기록한다. 이 모듈과 무관한 benchmark harness는 재사용하지 않고 bounded call-count/
  virtual-time와 structural in-flight/contention counter를 대체 검증으로 연결한다.

- [ ] **Step 4: lesson 작성과 커밋**

  Record context, chosen fencing/heartbeat/graph decisions, Floci 1.6.0 gaps, test evidence,
  missed assumptions, and a future guard in Korean. Commit with Lore trailers before PR creation.

- [ ] **Step 5: final review handoff**

  Run final six-perspective code review, resolve P0/P1, run `verification-before-completion`,
  update workflow checks, and stop at the PR/merge approval gate. Merge is not part of this plan
  until the user gives fresh exact-head approval.

## Rollback and rerun points

- Kotlin/Java implementations are disjoint by module. A failing module can be reverted to its
  explicit Kotlin contract/runtime or Java runtime checkpoint commit without touching the other
  SDK or the approved design. Fixture/docs have their own checkpoint commit after both modules
  pass their public compile contract.
- Store/graph behavior must be repaired with fake unit tests before rerunning Floci; do not widen
  emulator assumptions to real AWS.
- Durable checkpoint rollback is an operationally gated action: stop consumers, drain in-flight
  records, and never delete or rewind stored checkpoints as a rollback shortcut. A cross-version
  fixture writes `Sequence` and `ShardEnd` with the newer binary and reads them with the target
  rollback binary. If the target cannot read the existing state, rollback is forbidden until a
  controlled replay or explicit migration procedure is available; lease expiry after a release
  timeout must be awaited before restart.
- Any public signature change requires reopening the approved design and repeating affected
  design/API review before implementation continues.
- No dependency, BOM, version, or release mutation is planned. A dependency gap stops the task
  for explicit review rather than adding an unapproved coordinate.

## Self-review result

- **Spec coverage:** all §3–§8 contracts map to Tasks 2–6; existing `recordFlow` regression is
  explicitly retained in Task 5.
- **Review closure:** plan-review/risk artifacts must include six independent perspectives plus
  main integration, with P0/P1 both zero after repair. SPW-01..05 and Korean naturalness KO-01..07
  are mandatory before implementation commit.
- **Placeholder scan:** unresolved placeholder 없음; SDK-specific envelope는 승인된 spec에서
  개념 스케치로 표시했으며 실제 모듈 타입은 Task 3–4에 명시했다.
- **Type consistency:** `KinesisShardKey`, `KinesisCheckpoint`, `KinesisLease`, stores, options,
  metrics, exceptions, and `consumerFlow` are the same concepts in both modules; Java uses a
  private async poller while Kotlin reuses existing polling semantics.
