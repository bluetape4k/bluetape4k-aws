# SQS template 비동기 자동 배치 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** #461에 opt-in SQS send/delete 자동 배치, entry별 부분 결과, bounded
concurrency, 취소 전파와 안전한 Spring lifecycle을 추가하면서 기존
`SqsOperations`, `SqsProperties`, listener와 표준 `SqsAsyncClient` 경로를 보존한다.

**Architecture:** 기존 outbound/listener API와 분리된 `SqsBatchOperations`와
`SqsBatchCoroutinesTemplate`을 둔다. template은 direct mode에서 표준 client를
bounded하게 호출하고, batch mode에서만 전용 `SqsAsyncBatchManager`와 scheduler를
소유한다. 입력·결과 정규화, admission placeholder registry, monotonic close deadline,
redacted failure model과 Micrometer decorator를 내부 경계로 분리한다.

**Tech Stack:** Kotlin 2.x, AWS SDK for Java v2 SQS 2.51.3 resolved line,
Spring Boot 4, kotlinx-coroutines 1.10.x, Micrometer, JUnit 5, MockK,
bluetape4k-assertions, Gradle, Floci 선택 검증.

---

## 불변식·범위

- `SqsBatchOperations`는 `SqsOperations`를 상속하거나 변경하지 않는다. listener는
  계속 기존 `SqsOperations`만 사용한다.
- public input은 첫 suspension 전에 iterator에서 최대 `maxEntriesPerCall + 1`개까지만
  bounded snapshot하고, 초과 입력은 나머지를 materialize하지 않은 채 거부한다. entry
  ID는 nonblank·distinct이며 설정은 `+ 1` overflow가 불가능해야 한다.
- `sendMany`는 `RETURN` 또는 `THROW`만 제공한다. `deleteMany`는 entry 실패를 항상
  result로 반환한다. 라이브러리 retry·rollback·선택 재전송은 없다.
- direct/batch mode는 같은 public result와 failure normalization을 사용한다.
  batch mode의 `SqsAsyncBatchManager` class가 없으면 disabled mode는 계속 시작되고,
  enabled mode만 redacted startup failure로 실패한다.
- active transport future와 accepted placeholder는 전역 `maxInFlightEntries`를
  넘지 않는다. permit을 얻은 child만 짧은 lifecycle `ReentrantLock` 임계 구역에서
  `OPEN` 확인·placeholder 등록을 하고, lock→permit 역순은 금지한다. 두 경계를 함께
  가진 동안 suspension, completion signal, 외부 submit/await/cancel/close를 하지 않는다.
- caller cancellation은 처음 포착한 원래 `CancellationException` identity를
  유지하고 incomplete future에 `cancel(false)`를 정확히 한 번 요청한다. caller가
  active인데 SDK future가 cancellation으로 완료되면 entry `TRANSPORT` failure다.
- close는 `OPEN -> CLOSING -> CLOSED`, 신규 admission 차단, accepted drain,
  manager close, scheduler cleanup을 하나의 monotonic 전체 deadline 안에서 수행한다.
  concurrent/repeated caller는 동일 close completion과 동일 exception identity를 본다.
- public result에는 correlation entry ID를 유지하지만 `toString()`, exception message,
  log, metric에는 body, queue URL, receipt handle, entry ID, attributes, raw cause를 넣지
  않는다. transport failure의 `code`는 항상 null이다.
- 같은 호출 안의 동일 FIFO group도 전송 순서를 보장하지 않는다. 엄격한 순서가
  필요하면 `SqsCoroutinesTemplate.send(request)` 순차 호출이나 raw
  `SqsAsyncClient.sendMessageBatch`를 사용한다.
- 공개 `BatchExecutionStrategy`, outbound converter SPI, retry policy abstraction,
  새 dependency는 추가하지 않는다.
- 테스트 데이터는 `Base58.randomString(16)`을 사용한다. 비교·크기·범위·문자열은
  각각 `shouldBeEqualTo`, `shouldHaveSize`, `shouldBeLessOrEqualTo` /
  `shouldBeGreaterOrEqualTo`, `shouldContain` / `shouldNotContain`을 사용하고 해당
  직접 matcher가 있는 boolean 우회 단언은 금지한다.

## 사전 게이트

- [x] 승인된 설계와 Step 2-R P0=0/P1=0을 확인했다.
- [x] 기존 `SqsOperationsBatchTest`와 `SqsAutoConfigurationTest` 28개 baseline이
      `BUILD SUCCESSFUL`임을 격리 worktree에서 확인했다.
- [x] 여섯 독립 plan review에서 P0=0/P1=0을 확인한다.
- [ ] 본 계획·plan review를 Lore commit으로 보존하고 사용자 구현 계획 승인을
      받은 뒤에만 production/test code를 변경한다.
- [ ] 구현 시작 직전에 `origin/develop`과 승인 commit의 drift를 재확인한다. build,
      dependency, SQS source가 변했으면 plan review를 다시 연다.

## Task 0: resolved SDK와 변경 전 ABI 기준선 보존

**Files:**

