# SQS batch listener 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `test-driven-development` and `bluetape-kotlin-patterns` while implementing this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `@SqsListener(batch = true)`가 하나의 SQS 수신 응답을 coroutine-native batch handler로 전달하고, 전체·부분 acknowledgement와 항목별 재배달을 단건 listener 호환성을 유지한 채 제공한다.

**Architecture:** 기존 `SqsMessageListenerContainer`를 확장해 batch endpoint만 별도 경로로 라우팅한다. `SqsBatchAcknowledgement`는 batch 소유권과 항목별 상태를 `Mutex`로 직렬화하고, `SqsOperations.deleteBatch`는 기존 구현체를 위한 단건 fallback과 AWS SDK `DeleteMessageBatch` 최적화 경로를 모두 제공한다. AWS receipt handle은 내부 mapping에만 두고 공개 결과·로그·metric tag에는 노출하지 않는다.

**Tech Stack:** Kotlin 2.4, Spring Boot 4, kotlinx-coroutines, AWS SDK v2 `SqsAsyncClient`, Jackson `ObjectMapper`, Micrometer, JUnit 5, MockK, Kluent, Testcontainers/Floci.

---

## 작업 규칙과 소유 범위

- 구현 전에 `$bluetape-kotlin-patterns`, `$test-driven-development`와 해당 reference인 `checklist.md`, `spring-boot.md`, `testing.md`를 다시 읽는다.
- 모든 Kotlin 변경은 `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/issue-454-sqs-batch-listener`에서만 수행한다. `develop` checkout은 수정하지 않는다.
- 새 dependency, Spring Integration, awspring을 추가하지 않는다. AWS service SDK compileOnly 정책과 기존 `aws-java` batch coroutine helper를 재사용한다.
- receipt handle·message body·raw message id를 로그, `toString`, trace attribute, metric tag에 넣지 않는다. 기존 `AwsMicrometerSupport`의 bounded queue-name tag와 operation/outcome tag만 재사용한다.
- Floci/Testcontainers 실행은 Docker 자원을 공유하므로 한 번에 한 Gradle invocation으로 순차 실행한다.
- 각 기능 단위는 RED 테스트 → 최소 구현 → GREEN 테스트 → diff 점검 순서로 진행하고, 의미 있는 단위마다 Lore commit을 만든다.

## 변경 파일 지도

