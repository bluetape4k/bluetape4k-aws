# SQS batch listener 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `test-driven-development` and `bluetape-kotlin-patterns` while implementing this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `@SqsListener(batch = true)`가 하나의 SQS 수신 응답을 coroutine-native batch handler로 전달하고, 전체·부분 acknowledgement와 항목별 재배달을 단건 listener 호환성을 유지한 채 제공한다.

**Architecture:** 기존 `SqsMessageListenerContainer`를 확장해 batch endpoint만 별도 경로로 라우팅한다. `SqsBatchAcknowledgement`는 batch 소유권과 항목별 상태를 `Mutex`로 직렬화하고, `SqsOperations.deleteBatch`/`changeVisibilityBatch`는 기존 구현체를 위한 단건 fallback과 AWS SDK batch 최적화 경로를 모두 제공한다. AWS receipt handle은 내부 mapping에만 두고 공개 결과·로그·metric tag에는 노출하지 않는다.

**Tech Stack:** Kotlin 2.4, Spring Boot 4, kotlinx-coroutines, AWS SDK v2 `SqsAsyncClient`, Jackson `ObjectMapper`, Micrometer, JUnit 5, MockK, Kluent, Testcontainers/Floci.

---

## 작업 규칙과 소유 범위

- 구현 전에 `$bluetape-kotlin-patterns`, `$test-driven-development`와 해당 reference인 `checklist.md`, `spring-boot.md`, `testing.md`를 다시 읽는다.
- 모든 Kotlin 변경은 `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/issue-454-sqs-batch-listener`에서만 수행한다. `develop` checkout은 수정하지 않는다.
- 새 dependency, Spring Integration, awspring을 추가하지 않는다. AWS service SDK compileOnly 정책과 기존 `aws-java` batch coroutine helper를 재사용한다.
- receipt handle·message body·raw message id를 로그, `toString`, trace attribute, metric tag에 넣지 않는다. 기존 `AwsMicrometerSupport`의 bounded queue-name helper를 재사용하되, batch의 canonical allowlist(`listener.id`, `queue.name`, `operation`, `outcome`, `batch.size.bucket`, `implementation.path`)만 허용한다.
- 공개 결과의 `successfulMessageIds`는 API 조회용으로만 제공하고, 결과·실패·예외 타입의 `toString()`은 body, receipt handle, message id, queue URL, SDK detail/cause를 포함하지 않도록 명시적으로 redaction한다. logger/observation/meter capture negative assertion을 구현 전에 계획한다.
- Jackson 변환은 기존 `ObjectMapper` 설정 경계를 재사용하되 `Any`/`Object`/`Serializable` broad target, polymorphic `@class`/default-typing metadata, Java serialization을 허용하지 않는 negative test를 둔다.
- Floci/Testcontainers 실행은 Docker 자원을 공유하므로 한 번에 한 Gradle invocation으로 순차 실행한다.
- `aws-spring-boot`의 `-PskipAwsEmulatorTests=true`는 `**/*AwsEmulatorTest.class`를
  제외하는 명시적인 build guard로 구현한다. Task 7의 Floci 증거를 만든 뒤 Task 9의 module/
  repository test와 `build`에는 이 property를 반드시 전달해 emulator를 재실행하지 않는다.
- 각 기능 단위는 RED 테스트 → 최소 구현 → GREEN 테스트 → diff 점검 순서로 진행하고, 의미 있는 단위마다 Lore commit을 만든다.
- batch hot path는 poller별 in-flight 상한, AWS round-trip 수, fallback/optimized 경로, allocation·metric 비용을 fake 기반으로 측정한다. `concurrency=1`과 `concurrency>1`, batch size 1/10, Micrometer on/off를 모두 포함한다.
- rollback은 annotation 재배포 또는 별도 canary endpoint 전환을 제어면으로 삼고, 이미 삭제된 메시지는 복구하지 않는다. 정적 annotation을 단순히 `batch=false`로 runtime toggle한다고 표현하지 않으며, `STOPPING_RECEIVE → DRAINING → STOPPED` 후 이전 단건 handler 배포/dual handler 전환, redrive/DLQ, idempotency 확인 순서를 runbook에 고정한다. stop timeout, 승인자와 온콜 owner도 기록한다.

## 변경 파일 지도

| 책임 | 생성/수정 파일 | 검증 산출물 |
|---|---|---|
| annotation·endpoint 계약 | `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt`, `SqsAcknowledgementMode.kt`(생성), `SqsListenerEndpoint.kt`, `SqsListenerBatchCorrelation.kt`(생성), `SqsListenerAnnotationBeanPostProcessor.kt` | `SqsAutoConfigurationTest.kt`의 mode/maxMessages/invalid signature 회귀 |
| batch 공개 모델과 상태 | `SqsBatchAcknowledgement.kt`(생성), `SqsBatchModels.kt`(생성), `SqsMessageConversionException`, `SqsBatchDeleteProtocolException`, `SqsBatchVisibilityProtocolException`, `SqsBatchAcknowledgementFailure`·`SqsBatchAcknowledgementOperation` | `SqsBatchAcknowledgementTest.kt`(생성), 결과 ordering/redaction/state evidence |
| SQS batch operation | `SqsOperations.kt`, `SqsCoroutinesTemplate.kt`, `MicrometerSqsOperations.kt` | `SqsOperationsBatchTest.kt`(생성), `MicrometerSqsOperationsTest.kt`, emulator template test |
| payload resolver | `SqsListenerMethodInvoker.kt`, `SqsMessageConverter.kt`, `SqsMessageConversionException.kt`(생성) | `SqsListenerMethodInvokerTest.kt`(생성), `SqsAutoConfigurationTest.kt` |
| container runtime | `SqsMessageListenerContainer.kt`, `SqsMessageListenerContainerRegistry.kt`, `SqsListenerInterceptor.kt`, `MicrometerSqsListenerInterceptor.kt`(필요 시) | `SqsMessageListenerContainerTest.kt`, `SqsMessageListenerContainerRegistryTest.kt`, interceptor/metric assertions |
| test doubles/compatibility | `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/NoopSqsOperations.kt`, recording fake 및 precompiled ABI fixture | module tests, old implementation fixture |
| test execution guard | `aws-spring-boot/build.gradle.kts` | `-PskipAwsEmulatorTests=true`가 `**/*AwsEmulatorTest.class`를 제외하는지 검증 |
| emulator proof | `SqsListenerAwsEmulatorTest.kt`, `SqsCoroutinesTemplateAwsEmulatorTest.kt`, `SqsBatchListenerAwsEmulatorTest.kt`(생성) | Floci success/partial/FIFO/redelivery evidence |
| fake performance proof | `SqsBatchPerformanceTest.kt`(생성), `SqsBatchPerformanceRuntimeAdapter.kt`(생성·Task 5에서 batch 경로 연결) | baseline/candidate p95·allocated-bytes·metric allocation artifact |
| 사용자 문서 | `aws-spring-boot/README.md`, `README.ko.md`, `docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`, `docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md` | manual parity/manifest/contract checks |
| public KDoc/API examples | `SqsListener.kt`, `SqsListenerBatchCorrelation.kt`, `SqsListenerInterceptor.kt`, `SqsBatchAcknowledgement.kt`, `SqsOperations.kt`, `SqsBatchModels.kt` | Dokka/source contract, correlation-aware consumer fixture, and README/manual parity checks |
| durable research/lesson | `/Users/debop/work/bluetape4k/bluetape4k-wiki/research/2026-08-11-aws-sqs-batch-listener.md`, `docs/lessons/2026-08-11-sqs-batch-listener.md` | wiki validation, lesson Lore commit |

## Task 0: feature 전 baseline 성능 harness를 고정한다

**Files:**

- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchPerformanceTest.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchPerformanceRuntimeAdapter.kt`
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainer.kt`
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListenerMethodInvoker.kt`
- Create evidence: `.bluetape/evidence/issue-454/perf/baseline-commit.txt`,
  `baseline-commit.json`, `baseline-raw-samples.json`, `baseline-summary.json`

- [ ] **Step 1: RED/GREEN — production runtime seam과 fake harness를 함께 고정한다.**

  먼저 `SqsMessageListenerContainer`와 `SqsListenerMethodInvoker`에 trailing default가
  `Dispatchers.IO`인 내부 `CoroutineDispatcher` 주입 seam을 추가한다. container의 generation/
  stop scope와 invoker의 suspend/blocking invocation이 모두 이 dispatcher를 사용하게 하되,
  production default와 auto-configuration wiring은 바꾸지 않는다. 이 seam은 Task 0의 baseline
  부터 존재해야 하며 Task 5에서 처음 만들거나 worker 집합을 바꾸지 않는다. 이후 fake clock,
  recording `SqsOperations`, batch size 1/10, optimized/fallback delete·visibility,
  Micrometer on/off, concurrency 1/N, max-in-flight와 duplicate ack call count를 측정한다.
  fake는 AWS client boundary에만 두고, 실제 `SqsMessageListenerContainer`와
  `SqsListenerMethodInvoker`를 호출하는 `PerformanceRuntimeAdapter`를 통해 baseline 단건
  path를 실행한다. Task 5는 같은 adapter SPI와 동일한 test-owned dispatcher에 batch path를
  연결한다. allocation은
  baseline/candidate 양쪽에서 동일하게 수집한 test-owned dispatcher worker ID 전체에 대해
  `com.sun.management.ThreadMXBean`의
  `getThreadAllocatedBytes`를 합산하고, timer/span allocation은 recording interceptor로
  센다. bean이 비활성화됐거나 worker ID를 수집하지 못하면 측정을 PASS로 처리하지 않는다.
  harness test source는 이 task 이후 수정하지 않고, runtime adapter path(`single`/`batch`)와
  adapter source hash만 candidate artifact에 별도로 기록한다. baseline raw/summary artifact에는
  test-owned dispatcher의 구현 식별자, configured parallelism, captured worker count와
  allocation에 실제 사용한 worker ID 목록을 기록한다. worker ID 숫자는 JVM 실행마다 달라질 수
  있으므로 candidate 비교는 ID equality가 아니라 dispatcher 구성과 worker count를 비교하고,
  각 실행의 ID 목록은 추적용으로만 보존한다. 이 테스트는 저장소에
  `kotlinx.benchmark` 모듈/플러그인이 없으므로 JUnit 기반의 통제된 성능 회귀 harness로
  분류한다. 일반적인 microbenchmark 또는 절대 성능 주장을 하지 않고, 동일 JVM·dispatcher·
  warmup/measurement 조건의 baseline 대비 회귀 gate만 보고한다.

- [ ] **Step 2: feature 전 baseline을 pin한다.**

  harness만 포함한 Lore commit 직후 `BASELINE_COMMIT=$(git rev-parse HEAD)`를
  `.bluetape/evidence/issue-454/perf/baseline-commit.txt`에 기록한다. 다음 동일 command를
  feature 구현 전에 실행해 `baseline-commit.json`(commit, `measurementKind=controlled-regression`,
  harness test-source SHA-256, `runtimePath=single`, adapter source SHA-256, JVM, Kotlin,
  dispatcher configuration/worker count/worker IDs, warmup=3, measurement=10, samples,
  p95, allocatedBytesPerOp)을 만든다.

  ```bash
  ./gradlew --no-daemon --max-workers=1 --no-parallel :bluetape4k-aws-spring-boot:test \
    --tests "io.bluetape4k.aws.spring.sqs.SqsBatchPerformanceTest"
  ```

  baseline은 실제 listener의 기존 single production path를 측정하며, no-matching-test나
  누락된 harness artifact는 즉시 FAIL이다. 이후 Task 1–8 구현이 이 baseline commit을
  candidate 비교의 immutable 기준으로 사용한다. artifact에는
  `measurementKind=controlled-regression`과 dispatcher configuration/worker count/worker IDs를
  기록한다.

- [ ] **Step 3: 커밋한다.**

  커밋은 `#454 feature 전 SQS batch 성능 harness를 고정한다`로 만들고, baseline artifact가
  생성되지 않으면 Task 0 Step 1부터 재실행한다.

## Task 1: 계약 모델과 endpoint 해석을 먼저 고정한다

**Files:**

- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsAcknowledgementMode.kt`
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListenerEndpoint.kt`, `SqsListenerAnnotationBeanPostProcessor.kt`
- Test: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsAutoConfigurationTest.kt`

- [ ] **Step 1: RED — mode와 maxMessages 계약 테스트를 추가한다.**

  다음을 `ApplicationContextRunner`와 annotation fixture로 고정한다.

  ```kotlin
  @Test
  fun `batch endpoint inherits property maxMessages and rejects more than ten`() {
      // ApplicationContextRunner: property 7 resolves to endpoint.maxMessages == 7;
      // property 11 fails startup with the maxMessages range fragment.
  }

  @Test
  fun `inherit mode maps acknowledgement parameter to manual`() {
      // endpoint.acknowledgementMode shouldBe SqsAcknowledgementMode.MANUAL
  }

  @Test
  fun `on success rejects manual acknowledgement parameter`() {
      // context failure message shouldContain "ON_SUCCESS cannot declare SqsAcknowledgement"
  }
  ```

  RED는 endpoint resolver가 아직 batch/mode를 모르므로 위 assertion 중 하나가 실패하거나 endpoint가 생성되지 않는 상태다. 실패 메시지는 구현 전에 승인 spec의 stable fragment인 `batch=true requires a List payload`, `batch=false does not accept List payload`, `SqsBatchAcknowledgement requires batch=true`, `ON_SUCCESS cannot declare SqsAcknowledgement`, `MANUAL requires SqsBatchAcknowledgement`, `batch delete supports at most 10 messages` 중 계약에 맞는 값으로 고정한다.

- [ ] **Step 2: GREEN — annotation/endpoint 해석을 구현한다.**

  `batch: Boolean = false`, `acknowledgementMode: SqsAcknowledgementMode = INHERIT`를 annotation trailing default로 추가한다. `maxMessages = -1`은 `SqsProperties.Listener.maxMessages`를 상속하고, 최종 batch 값은 `1..10`만 허용한다. `INHERIT`는 acknowledgement parameter 유무로 `MANUAL`/`ON_SUCCESS`를 결정하며 명시적 충돌은 context 초기화에서 `IllegalArgumentException`으로 거부한다. endpoint에는 resolved mode와 batch flag를 저장한다.
  mode matrix는 `INHERIT` 무ack, `INHERIT` + 단건 ack, `INHERIT` + batch ack, 명시적
  `ON_SUCCESS` 무ack, 명시적 `ON_SUCCESS` + ack, 명시적 `MANUAL` + batch ack, 명시적
  `MANUAL` 무ack/단건 ack를 모두 포함하고, stable fragment는 승인 spec의 목록과 정확히
  일치시킨다. `maxMessages` annotation/property의 `-1`, `0`, `-2`, `1`, `10`, `11` 경계를
  같은 matrix로 검증한다.

- [ ] **Step 3: 검증한다.**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.sqs.SqsAutoConfigurationTest"
  ```

  기대 결과: 기존 단건 listener 테스트가 모두 PASS하고 신규 mode/maxMessages/invalid signature 테스트가 PASS한다. 실패하면 Task 1 범위에서 수정하고 다음 task로 진행하지 않는다.

- [ ] **Step 4: 커밋한다.**

  ```bash
  git add aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt \
    aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsAcknowledgementMode.kt \
    aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListenerEndpoint.kt \
    aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListenerAnnotationBeanPostProcessor.kt \
    aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsAutoConfigurationTest.kt
  git commit -m "#454 SQS batch endpoint 계약을 고정한다"
  ```

  커밋 본문에는 Lore trailers를 포함한다. 이 단계의 rollback은 개발/API rollback으로서 해당
  커밋을 revert하고 annotation 기본값만 유지하는 것이다. 운영 rollback은 Task 8의 stop→drain→
  이전 단건 handler 재배포 절차를 따른다.

## Task 2: 공개 batch 모델과 단일 소유 상태 머신을 구현한다

**Files:**

- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchModels.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchAcknowledgement.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchAcknowledgementTest.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsAcknowledgementTest.kt` only when shared fake signatures require it

- [ ] **Step 1: RED — 상태와 결과 불변식을 테스트한다.**

  fake `SqsOperations`에 호출 횟수와 receipt/entry를 기록하고 다음 테스트를 작성한다.

  ```kotlin
  @Test fun `acknowledge deletes all pending and completes`() = assertInputOrderAndCompleted()
  @Test fun `partial delete keeps failed item pending`() = assertPartialFailureAndPending()
  @Test fun `nack success becomes deferred and does not delete`() = assertDeferredWithoutDelete()
  @Test fun `concurrent duplicate ack is linearized`() = assertOneAwsActionPerHandle()
  @Test fun `foreign duplicate and eleven-item inputs fail before AWS`() = assertValidationAndZeroCalls()
  @Test fun `fifo predecessor blocks later acknowledgement`() = assertFailureCode("fifo_predecessor_pending")
  @Test fun `transport cancellation and protocol mismatch are rethrown`() = assertPendingUnconfirmed()
  ```

  각 테스트는 `successfulMessageIds`의 입력 순서, `completed == false`와 실패 항목의
  `pending` 잔존, handle별 AWS 호출 횟수 1회, 실패 전 fake 호출 0회, 그리고
  `fifo_predecessor_pending` 코드를 직접 assert한다. `acknowledge`, `nack`,
  `changeVisibility` 각각에 대해 다른 queue URL·다른 batch token·위조 message ID/receipt
  조합과 mutable collection 입력을 넣고 모두 AWS 호출 0회로 끝나는지 별도 parameterized
  case로 고정한다.

  `pending`, `successfulMessageIds`, `failed`를 caller가 복사·변경해도 내부 상태가 변하지
  않는 defensive snapshot을 assert하고, `ACKNOWLEDGE` 결과를 `NACK`/`CHANGE_VISIBILITY`
  반복 호출에 재사용하지 않는 operation truth table을 추가한다. timeout `-1`, `43_201`은
  `timeoutSeconds must be between 0 and 43200`와 AWS 호출 0회로 거부한다.

  결과·실패·예외의 `toString()`과 logger/observation/meter capture에는 body, receipt handle,
  message ID, queue URL, SDK detail/cause가 없음을 직접 assert한다. API 조회 필드인
  `successfulMessageIds`는 객체를 직접 출력하지 않고 명시적 accessor로만 검증한다.

- [ ] **Step 2: GREEN — result/state contracts를 구현한다.**

  `SqsBatchAcknowledgementResult`는 operation, status, `successfulMessageIds`, failure 목록을 유지하고 raw receipt handle/Serializable을 노출하지 않는다. operation은 `ACKNOWLEDGE`, `NACK`, `CHANGE_VISIBILITY` 중 하나로 고정하고, 다른 operation의 terminal cache는 재사용하지 않는다. 각 item을 `PENDING`, `IN_FLIGHT`, `ACKED`, `DEFERRED`로 관리하며 `Mutex.withLock` 안에서 ownership key(queue URL + batch token), max 10, duplicate receipt/message ID, FIFO predecessor를 검증한다. 구현은 2단계 규칙을 따른다: Mutex 안에서 `PENDING -> IN_FLIGHT` reservation과 호출 계획을 확정하고, AWS/interceptor 같은 외부 suspend IO는 lock 밖에서 수행한 뒤, 다시 Mutex 안에서 성공 commit 또는 실패 rollback을 한다. `ACKED`/`DEFERRED` 반복 호출은 같은 operation의 cached result를 반환하고 실패/unknown은 `PENDING`으로 되돌린다. `pending`과 result 컬렉션은 defensive read-only snapshot으로 반환한다. `changeVisibility`도 동일 결과 타입을 반환하며 `timeoutSeconds`는 0..43_200만 허용하고 범위 오류는 `timeoutSeconds must be between 0 and 43200` fragment로 fail-fast한다. `CancellationException`은 절대 삼키지 않는다.

- [ ] **Step 3: 검증한다.**

  ```bash
  ./gradlew --no-daemon --max-workers=1 --no-parallel :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.sqs.SqsBatchAcknowledgementTest"
  ```

  기대 결과: 호출 횟수, pending snapshot, completed monotonicity, result order, FIFO prefix와 raw handle redaction 테스트가 모두 PASS한다.

- [ ] **Step 4: 커밋하고 실패 시 재실행 지점을 기록한다.**

  커밋은 `#454 SQS batch acknowledgement 상태를 고정한다`로 만들고, 상태 머신 실패는 Task 2 RED 테스트부터 재실행한다. 이 단계의 API rollback은 신규 batch 타입과 endpoint flag를 제거하고 단건 경로만 남기는 개발 조치이며, production rollback과 혼동하지 않도록 Task 8 runbook을 참조한다.

