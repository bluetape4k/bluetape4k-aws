# SNS topic ARN resolver·cache Implementation Plan

> **For agentic workers:** 이 계획은 승인된 Type-A 설계에 따라 단계별로 실행한다. 각 단계는 TDD 증거와 체크박스로 추적하며, PR·merge는 별도 권한 없이는 수행하지 않는다.

**Goal:** Spring Boot SNS의 topic name/ARN resolver와 bounded, scoped, coroutine-safe cache를 추가해 반복적인 `ListTopics` 조회와 duplicate lookup을 줄인다.

**Architecture:** `SnsTopicArnResolver`가 입력 정규화, pagination, per-key flight와 AWS 오류 경계를 담당한다. `SnsTopicArnCache`는 scope가 포함된 key에 대해 TTL/LRU/negative entry를 저장한다. `SnsAutoConfiguration`은 기본 cache와 resolver를 bean으로 만들고 `SnsCoroutinesTemplate`은 resolver를 주입받아 기존 `findTopicArn` 호환 API를 유지한다.

**Tech Stack:** Kotlin 2.x, kotlinx-coroutines `Mutex`, JVM `ReentrantLock`, AWS SDK v2 `SnsAsyncClient`, Spring Boot 4 auto-configuration, JUnit 5, MockK, Floci.

---

## 파일 책임과 변경 지도

| 파일 | 책임 |
|---|---|
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsTopicArnResolver.kt` | resolver, scope/key/entry/cache 계약, in-memory/no-op cache, pagination과 single-flight |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsProperties.kt` | `accountId`, cross-account policy, `topicArnCache` Spring Boot property binding과 validation |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsCoroutinesTemplate.kt` | resolver 주입, `findTopicArn` 위임, create 성공 후 invalidate, 기존 생성자 유지 |
| `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsAutoConfiguration.kt` | cache/resolver bean 등록 및 조건부 사용자 bean 우선 |
| `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsTopicArnResolverTest.kt` | fake client 기반 RED/GREEN resolver/cache 계약 검증 |
| `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsCoroutinesTemplateTest.kt` | template delegation/invalidate 회귀 검증 |
| `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsAutoConfigurationTest.kt` | 기본/custom/disabled cache와 resolver bean 검증 |
| `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsCoroutinesTemplateAwsEmulatorTest.kt` | Floci topic create/find/publish 및 FIFO regression |

## Task 1: 계약·property·cache 테스트를 먼저 추가

**Files:**

- Create: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsTopicArnResolverTest.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsAutoConfigurationTest.kt`

- [x] **Step 1: RED 테스트 작성** — `SnsTopicArnCache`의 TTL, LRU max size, negative entry, invalidate/clear와 `SnsProperties`의 `topicArnCache` binding을 호출할 기대 API로 작성한다. AWS client mock은 `ListTopicsResponse`의 전체 구조를 제공하며 mock 자체를 검증하지 않고 resolver 결과와 호출 횟수를 검증한다.
- [x] **Step 2: RED 실행**

Run:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.SnsTopicArnResolverTest' --tests 'io.bluetape4k.aws.spring.sns.SnsAutoConfigurationTest' --no-configuration-cache --no-daemon --max-workers=1 --no-parallel
```

Expected: 새 resolver/cache 타입과 property가 아직 없어 compile/test failure가 발생한다.

- [x] **Step 3: 최소 cache/property 구현** — `DataKeyCache`의 `Clock`/LRU/TTL 패턴을 복사하되 SNS entry는 `Resolved`와 `NotFound`를 구분한다. `maxSize <= 0`, non-positive 또는 `24h` 초과 TTL, blank account ID를 즉시 거부한다. cache key의 namespace와 diagnostic redaction을 고정한다.
- [x] **Step 4: GREEN 실행** — 동일 명령을 다시 실행해 cache/property 테스트가 통과하는지 확인한다.
- [x] **Step 5: checkpoint** — `git diff --check`와 테스트 출력에서 경고/실패를 확인하고, 실패 시 Task 1로 되돌아간다.

## Task 2: resolver normalization·pagination·negative cache

**Files:**

- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsTopicArnResolver.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsTopicArnResolverTest.kt`