| 책임 | 생성/수정 파일 | 검증 산출물 |
|---|---|---|
| annotation·endpoint 계약 | `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt`, `SqsAcknowledgementMode.kt`(생성), `SqsListenerEndpoint.kt`, `SqsListenerAnnotationBeanPostProcessor.kt` | `SqsAutoConfigurationTest.kt`의 mode/maxMessages/invalid signature 회귀 |
| batch 공개 모델과 상태 | `SqsBatchAcknowledgement.kt`(생성), `SqsBatchModels.kt`(생성), `SqsBatchAcknowledgementFailure`·protocol exception | `SqsBatchAcknowledgementTest.kt`(생성), 결과 ordering/redaction/state evidence |
| SQS batch operation | `SqsOperations.kt`, `SqsCoroutinesTemplate.kt`, `MicrometerSqsOperations.kt` | `SqsOperationsBatchTest.kt`(생성), `MicrometerSqsOperationsTest.kt`, emulator template test |
| payload resolver | `SqsListenerMethodInvoker.kt`, `SqsMessageConverter.kt` | `SqsListenerMethodInvokerTest.kt`(생성), `SqsAutoConfigurationTest.kt` |
| container runtime | `SqsMessageListenerContainer.kt`, `SqsListenerInterceptor.kt`, `MicrometerSqsListenerInterceptor.kt`(필요 시) | `SqsMessageListenerContainerTest.kt`, interceptor/metric assertions |
| test doubles/compatibility | `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/NoopSqsOperations.kt`, recording fake 및 precompiled ABI fixture | module tests, old implementation fixture |
| emulator proof | `SqsListenerAwsEmulatorTest.kt`, `SqsCoroutinesTemplateAwsEmulatorTest.kt` | Floci success/partial/FIFO/redelivery evidence |
| 사용자 문서 | `aws-spring-boot/README.md`, `README.ko.md`, `docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`, `docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md` | manual parity/manifest/contract checks |
| durable research/lesson | `/Users/debop/work/bluetape4k/bluetape4k-wiki/research/2026-08-11-aws-sqs-batch-listener.md`, `docs/lessons/2026-08-11-sqs-batch-listener.md` | wiki validation, lesson Lore commit |

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
  fun `batch endpoint inherits property maxMessages and rejects more than ten`() { /* context startup assertion */ }

  @Test
  fun `inherit mode maps acknowledgement parameter to manual`() { /* endpoint assertion */ }

  @Test
  fun `on success rejects manual acknowledgement parameter`() { /* stable IllegalArgumentException fragment */ }
  ```

  기대 실패는 `batch=true requires a List payload`, `batch=false does not accept List payload`, `SqsBatchAcknowledgement requires batch=true`, `batch delete supports at most 10 messages` 중 해당 stable fragment가 없거나 endpoint가 생성되는 것이다.

- [ ] **Step 2: GREEN — annotation/endpoint 해석을 구현한다.**

  `batch: Boolean = false`, `acknowledgementMode: SqsAcknowledgementMode = INHERIT`를 annotation trailing default로 추가한다. `maxMessages = -1`은 `SqsProperties.Listener.maxMessages`를 상속하고, 최종 batch 값은 `1..10`만 허용한다. `INHERIT`는 acknowledgement parameter 유무로 `MANUAL`/`ON_SUCCESS`를 결정하며 명시적 충돌은 context 초기화에서 `IllegalArgumentException`으로 거부한다. endpoint에는 resolved mode와 batch flag를 저장한다.

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

  커밋 본문에는 Lore trailers를 포함한다. rollback은 해당 커밋을 revert하고 annotation 기본값만 유지하는 것이다.

## Task 2: 공개 batch 모델과 단일 소유 상태 머신을 구현한다

**Files:**

- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchModels.kt`
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchAcknowledgement.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchAcknowledgementTest.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsAcknowledgementTest.kt` only when shared fake signatures require it

- [ ] **Step 1: RED — 상태와 결과 불변식을 테스트한다.**

  fake `SqsOperations`에 호출 횟수와 receipt/entry를 기록하고 다음 테스트를 작성한다.

  ```kotlin
  @Test fun `acknowledge deletes all pending and completes`() { /* successfulMessageIds preserves input order */ }
  @Test fun `partial delete keeps failed item pending`() { /* SUCCESS/PARTIAL_FAILURE and completed=false */ }
  @Test fun `nack success becomes deferred and does not delete`() { /* pending excludes deferred item */ }
  @Test fun `concurrent duplicate ack is linearized`() { /* one AWS action per handle */ }
  @Test fun `foreign duplicate and eleven-item inputs fail before AWS`() { /* IllegalArgumentException and zero calls */ }
  @Test fun `fifo predecessor blocks later acknowledgement`() { /* fifo_predecessor_pending */ }
  @Test fun `transport cancellation and protocol mismatch are rethrown`() { /* pending remains unconfirmed */ }
  ```

- [ ] **Step 2: GREEN — result/state contracts를 구현한다.**

  `SqsBatchAcknowledgementResult`는 status, `successfulMessageIds`, failure 목록을 유지하고 raw receipt handle/Serializable을 노출하지 않는다. 각 item을 `PENDING`, `IN_FLIGHT`, `ACKED`, `DEFERRED`로 관리하며 `Mutex.withLock` 안에서 ownership, queue URL, max 10, duplicate receipt, FIFO predecessor를 검증한다. `ACKED`/`DEFERRED` 반복 호출은 cached result를 반환하고 실패/unknown은 `PENDING`으로 되돌린다. `changeVisibility`도 동일 결과 타입을 반환하며 `CancellationException`은 절대 삼키지 않는다.

- [ ] **Step 3: 검증한다.**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.sqs.SqsBatchAcknowledgementTest"
  ```

  기대 결과: 호출 횟수, pending snapshot, completed monotonicity, result order, FIFO prefix와 raw handle redaction 테스트가 모두 PASS한다.

