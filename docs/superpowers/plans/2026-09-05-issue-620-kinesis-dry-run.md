# Issue #620 Kinesis DryRun 구현 계획

> **구현자 안내:** 승인된
> [`2026-09-05-issue-620-kinesis-dry-run-design.md`](../specs/2026-09-05-issue-620-kinesis-dry-run-design.md)를
> TDD로 실행한다. Kotlin 구현은 `$bluetape-kotlin-patterns`를 따르고, Gradle 명령은
> context-mode를 통해 실행한다. Docker emulator는 순차 실행하며 실제 AWS endpoint와
> ambient credentials를 사용하지 않는다.

**목표:** AWS SDK for Kotlin `1.8.46`의 Kinesis `DryRun`을 네 convenience API와 두 기존
request helper에 builder-last 계약으로 노출하고, request/wire/no-write/ABI/문서 계약을
exact-head 증거로 고정한다.

**구조:** central catalog SHA와 CI pin을 먼저 일치시킨다. public API에는 builder 바로 앞에
`dryRun: Boolean = false`를 추가하고, old JVM descriptor에는 hidden overload를 남긴다.
fake/model 테스트가 request mapping과 exception transparency를 증명하고, JDK loopback
server가 실제 SDK JSON을 검증하며, Floci-first integration이 capability와 write no-op을
검증한다. 별도 additive ABI task와 pre-change Java consumer가 12개 old direct/`$default`
호출의 linkage를 증명한다.

**기술 스택:** Kotlin/JVM, AWS SDK for Kotlin `1.8.46`, coroutines, JUnit 5, MockK, Kluent,
JDK `HttpServer`, Floci/LocalStack Testcontainers, Gradle Kotlin DSL, Python 3 contract test.
새 dependency는 추가하지 않는다.

---

## Task 0: 계획 review·risk와 구현 전 checkpoint

**Files:**

- Create: `docs/superpowers/reviews/2026-09-05-issue-620-kinesis-dry-run-plan-review.md`
- Create: `docs/superpowers/risk/2026-09-05-issue-620-kinesis-dry-run-risk.md`
- Modify: `docs/superpowers/plans/2026-09-05-issue-620-kinesis-dry-run.md`
- Modify: `docs/superpowers/specs/2026-09-05-issue-620-kinesis-dry-run-design.md`
- Existing: `docs/superpowers/reviews/2026-09-05-issue-620-kinesis-dry-run-spec-review.md`
- Create: `src/abi-fixtures/kinesis-dry-run-pre-change/README.md`
- Create: `src/abi-fixtures/kinesis-dry-run-pre-change/kinesis-client-extensions.javap.txt`
- Create: `src/abi-fixtures/kinesis-dry-run-pre-change/put-record.javap.txt`
- Create: `src/abi-fixtures/kinesis-dry-run-pre-change/get-shard-iterator.javap.txt`

- [x] **Step 1: six-lens 계획 검토**

  Performance, Stability, Security, Operator/Ops, Developer/API, User/caller 관점이 다음을
  독립적으로 검토한다: catalog blast radius, exact call/copy budget, cancellation/cleanup,
  endpoint/credential guard, emulator capability closed set, source/binary compatibility,
  migration과 영어·한국어 문서 parity. P0/P1을 계획에 반영한 뒤 focused 재검토로 0건까지
  수렴한다.

- [x] **Step 2: risk ledger 확정**

  catalog drift, default `DryRun:false` wire change, builder `null` override, emulator false-green,
  create ambiguity와 foreign stream deletion, timeout masking, ABI stub leakage, whole-catalog
  regression, 문서 drift를 신호·완화·재실행·stop/rollback 조건과 연결한다.

- [x] **Step 3: production/catalog 변경 전 ABI baseline freeze**

  worktree의 production code와 catalog가 아직 exact base SHA
  `f07015b6e9a3e6aceb4f301081b502cb88eb40c3`인지 assert한 뒤 old module JAR을 만든다.

  ```bash
  test "$(git rev-parse HEAD)" = "f07015b6e9a3e6aceb4f301081b502cb88eb40c3"
  test "$(git show HEAD:settings.gradle.kts | rg -o '[0-9a-f]{40}' | head -1)" = \
    "850959d0ea5f76ac7e2c442400f47653d5f95eed"
  ./gradlew :bluetape4k-aws-kotlin:jar --no-daemon --no-configuration-cache
  javap -classpath aws-kotlin/build/libs/bluetape4k-aws-kotlin-*.jar -public -s \
    io.bluetape4k.aws.kotlin.kinesis.KinesisClientExtensionsKt
  javap -classpath aws-kotlin/build/libs/bluetape4k-aws-kotlin-*.jar -public -s \
    io.bluetape4k.aws.kotlin.kinesis.model.PutRecordKt
  javap -classpath aws-kotlin/build/libs/bluetape4k-aws-kotlin-*.jar -public -s \
    io.bluetape4k.aws.kotlin.kinesis.model.GetShardIteratorKt
  ```

  세 출력에서 승인 spec의 12개 direct/`$default` owner/name/descriptor만 normalized fixture로
  저장한다. `README.md`에는 base SHA, old catalog SHA, 명령, JAR identity와 12개 closed set을
  기록한다. Task 5는 이 committed fixture를 읽기만 하며 새 source에서 재생성하지 않는다.