## Task 3: `SqsOperations` delete/visibility batch fallback·AWS batch·Micrometer를 연결한다

**Files:**

- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsOperations.kt`, `SqsCoroutinesTemplate.kt`, `MicrometerSqsOperations.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsOperationsBatchTest.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/MicrometerSqsOperationsTest.kt`, `NoopSqsOperations.kt` if required by compiler

- [ ] **Step 1: RED — fallback, SDK mapping, protocol validation, metric을 테스트한다.**

  ```kotlin
  @Test fun `default deleteBatch falls back to single delete in input order`() = assertFallbackCallsAndEntryIds()
  @Test fun `template sends one DeleteMessageBatch request`() = assertSingleSdkRequestWithTenEntries()
  @Test fun `successful and failed SDK ids map without receipt leakage`() = assertMixedResultWithoutReceipt()
  @Test fun `unknown duplicate missing SDK id fails closed`() = assertProtocolException()
  @Test fun `micrometer records delete_batch with bounded tags`() = assertBoundedDeleteMetric()
  @Test fun `optimized and fallback paths expose only bounded implementation path`() = assertImplementationPathTag()
  @Test fun `metric contract uses listener dot id and rejects listenerId`() = assertCanonicalListenerIdTag()
  ```

  `changeVisibilityBatch`의 optimized SDK 호출 1회와 default 단건 fallback의 호출 순서/상한도
  같은 RED 묶음에 추가한다. auth/permission, network/timeout, unknown SDK exception,
  `CancellationException`, 명시적 SDK item failure를 각각 fake로 만들고, delete와 visibility
  모두 transport/unknown/cancellation은 재전파하며 명시적 item failure만 결과에 담아 앞서
  확정된 성공을 보존하고 미확인 항목을 pending으로 남기는지 검증한다. logger capture에는
  exception cause, AWS detail, receipt handle이 없음을 assert한다.

- [ ] **Step 2: GREEN — operation 계약을 구현한다.**

  `SqsOperations`에 source/binary 호환성을 위한 `deleteBatch(queueUrl, receiptHandles)`와
  `changeVisibilityBatch(queueUrl, requests)` default method를 추가한다. empty는 no-op,
  >10/duplicate는 validation error, `changeVisibilityBatch` request의 empty/duplicate/>10/
  timeout(-1, 43_201)도 동일하게 AWS 호출 전에 거부하며 stable fragment를 고정한다.
  single fallback은 명시적 item 오류만 failure로 수집하고
  transport/unknown/cancellation은 재전파한다. `SqsCoroutinesTemplate`은 기존 `aws-java`의
  `SqsAsyncClient.deleteMessageBatch`/`changeMessageVisibilityBatch` extension과 model helper를
  재사용하고 입력 순서의 공개 `entry-0..entry-9` mapping과 `List<String>` result 순서를 검증한다. SDK response의 Successful/Failed ID
  집합이 입력 entry 집합과 정확히 일치하지 않으면 fail-closed typed protocol exception을
  던진다. `MicrometerSqsOperations`는 `delete_batch`/`change_visibility_batch` operation으로
  위임하고 body/receipt/message id를 tag로 만들지 않는다. metric은 `listener.id`, bounded
  `queue.name`, `operation`, `outcome`, bounded `batch.size.bucket`, bounded
  `implementation.path`(`optimized`/`fallback`)만 허용하고 invocation/item/partial failure
  집계 단위를 명시한다.

- [ ] **Step 3: 검증한다.**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.sqs.SqsOperationsBatchTest" --tests "io.bluetape4k.aws.spring.sqs.MicrometerSqsOperationsTest"
  ```

  기대 결과: fallback N회와 AWS template 1회, visibility batch fallback/optimized 호출 상한이
  각각 증명되고, AWS response contract mismatch가 terminal 상태로 반영되지 않는다. 전용
  Micrometer/interceptor test command에서 batch size 1/10, partial failure, retry exhaustion,
  visibility failure, cancellation의 bounded tags/metric names와 raw 값 부재를 검증한다.
  `listener.id`가 유일한 listener tag key이고 legacy `listenerId`가 어떤 batch metric/trace에도
  나타나지 않는 contract assertion을 함께 둔다.

  canonical metric 계약은 `bluetape4k.sqs.batch.invocations`(counter),
  `bluetape4k.sqs.batch.acknowledgements`(counter),
  `bluetape4k.sqs.batch.handler.duration`(timer), `bluetape4k.sqs.batch.retry`(counter),
  `bluetape4k.sqs.batch.partial.failures`(counter),
  `bluetape4k.sqs.batch.visibility.failures`(counter),
  `bluetape4k.sqs.batch.cancellations`(counter),
  `bluetape4k.sqs.batch.redelivery.age`(histogram)으로 고정한다. 허용 tag는
  `listener.id`, 설정된 논리 queue의 bounded `queue.name`, `operation`, `outcome`,
  `batch.size.bucket`(`0`, `1`, `2-5`, `6-10`)와 최적화 경로를 구분하는
  `implementation.path`(`optimized`/`fallback`)뿐이다. invocation은 poll response 1회,
  acknowledgement는 public ack/nack/visibility call 1회이며 item 결과를 별도 counter로
  중복 집계하지 않는다. redelivery age source는 SQS
  `ApproximateFirstReceiveTimestamp`, DLQ visible count source는
  `ApproximateNumberOfMessagesVisible`이다. partial failure/visibility failure/retry
  exhaustion/redelivery-age/DLQ alert의 threshold와 온콜 owner는 Task 8 runbook의 고정
  표를 사용하고, 테스트에서 schema와 negative redaction을 검증한다.

- [ ] **Step 4: 커밋한다.**

  커밋은 `#454 SQS DeleteMessageBatch 경로를 추가한다`로 만들며 AWS request/response mapping failure 시 Task 3 RED 테스트부터 재실행한다.

## Task 4: batch payload resolver와 converter 오류를 추가한다

**Files:**

- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListenerMethodInvoker.kt`, `SqsMessageConverter.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsListenerMethodInvokerTest.kt`

- [ ] **Step 1: RED — supported/unsupported parameter shapes를 고정한다.**

  `List<SqsReceivedMessage>`, `List<Message>`, `List<String>`, `List<OrderPayload>`와 optional `SqsBatchAcknowledgement`의 arguments를 검증한다. raw Java `List`, `List<*>`, `List<T>`, `List<OrderPayload?>`, `List<List<OrderPayload>>`, batch=false List, two payloads, single `SqsAcknowledgement` in batch는 stable error fragment와 함께 거부되어야 한다. `Any`, `Object`, `Serializable` broad target와 `{"@class":"..."}`/polymorphic default-typing metadata, Java serialization payload도 거부하거나 concrete DTO 변환으로만 처리되는지 negative test한다. converter가 index·target type을 받는지, body/receipt/metadata/cause가 exception text·logger에 없는지도 검증한다.

- [ ] **Step 2: GREEN — KType resolver와 batch invocation을 구현한다.**

  `KFunction.valueParameters`의 `KType`를 사용해 invariant non-null concrete element `Class<T>`만 추출하고, endpoint 초기화 때 resolver 결과를 캐시한다. converter는 각 message body를 해당 class로 변환한다. `List<SqsReceivedMessage>`/`List<Message>`는 direct mapping하고, raw/nullable/nested/wildcard/broad target는 `IllegalArgumentException`으로 fail-fast한다. `JacksonSqsMessageConverter`는 caller가 제공한 ObjectMapper의 safe configuration을 재사용하되 default typing/Java serialization을 활성화하지 않으며, hostile metadata가 concrete DTO allowlist를 우회하지 않는지 검증한다. `invokeBatch(messages, batchAcknowledgement?)`는 one payload + optional acknowledgement만 전달하며 sync handler는 `runInterruptible(Dispatchers.IO)`, suspend handler는 기존 `withContext(Dispatchers.IO)` 경계를 유지한다.
  `SqsMessageConversionException`은 public constructor `(index: Int, targetType: Class<*>, cause: Throwable)`와 redacted `message`를 갖고, `SqsBatchDeleteProtocolException`·`SqsBatchVisibilityProtocolException`은 제출/응답 entry ID 집합을 raw handle 없이 보존하는 public typed exception으로 고정한다. 각 타입의 visibility, constructor와 exception text를 compile/runtime assertion으로 검증한다.

- [ ] **Step 3: 검증한다.**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.sqs.SqsListenerMethodInvokerTest"
  ```

  기대 결과: supported list examples compile/run, invalid generic examples fail-fast, `CancellationException` propagation이 PASS한다.