- [ ] **Step 4: 커밋하고 실패 시 재실행 지점을 기록한다.**

  커밋은 `#454 SQS batch acknowledgement 상태를 고정한다`로 만들고, 상태 머신 실패는 Task 2 RED 테스트부터 재실행한다. 외부 API 변경 rollback은 신규 batch 타입과 endpoint flag를 제거하고 단건 경로만 남기는 것이다.

## Task 3: `SqsOperations.deleteBatch` fallback·AWS batch·Micrometer를 연결한다

**Files:**

- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsOperations.kt`, `SqsCoroutinesTemplate.kt`, `MicrometerSqsOperations.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsOperationsBatchTest.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/MicrometerSqsOperationsTest.kt`, `NoopSqsOperations.kt` if required by compiler

- [ ] **Step 1: RED — fallback, SDK mapping, protocol validation, metric을 테스트한다.**

  ```kotlin
  @Test fun `default deleteBatch falls back to single delete in input order`() { /* N calls, entry-0.. */ }
  @Test fun `template sends one DeleteMessageBatch request`() { /* request count=1, 10 entries */ }
  @Test fun `successful and failed SDK ids map without receipt leakage`() { /* mixed result */ }
  @Test fun `unknown duplicate missing SDK id fails closed`() { /* protocol exception */ }
  @Test fun `micrometer records delete_batch with bounded tags`() { /* operation/outcome/queue.name only */ }
  ```

- [ ] **Step 2: GREEN — operation 계약을 구현한다.**

  `SqsOperations`에 source/binary 호환성을 위한 `deleteBatch(queueUrl, receiptHandles)` default method를 추가한다. empty는 no-op, >10/duplicate는 validation error, single delete fallback은 명시적 item 오류만 failure로 수집하고 transport/unknown/cancellation은 재전파한다. `SqsCoroutinesTemplate`은 기존 `aws-java`의 `SqsAsyncClient.deleteMessageBatch` extension과 `DeleteMessageBatchRequestEntry` helper를 재사용하고 `entry-0..entry-9` mapping을 검증한다. `MicrometerSqsOperations`는 `delete_batch` operation으로 위임하고 body/receipt/message id를 tag로 만들지 않는다.

- [ ] **Step 3: 검증한다.**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.sqs.SqsOperationsBatchTest" --tests "io.bluetape4k.aws.spring.sqs.MicrometerSqsOperationsTest"
  ```

  기대 결과: fallback N회와 AWS template 1회가 각각 증명되고, AWS response contract mismatch가 terminal 상태로 반영되지 않는다.

- [ ] **Step 4: 커밋한다.**

  커밋은 `#454 SQS DeleteMessageBatch 경로를 추가한다`로 만들며 AWS request/response mapping failure 시 Task 3 RED 테스트부터 재실행한다.

## Task 4: batch payload resolver와 converter 오류를 추가한다

**Files:**

- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListenerMethodInvoker.kt`, `SqsMessageConverter.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsListenerMethodInvokerTest.kt`

- [ ] **Step 1: RED — supported/unsupported parameter shapes를 고정한다.**

  `List<SqsReceivedMessage>`, `List<Message>`, `List<String>`, `List<OrderPayload>`와 optional `SqsBatchAcknowledgement`의 arguments를 검증한다. raw Java `List`, `List<*>`, `List<T>`, `List<OrderPayload?>`, `List<List<OrderPayload>>`, batch=false List, two payloads, single `SqsAcknowledgement` in batch는 stable error fragment와 함께 거부되어야 한다. converter가 index·target type을 받는지, body/receipt가 exception text에 없는지도 검증한다.

- [ ] **Step 2: GREEN — KType resolver와 batch invocation을 구현한다.**

  `KFunction.valueParameters`의 `KType`를 사용해 invariant non-null concrete element `Class<T>`만 추출하고, converter는 각 message body를 해당 class로 변환한다. `List<SqsReceivedMessage>`/`List<Message>`는 direct mapping하고, raw/nullable/nested/wildcard는 `IllegalArgumentException`으로 fail-fast한다. `invokeBatch(messages, batchAcknowledgement?)`는 one payload + optional acknowledgement만 전달하며 sync handler는 `runInterruptible(Dispatchers.IO)`, suspend handler는 기존 `withContext(Dispatchers.IO)` 경계를 유지한다.

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

- [ ] **Step 1: RED — runtime lifecycle와 mode semantics를 테스트한다.**

  ```kotlin
  @Test fun `batch receive invokes handler once with ten messages`() { /* no per-message handler loop */ }
  @Test fun `empty receive skips invocation`() { /* next poll continues */ }
  @Test fun `on success deletes pending and manual never auto deletes`() { /* operation call assertions */ }
  @Test fun `retry invokes only unacknowledged pending items`() { /* initial attempt counts as one */ }
  @Test fun `stop cancels receive drains handler and blocks stale generation callback`() { /* state order */ }
  @Test fun `cancellation propagates without starting new ack or visibility`() { /* CancellationException */ }
  ```

- [ ] **Step 2: GREEN — poll loop와 batch handler를 구현한다.**

  `messages.forEach` 단건 경로는 유지하고 endpoint.batch만 `handleBatch(queueUrl, messages)`로 라우팅한다. 한 poll result는 한 handler invocation이고 poller 하나당 in-flight batch 하나다. resolved mode가 `ON_SUCCESS`이면 handler 정상 반환 후 pending을 `deleteBatch`하고, `MANUAL`이면 handler가 호출한 acknowledgement만 반영한다. handler/converter/ack/visibility 오류는 기존 retry/backoff와 attempt 1 semantics를 사용하고 terminal 성공/deferred 항목은 재시도 목록에서 제거한다. interceptors는 기존 per-message context/ack hook을 유지하되 batch correlation·bounded metrics를 추가한다. `RUNNING -> STOPPING_RECEIVE -> DRAINING -> STOPPED` generation token으로 stale callback을 차단한다.

- [ ] **Step 3: 검증한다.**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.sqs.SqsMessageListenerContainerTest"
  ```

  기대 결과: 기존 stop/restart/cancellation 테스트와 신규 batch invocation/manual/partial/retry/FIFO-prefix lifecycle tests가 모두 PASS한다.

- [ ] **Step 4: 커밋한다.**

  커밋은 `#454 SQS batch listener lifecycle을 연결한다`로 만들며 timing failure가 발생하면 재시도하지 말고 fake scheduler/barrier로 재현한 뒤 Task 5 RED부터 다시 검증한다.

## Task 6: Spring Boot discovery·ABI·single-path regression을 확인한다

**Files:**

- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsAutoConfigurationTest.kt`, `SqsAcknowledgementTest.kt`
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsOperationsBinaryCompatibilityTest.kt` 또는 기존 compatibility fixture 위치
- Inspect/modify only if required: `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `SqsAutoConfiguration.kt`

- [ ] **Step 1: RED — positive/negative classpath와 precompiled implementation을 검증한다.**

  `ApplicationContextRunner`에서 AWS `Message`/Sqs client classpath의 positive auto-configuration, absent optional class의 negative path, existing registry/phase ordering을 확인한다. 새 `deleteBatch`를 compile하지 않은 fake implementation fixture를 새 consumer가 호출할 수 있어야 하며, 기존 single `SqsAcknowledgement` delete/changeVisibility, JSON DTO listener, `INHERIT` source behavior가 변하지 않아야 한다.

- [ ] **Step 2: GREEN — wiring/ABI를 최소 수정한다.**

  `AutoConfiguration.imports`, `@ConditionalOnClass`, `@ConditionalOnProperty`, `SmartLifecycle` phase와 post-processor ordering을 보존한다. Kotlin JVM default method 설정에 맞춰 `SqsOperations.deleteBatch` default를 유지하고, 불필요한 module/dependency/autoconfig를 추가하지 않는다.

- [ ] **Step 3: 검증한다.**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.sqs.SqsAutoConfigurationTest" --tests "io.bluetape4k.aws.spring.sqs.SqsAcknowledgementTest"
  ```

  기대 결과: positive/negative context, old implementation fixture, 단건 regression이 PASS한다.