- Modify: `build.gradle.kts`
- Create: `aws-spring-boot/src/consumerFixture/kotlin/io/bluetape4k/aws/spring/sqs/consumer/LegacySqsPropertiesFixture.kt`
- Create after baseline compile:
  `aws-spring-boot/src/test/resources/sqs-properties-abi/io/bluetape4k/aws/spring/sqs/consumer/LegacySqsPropertiesFixture.class`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsPropertiesBinaryCompatibilityTest.kt`

### Step 0.1: SDK evidence를 구현 전 고정

- [ ] 다음 결과를 `.lane-evidence/issue-461-sdk-dependency.txt`에 먼저 저장하고,
      임의의 최신 Gradle cache 파일이 아니라 resolved version directory의 유일한 jar를
      검사한다.
- [ ] fixture provenance에는 승인 기준 `bae9344a502eff9f1fb65188fd08a704823bc147`,
      baseline parent `2ff6b957fee97ffbdca6ca842af3d98bdbeaddf5`, 당시
      `SqsProperties.kt` SHA-256, `git status --short`와 `git diff --exit-code --
      aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsProperties.kt` 결과를
      `.lane-evidence/issue-461-properties-abi-provenance.txt`에 보존한다. source path가
      dirty면 fixture를 만들지 않는다.

~~~text
mkdir -p .lane-evidence
./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --no-daemon \
  --dependency software.amazon.awssdk:sqs --configuration compileClasspath \
  | tee .lane-evidence/issue-461-sdk-dependency.txt
SQS_VERSION=$(sed -nE \
  's/.*software\.amazon\.awssdk:sqs:([0-9][^ ]*).*/\1/p' \
  .lane-evidence/issue-461-sdk-dependency.txt | head -n 1)
test -n "$SQS_VERSION"
SQS_JAR_DIR="$HOME/.gradle/caches/modules-2/files-2.1/software.amazon.awssdk/sqs/$SQS_VERSION"
JAR_COUNT=$(find "$SQS_JAR_DIR" -type f -name "sqs-$SQS_VERSION.jar" | wc -l | tr -d ' ')
test "$JAR_COUNT" -eq 1
SQS_JAR=$(find "$SQS_JAR_DIR" -type f -name "sqs-$SQS_VERSION.jar")
{
  shasum -a 256 "$SQS_JAR"
  javap -classpath "$SQS_JAR" \
    software.amazon.awssdk.services.sqs.batchmanager.SqsAsyncBatchManager \
    software.amazon.awssdk.services.sqs.batchmanager.BatchOverrideConfiguration
} | tee .lane-evidence/issue-461-sdk-api.txt
~~~

- [ ] `sendMessage`, `deleteMessage`, builder/close, flush/size configuration과 반환
      `CompletableFuture` signature를 실제 output에서 확인한다.

### Step 0.2: legacy `SqsProperties` fixture를 먼저 RED/GREEN으로 고정

- [ ] `LegacySqsPropertiesFixture`는 현재 primary constructor와 `copy`를 호출하고
      batch 속성을 전혀 모르는 pre-change consumer로 작성한다.
- [ ] `registerSqsConsumerFixtureCompile`을 재사용해
      `compileSqsPropertiesLegacyConsumerFixture` task를 추가한다. production API를
      바꾸기 전에 다음 명령으로 compile/hash/javap를 보존한다.

~~~text
./gradlew compileSqsPropertiesLegacyConsumerFixture --no-daemon
{
  git rev-parse HEAD HEAD^
  shasum -a 256 \
    aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsProperties.kt
  git status --short
  git diff --exit-code -- \
    aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsProperties.kt
} | tee .lane-evidence/issue-461-properties-abi-provenance.txt
{
  find build/consumer-fixtures/aws-spring-sqs/properties-legacy/classes \
    -type f -print0 | sort -z | xargs -0 shasum -a 256
  javap -classpath build/consumer-fixtures/aws-spring-sqs/properties-legacy/classes \
    io.bluetape4k.aws.spring.sqs.consumer.LegacySqsPropertiesFixture
} | tee .lane-evidence/issue-461-properties-abi.txt
~~~

- [ ] 생성 class를 `sqs-properties-abi` test resource에 보존하고 SHA-256 상수를
      기록한다. 이후 이 compile task는 다시 실행하지 않는다.

~~~text
install -d \
  aws-spring-boot/src/test/resources/sqs-properties-abi/io/bluetape4k/aws/spring/sqs/consumer
install -m 0644 \
  build/consumer-fixtures/aws-spring-sqs/properties-legacy/classes/io/bluetape4k/aws/spring/sqs/consumer/LegacySqsPropertiesFixture.class \
  aws-spring-boot/src/test/resources/sqs-properties-abi/io/bluetape4k/aws/spring/sqs/consumer/LegacySqsPropertiesFixture.class
shasum -a 256 \
  aws-spring-boot/src/test/resources/sqs-properties-abi/io/bluetape4k/aws/spring/sqs/consumer/LegacySqsPropertiesFixture.class
~~~

- [ ] RED: isolated `ClassLoader`로 fixture를 읽고 기존 constructor/copy 호출 결과와
      hash를 검증하는 테스트를 먼저 추가한다. resource가 아직 없거나 상수가 없어서
      실패하는 것을 기록한다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sqs.SqsPropertiesBinaryCompatibilityTest"
~~~

- [ ] GREEN: 보존한 변경 전 class resource만 사용해 테스트를 통과시킨다. 새
      `SqsBatchProperties` 구현 뒤에도 fixture compile task는 재실행하지 않는다.

## Task 1: public batch 모델·validation·redaction

**Files:**

- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchModels.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchExceptions.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsAutomaticBatchModelsTest.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsAutomaticBatchExceptionsTest.kt`

### Step 1.1: RED

- [ ] `SqsBatchSendEntry`, `SqsBatchDeleteEntry`, `SqsBatchSendSuccess`,
      `SqsBatchEntryFailure`, `SqsSendManyResult`, `SqsDeleteManyResult`의
      serialization round-trip, defensive snapshot, `serialVersionUID=1L`, blank/duplicate
      validation, status 계산과 redacted `toString()`을 먼저 고정한다.
- [ ] `SqsBatchSendSuccess.sequenceNumber`가 non-null이면 nonblank여야 한다.
      모든 string invariant는 `requireNotBlank` 계열 기존 helper를 우선 사용한다.
- [ ] service code allow-list/64자 상한, transport `code == null`, wrapper 반복 unwrap,
      response unknown/duplicate/missing ID, null/blank `messageId`, input-relative order를
      테스트한다.
- [ ] exception의 message, `toString()`, cause, suppressed에 body, queue URL, receipt
      handle, entry ID, attribute, raw SDK error, CR/LF가 없음을 `shouldNotContain`으로
      검사한다. `SqsBatchStartupException`은 startup component와 cleanup component
      kind/count만 보유하고 `cause == null`, `suppressed.isEmpty()`이며 serialization 뒤에도
      안전한 고정 message/`toString()`만 제공하는지 검증한다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sqs.SqsAutomaticBatchModelsTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsAutomaticBatchExceptionsTest"
~~~

**Expected RED:** 새 public 타입과 normalizer가 없어 test compilation 또는 contract
assertion이 실패한다.

### Step 1.2: GREEN

- [ ] 승인 명세의 public model·enum shape를 구현한다. operations interface는 Task 5 RED
      뒤에 추가한다.