- [x] **Step 4: 문서 gate 실행**

  Run:

  ```bash
  node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
    docs/superpowers/specs/2026-09-05-issue-620-kinesis-dry-run-design.md \
    docs/superpowers/plans/2026-09-05-issue-620-kinesis-dry-run.md \
    docs/superpowers/reviews/2026-09-05-issue-620-kinesis-dry-run-spec-review.md \
    docs/superpowers/reviews/2026-09-05-issue-620-kinesis-dry-run-plan-review.md \
    docs/superpowers/risk/2026-09-05-issue-620-kinesis-dry-run-risk.md
  git diff --check
  ```

  Expected: terminology `findings=0`, whitespace error 없음, plan review `P0=0/P1=0`.

- [x] **Step 5: 구현 전 Lore checkpoint commit**

  Run:

  ```bash
  git add docs/superpowers/specs/2026-09-05-issue-620-kinesis-dry-run-design.md \
    docs/superpowers/plans/2026-09-05-issue-620-kinesis-dry-run.md \
    docs/superpowers/reviews/2026-09-05-issue-620-kinesis-dry-run-spec-review.md \
    docs/superpowers/reviews/2026-09-05-issue-620-kinesis-dry-run-plan-review.md \
    docs/superpowers/risk/2026-09-05-issue-620-kinesis-dry-run-risk.md \
    src/abi-fixtures/kinesis-dry-run-pre-change
  git commit -F - <<'EOF'
  #620 DryRun 계약과 검증 경계를 구현 전에 고정한다

  Constraint: 실제 AWS 없이 fake, loopback, Floci-first 증거로 검증한다.
  Rejected: builder-only 지원 | 이름 있는 API와 binary compatibility 요구를 충족하지 못한다.
  Confidence: high
  Scope-risk: broad
  Directive: 12개 legacy descriptor와 foreign stream 비삭제 경계를 약화하지 않는다.
  Tested: six-lens spec/plan review P0=0 P1=0, exact-base javap 12 descriptors, terminology findings=0, git diff --check
  Not-tested: production compile, emulator, compatibilityCheck, full build
  EOF
  ```

  Expected: production source 변경이 없는 첫 checkpoint. 구현 실패 시 이 commit이 안전한
  rollback 기준이다.

## Task 1: catalog·CI pin 계약을 먼저 RED→GREEN으로 고정

**Files:**

- Create: `.github/scripts/catalog_pin_contract_test.py`
- Modify: `.github/workflows/ci.yml:44,149-177,800-888`
- Modify: `settings.gradle.kts:11-15`

- [x] **Step 1: pin/filter contract RED 작성**

  Python test는 다음을 source text가 아니라 구조화된 정규식 결과로 검사한다.

  1. settings 기본 ref와 workflow `BLUETAPE4K_DEPENDENCIES_CATALOG_REF`가 동일하다.
  2. 값이 정확히 `9698c9d66bea6fcba373143ee8fa5bfbd9812d4b`다.
  3. `compatibility` filter가 `settings.gradle.kts`, `aws-kotlin/src/main/**`,
     `src/abi-fixtures/**`, `build.gradle.kts`를 포함한다.
  4. `changes` job이 이 contract test를 실행한다.
  5. `ci-status`가 `compatibility`를 `needs`에 포함하고, compatibility filter가 true인데
     job이 `skipped` 또는 success 이외 상태면 실패한다.

  Run: `python3 .github/scripts/catalog_pin_contract_test.py`

  Expected: 기존 두 pin이 old SHA이고 compatibility filter가 settings/aws-kotlin source를
  누락하므로 RED.