## Task 7: Floci/Testcontainers와 실제 SDK response를 검증한다

**Files:**

- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsCoroutinesTemplateAwsEmulatorTest.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsListenerAwsEmulatorTest.kt`
- Create if needed: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchListenerAwsEmulatorTest.kt`

- [ ] **Step 1: RED — emulator scenarios를 등록한다.**

  Floci 우선으로 10개 batch single invocation, ON_SUCCESS deletion, MANUAL partial ack, visibility-expiry redelivery, duplicate prevention, FIFO order/group prefix, concurrent poller lifecycle를 테스트한다. DeleteMessageBatch item-level failure가 emulator에서 제공되지 않으면 fake Task 3/2 테스트를 authoritative로 남기고 image/version·명령·capability gap을 lesson에 기록한다.

- [ ] **Step 2: GREEN — 실제 SQS template/container proof를 통과시킨다.**

  테스트는 message body를 assert할 수 있지만 운영 로그/metric assertion에는 body·receipt·raw id를 사용하지 않는다. 성공 항목은 visibility 만료 후 다시 나오지 않고, 실패/미확인 항목만 redelivery 대상이어야 한다. FIFO에서 predecessor가 pending일 때 later deletion이 발생하지 않아야 한다.

- [ ] **Step 3: 순차 검증한다.**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test -Dbluetape4k.aws.emulator=floci --tests "io.bluetape4k.aws.spring.sqs.SqsCoroutinesTemplateAwsEmulatorTest"
  ./gradlew :bluetape4k-aws-spring-boot:test -Dbluetape4k.aws.emulator=floci --tests "io.bluetape4k.aws.spring.sqs.SqsListenerAwsEmulatorTest"
  ```

  기대 결과: 각 invocation이 독립적으로 PASS하고 Docker 공유자원으로 인한 flaky failure는 재현 증거를 확보한 뒤 수정한다. Floci가 불가하면 `-Dbluetape4k.aws.emulator=localstack` fallback을 사용하고 capability gap을 기록한다.

## Task 8: 문서·연구 보존·운영 rollback 절차를 갱신한다

**Files:**

- Modify: `aws-spring-boot/README.md`, `aws-spring-boot/README.ko.md`
- Modify: `docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`, `docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`
- Create: `docs/lessons/2026-08-11-sqs-batch-listener.md`
- Create in wiki repo: `research/2026-08-11-aws-sqs-batch-listener.md`

- [ ] **Step 1: README/KDoc/manual을 같은 계약으로 작성한다.**

  `List<SqsReceivedMessage>`, `List<Message>`, `List<T>`, `ON_SUCCESS`/`MANUAL`/`INHERIT`, `acknowledge`/`nack`/`changeVisibility`, AWS 10개 제한, FIFO prefix, at-least-once/idempotency/DLQ, raw receipt redaction, batch disable/canary/rollback을 English/Korean manual에 구조적으로 대응시킨다. README는 요약과 manual 링크를 유지하고 full chapter를 중복하지 않는다.

- [ ] **Step 2: compile-check와 manual contract를 실행한다.**

  ```bash
  ./gradlew exportManualModuleInventory --no-daemon
  ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
  ruby scripts/manual/manual_contract_test.rb
  git diff --check
  ```

  README/KDoc 예제는 실제 `SqsBatchAcknowledgementResult.successfulMessageIds`와 `SqsBatchAcknowledgementStatus`를 사용하고, 문서에는 receipt handle을 출력하는 예제를 넣지 않는다.

- [ ] **Step 3: 외부 연구를 wiki에 보존한다.**

  AWS ReceiveMessage/DeleteMessageBatch와 Spring Cloud AWS reference/PR #1622의 URL, retrieval date, copyright-safe Korean summary, Bluetape implications, adopt/reject decision, Assets section을 wiki note에 저장한다. `git diff --check`, `gno update`, `gno embed --collection bluetape4k-wiki`, representative `gno search ... -c bluetape4k-wiki`를 실행하고 wiki의 pre-existing dirty/untracked 파일을 보존한다.

- [ ] **Step 4: lesson을 작성한다.**

  구현 중 발견한 state/ABI/emulator capability gap, 검토 miss, verification evidence와 향후 guard를 Korean lesson에 기록한다. PR/merge는 사용자가 별도로 요청하지 않았으므로 여기서 delivery를 중지한다.

## Task 9: 비례 검증·리뷰·완료 증거를 수집한다

- [ ] **Step 1: targeted → module → repository 검증을 순서대로 실행한다.**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.sqs.SqsBatchAcknowledgementTest"
  ./gradlew :bluetape4k-aws-spring-boot:test
  ./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin
  ./gradlew detekt
  ./gradlew build
  git diff --check
  ```

  emulator commands는 Task 7 결과가 PASS한 후 다시 실행하지 않고, 결과 파일·로그와 exact command를 증거로 연결한다.

