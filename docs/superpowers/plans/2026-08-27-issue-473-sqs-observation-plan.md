# Issue #473 SQS ObservationRegistry와 coroutine context 전파 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `bluetape4k-aws-spring-boot`의 SQS listener에 opt-in Micrometer Observation lifecycle을 추가하고, receive·process·실제 acknowledgement I/O를 분리해 측정하면서 coroutine dispatcher 전환 뒤에도 현재 observation을 안전하게 전파한다.

**Architecture:** 승인된 A안 Hybrid lifecycle을 따른다. optional classpath로 격리한 `SqsObservationAutoConfiguration`이 prerequisite를 만족할 때만 internal runtime과 activation marker를 만들고, 기존 BPP/container ABI는 internal setter로 유지한다. runtime은 scope를 suspension 구간에 열어 두지 않고 `ObservationRegistry.asContextElement()`를 캡처해 `withContext`로 process context를 전파하며, ACK state machine과 heartbeat의 실제 AWS I/O만 독립 observation으로 감싼다.

**Tech Stack:** Kotlin 2.3, Spring Boot 4, Micrometer Observation 1.17, Micrometer Context Propagation 1.2.1, kotlinx.coroutines, AWS SDK v2 SQS, JUnit 5, MockK, bluetape4k assertions, Testcontainers `FlociServer`, Gradle, detekt.

---

## 1. 실행 전제와 완료 경계