- [x] **Step 2: settings/workflow를 최소 수정**

  두 pin을 target SHA로 바꾸고 compatibility filter에 누락 경로를 추가한다. `changes` job의
  기존 helper test 옆에서 catalog contract test를 실행한다. `ci-status`는 compatibility
  result를 aggregate하고 `needs.changes.outputs.compatibility == 'true'`일 때 정확히 success를
  요구한다. workflow의 다른 trigger/job은 변경하지 않는다.

- [x] **Step 3: contract와 dependency resolution GREEN**

  Run:

  ```bash
  python3 .github/scripts/catalog_pin_contract_test.py
  ./gradlew :bluetape4k-aws-kotlin:dependencyInsight \
    --dependency aws.sdk.kotlin:kinesis --configuration testRuntimeClasspath \
    --no-daemon --no-configuration-cache
  ```

  Expected: contract PASS, selected Kinesis version `1.8.46`. property override 없이 실행한다.

- [x] **Step 4: catalog checkpoint commit**

  Intent: `#620 catalog와 CI가 같은 DryRun SDK를 사용하게 한다`. Lore trailers에 126개
  version key 변경이라는 broad blast radius, 임시 property override 기각, contract와
  dependencyInsight 결과, 아직 full build 미실행을 기록한다.

## Task 2: helper와 extension request 계약 TDD

**Files:**

- Modify: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisClientExtensions.kt`
- Modify: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/model/PutRecord.kt`
- Modify: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/model/GetShardIterator.kt`
- Modify: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/model/PutRecordTest.kt`
- Modify: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/model/GetShardIteratorTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisClientExtensionsMockTest.kt`

- [x] **Step 1: model/helper RED**

  두 helper test에 default `false`, explicit `true`, builder `true -> false`, builder
  `true -> null`, 기존 `ByteArray` identity를 추가한다.

  ```kotlin
  val request = putRecordRequestOf("stream", "partition", data, dryRun = true) {
      dryRun = null
  }
  request.dryRun shouldBeEqualTo null
  request.data shouldBeSameInstanceAs data
  ```

  Run:

  ```bash
  ./gradlew :bluetape4k-aws-kotlin:test \
    --tests '*PutRecordTest' --tests '*GetShardIteratorTest' --no-daemon
  ```

  Expected: helper에 named `dryRun`이 없어 compile RED.

- [x] **Step 2: extension fake RED**

  MockK `KinesisClient`에서 네 request를 capture한다. 각 operation에 대해 default/true/
  builder false/null을 검증하고 호출 횟수는 정확히 1회다. PutRecord data와 PutRecords
  entries/list identity를 확인한다. `DryRunOperationException`, 일반 SDK exception,
  `CancellationException`은 `shouldBeSameInstanceAs`로 동일 instance 전파를 확인한다.

  Run: `./gradlew :bluetape4k-aws-kotlin:test --tests '*KinesisClientExtensionsMockTest' --no-daemon`

  Expected: 새 인자와 mapping이 없어 compile/test RED.

- [x] **Step 3: 새 public overload와 helper 최소 구현**

  각 새 함수는 다음 순서를 사용한다.

  ```kotlin
  suspend inline fun KinesisClient.putRecord(
      streamName: String,
      partitionKey: String,
      data: ByteArray,
      dryRun: Boolean = false,
      crossinline builder: PutRecordRequest.Builder.() -> Unit = {},
  ): PutRecordResponse = putRecord {
      this.streamName = streamName
      this.partitionKey = partitionKey
      this.data = data
      this.dryRun = dryRun
      builder()
  }
  ```

  `putRecords`, `getShardIterator`, `getRecords`, `putRecordRequestOf`,
  `getShardIteratorRequestOf`도 `this.dryRun = dryRun` 뒤 `builder()`를 호출한다. 새 retry,
  exception mapping, request copy는 추가하지 않는다.

- [x] **Step 4: old source/binary overload 보존**

  네 extension과 두 helper에 변경 전 signature의 다음 형태를 둔다.

  ```kotlin
  @Deprecated("Binary compatibility overload", level = DeprecationLevel.HIDDEN)
  suspend inline fun KinesisClient.putRecord(
      streamName: String,
      partitionKey: String,
      data: ByteArray,
      crossinline builder: PutRecordRequest.Builder.() -> Unit = {},
  ): PutRecordResponse = putRecord(streamName, partitionKey, data, false, builder)
  ```

  새 source compiler에서는 숨기되 old direct/`$default` descriptor는 남긴다. trailing lambda와
  named `builder` compile fixture를 유지하고 괄호 안 positional builder는 migration 대상이다.

- [x] **Step 5: targeted GREEN**

  Run:

  ```bash
  ./gradlew :bluetape4k-aws-kotlin:test \
    --tests '*PutRecordTest' --tests '*GetShardIteratorTest' \
    --tests '*KinesisClientExtensionsMockTest' --no-daemon
  ```

  Expected: mapping/precedence/identity/cancellation/call-count 모두 PASS.

- [x] **Step 6: API checkpoint commit**

  Intent: `#620 Kinesis DryRun을 builder 우선 계약으로 노출한다`. Lore trailers에 old
  descriptor 보존, hidden overload 선택, positional builder migration, targeted test 결과,
  wire/emulator/ABI 미실행을 기록한다.