~~~kotlin
enum class SendBatchFailureStrategy { RETURN, THROW }
enum class SqsBatchResultStatus { SUCCESS, PARTIAL_FAILURE, FAILURE }
enum class SqsBatchFailureKind { SERVICE, TRANSPORT }
~~~

- [ ] 모든 public data class는 private primary constructor,
      `@ConsistentCopyVisibility`, validating public secondary constructor, `val`, defensive
      collection snapshot, Korean KDoc와 explicit `serialVersionUID`를 사용한다.
- [ ] internal `BatchResultNormalizer`는 expected ID/outcome을 property에 보관하지 않고
      성공·실패 목록을 input-relative order로 생성한다. protocol exception도 raw outcome을
      보유하지 않는다.
- [ ] `SqsSendBatchFailedException(result)`는 동일 normalized result만 보유하고,
      `SqsBatchCloseException`은 deduplicated `MANAGER`, `EXECUTOR`, `TIMEOUT`만 고정
      순서로 보유한다.
- [ ] `SqsBatchStartupException`은 `MANAGER`, `TRANSPORT`, `TEMPLATE` startup component와
      deduplicated cleanup component kind/count만 보유한다. raw startup/cleanup throwable을
      property, cause, suppressed에 보관하지 않고 안전한 exception identity만 전달한다.
- [ ] RED 명령을 그대로 다시 실행해 GREEN을 확인한다.

## Task 2: properties와 direct transport seam

**Files:**

- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchProperties.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchTransport.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchPropertiesTest.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsDirectBatchTransportTest.kt`

### Step 2.1: RED

- [ ] defaults와 각 범위, `batch.enabled=true`에서만
      `maxInFlightEntries >= maxBatchSize`, `shutdownTimeout >= flushInterval`을 검증한다.
      direct mode에서는 사용하지 않는 batch 크기 때문에 유효한 작은 in-flight 값을
      거부하지 않는다.
- [ ] direct transport가 `SqsSendRequest`의 delay/FIFO/attributes와 delete receipt handle을
      그대로 매핑하고 single future를 반환하며 client를 닫지 않는지 capture한다.
- [ ] success, `SqsException`, transport exception, future cancellation을 공통 outcome으로
      변환하되 SDK future cancellation은 caller cancellation과 분리한다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sqs.SqsBatchPropertiesTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsDirectBatchTransportTest"
~~~

**Expected RED:** properties/transport가 없어 compilation이 실패한다.

### Step 2.2: GREEN

- [ ] 기존 `SqsProperties`는 한 줄도 변경하지 않고 별도 설정을 추가한다.

~~~kotlin
internal const val SQS_BATCH_PROPERTIES_PREFIX = "bluetape4k.aws.sqs.batch"

@ConfigurationProperties("bluetape4k.aws.sqs.batch")
data class SqsBatchProperties(
    val enabled: Boolean = false,
    val maxBatchSize: Int = 10,
    val flushInterval: Duration = Duration.ofMillis(200),
    val maxEntriesPerCall: Int = 1_000,
    val maxInFlightEntries: Int = 100,
    val schedulerThreads: Int = 1,
    val shutdownTimeout: Duration = Duration.ofSeconds(5),
) : Serializable {
    init {
        maxBatchSize.requireInRange(1, 10, "$SQS_BATCH_PROPERTIES_PREFIX.max-batch-size")
        flushInterval.requireInRange(
            Duration.ofMillis(1), Duration.ofMinutes(1),
            "$SQS_BATCH_PROPERTIES_PREFIX.flush-interval",
        )
        maxEntriesPerCall.requireInRange(1, 10_000, "$SQS_BATCH_PROPERTIES_PREFIX.max-entries-per-call")
        maxInFlightEntries.requireInRange(1, 10_000, "$SQS_BATCH_PROPERTIES_PREFIX.max-in-flight-entries")
        schedulerThreads.requireInRange(1, 16, "$SQS_BATCH_PROPERTIES_PREFIX.scheduler-threads")
        shutdownTimeout.requireInRange(
            Duration.ofMillis(1), Duration.ofMinutes(1),
            "$SQS_BATCH_PROPERTIES_PREFIX.shutdown-timeout",
        )
        require(!enabled || maxInFlightEntries >= maxBatchSize) {
            "$SQS_BATCH_PROPERTIES_PREFIX.max-in-flight-entries must cover max-batch-size"
        }
        require(!enabled || shutdownTimeout >= flushInterval) {
            "$SQS_BATCH_PROPERTIES_PREFIX.shutdown-timeout must cover flush-interval"
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
~~~

- [ ] `init`은 기존 `io.bluetape4k.support.requireInRange`를 사용하고 property token만 가진
      message로 위 범위를 검증한다. `ApplicationContextRunner`는 각 invalid binding에서 bean
      생성 전 실패하고 raw 설정값을 failure message에 남기지 않는지 검증한다.
- [ ] internal `SqsBatchTransport`는 submit 시점의 future만 반환하고 public SDK/manager
      타입을 노출하지 않는다. `DirectSqsBatchTransport`는 caller-owned
      `SqsAsyncClient`를 닫지 않는다.
- [ ] transport factory와 response adapter는 공개 `BatchExecutionStrategy` 또는 converter가
      아니라 test-injectable internal seam으로 둔다.
- [ ] RED 명령을 그대로 다시 실행해 GREEN을 확인한다.

## Task 3: SDK batch manager adapter와 construction rollback

**Files:**

- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsAsyncBatchManagerTransport.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsAsyncBatchManagerTransportTest.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchTransportFactoryTest.kt`

### Step 3.1: RED

- [ ] fake/captured manager seam으로 size/flush 설정, send/delete request mapping, 부분 실패,
      shared transport failure와 exact future handoff를 먼저 검증한다.
- [ ] 실제 `SqsAsyncBatchManager`와 mock `SqsAsyncClient`, 제어 가능한 scheduler를 연결해
      batch size 도달 전 호출 0회, size 도달 즉시 1회, flush interval 뒤 작은 batch 1회,
      서로 다른 coroutine의 같은 queue 요청 병합, 다른 queue URL 분리, send/delete 혼합
      buffer 분리와 많은 queue URL의 pending 상한을 검증한다. wall-clock sleep은 쓰지 않는다.
