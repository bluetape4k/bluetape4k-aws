# SNS 배치·비동기 퍼블리싱 parity 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) for tracking.

**Goal:** #456에 Java SDK v2, AWS Kotlin SDK, Spring coroutine template의 SNS
batch publishing parity를 추가하고 호환성, 부분 성공, 취소, bounded concurrency를
보존한다.

**Architecture:** SDK 계층은 요청·검증·동기/비동기 호출만 담당한다. Spring 계층은
Serializable DTO와 additive SnsOperations 계약, 고정 worker SnsBatchExecutor를
제공한다. transport 예외는 redacted 진단과 완료 entry 식별 정보만 공개하며
client ownership은 호출자에게 남긴다.

**Tech Stack:** Kotlin 2.x, Java AWS SDK v2 SNS, AWS Kotlin SDK SNS, Spring
Boot 4, kotlinx-coroutines, JUnit 5, MockK, Kluent, Gradle, Floci 선택 검증.

---

## 불변식·범위

- SNS entry 수는 1..10, 빈 입력은 SDK를 호출하지 않으며 ID는 nonblank·distinct다.
- chunk는 최대 10개이고 input order로 결과를 복원한다. N=0,1,9,10,11,20,21,100의
  chunk 수는 0,1,1,1,2,2,3,10이다.
- maxInFlightBatches는 positive이고 worker 수는 min(maxInFlightBatches, chunkCount)다.
  per-chunk 무제한 launch, 전체 materialization, retry, 선택 재전송, rollback은 없다.
- completed chunk의 성공·실패 ID를 모두 기록한다. 순차 prefix 실패는 성공 prefix만
  기록하고, 병렬 응답 밖 chunk는 도달 가능성이 있으므로 재전송하지 않는다.
- unknown/duplicate/missing response ID는 Spring 경계의 redacted protocol
  exception이다. Spring public message/toString/suppressed cause에는 payload,
  ARN, attributes, credentials, raw SDK throwable을 넣지 않는다. low-level SDK
  API는 caller에게 원본 SDK exception을 전달하며 CancellationException은 양쪽
  경계에서 원본을 유지한다.
- #514는 public strategy/converter·retry 조사, #515는 latency/cleanup telemetry와
  실제 heap·throughput 측정 후속 이슈다. 이번 acceptance에서 선취하지 않는다.
  새 dependency, bean, client lifecycle 변경은 없다.

## 사전 게이트

- [ ] API/ABI, security, concurrency, caller/retry, operations, tests/docs/Kotlin
      여섯 plan review에서 P0=0, P1=0을 확인한다.
- [ ] 사용자의 계획 승인 전에는 production/test code를 변경하지 않는다. 승인 후에도
      task별 RED를 먼저 실행한다.
- [ ] worktree는 feat/issue-456-sns-batch, 기준은
      bd97ef16357a5cea93c10c60916d9bd54138409f이다.
- [ ] baseline을 고정한다.

~~~text
./gradlew :bluetape4k-aws-java:test :bluetape4k-aws-spring-boot:test \
  :bluetape4k-aws-kotlin:test \
  -DskipAwsEmulatorTests=true --no-daemon
~~~

## Task 0: SDK·ABI 기준선

- [ ] 다음 dependencyInsight로 실제 SNS SDK version과 compileOnly 경계를 기록한다.

~~~text
mkdir -p .lane-evidence
{
  echo '--- bluetape4k-aws-java'
  ./gradlew :bluetape4k-aws-java:dependencyInsight \
    --dependency software.amazon.awssdk:sns --configuration compileClasspath
  echo '--- bluetape4k-aws-spring-boot'
  ./gradlew :bluetape4k-aws-spring-boot:dependencyInsight \
    --dependency software.amazon.awssdk:sns --configuration compileClasspath
  echo '--- bluetape4k-aws-kotlin'
  ./gradlew :bluetape4k-aws-kotlin:dependencyInsight \
    --dependency software.amazon.awssdk:sns --configuration compileClasspath
} | tee .lane-evidence/issue-456-sdk-dependency.txt
~~~

- [ ] 각 dependencyInsight 결과를 먼저
      .lane-evidence/issue-456-sdk-dependency.txt에 저장하고, 그 결과에서
      resolved SNS version을 추출한다. 전체 Gradle cache의 최신 파일을
      임의로 선택하지 않으며, cached source를 대체 증거로 사용하지 않는다.
      resolved version directory에 jar가 정확히 하나인지 assertion한 뒤
      hash/javap 출력을 .lane-evidence/issue-456-sdk.txt에 보존한다.