- [ ] **Step 4: 커밋한다.**

  커밋은 `#454 SQS batch payload resolver를 추가한다`로 만들고 converter signature 변경이 기존 single conversion을 깨면 single path regression부터 복구한다.

## Task 5: container에서 batch invocation·retry·stop generation을 연결한다

**Files:**

- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainer.kt`, `SqsListenerInterceptor.kt`, `MicrometerSqsListenerInterceptor.kt` if batch counters need an extension
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainerTest.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchPerformanceRuntimeAdapter.kt` to route the unchanged harness to the batch production path

- [ ] **Step 1: RED — runtime lifecycle와 mode semantics를 테스트한다.**

  ```kotlin
  @Test fun `batch receive invokes handler once with ten messages`() = assertSingleBatchInvocation()
  @Test fun `empty receive skips invocation`() = assertNoInvocationAndNextPoll()
  @Test fun `on success deletes pending and manual never auto deletes`() = assertModeOperationCalls()
  @Test fun `retry invokes only unacknowledged pending items`() = assertPendingOnlyRetryWithAttemptOne()
  @Test fun `stop cancels receive drains handler and blocks stale generation callback`() = assertLifecycleStateOrder()
  @Test fun `cancellation propagates without starting new ack or visibility`() = assertCancellationPropagation()
  @Test fun `fatal error in receive converter handler ack or visibility stops without retry`() = assertFatalPhasePolicy()
  @Test fun `registry rejects start during asynchronous stop and invokes callback once`() = assertRegistryStopStartRace()
  ```

  barrier fake로 `concurrency=1`과 `concurrency=3`에서 `maxInFlightBatch <= poller 수`를
  assert하고, handler/ack가 끝나기 전 다음 receive가 시작되지 않는지 확인한다. batch size
  1/10, optimized/fallback operation, Micrometer on/off를 각각 실행해 handler invocation,
  ack/item, partial 결과의 집계 단위를 분리한다. `SqsListenerBatchCorrelation(generation,
  pollerId, batchSequence)`를 before/after receive와 batch acknowledgement의
  correlation-aware interceptor overload로 전달하고, 기존 overload는 Kotlin default bridge로
  보존한다. built-in Micrometer는 batch-level metric/span만 기본 생성하며 기존
  per-message callback은 호환성 호출만 하고 timer/span은 만들지 않는다. custom interceptor가
  명시적으로 per-message observation을 선택한 경우만 N개를 생성한다.

  receive loop는 `CancellationException`을 즉시 전파하고, 일반 transport 예외는 batch
  `maxAttempts`와 분리된 bounded receive backoff로 재시도한다. `Error`/fatal throwable은
  retry하지 않고 listener를 중지한 뒤 상위로 전파하며, busy poll churn이 없어야 한다.
  receive-failure, cancellation, fatal-error 회귀 테스트에서 이 정책과 bounded logging을
  고정한다. `CancellationException`과 `Error`는 receive·converter·handler·delete·visibility
  어느 phase에서도 retry/backoff·error visibility·새 acknowledgement를 시작하지 않고 원래
  throwable을 전파한다. fatal handler test는 no-retry/no-duplicate-ack/no-new-AWS-call을
  assert한다.

  `start()`는 `STOPPING_RECEIVE` 또는 `DRAINING` 중이면 새 generation을 만들지 않고
  `IllegalStateException("listener is stopping")`으로 거부한다. callback 이후 상태가
  `STOPPED`가 된 뒤에만 새 generation을 시작하며, old generation의 receive/AWS call은 0회,
  callback은 exactly-once여야 한다. `SqsMessageListenerContainerRegistry`도 같은 gate를
  원자적으로 적용해 asynchronous stop callback 중 registry-level `start(id)`를 거부하고,
  `running=false`와 새 generation 생성은 STOPPED callback 뒤에만 허용한다.

- [ ] **Step 2: GREEN — poll loop와 batch handler를 구현한다.**

  `messages.forEach` 단건 경로는 유지하고 endpoint.batch만 `handleBatch(queueUrl, messages)`로 라우팅한다. 한 poll result는 한 handler invocation이고 poller 하나당 in-flight batch 하나다. `BatchAttemptContext`가 batch acknowledgement owner와 attempt budget을 재시도 간 유지하며, attempt 1은 최초 handler invocation이다. `handler/converter/delete/visibility` 실패가 같은 budget을 공유하고 receive transport failure는 batch budget에 포함하지 않는 별도 bounded backoff 정책을 사용한다. `maxAttempts=1/2`, partial ack 후 handler 예외, delete transport failure, visibility failure truth table을 테스트한다. resolved mode가 `ON_SUCCESS`이면 handler 정상 반환 후 pending을 `deleteBatch`하고, `MANUAL`이면 handler가 호출한 acknowledgement만 반영한다. 일반 `Exception`만 기존 retry/backoff와 attempt 1 semantics를 사용하고 terminal 성공/deferred 항목은 재시도 목록에서 제거한다. `CancellationException`과 `Error`는 모든 phase에서 즉시 stop·전파하며 retry/visibility/새 ack를 금지하고, fatal path에서 old operation이 중복되지 않는지 assert한다. `changeVisibilityBatch`를 사용해 실패 항목 visibility를 한 번에 갱신하고, fallback 경로의 호출 상한을 테스트한다. interceptors는 correlation-aware overload와 default bridge를 유지하고, generation별 active ack/visibility operation registry와 operation-start fence를 두며 cancellation 이후 새 AWS call을 금지하고 원래 `CancellationException`을 보존한다. `RUNNING -> STOPPING_RECEIVE -> DRAINING -> STOPPED` generation token으로 stale callback을 result/metric/visibility 모두 no-op 처리한다.
  `PerformanceRuntimeAdapter`에 candidate batch path를 연결할 때도 Task 0의 동일한 fake AWS
  boundary, test-owned dispatcher worker set, handler/interceptor recording을 사용한다.
  Task 0에서 추가한 production container/invoker의 dispatcher seam에 같은 test-owned
  dispatcher를 주입한다. 실제 production path의 기본 dispatcher 선택은 변경하지 않으며,
  baseline/candidate는 같은 worker set, harness source hash와 측정 schema를 공유하고 runtime
  adapter path만 다르다.

- [ ] **Step 3: 검증한다.**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.sqs.SqsMessageListenerContainerTest"
  ```

  기대 결과: 기존 stop/restart/cancellation 테스트와 신규 batch invocation/manual/partial/retry/FIFO-prefix lifecycle tests가 모두 PASS한다. 모든 receive/handler/ack/visibility job과 interceptor timing map은 `finally`에서 deregister하고, forced stop 뒤 registry와 map이 empty인지 assert한다. stop test는
  `STOPPING_RECEIVE -> DRAINING -> STOPPED`, `stopTimeoutMillis`, in-flight handler/ack/visibility
  join, timeout 강제 취소, stale generation callback exactly-once/no-op을 순서대로 assert한다.

- [ ] **Step 4: 커밋한다.**

  커밋은 `#454 SQS batch listener lifecycle을 연결한다`로 만들며 timing failure가 발생하면 재시도하지 말고 fake scheduler/barrier로 재현한 뒤 Task 5 RED부터 다시 검증한다.

## Task 6: Spring Boot discovery·ABI·single-path regression을 확인한다

**Files:**

- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainerRegistry.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsAutoConfigurationTest.kt`, `SqsAcknowledgementTest.kt`
- Modify: `aws-spring-boot/build.gradle.kts` to add the `skipAwsEmulatorTests` test filter
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsOperationsBinaryCompatibilityTest.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainerRegistryTest.kt`
- Create: `aws-spring-boot/src/consumerFixture/kotlin/io/bluetape4k/aws/spring/sqs/LegacySqsOperationsFixture.kt` and a Gradle fixture compile task that consumes a pre-change `SqsOperations` ABI jar
- Create: `aws-spring-boot/src/consumerFixture/kotlin/io/bluetape4k/aws/spring/sqs/LegacySqsListenerAnnotationFixture.kt`, `LegacySqsListenerInterceptorFixture.kt`, and corresponding isolated compile tasks
- Inspect/modify only if required: `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `SqsAutoConfiguration.kt`

- [ ] **Step 1: RED — positive/negative classpath와 precompiled implementation을 검증한다.**

  `ApplicationContextRunner`에서 AWS `Message`/Sqs client classpath의 positive auto-configuration, `FilteredClassLoader`로 `Message` 또는 SQS client를 제거한 negative path, existing registry/phase ordering을 확인한다. 새 `deleteBatch`를 전혀 컴파일하지 않은 pre-change `SqsOperations` ABI jar와 old annotation consumer fixture를 별도 compile task로 먼저 고정하고, 새 API runtime에서 default fallback 호출이 linkage error 없이 동작해야 한다. 기존 single `SqsAcknowledgement` delete/changeVisibility, JSON DTO listener, `INHERIT` source behavior가 변하지 않아야 한다.
  `List<Message>`의 compileOnly owner는 `aws-spring-boot` module compileClasspath와
  `@ConditionalOnClass(name = "software.amazon.awssdk.services.sqs.model.Message")` guard를
  `SqsAsyncClient` guard와 함께 projects/dependency report로 확인하고, optional class를 제거한 각 auto-configuration
  phase에서 bean registration/linkage error가 없는지 fixture classloader로 assert한다.
  old interceptor fixture와 새 batch consumer fixture는 `SqsListenerBatchCorrelation` 생성 및
  correlation-aware overload/default bridge를 compile/runtime 검증하되 opaque 값 외의 raw
  message metadata를 참조하지 않아야 한다. registry fixture는 asynchronous `stop(id)` callback이 완료되기 전 `start(id)`를
  `IllegalStateException("listener is stopping")`으로 거부하고, STOPPED callback exactly-once,
  `running` 상태 전환, old generation AWS call 0회와 STOPPED 이후 새 generation 1회를
  검증한다.

- [ ] **Step 2: GREEN — wiring/ABI를 최소 수정한다.**

  `AutoConfiguration.imports`, `@ConditionalOnClass`, `@ConditionalOnProperty`, `SmartLifecycle` phase와 post-processor ordering을 보존한다. repository root의 `-jvm-default=enable`과 Kotlin 2.4/JVM target 설정을 fixture compile task에도 고정하고, JVM classfile에 interface default method가 존재하는지 확인한다. `SqsOperations.deleteBatch`와 `changeVisibilityBatch` default를 유지하고, annotation/interceptor precompiled consumer가 linkage error 없이 로드되는지 확인하며 불필요한 module/dependency/autoconfig를 추가하지 않는다.
  `aws-spring-boot/build.gradle.kts`의 `tasks.test`는
  `providers.gradleProperty("skipAwsEmulatorTests").map(String::toBoolean).orElse(false)`가
  true일 때만 `exclude("**/*AwsEmulatorTest.class")`를 적용한다. property가 없거나 false이면
  기존 Floci 기본값과 emulator test discovery를 유지하고, guard 자체를 dedicated Gradle
  test로 확인한다.

- [ ] **Step 3: 검증한다.**

  ```bash
  ./gradlew --no-daemon --max-workers=1 --no-parallel :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.sqs.SqsAutoConfigurationTest" --tests "io.bluetape4k.aws.spring.sqs.SqsAcknowledgementTest" --tests "io.bluetape4k.aws.spring.sqs.SqsOperationsBinaryCompatibilityTest" --tests "io.bluetape4k.aws.spring.sqs.SqsMessageListenerContainerRegistryTest"
  ./gradlew --no-daemon --max-workers=1 --no-parallel compileSqsOperationsLegacyConsumerFixture compileSqsListenerAnnotationLegacyConsumerFixture compileSqsListenerInterceptorLegacyConsumerFixture
  ```

  기대 결과: positive/negative context, filtered-classloader에서 linkage error·bean registration 없음,
  pre-change ABI jar의 old implementation/annotation/interceptor consumer compile 및 runtime fallback,
  단건 regression이 PASS한다. fixture compile은 새 `SqsOperations` source를 classpath에서
  제외한 별도 configuration으로 수행한다.

## Task 7: Floci/Testcontainers와 실제 SDK response를 검증한다

**Files:**

- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsCoroutinesTemplateAwsEmulatorTest.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsListenerAwsEmulatorTest.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchListenerAwsEmulatorTest.kt`
- Create evidence: `.bluetape/evidence/issue-454/floci/capability-gap.json`,
  `docker-info.txt`, `floci-image.json`, `template.stdout`, `template.stderr`, `template.exit`,
  `listener.stdout`, `listener.stderr`, `listener.exit`, `batch.stdout`, `batch.stderr`,
  `batch.exit`, `failure-classification.json`, `docker-info.stderr`, `docker-info.exit`,
  `floci-image.stderr`, `floci-image.exit`, `preflight.capability-marker.json`,
  `template.capability-marker.json`, `listener.capability-marker.json`,
  `batch.capability-marker.json`, `localstack.stdout`, `localstack.stderr`,
  `localstack.exit`, `localstack-capability-gap.json`, `negative/` fixture outputs and exit codes

- [ ] **Step 1: RED — emulator scenarios를 등록한다.**

  Floci 우선으로 10개 batch single invocation, ON_SUCCESS deletion, MANUAL partial ack, visibility-expiry redelivery, duplicate prevention, FIFO order/group prefix, concurrent poller lifecycle를 테스트한다. 테스트 결과는 `build/test-results/test/`와 `.bluetape/evidence/issue-454/floci/`에 exact command, Floci image/version, capability matrix, stdout/stderr 요약을 저장한다. DeleteMessageBatch item-level failure가 emulator에서 제공되지 않으면 fake Task 3/2 테스트를 authoritative로 남기되, capability-gap record에 `owner`, tracking issue, authoritative proof, unsupported behavior, expiry/recheck date, release-blocking 여부를 반드시 채운다. 고정 record 파일은 `.bluetape/evidence/issue-454/floci/capability-gap.json`이며 다음 필드를 검증한다: `issue=454`, `retrievedAt`, `emulator.name`, `emulator.image`, `emulator.version`, `command`, `capabilities[].operation/status/authoritativeProof/unsupportedBehavior/owner/trackingIssue/expiryDate/recheckDate/releaseBlocking`. owner alias는 `bluetape4k-sqs-oncall`, 승인자 alias는 `bluetape4k-release-approvers`로 고정한다. capability gap은 stdout/stderr 정규식으로 추론하지 않으며, preflight/scenario가 생성한 machine-readable marker만 인정한다. marker는 `kind=floci-capability-gap`, `status=UNAVAILABLE|UNSUPPORTED`, 정확한 `operation`, 현재 `runNonce`, 현재 명령의 `commandSha256`, 현재 실행 stdout/stderr를 가리키는 `authoritativeProof`, 해당 파일의 `proofSha256`, `unsupportedBehavior`, owner/tracking/expiry/recheck/releaseBlocking 필드를 가져야 한다. marker의 proof 경로는 해당 invocation의 고정된 stdout/stderr 경로 중 하나여야 하고, wrapper가 파일 SHA-256과 marker의 `proofSha256`를 비교한다. marker가 없거나 operation/nonce/command hash/proof 경로·hash가 불일치하는 assertion/auth/permission/프로토콜 실패는 exit 1로 fail-closed하고 LocalStack fallback을 실행하지 않는다.

- [ ] **Step 2: GREEN — 실제 SQS template/container proof를 통과시킨다.**

  테스트는 message body를 assert할 수 있지만 운영 로그/metric assertion에는 body·receipt·raw id를 사용하지 않는다. 성공 항목은 visibility 만료 후 다시 나오지 않고, 실패/미확인 항목만 redelivery 대상이어야 한다. FIFO에서 predecessor가 pending일 때 later deletion이 발생하지 않아야 한다. valid machine-readable capability marker가 있는 실행 불가만 PENDING으로 기록하고, assertion/protocol/test failure는 항상 FAIL로 남긴다. fake clock으로 visibility timeout, retry backoff, `maxAttempts=1/2`, handler duration의 조합을 표로 실행해 active batch overlap 허용 여부와 최종 실패 visibility 순서를 고정하고, overlap이 발생할 수 있는 at-least-once 구간과 idempotency/dedup 전제를 결과 artifact에 기록한다.