- [ ] scheduler 생성 뒤 manager build 실패 시 같은 stack에서 `shutdownNow()`가 호출되고
      표준 client는 닫히지 않는 construction rollback을 검증한다.
- [ ] manager 생성 뒤 transport adapter 조립이 실패하는 fake 경로도 추가한다. manager
      close → scheduler shutdown 순서와 각각 exactly once, 표준 client 미종료, 한 번 만든
      safe `SqsBatchStartupException` identity 보존, raw startup/cleanup throwable graph 제거,
      cleanup failure의 component-only 기록을 검증한다.
- [ ] manager class 없는 classloader에서는 direct types가 load되고, manager transport
      경로만 load 실패하는 optional-class isolation을 검증한다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sqs.SqsAsyncBatchManagerTransportTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsBatchTransportFactoryTest"
~~~

**Expected RED:** manager adapter/factory가 없어 compilation이 실패한다.

### Step 3.2: GREEN

- [ ] `SqsAsyncBatchManager.builder()`에 caller-owned `SqsAsyncClient`, 전용 daemon
      `ScheduledThreadPoolExecutor`, `maxBatchSize`, `flushInterval`을 연결한다.
- [ ] thread 이름에는 고정 prefix와 내부 sequence만 사용하고 queue URL/entry ID를 넣지
      않는다. `setRemoveOnCancelPolicy(true)`와 rejected cleanup을 설정한다.
- [ ] `SqsBatchTransportFactory.create(properties, client)`는 manager build 실패 시 executor를
      닫고 raw error graph를 보관하지 않는 `SqsBatchStartupException`으로 한 번 바꾼다.
      message/`toString()`에는 startup/cleanup kind/count만 있고 `cause=null`, suppressed는
      빈 배열이다.
- [ ] manager SDK class를 import하는 production file과 enabled nested configuration에만
      `@ConditionalOnClass(name=...)` 경계를 둔다.
- [ ] RED 명령을 그대로 다시 실행해 GREEN을 확인한다.

## Task 4: coordinator admission·부분 결과·취소

**Files:**

- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchCoordinator.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchCoordinatorTest.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchCoordinatorRaceTest.kt`

### Step 4.1: deterministic RED

- [ ] N=0, 1, `maxInFlightEntries`, `maxInFlightEntries+1`, `maxEntriesPerCall`,
      `maxEntriesPerCall+1` matrix를 고정한다. oversized/custom collection은 iterator가
      상한+1 뒤 더 읽히면 실패하도록 만들어 bounded materialization을 증명한다. empty와
      invalid/overflow 설정은 resource 생성·transport 0회다.
- [ ] caller가 넘긴 mutable collection과 `messageAttributes` map을 admission 뒤 변경해도
      transport request가 최초 snapshot을 유지하는지 검증한다.
- [ ] active future와 accepted placeholder max가 설정 상한 이하인지
      `shouldBeLessOrEqualTo`, window 이후 다음 window가 진행되는지
      `shouldBeGreaterOrEqualTo`로 검사한다.
- [ ] 첫 sequence 결과를 지연한 상태에서 resident child, pending result map peak도 각각
      `maxInFlightEntries` 이하이고 완료 뒤 0인지 deterministic counter로 단언한다.
- [ ] `supervisorScope` entry별 service/transport failure를 모두 수집하고 send RETURN/THROW와
      delete result 계약, input-relative order, no-retry를 검증한다.
- [ ] 동일 FIFO group 요청도 fake transport의 실제 submit 순서를 API 계약으로 단언하지
      않고, 결과 상대 순서만 고정한다.
- [ ] 서로 다른 동시 호출이 같은 public entry ID를 사용해도 monotonic internal token과
      registry가 충돌하지 않고 두 result를 독립적으로 반환하는지 검증한다.
- [ ] barrier로 permit 획득 직후 close, placeholder 등록 직후 caller cancel, submit 수락 전
      future 실패, accepted 뒤 submit throw, response 직후 cancel interleaving을 고정한다.
- [ ] root caller `CancellationException` identity, incomplete future `cancel(false)` 정확히
      1회, permit과 registry exactly-once release, caller active 상태의 SDK cancellation이
      `TRANSPORT` result인 것을 검증한다. cancel count를 기록하는 fake future와 실제 job
      cancellation을 사용하고 `runCatching`을 쓰지 않는다.
- [ ] 모든 permit이 점유된 barrier에서 다음 child가 close-aware acquire를 기다리게 한 뒤
      close한다. waiter 종료, 외부 submit 0회, permit 누수 0, orphan child 0을 검증한다.
- [ ] close-timeout과 caller cancellation을 같은 future 앞에서 경합시킨다. 두 경로가
      entry의 유일한 `cancelIfIncomplete()` atomic guard를 통과해 fake future의
      `cancel(false)` count가 1이고 원래 `CancellationException` identity가 유지되는지
      검증한다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sqs.SqsBatchCoordinatorTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsBatchCoordinatorRaceTest"
~~~

**Expected RED:** coordinator가 없어 compilation이 실패한다.

### Step 4.2: GREEN

- [ ] operation 입구에서 iterator를 최대 `maxEntriesPerCall + 1`까지만 읽어 초과를
      거부하고, 허용된 snapshot을 validate한 뒤 `maxInFlightEntries` 크기의 admission
      window만 materialize한다.
- [ ] 각 child는 permit 획득 후 lock 아래 `OPEN` 확인과 monotonic token placeholder 등록만
      수행한다. lock 밖 submit 뒤 같은 token에 future를 handoff하고 await한다.
- [ ] close가 accepted-before-handoff placeholder도 기다릴 수 있도록 registration,
      handoff, completion removal, close snapshot/claim을 같은 `ReentrantLock`에서
      linearize한다. deferred signal은 lock 밖에서 완료한다.
- [ ] internal custom cancellable await, child `finally`, close-timeout은 entry별 유일한
      `cancelIfIncomplete()` atomic once guard를 공유한다. 어느 경로가 먼저 와도 incomplete
      future `cancel(false)`는 1회이고 registry 제거와 permit release도 exactly once다. stock
      `CompletionStage.await()`와 별도 cancel을 함께 쓰지 않으며 원래 cancellation을 교체하지
      않는다.
- [ ] synchronization order는 permit→짧은 lifecycle lock만 허용하고 lock→permit은
      금지한다. permit과 close signal을 함께 기다리는 cancellable acquire gate를 사용해
      permit 대기 중 close/caller cancellation은 placeholder 없이 종료한다. permit을 먼저
      얻은 경로도 lifecycle lock의 `OPEN` 검사에서 close와 선형화한다. permit+lock 구간에는
      suspension·signal·외부 호출이 없다. barrier와 lock ownership test로 역순 대기,
      외부 호출 0회, permit 누수와 orphan child 0을 검증한다.
- [ ] RED 명령을 그대로 다시 실행해 GREEN을 확인한다.

## Task 5: template API와 close lifecycle

**Files:**

- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchOperations.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchCoroutinesTemplate.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchCoroutinesTemplateTest.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchLifecycleTest.kt`