## Task 3: public wire serialization proof

**Files:**

- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisDryRunWireTest.kt`

- [x] **Step 1: loopback test RED 작성**

  JDK `HttpServer`를 literal `127.0.0.1`의 ephemeral port에 연다. 실제 `KinesisClient`는
  explicit endpoint, region, static fake credentials를 사용한다. test server는 operation별
  `X-Amz-Target`과 body를 메모리로 capture하고 AWS JSON `DryRunOperationException` response를
  반환한다. server/port/client는 `use`/`finally`로 닫는다.

  다음 table을 네 operation 모두에 실행한다.

  | 호출 | wire assertion |
  | --- | --- |
  | default | `"DryRun":false` 정확히 1회 |
  | `dryRun = true` | `"DryRun":true` 정확히 1회 |
  | builder `dryRun = null` | `DryRun` member 없음 |

  `PutRecords`는 top-level `DryRun`만 가지며 record마다 복제되지 않아야 한다. test 실패
  메시지에 body, `Authorization`, 전체 header를 넣지 않는다.

- [x] **Step 2: endpoint/credential guard 음성 테스트**

  loopback literal이 아닌 HTTP host 또는 static fake credential이 아닌 설정은 operation 전에
  실패시킨다. test는 ambient credential chain과 실제 AWS endpoint를 참조하지 않는다.

- [x] **Step 3: wire GREEN**

  Run: `./gradlew :bluetape4k-aws-kotlin:test --tests '*KinesisDryRunWireTest' --no-daemon`

  Expected: 네 target의 false/true/null serialization과 SDK exception type PASS. JDK loopback
  실행이 불가능하면 대체 성공으로 처리하지 않고 전체 DoD를 PENDING으로 둔다.

- [x] **Step 4: wire checkpoint commit**

  Intent: `#620 DryRun wire shape를 public SDK 경계에서 증명한다`. No-real-AWS와 no-secret-log
  constraint, internal serializer/reflection 기각, loopback test 결과를 Lore trailers에 기록한다.

## Task 4: Floci-first capability·no-write·cleanup 검증

**Files:**

- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisDryRunTestSupport.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisDryRunSupportTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisDryRunEmulatorTest.kt`
- Create: `.github/scripts/validate_kinesis_dry_run_capability.py`
- Modify: `.github/workflows/ci.yml` (aws-kotlin timeout 30분, test 뒤 capability validate/upload)
- Modify: `.github/workflows/nightly-tests.yml` (aws-kotlin timeout 75분, 동일 validate/upload)

- [ ] **Step 1: test-only ownership/cleanup helper RED**

  test support는 run nonce+UUID name 생성, `describeStream` absence preflight, 최대 3회 collision
  retry, create 전 cleanup registration, `ResourceNotFoundException` idempotence를 제공한다.
  preflight에서는 `ResourceNotFoundException`만 부재로 인정한다. `AccessDenied`, endpoint/
  connection 오류, timeout과 기타 예외는 즉시 non-zero failure이며 이름은 미소유로 남긴다.
  create가 server에 적용된 뒤 client가 실패하는 ambiguous path도 같은 owned name만 cleanup한다.
  pre-existing/`ResourceInUseException`과 그 밖의 응답에서 이름을 cleanup 대상에 등록하지
  않는 음성 test를 둔다.

  cleanup은 `withContext(NonCancellable) { withTimeout(30.seconds) { ... } }`이며 primary exception을
  유지하고 cleanup failure를 suppressed로 붙인다. fake test는 collision, create-before-response
  failure, body failure, cancellation, cleanup failure, timeout을 검증한다.

  test-only client factory와 create/describe/delete/read helper는 매 호출 전에 허용된
  loopback/emulator endpoint와 static fake credential marker를 재검사한다. null/default credential
  chain, 실제 AWS endpoint, endpoint userinfo, 임의 host는 network I/O 전에 실패시키고 fake
  transport call count가 0인지 음성 test로 고정한다.

- [ ] **Step 2: capability classifier RED**

  operation별 emulator unsupported closed set만 JUnit assumption으로 바꾼다. `AccessDenied`,
  HTTP 403, endpoint/connection failure, timeout, assertion failure, 정상 response는 skip되지
  않아야 한다. assumption message는 backend/version/operation/sanitized reason code/stream
  token만 포함하고 payload·credential·authorization은 제외한다. sanitizer는 임의 SDK/cleanup
  exception message를 그대로 사용하지 않고 bounded allow-list의 backend, 제한 길이 version,
  operation, reason code와 생성된 stream token만 반환한다. credential/access key/session token,
  `Authorization`, payload/body, 전체 header, endpoint userinfo sentinel을 exception과 cleanup
  failure에 주입해 assumption, JSON, JUnit/Gradle captured output에 나타나지 않는지 검증한다.
  client request logging은 비활성화한다.

  같은 정보는
  `aws-kotlin/build/reports/kinesis-dry-run/capability-<backend>.json`에 machine-readable로
  기록한다. schema는 `schemaVersion`, `backend`, `backendVersion`, `operation`,
  `status=supported|unsupported|failed`, `sanitizedReason`, `streamToken`이며 네 operation row가
  정확히 한 번씩 있어야 한다. validator는 누락/중복/unknown field/status, closed-set 밖의
  unsupported, secret/header/payload sentinel을 실패시킨다. 성공 시 allow-list field만 정규화한
  `capability-<backend>.validated.json`을 생성한다. 실패 시 raw report를 삭제하고 고정된 redacted
  metadata만 log에 남긴다. `failed` row는 Gradle test exit를 non-zero로 유지한다.

  Run: `./gradlew :bluetape4k-aws-kotlin:test --tests '*KinesisDryRunSupportTest' --no-daemon`

  Expected: support 구현 전 RED, 구현 후 모든 음성 분류와 cleanup 테스트 GREEN.

- [ ] **Step 3: isolated emulator scenarios**

  `@Execution(SAME_THREAD)`와 scenario별 `@Timeout(180)`을 사용한다. 각 시나리오 본문은 120초,
  cleanup은 30초, JUnit 종료 여유는 30초다. 별도 disposable stream에서:

  1. PutRecord dry-run 전후 bounded read로 marker 부재 확인
  2. PutRecords dry-run 전후 각 marker 부재 확인
  3. GetShardIterator dry-run의 operation-specific response/exception 확인
  4. 별도 정상 non-dry-run `getShardIterator`로 non-blank iterator를 만든 뒤 그 iterator를
     사용한 GetRecords dry-run의 operation-specific response/exception 확인

  유효한 dry-run이 정상 success를 반환하거나 iterator 준비가 실패하거나 marker가 보이면
  contract failure다. unsupported closed set일 때만 operation별 skip evidence를 남기며
  fake/model/wire proof는 항상 필수다.

  `withinOperationDeadline` helper는 각 operation, stream `ACTIVE`, baseline 및 record 관측을
  `withTimeout(30.seconds)`로 감싸고 polling/backoff를 500ms 이하로 clamp한다. fake clock/delay
  test는 30초 초과, 500ms 초과 요청, 무한 polling을 실패시키며 모든 관측 경로가 helper를
  통과하는지 검증한다. 네 scenario를 순차 실행하는 CI job의 `timeout-minutes`는 최악 경계와
  setup/teardown을 수용하면서 무한 대기를 막는 30으로 변경한다. 전체 module test를 최대 5회
  재시도하는 Full Nightly job은 최악 12분 x 5, 30초 retry backoff와 setup/teardown을 수용하도록
  75분으로 변경한다.

- [ ] **Step 4: Floci-first GREEN과 LocalStack fallback**

  Run sequentially:

  ```bash
  ./gradlew :bluetape4k-aws-kotlin:test \
    --tests '*KinesisDryRunSupportTest' --tests '*KinesisDryRunEmulatorTest' \
    -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 --no-daemon
  python3 .github/scripts/validate_kinesis_dry_run_capability.py \
    aws-kotlin/build/reports/kinesis-dry-run/capability-floci.json
  ```

  If and only if Floci reports the closed-set unsupported capability, run:

  ```bash
  ./gradlew :bluetape4k-aws-kotlin:test \
    --tests '*KinesisDryRunEmulatorTest' \
    -Dbluetape4k.aws.emulator=localstack --no-parallel --max-workers=1 --no-daemon
  ```

  Expected: supported operation은 `DryRunOperationException`과 no-write PASS; unsupported는
  explicit operation별 evidence. 인증/네트워크/Docker/timeout 등 closed-set 밖 오류는 test
  command를 non-zero로 실패시키고 assumption/skip하지 않는다. 최종 DoD의 `PENDING/BLOCKED`는
  그 실패를 pass로 바꾸지 않는 delivery 상태이며 원래 exit status를 함께 기록한다.

  PR CI와 Full Nightly는 test 뒤 validator를 `if: always()`로 실행한다. upload step은 validator
  success인 경우에만
  `capability-floci.validated.json`을 `coverage-aws-kotlin`과 분리된
  `kinesis-dry-run-capability` artifact로 올린다. validator failure에서는 raw report를 삭제하고
  artifact upload를 실행하지 않는다. workflow contract test는 unconditional upload와 raw report
  path upload를 금지하고 두 workflow의 30분/75분 bounded budget을 확인한다. report 누락이나
  validator 실패는 job을 실패시킨다. PR DoD는 PR CI와 실행 가능한 Full Nightly validated
  artifact의 네 operation row와 backend/version/status를 read-back한다.

- [ ] **Step 5: emulator checkpoint commit**

  Intent: `#620 DryRun emulator 검증이 외부 자원을 지우지 않게 한다`. Lore trailers에
  Floci-first, owned-name cleanup, LocalStack fallback 여부, skip/PENDING 결과를 기록한다.