~~~text
SNS_VERSION=$(sed -nE \
  's/.*software\.amazon\.awssdk:sns:([0-9][^ ]*).*/\1/p' \
  .lane-evidence/issue-456-sdk-dependency.txt | head -n 1)
test -n "$SNS_VERSION"
SNS_JAR_DIR="$HOME/.gradle/caches/modules-2/files-2.1/software.amazon.awssdk/sns/$SNS_VERSION"
JAR_COUNT=$(find "$SNS_JAR_DIR" -type f -name "sns-$SNS_VERSION.jar" | wc -l | tr -d ' ')
test "$JAR_COUNT" -eq 1
SNS_JAR=$(find "$SNS_JAR_DIR" -type f -name "sns-$SNS_VERSION.jar")
{
  shasum -a 256 "$SNS_JAR"
  javap -classpath "$SNS_JAR" \
  software.amazon.awssdk.services.sns.SnsClient \
  software.amazon.awssdk.services.sns.SnsAsyncClient \
  software.amazon.awssdk.services.sns.model.PublishBatchResponse \
  software.amazon.awssdk.services.sns.model.BatchResultEntry \
  software.amazon.awssdk.services.sns.model.BatchResultErrorEntry
} | tee .lane-evidence/issue-456-sdk.txt
~~~

- [ ] SnsClient.publishBatch, async future, response entry, builder parameter,
      CompletableFuture<PublishBatchResponse> return, and SDK member signature
      are output에서 확인한다.
- [ ] aws-spring-boot/src/consumerFixture/kotlin/io/bluetape4k/aws/spring/sns/consumer/LegacySnsOperationsFixture.kt에
      새 batch method를 모르는 legacy SnsOperations fixture를 만들고 root
      build.gradle.kts에 awsSpringSnsConsumerFixtureClasspath와
      registerSnsConsumerFixtureCompile 및
      compileSnsOperationsLegacyConsumerFixture를 추가한다. classpath에는
      project :bluetape4k-aws-spring-boot, Spring BOM, libs.aws2.sns,
      coroutines, Spring context support, bluetape core를 명시한다.
- [ ] API 변경 전 baseline에서 다음 task를 실행하고 output class hash를
      .lane-evidence/issue-456-legacy-abi.txt에 보존한다.

~~~text
./gradlew compileSnsOperationsLegacyConsumerFixture --no-daemon
{
  find build/consumer-fixtures/aws-spring-sns/operations-legacy/classes \
    -type f -print0 | sort -z | xargs -0 shasum -a 256
  javap -classpath build/consumer-fixtures/aws-spring-sns/operations-legacy/classes \
    io.bluetape4k.aws.spring.sns.consumer.LegacySnsOperationsFixture
} | tee .lane-evidence/issue-456-legacy-abi.txt
~~~

- [ ] production interface가 변경된 뒤에는 legacy compile task를 다시 실행하지
      않는다. aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/
      SnsOperationsCompatibilityTest.kt가 위 보존 directory를 URLClassLoader로
      읽어 LegacySnsOperationsFixture를 instantiate하고 default batch dispatch를
      검증한다. test command와 preserved class hash를 함께 기록한다.
- [ ] production API는 이 task에서 바꾸지 않는다.

## Task 1: Java SDK v2 parity

### RED

- [ ] aws-java/src/test/kotlin/io/bluetape4k/aws/sns/SnsClientTest.kt,
      aws-java/src/test/kotlin/io/bluetape4k/aws/sns/SnsAsyncClientTest.kt,
      aws-java/src/test/kotlin/io/bluetape4k/aws/sns/SnsAsyncClientCoroutinesExtensionsTest.kt에
      1/10 성공, 0/11/blank/duplicate 실패, order, future cancellation,
      suspend 원본 예외, builder override를 먼저 추가한다.

~~~text
./gradlew :bluetape4k-aws-java:test --no-daemon \
  --tests "io.bluetape4k.aws.sns.SnsClientTest" \
  --tests "io.bluetape4k.aws.sns.SnsAsyncClientTest" \
  --tests "io.bluetape4k.aws.sns.SnsAsyncClientCoroutinesExtensionsTest"