### Step 5.1: RED

- [ ] direct/batch mode의 same-result parity, request field preservation, send RETURN/THROW,
      delete always-return, validation-before-call을 검증한다.
- [ ] close owner만 transition하는지, `CLOSING` admission rejection, 정상 drain 뒤 manager
      close, timeout의 `cancelIfIncomplete()` 뒤 close, executor shutdown 순서를 fake
      clock/barrier로 고정한다.
- [ ] drain/manager/executor가 각각 전체 deadline을 새로 받지 않고 남은 monotonic duration만
      받는지 검증한다.
- [ ] manager `close()` block, cleanup thread interrupt 무시, scheduler 종료 실패, owner
      interrupt, unexpected throwable에서도 bounded return, daemon thread, 최종 `CLOSED`,
      completion 완료를 검증한다. 외부 publisher latency 자체의 상한을 주장하지 않는다.
- [ ] 반복 timeout에서 orphan daemon cleanup thread의 현재 수와 eventual termination을
      관찰한다. fake manager가 영구 block하는 한계까지 실제 측정하지 않으면 Task 10의 SQS
      성능 후속 이슈 acceptance에 cleanup thread 누적 telemetry를 포함한다.
- [ ] concurrent/repeated close caller가 동일 success 또는 동일
      `SqsBatchCloseException` instance를 관찰하고 cleanup component가 중복되지 않는지
      실제 multithread tester로 검증한다.
- [ ] manager와 executor cleanup이 각각 또는 동시에 실패해도 나머지 cleanup이 진행되고,
      component는 `MANAGER`, `EXECUTOR`, `TIMEOUT` 고정 순서·중복 없음으로 정규화되는지
      검증한다.
- [ ] batch resources 생성 뒤 template 조립이 실패하는 injected factory 경로에서 manager
      close → scheduler shutdown을 각각 1회 수행하고 표준 client는 닫지 않으며 같은 safe
      `SqsBatchStartupException` identity를 유지하는지 검증한다. raw startup/cleanup
      throwable의 message, cause, suppressed, `toString()` token은 외부 exception graph에 없어야
      한다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sqs.SqsBatchCoroutinesTemplateTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsBatchLifecycleTest"
~~~

**Expected RED:** template/lifecycle 구현이 없어 compilation이 실패한다.

### Step 5.2: GREEN

- [ ] 승인 명세의 exact operations declaration을 추가한다.

~~~kotlin
interface SqsBatchOperations {
    suspend fun sendMany(
        entries: Collection<SqsBatchSendEntry>,
        failureStrategy: SendBatchFailureStrategy = SendBatchFailureStrategy.RETURN,
    ): SqsSendManyResult

    suspend fun deleteMany(entries: Collection<SqsBatchDeleteEntry>): SqsDeleteManyResult
}
~~~

- [ ] `SqsBatchCoroutinesTemplate`은 `SqsBatchOperations, AutoCloseable`이며 표준 client는
      빌리지만 transport/manager/executor만 소유한다.
- [ ] companion/internal factory는 batch resources를 만든 뒤 template constructor가
      실패하면 manager와 scheduler를 역순 정리한다. cleanup failure는 한 번 만든 safe
      `SqsBatchStartupException`을 교체하지 않으며 raw throwable graph를 부착하지 않는다.
- [ ] close owner는 lock 아래 state/snapshot/shared completion 소유권만 정하고, lock 밖에서
      monotonic deadline을 적용해 drain → manager cleanup daemon thread → executor cleanup을
      수행한다.
- [ ] 최상위 `try/finally`가 unexpected failure와 interrupt에서도 `CLOSED` 전환과
      shared completion 완료를 보장한다. interrupt status를 복원하고 raw failure는 버린 뒤
      cleanup component kind/count만 materialize한다.
- [ ] manager `close()`는 accepted future drain 뒤에만 호출한다. timeout 뒤에는 delivery를
      보장하지 않으며 cancel은 rollback이 아님을 KDoc에 명시한다.
- [ ] RED 명령을 그대로 다시 실행해 GREEN을 확인한다.

## Task 6: Spring auto-configuration·optional class·ABI

**Files:**

- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsAutoConfiguration.kt`
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMicrometerAutoConfiguration.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsAutoConfigurationTest.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsPropertiesBinaryCompatibilityTest.kt`

### Step 6.1: RED

- [ ] `ApplicationContextRunner`로 global/SQS disabled, batch default false/direct bean,
      enabled manager bean, invalid properties, custom `SqsBatchOperations` complete backoff,
      standard `SqsAsyncClient`/`SqsOperations`/listener 그대로 유지, destroy close를 검증한다.
- [ ] `FilteredClassLoader`로 manager package만 제거한다. disabled mode는 시작되고 direct
      bean이 존재하며, enabled mode는 민감정보 없는 startup failure를 내고, 전체 SQS SDK가
      없으면 기존 auto-configuration이 backoff하는지 각각 분리한다.
- [ ] custom `SqsBatchOperations`와 custom concrete `SqsBatchCoroutinesTemplate`을 각각
      등록한다. 두 경우 모두 default raw marker/manager/executor/decorator가 0개이며 custom
      concrete bean 자체는 decorator로 감싸지지 않는지 검증한다.
- [ ] precompiled `SqsProperties` fixture hash/isolated load와 primary constructor/copy 호출이
      새 code에서도 그대로 동작하는지 확인한다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sqs.SqsAutoConfigurationTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsPropertiesBinaryCompatibilityTest"
~~~