- [x] **Step 1: RED 테스트 작성** — pagination 2페이지, topic 미존재 negative cache, explicit ARN 우회와 SNS ARN 형식/region/cross-account/wildcard 정책, `.fifo` suffix 정확성, AWS 예외 미저장 테스트를 추가한다.
- [x] **Step 2: RED 실행** — Task 1 targeted command로 새 테스트가 계약 누락 때문에 실패하는지 확인한다.
- [x] **Step 3: 최소 구현** — trim/blank/topic-name 검증, SNS ARN parser와 기본 cross-account 거부, `arn:` direct return, `ListTopics` nextToken loop와 `:$topicName` suffix 비교, 성공/null만 cache 저장을 구현한다. 예외와 `CancellationException`은 catch하지 않거나 재전파한다.
- [x] **Step 4: GREEN 실행** — resolver 테스트를 단독 실행해 pagination/normalization/negative/error 경계가 통과하는지 확인한다.

## Task 3: scoped per-key single-flight와 lifecycle cleanup

**Files:**

- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsTopicArnResolver.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsTopicArnResolverTest.kt`

- [x] **Step 1: RED 테스트 작성** — 같은 name의 동시 coroutine이 SDK를 1회만 호출하는지, `Noop` cache와 max-size eviction에서도 transient outcome을 공유하는지, failure 후 다음 호출이 재시도하는지, caller cancellation 후 flight가 남지 않는지, invalidate/clear 중인 조회가 stale cache를 늦게 쓰지 않는지, 같은 endpoint/region/account라도 다른 resolver/client namespace가 같은 cache에서 분리되는지, 서로 다른 key가 병렬 진행되는지 검증한다.
- [x] **Step 2: RED 실행** — `runTest`에서 unresolved `CompletableFuture`를 사용해 duplicate call count가 2가 되는 기대 실패를 확인한다.
- [x] **Step 3: 최소 구현** — key별 flight의 transient success/failure outcome과 user count를 추적한다. flight table은 `ReentrantLock`으로 보호하고 같은 key의 lookup은 per-key `Mutex.withLock`으로 double-check와 AWS 호출을 직렬화한다. cache put/invalidate/clear는 공통 lock 순서로 stale late-write를 차단한다. `finally`에서 user count가 0이면 flight를 제거한다. background scope/`GlobalScope`/`runBlocking`은 사용하지 않는다.
- [x] **Step 4: GREEN 실행** — 동시성·취소 테스트를 단독 실행하고, cancellation identity가 swallowed되지 않았는지 확인한다.

## Task 4: template integration와 compatibility

**Files:**

- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsCoroutinesTemplate.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsCoroutinesTemplateTest.kt`

- [x] **Step 1: RED 테스트 작성** — injected resolver를 사용한 `findTopicArn` delegation, `createTopic`/`createFifoTopic` 성공 후 invalidate, 기존 2개 생성자 compile/behavior compatibility를 검증한다.
- [x] **Step 2: RED 실행** — template test에서 injected resolver 생성자 또는 delegation이 없어 실패하는지 확인한다.
- [x] **Step 3: 최소 구현** — private primary constructor에 resolver를 추가하고 기존 public constructor를 보존한다. `findTopicArn`은 resolver에 위임하고 create 성공 ARN 반환 직전에 invalidate한다. batch/publish/SMS/confirmation 코드는 변경하지 않는다.
- [x] **Step 4: GREEN 실행** — `SnsCoroutinesTemplateTest`와 resolver 테스트를 함께 실행한다.

## Task 5: Spring Boot auto-configuration

**Files:**

- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsAutoConfiguration.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsAutoConfigurationTest.kt`

- [x] **Step 1: RED 테스트 작성** — 기본 cache/resolver bean, `enabled=false`의 `Noop` cache, custom cache/resolver back-off, properties와 `SnsConnectionDetails` 각각의 effective endpoint/region, account/cross-account policy binding을 확인한다.
- [x] **Step 2: RED 실행** — auto-configuration test에서 bean 누락/constructor mismatch failure를 확인한다.
- [x] **Step 3: 최소 구현** — `@ConditionalOnMissingBean` cache/resolver bean을 등록하고 effective `SnsConnectionDetails` endpoint/region과 properties account/policy/cache options를 전달한다. operations bean이 custom이면 기존처럼 template을 만들지 않는다.
- [x] **Step 4: GREEN 실행** — auto-config 및 compatibility test를 실행한다.

## Task 6: Floci smoke와 전체 affected-module 검증

**Files:**

- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsCoroutinesTemplateAwsEmulatorTest.kt`

