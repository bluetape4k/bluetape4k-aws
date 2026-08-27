# Issue #471 Spring Modulith SNS·SQS event externalization 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring Modulith 2.1 publication을 기존 coroutine 기반 SNS·SQS operations로 외부화하고, Floci의 SQS 수신 경로에서 허용된 event만 복원·중복 억제·성공 후 ack하는 선택적 Spring Boot 기능을 제공한다.

**Architecture:** `aws-spring-boot` 안에 `compileOnly` Modulith adapter를 추가한다. 바깥 자동 설정은 class name condition만 보유하고, nested Modulith/SNS/SQS 설정이 실제 optional type을 참조한다. outbound는 bounded coroutine transport와 service별 publisher로, inbound는 strict envelope codec, lease/fencing idempotency store, ack를 모르는 public consumer와 manual-ack internal listener로 나눈다.

**Tech Stack:** Kotlin 2.3, Spring Boot 4, Spring Modulith 2.1, AWS SDK v2 SNS/SQS, kotlinx.coroutines, Jackson 3, Micrometer, JUnit 5, MockK, Kluent, Testcontainers `FlociServer`.

---

## 1. 실행 전제와 완료 경계

- Work type: Type A - Full Feature.
- 기준 이슈: [#471](https://github.com/bluetape4k/bluetape4k-aws/issues/471), OPEN, milestone `1.0.0`, assignee `debop`.
- 기준 설계: `docs/superpowers/specs/2026-08-26-issue-471-modulith-sns-sqs-design.md`의 `reviewed-design` 상태.
- 구현 worktree: `.worktrees/feat-issue-471-modulith-sns-sqs`, branch `feat/issue-471-modulith-sns-sqs`, base `origin/develop`.
- PR delivery: `bluetape4k/bluetape4k-aws`, base `develop`, head `feat/issue-471-modulith-sns-sqs`로 생성한다. merge는 exact-head merge-ready 보고 뒤 별도 승인을 받는다.
- human review: `N/A (single-developer lane)`. exact-head CI, 독립 6관점 review, review/thread read-back은 계속 필수다.
- real AWS: 사용자에게 계정이 없으므로 `N/A`. Floci 성공과 deterministic contract test를 완료 근거로 사용하며 IAM/resource policy나 실제 AWS timing을 검증했다고 표현하지 않는다.
- 금지: SNS subscription/DLQ/store provisioning, 자체 retry loop, cross-account ARN/queue URL target, upcaster, multi-source listener, exactly-once 보장, production signature 검증을 Floci test verifier로 대체했다는 주장.

### 구현 종료 조건

1. 설계의 모든 수용 기준이 아래 task와 검증 명령에 추적된다.
2. TDD의 RED와 GREEN을 각 task에서 관찰한다.
3. module test, detekt, build, consumer fixture compile, `git diff --check`가 통과한다.
4. Floci DIRECT와 지원 가능한 SNS→SQS/FIFO/redrive 시나리오가 skip 없이 통과한다.
5. Floci가 서명 가능한 SNS notification을 만들지 못하면 서명 경계는 mock certificate fixture로 별도 통과시키고 Floci 서명 증거로 표기하지 않는다.
6. 구현 diff에 대한 6관점 review와 main integration에서 P0=0, P1=0이다.
7. Korean KDoc, README 두 locale, manual 두 locale, lesson, PR `## DoD Status`가 source와 일치한다.
8. PR exact-head CI가 terminal success인 뒤 merge-ready에서 멈춘다.

## 2. 파일 구조와 책임

### build와 등록

- Modify: `gradle/libs.versions.toml` — Modulith api/core/jackson alias.
- Modify: `aws-spring-boot/build.gradle.kts` — `compileOnly`와 test classpath.
- Modify: `build.gradle.kts` — consumer-side optional dependency compile fixture.
- Modify: `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — name-only outer auto-configuration 등록.
- Create: `aws-spring-boot/src/consumerFixture/kotlin/io/bluetape4k/aws/spring/modulith/consumer/AwsModulithConsumerFixture.kt` — 실제 소비자 classpath의 public API compile 증거.

### public contract와 codec

- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithEventTypes.kt` — registration, immutable registry, resolved registration.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithEventEnvelope.kt` — versioned envelope와 AWS attribute contract.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithEventCodec.kt` — bounded strict JSON preflight, serializer adapter, encode/decode.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithEventsProperties.kt` — root/producer/consumer/target/idempotency 속성.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithExceptions.kt` — bounded diagnostic code와 typed exception hierarchy.

### outbound

- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithTargetPublisher.kt` — AWS SDK type을 transport에서 숨기는 internal SPI와 immutable result.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithEventExternalizationTransport.kt` — Modulith transport, admission, future/job terminal state, target resolution, close.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithSnsTargetPublisher.kt` — SNS name resolution와 FIFO publish request.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithSqsTargetPublisher.kt` — SQS single-flight resolution와 full-request publish.

### inbound와 운영

- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithIdempotency.kt` — public key/token/result/store SPI.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/InMemoryAwsModulithEventIdempotencyStore.kt` — bounded process-local lease/fencing 구현.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithSqsEventConsumer.kt` — source verify, decode, claim/heartbeat, loop guard, local publish, complete.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithSqsEventListener.kt` — package-private `@SqsListener`와 success-only manual ack.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithEventsAutoConfiguration.kt` — name-only outer config와 nested optional configs.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithMetrics.kt` — low-cardinality counters/timers/gauges와 no-op fallback.

### tests와 문서

- Create: matching `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/modulith/*Test.kt` files named in each task.
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithEventsFlociTest.kt`.
- Modify: `README.md`, `README.ko.md` — capability summary와 manual links.
- Modify: `docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`.
- Modify: `docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`.
- Modify: `docs/manual/en/modules/bluetape4k-aws-spring-boot/auto-configuration.md`.
- Modify: `docs/manual/ko/modules/bluetape4k-aws-spring-boot/auto-configuration.md`.
- Modify: `docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md`.
- Modify: `docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md`.
- Create: `docs/lessons/2026-08-26-issue-471-modulith-sns-sqs.md`.
- Create: `docs/review/2026-08-26-issue-471-modulith-sns-sqs-code-review.md`.

## 3. 작업 순서

### Task 1: optional dependency와 compile boundary 고정

**Complexity:** M
**Depends on:** 승인된 설계와 본 계획
**Write scope:** `gradle/libs.versions.toml`, `aws-spring-boot/build.gradle.kts`, `build.gradle.kts`, consumer fixture
**Required skills:** `$bluetape-kotlin-patterns`, `test-driven-development`; Spring trigger는 `references/spring-boot.md`, test trigger는 `references/testing.md`
**Expected DoD:** Modulith가 없는 runtime은 영향이 없고, 명시적으로 추가한 consumer classpath에서는 public contract가 compile된다.

- [ ] **Step 1.1: RED consumer fixture를 추가한다**

  `AwsModulithConsumerFixture.kt`에 다음 실제 사용 형태를 먼저 작성한다.

  ```kotlin
  package io.bluetape4k.aws.spring.modulith.consumer

  import io.bluetape4k.aws.spring.modulith.AwsModulithEventTypeRegistration
  import io.bluetape4k.aws.spring.modulith.AwsModulithEventTypeRegistry

  class FixtureEvent(val id: String)

  val fixtureRegistry = AwsModulithEventTypeRegistry.of(
      AwsModulithEventTypeRegistration(
          type = "fixture.event",
          version = 1,
          eventClass = FixtureEvent::class.java,
          eventId = FixtureEvent::id,
      ),
  )
  ```

  root `build.gradle.kts`에 `awsSpringModulithConsumerFixtureClasspath`와 `compileAwsSpringModulithConsumerFixture`를 등록한다. classpath에는 project jar, Spring Boot BOM, `spring-modulith-events-api`, `spring-modulith-events-core`, `spring-modulith-events-jackson`, SNS, SQS, sns-message-manager, coroutines, Spring context만 둔다.

- [ ] **Step 1.2: RED를 관찰한다**

  Run:

  ```bash
  ./gradlew compileAwsSpringModulithConsumerFixture --no-daemon --no-build-cache
  ```

  Expected: `AwsModulithEventTypeRegistration`과 `AwsModulithEventTypeRegistry`가 아직 없어 Kotlin compile이 FAIL한다. dependency resolution 자체가 실패하면 contract failure와 분리해 catalog/BOM 문제부터 고친다.

- [ ] **Step 1.3: version catalog와 module dependency를 최소로 추가한다**

  `gradle/libs.versions.toml`:

  ```toml
  spring-modulith-events-api = { module = "org.springframework.modulith:spring-modulith-events-api" }
  spring-modulith-events-core = { module = "org.springframework.modulith:spring-modulith-events-core" }
  spring-modulith-events-jackson = { module = "org.springframework.modulith:spring-modulith-events-jackson" }
  ```

  `aws-spring-boot/build.gradle.kts`:

  ```kotlin
  compileOnly(libs.spring.modulith.events.api)
  compileOnly(libs.spring.modulith.events.core)
  compileOnly(libs.spring.modulith.events.jackson)
  testImplementation(libs.spring.modulith.events.api)
  testImplementation(libs.spring.modulith.events.core)
  testImplementation(libs.spring.modulith.events.jackson)
  ```

  version은 root의 Spring dependency management가 관리한다. library의 runtime transitive dependency로 바꾸지 않는다.

- [ ] **Step 1.4: 최소 public registration skeleton을 추가하고 GREEN을 관찰한다**

  `AwsModulithEventTypes.kt`에 Task 2가 확장할 다음 public signature를 추가한다.

  ```kotlin
  data class AwsModulithEventTypeRegistration<T : Any>(
      val type: String,
      val version: Int,
      val eventClass: Class<T>,
      val eventId: (T) -> String,
      val allowedHeaderNames: Set<String> = emptySet(),
      val headers: (T) -> Map<String, String> = { emptyMap() },
  )

  class AwsModulithEventTypeRegistry private constructor(
      registrations: List<AwsModulithEventTypeRegistration<*>>,
  ) {
      companion object {
          fun of(vararg registrations: AwsModulithEventTypeRegistration<*>): AwsModulithEventTypeRegistry =
              AwsModulithEventTypeRegistry(registrations.toList())
      }
  }
  ```

  Run the fixture compile again. Expected: PASS.

- [ ] **Step 1.5: optional dependency graph를 검증한다**

  Run:

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:dependencies --configuration runtimeClasspath --no-daemon
  ./gradlew :bluetape4k-aws-spring-boot:dependencies --configuration compileClasspath --no-daemon
  ```

  Expected: Modulith artifact는 compile classpath에 있고 runtimeClasspath에는 library가 새로 강제하지 않는다.

- [ ] **Step 1.6: commit**

  ```bash
  git add gradle/libs.versions.toml aws-spring-boot/build.gradle.kts build.gradle.kts \
    aws-spring-boot/src/consumerFixture aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithEventTypes.kt
  git commit
  ```

  Intent line: `feat: Modulith 선택 의존성을 기존 소비자 classpath와 격리한다`

  **Rollback/rerun:** alias나 BOM resolution이 흔들리면 root dependency-management source를 다시 확인하고 이 task commit만 되돌린다. production behavior는 아직 활성화하지 않는다.

### Task 2: registry, properties, diagnostic public contract 구현

**Complexity:** L
**Depends on:** Task 1
**Write scope:** event types, properties, exceptions와 해당 unit tests
**Expected DoD:** 모든 caller input과 configuration constraint가 시작 또는 publication 전에 deterministic하게 거부되고 payload/secret을 예외에 담지 않는다.

- [ ] **Step 2.1: RED registry/property/diagnostic tests를 작성한다**

  Create:

  - `AwsModulithEventTypeRegistryTest.kt`
  - `AwsModulithEventsPropertiesTest.kt`
  - `AwsModulithExceptionsTest.kt`

  Parameterized cases:

  ```kotlin
  @ValueSource(strings = ["", "Order.Placed", "-order", "order placed"])
  fun `invalid type is rejected`(type: String) { /* registry construction must throw */ }

  @Test
  fun `duplicate class and type version are rejected`() { /* exact duplicate maps */ }

  @Test
  fun `root producer consumer are opt in`() {
      val properties = AwsModulithEventsProperties()
      properties.enabled shouldBeEqualTo false
      properties.producer.enabled shouldBeEqualTo false
      properties.consumer.enabled shouldBeEqualTo false
  }
  ```

  Boundaries: registration 256, target 100, `maxInFlight` 1/1024, payload/envelope 1/262144, shutdown 1s/5m, retention 1m/7d, lease 30s/30m, `maxInProgress <= maxEntries`, non-blank queue/source mode, SNS expected ARN non-empty.

- [ ] **Step 2.2: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.modulith.AwsModulithEventTypeRegistryTest' --tests 'io.bluetape4k.aws.spring.modulith.AwsModulithEventsPropertiesTest' --tests 'io.bluetape4k.aws.spring.modulith.AwsModulithExceptionsTest' --no-daemon --no-build-cache
  ```

  Expected: missing types/validation 때문에 FAIL.

- [ ] **Step 2.3: registry를 immutable exact-match contract로 완성한다**

  Adapter-internal methods:

  ```kotlin
  internal fun registrationFor(event: Any): AwsModulithResolvedRegistration
  internal fun registrationFor(type: String, version: Int): AwsModulithResolvedRegistration
  ```

  `AwsModulithResolvedRegistration`도 `internal`로 고정한다. Resolved wrapper는 `eventClass.cast(event)` 뒤 typed lambdas를 호출한다. subclass/proxy, duplicate class, duplicate `(type, version)`, 동일 type의 복수 current version을 거부한다. caller input에는 기존 `require*` helper를 우선 사용하고, public KDoc은 한국어로 작성한다.

- [ ] **Step 2.4: properties와 target model을 구현한다**

  ```kotlin
  @ConfigurationProperties("bluetape4k.aws.modulith.events")
  data class AwsModulithEventsProperties(
      var enabled: Boolean = false,
      var producer: Producer = Producer(),
      var consumer: Consumer = Consumer(),
      var targets: Map<String, Target> = emptyMap(),
  )

  enum class AwsModulithTargetService { SNS, SQS }
  enum class AwsModulithSourceMode { DIRECT, SNS }
  ```

  alias와 destination은 non-blank name만 허용하고 `arn:`, `http://`, `https://`와 queue URL을 거부한다. standard destination에 routing key가 오면 publication 오류, `.fifo`에는 non-blank UTF-8 128-byte 이하 group ID를 요구한다.

- [ ] **Step 2.5: diagnostic code와 typed exception을 구현한다**

  ```kotlin
  enum class AwsModulithCallerAction {
      STOP_DEPLOYMENT,
      FIX_PAYLOAD,
      RESUBMIT_PUBLICATION,
      CHECK_AWS_AND_RESUBMIT,
      QUARANTINE_SOURCE,
      DEPLOY_COMPATIBLE_CONSUMER,
      RECOVER_STORE_AND_RETRY,
      INSPECT_DISPATCH_OR_ACK,
  }

  enum class AwsModulithDiagnosticCode(
      val value: String,
      val retryable: Boolean,
      val callerAction: AwsModulithCallerAction,
  ) {
      CONFIGURATION("BT4K-MOD-101", false, AwsModulithCallerAction.STOP_DEPLOYMENT),
      ENVELOPE("BT4K-MOD-102", false, AwsModulithCallerAction.FIX_PAYLOAD),
      PRODUCER_LIFECYCLE("BT4K-MOD-103", true, AwsModulithCallerAction.RESUBMIT_PUBLICATION),
      AWS_PUBLISH("BT4K-MOD-104", true, AwsModulithCallerAction.CHECK_AWS_AND_RESUBMIT),
      SOURCE("BT4K-MOD-201", false, AwsModulithCallerAction.QUARANTINE_SOURCE),
      INBOUND("BT4K-MOD-202", false, AwsModulithCallerAction.DEPLOY_COMPATIBLE_CONSUMER),
      CLAIM("BT4K-MOD-203", true, AwsModulithCallerAction.RECOVER_STORE_AND_RETRY),
      DISPATCH_ACK("BT4K-MOD-204", true, AwsModulithCallerAction.INSPECT_DISPATCH_OR_ACK),
  }

  enum class AwsModulithFailurePhase {
      CONFIGURATION, SERIALIZATION, LIFECYCLE, RESOLUTION, PUBLISH,
      SOURCE, DECODE, CLAIM, DISPATCH, ACK, CLEANUP,
  }

  sealed class AwsModulithEventException protected constructor(
      val code: AwsModulithDiagnosticCode,
      val phase: AwsModulithFailurePhase,
  ) : RuntimeException("${code.value}:${phase.name}", null, true, true) {
      val retryable: Boolean get() = code.retryable
      val callerAction: AwsModulithCallerAction get() = code.callerAction
  }
  ```

  code별 `retryable`과 `callerAction`은 다음으로 고정하고 concrete exception이 이를
  override하지 못하게 test한다. public concrete exception은 catch type으로 노출하되
  constructor는 모두 `internal`로 둔다. sealed base와 code-derived property 때문에
  consumer module은 임의 subclass, retryability, action, raw message/cause를 만들 수 없다.
  4-인자 `RuntimeException` constructor는 cause를 초기화된 `null`로 잠가
  `initCause(hostileThrowable)`을 `IllegalStateException`으로 거부하고, sanitized cleanup
  exception을 suppressed로 붙일 수 있도록 suppression은 활성화한다.

  | Code | `retryable` | caller action |
  | --- | --- | --- |
  | `BT4K-MOD-101` | `false` | 시작/배포 중단 |
  | `BT4K-MOD-102` | `false` | 자동 재시도 금지, DLQ 보존 후 registration/payload 수정 |
  | `BT4K-MOD-103` | `true` | publication 미완료 유지, Modulith resubmission |
  | `BT4K-MOD-104` | `true` | publication 미완료 유지, endpoint/권한 확인 뒤 재시도 |
  | `BT4K-MOD-201` | `false` | no-ack, redrive/DLQ 격리 |
  | `BT4K-MOD-202` | `false` | no-ack, 호환 consumer 선배포 또는 DLQ 분석 |
  | `BT4K-MOD-203` | `true` | no-ack, lease/store 복구 뒤 재시도 |
  | `BT4K-MOD-204` | `true` | no-ack, completed 상태를 확인한 안전한 재처리 |

  public concrete catch ABI는 아래 catalog에만 둔다. 모든 class는
  `AwsModulithEventException`을 상속하고 exact constructor는 인자 없는
  `internal constructor()`다.

  | Public exception type | Diagnostic code | Fixed phase |
  | --- | --- | --- |
  | `AwsModulithConfigurationException` | `BT4K-MOD-101` | `CONFIGURATION` |
  | `AwsModulithEventRegistrationMismatchException` | `BT4K-MOD-102` | `SERIALIZATION` |
  | `AwsModulithOutboundEnvelopeException` | `BT4K-MOD-102` | `SERIALIZATION` |
  | `AwsModulithProducerCapacityException` | `BT4K-MOD-103` | `LIFECYCLE` |
  | `AwsModulithProducerClosedException` | `BT4K-MOD-103` | `LIFECYCLE` |
  | `AwsModulithTargetResolutionException` | `BT4K-MOD-104` | `RESOLUTION` |
  | `AwsModulithPublishException` | `BT4K-MOD-104` | `PUBLISH` |
  | `AwsModulithSourceException` | `BT4K-MOD-201` | `SOURCE` |
  | `AwsModulithInboundEnvelopeException` | `BT4K-MOD-202` | `DECODE` |
  | `AwsModulithUnknownEventTypeException` | `BT4K-MOD-202` | `DECODE` |
  | `AwsModulithUnsupportedEventVersionException` | `BT4K-MOD-202` | `DECODE` |
  | `AwsModulithInboundLoopRiskException` | `BT4K-MOD-202` | `DECODE` |
  | `AwsModulithClaimCapacityException` | `BT4K-MOD-203` | `CLAIM` |
  | `AwsModulithEventInProgressException` | `BT4K-MOD-203` | `CLAIM` |
  | `AwsModulithStaleClaimException` | `BT4K-MOD-203` | `CLAIM` |
  | `AwsModulithClaimMutationException` | `BT4K-MOD-203` | `CLAIM` |
  | `AwsModulithDispatchException` | `BT4K-MOD-204` | `DISPATCH` |
  | `AwsModulithAcknowledgementException` | `BT4K-MOD-204` | `ACK` |

  source/signature failure는 `AwsModulithSourceException`, renew/complete/release failure는
  `AwsModulithClaimMutationException`으로 묶는다. cleanup failure만 public catalog 밖의
  `internal class AwsModulithCleanupException internal constructor()`로 두고
  `BT4K-MOD-204`/`CLEANUP`에 고정한다.

  등록 불일치, unknown type/version, producer capacity/shutdown, target resolution/publish, source/signature, envelope/loop, claim/lease/complete, dispatch/ack용 concrete exception을 만든다. 일반 typed exception의 message는 diagnostic code와 bounded phase에서만 생성하고 cause는 항상 초기화된 `null`이다. untrusted AWS/source/handler throwable은 원문 message/cause chain을 public typed exception에 연결하지 않고 cause class와 bounded phase만 안전한 내부 failure summary로 바꾼다. `CancellationException`과 JVM `Error`는 sanitize하지 않고 원래 identity로 전파하되 adapter logger에 넘기거나 렌더링하지 않는다. framework/user logger가 이 원본 객체를 렌더링할 때의 message/cause 비노출은 adapter 보장으로 주장하지 않는다. message에는 event ID, body, header value, ARN/URL, AWS request/response를 넣지 않는다.

- [ ] **Step 2.6: GREEN과 로그 안전성을 확인한다**

  Run Step 2.2 command. Expected: PASS. 모든 diagnostic code를 순회해 exact `retryable`/`callerAction` mapping, sealed hierarchy, concrete constructor의 consumer 비노출, cause `null`, `initCause(hostile)`의 `IllegalStateException`, code+phase-only message를 확인한다. 추가 parameterized catalog test는 18개 concrete exception을 module 내부에서 각각 생성해 public type, fixed `code`, fixed `phase`, code-derived `retryable`/`callerAction`, code+phase-only `message`를 위 catalog의 모든 행과 직접 대조한다. 별도 log-capture assertion은 hostile cause message에 `secret-value`, event ID, header value, destination ARN/URL, AWS request/response `toString()`을 넣고 adapter-generated publish/source/dispatch exception rendering과 adapter 소유 운영 로그 어디에도 나타나지 않음을 확인한다. `CancellationException`/JVM `Error`는 identity 보존과 adapter log call 0을 별도 검증하며 framework 렌더링 no-leak assertion에 넣지 않는다. 정상 운영 로그는 untrusted throwable 전체를 logger에 넘기지 않는다.

- [ ] **Step 2.7: commit**

  Intent line: `feat: 외부화 설정과 event 등록의 실패 경계를 먼저 고정한다`

  **Rollback/rerun:** public signature가 설계와 다르면 구현을 진행하지 말고 plan/spec review를 다시 연다.

### Task 3: bounded envelope와 strict codec 구현

**Complexity:** XL
**Depends on:** Task 2
**Write scope:** envelope, codec, codec/security tests
**Expected DoD:** serializer는 한 번만 호출되고, 허용된 concrete type만 decode되며, JSON/byte/header/attribute 경계 밖 입력은 AWS/local publish 전에 실패한다.

- [ ] **Step 3.1: RED envelope/codec/security tests를 작성한다**

  Create:

  - `AwsModulithEventCodecTest.kt`
  - `AwsModulithEventCodecSecurityTest.kt`

  Required cases: round-trip, non-String `EventSerializer.serialize`, payload 196608/196609 byte, envelope 262144/262145 byte, serializer call count 1, duplicate key, depth 32/33, token 100000/100001, string 196608/196609, number 1000/1001, nested `@class`/`@type`/`@c`/`javaClass`, unknown outer field, invalid event ID, header allowlist/name/value/count, sensitive/reserved header, body-vs-attribute mismatch.

- [ ] **Step 3.2: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.modulith.AwsModulithEventCodec*' --no-daemon --no-build-cache
  ```

  Expected: codec/envelope types가 없어 FAIL.

- [ ] **Step 3.3: immutable envelope와 attributes를 구현한다**

  ```kotlin
  data class AwsModulithEventEnvelope(
      val specVersion: Int = 1,
      val id: String,
      val type: String,
      val version: Int,
      val payload: String,
      val headers: Map<String, String> = emptyMap(),
  ) : Serializable

  internal data class AwsModulithEncodedEvent(
      val body: String,
      val messageAttributes: Map<String, String>,
  )
  ```

  모든 data class에 `serialVersionUID`를 둔다. system attribute는 `bt4k-event-id`, `bt4k-event-type`, `bt4k-event-version`으로 고정한다.

- [ ] **Step 3.4: strict preflight와 serializer adapter를 구현한다**

  ```kotlin
  internal interface AwsModulithEventCodec {
      fun encode(event: Any): AwsModulithEncodedEvent
      fun decode(body: String, attributes: Map<String, String>): Any
  }
  ```

  Jackson streaming parser constraint와 duplicate detection으로 outer JSON을 먼저 검사한다. `EventSerializer.serialize(event)` 결과가 `String`인지 확인하고 같은 String을 byte count/envelope에 재사용한다. decode는 registry가 고른 final `Class<*>`만 `EventSerializer.deserialize(payload, eventClass)`에 전달한다. default typing과 classpath scanning을 사용하지 않는다.

- [ ] **Step 3.5: header와 attribute validation을 구현한다**

  이름 `[A-Za-z0-9_.-]{1,128}`, 값 UTF-8 1024 byte, 전체 attribute 10개, `bt4k-`와 case-insensitive sensitive token을 거부한다. body가 기준 데이터 원본이며 같은 system attribute가 있으면 값이 일치해야 한다.

- [ ] **Step 3.6: GREEN, allocation invariant, diff check를 실행한다**

  Run Step 3.2 command and:

  ```bash
  git diff --check
  ```

  Expected: all PASS; oversize payload에서 envelope copy와 publisher 호출 횟수는 0.

- [ ] **Step 3.7: commit**

  Intent line: `feat: 원격 event 복원 전에 JSON과 byte 경계를 닫는다`

  **Risk prediction:** parser limit이 hot path allocation을 늘릴 수 있다. serializer 1회, body copy 수, 256 KiB 경계, 100k token test로 감시하고 Task 10 성능·안정성 scan에서 재검증한다.

### Task 4: idempotency SPI, in-memory store, TCK 구현

**Complexity:** XL
**Depends on:** Task 2
**Write scope:** idempotency contract/store, TCK와 unit tests
**Expected DoD:** claim은 key당 linearizable single winner이고 lease takeover와 stale fencing이 모든 store 구현에 재사용 가능한 TCK로 증명된다.

- [ ] **Step 4.1: RED public contract TCK와 in-memory scenarios를 작성한다**

  Create:

  - `AwsModulithEventIdempotencyStoreContract.kt`
  - `InMemoryAwsModulithEventIdempotencyStoreTest.kt`

  TCK factory:

  ```kotlin
  abstract class AwsModulithEventIdempotencyStoreContract {
      abstract fun createStore(): AwsModulithEventIdempotencyStore
  }
  ```

  Cases: 64-way single winner, completed duplicate, in-progress deadline, repeat complete/release, stale renew/complete/release, lease takeover generation increment, recoverExpired active preservation, cancellation propagation, max entries/in-progress/key bytes, completed TTL/LRU, close 후 신규 claim 거부.

- [ ] **Step 4.2: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.modulith.*Idempotency*' --no-daemon --no-build-cache
  ```

- [ ] **Step 4.3: public SPI를 설계대로 구현한다**

  ```kotlin
  data class AwsModulithEventKey(val type: String, val eventId: String)
  data class AwsModulithClaimToken(
      val key: AwsModulithEventKey,
      val ownerId: String,
      val generation: Long,
      val leaseUntil: java.time.Instant,
  )
  sealed interface AwsModulithClaimResult {
      data class Acquired(val token: AwsModulithClaimToken) : AwsModulithClaimResult
      data object Completed : AwsModulithClaimResult
      data class InProgress(val leaseUntil: java.time.Instant) : AwsModulithClaimResult
  }
  enum class AwsModulithStoreMutation { APPLIED, ALREADY_APPLIED, NOT_FOUND, STALE }
  interface AwsModulithEventIdempotencyStore {
      suspend fun claim(key: AwsModulithEventKey, leaseDuration: java.time.Duration): AwsModulithClaimResult
      suspend fun renew(token: AwsModulithClaimToken, leaseDuration: java.time.Duration): AwsModulithClaimToken
      suspend fun complete(token: AwsModulithClaimToken): AwsModulithStoreMutation
      suspend fun release(token: AwsModulithClaimToken): AwsModulithStoreMutation
      suspend fun recoverExpired(now: java.time.Instant): Int
  }
  ```

- [ ] **Step 4.4: bounded in-memory implementation을 구현한다**

  Monitor/synchronized를 쓰지 않고 explicit `ReentrantLock` 또는 atomic map operation으로 key별 CAS를 선형화한다. active entry는 자동 축출하지 않고, completed만 retention/LRU로 지운다. close는 새 claim을 막고 owned state만 비운다. owner ID와 event ID를 로그/metric tag에 남기지 않는다.

- [ ] **Step 4.5: GREEN과 반복 안정성을 확인한다**

  Run Step 4.2 command three times. Expected: 3/3 PASS, flaky race 0.

- [ ] **Step 4.6: commit**

  Intent line: `feat: 중복 처리의 lease와 fencing 계약을 재사용 가능하게 고정한다`

  **Rollback/rerun:** race가 한 번이라도 재현되면 retry PASS로 닫지 않고 raw failure를 보존한 뒤 Task 4부터 다시 시작한다.

### Task 5: service publisher와 bounded outbound transport 구현

**Complexity:** XL
**Depends on:** Tasks 2, 3
**Write scope:** target publisher SPI, SNS/SQS publisher, transport와 unit/contract tests
**Expected DoD:** actual operations 성공 전 Modulith future가 완료되지 않으며, capacity/lifecycle/cancellation race가 first-terminal-wins와 bounded state를 지킨다.

- [ ] **Step 5.1: RED publisher request tests를 작성한다**

  Create:

  - `AwsModulithSnsTargetPublisherTest.kt`
  - `AwsModulithSqsTargetPublisherTest.kt`

  Cases: name-only resolution, missing destination, ARN/URL rejection, standard key rejection, FIFO group ID, SHA-256 event ID deduplication, headers/system attributes, SQS markerless operations rejection, SNS/SQS response message ID는 presence만 result에 남음, SQS concurrent first resolution single-flight, failure entry eviction. SNS publisher는 기존 `SnsTopicArnResolver`의 single-flight를 재사용하므로 `SnsTopicArnResolverTest`의 concurrent cold-cache call-count 1 regression과 adapter의 `findTopicArn` 단일 호출을 함께 실행한다.

- [ ] **Step 5.2: RED transport concurrency/lifecycle tests를 작성한다**

  Create `AwsModulithEventExternalizationTransportTest.kt` with a blocking fake publisher. Cases: exact signature `externalize(Any, RoutingTarget): CompletableFuture<*>`, success 전 incomplete, operation failure exceptional, `maxInFlight + 32` admission, over-capacity immediate failure/no job/no call, permit release/re-admission, caller future cancellation → child cancellation, AWS success-vs-cancel, externalize-vs-close barrier, timeout cancellation, concurrent/repeated close shared completion, post-close child 0, AWS operations never closed.

- [ ] **Step 5.3: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.modulith.AwsModulith*SnsTargetPublisherTest' --tests 'io.bluetape4k.aws.spring.modulith.AwsModulith*SqsTargetPublisherTest' --tests 'io.bluetape4k.aws.spring.modulith.AwsModulithEventExternalizationTransportTest' --no-daemon --no-build-cache
  ```

- [ ] **Step 5.4: AWS-independent internal publisher SPI를 구현한다**

  ```kotlin
  internal data class AwsModulithPublishCommand(
      val targetAlias: String,
      val destination: String,
      val routingKey: String?,
      val eventId: String,
      val encoded: AwsModulithEncodedEvent,
  )

  internal data class AwsModulithPublishResult(
      val service: AwsModulithTargetService,
      val targetAlias: String,
      val providerMessageIdPresent: Boolean,
  )

  internal fun interface AwsModulithTargetPublisher {
      suspend fun publish(command: AwsModulithPublishCommand): AwsModulithPublishResult
  }
  ```

- [ ] **Step 5.5: SNS/SQS adapter를 구현한다**

  SNS는 single-flight와 bounded cache가 이미 적용된 `SnsOperations.findTopicArn` 후 `SnsPublishRequest`; SQS는 `getQueueUrl` 후 `SqsSendRequest`를 `SqsFullRequestOperations.send`에 전달한다. SQS cache는 configured alias 수로 bounded하고 `Deferred` single-flight 실패/cancellation entry를 제거한다. SDK operations/client ownership은 application/Spring에 남긴다.

- [ ] **Step 5.6: transport를 구현한다**

  ```kotlin
  internal class AwsModulithEventExternalizationTransport(/* ... */) :
      EventExternalizationTransport, AutoCloseable {
      override fun externalize(payload: Any, target: RoutingTarget): CompletableFuture<*>
      override fun close()
  }
  ```

  `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, queue 없는 `Semaphore.tryAcquire`, `OPEN -> CLOSING -> CLOSED`, lock 안 admission, atomic first-terminal-wins를 사용한다. `CancellationException`은 그대로 전파하고 broad catch 전에 분리한다. close timeout cleanup만 `NonCancellable`로 bounded하게 실행한다. operational component는 `KLogging()` 또는 `KLoggingChannel()`로 code/phase/alias만 남긴다.

- [ ] **Step 5.7: GREEN과 bounded-load를 관찰한다**

  Run Step 5.3 command. Expected: all PASS and fake publisher maximum active count `<= maxInFlight`.

- [ ] **Step 5.8: commit**

  Intent line: `feat: Modulith publication 완료를 실제 SNS·SQS 결과에 결박한다`

  **Risk prediction:** future/job/cancel/close race와 single-flight cache가 stability hot spot이다. Task 10.2와 10.4에서 deterministic 100회 반복 test와 independent performance/stability review를 강제한다.

### Task 6: inbound source, consumer, success-only ack 구현

**Complexity:** XL
**Depends on:** Tasks 3, 4
**Write scope:** public consumer, internal listener, source parser/verifier adapter, metrics와 tests
**Expected DoD:** source trust와 decode가 claim보다 먼저 끝나고, completed 뒤에만 ack하며, 일반 failure는 안전한 code/phase로 정규화하고 cancellation/Error identity는 보존한다.

- [ ] **Step 6.1: RED source/consumer/listener tests를 작성한다**

  Create:

  - `AwsModulithSqsEventConsumerTest.kt`
  - `AwsModulithSqsEventListenerTest.kt`
  - `AwsModulithSnsSourceVerifierTest.kt`
  - `AwsModulithMetricsTest.kt`

  Cases: DIRECT rejects SNS-like body; SNS raw body 262144/262145 byte; strict `Notification` discriminator/required fields; duplicate `TopicArn`/`Message`; unknown field; depth 32/33, token 100000/100001, string 196608/196609, number 1000/1001; exact expected ARN; structural-only unsigned reject; unexpected ARN이면 verifier call count 0; registry decode before claim; completed duplicate skips publish; in-progress/capacity no ack; loop-risk release/no ack; heartbeat every lease/3; handler success → stop heartbeat → complete; handler+release failure precedence; cancellation+release; JVM `Error`+release; non-cooperative release timeout; renew failure; complete failure; complete success+ack failure preserves completed; duplicate+ack failure; no payload/event ID/ARN/message ID metric tag. 일반 hostile handler/cleanup message와 cause는 adapter-generated thrown exception, sanitized suppressed array, adapter 소유 log 어느 곳에도 없어야 한다. hostile `CancellationException`/JVM `Error`는 같은 객체 identity와 adapter log call 0을 검증하고 framework 렌더링은 no-leak assertion에서 제외한다.

- [ ] **Step 6.2: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.modulith.AwsModulithSqsEvent*Test' --tests 'io.bluetape4k.aws.spring.modulith.AwsModulithSnsSourceVerifierTest' --tests 'io.bluetape4k.aws.spring.modulith.AwsModulithMetricsTest' --no-daemon --no-build-cache
  ```

- [ ] **Step 6.3: public consumer outcome contract를 구현한다**

  ```kotlin
  enum class AwsModulithConsumeOutcome { PROCESSED, COMPLETED_DUPLICATE }

  class AwsModulithSqsEventConsumer internal constructor(
      sourceDecoder: AwsModulithInboundSourceDecoder,
      registry: AwsModulithEventTypeRegistry,
      store: AwsModulithEventIdempotencyStore,
      externalization: org.springframework.modulith.events.EventExternalizationConfiguration,
      eventPublisher: org.springframework.context.ApplicationEventPublisher,
      properties: AwsModulithEventsProperties.Consumer,
      metrics: AwsModulithMetrics,
      clock: java.time.Clock,
  ) {
      suspend fun consume(message: SqsReceivedMessage): AwsModulithConsumeOutcome
  }
  ```

  constructor와 `AwsModulithInboundSourceDecoder`, `AwsModulithMetrics`는 Spring 조립용
  `internal` 경계다. caller는 bean을 주입받아 `consume`만 호출하며 직접 생성하지 않는다.

  DIRECT 순서: raw body byte bound → SNS-like discriminator 거부 → envelope/attribute decode → registry deserialize. SNS 순서: raw body byte bound → bounded Notification JSON preflight(duplicate/unknown/depth/token/string/number) → strict discriminator와 required fields → exact `TopicArn` allowlist → `SnsHttpMessageVerifier` → inner envelope/attribute decode → registry deserialize. 공통 처리 뒤 claim → loop guard → structured heartbeat + synchronous `ApplicationEventPublisher.publishEvent` → heartbeat stop → fencing complete를 실행한다. user store는 닫지 않는다.

- [ ] **Step 6.4: failure precedence와 cancellation cleanup을 구현한다**

  handler failure/cancellation/Error의 release만 `withContext(NonCancellable)`에 두되 `cleanupTimeout = minOf(sqsProperties.listener.stopTimeoutMillis, leaseDuration.toMillis() / 3).coerceAtLeast(1)`로 `withTimeout(cleanupTimeout)`을 적용한다. 일반 handler throwable은 원문 cause 없이 cause class와 bounded phase만 internal summary로 보존한 `AwsModulithDispatchException()`으로 전파한다. cleanup throwable과 timeout도 raw cause 없이 sanitized cleanup exception으로 바꿔 suppressed에 붙인다. `CancellationException`과 JVM `Error`만 원래 객체 identity를 재전파하며 sanitized cleanup failure가 primary를 덮지 않는다. adapter는 identity-preserved 객체를 log/render하지 않고 framework/user logger의 렌더링은 adapter no-leak claim에서 제외한다. lease 만료와 fencing takeover로 복구하고 renew/stale/complete failure를 성공으로 바꾸지 않는다. `ApplicationEventPublisher` 반환은 동기 dispatch까지만 성공이며 async/transactional handler completion으로 설명하지 않는다.

- [ ] **Step 6.5: package-private listener를 구현한다**

  ```kotlin
  internal class AwsModulithSqsEventListener(
      private val consumer: AwsModulithSqsEventConsumer,
  ) {
      @SqsListener(
          queue = "\${bluetape4k.aws.modulith.events.consumer.queue}",
          acknowledgementMode = SqsAcknowledgementMode.MANUAL,
      )
      suspend fun onMessage(message: SqsReceivedMessage, acknowledgement: SqsAcknowledgement) {
          consumer.consume(message)
          acknowledgement.acknowledge()
      }
  }
  ```

  exception/cancellation을 catch해 정상 반환하지 않는다.

- [ ] **Step 6.6: bounded metrics와 safe logging을 구현한다**

  publish latency/success/failure, resolution failure, in-flight/capacity reject, source reject/redelivery, claim state/takeover, ack failure를 bounded tag `service`, `phase`, `outcome`, `code`로만 기록한다. `MeterRegistry`가 없으면 no-op이다.

- [ ] **Step 6.7: GREEN과 cancellation test 반복을 실행한다**

  Run Step 6.2 command three times. Expected: 3/3 PASS. `runTest` virtual time과 barrier fake로 heartbeat/release 순서를 고정하고 각 test는 5초 timeout 안에 끝나며 active heartbeat job/claim이 0인지 확인한다.

- [ ] **Step 6.8: commit**

  Intent line: `feat: 원격 event는 completed claim 뒤에만 확인한다`

  **Rollback/rerun:** failure precedence가 설계 표와 다르면 consumer 구현을 최소화하기 전에 test를 설계 표에 맞게 다시 잠그고 Task 6 전체를 재실행한다.

### Task 7: name-only auto-configuration과 classloading guard 구현

**Complexity:** XL
**Depends on:** Tasks 2–6
**Write scope:** auto-configuration, imports, condition/redrive/Modulith integration tests
**Expected DoD:** root opt-in과 classpath condition을 모두 만족할 때만 필요한 nested bean이 생기며, 미사용 SNS/SQS/Modulith SDK가 없어도 context가 시작한다.

- [ ] **Step 7.1: RED auto-configuration matrix를 작성한다**

  Create:

  - `AwsModulithEventsAutoConfigurationTest.kt`
  - `AwsModulithEventsClasspathIsolationTest.kt`
  - `AwsModulithPublicationCompletionIntegrationTest.kt`

  Use `ApplicationContextRunner` and `FilteredClassLoader`. Cases: no Modulith → zero adapter beans; `EventExternalizerModuleListener` only missing → zero; root false → zero; producer/consumer independent opt-in; user transport/store back-off; custom transport + inbound consumer coexist; missing registry/serializer/target/queue/full-request capability fail with `BT4K-MOD-101`; SNS SDK absent SQS-only starts; SQS SDK absent SNS-only starts; `sns-message-manager` absent DIRECT starts/SNS fails; consumer DIRECT/SNS validation; expected ARN/verifier; `redrive-required=true` and no `RedrivePolicy` startup fail; owned transport/store close only.

- [ ] **Step 7.2: actual Modulith completion RED test를 작성한다**

  실제 `EventExternalizerModuleListener`, `EventSerializer`, test-local
  `RecordingEventPublicationRepository : EventPublicationRepository`, 그리고
  `DefaultEventPublicationRegistry(recordingRepository, fixedClock)`을 사용한다. transport
  future가 incomplete일 때 repository publication이 incomplete이고, success 때만
  completed, exceptional future는 resubmission 대상에 남는지 검증한다. direct transport
  mock만으로 이 acceptance를 대신하지 않는다.

- [ ] **Step 7.3: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.modulith.AwsModulithEvents*' --tests 'io.bluetape4k.aws.spring.modulith.AwsModulithPublicationCompletionIntegrationTest' --no-daemon --no-build-cache
  ```

- [ ] **Step 7.4: outer와 nested config를 구현한다**

  Outer class는 Modulith/AWS type을 signature, field, generic, annotation class literal로 참조하지 않는다.

  ```kotlin
  @AutoConfiguration(afterName = [
      "io.bluetape4k.aws.spring.sns.SnsAutoConfiguration",
      "io.bluetape4k.aws.spring.sqs.SqsAutoConfiguration",
  ])
  @ConditionalOnClass(name = [
      "org.springframework.modulith.events.support.EventExternalizationTransport",
      "org.springframework.modulith.events.core.EventSerializer",
      "org.springframework.modulith.events.support.EventExternalizerModuleListener",
  ])
  @ConditionalOnProperty(prefix = "bluetape4k.aws.modulith.events", name = ["enabled"], havingValue = "true")
  @Import(AwsModulithEventsImportSelector::class)
  class AwsModulithEventsAutoConfiguration
  ```

  Import selector가 조건 통과 후 Modulith config를, 그 안에서 service별 config를 name condition으로 선택한다. 실제 Spring Modulith 2.1 package는 transport/listener가 `org.springframework.modulith.events.support`, serializer가 `org.springframework.modulith.events.core`, externalization configuration이 `org.springframework.modulith.events`다. producer transport는 `@ConditionalOnMissingBean(EventExternalizationTransport::class)`, store/verifier는 user bean back-off를 지킨다.

- [ ] **Step 7.5: consumer redrive startup guard를 구현한다**

  consumer 시작 전에 `SqsQueueAttributesOperations.getQueueAttributes(queueUrl, listOf(QueueAttributeName.REDRIVE_POLICY))`로 확인한다. `redrive-required=true`인데 capability나 policy가 없으면 `BT4K-MOD-101`로 startup fail한다. async fire-and-forget validation은 허용하지 않는다.

- [ ] **Step 7.6: imports와 configuration metadata를 확인한다**

  `AutoConfiguration.imports`에 outer class 하나만 등록한다. configuration processor가 property metadata를 생성하는지 test/resource output으로 확인한다.

- [ ] **Step 7.7: consumer fixture를 최종 public ABI까지 확장한다**

  Task 1의 fixture에 `ConsumerInjection(val consumer: AwsModulithSqsEventConsumer)`과
  최소 `FixtureStore : AwsModulithEventIdempotencyStore` 구현을 추가한다. fixture는
  `AwsModulithEventKey`, `AwsModulithClaimToken`, `AwsModulithClaimResult`,
  `AwsModulithStoreMutation`, `AwsModulithConsumeOutcome`, `java.time.Duration`,
  `java.time.Instant`를 직접 참조하고 모든 SPI method를 override한다. constructor는
  `internal`이므로 직접 생성하지 않고 Spring constructor injection 대상 type으로만
  compile한다. 같은 외부 fixture에서 `AwsModulithEventException`의 `code`, `phase`,
  `retryable`, `callerAction`, `message`, `cause`를 읽고, Task 2 catalog의 public exception
  18개를 모두 `when`/`catch` type으로 참조한다.

- [ ] **Step 7.8: forbidden constructor를 외부 Kotlin compile로 거부한다**

  `AwsModulithForbiddenConfigurationExceptionConstructionFixture.kt`와
  `AwsModulithForbiddenDispatchExceptionConstructionFixture.kt`는 각각
  `AwsModulithConfigurationException()`과 `AwsModulithDispatchException()`을 직접
  호출한다. 정상 fixture와 분리한 external source set/task
  `awsSpringModulithForbiddenConfigurationConstructorFixture` /
  `compileAwsSpringModulithForbiddenConfigurationConstructorFixture`,
  `awsSpringModulithForbiddenDispatchConstructorFixture` /
  `compileAwsSpringModulithForbiddenDispatchConstructorFixture`를 만들되 `check`
  dependency에는 넣지 않는다. 다음 command는 두 expected-failure를 독립 검증한다.

  ```bash
  mkdir -p build/consumer-fixtures
  set +e
  ./gradlew compileAwsSpringModulithForbiddenConfigurationConstructorFixture --no-daemon --no-build-cache \
    > build/consumer-fixtures/aws-spring-modulith-forbidden-configuration.log 2>&1
  configuration_fixture_exit=$?
  ./gradlew compileAwsSpringModulithForbiddenDispatchConstructorFixture --no-daemon --no-build-cache \
    > build/consumer-fixtures/aws-spring-modulith-forbidden-dispatch.log 2>&1
  dispatch_fixture_exit=$?
  set -e
  test "$configuration_fixture_exit" -ne 0
  test "$dispatch_fixture_exit" -ne 0
  rg -i 'internal.*AwsModulithConfigurationException|AwsModulithConfigurationException.*internal' \
    build/consumer-fixtures/aws-spring-modulith-forbidden-configuration.log
  rg -i 'internal.*AwsModulithDispatchException|AwsModulithDispatchException.*internal' \
    build/consumer-fixtures/aws-spring-modulith-forbidden-dispatch.log
  ```

  Expected: external Kotlin compiler가 두 constructor의 `internal` 접근을 거부한다. 같은
  module friend-path test나 Java bytecode visibility만으로 대신하지 않는다.

- [ ] **Step 7.9: GREEN과 classloading proof를 실행한다**

  Run Step 7.3 and:

  ```bash
  ./gradlew compileAwsSpringModulithConsumerFixture :bluetape4k-aws-spring-boot:compatibilityTest --no-daemon --no-build-cache
  ```

  Expected: PASS, no `NoClassDefFoundError`. `EventExternalizerModuleListener`만 빠진 filtered context는 adapter bean 0이며, `sns-message-manager`가 없는 DIRECT consumer는 시작하고 SNS consumer는 `BT4K-MOD-101`로 fail closed한다.

- [ ] **Step 7.10: commit**

  Intent line: `feat: Modulith와 AWS service classpath가 있을 때만 adapter를 조립한다`

  **Hazard check:** module 추가는 없으므로 settings/BOM/Kover 등록은 N/A. shared catalog, root fixture, auto-configuration imports는 applicable이며 diff와 task output을 별도 확인한다.

### Task 8: Floci DIRECT/SNS/FIFO/redrive 통합 검증

**Complexity:** XL
**Depends on:** Task 7
**Write scope:** Floci integration test와 bounded test fixtures only
**Heavy-command limit:** Docker-backed Gradle invocation 1개씩 순차 실행
**Expected DoD:** 실제 AWS 계정 없이 Floci가 지원하는 transport/consumer lifecycle이 skip 없이 통과하고, 지원하지 않는 signature API는 명시적인 별도 contract로 남는다.

- [ ] **Step 8.1: Floci capability probe를 test setup으로 고정한다**

  `AwsSpringBootTestEmulator.get("sns", "sqs")`를 사용한다. queue/topic/DLQ/subscription 생성 직후 cleanup action을 LIFO stack에 등록해 partial setup 실패도 회수한다. teardown은 resource별 10초 timeout을 적용하고 primary test failure를 보존한 채 cleanup failure를 suppressed로 기록한다. cleanup stack 자체는 partial-setup/LIFO/timeout/primary-precedence/leak-free unit test를 갖는다. runtime property는 `-Dbluetape4k.aws.emulator=floci`를 명시한다.

- [ ] **Step 8.2: RED DIRECT round-trip을 작성한다**

  local event → direct SQS target → built-in consumer → mapped non-externalized integration DTO handler. Assert type/version/payload/header, handler 1회, queue empty/ack 완료.

- [ ] **Step 8.3: RED SNS→SQS와 FIFO를 작성한다**

  standard SNS topic subscription → SQS notification → test verifier → local handler, direct FIFO SQS group/dedup, Floci가 지원하면 SNS FIFO subscription까지 검증한다. test verifier 사용 시 test name과 report에 `signature-not-proven`을 포함한다.

- [ ] **Step 8.4: RED retry/idempotency/shutdown/redrive를 작성한다**

  Cases: duplicate envelope 두 번 → handler 1회/ack 2회; unknown type/version/malformed → no delete/visibility 후 재수신; publish failure → exceptional/incomplete; close 중 blocking publish → timeout/cancel; poison message → configured maxReceiveCount 뒤 DLQ.

- [ ] **Step 8.5: signature contract fixture를 분리한다**

  기존 `SnsHttpMessageVerifier`의 mock certificate/request fixture로 valid/invalid signature, unexpected TopicArn, 구조만 맞는 unsigned notification을 검증한다. Floci test verifier와 같은 test class/claim에 섞지 않는다.

- [ ] **Step 8.6: RED를 관찰하고 최소 fixture/config를 구현한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.modulith.AwsModulithEventsFlociTest' --no-daemon --no-build-cache -Dbluetape4k.aws.emulator=floci
  ```

  Expected first run: missing test wiring or adapter defect로 FAIL. Floci capability gap은 test skip으로 바꾸지 않고 지원 matrix와 deterministic fallback assertion으로 분리한다.

- [ ] **Step 8.7: GREEN을 두 번 순차 실행한다**

  Run Step 8.6 command twice, never in parallel. Expected: 2/2 PASS; JUnit XML과 Floci logs에서 skip 0.

- [ ] **Step 8.8: commit**

  Intent line: `test: AWS 계정 없이 Modulith SNS·SQS 경계를 Floci로 증명한다`

  **Rollback/rerun:** bind-mount `operation not supported`이면 Colima가 건강한지 확인하고 VM을 재시작하지 않는다. 실제 adapter 실패와 환경 실패를 분류한 뒤 동일 command를 다시 실행한다.

### Task 9: README/manual/KDoc와 운영 계약 동기화

**Complexity:** L
**Depends on:** Tasks 1–8의 실제 API와 test 결과
**Write scope:** README 두 locale, manual 여섯 파일, KDoc corrections
**Required skills:** `$bluetape-writer`; Korean files는 naturalness/terminology audit
**Expected DoD:** copy-paste recipe가 source와 일치하고 README는 요약, manual은 상세 기준 정보로 역할을 나눈다.

- [ ] **Step 9.1: source-backed documentation ledger를 만든다**

  실제 public class/property/diagnostic code/test name과 manual section을 표로 매핑한다. 아직 없는 API를 문서에 쓰지 않는다.

- [ ] **Step 9.2: manual EN/KO를 같은 구조로 갱신한다**

  `storage-and-messaging.md`: BOM dependency recipe, registry bean, logical target, producer-only, DIRECT, SNS, FIFO.
  `auto-configuration.md`: opt-in, classpath conditions, custom transport/store/verifier back-off, one queue/source per context.
  `runtime-operations.md`: metrics, diagnostic catalog, redrive/DLQ, consumer-first rollout, rollback, at-least-once/idempotency, Floci-vs-real-AWS evidence boundary.

- [ ] **Step 9.3: README EN/KO를 요약한다**

  full manual을 복제하지 않고 feature matrix, 최소 dependency/property snippet, manual links만 둔다. root BOM과 unversioned coordinates를 사용한다.

- [ ] **Step 9.4: public KDoc를 한국어로 최종 확인한다**

  registry, consumer, idempotency SPI, properties와 exception의 caller contract, lifecycle, ownership, 한계를 설명한다. `exactly-once`, async handler completion, production signature proof를 과장하지 않는다.

- [ ] **Step 9.5: locale parity와 terminology audit를 실행한다**

  manual의 producer/DIRECT/SNS copy-paste recipe는 source-backed ledger로 실제 public
  symbol과 대조하고, `AwsModulithDocumentationRecipeTest`의
  `ApplicationContextRunner`로 각 property/dependency 조합이 시작되는지 검증한다.

  ```bash
  node ~/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
    README.ko.md \
    docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md \
    docs/manual/ko/modules/bluetape4k-aws-spring-boot/auto-configuration.md \
    docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md
  ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
  ruby scripts/manual/manual_contract_test.rb
  git diff --check
  ```

  Expected: audit finding 0 또는 context별 intentional disposition, manifest/contract/diff PASS.

- [ ] **Step 9.6: commit**

  Intent line: `docs: Modulith SNS·SQS 운영 경계와 Floci 검증 범위를 공개한다`

### Task 10: 전체 검증, 성능·안정성 scan, 독립 review 수렴

**Complexity:** XL
**Depends on:** Tasks 1–9
**Write scope:** review artifact와 blocker repair only
**Expected DoD:** fresh full evidence와 6관점 review가 P0=0/P1=0으로 수렴하고 exact scoped branch가 clean하다.

- [ ] **Step 10.1: targeted tests를 dependency order로 실행한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.modulith.*' --no-daemon --no-build-cache -Dbluetape4k.aws.emulator=floci
  ```

- [ ] **Step 10.2: concurrency hot tests를 100회 반복한다**

  Create `AwsModulithConcurrencyStabilityTest.kt` with `@RepeatedTest(100)`, `runTest(timeout = 5.seconds)`, deterministic barrier fakes for transport admission/close, claim takeover, consumer cancellation, and assertions for active job/claim/permit 0 plus exact publisher call-count.

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.modulith.AwsModulithConcurrencyStabilityTest' \
    --no-daemon --no-build-cache
  ```

  Expected: JUnit XML reports 100/100 PASS per repeated scenario, failure 0, skipped 0, leak/call-count invariant PASS.

- [ ] **Step 10.3: module/full static proof를 실행한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test --no-daemon --no-build-cache -Dbluetape4k.aws.emulator=floci
  ./gradlew :bluetape4k-aws-spring-boot:detekt --no-daemon
  ./gradlew compileAwsSpringModulithConsumerFixture --no-daemon --no-build-cache
  mkdir -p build/consumer-fixtures
  set +e
  ./gradlew compileAwsSpringModulithForbiddenConfigurationConstructorFixture --no-daemon --no-build-cache \
    > build/consumer-fixtures/aws-spring-modulith-forbidden-configuration.log 2>&1
  configuration_fixture_exit=$?
  ./gradlew compileAwsSpringModulithForbiddenDispatchConstructorFixture --no-daemon --no-build-cache \
    > build/consumer-fixtures/aws-spring-modulith-forbidden-dispatch.log 2>&1
  dispatch_fixture_exit=$?
  set -e
  test "$configuration_fixture_exit" -ne 0
  test "$dispatch_fixture_exit" -ne 0
  rg -i 'internal.*AwsModulithConfigurationException|AwsModulithConfigurationException.*internal' \
    build/consumer-fixtures/aws-spring-modulith-forbidden-configuration.log
  rg -i 'internal.*AwsModulithDispatchException|AwsModulithDispatchException.*internal' \
    build/consumer-fixtures/aws-spring-modulith-forbidden-dispatch.log
  ./gradlew build -x test --parallel --no-daemon
  git diff --check
  ```

  Docker-backed module test는 다른 heavy command와 겹치지 않는다.

- [ ] **Step 10.4: `$bluetape-kotlin-patterns` final checklist와 risk scan을 완료한다**

  Validation/exception, no `!!`, cancellation, `Dispatchers.IO`, no `GlobalScope`, no monitor, resource ownership, logging, Korean KDoc, Spring condition, testing assertion quality를 현재 diff에서 확인한다. performance/stability scan은 allocation, semaphore contention, job/cache cleanup, polling/backpressure, Testcontainers stability를 확인한다.

- [ ] **Step 10.5: 구현 diff 6관점 review를 실행한다**

  performance, stability, security, operator/Ops, developer/API, user/caller를 exact branch diff에 독립 적용한다. 각 lane은 read-only이며 P0/P1/P2/P3, file:line, required edit, verdict를 반환한다. main session이 deduplicate하고 P0/P1을 수정한 뒤 affected lane과 tests를 재실행한다.

- [ ] **Step 10.6: integrated review artifact를 작성한다**

  `docs/review/2026-08-26-issue-471-modulith-sns-sqs-code-review.md`에 scope/head, validation, six-lens table, dispositions, P0/P1=0, known gaps(real AWS N/A, human review N/A)을 기록하고 `$bluetape-writer` SPW-01..05를 통과시킨다.

- [ ] **Step 10.7: blocker repair 뒤 문서 계약을 다시 잠근다**

  Task 10 review가 public API, property, diagnostic code, recipe를 바꾸면 Task 9.1–9.5,
  `AwsModulithDocumentationRecipeTest`, source-backed ledger, 두 locale terminology audit,
  manual manifest/contract를 모두 다시 실행한다. 재실행 evidence 없이는 review를 PASS로
  닫지 않는다.

- [ ] **Step 10.8: converged commit을 만든다**

  Intent line: `refactor: Modulith SNS·SQS 경계의 검증 누락을 수렴한다`

  `git status --short`, `git diff origin/develop...HEAD --stat`, `git log --format=fuller`로 intended files와 Lore trailers를 확인한다.

### Task 11: lesson, PR delivery, exact-head CI, merge-ready 정지

**Complexity:** L
**Depends on:** Task 10
**Write/external scope:** lesson, branch push, PR create/update; merge 금지
**Expected DoD:** lesson과 PR metadata가 live source와 일치하고 exact-head CI 후 CG-16에서 멈춘다.

- [ ] **Step 11.1: durable lesson을 작성·검증·commit한다**

  `docs/lessons/2026-08-26-issue-471-modulith-sns-sqs.md`에 context, 선택, Floci/signature capability gap, concurrency/ack surprise, 결과, commands, review miss, future guard를 기록한다. `$bluetape-writer` SPW-01..05와 Korean audit를 통과한 뒤 commit한다.

- [ ] **Step 11.2: PR authority와 live issue metadata를 다시 읽는다**

  CG-01..CG-10/A-01..A-09 PASS, repo/base/head authorization, issue OPEN/milestone/labels/assignee를 fresh read한다. guidance, common gates, PR body template도 PR 직전에 다시 읽는다.

- [ ] **Step 11.3: exact head를 push하고 read-back한다**

  ```bash
  git push -u origin feat/issue-471-modulith-sns-sqs
  git rev-parse HEAD
  git ls-remote origin refs/heads/feat/issue-471-modulith-sns-sqs
  ```

  Expected: local/remote SHA match. force push는 사용하지 않는다.

- [ ] **Step 11.4: Korean PR을 만들고 live metadata를 검증한다**

  Title/body/comments는 한국어, assignee `debop`, milestone `1.0.0`, issue labels를 반영한다. body는 why/what, 검증, known gaps, `Closes #471`을 포함하고 마지막 `##` section이 `## DoD Status`여야 한다. `gh pr view --json`으로 head/base/body/labels/milestone/assignee를 재확인한다.

- [ ] **Step 11.5: post-PR 6관점 review와 exact-head CI를 통과한다**

  actual PR diff와 live review/thread를 다시 읽는다. GitHub Actions `Test / aws-spring-boot` terminal success와 `test-results-aws-spring-boot` artifact를 exact PR head에서 확인한다. docs/path-filter skip이나 old SHA run은 evidence로 세지 않는다. human review subgate만 `N/A (single-developer lane)`다.

- [ ] **Step 11.6: merge-ready DoD를 보고하고 멈춘다**

  exact PR/head, checks X/Y, N/A, Blocked=0, P0/P1=0, Floci scope, real AWS N/A, unchecked `CG-16`, `CG-17`, `CG-18`을 사용자에게 보고한다. fresh merge approval 전 `gh pr merge`나 auto-merge를 실행하지 않는다.

## 4. 수용 기준 추적표

| Issue/spec 수용 기준 | 구현 task | 주 검증 |
| --- | --- | --- |
| local event → SNS/SQS externalize | 3, 5, 7, 8 | transport unit + actual Modulith completion + Floci round-trip |
| remote event → local handler | 3, 4, 6, 7, 8 | consumer/listener unit + Floci DIRECT/SNS |
| type/version/route/payload/header mapping | 2, 3, 5 | registry/codec/publisher boundary tests |
| actual publication completion/failure | 5, 7 | first-terminal-wins + `EventExternalizerModuleListener` integration |
| retry/dead-letter는 기존 listener/redrive 재사용 | 6, 7, 8 | no-ack visibility redelivery + redrive/DLQ Floci |
| idempotency와 ack boundary | 4, 6, 8 | reusable TCK + failure precedence + duplicate delivery |
| unknown type/version 재처리 | 2, 3, 6, 8 | typed error/no ack/visibility redelivery |
| FIFO group/dedup | 2, 5, 8 | request contract + direct FIFO Floci, SNS FIFO if supported |
| publish failure/duplicate/graceful shutdown | 4–6, 8, 10 | unit race matrix + Floci lifecycle + repeated stability |
| Modulith dependency 없는 app에서 bean 없음 | 1, 7 | runtime graph + `FilteredClassLoader` |
| 안전한 SNS source | 3, 6, 8 | strict structure/topic/signature contract; Floci test verifier는 별도 표기 |
| 운영/rollout/rollback 문서 | 9, 11 | EN/KO parity, manual contract, PR DoD |

## 5. 위험 예측과 중단 규칙

| 위험 | 조기 신호 | 완화/검증 | rollback/rerun |
| --- | --- | --- | --- |
| optional class eager loading | `NoClassDefFoundError`, filtered context failure | name-only outer config, service nested config | Task 7 commit으로 되돌리고 classloading matrix부터 재실행 |
| future/job/close race | permit leak, double completion, late cancellation overwrite | blocking fake, first-terminal CAS, 100회 반복 | Task 5 RED부터 재실행; retry PASS로 닫지 않음 |
| SQS resolution stampede | concurrent `getQueueUrl` call > 1 | bounded single-flight, failure eviction | cache 구현을 제거하고 correctness 우선 resolver로 축소 후 재승인 |
| unsafe deserialization | class name 선택, duplicate key 수용, oversize copy | strict preflight + registry concrete class | Task 3 전체 rollback, security lane 재검토 |
| stale claim이 newer owner를 덮음 | generation mismatch mutation 성공 | TCK 64-way race/takeover | store 구현 rollback, TCK부터 다시 GREEN |
| handler failure가 cleanup에 가려짐 | primary throwable 변경 | suppressed cleanup, bounded `NonCancellable` | failure-precedence tests부터 재실행 |
| completed 전 ack | handler 재처리 손실 | public consumer ack-free, internal listener only | Task 6 listener/consumer split 복원 |
| Floci capability를 AWS proof로 과장 | test verifier/unsupported API가 report에서 숨음 | capability matrix와 `signature-not-proven` naming | PR/문서 gate 재개방 |
| redrive 없는 poison loop | startup은 green, DLQ 이동 없음 | default `redrive-required=true`, attribute startup check | consumer enablement rollback |
| 문서와 public API drift | recipe compile 실패 | consumer fixture, source ledger, locale parity | Task 9 재실행 후 PR body 갱신 |

## 6. repository hazard와 N/A

- 새 Gradle module/artifact 없음: `settings.gradle.kts`, publishable BOM constraint, Kover module registration은 N/A.
- shared version catalog 변경: applicable. aliases가 Spring BOM에서 version을 받는지 dependency report로 증명한다.
- root build fixture 변경: applicable. fixture task 단독 PASS와 root build PASS를 요구한다.
- Spring auto-configuration imports 변경: applicable. import registration, filtered classpath, back-off ordering test를 요구한다.
- HTTP adapter 성장 없음: SNS HTTP endpoint를 추가하지 않고 기존 `SnsHttpMessageVerifier`를 재사용한다.
- Testcontainers/Floci 성장: applicable. sequential execution, resource close, skip 0, logs/JUnit artifact를 요구한다.
- workflow YAML, Nightly path filter, benchmark module 변경 없음: N/A. implementation이 `aws-spring-boot/**`를 바꾸므로 기존 `Test / aws-spring-boot` job이 exact-head에서 실행되어야 한다.
- diagram 없음: message flow는 prose/code/table로 충분하며 새 visual artifact는 N/A.
- CHANGELOG/release note 없음: 이 이슈는 release 작업이 아니며 현재 repository convention에 따라 PR delivery에서 별도 release artifact를 만들지 않는다.

## 7. 계획 승인 뒤 실행 방식

이 계획은 Type A Step 3/3-R artifact다. 독립 6관점 검토와 main integration에서 P0=0/P1=0으로 수렴하고 plan/review artifact를 Lore commit한 뒤 사용자에게 구현 승인을 요청한다. 승인 전에는 Task 1의 Kotlin production 변경을 시작하지 않는다. 승인 뒤에는 현재 세션에서 task를 순서대로 실행하며, 독립적 read-only review만 native subagent lane으로 분리한다.