- [ ] **Step 3: 순차 검증한다.**

  ```bash
  set -Eeuo pipefail
  : "${FLOCI_IMAGE:?set exact Floci image reference}"
  mkdir -p .bluetape/evidence/issue-454/floci
  RUN_NONCE="${FLOCI_RUN_NONCE:-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
  export FLOCI_RUN_NONCE="$RUN_NONCE"
  mkdir -p .bluetape/evidence/issue-454/floci/stale
  for marker in .bluetape/evidence/issue-454/floci/*.capability-marker.json; do
    [[ -e "$marker" ]] || continue
    mv "$marker" ".bluetape/evidence/issue-454/floci/stale/$(basename "$marker").$RUN_NONCE"
  done
  for artifact in .bluetape/evidence/issue-454/floci/failure-classification.json \
      .bluetape/evidence/issue-454/floci/localstack-capability-gap.json \
      .bluetape/evidence/issue-454/floci/localstack.stdout \
      .bluetape/evidence/issue-454/floci/localstack.stderr \
      .bluetape/evidence/issue-454/floci/localstack.exit \
      .bluetape/evidence/issue-454/floci/*.stdout \
      .bluetape/evidence/issue-454/floci/*.stderr \
      .bluetape/evidence/issue-454/floci/*.exit; do
    [[ -e "$artifact" ]] || continue
    mv "$artifact" ".bluetape/evidence/issue-454/floci/stale/$(basename "$artifact").$RUN_NONCE"
  done
  sha256_text() { printf '%s' "$1" | sha256sum | awk '{print $1}'; }
  valid_capability_marker() {
    local marker="$1"
    local operation="$2"
    local command_hash="$3"
    local expected_proof="$4"
    local alternate_proof="${5:-}"
    local proof
    proof="$(jq -r '.authoritativeProof // empty' "$marker" 2> /dev/null || true)"
    if [[ -z "$proof" ]]; then
      return 1
    fi
    if ! [[ "$proof" == "$expected_proof" || "$proof" == "$alternate_proof" ]]; then
      return 1
    fi
    local proof_file=".bluetape/evidence/issue-454/floci/$proof"
    if ! [[ -f "$proof_file" && -s "$proof_file" ]]; then
      return 1
    fi
    local proof_hash
    if ! proof_hash="$(sha256sum "$proof_file" | awk '{print $1}')"; then
      return 1
    fi
    if [[ -z "$proof_hash" ]]; then
      return 1
    fi
    jq -e --arg operation "$operation" --arg runNonce "$RUN_NONCE" --arg commandSha256 "$command_hash" --arg proofSha256 "$proof_hash" \
      '(.kind == "floci-capability-gap" and (.status == "UNAVAILABLE" or .status == "UNSUPPORTED") and .operation == $operation and .runNonce == $runNonce and .commandSha256 == $commandSha256 and .proofSha256 == $proofSha256 and (.authoritativeProof | type == "string") and (.unsupportedBehavior | type == "string") and (.owner | type == "string") and (.trackingIssue | type == "string") and (.expiryDate | type == "string") and (.recheckDate | type == "string") and (.releaseBlocking | type == "boolean"))' \
      "$marker" > /dev/null
  }
  DOCKER_INFO_COMMAND_HASH="$(sha256_text 'docker info')"
  PREFLIGHT_COMMAND_TEXT="docker image inspect $(printf '%q' "$FLOCI_IMAGE")"
  PREFLIGHT_COMMAND_HASH="$(sha256_text "$PREFLIGHT_COMMAND_TEXT")"
  if docker info > .bluetape/evidence/issue-454/floci/docker-info.txt \
      2> .bluetape/evidence/issue-454/floci/docker-info.stderr; then
    printf '%s\n' 0 > .bluetape/evidence/issue-454/floci/docker-info.exit
  else
    status=$?
    printf '%s\n' "$status" > .bluetape/evidence/issue-454/floci/docker-info.exit
    if ! valid_capability_marker .bluetape/evidence/issue-454/floci/preflight.capability-marker.json preflight "$DOCKER_INFO_COMMAND_HASH" docker-info.stderr; then
      printf '%s\n' '{"status":"FAIL","reason":"Docker preflight failed without valid machine-readable capability marker"}' > .bluetape/evidence/issue-454/floci/failure-classification.json
      exit 1
    fi
    printf '%s\n' '{"status":"PENDING","reason":"machine-readable Floci Docker preflight capability gap"}' > .bluetape/evidence/issue-454/floci/failure-classification.json
    exit 2
  fi
  if docker image inspect "$FLOCI_IMAGE" > .bluetape/evidence/issue-454/floci/floci-image.json \
      2> .bluetape/evidence/issue-454/floci/floci-image.stderr; then
    printf '%s\n' 0 > .bluetape/evidence/issue-454/floci/floci-image.exit
  else
    status=$?
    printf '%s\n' "$status" > .bluetape/evidence/issue-454/floci/floci-image.exit
    if ! valid_capability_marker .bluetape/evidence/issue-454/floci/preflight.capability-marker.json preflight "$PREFLIGHT_COMMAND_HASH" floci-image.stderr; then
      printf '%s\n' '{"status":"FAIL","reason":"Floci image preflight failed without valid machine-readable capability marker"}' > .bluetape/evidence/issue-454/floci/failure-classification.json
      exit 1
    fi
    printf '%s\n' '{"status":"PENDING","reason":"machine-readable Floci preflight capability gap"}' > .bluetape/evidence/issue-454/floci/failure-classification.json
    exit 2
  fi
  FAILED_SCENARIOS=()
  declare -A SCENARIO_HASHES=()
  run_floci() {
    local name="$1"
    shift
    local command_text
    printf -v command_text '%q ' "$@"
    command_text="${command_text% }"
    local command_hash
    command_hash="$(sha256_text "$command_text")"
    SCENARIO_HASHES["$name"]="$command_hash"
    local stdout=".bluetape/evidence/issue-454/floci/$name.stdout"
    local stderr=".bluetape/evidence/issue-454/floci/$name.stderr"
    local exit_file=".bluetape/evidence/issue-454/floci/$name.exit"
    local status=0
    if "$@" > "$stdout" 2> "$stderr"; then
      status=0
    else
      status=$?
    fi
    printf '%s\n' "$status" > "$exit_file"
    if ((status != 0)); then FAILED_SCENARIOS+=("$name"); fi
  }
  run_floci template ./gradlew --no-daemon --max-workers=1 --no-parallel :bluetape4k-aws-spring-boot:test -Dbluetape4k.aws.emulator=floci --tests "io.bluetape4k.aws.spring.sqs.SqsCoroutinesTemplateAwsEmulatorTest"
  run_floci listener ./gradlew --no-daemon --max-workers=1 --no-parallel :bluetape4k-aws-spring-boot:test -Dbluetape4k.aws.emulator=floci --tests "io.bluetape4k.aws.spring.sqs.SqsListenerAwsEmulatorTest"
  run_floci batch ./gradlew --no-daemon --max-workers=1 --no-parallel :bluetape4k-aws-spring-boot:test -Dbluetape4k.aws.emulator=floci --tests "io.bluetape4k.aws.spring.sqs.SqsBatchListenerAwsEmulatorTest"
  if ((${#FAILED_SCENARIOS[@]} > 0)); then
    unsupported=true
    for scenario in "${FAILED_SCENARIOS[@]}"; do
      marker=".bluetape/evidence/issue-454/floci/$scenario.capability-marker.json"
      if ! valid_capability_marker "$marker" "$scenario" "${SCENARIO_HASHES[$scenario]}" "$scenario.stdout" "$scenario.stderr"; then
        unsupported=false
      fi
    done
    if [[ "$unsupported" == true ]]; then
      printf '%s\n' '{"status":"PENDING","reason":"machine-readable Floci launcher/image/operation capability gap","scenarios":"see *.exit, stdout/stderr and capability-marker.json"}' \
        > .bluetape/evidence/issue-454/floci/failure-classification.json
      exit 2
    fi
    printf '%s\n' '{"status":"FAIL","reason":"emulator assertion/protocol/test failure","scenarios":"see *.exit and stdout/stderr"}' \
      > .bluetape/evidence/issue-454/floci/failure-classification.json
    exit 1
  fi
  printf '%s\n' '{"status":"PASS","scenarios":["template","listener","batch"]}' \
    > .bluetape/evidence/issue-454/floci/failure-classification.json
  ```

  기대 결과: 각 invocation이 독립적으로 PASS하고 Docker 공유자원으로 인한 flaky failure는 재현 증거를 확보한 뒤 수정한다. 세 scenario 중 하나라도 valid capability marker가 없는 assertion/auth/permission/protocol/test failure이면 capture script는 exit 1로 종료하고 PASS artifact를 만들지 않는다. preflight 또는 scenario의 valid machine-readable capability marker만 `failure-classification.json.status=PENDING`과 exit 2로 남기며, 그 경우에만 아래 LocalStack fallback을 실행한다. fallback은 Floci proof로 간주하지 않으며 image/version·정확한 전체 command·지원 capability 차이를 별도 표로 기록한다.

  capture wrapper의 negative fixture도 같은 순서로 실행한다. marker 누락, malformed JSON,
  stale `runNonce`/`commandSha256`, operation 불일치, empty proof, wrong proof path/hash,
  `authoritativeProof` 누락, assertion/auth/permission stderr와
  `docker image inspect` 비-0 결과는 각각 exit 1이며 LocalStack command와
  `localstack-capability-gap.json`을 만들지 않아야 한다. 유효한 preflight/scenario marker가
  있는 비-0 결과만 exit 2/PENDING이고 fallback으로 진행한다. 이 fixture 결과와 exit code를
  `.bluetape/evidence/issue-454/floci/negative/`에 보존한다.

  ```bash
  set -Eeuo pipefail
  : "${LOCALSTACK_IMAGE:?set exact LocalStack image reference}"
  : "${LOCALSTACK_VERSION:?set LocalStack version}"
  : "${RETRIEVED_AT:?set ISO-8601 retrieval timestamp}"
  : "${EXPIRY_DATE:?set capability-gap expiry date}"
  : "${RECHECK_DATE:?set capability-gap recheck date}"
  mkdir -p .bluetape/evidence/issue-454/floci/localstack
  LOCALSTACK_COMMAND=(./gradlew --no-daemon --max-workers=1 --no-parallel :bluetape4k-aws-spring-boot:test \
      -Dbluetape4k.aws.emulator=localstack --tests "io.bluetape4k.aws.spring.sqs.SqsBatchListenerAwsEmulatorTest")
  printf -v LOCALSTACK_COMMAND_TEXT '%q ' "${LOCALSTACK_COMMAND[@]}"
  LOCALSTACK_COMMAND_TEXT="${LOCALSTACK_COMMAND_TEXT% }"
  set +e
  "${LOCALSTACK_COMMAND[@]}" \
      > .bluetape/evidence/issue-454/floci/localstack.stdout \
      2> .bluetape/evidence/issue-454/floci/localstack.stderr
  status=$?
  set -e
  printf '%s\n' "$status" > .bluetape/evidence/issue-454/floci/localstack.exit
  localstack_status=FAIL
  if ((status == 0)); then localstack_status=PASS; fi
  jq -n \
    --arg retrievedAt "$RETRIEVED_AT" \
    --arg image "$LOCALSTACK_IMAGE" \
    --arg version "$LOCALSTACK_VERSION" \
    --arg command "$LOCALSTACK_COMMAND_TEXT" \
    --arg status "$localstack_status" \
    --arg proof "localstack.stdout/localstack.stderr" \
    --arg expiryDate "$EXPIRY_DATE" \
    --arg recheckDate "$RECHECK_DATE" \
    '{issue:454,status:$status,retrievedAt:$retrievedAt,emulator:{name:"localstack",image:$image,version:$version},command:$command,capabilities:[{operation:"SqsBatchListenerAwsEmulatorTest",status:$status,authoritativeProof:$proof,unsupportedBehavior:"not-Floci-authoritative",owner:"bluetape4k-sqs-oncall",trackingIssue:"454",expiryDate:$expiryDate,recheckDate:$recheckDate,releaseBlocking:false}]}' \
    > .bluetape/evidence/issue-454/floci/localstack-capability-gap.json
  exit "$status"
  ```

  각 실행 전 `docker info`와 Floci image/version을 기록하고, 실행 후 Floci singleton이
  소유한 cleanup/residue를 확인한다. emulator assertion/protocol failure는 fallback 사유가
  아니며, preflight/scenario machine-readable marker가 `UNAVAILABLE|UNSUPPORTED`를
  증명한 경우에만 LocalStack을 별도 실행한다. 테스트는 Floci가 소유한 singleton/ShutdownQueue 외 raw container lifecycle을
  생성하거나 종료하지 않는다.