- Work type: Type A - Full Feature.
- 기준 이슈: [#473](https://github.com/bluetape4k/bluetape4k-aws/issues/473), OPEN, milestone `1.0.0`, assignee `debop`.
- 기준 설계: `docs/superpowers/specs/2026-08-27-issue-473-sqs-observation-design.md`, status `reviewed-design`, SHA-256 `1b63094c85c7beedd4fad754a421a9cd5f3c79b7e90ed916be3969087cf81a33`.
- 구현 worktree: `.worktrees/feat-issue-473-sqs-observation`, branch `feat/issue-473-sqs-observation`, base `origin/develop`.
- 구현은 본 계획의 별도 사용자 승인 뒤 시작한다. production code는 각 task의 실패하는 test 또는 compile fixture를 먼저 관찰한 뒤 추가한다.
- PR delivery는 `bluetape4k/bluetape4k-aws`, base `develop`, head `feat/issue-473-sqs-observation`으로 한정한다. PR 생성과 merge는 각각 별도 승인 gate다.
- human review는 `N/A (single-developer lane)`다. 독립 모델 review, exact-head CI, review/thread read-back은 생략하지 않는다.
- 실제 AWS 계정과 OpenTelemetry SDK/exporter 검증은 `N/A`다. emulator acceptance는 `bluetape4k-testcontainers`의 `FlociServer`만 사용한다.
- Docker-backed test는 건강한 Colima와 기존 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 환경에서 별도 Gradle process를 병렬 실행하지 않고 순차 실행한다.
- 범위 밖: inbound W3C/B3 carrier 추출, OpenTelemetry/X-Ray 자동 구성, backpressure/FIFO/queue attribute 정책 변경, heartbeat 주기·정책 변경, Spring Cloud AWS API 복제, 기존 operations meter 제거.

### 구현 종료 조건

1. 아래 추적성 표의 모든 설계 수용 기준이 구체적인 task와 fresh verification에 연결된다.
2. 각 기능 task에서 RED와 GREEN 결과를 기록하고, 테스트가 이름으로 주장한 동작을 실제 assertion으로 증명한다.
3. runtime이 없거나 registry 자체가 `ObservationRegistry.NOOP`이면 context 생성 전 기존 block을 직접 실행한다. user factory가 `Observation.NOOP`을 반환하면 context/customizer/factory까지는 실행하지만 `start/openScope/capture/withContext/event`는 만들지 않는다.
4. BPP 6-인자 constructor, `SqsAutoConfiguration` bean method, container 5-인자 constructor와 `SqsProperties.Listener` descriptor가 유지된다.
5. targeted observation test, 전체 `aws-spring-boot` Floci test, compile, detekt, dependency/ABI contract, 문서 contract와 `git diff --check`가 통과한다.
6. 구현 diff 6관점 review와 main integration 결과가 P0=0, P1=0이다. P2/P3는 수정하거나 근거를 남겨 후속 이슈로 분리한다.
7. 공개 KDoc, EN/KO manual, root/module README, compile-verified customization example과 Type A lesson이 구현과 일치한다.
8. PR metadata는 한국어 제목·본문, `debop` assignee, issue milestone/labels를 유지하고 마지막 H2를 `## DoD Status`로 둔다.
9. exact-head CI가 terminal success인 merge-ready 보고에서 멈추고 fresh merge 승인을 기다린다.

## 2. 파일 구조와 책임

### dependency와 public contract

- Modify: `aws-spring-boot/build.gradle.kts` — `implementation(bt4k.micrometer.context.propagation)`, observation benchmark/test dependency와 task 설정.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationProperties.kt` — 독립 opt-in 속성.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationContext.kt` — stage/outcome/delivery, sanitized immutable metadata, bounded runtime context.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationConvention.kt` — stage별 convention과 이름/tag 상수.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationFactory.kt` — `createNotStarted`, ordered customizer contract.

### runtime와 자동 설정

- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationRuntime.kt` — direct fast path, lifecycle, context capture, failure precedence와 retry event.
- Create: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationAutoConfiguration.kt` — optional class name guard, nested configuration, activation marker와 bounded diagnostics.
- Modify: `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — outer observation auto-configuration 등록.
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsAutoConfiguration.kt` — 기존 bean method descriptor를 바꾸지 않는 연결점만 유지.
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMicrometerAutoConfiguration.kt` — activation marker가 있을 때 자동 listener interceptor만 back-off.
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListenerAnnotationBeanPostProcessor.kt` — 기존 constructor와 동작을 유지하는 internal runtime setter, 생성한 container에 동일 runtime 전달.
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainer.kt` — 기존 5-인자 constructor 유지, internal setter와 receive/process around 경계.
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListenerMethodInvoker.kt` — conversion 완료 지점을 handler 호출과 구분하는 internal phase callback; 기존 invoke signature와 예외 identity 유지.
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsAcknowledgement.kt` — 실제 단건 ACK/NACK/visibility I/O observation.
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchAcknowledgement.kt` — actual-I/O batch observation, partial count, cancellation rollback 순서.

### tests와 benchmark

- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationContextTest.kt`.
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationConventionTest.kt`.
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationRuntimeTest.kt`.
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationAutoConfigurationTest.kt`.
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationBinaryCompatibilityTest.kt`.
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationDependencyContractTest.kt`.
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationAllocationTest.kt`.
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationCustomizationExampleTest.kt`.
- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationAwsEmulatorTest.kt` — `FlociServer.Launcher.floci` singleton을 직접 사용하는 emulator acceptance.
- Create: `aws-spring-boot/src/benchmark/kotlin/io/bluetape4k/aws/spring/sqs/SqsObservationBenchmark.kt`.
- Modify: `SqsMessageListenerContainerTest.kt`, `SqsAcknowledgementTest.kt`, `SqsBatchAcknowledgementTest.kt`, `MicrometerSqsListenerInterceptorTest.kt`, `MicrometerSqsOperationsTest.kt` — 기존 lifecycle과 meter 공존 회귀.

### 문서와 delivery evidence

- Modify: `CHANGELOG.md` — `[미출시]`의 한국어 `추가` 항목에 opt-in SQS Observation과 Floci 검증 경계 기록.
- Modify: `README.md`, `README.ko.md` — 상세 설명을 복제하지 않는 capability 요약과 manual link.
- Modify: `aws-spring-boot/README.md`, `aws-spring-boot/README.ko.md` — module capability 요약과 EN/KO manual link.
- Modify: `docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`.
- Modify: `docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`.
- Modify: `docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md`.
- Modify: `docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md`.
- Create: `docs/lessons/2026-08-27-issue-473-sqs-observation.md`.
- Create after implementation review: `docs/review/2026-08-27-issue-473-sqs-observation-code-review.md`.

## 3. 작업 순서

### Task 1: dependency와 공개 metadata contract를 먼저 고정

**Complexity:** L
**Depends on:** 승인된 설계와 본 계획
**Write scope:** `aws-spring-boot/build.gradle.kts`, `SqsObservationProperties.kt`, `SqsObservationContext.kt`, context/dependency tests
**Required skills:** `$bluetape-kotlin-patterns`, `test-driven-development`; testing과 Spring reference 적용
**Expected DoD:** opt-in 기본값, serialization, metadata 불변식, queue name 정제와 context-propagation dependency 경계가 테스트로 고정된다.

- [ ] **Step 1.1: RED public contract test를 작성한다**

  `SqsObservationContextTest`에서 다음을 먼저 컴파일하거나 실행한다.

  - `SqsObservationProperties().enabled == false`.
  - `SqsObservationStage`, `SqsObservationOutcome`, `SqsObservationDelivery`의 모든 bounded enum 값.
  - PROCESS/ACKNOWLEDGEMENT의 `initialAttempt >= 1`; RECEIVE/UNKNOWN에서 attempt 생략.
  - batch 크기 1을 포함한 `batch=true` metadata의 message/group/deduplication ID가 모두 `null`.
  - receive count 누락/파싱 실패/1/2 이상이 `UNKNOWN/FIRST/REDELIVERED`로 변환됨.
  - `toString()`에 message ID, FIFO ID, exact attempt, URL, receipt, body가 없음.
  - `https://user:secret@host/123456789012/orders?token=secret#fragment`, encoded separator `%2F`, trailing slash, blank path, malformed URI와 81자 segment를 parameterized input으로 사용한다. metadata, low/high tag, contextual name과 telemetry error 어디에도 원본 URL·user-info·host·account ID·query·fragment가 없고, 허용된 raw 마지막 segment 또는 `unknown`만 남아야 한다.
  - blank/whitespace listener ID는 `unknown`으로 정규화하고 message body/attribute/system attribute에서 listener ID를 만들지 않는다.
  - blank/whitespace queue name도 `unknown`으로 정규화한다.
  - `SqsObservationProperties`와 `SqsObservationMetadata`의 `serialVersionUID == 1L`.

- [ ] **Step 1.2: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationContextTest' \
    --no-daemon --no-build-cache
  ```

  Expected: observation public type이 아직 없어 Kotlin compile이 FAIL한다.

- [ ] **Step 1.3: 최소 public type과 queue sanitizer를 구현한다**

  - 설계의 public signature를 그대로 추가한다.
  - queue sanitizer는 URI의 마지막 raw path segment만 사용하고 `(?=.{1,80}$)[A-Za-z0-9_-]+(?:\.fifo)?` 외에는 `unknown`을 반환한다.
  - percent decoding을 하지 않고 `%`, query, fragment, user-info, host, account ID와 malformed input을 context에 복사하지 않는다.
  - 동일 resolved URL 정제는 container가 한 번 계산해 runtime metadata 생성에 재사용하도록 internal value function을 제공한다.
  - 기존 `AwsMicrometerSupport.queueNameTag`의 정제 계약을 먼저 비교한다. account/URL 노출 금지와 raw-segment regex가 완전히 같을 때만 utility를 재사용하고, 다르면 더 느슨한 공용 helper를 바꾸지 않은 채 observation-local sanitizer를 둔다. 이 결정과 근거를 test 이름과 lesson에 남긴다.
  - validation은 observation 시작 전에 fail fast하며 새 production `!!`를 사용하지 않는다.

- [ ] **Step 1.4: context-propagation dependency contract를 추가한다**

  `aws-spring-boot/build.gradle.kts`에 다음을 추가한다.

  ```kotlin
  implementation(bt4k.micrometer.context.propagation)
  ```

  `SqsObservationDependencyContractTest`와 Gradle dependency report로 다음을 고정한다.

  - runtimeClasspath에 `io.micrometer:context-propagation:1.2.1`이 존재한다.
  - 새 public class/method/constructor signature가 `io.micrometer.context.*` type을 노출하지 않는다.

- [ ] **Step 1.5: GREEN과 dependency graph를 확인한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationContextTest' \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationDependencyContractTest' \
    --no-daemon --no-build-cache
  ./gradlew :bluetape4k-aws-spring-boot:dependencies \
    --configuration runtimeClasspath --no-daemon | \
    rg 'io\.micrometer:context-propagation:1\.2\.1'
  ```

  Expected: tests PASS, dependency 한 줄 이상 확인, public signature scan에서 context-propagation type 0건.

- [ ] **Step 1.6: Lore commit을 만든다**

  Intent line: `feat: SQS 관찰 metadata가 민감한 원본에 접근하지 못하게 한다`

  Trailers:

  ```text
  Constraint: Observation은 기본 비활성이며 context-propagation은 내부 구현 의존성으로만 노출한다
  Rejected: 원본 SqsReceivedMessage를 customization context에 전달 | payload와 receipt 접근 경계를 넓힌다
  Confidence: high
  Scope-risk: moderate
  Directive: queue URL은 raw 마지막 segment allowlist를 통과한 이름만 재사용한다
  Tested: SqsObservationContextTest, SqsObservationDependencyContractTest, runtimeClasspath
  Not-tested: container lifecycle와 Floci는 후속 task에서 검증한다
  ```

  **Rollback/rerun:** dependency version이 1.2.1이 아니면 저장소 BOM alias를 먼저 확인한다. 별도 version pin을 추가하지 말고 Task 1 변경만 중단한다.

### Task 2: convention, factory와 customization 계약 구현

**Complexity:** L
**Depends on:** Task 1
**Write scope:** `SqsObservationConvention.kt`, `SqsObservationFactory.kt`, convention/customization tests
**Expected DoD:** 이름·tag allowlist, stage별 convention 선택, ordered customizer와 user factory contract가 deterministic하다.

- [ ] **Step 2.1: RED convention/factory test를 작성한다**

  `SqsObservationConventionTest`와 `SqsObservationCustomizationExampleTest`에서 다음을 먼저 고정한다.

  - 이름은 `bluetape4k.aws.sqs.receive`, `bluetape4k.aws.sqs.process`, `bluetape4k.aws.sqs.acknowledgement` 세 값뿐이다.
  - low-cardinality key는 설계의 9개 allowlist이며, high-cardinality key는 단건의 message/group/deduplication ID와 exact attempt뿐이다.
  - batch 크기 bucket은 `0`, `1`, `2-5`, `6-10`이다.
  - failure stage는 `none/receive/conversion/handler/acknowledgement/observation`만 허용한다.
  - 동일 stage의 user convention 두 개는 configuration exception으로 실패한다.
  - `@Order` customizer 두 개가 순서대로 정확히 한 번 실행되고 factory보다 먼저 실행된다.
  - factory는 supplied registry와 동일 context를 사용한 not-started observation 또는 `Observation.NOOP`을 반환한다.
  - 다른 context instance나 다른 registry에 연결한 observation은 fail fast한다. factory가 lifecycle을 시작하거나 `error/stop`을 소유하지 않으며 runtime만 정확히 한 번 소유한다. Micrometer public API로 사전 검출할 수 없는 already-started 반환은 unsupported contract로 KDoc/manual에 명시한다.
  - body, receipt handle, full URL, arbitrary header, exception message/stack trace가 tag와 context에 없다.
  - contextual name도 sanitized queue name만 사용하며 adversarial URL 원문을 포함하지 않는다.
  - public context/factory/customizer에는 raw `SqsReceivedMessage`, receipt handle, body, message/system attribute map 접근자가 없다.

- [ ] **Step 2.2: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationConventionTest' \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationCustomizationExampleTest' \
    --no-daemon --no-build-cache
  ```

  Expected: convention/factory type 또는 실제 tag가 없어 FAIL한다.

- [ ] **Step 2.3: default convention과 factory를 최소 구현한다**

  - public `SqsObservationConvention`, `SqsObservationContextCustomizer`, `SqsObservationFactory`는 설계 signature를 유지한다.
  - default implementation과 selection helper는 `internal`이다.
  - convention은 start/stop 때 같은 mutable context를 읽어 terminal outcome/attempt/count를 tag에 반영한다.
  - business throwable 대신 message/cause/stack trace가 없는 internal redacted telemetry exception만 `Observation.error`에 제공한다.
  - secret-bearing message/cause/stack을 가진 convention/customizer/factory Throwable test를 추가한다. caller에는 같은 Throwable instance를 재전파하되 recording handler, condition diagnostic와 log capture에는 secret, message, cause, stack이 없고 bounded failure stage/reason만 있어야 한다.
  - user factory 반환 직후 context identity를 검사하고, runtime start/scope 뒤 supplied registry의 current observation identity를 검사할 수 있는 contract 정보를 보존한다.
  - `Observation.NOOP`은 정상 반환으로 인정한다.

- [ ] **Step 2.4: GREEN과 API compile evidence를 확인한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationConventionTest' \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationCustomizationExampleTest' \
    --no-daemon --no-build-cache
  ```

  Expected: PASS. customization fixture는 PROCESS convention, ordered customizer 두 개와 supplied registry factory를 실제 Kotlin source로 컴파일한다.

- [ ] **Step 2.5: Lore commit을 만든다**

  Intent line: `feat: SQS 관찰 확장점을 bounded stage 계약으로 제한한다`

  **Rollback/rerun:** user convention 선택이 bean order에 의존하면 stage별 map을 startup에 확정하도록 되돌린다. 임의 첫 bean 선택은 허용하지 않는다.

### Task 3: coroutine-safe runtime과 실패 우선순위 구현

**Complexity:** XL
**Depends on:** Task 2
**Write scope:** `SqsObservationRuntime.kt`, `SqsObservationRuntimeTest.kt`
**Expected DoD:** scope를 suspension에 걸치지 않고 current observation을 전파하며, success/error/cancellation/setup/stop의 primary/suppressed와 exactly-once stop을 보존한다.

- [ ] **Step 3.1: RED lifecycle matrix를 작성한다**

  `runTest`와 in-memory recording handler로 다음 matrix를 먼저 작성한다.

  - suspend 전후, `StandardTestDispatcher` 전환 뒤에도 같은 current observation.
  - child observation이 process observation 아래에 연결됨.
  - success, error, cancellation에서 start/stop 정확히 1회와 parent 복원.
  - customizer/factory/start/scope/capture/error/stop 각 setup failure에서 원래 parent 복원과 current observation 누출 0. start가 끝난 observation은 stop 1회, start 전 실패한 observation은 stop 0회.
  - customizer/factory/start/scope/capture/error/stop 실패 조합의 primary throwable과 suppressed 순서.
  - cancellation은 같은 `CancellationException` instance를 재전파하고 raw cancellation을 handler에 전달하지 않음.
  - retry attempt는 매번 갱신하지만 `Observation.Event.of("retry")`는 process당 최대 1회.
  - runtime 없음과 registry 자체가 `ObservationRegistry.NOOP`이면 context/customizer/factory/start/scope/capture/event counter가 모두 0인 직접 경로.
  - factory `Observation.NOOP`이면 context/customizer/factory는 각각 1회지만 start/scope/capture/`withContext`/event counter는 0인 직접 business 경로.

- [ ] **Step 3.2: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationRuntimeTest' \
    --no-daemon --no-build-cache
  ```

  Expected: runtime이 없어 compile FAIL한다.

- [ ] **Step 3.3: 공통 around lifecycle을 구현한다**

  순서를 다음으로 고정한다.

  1. context 생성과 불변식 검증.
  2. ordered customizer를 정확히 한 번 실행.
  3. factory `createNotStarted` 호출과 context identity 확인.
  4. factory 반환이 NOOP이면 이미 생성한 context 이후에는 start/scope/capture/`withContext`/event 없이 business block을 직접 실행한다. runtime null/registry NOOP 검사는 이 순서보다 앞서 context 생성 전 반환한다.
  5. observation start → 같은 스레드 scope open → supplied registry identity 확인 → `asContextElement()` capture → 같은 스레드 scope close.
  6. `withContext(capturedContext)`에서 business block과 retry event/outcome 갱신.
  7. `finally`에서 error/stop을 정확히 한 번 실행하고 parent를 복원.

  `catch (CancellationException) { throw e }` 경계를 다른 Throwable보다 앞에 둔다. receive/process/batch/ACK cancellation에서 rollback → waiter completion → interceptor cancellation/finally hook → observation error/stop 전체 cleanup 순서를 `withContext(NonCancellable)`로 완료한다. business primary가 있으면 observation cleanup 오류를 suppressed로 붙이고, business가 성공했는데 stop만 실패하면 stop 오류를 primary로 전달한다.

- [ ] **Step 3.4: GREEN과 coroutine leak 점검을 실행한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationRuntimeTest' \
    --no-daemon --no-build-cache
  ```

  Expected: 전체 lifecycle matrix PASS, unfinished child job 0, parent 복원 assertion PASS.

- [ ] **Step 3.5: Lore commit을 만든다**

  Intent line: `feat: coroutine suspension과 관찰 scope 수명을 분리한다`

  **Rollback/rerun:** scope를 `withContext` 바깥에서 열린 상태로 유지하거나 다른 thread에서 닫는 구현은 폐기한다. lifecycle matrix에서 한 조합이라도 실패하면 downstream container 연결을 시작하지 않는다.

### Task 4: prerequisite 기반 auto-configuration과 metric 공존 고정

**Complexity:** XL
**Depends on:** Task 3
**Write scope:** `SqsObservationAutoConfiguration.kt`, imports, `SqsMicrometerAutoConfiguration.kt`, auto-config/metric tests
**Expected DoD:** property, optional class, non-NOOP registry와 supporting Spring handler bean을 모두 만족할 때만 marker/runtime이 생기며 legacy listener meter와 operations meter 정책이 유지된다.

- [ ] **Step 4.1: RED `ApplicationContextRunner` matrix를 작성한다**

  `SqsObservationAutoConfigurationTest`에 다음 positive/negative case를 parameterized test로 추가한다.

  - `bluetape4k.aws.enabled=false` → SQS/observation auto-configuration과 자동 listener meter 모두 없음, 사용자 수동 bean은 제거하지 않음, 표준 outer condition negative match.
  - `bluetape4k.aws.sqs.enabled=false` → SQS/observation auto-configuration과 자동 listener meter 모두 없음, 사용자 수동 bean은 제거하지 않음, 표준 property condition negative match.
  - property missing/false → `disabled`.
  - registry bean 없음 → `registry-missing`.
  - registry가 정확히 `ObservationRegistry.NOOP` → `registry-noop`.
  - `ObjectProvider<ObservationHandler<*>>` Spring bean 중 sanitized PROCESS probe에 `supportsContext`를 반환하는 handler 없음 → `handler-missing`.
  - generic `ObservationHandler<Observation.Context>`가 PROCESS probe를 지원하면 prerequisite 충족, bean은 있지만 probe를 지원하지 않으면 `handler-missing`.
  - registry config에만 직접 등록하고 Spring bean이 아닌 handler → `handler-missing`.
  - `FilteredClassLoader("io.micrometer.context.ContextSnapshot")` → `context-propagation-missing`, linkage error 없음.
  - 모든 prerequisite 충족 → activation marker/runtime 생성.
  - user factory는 default factory만 back-off하고 prerequisite를 우회하지 않음; condition reason `user-factory`.
  - 같은 stage user convention 둘 → startup configuration failure.
  - observation auto-config는 `org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration` 뒤, `SqsAutoConfiguration` 앞에 평가됨.
  - `Binder`로 exact prefix `bluetape4k.aws.sqs.observation`을 읽어 missing/`false`는 `enabled=false`, `true`는 `enabled=true`가 되고 properties bean이 한 번만 등록됨.
  - 동일 runner로 context를 순차 재생성해 false → true → false가 runtime 재바인딩 없이 restart/redeploy 때만 marker 없음 → 있음 → 없음으로 바뀜.
  - observation auto-config는 Actuator health/readiness contributor를 추가하지 않고, prerequisite가 없거나 Floci가 없어도 application context startup을 block하지 않음.

- [ ] **Step 4.2: RED metric 공존 test를 작성한다**

  - AWS/SQS는 활성 상태이고 observation marker만 없음/빈 registry/handler 없음이면 자동 `MicrometerSqsListenerInterceptor` 존재.
  - marker가 있으면 자동 listener interceptor만 없음.
  - 사용자 등록 listener interceptor는 제거하지 않음.
  - `MicrometerSqsOperations`/`MicrometerFullRequestSqsOperations` bean과 operations meter는 모든 observation 상태에서 유지.

- [ ] **Step 4.3: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationAutoConfigurationTest' \
    --tests 'io.bluetape4k.aws.spring.sqs.MicrometerSqsListenerInterceptorTest' \
    --tests 'io.bluetape4k.aws.spring.sqs.MicrometerSqsOperationsTest' \
    --no-daemon --no-build-cache
  ```

  Expected: 새 auto-config/marker가 없어 FAIL한다.

- [ ] **Step 4.4: name-only outer guard와 nested configuration을 구현한다**

  - outer auto-configuration에 `@ConditionalOnAwsEnabled`를 적용하고 SQS global property `bluetape4k.aws.sqs.enabled=true`와 observation property `bluetape4k.aws.sqs.observation.enabled=true`를 모두 `@ConditionalOnProperty`로 고정한다.
  - annotation presence와 각 global/property negative match의 표준 `ConditionEvaluationReport` reason을 reflection/runner test로 직접 검증한다. custom bounded reason은 observation property가 enabled지만 prerequisite가 빠진 경우에만 사용한다.
  - `@AutoConfiguration(afterName = ["org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration"], before = [SqsAutoConfiguration::class])`로 name-based observation ordering과 SQS ordering을 고정하고 imports read-back test로 등록 순서를 확인한다.
  - outer auto-config는 optional type을 method/field/constructor signature에 노출하지 않고 class name string으로만 조건을 검사한다.
  - nested configuration이 `ObservationRegistry`, `ObjectProvider<ObservationHandler<*>>`, convention, customizer, factory와 runtime을 참조하고 sanitized PROCESS probe의 `supportsContext`로 supporting handler를 판정한다.
  - property와 class/bean condition은 optional type을 참조하는 모든 phase에 반복 적용한다.
  - prerequisite 불충족은 startup warning/error 없이 condition report의 bounded code `BT4K-SQS-OBS-101`과 reason만 제공한다.
  - observation property가 enabled인 prerequisite negative case는 `ConditionEvaluationReport`에서 exact code/reason 한 건, observation auto-config 관련 warning/error log 0건을 assertion한다.
  - 새 diagnostic에는 payload, queue URL, account ID, exception text를 넣지 않는다.
  - imports에 outer auto-config를 직접 등록한다.

- [ ] **Step 4.5: GREEN과 optional class isolation을 확인한다**

  위 RED 명령을 재실행한다. Expected: 전 matrix PASS, `FilteredClassLoader` case의 linkage error 0, restart simulation의 marker 상태 `absent → present → absent`.

- [ ] **Step 4.6: Lore commit을 만든다**

  Intent line: `feat: 실제 관찰 처리기가 있을 때만 SQS 관찰을 활성화한다`

  **Rollback/rerun:** class absence가 config parsing 전에 실패하면 optional type이 outer class 또는 eager bean signature에 남아 있는지 `javap`로 찾고 nested boundary를 다시 나눈다.

### Task 5: ABI를 보존한 runtime 주입과 direct fast path 연결

**Complexity:** L
**Depends on:** Task 4
**Write scope:** `SqsAutoConfiguration.kt`, BPP, container, binary compatibility/fast-path tests
**Expected DoD:** 기존 JVM descriptor를 하나도 제거하지 않고 runtime을 internal setter로 전달하며 미설정 경로는 기존 listener 동작을 직접 실행한다.

- [ ] **Step 5.1: RED binary compatibility test를 작성한다**

  `SqsObservationBinaryCompatibilityTest`에서 reflection과 `javap` 출력으로 다음 descriptor를 고정한다.

  - `SqsListenerAnnotationBeanPostProcessor(Environment, SqsProperties, SqsOperations, SqsMessageListenerContainerRegistry, SqsMessageConverter, List)`.
  - `SqsAutoConfiguration.sqsListenerAnnotationBeanPostProcessor(Environment, SqsProperties, SqsOperations, SqsMessageListenerContainerRegistry, ObjectProvider, ObjectProvider)`.
  - `SqsMessageListenerContainer(SqsListenerEndpoint, SqsOperations, SqsListenerMethodInvoker, List, CoroutineDispatcher)`.
  - `SqsProperties.Listener` legacy constructor와 serialization test가 기존 expected descriptor/UID를 유지.

- [ ] **Step 5.2: RED fast-path test를 작성한다**

  기존 `SqsMessageListenerContainerTest` fixture로 runtime 미설정과 registry 자체가 NOOP인 runtime에서 receive/handler/ack 순서가 기존과 같고, observation context factory와 capture hook 호출이 0인지 확인한다. factory가 NOOP observation을 반환하는 경우는 Task 3 계약대로 context/customizer/factory 1회와 capture 0을 별도 확인한다.

  auto-configuration runner에 recording listener bean을 추가해 runtime connector BeanPostProcessor가 SQS BPP의 internal setter를 정확히 한 번 호출한 뒤에만 첫 listener bean의 post-processing/등록이 시작되고, 생성된 모든 container가 같은 runtime identity를 받는지 확인한다.

- [ ] **Step 5.3: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationBinaryCompatibilityTest' \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsMessageListenerContainerTest' \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsPropertiesBinaryCompatibilityTest' \
    --no-daemon --no-build-cache
  ```

  Expected: runtime 연결 assertion이 없어 새 test FAIL; 기존 ABI tests는 계속 PASS해야 한다.

- [ ] **Step 5.4: setter 기반 연결을 구현한다**

  - optional nested configuration의 전용 BeanPostProcessor를 명시적 `PriorityOrdered`와 early/static bean registration 경계로 등록해 생성된 SQS BPP의 internal setter를 listener bean processing 전에 연결한다.
  - SQS BPP는 container 생성 직후 같은 runtime을 internal setter로 전달한다.
  - 기존 public constructor와 `@Bean` method parameter는 변경하지 않는다.
  - runtime nullable field가 null이면 receive/process/ack 기존 함수를 직접 호출한다.

- [ ] **Step 5.5: GREEN과 `javap`를 확인한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:compileKotlin \
    :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --no-build-cache
  javap -classpath aws-spring-boot/build/classes/kotlin/main \
    io.bluetape4k.aws.spring.sqs.SqsListenerAnnotationBeanPostProcessor \
    io.bluetape4k.aws.spring.sqs.SqsAutoConfiguration \
    io.bluetape4k.aws.spring.sqs.SqsMessageListenerContainer
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationBinaryCompatibilityTest' \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsMessageListenerContainerTest' \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsPropertiesBinaryCompatibilityTest' \
    --no-daemon --no-build-cache
  ```

  Expected: compile/test PASS, 네 descriptor 유지, optional type이 BPP/SqsAutoConfiguration/container public signature에 없음.

- [ ] **Step 5.6: Lore commit을 만든다**

  Intent line: `feat: 기존 SQS listener ABI를 유지하며 관찰 runtime을 연결한다`

  **Rollback/rerun:** descriptor 하나라도 사라지면 constructor parameter나 bean method parameter 추가를 되돌리고 setter 연결만 유지한다.

### Task 6: receive와 process lifecycle을 container에 통합

**Complexity:** XL
**Depends on:** Task 5
**Write scope:** container, runtime, container/runtime tests
**Expected DoD:** receive/process observation count와 parent 전파가 정확하고 기존 retry, cancellation, generation stop 동작이 유지된다.

- [ ] **Step 6.1: RED receive/process tests를 확장한다**

  - empty/success/error/cancellation receive 각각 receive observation 1개.
  - queue URL resolution 실패는 observation 시작 전 `BT4K-SQS-OBS-201` 경계이며 receive observation을 만들지 않음.
  - 단건 `n`개는 receive 1 + process `n`; batch는 receive 1 + process 1.
  - conversion과 handler는 같은 process observation 안에서 실행됨.
  - converter가 던진 오류는 `failure.stage=conversion`, reflective/suspend listener가 던진 오류는 `failure.stage=handler`이며 원본 exception identity는 유지됨.
  - retry 후 성공은 process 1, retry event 최대 1, outcome `RETRIED`, terminal attempt 갱신.
  - controlled dispatcher/barrier로 retry backoff 중 취소하고 같은 cancellation instance, process stop 1회, 추가 retry/ACK/event 0, parent 복원을 확인.
  - conversion error/handler error/cancellation은 `ERROR/ERROR/CANCELLED`, 원본 Throwable identity 보존.
  - batch 크기 1도 process context에 message/FIFO ID 없음.
  - process 종료 뒤 parent 복원과 container stop 경쟁에서 observation stop 정확히 1회.
  - heartbeat I/O 중 container stop barrier를 걸어 `stop → generation cancellation → join → heartbeat/process observation stop` 순서, leak 0과 parent 복원을 확인.
  - 동일 resolved URL에서 N개 메시지를 처리해 sanitizer 호출 1회와 이후 cache hit N회를 counter로 확인.

- [ ] **Step 6.2: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationRuntimeTest' \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsMessageListenerContainerTest' \
    --no-daemon --no-build-cache
  ```

  Expected: container가 runtime을 호출하지 않아 observation count/parent assertion FAIL.

- [ ] **Step 6.3: around 경계를 최소 통합한다**

  - resolved queue name cache를 queue URL resolve 성공 직후 한 번 만든다.
  - receive는 `operations.receive` I/O와 interceptor receive lifecycle을 observation에 연결한다.
  - process는 conversion, handler, retry 판정과 자동 ACK 호출을 포함한다.
  - `SqsListenerMethodInvoker`는 argument 배열 생성이 끝난 뒤 internal phase callback으로 `handler` 진입을 알린다. container는 callback 전 실패를 `conversion`, callback 후 실패를 `handler`로 기록하며 public invoke API나 Throwable wrapping을 추가하지 않는다.
  - 기존 interceptor 호출 순서와 generation cancellation/join 순서를 바꾸지 않는다.
  - receive/process setup 실패도 기존 retry/fail-fast 경로로 전달한다.
  - receive/process cancellation은 같은 cancellation을 보존하고 interceptor hook과 observation error/stop을 포함한 전체 cleanup을 `NonCancellable`에서 끝낸다. cleanup 실패는 같은 cancellation에 suppressed로 붙인다.

- [ ] **Step 6.4: GREEN과 deterministic count budget을 확인한다**

  Step 6.2 명령을 재실행한다. Expected: PASS, observation/event exact count assertion 모두 일치.

- [ ] **Step 6.5: Lore commit을 만든다**

  Intent line: `feat: SQS 수신과 처리 수명을 하나의 coroutine trace로 연결한다`

  **Rollback/rerun:** retry마다 process observation이 늘거나 interceptor 순서가 바뀌면 integration을 되돌리고 runtime event 갱신만 process 내부에 둔다.

### Task 7: 단건·batch ACK, detached parent와 heartbeat I/O 통합

**Complexity:** XL
**Depends on:** Task 6
**Write scope:** acknowledgements, container heartbeat, ACK/container tests
**Expected DoD:** 실제 AWS I/O에만 acknowledgement observation이 생기고 partial/cancellation/race와 heartbeat 오류 정책이 유지된다.

- [ ] **Step 7.1: RED 단건 ACK lifecycle test를 작성한다**

  - ACK/NACK/change visibility 성공·오류·cancellation outcome.
  - duplicate terminal 호출과 이미 완료된 호출은 actual I/O와 observation 모두 0.
  - detached manual ACK는 호출 시점 current observation만 parent로 사용하고 종료된 process parent를 재사용하지 않음.
  - ACK observation setup/stop failure가 기존 retry/error visibility 경로에 fail-closed로 전달됨.
  - ACK I/O 오류 + observation `error/stop` 오류, business 성공 + stop 오류, cancellation + cleanup 오류 조합에서 primary/suppressed 순서와 기존 retry/redelivery 전달을 확인.

- [ ] **Step 7.2: RED batch ACK race test를 작성한다**

  - success/partial/error outcome과 성공/실패 count.
  - wait/already-terminal/pre-validation-only path는 observation 0.
  - cancellation 시 mutex 안에서 `PENDING`, `inFlight=null` rollback을 먼저 확정하고 mutex 밖에서 waiter를 완료.
  - rollback 뒤 같은 ACK 재호출 성공.
  - interceptor hook과 observation cleanup이 `NonCancellable`에서 완료되고 cleanup 오류는 cancellation에 suppressed.
  - batch context는 크기 1도 individual ID 목록을 노출하지 않음.

- [ ] **Step 7.3: RED heartbeat test를 작성한다**

  - 실제 `ChangeMessageVisibility` I/O마다 acknowledgement observation 1개.
  - success/error/cancellation, dispatcher 지연, parent 복원.
  - observation `error()`/`stop()` 실패는 `BT4K-SQS-OBS-202` bounded diagnostic만 남기고 heartbeat/handler 결과를 바꾸지 않음.
  - #453의 주기, stop, `cancelAndJoin`, operation mutex 계약은 변경하지 않음.

- [ ] **Step 7.4: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsAcknowledgementTest' \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsBatchAcknowledgementTest' \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsMessageListenerContainerTest' \
    --no-daemon --no-build-cache
  ```

  Expected: acknowledgement observation과 새 race assertion이 없어 FAIL.

- [ ] **Step 7.5: actual-I/O around 경계를 구현한다**

  - 단건은 operation guard 뒤 실제 delete/changeVisibility block만 runtime으로 감싼다.
  - batch는 reserve 성공 후 runnable item이 있고 실제 deleteBatch/changeVisibilityBatch를 호출할 때만 observation을 만든다.
  - partial count를 context에 기록하되 ID 목록을 tag/context에 추가하지 않는다.
  - rollback은 mutex 안 상태 복원과 mutex 밖 waiter 완료 두 단계로 나눠 cancellation에서도 `NonCancellable`로 끝낸다.
  - cancellation cleanup 순서는 mutex 안 rollback → mutex 밖 waiter `Deferred.complete` → interceptor hook → observation error/stop으로 고정하고 각 단계 오류는 원래 cancellation에 suppressed로 붙인다.
  - single heartbeat/ACK의 customizer/factory/context capture는 `operationMutex` 획득 전에 끝내고, batch observation은 reserve mutex 해제 뒤 시작한다. contention test가 mutex 보유 중 observation setup 호출 0을 확인한다.
  - heartbeat는 기존 mutex와 `Dispatchers.IO` 경계를 유지하고 observation telemetry 실패만 진단 후 무시한다.

- [ ] **Step 7.6: GREEN과 cancellation stress를 확인한다**

  Step 7.4 명령을 재실행하고 batch cancellation race test 내부에서 1,000 iteration을 `withTimeout(30_000)`으로 반복한다.

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsBatchAcknowledgementTest.*cancellation*' \
    --rerun-tasks --no-daemon
  ```

  Expected: 전체 PASS, 1,000/1,000 waiter completion과 재호출 성공, hang 0, observation count가 actual I/O count와 동일.

- [ ] **Step 7.7: Lore commit을 만든다**

  Intent line: `feat: 실제 SQS 확인 I/O만 독립 관찰로 기록한다`

  **Rollback/rerun:** wait/duplicate path에 observation이 생기거나 cancellation waiter가 남으면 ACK integration을 중단하고 rollback 순서를 먼저 복구한다.

### Task 8: Floci end-to-end acceptance 구현

**Complexity:** XL
**Depends on:** Task 7
**Write scope:** `SqsObservationAwsEmulatorTest.kt`, 필요한 기존 Floci fixture의 최소 재사용 변경
**Expected DoD:** 실제 AWS 계정 없이 `FlociServer`의 listener/ACK/visibility 동작과 in-memory handler 증거가 결합된다.

- [ ] **Step 8.1: `FlociServer` 기반 RED integration test를 작성한다**

  raw `GenericContainer`나 LocalStack을 새로 만들지 않는다. `bluetape4k-testcontainers`의 `FlociServer.Launcher.floci` singleton을 fixture로 재사용하고 다음을 순차 테스트한다. test class 이름은 기존 `skipAwsEmulatorTests` 분류와 맞게 `*AwsEmulatorTest`를 유지한다.

  1. 표준 queue 단건 handler에서 current process observation 확인.
  2. child observation의 parent가 process observation인지 확인.
  3. retry 후 성공과 error visibility가 기존 delivery 동작을 보존.
  4. FIFO message group/message ID는 단건 high-cardinality key에만 존재.
  5. manual batch partial ACK는 성공 항목만 삭제하고 `PARTIAL` 및 count를 기록.
  6. body, receipt handle, full URL, secret attribute가 모든 tag/context/telemetry error에 없음.
  7. secret-bearing handler/customizer/factory/convention `Throwable`을 주입해 telemetry error에는 bounded diagnostic만 남고 원본 message/cause/stack token은 없으며, business Throwable identity와 delivery 결과는 그대로 보존됨.
  8. queue resolution 실패는 exact code `BT4K-SQS-OBS-201`, bounded stage/reason만 남기고 URL·account·원본 message/cause/stack token을 남기지 않음.
  9. heartbeat observation `error()`/`stop()` 실패는 exact code `BT4K-SQS-OBS-202`, bounded action/stage/reason만 남기고 visibility 결과와 listener delivery를 바꾸지 않음.
  10. heartbeat visibility와 acknowledgement failure가 actual-I/O observation으로 기록.
  11. observation count budget이 empty/single/batch/retry/heartbeat 규칙과 일치.
  12. fixture owner는 test class companion의 `FlociServer.Launcher.floci`이고 application context/registry가 listener stop과 `cancelAndJoin`을 소유한다. 각 test는 30초 bounded timeout과 `finally` teardown을 사용하며 종료 뒤 listener job 0, 열린 application context 0을 확인한다. shared Floci container 자체는 launcher owner가 재사용하므로 개별 test가 close하지 않는다.

- [ ] **Step 8.2: RED를 관찰한다**

  실행 전 `colima status`, `docker context show`, `docker info`로 healthy context를 확인한다. VM을 재시작하지 않는다.

  ```bash
  colima status
  docker context show
  docker info
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationAwsEmulatorTest' \
    -Dbluetape4k.aws.emulator=floci \
    --no-daemon --no-build-cache
  ```

  Expected: 아직 fixture/runtime 연결이 부족한 assertion이 FAIL한다. container/image/network 실패는 코드 RED와 분리해 원문을 보존한다.

- [ ] **Step 8.3: fixture를 최소 연결하고 GREEN을 확인한다**

  production contract를 Floci에 맞춰 완화하지 않는다. Floci가 지원하지 않는 AWS 기능이 발견되면 같은 SDK request/response 경계를 deterministic fake test로 보강하고, issue 수용 기준의 listener/ACK/visibility 핵심 시나리오가 skip 없이 가능한지 먼저 확인한다.

  Step 8.2의 Gradle 명령을 재실행한다. Expected: PASS, skip 0, observation count와 SQS queue 결과 일치.

- [ ] **Step 8.4: Lore commit을 만든다**

  Intent line: `test: Floci에서 SQS 관찰과 메시지 결과를 함께 검증한다`

  **Rollback/rerun:** Floci 기능 공백을 실제 AWS 성공으로 추정하지 않는다. 핵심 수용 기준을 Floci로 증명할 수 없으면 PENDING으로 유지하고 범위 변경 없이 보고한다.

### Task 9: fast-path allocation·contention benchmark와 전체 Kotlin 품질 gate

**Complexity:** L
**Depends on:** Task 8
**Write scope:** benchmark source/config, touched Kotlin code와 tests의 최소 수정
**Expected DoD:** disabled 경로의 direct bypass와 active observation count budget이 측정되고, Kotlin/Spring/coroutine 차단 항목이 없다.

- [ ] **Step 9.1: 기존 benchmark wiring을 확장한다**

  module에 이미 적용된 `bt4k.plugins.kotlinx.benchmark`, `sourceSets.create("benchmark")`와 generated `:bluetape4k-aws-spring-boot:benchmark` task를 사용한다. `build.gradle.kts`에 `sqsObservationFastPath`와 `sqsObservationContention` configuration을 등록한다.

  - fast path: warmup 5회, measurement 10회, 1초/회, fork 2, `avgt`, ns/op, JSON.
  - contention: warmup 5회, measurement 10회, 1초/회, fork 2, `sample`, ns/op으로 p50/p95/p99를 JSON에 기록.
  - 결과 경로와 exact commit SHA를 `docs/lessons/2026-08-27-issue-473-sqs-observation.md`에 기록한다.

  `SqsObservationBenchmark`에 동일한 no-op business block을 사용하는 `directBaseline`, `disabledFastPath`, `activeProcess`, `concurrentSingleAck`, `concurrentBatchAck`, `concurrentHeartbeat`를 둔다. ACK/heartbeat harness는 32 coroutine, exact I/O count와 queue-wait/operation percentile을 측정한다. 절대 시간 threshold는 명세대로 두지 않고 같은 JVM/fork의 disabled baseline 대비 ratio와 95% confidence interval을 보고하며, correctness는 count mismatch, timeout 또는 leak이면 FAIL한다.

  모든 benchmark와 allocation workload는 같은 non-elidable sentinel을 갱신하고 결과를 JMH `Blackhole.consume` 또는 volatile/atomic sink로 소비한다. warmup·measurement 뒤 expected invocation count와 sentinel 값을 assertion해 JIT dead-code elimination으로 business block이나 fast path가 제거되지 않았음을 blocking evidence로 남긴다.

  `SqsObservationAllocationTest`는 `com.sun.management.ThreadMXBean`의 current-thread allocated bytes로 100,000회 warmup 뒤 1,000,000회 측정을 30회 반복한다. `directBaseline`과 `disabledFastPath`의 median B/op 차이와 bootstrap 95% confidence interval을 계산하고, upper bound `0.5 B/op` 이하를 blocking acceptance로 둔다.

  - disabled 경로가 runtime context/factory/capture counter를 증가시키지 않음.
  - active 경로가 process당 observation 1개와 bounded event만 생성.
  - queue sanitizer counter가 resolved URL당 1회이고 message 수에 비례하지 않음.
  - concurrent ACK/heartbeat에서 observation setup이 mutex critical section 안에서 호출되지 않고 exact I/O count와 leak 0을 유지.

- [ ] **Step 9.2: benchmark와 targeted quality gate를 실행한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.SqsObservationAllocationTest' \
    --no-daemon --no-build-cache
  ./gradlew :bluetape4k-aws-spring-boot:benchmark --no-daemon
  ./gradlew :bluetape4k-aws-spring-boot:compileKotlin \
    :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --no-build-cache
  ./gradlew detekt --no-daemon
  ```

  Expected: allocation/benchmark task/compile/detekt PASS. disabled allocation delta의 95% CI upper bound는 `0.5 B/op` 이하, disabled counter는 0, active/ACK/heartbeat count budget은 expected와 동일하고 모든 workload의 invocation/sentinel assertion이 PASS한다. latency percentile은 같은 run의 baseline ratio·CI와 함께 기록하되 명세가 금지한 임의 절대 시간 threshold로 PASS/FAIL을 만들지 않는다.

- [ ] **Step 9.3: Kotlin final checklist를 적용한다**

  - 새 production `!!`, suspend `runCatching`, swallowed cancellation, blocking event-loop call, monitor 기반 coroutine lock 0건.
  - 모든 `catch (Throwable)`에서 cancellation 전파 또는 bounded cleanup 의도가 test로 증명됨.
  - `NonCancellable`은 반드시 끝나야 하는 suspend cleanup에만 사용.
  - touched tests는 JUnit 5, MockK, bluetape4k assertions와 실제 cancellation을 사용.
  - optional auto-config의 class/property 조건이 모든 참조 phase에 존재.
  - public KDoc은 한국어이며 API/implementation lifecycle 책임이 분리됨.

- [ ] **Step 9.4: Lore commit을 만든다**

  Intent line: `test: SQS 관찰의 비활성 fast path와 coroutine 계약을 고정한다`

  **Rollback/rerun:** 실행 전 `./gradlew :bluetape4k-aws-spring-boot:tasks --all --no-daemon`에서 `benchmark`와 `benchmarkBenchmarkJar` generated task를 확인한다. task가 없으면 benchmark code를 작성하지 말고 기존 plugin/source-set wiring 문제를 먼저 고친다. 측정 없이 성능 통과를 선언하지 않는다.

### Task 10: 문서·lesson·전체 검증과 구현 review 준비

**Complexity:** XL
**Depends on:** Task 9
**Write scope:** `CHANGELOG.md`, EN/KO manual, README, example contract, lesson, review artifact
**Expected DoD:** 사용자가 activation, privacy, customization, migration과 rollback을 구현과 같은 계약으로 이해하고 전체 module evidence가 fresh하다.

- [ ] **Step 10.1: customization example contract를 문서와 연결한다**

  manual 예시는 `SqsObservationCustomizationExampleTest.kt`의 marker-bounded source와 다음 내용을 동일하게 유지한다.

  - PROCESS convention 교체.
  - `@Order`가 있는 customizer 두 개.
  - supplied registry를 사용하는 `createNotStarted` factory.
  - prerequisite가 없으면 user factory만으로 활성화되지 않는 fallback.
  - user factory는 전달받은 context와 registry를 그대로 사용하고 시작되지 않은 observation만 반환하며, `start/error/stop` lifecycle은 runtime만 소유한다.
  - 다른 context/registry, 이미 시작된 observation을 반환하면 bounded configuration failure가 나고, factory 반환이 `Observation.NOOP`이면 context/customizer/factory 뒤 start/scope/capture/event 없이 business block을 직접 실행한다.

  test가 EN/KO fenced code block을 fixture source와 정규화 비교하고, fixture 자체는 `compileTestKotlin`에서 컴파일된다.

- [ ] **Step 10.2: EN/KO manual과 README를 수정한다**

  두 locale에서 구조와 링크를 맞추고 다음을 명시한다.

  - default disabled와 활성 prerequisite.
  - 다음 exact activation YAML과 restart/redeploy 적용 경계.

    ```yaml
    bluetape4k:
      aws:
        sqs:
          observation:
            enabled: true
    ```

  - tag privacy allowlist, inbound carrier propagation 미지원.
  - listener ID는 bounded operator configuration이며 blank는 `unknown`; message에서 동적으로 만들지 않음.
  - customizer/factory가 generic context에 외부 데이터를 추가하면 privacy/cardinality 책임은 사용자에게 있고 raw message/body/receipt/message·system attribute 접근자는 제공하지 않음.
  - detached manual ACK parent 규칙.
  - legacy listener meter/operations meter migration matrix.
  - `BT4K-SQS-OBS-101/201/202` 진단과 troubleshooting.
  - runtime property rebind는 지원하지 않으며 활성화·비활성화 모두 restart/redeploy가 필요하다.
  - canary는 최소 30분과 10,000 message를 모두 충족할 때까지 legacy listener meter, 새 observation count, p95 process latency, redelivery rate, DLQ count를 비교한다.
  - observation count mismatch, p95 20% 초과 상승, redelivery rate 1%p 초과 상승, DLQ 신규 발생 중 하나면 receive stop → in-flight drain → `STOPPING_RECEIVE → DRAINING → STOPPED` → property false → restart/redeploy 순서로 abort/rollback한다.
  - dashboard와 alert는 전체 canary window가 통과한 뒤에만 새 meter로 전환하고 그 전에는 legacy meter를 보존한다.
  - `context-propagation:1.2.1`은 transitive runtime dependency이고 공개 signature에는 노출되지 않으며 schema/persisted-state migration은 없다.
  - 실제 AWS/OpenTelemetry exporter 검증 `N/A`, Floci acceptance 범위.

  README는 기능 요약과 manual link만 추가하고 상세 manual chapter를 복제하지 않는다. root/module EN/KO 네 파일의 link target과 anchor가 존재하고 locale 구조가 일치하는지 contract test로 확인한다.

  `CHANGELOG.md`의 `[미출시]` → `추가`에 한국어로 opt-in SQS Observation, coroutine context propagation, legacy meter 공존, `FlociServer` acceptance, 실제 AWS/OpenTelemetry exporter `N/A`와 #473 링크를 한 항목으로 기록한다.

  운영 소유권은 다음과 같이 고정한다.

  | 운영 책임 | 담당 | 완료 증거 |
  | --- | --- | --- |
  | activation과 canary 승인/중단 | `debop` | canary window와 abort signal 기록 |
  | dashboard/alert meter 전환 | `debop` | 전체 window 통과 뒤 전환 read-back |
  | rollback과 진단 코드 확인 | `debop` | drain state, restart 뒤 marker 부재, legacy meter 복구 |
  | heartbeat 정책 자체 | #453 | #473은 telemetry 경계만 소유하고 주기·정책은 변경하지 않음 |

- [ ] **Step 10.3: Type A lesson을 작성한다**

  `docs/lessons/2026-08-27-issue-473-sqs-observation.md`에 다음을 기록한다.

  - scope와 coroutine context element 수명 분리.
  - optional classpath와 실제 handler prerequisite.
  - ACK actual-I/O observation 경계와 cancellation rollback 순서.
  - Floci가 증명한 범위와 actual AWS `N/A`.
  - benchmark/CI/review에서 발견한 reusable lesson.

- [ ] **Step 10.4: 문서와 전체 module 검증을 순차 실행한다**

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.sqs.*Observation*' \
    --no-daemon --no-build-cache
  ./gradlew :bluetape4k-aws-spring-boot:test \
    -Dbluetape4k.aws.emulator=floci \
    --no-daemon --no-build-cache
  ./gradlew :bluetape4k-aws-spring-boot:compileKotlin \
    :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --no-build-cache
  ./gradlew detekt --no-daemon
  ruby scripts/manual/export_manifest.rb \
    docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
  ruby scripts/manual/manual_contract_test.rb
  node ~/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
    docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md \
    docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md \
    README.ko.md \
    aws-spring-boot/README.ko.md \
    docs/lessons/2026-08-27-issue-473-sqs-observation.md
  git diff --check
  ```

  Expected: observation targeted PASS, 전체 module PASS와 emulator skip 0, compile/detekt/manual/audit/diff PASS. 기존 Floci baseline `1395 passed, 2 pending, 0 failed`와 비교해 실제 테스트 수, pending/skip 수와 증감 이유를 기록한다.

- [ ] **Step 10.5: Lore commit을 만든다**

  Intent line: `docs: SQS 관찰의 활성화와 rollback 책임을 명확히 한다`

- [ ] **Step 10.6: 구현 diff를 6관점 독립 review한다**

  performance, stability, security, operator/ops, developer/API, user/caller lane이 같은 exact head를 read-only 검토한다. main session이 finding을 deduplicate하고 P0/P1을 수정한 뒤 영향 관점을 fresh rerun한다. 최종 결과를 `docs/review/2026-08-27-issue-473-sqs-observation-code-review.md`에 한국어로 기록한다.

- [ ] **Step 10.7: delivery gate를 준비한다**

  - final scope, issue link, milestone/labels/assignee를 live GitHub에서 다시 읽는다.
  - commit을 remote에 publish하기 전 별도 publication authority를 확인한다.
  - PR body는 마지막 H2가 `## DoD Status`인지 lint/read-back한다.
  - exact-head CI가 terminal success가 아니면 merge-ready로 진행하지 않는다.

  **Rollback/rerun:** 문서와 source가 어긋나면 문구만 완화하지 않고 public API test와 manual example을 함께 고친다. 전체 module failure는 targeted pass로 덮지 않는다.

## 4. 수용 기준 추적성

| 설계 수용 기준 | 구현 task | 핵심 증거 |
| --- | --- | --- |
| AWS/SQS global enable, property, class, non-NOOP registry, supporting Spring handler에서만 활성화 | 4, 5 | `SqsObservationAutoConfigurationTest`, `FilteredClassLoader`, condition reasons |
| user factory는 default만 대체하고 prerequisite를 우회하지 않음 | 2, 4 | customization fixture, auto-config matrix |
| process가 conversion/handler/retry/자동 ACK와 coroutine downstream context를 포함 | 3, 6 | runtime dispatcher test, container lifecycle test |
| receive와 실제 ACK/heartbeat visibility I/O만 독립 observation | 6, 7, 8 | exact observation counts, Floci I/O 결과 |
| success/retried/error/cancelled/partial outcome 구분 | 2, 3, 6, 7 | convention tag test, lifecycle matrix |
| body, receipt, full URL, secret header, exception message 비노출 | 1, 2, 8 | metadata/toString/tag scan, redacted error test |
| message/FIFO ID와 exact attempt는 단건 high cardinality에만 존재 | 1, 2, 6, 8 | convention test, batch-size-1 test, Floci FIFO case |
| batch는 크기 1도 개별 ID를 노출하지 않음 | 1, 6, 7 | metadata와 batch process/ACK tests |
| business Throwable 대신 redacted telemetry error 사용 | 2, 3, 6, 8 | handler recording, Throwable identity와 redaction assertions |
| primary/suppressed, parent 복원, exactly-once stop | 3, 6, 7 | lifecycle failure matrix, container stop race |
| cancellation ACK rollback 후 waiter 완료와 재호출 | 7 | batch cancellation race 반복 test |
| retry event 최대 1과 observation count budget | 3, 6, 8, 9 | exact event/count assertions, benchmark counters |
| activation 시 자동 legacy listener metric만 억제, operations meter 유지 | 4 | Micrometer interceptor/operations tests |
| disabled와 기존 BPP/properties/bean/container ABI 유지 | 4, 5 | direct fast path, reflection, `javap`, binary tests |
| bounded diagnostics, restart-only activation/rollback, canary와 운영 소유권 문서화 | 4, 10 | condition report/log assertions, sequential context restart simulation, EN/KO manual contract |
| context-propagation transitive dependency와 public signature 비노출 | 1, 5 | dependency report, signature scan |
| Floci listener와 in-memory handler 통과 | 3, 8, 10 | targeted runtime test, `SqsObservationAwsEmulatorTest`, 전체 module test |
| actual AWS와 OpenTelemetry exporter는 N/A | 1, 8, 10 | plan/manual/lesson/DoD의 명시적 N/A |

## 5. 구현 위험 예측과 중단 조건

| 위험 | 조기 증거 | 대응/중단 조건 |
| --- | --- | --- |
| Micrometer scope를 suspension 뒤 다른 thread에서 닫음 | dispatcher 전환 test의 parent/current mismatch | scope capture/close 순서를 Task 3으로 되돌리고 container integration 중단 |
| optional type eager linkage | `FilteredClassLoader` context startup failure | outer signature에서 optional type을 제거할 때까지 Task 5 진행 금지 |
| legacy meter가 빈 registry에서 사라짐 | auto-config matrix의 interceptor bean 부재 | property 조건 대신 activation marker 조건을 복구 |
| user factory가 다른 context/registry 또는 started observation 반환 | identity/current observation test failure | context/registry 위반은 fail fast; started 여부는 KDoc/test contract 범위만 약속 |
| cancellation 중 ACK waiter hang | race test timeout, `IN_FLIGHT` 잔류 | mutex 내부 rollback 후 외부 completion 순서를 복구하고 Floci 진행 금지 |
| heartbeat telemetry 실패가 business 결과를 바꿈 | handler/visibility result mismatch | `BT4K-SQS-OBS-202` 진단 후 telemetry failure 무시 경계 복구 |
| tag cardinality 또는 secret 누출 | allowlist 기준 데이터 차이, forbidden token 발견 | public context 접근 경계를 좁히고 해당 review lens 재실행 |
| disabled fast path allocation 증가 | factory/capture counter 증가 또는 benchmark 회귀 | nullable/registry-NOOP 직접 반환을 observation context 생성 이전으로 이동 |
| Floci capability 공백 | SDK operation unsupported 원문 | actual AWS 성공으로 추정하지 않고 수용 기준을 PENDING으로 보고 |
| ABI descriptor drift | reflection/`javap` mismatch | setter 연결 외 constructor/bean parameter 변경을 되돌림 |

## 6. repository hazard와 N/A

- 새 Gradle module/artifact 없음: `settings.gradle.kts`, publishable BOM constraint와 Kover module registration은 N/A.
- shared version catalog 변경 없음: `bt4k.micrometer.context.propagation` alias와 BOM-managed 1.2.1을 그대로 사용하고 runtime dependency report만 검증한다.
- Spring auto-configuration imports 변경: applicable. name-only outer registration, nested optional class isolation, property/bean ordering과 negative condition reason을 Task 4에서 검증한다.
- Actuator health/readiness 변경 없음: N/A. 새 health contributor나 readiness/liveness state를 추가하지 않고 auto-config startup이 Floci/AWS 연결 없이 non-blocking임을 `ApplicationContextRunner`로 검증한다.
- public ABI 성장: applicable. 새 context/convention/factory API compile fixture와 기존 BPP/bean/container/properties descriptor를 Task 2·5에서 함께 검증한다.
- Exposed/data access 변경 없음: transaction, operator import와 receiver-shadowing check는 N/A.
- HTTP adapter 변경 없음: SNS HTTP verification, MVC/WebFlux와 HC5 lifecycle check는 N/A.
- Testcontainers/Floci 성장: applicable. `FlociServer.Launcher.floci`, 순차 실행, bounded timeout, listener/context teardown, skip 0을 Task 8에서 요구한다.
- workflow YAML/Nightly path filter 변경 없음: N/A. 다만 `aws-spring-boot/**`가 바뀌므로 PR exact-head의 `Test / aws-spring-boot` job은 skip 없이 terminal success여야 한다.
- manual 새 page와 diagram 없음: manifest entry·editable SVG/PNG는 N/A. 기존 EN/KO page와 README link만 동기화하고 manual contract를 실행한다.
- CHANGELOG `[미출시]` 갱신: applicable. 한국어 `추가` 항목에 consumer-visible `context-propagation:1.2.1` transitive dependency, default-disabled, schema/persisted-state migration 없음, restart/redeploy 요구와 Floci-only 검증 경계를 manual/PR metadata와 일치시킨다.
- actual AWS/IAM/cross-account/exporter 검증 없음: 사용자에게 AWS 계정이 없고 범위 밖이므로 N/A. Floci와 in-memory handler 증거를 실제 AWS 운영 증거로 표현하지 않는다.

## 7. 계획 승인 뒤 실행 규칙

1. 승인 직후 reviewed spec과 reviewed plan을 하나의 Lore commit 또는 서로 독립적인 문서 commit으로 먼저 고정한다.
2. 구현은 `subagent-driven-development` 또는 `executing-plans` 중 현재 runtime에 맞는 하나를 사용하고, 각 task의 RED → 최소 GREEN → refactor → verification 순서를 지킨다.
3. 독립 task만 병렬화하며 Docker/Floci와 동일 파일 write scope는 병렬 실행하지 않는다.
4. 각 commit 전에 targeted test와 `git diff --check`를 실행하고 receipt에 fresh evidence를 연결한다.
5. scope가 inbound carrier, tracer SDK, heartbeat 정책, backpressure 또는 새 module로 넓어지면 현재 구현을 멈추고 별도 이슈/설계 승인으로 분리한다.
6. PR 생성 전 plan/spec/task checkbox, implementation review, lesson, exact-head local evidence를 다시 읽는다.