- [x] **Step 1: RED test extension** — standard/FIFO topic create 후 `findTopicArn`과 publish를 resolver 경로로 실행하고 create invalidation 경계를 확인한다.
- [x] **Step 2: Floci sequential run**

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.SnsCoroutinesTemplateAwsEmulatorTest' -Dbluetape4k.aws.emulator=floci --no-configuration-cache --no-daemon --max-workers=1 --no-parallel
```

Expected: Floci SNS create/find/publish smoke가 PASS한다. Docker/emulator capability gap이면 raw output과 N/A 범위를 기록하고 unit coverage를 증거로 남긴다.

- [x] **Step 3: module test**

```bash
./gradlew :bluetape4k-aws-spring-boot:test --no-configuration-cache --no-daemon --max-workers=1 --no-parallel
```

- [x] **Step 4: static/diff checks** — `./gradlew :bluetape4k-aws-spring-boot:detekt`가 존재하면 실행하고, `git diff --check`, changed Kotlin KDoc/API read-back, public constructor compatibility를 확인한다.

## Task 7: Type-A verifier, review, lesson, no-PR handoff

- [x] **Step 1: spec/plan traceability** — 설계의 각 acceptance row를 구현 파일·테스트·명령 결과에 연결하고 gaps/risks를 기록한다.
- [x] **Step 2: performance/stability scan** — cache lock 범위, clock expiry, flight cleanup, cancellation, allocation과 external emulator lifecycle을 점검한다. transient flight는 caller 수명 동안만 유지되고 key별 map에서 제거되므로 고정 global semaphore는 두지 않는다. P0/P1이 있으면 Task 2–6으로 되돌린다.
- [x] **Step 3: final checklist** — `verification-before-completion`으로 compile/test/static/diff/API/KDoc를 재검증한다.
- [x] **Step 4: lesson** — `docs/lessons/2026-08-25-sns-topic-arn-resolver.md`를 Korean으로 작성하고 SPW-01~05를 통과시킨 뒤 Lore commit을 만든다. 구현 산출물과 테스트만 있고 durable lesson이 없다는 판단도 근거와 함께 기록한다.
- [x] **Step 5: delivery boundary** — 현재 요청은 구현이며 PR repository/base/head와 PR 생성 권한을 명시하지 않았다. CG-11~CG-18/A-10~A-12는 N/A로 보고하고, PR/merge/remote push는 실행하지 않는다.

### Step 6-R 통합 review evidence

| 관점 | 초기 finding | 보완 및 현재 판정 |
|---|---|---|
| Performance | P1 3, P2 3 | transient outcome, Noop/LRU 동시성, positive hit/TTL, key별 병렬 테스트를 추가했다. P0=0, P1=0; active-flight 상한은 caller 수명과 map 제거로 bounded하므로 global semaphore를 도입하지 않는 P2 보류다. |
| Stability | P1 3, P2 2 | invalidate/clear late-write 차단, effective connection scope, Floci negative→create→find, cancellation cleanup을 검증했다. P0=0, P1=0; owner/waiter cancellation stress는 후속 강화 항목이다. |
| Security | P1 3, P2 2 | SNS ARN/region/account 검증, cross-account opt-in, endpoint credential/query/fragment 차단, namespace·diagnostic redaction, TTL 상한을 반영했다. P0=0, P1=0. |
| Main integration | — | acceptance matrix, Korean KDoc/spec/plan/lesson, scope·ABI·emulator evidence를 재대조했다. 최종 P0=0, P1=0. |

## Rollback과 위험 예측

| 위험 | 신호 | 완화 | rollback/rerun |
|---|---|---|---|
| cache stale/negative hit | create 후 null 반복 | create success invalidate, TTL/수동 invalidate 테스트 | Task 4 revert 후 resolver unit rerun |
| scope collision | endpoint/region/account 변경 후 이전 ARN 반환 | scope를 key에 포함, shared-cache test | Task 3 revert 후 scope tests rerun |
| duplicate AWS call | concurrent count > 1 | key별 Mutex + double-check | Task 3 단독 재실행 |
| cancellation leak | 취소 후 다음 lookup이 hang/중복 | finally user count cleanup, no external scope | Task 3 cancellation test rerun |
| SDK/emulator drift | Floci smoke failure | fake client pagination을 primary proof로 유지, capability gap 기록 | Task 6 sequential rerun |
| API compatibility | 기존 constructor/test compile failure | old constructors and `SnsOperations` unchanged | Task 4 compatibility test rerun |

## Plan writer gate 기록

- SPW-01: PASS — Issue #474와 current source anchors를 파일/명령으로 고정했다.
- SPW-02: PASS — exact files, RED/GREEN actions, commands, expected evidence,
  rollback, hazards, delivery boundary를 포함했다.
- SPW-03: PASS — Korean technical register를 적용했고 code/API/commands는
  원문 토큰을 보존했다.
- SPW-04: PASS — spec acceptance matrix와 task/file/test/command를 대조했다.
- SPW-05: PASS — plan read-back에서 placeholder/TBD/후행 의존성을 제거했다.