## Task 8: 문서·연구 보존·운영 rollback 절차를 갱신한다

**Files:**

- Modify: `aws-spring-boot/README.md`, `aws-spring-boot/README.ko.md`
- Modify: `docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`, `docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`
- Create: `docs/lessons/2026-08-11-sqs-batch-listener.md`
- Create in wiki repo: `research/2026-08-11-aws-sqs-batch-listener.md`

- [ ] **Step 1: README/KDoc/manual을 같은 계약으로 작성한다.**

  `List<SqsReceivedMessage>`, `List<Message>`, `List<T>`, `ON_SUCCESS`/`MANUAL`/`INHERIT`, `acknowledge`/`nack`/`changeVisibility`, AWS 10개 제한, FIFO prefix, at-least-once/idempotency/DLQ, raw receipt redaction, batch canary/rollback을 English/Korean manual에 구조적으로 대응시킨다. README는 요약과 manual 링크를 유지하고 full chapter를 중복하지 않는다. rollback runbook에는 annotation 재배포/dual-handler 전환, `STOPPING_RECEIVE → DRAINING → STOPPED`, `stopTimeoutMillis`, 이미 삭제된 메시지는 복구되지 않는다는 경고, 실패 메시지 redrive/DLQ, idempotency 확인, 승인자와 온콜 owner를 포함한다.
  public API example matrix에는 (1) `SqsBatchAcknowledgementResult.operation/status`,
  `successfulMessageIds`와 MANUAL `acknowledge`/`nack`/`changeVisibility`, (2)
  `SqsBatchDeleteResult.successfulEntryIds`의 `entry-0..entry-9` 순서와
  `SqsBatchVisibilityResult`, (3) `timeoutSeconds` 0..43_200·기본 nack timeout 0, (4)
  `SqsMessageConversionException`, `SqsBatchDeleteProtocolException`,
  `SqsBatchVisibilityProtocolException` 예외/수정 예제를 English/Korean 양쪽에 포함한다.
  `SqsListenerBatchCorrelation`의 opaque `generation`/`pollerId`/`batchSequence`와
  correlation-aware interceptor overload/default bridge 사용 예제도 양쪽에 포함하고, queue URL·
  receipt handle·body·message ID를 correlation 값과 observation tag에 넣지 않는다.
  예제는 `software.amazon.awssdk.services.sqs.model.Message` import를 명시하고 helper와
  redacted logging을 self-contained하게 작성해 consumer fixture가 동일 source를 compile한다.
  stable error 표는 입력 오류 → 예외 타입 → exact fragment → caller 수정법을 bilingual로
  고정한다.
  운영 rollback runbook은 테스트 명령과 control-plane 명령을 분리한다. 테스트 증거 명령은
  `./gradlew --no-daemon --max-workers=1 --no-parallel :bluetape4k-aws-spring-boot:test
  -Dbluetape4k.aws.emulator=floci --tests
  "io.bluetape4k.aws.spring.sqs.SqsBatchListenerAwsEmulatorTest"`로 고정한다. 운영 adapter는
  다음 변수와 응답 contract를 제공한다.

  ```bash
  set -Eeuo pipefail
  : "${CONTROL_PLANE_URL:?set deployment listener control-plane URL}"
  : "${NAMESPACE:?set Kubernetes namespace}"
  : "${DEPLOYMENT:?set consumer deployment}"
  : "${LISTENER_ID:?set listener id}"
  : "${OLD_REVISION:?set last known-good deployment revision}"
  : "${DLQ_ARN:?set dead-letter queue ARN}"
  : "${QUEUE_ARN:?set source queue ARN}"
  : "${REDRIVE_RATE:?set safe redrive rate}"
  STOP_TIMEOUT_MILLIS=30000
  EVIDENCE_DIR=".bluetape/evidence/issue-454/rollback"
  mkdir -p "$EVIDENCE_DIR"
  curl --fail-with-body --silent --show-error --request POST \
    "$CONTROL_PLANE_URL/v1/listeners/$LISTENER_ID/stop" \
    --data "{\"timeoutMillis\":$STOP_TIMEOUT_MILLIS,\"waitFor\":\"STOPPED\"}" \
    | tee "$EVIDENCE_DIR/stop.json"
  test "$(jq -r .state "$EVIDENCE_DIR/stop.json")" = STOPPED
  test "$(jq -r .drained "$EVIDENCE_DIR/stop.json")" = true
  test "$(jq -r .inFlight "$EVIDENCE_DIR/stop.json")" = 0
  test "$(jq -r .generation "$EVIDENCE_DIR/stop.json")" != null
  kubectl -n "$NAMESPACE" rollout undo "deployment/$DEPLOYMENT" --to-revision="$OLD_REVISION"
  kubectl -n "$NAMESPACE" rollout status "deployment/$DEPLOYMENT" --timeout=10m \
    | tee "$EVIDENCE_DIR/old-handler-rollout.txt"
  aws sqs start-message-move-task --source-arn "$DLQ_ARN" --destination-arn "$QUEUE_ARN" \
    --max-number-of-messages-per-second "$REDRIVE_RATE" \
    > "$EVIDENCE_DIR/redrive-start.json"
  REDRIVE_TASK_HANDLE="$(jq -r .TaskHandle "$EVIDENCE_DIR/redrive-start.json")"
  test -n "$REDRIVE_TASK_HANDLE" -a "$REDRIVE_TASK_HANDLE" != null
  for attempt in $(seq 1 60); do
    aws sqs list-message-move-tasks --source-arn "$DLQ_ARN" --max-results 10 \
      > "$EVIDENCE_DIR/redrive-status.json"
    REDRIVE_STATUS="$(jq -r --arg handle "$REDRIVE_TASK_HANDLE" '.Results[] | select(.TaskHandle == $handle) | .Status' "$EVIDENCE_DIR/redrive-status.json" | head -1)"
    case "$REDRIVE_STATUS" in
      COMPLETED) break ;;
      FAILED|CANCELED) exit 1 ;;
    esac
    test "$attempt" -lt 60 || exit 1
    sleep 5
  done
  curl --fail-with-body --silent --show-error \
    "$CONTROL_PLANE_URL/v1/listeners/$LISTENER_ID/idempotency/check" \
    | tee "$EVIDENCE_DIR/idempotency.json"
  test "$(jq -r .deduplicationEnabled "$EVIDENCE_DIR/idempotency.json")" = true
  ```

  control-plane 응답은 `state=STOPPED`, `drained=true`, `inFlight=0`, `generation`을
  반환해야 하며, deployment adapter가 이 contract를 제공하지 않으면 rollback은 실패로
  중단한다. 실행 순서는 receive 중지→drain→old handler 배포→redrive/DLQ→idempotency 확인이다.
  `stopTimeoutMillis=30_000` 초과, partial failure 비율 `>1%/5m`, retry-exhausted 비율
  `>0.1%/5m`, redelivery-age p95가 visibility timeout의 `80%` 초과, DLQ visible count가
  `>0/5m`이면 canary를 중단한다. alert owner는 `bluetape4k-sqs-oncall`, 승인자는
  `bluetape4k-release-approvers`로 고정하며, 모든 command·응답·threshold snapshot을
  `$EVIDENCE_DIR`에 보존한다. MANUAL 정상 반환·무ack는 자동 삭제하지 않고 redelivery된다는
  표도 함께 둔다.

- [ ] **Step 2: compile-check와 manual contract를 실행한다.**

  ```bash
  ./gradlew exportManualModuleInventory --no-daemon
  ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
  ruby scripts/manual/manual_contract_test.rb
  git diff --check
  ```

  runbook contract test는 `set -Eeuo pipefail`을 확인하고 stop curl, rollout status,
  redrive polling, idempotency curl 각각을 실패시키는 negative fixture를 실행한다. 각 fixture는
  실패 exit code와 `redrive-start.json` 미생성을 assert하여 stop/rollout/idempotency 실패 뒤
  redrive가 0회인지 검증한다.

  README/KDoc 예제는 실제 `SqsBatchAcknowledgementResult.successfulMessageIds`와 `SqsBatchAcknowledgementStatus`를 사용하고, 문서에는 receipt handle을 출력하는 예제를 넣지 않는다.
  공개 KDoc 대상은 `SqsListener.kt`, `SqsAcknowledgementMode.kt`, `SqsListenerBatchCorrelation.kt`,
  `SqsListenerInterceptor.kt`, `SqsBatchAcknowledgement.kt`, `SqsBatchModels.kt`, `SqsOperations.kt`,
  `SqsMessageConversionException.kt`로 고정하고,
  실제 declaration/example을 consumer fixture가 compile하는지와 Dokka output에 stable
  signature/KDoc가 존재하는지 확인한다.

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:dokkaGeneratePublicationHtml
  ./gradlew compileSqsBatchConsumerFixture
  ```

- [ ] **Step 3: 외부 연구를 wiki에 보존한다.**

  AWS ReceiveMessage/DeleteMessageBatch와 Spring Cloud AWS reference/PR #1622의 URL, retrieval date, copyright-safe Korean summary, Bluetape implications, adopt/reject decision, Assets section을 wiki note에 저장한다. `git diff --check`, `gno update`, `gno embed --collection bluetape4k-wiki`, representative `gno search ... -c bluetape4k-wiki`를 실행하고 wiki의 pre-existing dirty/untracked 파일을 보존한다.

- [ ] **Step 4: lesson을 작성한다.**

  구현 중 발견한 state/ABI/emulator capability gap, 검토 miss, verification evidence와 향후 guard를 Korean lesson에 기록한다. capability gap은 lesson의 고정 표에 `owner`, tracking issue, authoritative proof 경로, unsupported behavior, expiry/recheck date, release-blocking 여부를 채운다. PR/merge는 사용자가 별도로 요청하지 않았으므로 여기서 delivery를 중지한다.

## Task 9: 비례 검증·리뷰·완료 증거를 수집한다

**Files:**

- Verify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchPerformanceTest.kt` and `SqsBatchPerformanceRuntimeAdapter.kt` from Task 0/5
- Create evidence: `.bluetape/evidence/issue-454/perf/candidate-HEAD.json`,
  `candidate-raw-samples.json`, `candidate-summary.json`