~~~

### GREEN

- [ ] aws-java/src/main/kotlin/io/bluetape4k/aws/sns/model/Publish.kt에
      publishBatchRequestEntryOf와 publishBatchRequestOf를
      추가하고 attributes/FIFO/builder override를 보존한다.
- [ ] exact declaration을 계획과 구현 diff에 고정한다:
      inline publishBatchRequestEntryOf(id: String, message: String,
      messageAttributes: Map<String, MessageAttributeValue>? = null,
      messageDeduplicationId: String? = null, messageGroupId: String? = null,
      builder: PublishBatchRequestEntry.Builder.() -> Unit = {}):
      PublishBatchRequestEntry, inline publishBatchRequestOf(topicArn: String,
      entries: List<PublishBatchRequestEntry>,
      overrideConfiguration: AwsRequestOverrideConfiguration? = null,
      builder: PublishBatchRequest.Builder.() -> Unit = {}): PublishBatchRequest,
      SnsClient.publishBatch(topicArn: String, entries: List<PublishBatchRequestEntry>,
      builder: PublishBatchRequest.Builder.() -> Unit = {}): PublishBatchResponse,
      SnsAsyncClient.publishBatchAsync(request: PublishBatchRequest):
      CompletableFuture<PublishBatchResponse>, and
      SnsAsyncClient.publishBatchSuspend(request: PublishBatchRequest):
      PublishBatchResponse.
- [ ] aws-java/src/main/kotlin/io/bluetape4k/aws/sns/SnsClientExtensions.kt의
      sync publishBatch, aws-java/src/main/kotlin/io/bluetape4k/aws/sns/SnsAsyncClientExtensions.kt의
      async publishBatchAsync, aws-java/src/main/kotlin/io/bluetape4k/aws/sns/SnsAsyncClientCoroutinesExtensions.kt의
      suspend publishBatchSuspend를
      추가한다. topic/entry invariants를 경계에서 검사하고 SDK future·await 예외를
      숨기지 않는다. response ID mapping도 테스트한다.
- [ ] low-level Java/Kotlin API는 SDK exception과 future/await cancellation을
      caller에게 raw passthrough하고 Spring 경계에서만 safe wrapper로 정규화한다.
      두 경계를 섞지 않도록 low-level passthrough test와 Spring redaction test를
      분리한다.

## Task 2: AWS Kotlin SDK parity

- [ ] RED로 aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sns/SnsClientExtensionsTest.kt에
      Base58.randomString(16) ID/message를 사용한
      0/11/blank/duplicate 실패와 1/10 성공, topic ARN blank를 추가한다.
- [ ] aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/sns/SnsClientExtensions.kt의
      publishBatch가 Java와 같은 count/nonblank/distinct를 SDK 전 검사하도록 최소
      변경하고 기존 request-entry builder를 재사용한다.

~~~text
./gradlew :bluetape4k-aws-kotlin:test --no-daemon \
  --tests "io.bluetape4k.aws.kotlin.sns.SnsClientExtensionsTest"
~~~

## Task 3: Spring 모델·예외

- [ ] RED로 aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchModelsTest.kt와
      aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExceptionsTest.kt에
      serialization round-trip, serialVersionUID, redacted toString,
      CR/LF diagnostic, unknown/duplicate/missing ID, mixed result,
      completedEntryIds와 raw secret 비노출을 고정한다.
- [ ] aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchModels.kt에
      SnsPublishBatchEntry/Request/Result/Success/Failure와
      SnsBatchExecutionOptions 여섯 immutable Serializable 모델을 추가하고
      각 class에 serialVersionUID = 1L과 방어적 collection을 사용한다.
      모두 Kotlin data class와 val constructor property를 사용한다. Entry는
      id: String, message: String, subject: String?, messageAttributes:
      Map<String, MessageAttributeValue>, messageGroupId: String?,
      messageDeduplicationId: String?를, Request는 topicArn: String과
      entries: List<SnsPublishBatchEntry>를, Result는 successful/failed 목록과
      isFullySuccessful을, Success는 entryId/messageId/sequenceNumber를,
      Failure는 entryId/code/message/senderFault를, Options는
      maxInFlightBatches: Int를 보존한다.