## Task 5: 12개 legacy JVM 호출의 additive ABI fixture

**Files:**

- Modify: `build.gradle.kts`
- Create: `src/abi-fixtures/kinesis-dry-run-pre-change/README.md`
- Create: `src/abi-fixtures/kinesis-dry-run-pre-change/kinesis-client-extensions.javap.txt`
- Create: `src/abi-fixtures/kinesis-dry-run-pre-change/put-record.javap.txt`
- Create: `src/abi-fixtures/kinesis-dry-run-pre-change/get-shard-iterator.javap.txt`
- Create: `src/abi-fixtures/kinesis-dry-run-pre-change/stub/io/bluetape4k/aws/kotlin/kinesis/KinesisClientExtensionsKt.java`
- Create: `src/abi-fixtures/kinesis-dry-run-pre-change/stub/io/bluetape4k/aws/kotlin/kinesis/model/PutRecordKt.java`
- Create: `src/abi-fixtures/kinesis-dry-run-pre-change/stub/io/bluetape4k/aws/kotlin/kinesis/model/GetShardIteratorKt.java`
- Create: `src/abi-fixtures/kinesis-dry-run-pre-change/consumer/io/bluetape4k/aws/kotlin/kinesis/KinesisDryRunLegacyConsumer.java`
- Modify: `aws-kotlin/src/consumerFixture/kotlin/io/bluetape4k/aws/kotlin/consumer/KotlinServiceConsumerFixture.kt`

- [ ] **Step 1: pre-change baseline과 Java legacy consumer RED**

  Task 0에서 exact base SHA로 freeze한 세 `javap.txt`를 수정 없이 소비한다. Java stub은 정확히
  그 12개 method를 선언하고 consumer는 direct와 `$default` 12개를 모두 `invokestatic`으로 호출한다.
  네 extension 호출은 linkage 뒤 expected NPE를 잡고, helper 네 호출은 model field를 검사한다.

  기존 Kotlin external consumer fixture에는 네 extension과 두 helper 각각의 기존 trailing
  lambda와 named `builder = {}` 호출, 새 `dryRun = true` 호출을 추가한다. positional builder
  migration은 fixture의 migrated named/trailing 형태와 pre-change README의 before/after source로
  고정한다. `compileAwsKotlinServiceConsumerFixture`를 `compatibilityCheck` checks/dependencies에
  추가한다.

- [ ] **Step 2: compile/runtime classpath를 분리한 Gradle task 구현**

  `VerifyAdditiveKinesisAbiTask`는 production JAR의 `javap -public -s`가 baseline의 모든
  method/descriptor를 포함하는지 확인하고 additions는 허용한다. 기존
  `VerifyLegacyAbiTask`와 implementation baseline 규칙은 변경하지 않는다.

  별도 task는 다음 invariant를 먼저 assert한다.

  - stub compile: stub output+AWS/Kotlin dependencies, production JAR 없음
  - consumer compile: stub output+dependencies, production JAR 없음
  - runtime: consumer output+production JAR+dependencies, stub output 없음
  - `javap -c -s`: consumer가 12개 exact owner/name/descriptor를 참조

  `verifyKinesisDryRunAdditiveAbi`, `verifyKinesisDryRunLegacyInvocations`,
  `runKinesisDryRunLegacyConsumer`를 `compatibilityCheck`의 checks와 dependencies에 추가한다.