**Expected RED:** batch beans/properties/conditions가 없어 context assertion이 실패한다.

### Step 6.2: GREEN

- [ ] `@EnableConfigurationProperties(SqsProperties::class, SqsBatchProperties::class)`로
      별도 binding을 등록하고 `SqsProperties` declaration은 변경하지 않는다.
- [ ] raw template configuration을 property-exclusive하게 나눈다. direct nested
      configuration은 `enabled=false` 또는 missing, enabled nested configuration은
      `@ConditionalOnProperty(... havingValue="true")`와 manager
      `@ConditionalOnClass(name=...)`를 함께 사용한다. 두 configuration의 raw bean은
      `@Bean(destroyMethod = "close")`와
      `@ConditionalOnMissingBean(SqsBatchOperations::class)`를 사용한다.
- [ ] 별도 manager-missing nested guard는 `enabled=true`,
      `@ConditionalOnMissingClass(name=...)`,
      `@ConditionalOnMissingBean(SqsBatchOperations::class)`에서만 활성화된다. raw
      template/manager/executor를 만들지 않고 safe `SqsBatchStartupException`으로 context를
      실패시킨다. disabled와 custom operations 경로에서는 guard가 활성화되지 않는다.
- [ ] custom batch operations가 있으면 raw template, manager, executor, Micrometer batch
      decorator 모두 생성하지 않는다. 기존 operations/listener bean 수와 ordering은
      변하지 않는다.
- [ ] default direct/enabled configuration만 internal `DefaultSqsBatchOperationsMarker` bean을
      raw template과 함께 등록한다. Micrometer decorator는 marker, raw concrete template,
      `MeterRegistry`가 모두 있을 때만 생성해 custom concrete template을 장식하지 않는다.
- [ ] `MeterRegistry` 환경에서 `SqsOperations` 주입은 기존 `MicrometerSqsOperations`,
      `SqsBatchOperations` 주입은 `MicrometerSqsBatchOperations`, concrete
      `SqsBatchCoroutinesTemplate` 주입은 raw bean으로 각각 유일하게 해석되는지 검증한다.
      custom batch bean에서는 raw/manager/batch decorator가 모두 0개다.
- [ ] RED 명령을 그대로 다시 실행해 GREEN을 확인한다.

## Task 7: Micrometer exact tag 계약

**Files:**

- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/MicrometerSqsBatchOperations.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/MicrometerSqsBatchOperationsTest.kt`
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMicrometerAutoConfiguration.kt`

### Step 7.1: RED

- [ ] timer 이름 `bluetape4k.aws.sqs.batch.operation`과 exact tag key/value allow-list를
      고정한다: `service=sqs`,
      `operation=send_many|delete_many`, `mode=batch|direct`,
      `outcome=success|partial_failure|failure|cancelled`.
- [ ] queue URL/name, body, receipt handle, entry ID, service code, exception message가 tag나
      meter ID에 없음을 `shouldNotContain`으로 검증한다.
- [ ] registry 조회로 send RETURN의 success/partial/all-failed, send THROW의
      success/mixed-result exception/all-failed exception, delete success/partial/all-failed,
      validation·protocol·lifecycle failure와 caller cancellation matrix를 검증한다.
      outcome은 각각 result status에 따른 `success|partial_failure|failure`, 일반 실패는
      `failure`, cancellation은 `cancelled`이며 exception/result identity를 바꾸지 않는다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sqs.MicrometerSqsBatchOperationsTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsAutoConfigurationTest"
~~~

**Expected RED:** batch decorator/tag가 없어 compilation 또는 meter assertion이 실패한다.

### Step 7.2: GREEN

- [ ] decorator는 raw template에 위임하고 기존 `MicrometerSqsOperations`와 별도 bean type을
      유지한다. tag cardinality를 exact allow-list로 제한한다.
- [ ] custom `SqsBatchOperations` backoff와 raw template injection 경계를 지킨다.
- [ ] RED 명령을 그대로 다시 실행해 GREEN을 확인한다.

## Task 8: Floci 순차 smoke와 capability 경계

**Files:**

- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchCoroutinesTemplateAwsEmulatorTest.kt`
- Create: `scripts/ci/report_sqs_batch_floci_status.rb`
- Create: `scripts/ci/report_sqs_batch_floci_status_test.rb`
- Modify: `.github/workflows/ci.yml`
- Reuse: repository shared AWS emulator test base and queue helpers

### Step 8.1: RED/GREEN

- [ ] Floci에서 direct fallback send/delete, enabled batch size/flush로 다건 send/delete,
      FIFO fields 또는 standard queue attributes 보존, clean close를 순차 검증한다.
- [ ] entry partial failure를 Floci가 안정적으로 만들 수 없으면 deterministic unit test를
      acceptance evidence로 유지하고 emulator에서 거짓 PASS를 만들지 않는다.
- [ ] Colima/Testcontainers shared resource 때문에 다른 emulator test와 병렬 실행하지 않는다.
      기존 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`/AGENTS 운영 해법을 적용한 뒤에도
      capability/socket 장애면 원 오류와 skip 사유를 보존한다.
- [ ] XML status reporter의 RED를 먼저 추가한다. target class test count가 1 이상이고
      failure/error/skip 0이면 `PASS`, 전체 skip 또는 capability skip이면 `N_A`, 그 밖에는
      `FAIL`을 출력한다. raw exception/payload는 출력하지 않고 class/count/reason token만
      GitHub summary와 `sqs-batch-floci-status.txt`에 남긴다.
- [ ] reporter test는 missing XML, malformed XML, zero-test XML, mixed pass/failure, 전체 skip,
      capability skip을 각각 고정한다. `PASS`와 `N_A`는 process exit 0, `FAIL`은 nonzero이며
      status file에는 항상 `PASS`, `N_A`, `FAIL` 중 하나와 정규화한 reason token이 있어야
      한다.

~~~text
ruby scripts/ci/report_sqs_batch_floci_status_test.rb
~~~

**Expected RED:** reporter가 없어 require 또는 status assertion이 실패한다.