- [ ] aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExceptions.kt에
      SnsBatchTransportException과 protocol exception을 추가한다. failureType은
      SDK_SERVICE, CLIENT, TIMEOUT, UNKNOWN 중 하나만 허용하고 diagnostic은 count와
      entry fingerprint만 공개하며 raw cause는 보관하지 않는다. test에서
      failureType membership와 public getMessage/toString/stack trace/suppressed에
      payload·ARN·attributes·raw throwable·CR/LF가 없는지 확인한다. 성공·실패는
      input order로 정렬한다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sns.SnsBatchModelsTest" \
  --tests "io.bluetape4k.aws.spring.sns.SnsBatchExceptionsTest"
~~~

## Task 4: SnsOperations·template 호환성

- [ ] RED로 legacy fixture와 NoopSnsOperations의 source/binary compatibility,
      default sequential fallback, first failure stop, successful prefix,
      CancellationException 원본 전달을 검증한다. options=4 또는 큰 값을
      전달해도 fallback의 실제 동시성은 1이고 input order·prefix semantics가
      유지되는지 직접 assertion한다.
- [ ] aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsOperations.kt에
      additive batch default를 추가한다. 기존 구현체가 새 abstract
      method 없이 compile/source/binary compatible해야 하고 retry/parallel/rollback은
      수행하지 않는다. 공개 함수는 suspend publishBatch(request:
      SnsPublishBatchRequest, options: SnsBatchExecutionOptions =
      SnsBatchExecutionOptions()): SnsPublishBatchResult이며 default fallback에서는
      options를 1로 해석한다.
- [ ] aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/NoopSnsOperations.kt와
      aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsCoroutinesTemplate.kt를
      갱신하되 single publish semantics와
      client ownership을 유지한다. fixture javap/classloader dispatch를 재검증한다.

## Task 5: Spring executor RED

- [ ] aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutorTest.kt에
      N=0,1,9,10,11,20,21,100 matrix와 exact chunk 수를 먼저 고정한다.
- [ ] order 복원, mixed result, worker count 1/2/4,
      lazy iterator, resident entry 10 * maxInFlightBatches, sibling failure,
      collector/underlying cancellation, permit 반환, no-retry를 RED로 추가한다.
- [ ] blocking publisher latency 자체는 timeout proof로 주장하지 않고 controlled fake의
      cancellation/cleanup만 관찰한다. high-volume 입력은 Base58.randomString(16)을
      사용하고 payload/ARN이 diagnostics에 나타나지 않는지 확인한다.
- [ ] subject/attributes/FIFO mapping, standard/FIFO validation, protocol ID guard,
      low-level future cancellation, completedEntryIds와 failureType도 포함한다.
- [ ] 다음 named race tests를 barrier-controlled fake publisher와 runTest
      virtual scheduler로 먼저 작성한다: claim 직후 caller cancellation,
      permit 대기 중 cancellation, sibling transport failure 중 future
      cancellation, mixed-success 선행 chunk와 sibling failure, 모든 경로의
      permit 반환 및 pending future 0건. RED는 GREEN과 동일한 exact 명령으로
      구현 전 실행한다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sns.SnsBatchExecutorTest"
~~~

- [ ] maxInFlightBatches가 0 또는 음수이면 즉시 거부하고, maxInFlightBatches가
      chunkCount보다 큰 경우 worker 수가 chunkCount를 넘지 않는다는 assertion을
      추가한다. resident bound는 entry뿐 아니라 worker, pending task, iterator
      대기열까지 10 * maxInFlightBatches 안에 있음을 observable counter로 검증한다.

## Task 6: 고정 worker executor GREEN

- [ ] aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExecutor.kt를
      추가한다. lazy 10-entry chunk, empty fast path,
      sequential path, fixed workers, shared Mutex iterator claim을 사용한다.
- [ ] SDK future await는 Semaphore.withPermit 경계에서 수행하고 permit은 finally로
      반환한다. Mutex 안에서 SDK 호출·await·외부 cancellation을 수행하지 않는다.
- [ ] chunk sequence/entry ID로 order를 복원하고 completed IDs, protocol redaction,
      sibling/future best-effort cancellation, operation-state 해제를 구현한다.
      coroutineScope 안에서 first failure가 sibling을 취소하고 원래
      CancellationException 또는 safe transport wrapper를 전파하도록 명시한다.
      completedEntryIds는 terminal response 집합이며 item-level success/retry
      집합이 아님을 구현·테스트에 고정하고, 취소 경로에는 완료 ID를 추가하지 않는다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sns.SnsCoroutinesTemplateTest" \
  --tests "io.bluetape4k.aws.spring.sns.SnsBatchExecutorTest" \
  --tests "io.bluetape4k.aws.spring.sns.SnsAutoConfigurationTest"