- [ ] **Step 2: Kotlin/Type A verifier를 실행한다.**

  validation/exception, immutability, coroutine cancellation/IO dispatcher, Spring auto-configuration, ABI, public KDoc/manual parity, observability redaction, Floci evidence와 plan traceability를 확인한다. P0/P1이 하나라도 남으면 구현으로 되돌아가 해당 task의 RED부터 재실행한다.

- [ ] **Step 3: final independent code review를 수행한다.**

  performance, stability, security, Ops, API, user/caller 6관점과 main integration을 실행한다. no PR 범위이므로 live PR/merge/CI review gate는 N/A로 별도 기록하고, local branch/head와 working tree evidence를 보존한다.

## 위험 예측(3-P)

| 위험 | 조기 신호 | 완화 | rollback/rerun |
|---|---|---|---|
| DeleteMessageBatch 응답 유실/ID 불일치가 이미 삭제된 항목과 미확인 항목을 섞음 | protocol exception, redelivery count 증가, partial failure counter | entry ID mapping fail-closed, confirmed success만 terminal, handler idempotency/DLQ 문서화 | batch endpoint를 단건으로 disable하고 Task 3/5 fake response부터 재실행 |
| 동시 ack/nack/changeVisibility race가 duplicate AWS call 또는 false completed를 만듦 | handle당 호출 횟수 >1, pending이 비단조적, completed 조기 true | batch ownership + Mutex state machine + cached terminal result | Task 2 concurrency RED부터 재실행; 공개 mode를 MANUAL에서 disable |
| FIFO predecessor보다 later item이 먼저 삭제됨 | same-group later message가 predecessor pending 중 terminal 처리됨 | group별 contiguous-success-prefix validation, FIFO fake/emulator test | Task 2 FIFO RED와 Task 7 sequential emulator부터 재실행 |
| handler가 visibility timeout을 넘겨 side effect 중복/재배달 발생 | visibility margin counter, duplicate message-id observation | #453 heartbeat 범위는 명시적으로 제외하고 timeout/at-least-once/idempotency/DLQ runbook 제공 | batch endpoint disable; #453 별도 issue로 추적 |
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
| interceptor/metric redaction and bounded tags | 2, 3, 5, 9 | Micrometer/interceptor assertions |
| single listener/ABI/auto-config compatibility | 1, 6, 9 | context/fixture/regression tests |
| Floci or explicit capability gap | 7, 8 | sequential emulator output + lesson |
| README/KDoc/manual/rollback/runbook | 8, 9 | parity/manual checks |
| P0/P1=0, no PR/merge | 9 | review table and final no-delivery DoD |

## 완료 기준

- [ ] 계획·설계 문서가 Lore commit으로 branch에 존재한다.
- [ ] Task 1–8의 RED/GREEN와 targeted/module/repository 검증 증거가 존재한다.
- [ ] approved spec의 모든 요구사항이 추적성 표의 task와 test/doc evidence에 연결된다.
- [ ] six-lens plan/code review와 main integration에서 P0=0, P1=0이다.
- [ ] Floci capability 결과 또는 명시적인 gap/owner가 lesson과 test evidence에 기록된다.
- [ ] PR/merge/publish/branch cleanup은 별도 권한 경계로 남기고 현재 이슈의 local DoD만 보고한다.