- [ ] CI의 `test-aws-spring-boot` job은 Gradle test step의 성공 여부와 무관하게 reporter를
      `if: always()`로 실행하고 status artifact upload도 `if: always()`로 수행한다. reporter
      exit code는 `PASS`/`N_A`에서 0, `FAIL`에서 nonzero다. `FAIL`은 job을 실패시키고 `N_A`는
      deterministic unit evidence와 별도 분류하되 PR DoD의 emulator PASS로 체크하지 않는다.
      `ci-status` 검증 fixture는 spring-boot job success와 status `N_A`가 함께 있어도 emulator
      PASS로 승격하지 않고, missing/malformed status artifact를 failure로 분류한다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  -Dbluetape4k.aws.emulator=floci \
  --tests "io.bluetape4k.aws.spring.sqs.SqsBatchCoroutinesTemplateAwsEmulatorTest"
~~~

**Expected RED:** test가 먼저 batch API/bean 부재로 실패한다. GREEN은 실제 emulator
success만 PASS로 집계한다.

## Task 9: KDoc·README·manual·compiled example

**Files:**

- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `aws-spring-boot/README.md`
- Modify: `aws-spring-boot/README.ko.md`
- Modify: `docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`
- Modify: `docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchDocumentationExampleTest.kt`
- Create: `aws-spring-boot/src/test/resources/documentation/sqs-batch/application.yaml`
- Modify: `scripts/manual/manual_contract.rb`
- Modify: `scripts/manual/manual_contract_test.rb`

### Step 9.1: canonical example RED

- [ ] `SqsBatchDocumentationExampleTest`의
      `// tag::sqs-batch-kotlin[]` / `// end::sqs-batch-kotlin[]` region에 injection,
      `RETURN`, `THROW`, `deleteMany`, 기존 `SqsOperations.send` migration을 컴파일되는 코드로
      먼저 작성한다. YAML은 `src/test/resources/documentation/sqs-batch/application.yaml`에
      일곱 설정과 실제 defaults를 모두 둔다.
- [ ] manual contract test RED는 여섯 Markdown 문서의
      `<!-- sqs-batch-kotlin:start|end -->`, `<!-- sqs-batch-yaml:start|end -->` 사이 fenced
      body가 각각 Kotlin region과 YAML resource에서 표류하면 exact error를 내도록 먼저
      추가한다. Kotlin 정규화는 marker와 공통 들여쓰기만 제거하고, YAML은 UTF-8/LF와
      마지막 newline만 정규화한다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sqs.SqsBatchDocumentationExampleTest"
ruby scripts/manual/manual_contract_test.rb
~~~

**Expected RED:** public batch API 부재로 example compilation이 실패하고, parity 구현
전에는 manual contract drift assertion도 실패한다.

### Step 9.2: 문서 GREEN

- [ ] 네 README와 EN/KO manual에 opt-in, direct fallback, size/flush/in-flight/shutdown
      trade-off, partial result, no retry/rollback, cancellation delivery uncertainty, strict FIFO
      warning, custom bean backoff와 observability를 구조적으로 맞춘다.
- [ ] Markdown snippet은 canonical source region과 동일하게 유지한다. 기술 token/anchor/link는
      보존하고 한국어 문서는 자연스러운 기술 문체로 작성한다.
- [ ] YAML 예시는 `scheduler-threads: 1`, `shutdown-timeout: 5s`까지 포함해 defaults 표와
      생략 의미가 갈리지 않게 한다.
- [ ] 모든 새 public API에 Korean KDoc, cancellation/lifecycle/ownership warning을 넣는다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sqs.SqsBatchDocumentationExampleTest"
ruby scripts/manual/manual_contract_test.rb
git diff --check
~~~

## Task 10: 통합 검증·성능 스캔·후속 이슈 게이트

### Step 10.1: targeted와 module 검증

- [ ] 아래 명령을 순차 실행하고 fresh output을 보존한다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sqs.SqsAutomaticBatchModelsTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsAutomaticBatchExceptionsTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsDirectBatchTransportTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsAsyncBatchManagerTransportTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsBatchCoordinatorTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsBatchCoordinatorRaceTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsBatchLifecycleTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsAutoConfigurationTest" \
  --tests "io.bluetape4k.aws.spring.sqs.MicrometerSqsBatchOperationsTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsPropertiesBinaryCompatibilityTest" \
  --tests "io.bluetape4k.aws.spring.sqs.SqsBatchDocumentationExampleTest"
./gradlew :bluetape4k-aws-spring-boot:test \
  -PskipAwsEmulatorTests=true --no-daemon
./gradlew verifyAwsSpringSqsCompileOnlyPublication --no-daemon
./gradlew detekt --no-daemon
./gradlew build -x test --parallel --no-daemon
ruby scripts/manual/manual_contract_test.rb
git diff --check
~~~

- [ ] legacy fixture SHA/javap, public JVM signatures, `SqsProperties` unchanged diff와
      optional-class isolation을 다시 읽는다. baseline fixture compile task는 재실행하지
      않는다.
- [ ] root `build.gradle.kts`의 `verifyAwsSpringSqsCompileOnlyPublication`은
      `:bluetape4k-aws-spring-boot:generatePomFileForBluetapeAwsPublication`과
      `generateMetadataFileForBluetapeAwsPublication` 뒤 POM/module metadata에서
      `software.amazon.awssdk:sqs`가 runtime dependency로 노출되지 않는지 검사한다. task
      자체의 RED fixture는 금지 dependency를 넣은 임시 parsed text로 먼저 실패시킨다.
- [ ] 이 root task는 정확히
      `aws-spring-boot/build/publications/BluetapeAws/pom-default.xml`과
      `aws-spring-boot/build/publications/BluetapeAws/module.json`을 읽고 두 generation task에
      `dependsOn`한다. 기존 publication dependency parser를 shared helper로 추출해
      `software.amazon.awssdk:sqs`가 POM의 dependency section 또는 Gradle metadata의
      dependencies array에 있으면 실패시킨다. root `check`도 이 task에 의존하며 missing
      metadata, forbidden POM fixture, forbidden module fixture, clean fixture를 RED/GREEN으로
      검증한다.

### Step 10.2: bounded resource evidence와 durable follow-up

- [ ] deterministic counters로 active future/placeholder ≤ `maxInFlightEntries`, pending map 0,
      executor termination, cleanup thread daemon과 close deadline을 확인한다.