- [ ] **Step 3: compatibility RED→GREEN**

  Run:

  ```bash
  ./gradlew verifyKinesisDryRunAdditiveAbi \
    verifyKinesisDryRunLegacyInvocations runKinesisDryRunLegacyConsumer \
    compileAwsKotlinServiceConsumerFixture compatibilityCheck \
    --no-daemon --no-configuration-cache --no-build-cache
  ```

  Expected: hidden overload가 없으면 descriptor/linkage RED; Task 2 구현 후 12개 reference와
  runtime call이 GREEN. stub output이 runtime에 섞이면 명시적으로 실패한다.

- [ ] **Step 4: ABI checkpoint commit**

  Intent: `#620 기존 Kinesis binary가 새 DryRun API에서도 연결되게 한다`. Lore trailers에
  12개 descriptor, additive verifier 분리, classpath isolation, compatibilityCheck 결과를
  기록한다.

## Task 6: KDoc과 영어·한국어 module README

**Files:**

- Modify: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisClientExtensions.kt`
- Modify: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/model/PutRecord.kt`
- Modify: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/model/GetShardIterator.kt`
- Modify: `aws-kotlin/README.md:261-274`
- Modify: `aws-kotlin/README.ko.md:255-268`
- Modify: `CHANGELOG.md` (`[미출시]`의 `추가`)
- Create: `.github/scripts/kinesis_readme_contract_test.py`

- [ ] **Step 1: API documentation**

  네 extension KDoc에는 `@param dryRun`, successful validation의
  `DryRunOperationException`, 다른 SDK 예외와 coroutine cancellation의 그대로인 전파,
  builder-last를 기록한다. 실제 호출을 수행하는 두 write extension에는 payload/credential이
  endpoint로 전송되며 client-side validation·encryption·network block이 아니라는 경고를
  포함한다. 두 request helper KDoc에는 request mapping, builder-last, builder의 `false`/`null`이
  `DryRun` 전송을 해제하거나 생략하는 규칙만 기록하고 서비스 호출 예외를 약속하지 않는다.

- [ ] **Step 2: README examples와 parity**

  기존 `putRecordRequestOf(streamName, data, partitionKey = "default")`의 잘못된 positional
  호출을 named argument로 고친다. 네 operation과 두 helper의 `dryRun = true`, 예외 처리,
  `DryRun:false`와 null omission 차이, positional builder migration, backend capability 표를
  영어·한국어에서 같은 구조와 technical token으로 제공한다.

  `CHANGELOG.md`의 `[미출시]` 아래 `추가`에 Issue #620과 Kinesis DryRun named API,
  `DryRunOperationException` 계약을 한국어로 기록한다.

- [ ] **Step 3: docs 검증**

  Run:

  ```bash
  node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
    CHANGELOG.md \
    aws-kotlin/README.ko.md \
    aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisClientExtensions.kt \
    aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/model/PutRecord.kt \
    aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/model/GetShardIterator.kt
  python3 .github/scripts/kinesis_readme_contract_test.py
  ./gradlew compileAwsKotlinServiceConsumerFixture --no-daemon --no-configuration-cache
  git diff --check
  ```

  그리고 두 README의 heading, operation/helper 이름, code fence, links와 경고 문장 및
  `CHANGELOG.md`의 `[미출시] > 추가` 배치를 명시적으로 read-back한다. Python contract는 두
  README의 Kinesis fenced section에서 잘못된 positional helper 호출이 사라지고 여섯 API,
  builder-last, default `false`, nullable omission, backend capability, 정확한
  `DryRunOperationException`/기타 예외 분기, payload 경고와 migration token이 모두 존재하는지
  검사한다. 또한 네 extension과 두 helper별 KDoc이 위의 서로 다른 책임을 충족하는지,
  `CHANGELOG.md`의 `[미출시] > 추가` 아래에 `#620`, named API,
  `DryRunOperationException`이 함께 있는지 검사한다. Kotlin external consumer compile이 snippet의
  실제 public call shape를 검증한다. 중앙 manual/diagram은 변경하지 않는다.

- [ ] **Step 4: docs checkpoint commit**

  Intent: `#620 DryRun의 성공 예외와 payload 전송 위험을 사용자에게 알린다`. Lore trailers에
  module-only manual ownership, locale parity, terminology 결과를 기록한다.

## Task 7: affected/full 검증과 최종 전달

**Files:**