- [ ] **Step 1: targeted → module → repository 검증을 순서대로 실행한다.**

  ```bash
  ./gradlew --no-daemon --max-workers=1 --no-parallel :bluetape4k-aws-spring-boot:test -PskipAwsEmulatorTests=true --tests "io.bluetape4k.aws.spring.sqs.SqsBatchAcknowledgementTest"
  ./gradlew --no-daemon --max-workers=1 --no-parallel :bluetape4k-aws-spring-boot:test -PskipAwsEmulatorTests=true
  ./gradlew --no-daemon --max-workers=1 --no-parallel :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin
  ./gradlew --no-daemon --max-workers=1 --no-parallel compileSqsOperationsLegacyConsumerFixture compileSqsListenerAnnotationLegacyConsumerFixture compileSqsListenerInterceptorLegacyConsumerFixture compileSqsBatchConsumerFixture
  ./gradlew --no-daemon --max-workers=1 --no-parallel :bluetape4k-aws-spring-boot:dokkaGeneratePublicationHtml
  ./gradlew --no-daemon --max-workers=1 --no-parallel projects
  ./gradlew --no-daemon --max-workers=1 --no-parallel :bluetape4k-aws-spring-boot:dependencies --configuration compileClasspath
  ./gradlew --no-daemon --max-workers=1 --no-parallel detekt
  ./gradlew --no-daemon --max-workers=1 --no-parallel -PskipAwsEmulatorTests=true build
  git diff --check
  ```

  emulator commands는 Task 7 결과가 PASS한 후 다시 실행하지 않고, 결과 파일·로그와 exact command를 증거로 연결한다. `aws-spring-boot/build.gradle.kts`의
  `skipAwsEmulatorTests` guard는 `**/*AwsEmulatorTest.class`를 제외하며, Task 9 module/repository
  test와 `build` command에는 `-PskipAwsEmulatorTests=true`를 전달한다. 통제된 JUnit 성능 회귀
  gate는 batch size 1/10, optimized/fallback delete·visibility, Micrometer on/off,
  concurrency 1/N, max-in-flight, duplicate ack call count, p95 latency/allocation budget을
  기록한다. `SqsBatchPerformanceTest.kt`는 fake clock/recording operations harness와
  `com.sun.management.ThreadMXBean` allocated-bytes collector를 사용한다. 고정 command는
  `./gradlew --no-daemon --max-workers=1 --no-parallel
  :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.sqs.SqsBatchPerformanceTest"`이며,
  warmup 3회/measurement 10회, Task 0에서 pin한 `baseline-commit.txt`의 동일 harness
  command로 측정한 `baseline-commit.json`과 candidate를 비교한다. candidate artifact에는
  `measurementKind=controlled-regression`, `runtimePath=batch`, 동일 harness test-source SHA-256,
  batch adapter source SHA-256, test-owned dispatcher configuration, worker count와 해당 실행의
  worker ID 목록을 기록하고, baseline harness SHA-256 또는 dispatcher configuration/worker count가
  다르면 비교를 FAIL한다. JVM별 worker ID 숫자는 equality 비교에서 제외하고 raw artifact에만
  보존한다.
  p95 latency와
  allocated bytes/op은 baseline 대비 `<=20%`만 PASS이며, batch size 10 optimized
  delete/visibility는 AWS call `<=2`, Micrometer 기본 per-message timer/span allocation은
  `0`개여야 한다. baseline/candidate/raw samples/summary는
  `.bluetape/evidence/issue-454/perf/`에 저장하고, 기준 위반은 FAIL로 남긴다. runbook
  contract test는 stop-drain-redrive/DLQ와 alert schema/owner/threshold를 검증한다.

- [ ] **Step 2: Kotlin/Type A verifier를 실행한다.**

  validation/exception, immutability, coroutine cancellation/IO dispatcher, Spring auto-configuration, ABI, public KDoc/manual parity, observability redaction, Floci evidence와 plan traceability를 확인한다. P0/P1이 하나라도 남으면 구현으로 되돌아가 해당 task의 RED부터 재실행한다.

- [ ] **Step 3: final independent code review를 수행한다.**

  performance, stability, security, Ops, API, user/caller 6관점과 main integration을 실행한다. no PR 범위이므로 live PR/merge/CI review gate는 N/A로 별도 기록하고, local branch/head와 working tree evidence를 보존한다.

## 위험 예측(3-P)

| 위험 | 조기 신호 | 완화 | rollback/rerun |
|---|---|---|---|
| DeleteMessageBatch 응답 유실/ID 불일치가 이미 삭제된 항목과 미확인 항목을 섞음 | protocol exception, redelivery count 증가, partial failure counter | entry ID mapping fail-closed, confirmed success만 terminal, handler idempotency/DLQ 문서화 | drain/stop 후 이전 단건-handler를 재배포하고 Task 3/5 fake response부터 재실행 |
| 동시 ack/nack/changeVisibility race가 duplicate AWS call 또는 false completed를 만듦 | handle당 호출 횟수 >1, pending이 비단조적, completed 조기 true | batch ownership + Mutex state machine + cached terminal result | Task 2 concurrency RED부터 재실행; drain/stop 후 이전 단건-handler로 전환 |
| FIFO predecessor보다 later item이 먼저 삭제됨 | same-group later message가 predecessor pending 중 terminal 처리됨 | group별 contiguous-success-prefix validation, FIFO fake/emulator test | Task 2 FIFO RED와 Task 7 sequential emulator부터 재실행 |
| handler가 visibility timeout을 넘겨 side effect 중복/재배달 발생 | visibility margin counter, duplicate message-id observation | #453 heartbeat 범위는 명시적으로 제외하고 timeout/at-least-once/idempotency/DLQ runbook 제공 | drain/stop 후 단건-handler 재배포; #453 별도 issue로 추적 |
| compileOnly AWS class 또는 default method ABI가 consumer startup을 깨뜨림 | negative/positive ApplicationContextRunner 실패, old fixture linkage error | existing imports/guards 보존, default fallback, precompiled fixture | Task 1/6 compatibility tests부터 재실행; 새 API 제거/release 이전 revert |
| 외부 body/generic metadata가 converter trust boundary를 우회함 | raw/wildcard/nullable/nested generic accepted, body/handle in exception/log | concrete KType only, existing safe converter, bounded errors, no Java serialization/type metadata | Task 4 invalid-shape tests부터 재실행; batch opt-in off |

## 요구사항 추적성

| 승인 명세/DoD | 계획 task | 증거 |
|---|---|---|
| batch=true opt-in, INHERIT/ON_SUCCESS/MANUAL, 1..10 | 1, 5, 6 | endpoint/context tests |
| List<SqsReceivedMessage>/Message/T와 generic fail-fast | 4 | invoker tests + compile examples |
| partial ack, pending/completed, retry/visibility/cancellation | 2, 5 | fake state/runtime tests |
| DeleteMessageBatch item result, fallback, protocol fail-closed | 3 | operation tests + SDK emulator |
| FIFO prefix, no internal batch parallelism, stop generation | 2, 5, 7 | lifecycle/FIFO tests |
| interceptor/metric redaction, canonical metric names/tags, implementation.path and cancellation | 2, 3, 5, 9 | Micrometer/interceptor assertions + metric schema/runbook contract |
| single listener/ABI/auto-config compatibility | 1, 6, 9 | context/fixture/regression tests |
| Floci or explicit capability gap | 7, 8 | sequential emulator output + lesson |
| README/KDoc/manual/rollback/runbook | 8, 9 | parity/manual checks |
| P0/P1=0, no PR/merge | 9 | review table and final no-delivery DoD |

## 완료 기준

- [ ] 계획·설계 문서가 Lore commit으로 branch에 존재한다.
- [ ] Task 0–8의 RED/GREEN와 targeted/module/repository 검증 증거가 존재한다.
- [ ] approved spec의 모든 요구사항이 추적성 표의 task와 test/doc evidence에 연결된다.
- [ ] six-lens plan/code review와 main integration에서 P0=0, P1=0이다.
- [ ] Floci capability 결과 또는 명시적인 gap/owner가 lesson과 test evidence에 기록된다.
- [ ] PR/merge/publish/branch cleanup은 별도 권한 경계로 남기고 현재 이슈의 local DoD만 보고한다.