- [ ] 실제 외부 publisher latency/cleanup telemetry 또는 heap·throughput 수치를 이번 구현에서
      측정하지 않으면 PR 생성 전에 SQS 전용 후속 GitHub 이슈를 한국어로 생성한다. 이슈는
      assignee `debop`, 연결 PR이 없으므로 milestone 없음, `enhancement` 또는 `performance`,
      `aws-spring-boot`, `sqs` labels, 재현 workload, 측정 지표, acceptance와
      `## DoD Status`를 포함한다. 반복 timeout의 orphan cleanup thread 수·eventual 종료
      telemetry도 acceptance에 넣고, live read-back과 #461 링크를 본 plan review/PR에
      기록한다.
- [ ] 공개 `BatchExecutionStrategy`·outbound converter 요구는 이번 scope에 넣지 않는다.
      SQS에서 실제 확장 요구가 확인되면 별도 backlog 이슈로만 제안한다.

### Step 10.3: rollback checkpoint

- [ ] production rollback은 batch operations/models/properties/transport/coordinator/template/
      auto-config/decorator를 한 단위로 되돌린다. cancellation과 lifecycle 일부만 따로
      되돌리지 않는다.
- [ ] rollback 시 RED tests, pre-change ABI resource, evidence는 보존하고 기존
      `SqsOperations`/listener baseline과 `batch.enabled=false` context를 재실행한다.

## Task 11: 구현 후 review·PR·merge train 게이트

- [ ] Step 6-R 독립 implementation review에서 P0=0/P1=0을 만든다. Kotlin pattern,
      assertions public API, security, lifecycle/concurrency, performance/resource, API/ABI,
      docs/user 관점을 각각 검증한다.
- [ ] Issue #461 본문을 최종 API와 DoD로 한국어 갱신하고 live metadata를 읽는다.
- [ ] PR base는 `develop`, head는 `feat/issue-461-sqs-template-batching`으로만 생성한다.
      PR 제목/본문은 한국어, assignee `debop`, milestone `0.6.0`, Issue #461 연결,
      마지막 section은 `## DoD Status`다.
- [ ] exact-head 전체 CI, checks, threads, mergeability를 다시 읽은 뒤 fresh merge 승인을
      받는다. 1인 개발자 human review는 N/A이며 auto-merge는 사용하지 않는다.
- [ ] merge 뒤 canonical develop sync, merged blob parity, dirty/untracked 보존, 정확한
      worktree/branch만 proof-gated cleanup하고 Epic #499 자식 이슈 상태를 재평가한다.

## 커밋 전략

각 RED→GREEN 단위는 작게 커밋하되 모든 pushed commit message를 한국어 Lore protocol로
작성한다. 예시는 다음과 같다.

| 완료 경계 | intent line | 필수 Tested evidence |
|---|---|---|
| Task 0 | `SQS 설정 ABI 기준선을 변경 전에 보존한다` | fixture compile/hash/isolated load |
| Task 1~2 | `SQS 배치 입력과 실패를 안전한 공개 계약으로 고정한다` | model/exception/properties/direct tests |
| Task 3 | `SDK 자동 배치를 소유 자원 경계 안에 격리한다` | manager/factory/rollback tests |
| Task 4~5 | `SQS 배치 취소와 종료의 선형화 경계를 보존한다` | coordinator/race/lifecycle tests |
| Task 6~7 | `SQS 배치 bean과 관측성을 기존 주입 경계에 추가한다` | context/ABI/Micrometer tests |
| Task 8~9 | `SQS 자동 배치의 실행 예제와 운영 계약을 고정한다` | Floci/example/manual contract |
| Task 10 | `SQS 배치 구현의 통합 품질과 복구 경계를 증명한다` | module/detekt/build/review evidence |

~~~text
SQS 배치 입력과 실패를 안전한 공개 계약으로 고정한다

Constraint: 기존 SqsOperations와 SqsProperties ABI를 바꾸지 않는다
Rejected: 기존 SqsOperations 확장 | listener lifecycle과 batch buffer ownership이 결합된다
Confidence: high
Scope-risk: moderate
Directive: cancellation identity와 redacted failure 경계를 약화하지 않는다
Tested: SqsAutomaticBatchModelsTest, SqsAutomaticBatchExceptionsTest
Not-tested: Floci integration
~~~

계획·review artifact commit은 구현 전 별도 commit으로 만들며, generated
`.lane-inputs/`, `.omx/`, `.lane-evidence/`는 커밋하지 않는다.

## 명세 수용 기준 추적

| 명세 요구 | 구현 task | 검증 |
|---|---|---|
| direct/batch 동일 결과 | 2, 3, 5 | transport/template tests |
| 실제 size/flush 자동 병합 | 3, 8 | manager adapter + Floci |
| entry partial failure와 RETURN/THROW | 1, 4, 5 | models/coordinator/template |
| correlation ID와 입력 상대 순서 | 1, 4 | normalizer/coordinator tests |
| FIFO·deduplication·attributes·snapshot | 2, 4, 8 | request capture/mutable input/Floci |
| active future 상한 | 4, 10 | deterministic counters/races |
| cancellation identity와 cancel(false) | 4, 5 | real job cancellation tests |
| bounded close와 cleanup | 3, 5, 10 | lifecycle/construction rollback |
| 기존 API/ABI/listener 보존 | 0, 6, 10 | precompiled fixture/context |
| redaction | 1, 3, 5, 7 | exception/startup/close/meter allow-list |
| Base58/assertions 규칙 | 1~10 | test source review/detekt |
| README/manual/KDoc/Issue 일치 | 9, 11 | compiled example/manual contract/live read-back |

## 계획 DoD

- [x] 여섯 독립 plan review에서 P0=0/P1=0이다.
- [x] 모든 명세 요구가 선행 artifact 없이 실행 가능한 task와 exact command에 연결된다.
- [x] RED가 해당 production 변경보다 앞서고 cancellation/race/lifecycle/ABI가 포함된다.
- [x] Kotlin, Spring Boot, coroutine, bluetape4k-assertions와 writer 계약을 반영했다.
- [x] 계획·통합 review를 Lore commit으로 보존했다.
- [ ] 사용자가 구현 계획을 승인했다.

**상태: PENDING — Step 3-R은 PASS이며 사용자 구현 계획 승인이 남아 있다.**