- Create: `docs/lessons/2026-09-05-issue-620-kinesis-dry-run.md`
- Create: `docs/superpowers/reviews/2026-09-05-issue-620-kinesis-dry-run-final-review.md`
- Modify: implementation/test/docs files only if verification finds a defect

- [ ] **Step 1: targeted와 module test**

  Run sequentially through context-mode:

  ```bash
  ./gradlew :bluetape4k-aws-kotlin:test \
    --tests '*PutRecordTest' --tests '*GetShardIteratorTest' \
    --tests '*KinesisClientExtensionsMockTest' --tests '*KinesisDryRunWireTest' \
    --tests '*KinesisDryRunSupportTest' --tests '*KinesisDryRunEmulatorTest' \
    -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 --no-daemon
  python3 .github/scripts/validate_kinesis_dry_run_capability.py \
    aws-kotlin/build/reports/kinesis-dry-run/capability-floci.json
  ./gradlew :bluetape4k-aws-kotlin:test \
    -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 --no-daemon
  ```

  Expected: targeted GREEN; module baseline의 기존 pending은 수량/이름을 기록하고 새 unexpected
  failure/skip은 0. container failure는 skip 성공으로 취급하지 않는다.

- [ ] **Step 2: static/compatibility/full build**

  exact HEAD를 기록한 뒤 순차 실행한다.

  ```bash
  git rev-parse HEAD
  ./gradlew detekt --no-daemon --no-configuration-cache --max-workers=1 --console=plain
  ./gradlew compatibilityCheck --no-daemon --no-configuration-cache --no-build-cache
  ./gradlew build --no-daemon --no-parallel --max-workers=1
  ```

  Expected: 모두 exit 0. `build/reports/detekt/`, compatibility report, test/JUnit counts와
  resolved catalog를 evidence에 기록한다. catalog가 126개 key를 바꾸므로 affected-module
  compile만으로 full build를 대체하지 않는다.

- [ ] **Step 3: six-lens final review와 lesson**

  latest diff/exact HEAD를 대상으로 performance, stability, security, ops, API, caller review를
  수행하고 P0/P1=0까지 수렴한다. lesson에는 catalog/CI dual pin, nullable Boolean wire shape,
  inline function ABI와 `$default`, emulator ownership cleanup, fake/wire/emulator 증거 계층을
  한국어로 기록한다.

- [ ] **Step 4: 최종 Lore commit와 branch 검증**

  final review/lesson 보정만 stage해 `#620 DryRun 구현의 검증 근거를 남긴다` commit을 만든다.
  `git status --short`, `git log --oneline origin/develop..HEAD`, `git diff --check`가 깨끗하고
  모든 receipt lane이 terminal인지 확인한다.

- [ ] **Step 5: PR 생성과 exact-head CI**

  repo `bluetape4k/bluetape4k-aws`, base `develop`, head
  `feat/issue-620-kinesis-dry-run`으로 Korean PR을 생성한다. body는 issue link, 설계 결정,
  catalog blast radius, tests/skip evidence, risk/rollback과 마지막 `## DoD Status`를 포함한다.
  생성 후 PR head SHA, mergeability, review/thread, checks를 다시 읽고 모든 required/expected
  check가 같은 exact head에서 terminal success인지 확인한다.

  **Stop:** PR이 merge-ready면 `PENDING (merge approval)`로 보고한다. auto-merge, merge,
  branch/worktree 삭제는 실행하지 않는다.

## 전체 DoD

- [ ] spec/plan/risk review가 `P0=0`, `P1=0`이다.
- [ ] catalog settings/CI pin이 exact target SHA이며 Kinesis `1.8.46`이 resolve된다.
- [ ] 네 extension과 두 helper의 default/true/false/null builder-last 계약이 통과한다.
- [ ] fake와 wire proof가 operation당 단일 호출, no-copy, exception identity를 증명한다.
- [ ] emulator capability/no-write와 ownership-safe bounded cleanup이 증명된다.
- [ ] 12개 old direct/`$default` descriptor와 pre-change binary invocation이 통과한다.
- [ ] KDoc와 영어·한국어 module README가 동작·예외·payload·migration을 설명한다.
- [ ] module test, detekt, compatibilityCheck, full build가 exact head에서 성공한다.
- [ ] final six-lens review가 `P0=0`, `P1=0`이고 lesson/Lore commits가 있다.
- [ ] PR exact-head CI가 terminal success이고 mergeability/review/thread read-back이 끝났다.
- [ ] merge는 fresh explicit approval 전 실행하지 않는다.