~~~

## Task 7: 문서·후속 이슈

- [ ] README.md, README.ko.md, aws-spring-boot/README.md,
      aws-spring-boot/README.ko.md에
      Java sync/async, Kotlin SDK, Spring batch 예제를 같은 구조로 추가한다.
      10개 제한, partial result, cancellation, no-retry, FIFO/idempotency caller
      responsibility, low-level raw passthrough와 Spring safe-wrapper 경계를
      설명하고 payload/ARN 로그 예제는 넣지 않는다.
- [ ] mixed-success 선행 chunk와 sibling transport failure가 함께 발생하면
      전체 request를 재처리하지 않고, completedEntryIds 밖의 entry는 발행되지
      않았다고 가정하지 않으며, FIFO deduplication 또는 외부 idempotency가
      없을 때 수동 reconciliation을 수행한다는 동일 anchor/단계 절차를 양국
      README에 기록한다. SNS business rollback/보상 트랜잭션은 제공하지 않는다.
- [ ] #514/#515 링크와 범위를 문서에 반영하고 git diff --check와 bilingual
      heading/link parity를 확인한다.

## Task 8: 통합 검증·복구 경계

~~~text
./gradlew :bluetape4k-aws-java:test \
  :bluetape4k-aws-kotlin:test \
  :bluetape4k-aws-spring-boot:test \
  -DskipAwsEmulatorTests=true --no-daemon
./gradlew detekt --no-daemon
./gradlew build -x test --parallel --no-daemon
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon \
  --tests "io.bluetape4k.aws.spring.sns.SnsOperationsCompatibilityTest"
~~~

- [ ] Floci smoke는 opt-in으로 -Dbluetape4k.aws.emulator=floci를 사용하고,
      Testcontainers가 공유 Docker/Colima 자원을 점유하므로 다른 emulator test와
      동시에 실행하지 않는다. capability 미지원, socket/mount 오류, 또는
      Colima 장애면 deterministic mock 결과를 필수 증거로 남기고 skip 사유와
      원래 오류를 기록하며 emulator PASS로 집계하지 않는다.
- [ ] fixture compile/runtime, javap ABI, redaction, cancellation, completed ID를
      최종 확인한다. SNS business rollback은 지원하지 않음을 별도로 확인한다.
      legacy fixture compile task는 Task 0 baseline 이후 다시 실행하지 않고,
      보존한 class/hash와 SnsOperationsCompatibilityTest의 URLClassLoader
      결과만 ABI 증거로 사용한다.
      코드 coordinator/template 변경을 되돌릴 때만 하나의 작업 복구 단위로
      보존하고 RED evidence는 유지한다. cancellation/drain production 변경을
      서로 부분적으로 되돌리는 복구는 금지한다.

## 커밋·PR 게이트

- [ ] 계획 승인 후 설계 명세, 계획, 설계 review, plan review를 Lore protocol로
      먼저 커밋한다. Constraint, Rejected, Confidence, Scope-risk, Directive,
      Tested, Not-tested trailer를 포함한다.
- [ ] generated .lane-inputs/와 transient .omx/는 커밋하지 않는다. PR body는
      한국어로 작성하고 ## DoD Status로 끝낸다.
- [ ] PR 직전 AGENTS, 선택 skill, template, linked issue metadata를 다시 읽고
      gh live read-back을 수행한다. exact-head required CI가 모두 성공해야 한다.
      1인 개발자 human review는 N/A지만 CI와 exact-head merge 승인은 유지한다.
- [ ] fresh approval 전 merge, branch deletion, cleanup을 하지 않는다.

## 완료 조건

- [ ] plan review P0=0/P1=0이고 P2는 #514/#515로 분리된다.
- [ ] 계획 승인 후 명세·계획·review artifact를 커밋한다.
- [ ] RED→GREEN exact command와 API, ABI, redaction, cancellation, bounded
      concurrency, docs parity evidence를 모두 수집한다.
- [ ] CI, exact-head merge, local sync, cleanup 상태를 각각 보고한다.
