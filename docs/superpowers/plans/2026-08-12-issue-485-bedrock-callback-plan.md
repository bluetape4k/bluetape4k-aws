# #485 Bedrock ConverseStream 콜백 조정 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use test-driven-development and execute this plan task-by-task with the Bluetape workflow receipts. 각 단계는 체크박스로 추적한다.

**Goal:** BedrockRuntimeFlowExtensions.kt의 callback monitor를 명시적인 ReentrantLock 경계로 교체하고, callback pending drain과 취소 오류 우선순위를 회귀 테스트로 고정한다.

**Architecture:** AWS SDK callback에서만 사용하는 non-suspending ReentrantLock과 기존 suspend 가능한 Mutex를 분리한다. callback 등록·완료 상태 전이·제거·close snapshot과 coordinator-owned failure accumulator의 mutation/materialization은 하나의 callback lock에서 선형화하되 deferred signal은 lock 밖에서 수행한다. publisher 취소는 pre-handoff의 직접 cancel과 post-handoff의 suspend 가능한 request→join→completion-result 병합 경계를 구분하고, 모든 경로에서 primary/suppressed 정책을 공유한다. generation ID가 없는 exceptionOccurred map은 제거하고 SDK operation future와 StreamAttempt.completion을 오류 원본으로 유지한다.

**Tech Stack:** Kotlin, kotlinx.coroutines channelFlow/Mutex/runTest, AWS SDK v2 SdkPublisher, JUnit 5, Bluetape assertions, Gradle, Detekt.

---

## 범위와 고정된 파일 경계

이번 계획에서 변경 가능한 파일은 다음과 같다.

- Modify: aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt
  - StreamAttempt, StreamCoordinator, private publisher cancellation helper의 내부 동시성 경계만 변경한다.
  - public converseStreamFlow signature, Flow.buffer(0), AWS client 호출 방식은 유지한다.
- Modify: aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensionsTest.kt
  - 취소 실패 primary/suppressed, callback close/drain, 고빈도 replacement 회귀 증거를 추가한다.
  - 기존 20개 테스트와 Bluetape assertions를 유지한다.
- Do not modify unless a test cannot be expressed otherwise: aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/RecordingSdkPublisher.kt
  - 현재 onCancelled callback이 취소 예외를 주입할 수 있으므로 우선 변경하지 않는다.
- Modify: docs/superpowers/specs/2026-08-12-issue-485-bedrock-callback-design.md
  - 사용자 승인 완료 상태와 승인 범위를 기록한다.
- Create: docs/superpowers/plans/2026-08-12-issue-485-bedrock-callback-plan.md
  - 이 구현 계획과 검증 명령을 보존한다.
- Create after implementation: docs/lessons/2026-08-12-issue-485-bedrock-callback.md
  - Type A lesson gate에서 실제 구현·검증 결과를 한국어로 기록한다.
- Create after review: docs/review/2026-08-12-issue-485-bedrock-callback-code-review.md
  - 구현 diff, 테스트, static boundary를 독립적으로 검토한 결과를 기록한다.
- Create before implementation: docs/review/2026-08-12-issue-485-bedrock-callback-plan-review.md
  - 계획의 6관점·통합 review, traceability, SPW-01..05 판정을 기록한다.

다음은 이 이슈에서 변경하지 않는다: dependency catalog/BOM 버전, public API, README, RecordingSdkPublisher의 기존 monitor, AWS SDK lifecycle, external publisher timeout/dispatcher 정책. 단, 현재 resolved `software.amazon.awssdk:bedrockruntime`와 coroutine reactive source를 증거로 확인하는 read-only dependency inspection은 수행한다.

## 선행 증거와 승인

- 설계 review: docs/review/2026-08-12-issue-485-bedrock-callback-review.md, P0=0/P1=0, P2=2(외부 publisher 임의 지연과 구현 후 retention 측정).
- 설계 근거: docs/superpowers/specs/2026-08-12-issue-485-bedrock-callback-design.md.
- AWS callback 계약: https://sdk.amazonaws.com/java/api/2.0.0/software/amazon/awssdk/awscore/eventstream/EventStreamResponseHandler.html.
- baseline: ./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest' --no-build-cache --no-daemon, 20개 통과. 현재 branch가 origin/develop보다 1 commit 뒤이므로 구현 전 fast-forward 가능성과 root build/settings 변경을 확인한다.
- 2026-08-12 사용자 승인: ReentrantLock 채택, handlerFailures 제거, pending callback drain, cancellation primary/suppressed 정책을 포함한 material design 변경의 구현을 승인했다.

---

### Task 1: 워크플로 승인 상태와 계획 검토 입력 고정

Files:
- Modify: .bluetape receipt state through bluetape-flow.py; receipt files are not edited directly.
- Review: docs/superpowers/specs/2026-08-12-issue-485-bedrock-callback-design.md
- Review: docs/review/2026-08-12-issue-485-bedrock-callback-review.md
- Create: docs/review/2026-08-12-issue-485-bedrock-callback-plan-review.md

- [ ] Step 1: 승인 후 상태를 read-only로 확인한다.

Run:

    python3 /Users/debop/.codex/skills/bluetape-workflow/scripts/bluetape-flow.py \
      --state-root .bluetape verify \
      --run-id 20260812T124952Z-bceda7ab
    python3 /Users/debop/.codex/skills/bluetape-workflow/scripts/bluetape-flow.py \
      --state-root .bluetape completion-check \
      --run-id 20260812T124952Z-bceda7ab

Expected: receipt checksum is valid; 구현 lane과 required checks는 아직 미완료로 표시된다. 기존 plan_approved evidence는 Epic/train 승인이고, 이번 사용자 승인은 이 계획 문서와 code review artifact에 기록된 material design 승인으로 구분한다.

- [ ] Step 2: 설계·계획의 파일 경계와 비목표를 대조한다.

    rg -n "ReentrantLock|handlerFailures|pending|suppressed|public API|timeout|DoD" \
      docs/superpowers/specs/2026-08-12-issue-485-bedrock-callback-design.md \
      docs/superpowers/plans/2026-08-12-issue-485-bedrock-callback-plan.md

Expected: lock 분리, map 제거, 동일 lock pending drain, cancellation precedence, external timeout 보류가 모두 계획 작업과 연결된다.

- [ ] Step 3: 계획 review를 실행한다.

Performance, stability, security/resource, operator/ops, developer/API, user/caller의
6개 독립 관점 lane을 각각 read-only로 실행하고, main integration lane에서 findings를
P0/P1/P2/P3로 중복 제거한다. 보안·운영·사용자 관점은 public API/credential/logging 변경이
없는 private coordinator임을 확인하되 N/A 사유와 source evidence를 남긴다. lane이
실행 불가한 관점은 `blocked` 상태와 구체적인 capability/시간 제한을 receipt에 남기고,
main integration은 해당 관점의 독립 evidence를 대체한다고 주장하지 않는다. writer pass는
실제 계약대로 SPW-01(독자·목적·근거·미확정 사항 고정), SPW-02(계획 artifact 구조),
SPW-03(한국어 기술 문체와 용어), SPW-04(소스·설계·계획 traceability),
SPW-05(최종 read-back과 checklist 기록)를 각각 판정한다.

각 reviewer는 최소한 다음 네 가지 질문에 P0/P1/P2/P3와 근거를 붙인다.

1. `cancelOnce()`가 pre-handoff 직접 cancel과 post-handoff request→join→completion-result 경로 모두에서 cleanup outcome을 회수하고, 호출자의 bounded accumulator가 primary를 보존하는가?
2. callback 등록·완료 상태 전이·close snapshot/clear가 같은 `ReentrantLock` 아래 선형화되고 deferred signal은 lock 밖인가?
3. callback lock과 `Mutex`가 어느 방향으로도 중첩되지 않는가?
4. resolved SDK evidence, late handler failure, cancellation/close race, public/Flow/backpressure/client lifecycle 계약이 계획에서 빠지지 않았는가?

계획 review에서 P0/P1이 남으면 구현·baseline fast-forward·spec/plan commit을 진행하지 않고 계획을 수정한다.

- [ ] Step 4: 기존 stalled main lane을 구현 전 유효 lane으로 복구한다.

다음 read-only 상태를 확인한 뒤, workflow script가 제공하는 recovery/replacement
명령으로 `main`의 stalled lineage를 닫고 `issue-485-bedrock-callback` component를
소유하는 새 implementation lane을 만든다. 현재 상태처럼 `main`이 `replaced`이고
`design-resumed`가 terminal이면 먼저 evidence JSON 배열을 준비한 뒤 다음처럼 정확한
CLI 계약으로 실행한다.

실행 직전에 `ISSUE485_EVIDENCE_DIR=$(mktemp -d /tmp/issue-485-evidence.XXXXXX)`를
만들고, 아래 모든 JSON 입력과 commit message 파일을 그 디렉터리에 생성한다. 이
디렉터리는 workflow 입력을 위한 임시 산출물이며 receipt/owner JSONL을 직접 수정하지
않는다.

    OWNER=.bluetape/handles/issue-485-owner.json
    RUN=20260812T124952Z-bceda7ab
    python3 /Users/debop/.codex/skills/bluetape-workflow/scripts/bluetape-flow.py \
      --state-root .bluetape replacement-close --run-id "$RUN" \
      --owner-file "$OWNER" --lane-id main --replacement-lane-id design-resumed \
      --at 2026-08-12T15:00:00Z \
      --evidence "$ISSUE485_EVIDENCE_DIR/replacement-close-evidence.json"

그 다음 `lane-create` 입력 JSON에는 `lane_id`, `agent_id`, `assignment`, `write_scope`,
`fallback`, `observed_at`, `startup_ack_deadline`, `command_deadline`를 모두 포함하고,
다음 명령으로 `implementation-main`(agent `root`, production/test/docs write scope)을
만든다.

    python3 /Users/debop/.codex/skills/bluetape-workflow/scripts/bluetape-flow.py \
      --state-root .bluetape lane-create --run-id "$RUN" --owner-file "$OWNER" \
      --evidence "$ISSUE485_EVIDENCE_DIR/lane-create-evidence.json" \
      --input "$ISSUE485_EVIDENCE_DIR/implementation-main.json"

lane이 생성되면 `lane-start`와 `startup-ack`을 각각 `--lane-id implementation-main
--agent-id root --at <UTC>` 및 `--evidence <JSON>`로 실행한다. 이후 topology JSON 배열의
component에 `owner_lane: implementation-main`과 기존 required checks를 넣어
`topology-register --input "$ISSUE485_EVIDENCE_DIR/issue-485-topology.json"`을 실행한다.
replacement가 incomplete이면 먼저 같은 owner/evidence/UTC 인자로
`replacement-repair`를 수행한 뒤 `replacement-close`를 재시도한다. `receipt-diagnose`가
손상을 보고할 때만 diagnosis JSON checksum을 보존하고 `recovery-run-create --input
`"$ISSUE485_EVIDENCE_DIR/diagnosis.json" --owner-file "$ISSUE485_EVIDENCE_DIR/new-owner.json"`로 새 run을 만든다. receipt JSONL/owner token은
직접 편집하지 않는다.

```bash
python3 /Users/debop/.codex/skills/bluetape-workflow/scripts/bluetape-flow.py \
  --state-root .bluetape resume-check --run-id 20260812T124952Z-bceda7ab
python3 /Users/debop/.codex/skills/bluetape-workflow/scripts/bluetape-flow.py \
  --state-root .bluetape mutation-check --session-id 019febd3-d496-78f2-bb91-f318374e5bb7 \
  --target aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt \
  --target aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensionsTest.kt \
  --target docs/superpowers/specs/2026-08-12-issue-485-bedrock-callback-design.md \
  --target docs/superpowers/plans/2026-08-12-issue-485-bedrock-callback-plan.md \
  --target docs/review/2026-08-12-issue-485-bedrock-callback-review.md \
  --target docs/review/2026-08-12-issue-485-bedrock-callback-plan-review.md
python3 /Users/debop/.codex/skills/bluetape-workflow/scripts/bluetape-flow.py \
  --state-root .bluetape completion-check --run-id 20260812T124952Z-bceda7ab
```

Expected: `main`/replacement lineage가 terminal로 닫히고
`implementation-main` lane이 `running`이며 component owner가 명시된다. recovery run을
만들었다면 새 run ID·lane·component를 plan-review artifact에 기록한다. 유효 lane과
`mutation-check`가 확인되기 전에는 production/test 파일을 수정하지 않는다.

- [ ] Step 5: 구현 전 baseline drift를 확인하고 fast-forward한다.

```bash
git status --short --branch
git fetch origin develop
git diff --stat HEAD..origin/develop
git merge --ff-only origin/develop
git status --short --branch
```

Expected: untracked 설계/계획/review 문서는 보존되고, feature branch가 현재 `origin/develop`을 직접 가리킨다. fast-forward가 불가능하거나 tracked conflict가 생기면 구현을 시작하지 않고 해당 drift를 별도 복구한다.

- [ ] Step 6: 승인된 spec/plan/review를 구현 전에 commit한다.

계획 review가 P0=0/P1=0으로 수렴한 뒤에만 다음을 Lore 형식으로 commit한다.

```bash
git add docs/superpowers/specs/2026-08-12-issue-485-bedrock-callback-design.md \
  docs/superpowers/plans/2026-08-12-issue-485-bedrock-callback-plan.md \
  docs/review/2026-08-12-issue-485-bedrock-callback-review.md \
  docs/review/2026-08-12-issue-485-bedrock-callback-plan-review.md
git commit -F "$ISSUE485_EVIDENCE_DIR/plan-checkpoint-commit-message.txt"
```

`issue-485-plan-checkpoint-commit-message.txt`는 다음 Lore trailers를 포함한다.

    #485 구현 경계를 설계와 계획에 고정한다

    Constraint: AWS callback에는 publisher/generation identity가 없고 public Flow 계약을 유지해야 했다.
    Rejected: callback 상태를 Mutex 하나로 통합 | non-suspending SDK callback에서 suspend와 deadlock 위험이 있다.
    Confidence: high
    Scope-risk: moderate
    Directive: 구현 전 baseline과 workflow lane을 다시 확인하고 coordinator diff를 한 단위로 되돌린다.
    Tested: 문서 diff-check, 계획 6관점 read-only review
    Not-tested: production Kotlin, targeted/full test, CI

Expected: spec/plan/review가 feature branch의 tracked commit에 포함되고, production/test Kotlin 파일은 아직 변경되지 않는다. 이 commit은 구현 전 checkpoint이며 이후 rollback 시 보존한다.

---

### Task 2: 취소 오류 우선순위의 RED 테스트 작성

Files:
- Test: aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensionsTest.kt
- Read: aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/RecordingSdkPublisher.kt

- [ ] Step 1: resolved dependency와 coroutine cleanup source를 기록한다.

```bash
./gradlew :bluetape4k-aws-java:dependencyInsight \
  --dependency software.amazon.awssdk:bedrockruntime \
  --configuration testRuntimeClasspath --no-daemon \
  | tee "$ISSUE485_EVIDENCE_DIR/bedrockruntime-dependency-insight.txt"
./gradlew :bluetape4k-aws-java:dependencyInsight \
  --dependency org.jetbrains.kotlinx:kotlinx-coroutines-reactive \
  --configuration testRuntimeClasspath --no-daemon \
  | tee "$ISSUE485_EVIDENCE_DIR/coroutines-reactive-dependency-insight.txt"
```

Expected: resolved `bedrockruntime`와 `kotlinx-coroutines-reactive` version을 출력으로
보존한다. 각 resolved version의 source/jar 절대 경로와 `shasum -a 256`을
`$ISSUE485_EVIDENCE_DIR/resolved-artifacts.txt`에 기록하고, AWS SDK source에서
`EventStreamAsyncResponseTransformer.exceptionOccurred`가 원래 throwable로
operation/transform future를 실패시키는 구현 줄과, coroutine reactive source에서
`PublisherAsFlow.collectImpl`이 `finally`에서 subscription `cancel()`을 호출하는
구현 줄을 각각 짧은 excerpt로 같은 evidence 디렉터리에 보존한다. 테스트는 이
resolved contract를 재현하는 transformer/handler fixture 또는 실제 transformer 경로를
사용해 `exceptionOccurred(expected)`가 operation future에 동일 instance로 도달하는지
확인해야 하며, 단순히 mock future를 수동 exceptional-complete하는 것만으로 대체하지
않는다. dependency catalog나 version은 변경하지 않는다.

- [ ] Step 2: operation failure가 primary이고 post-handoff publisher cancel failure가 suppressed인지 검증하는 테스트를 추가한다.

테스트는 publisher를 실제로 `asFlow()`에 handoff한 뒤 `RecordingSdkPublisher(onCancelled = { throw cancellationFailure })`, operation `CompletableFuture`의 `completeExceptionally(operationFailure)`, collector의 예외 캡처를 사용한다. `withTimeout(1_000)`으로 terminal await를 감싸고 operation failure identity와 cancellation failure의 suppressed identity를 검증한다. publisher를 재구독해 cancel을 흉내 내지 않는다.

    @Test
    fun operationFailureRemainsPrimaryWhenPostHandoffCancellationFails() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val operationFailure = ValidationException.builder().message("operation").build()
        val cancellationFailure = IllegalStateException("cancel")
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>(
            onCancelled = { throw cancellationFailure },
        )
        val terminal = CompletableDeferred<Throwable>()
        val collector = launch {
            try {
                client.converseStreamFlow(request).toList()
            } catch (cause: Throwable) {
                terminal.complete(cause)
            }
        }
        runCurrent()
        handler.captured.onEventStream(publisher)
        runCurrent()
        future.completeExceptionally(operationFailure)

        val actual = withTimeout(1_000) { terminal.await() }
        actual shouldBeSameInstanceAs operationFailure
        actual.suppressed.toList() shouldBeEqualTo listOf(cancellationFailure)
        collector.join()
    }

- [ ] Step 3: collector CancellationException이 primary이고 post-handoff cleanup failure가 suppressed인지 검증하는 테스트를 추가한다.

collector job에 명시적인 `CancellationException`을 전달해 취소하고 publisher
`onCancelled`에서 `IllegalStateException("cancel")`을 던진다. collector가 관찰한
동일한 CancellationException의 suppressed 목록에 cancel failure가 있는지 확인하고
operation future와 publisher cancel 횟수가 각각 1인지 확인한다. `withTimeout(1_000)`은
terminal await의 종료 보조 수단일 뿐이며 blocking publisher를 선점하거나 latency
상한을 증명하는 데 사용하지 않는다.

추가로 `successfulCancellationHasNoSuppressedCleanupFailure` 테스트에서는 publisher가
정상적으로 cancel될 때 collector의 CancellationException identity가 유지되고
`suppressed`가 비어 있는지 확인한다. 정상 취소 CE를 cleanup failure로 잘못 병합하면
이 테스트가 RED가 된다.

`cancelOnceNormalCancellationDoesNotSuppressDeferredCancellationException` 테스트에서는
`jobReady` 또는 `completion` deferred가 요청 취소로 `CancellationException`을 반환하는
경로를 deterministic fixture로 만들고, 해당 CE가 cleanup failure나 suppressed 원인이
되지 않는지 별도로 확인한다. non-CE subscription cleanup failure만 suppressed 대상이다.

- [ ] Step 4: pre-handoff cancellation failure와 rejected callback failure를 검증한다.

collector가 callback coroutine을 시작하기 전 취소되는 경로와 collector가 닫힌 뒤 late
`onEventStream`을 전달하는 경로를 각각 만든다. pre-handoff publisher의 직접
`cancelImmediately` failure는 해당 callback completion/operation terminal contract에
따라 검증한다. rejected callback은 resolved SDK transformer가 handler 호출을 catch하지
않는다는 source 근거에 맞춰 `assertFailsWith<IllegalStateException>`으로 callback
직접 호출의 thrown identity를 확인하고, operation future가 그 예외를 자동으로 받는다고
주장하지 않는다. callback completion은 `withTimeout(1_000)` 안에 종료되어야 하며 SDK
operation 호출 횟수는 1회다.

- [ ] Step 5: replacement의 두 cancellation failure에서 첫 실패가 primary인지 검증한다.

첫 publisher를 active subscriber까지 handoff하고 replacement callback을 전달한다. 첫 publisher의 onCancelled는 firstCancellationFailure, replacement publisher의 pre-handoff cleanup은 secondCancellationFailure를 던진다. replacement 과정의 callback completion을 모두 await한 뒤 terminal collector에서 first failure identity와 second failure suppressed identity를 확인한다. 동일 Throwable가 cancellation failure로 반복되는 경우에는 self-suppression 없이 primary identity를 유지하는 별도 assertion을 추가한다.

- [ ] Step 6: bounded failure accumulator의 RED 계약을 고정한다.

production 변경 전에 다음 네 가지 bounded 테스트를 추가한다. 테스트는 서로 다른
`IllegalStateException("cancel-$index")` 20개를 replacement publisher의 실제
`onCancelled` 경로로 발생시키고, operation primary의 identity와 suppressed 내용을
관찰한다. 이 테스트의 구조는 private accumulator를 직접 노출하지 않고 public Flow의
terminal 예외에서 bounded 결과를 검증한다.

- `boundedCancellationFailuresRetainBoundedSamplesAndOverflowCount`: operation failure를
  primary로 고정하고 20개의 distinct cleanup failure를 순서대로 전달한다. 첫 16개
  failure identity가 순서대로 suppressed되고, stackless overflow marker는 정확히 하나이며
  message의 `dropped=4`를 확인한다. primary 자체는 suppressed에 포함되지 않는다.
- `repeatedCancellationFailureDoesNotDuplicateRetainedThrowable`: 첫 failure를 같은
  Throwable instance로 여러 번 반복하고, normal `CancellationException`도 섞는다. 동일
  instance는 suppressed에 한 번만 나타나고 CE는 sample/overflow count 어느 쪽에도 포함되지
  않음을 확인한다.
- `boundedFailureAccumulatorMaterializesOverflowOnce`: 동일 operation에서 terminal
  cancellation과 outer `finally` cleanup 경합을 만들고, overflow marker가 한 번만
  materialize되며 두 번째 materialization이 suppressed 목록을 늘리지 않는지 확인한다.
- `completedCallbackFailureIsClearedAfterClose`: callback failure를 close snapshot으로
  소비한 뒤 late callback이 즉시 reject/cancel되고, 이전 failure/Throwable root가 다음
  terminal 결과로 재사용되지 않으며 pending map이 비어 있음을 observable cancel count와
  terminal identity로 확인한다.

`MAX_RETAINED_SUPPRESSED_FAILURES`는 16, overflow count는 `Long` saturating add,
retained primary/sample identity dedupe는 최대 17개 root reference라는 invariant를
테스트 설명과 production KDoc에 고정한다. dropped failure 자체의 identity는 저장하지
않는다.

- [ ] Step 7: 현재 handler failure 전달과 late old-generation handler callback을 RED 테스트로 고정한다.

서로 다른 두 계약을 별도 테스트로 유지한다. (1) Step 1에서 보존한 resolved AWS
SDK transformer/handler fixture 또는 실제 transformer 경로를 통해
`exceptionOccurred(expected)`를 호출하고, transformer가 operation/transform future를
동일 `expected` instance로 실패시키는지 확인한다. 이 테스트에서는 mock future를
수동으로 `completeExceptionally`하지 않는다. (2) coordinator-only 테스트에서는
replacement publisher가 active가 된 뒤 handler에
`exceptionOccurred(lateOldGenerationFailure)`를 직접 전달하고 replacement publisher를
정상 완료한 다음 operation future를 성공 완료한다. 이 두 번째 테스트는 SDK 전파를
주장하지 않고, generation map 제거 후 늦은 callback이 replacement 성공 결과를
오염시키지 않는지만 확인한다.

- [ ] Step 8: 모든 신규 테스트만 실행해 RED를 확인한다.

    ./gradlew :bluetape4k-aws-java:test \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.operationFailureRemainsPrimaryWhenPostHandoffCancellationFails' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.collectorCancellationPreservesPrimaryWhenPostHandoffCancellationFails' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.successfulCancellationHasNoSuppressedCleanupFailure' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.cancelOnceNormalCancellationDoesNotSuppressDeferredCancellationException' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.outerFinallyDoesNotDuplicateCancellationSuppression' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.rejectedCallbackReportsPreHandoffCancellationFailure' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.replacementCancellationFailuresPreserveFirstFailure' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.boundedCancellationFailuresRetainBoundedSamplesAndOverflowCount' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.repeatedCancellationFailureDoesNotDuplicateRetainedThrowable' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.boundedFailureAccumulatorMaterializesOverflowOnce' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.completedCallbackFailureIsClearedAfterClose' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.currentHandlerFailureUsesOperationFutureCause' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.lateOldGenerationHandlerFailureDoesNotContaminateReplacementSuccess' \
      --no-build-cache --no-daemon

Expected: current 구현은 post-handoff cleanup failure를 bounded accumulator로 병합하지
않고 pending callback drain/handler authority 증거가 부족하므로 하나 이상의 테스트가
실패한다. 테스트가 무기한 대기하면 bounded timeout 결과로 분류하고 구현으로 넘어가지
않는다.

- [ ] Step 9: callback drain RED 테스트를 production 변경 전에 작성하고 실패를 확인한다.

Task 3 production 변경 전에 다음 다섯 테스트를 정확한 이름으로 추가한다.

- `acceptedCallbackIsDrainedWhenOperationFailsBeforeHandoff`
- `closeDrainsSuspendedCallbackCompletion`
- `lateCallbackIsRejectedAfterClose`
- `highVolumeReplacementDrainsCompletedCallbacks`
- `concurrentReplacementCleanupAndTerminalFailureIsSerialized`

모든 terminal/deferred await는 `withTimeout(1_000)`으로 감싸며 `repeat(100)`은
heap/retention 수치가 아니라 deterministic lifecycle/output 증거로만 해석한다. 마지막
race 테스트는 `Dispatchers.Default.limitedParallelism(2)` 또는 동등한 실제 JVM
멀티스레드 barrier를 사용해 replacement callback cleanup과 terminal failure를 동시에
진행시키고, `ConcurrentModificationException`/lost primary 없이 operation primary identity,
suppressed identity dedupe, overflow count를 확인한다. 단일 `runTest` scheduler만으로
동시성 안전을 주장하지 않는다. 다음
명령을 Task 3 production 변경 전에 실행한다.

    ./gradlew :bluetape4k-aws-java:test \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.acceptedCallbackIsDrainedWhenOperationFailsBeforeHandoff' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.closeDrainsSuspendedCallbackCompletion' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.lateCallbackIsRejectedAfterClose' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.highVolumeReplacementDrainsCompletedCallbacks' \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest.concurrentReplacementCleanupAndTerminalFailureIsSerialized' \
      --no-build-cache --no-daemon

Expected: 현재 production 구현은 callback pending clear/drain, accepted-before-handoff
operation failure handoff, lock 밖 signal, shared accumulator serialization 계약을 충족하지
않으므로 하나 이상의 테스트가
RED가 된다. 다섯 테스트가 모두 통과하거나 이름/경로가 해석되지 않으면 구현하지 않고
테스트 계약을 먼저 수정한다. 이 RED 증거와 Task 2 Step 8 결과를 보존한 뒤에만 Task 3
production 변경으로 이동한다.

---

### Task 3: callback lock과 pending callback drain을 최소 diff로 구현

Files:
- Modify: aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt

- [ ] Step 1: non-suspending lock import와 callback state를 교체한다.

기존 callbackLock: Any를 다음처럼 바꾸고, 이미 존재하는 coroutine withLock과 충돌하지 않도록 alias를 사용한다.

    import java.util.concurrent.locks.ReentrantLock
    import java.util.Collections
    import java.util.IdentityHashMap
    import kotlin.concurrent.withLock as withReentrantLock

    private val callbackLock = ReentrantLock()

handlerFailures map은 삭제한다. Mutex는 suspend 가능한 stream state 전용으로 남긴다.
pending callback 저장소는 `LinkedHashMap<Long, CallbackCompletion>`으로 둔다. sequence를
key로 사용해 callback logical completion의 제거를 O(1)로 만들고, `values.toList()`가
등록 순서 snapshot을 유지하게 한다. map은 callback lock 밖에서 직접 읽거나 수정하지
않는다.

- [ ] Step 2: callback completion의 logical state와 deferred signal을 분리한다.

lock 안에서는 callback completion을 logical state로 표시하고, close가 아직 snapshot을
소유하지 않은 항목은 pending 목록에서 제거하면서 failure를 operation-level accumulator에
병합한다. close가 먼저 snapshot을 소유한 항목은 snapshot에 남겨 deferred 결과로 drain한다.
어느 경로든 `CompletableDeferred.complete()`와 Job completion handler는 callback lock
밖에서만 실행하며, 같은 항목의 중복 logical completion은 lock 안에서 무시한다.

replaceFromCallback의 finally와 시작 전 취소 invokeOnCompletion은 이 protocol을 사용한다. callback lock 밖에서는 publisher cancel이나 coroutine launch를 수행한다.
기존 `callbackStarted` 판정은 유지해 body가 시작된 callback은 `finally`가 한 번만
completion을 signal하고, 시작되지 않은 launch만 completion handler가 pre-handoff cancel
결과를 signal한다.

- [ ] Step 3: callback acceptance와 close snapshot/clear를 lock 경계로 바꾼다.

replaceFromCallback의 acceptance block은 callbackLock.withReentrantLock으로 바꾸고, closeCallbacks는 다음 선형화 규칙을 지킨다.
acceptance가 성공하면 `CallbackCompletion(sequence, ...)`을
`callbackCompletions[sequence]`에 등록하고, callback completion이 먼저 논리 완료되면
동일 sequence key를 제거한다.

    private data class CallbackCompletion(
        val sequence: Long,
        val result: CompletableDeferred<Throwable?> = CompletableDeferred(),
        var logicallyCompleted: Boolean = false,
        var drainClaimed: Boolean = false,
    )

    private data class CallbackDrain(
        val pending: List<CallbackCompletion>,
        val completedFailure: FailureSnapshot,
    )

    // File-level private constants; declare these at file scope before StreamCoordinator.
    private const val MAX_RETAINED_SUPPRESSED_FAILURES = 16
    private const val MAX_OVERFLOW_COUNT = Long.MAX_VALUE

    private data class FailureSnapshot(
        val primary: Throwable?,
        val suppressed: List<Throwable>,
        val overflowCount: Long,
    )

    // Memory-only methods below require the coordinator callbackLock. A rejection-local
    // instance is safe only while its single owner is using it. The class deliberately
    // does not acquire another lock, so callbackLock and Mutex are never nested in either
    // direction.
    private class BoundedFailureAccumulator(initialPrimary: Throwable? = null) {
        private var primary: Throwable? = initialPrimary
        private val suppressed = ArrayList<Throwable>(MAX_RETAINED_SUPPRESSED_FAILURES)
        private var overflowCount = 0L
        private val retainedIdentities =
            Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        private var materialized: Throwable? = null

        init {
            initialPrimary?.let(retainedIdentities::add)
        }

        fun record(failure: Throwable?) {
            check(materialized == null) { "failure accumulator is already materialized" }
            if (failure == null || failure is CancellationException || retainedIdentities.contains(failure)) return
            if (primary == null) {
                primary = failure
                retainedIdentities += failure
            } else if (suppressed.size < MAX_RETAINED_SUPPRESSED_FAILURES) {
                suppressed += failure
                retainedIdentities += failure
            } else {
                overflowCount = saturatingAdd(overflowCount, 1)
            }
        }

        // Authoritative operation/publisher/collector cause. Unlike cleanup record(), this
        // method accepts CancellationException and may promote a retained sample.
        fun selectPrimary(authoritative: Throwable?) {
            check(materialized == null) { "failure accumulator is already materialized" }
            if (authoritative == null) return
            if (primary === authoritative) return
            val existingIndex = suppressed.indexOfFirst { it === authoritative }
            if (primary == null) {
                primary = authoritative
                retainedIdentities += authoritative
            } else {
                val previous = primary
                if (existingIndex >= 0) {
                    suppressed.removeAt(existingIndex)
                    retainedIdentities.remove(authoritative)
                }
                primary = authoritative
                retainedIdentities += authoritative
                if (previous != null) {
                    suppressed.add(0, previous)
                    if (suppressed.size <= MAX_RETAINED_SUPPRESSED_FAILURES) {
                        retainedIdentities += previous
                    } else {
                        val dropped = suppressed.removeAt(suppressed.lastIndex)
                        retainedIdentities.remove(dropped)
                        overflowCount = saturatingAdd(overflowCount, 1)
                    }
                }
            }
        }

        fun merge(snapshot: FailureSnapshot) {
            record(snapshot.primary)
            snapshot.suppressed.forEach(::record)
            overflowCount = saturatingAdd(overflowCount, snapshot.overflowCount)
        }

        fun snapshotAndClear(): FailureSnapshot =
            FailureSnapshot(primary, suppressed.toList(), overflowCount).also {
                primary = null
                suppressed.clear()
                retainedIdentities.clear()
                overflowCount = 0
                materialized = null
            }

        fun throwable(): Throwable? {
            materialized?.let { return it }
            val current = primary ?: return null
            suppressed.forEach { failure -> current.addSuppressed(failure) }
            if (overflowCount > 0) current.addSuppressed(SuppressedFailureOverflow(overflowCount))
            return current.also { materialized = it }
        }

        private fun saturatingAdd(current: Long, increment: Long): Long =
            if (increment > MAX_OVERFLOW_COUNT - current) MAX_OVERFLOW_COUNT else current + increment
    }

    private class SuppressedFailureOverflow(count: Long) :
        RuntimeException("suppressed failure count exceeded bound; dropped=$count") {
        override fun fillInStackTrace(): Throwable = this
    }

    // Both coordinator-owned accumulators are accessed only while callbackLock is held.
    // BoundedFailureAccumulator itself does not acquire a second lock; rejection-local
    // instances have a single caller/owner. No suspend, await, join, or external call is
    // performed while callbackLock is held.
    private val completedCallbackFailures = BoundedFailureAccumulator()
    // One accumulator belongs to one cold Flow collection and is shared by every terminal
    // path, replacement cleanup, callback drain, and outer finally.
    private val operationFailures = BoundedFailureAccumulator()

    private fun recordOperationFailure(failure: Throwable?) {
        callbackLock.withReentrantLock { operationFailures.record(failure) }
    }

    private fun selectOperationPrimary(failure: Throwable?) {
        callbackLock.withReentrantLock { operationFailures.selectPrimary(failure) }
    }

    private fun mergeOperationFailures(snapshot: FailureSnapshot) {
        callbackLock.withReentrantLock { operationFailures.merge(snapshot) }
    }

    // StreamCoordinator is private, so this non-private member is callable by the
    // file-local public Flow extension without widening the external API.
    fun materializeOperationFailure(): Throwable? =
        callbackLock.withReentrantLock { operationFailures.throwable() }

    private fun completeCallback(callback: CallbackCompletion, failure: Throwable?) {
        val shouldSignal = callbackLock.withReentrantLock {
            if (callback.logicallyCompleted) {
                false
            } else {
                callback.logicallyCompleted = true
                if (callback.drainClaimed) {
                    true
                } else {
                    callbackCompletions.remove(callback.sequence)
                    completedCallbackFailures.record(failure)
                    true
                }
            }
        }
        if (shouldSignal) callback.result.complete(failure)
    }

    private fun closeCallbacks(): CallbackDrain = callbackLock.withReentrantLock {
        acceptingCallbacks = false
        val pending = callbackCompletions.values.toList()
        pending.forEach { it.drainClaimed = true }
        callbackCompletions.clear()
        CallbackDrain(pending, completedCallbackFailures.snapshotAndClear())
    }

callback 등록·logical completion 표시·pending removal·close snapshot/clear는 같은
`ReentrantLock`에서 수행한다. callback이 먼저 완료되면 failure를 단일 bounded
operation-level `completedCallbackFailures`에 병합하고 항목을 제거한다. close가 먼저 snapshot을 소유하면
`drainClaimed`를 표시하고 deferred를 snapshot에 남긴다. 어느 경로든
`CompletableDeferred.complete(result)`는 lock 밖에서만 호출하며, snapshot await도
함수 밖의 suspend 경로에서만 실행한다. 따라서 callback이 lock 밖에서 signal하는 순간과
close snapshot 사이에 결과가 사라지지 않는다. `futureSucceeded`·`futureFailed`·collector
cancellation은 `CallbackDrain.completedFailure` snapshot과 각 pending result를 모두
await한 뒤 `callbackLock`으로 직렬화된 coordinator-owned `operationFailures`에 병합한다.
pending snapshot의 suppression 순서는 callback 등록 sequence 순서로 await해 실행 thread
순서에 의존하지 않게 한다. replacement cleanup의 typed outcome도 `Mutex` 밖에서
`recordOperationFailure` 경계를 통해 같은 짧은 lock으로 직렬화하며, 두 경로의 concurrent
mutation은 직접 accumulator를 호출하지 않는다.

- [ ] Step 4: handler failure map 경로를 제거한다.

handlerFailureFromCallback은 삭제하거나 Unit을 반환하는 private no-op으로 축소하고, handler의 exceptionOccurred는 callback generation map을 갱신하지 않는다. futureSucceeded는 `StreamAttempt.completion`의 `Failed(cause)` outcome만 publisher 오류 원본으로 사용하고, `Cancelled` outcome의 cleanup failure는 cancellation accumulator로만 사용한다.

- [ ] Step 5: lock 경계 static check와 컴파일을 실행한다.

    rg -n "synchronized\(callbackLock\)|handlerFailures|callbackLock.*Mutex|Mutex.*callbackLock" \
      aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt
    ./gradlew :bluetape4k-aws-java:compileKotlin --no-build-cache --no-daemon

Expected: rg는 출력이 없고 compileKotlin은 성공한다. withReentrantLock 블록에는 await, join, emit, collect, cancelImmediately, AWS client call이 없어야 한다. Task 2 Step 9의 RED 증거가 먼저 존재해야 하며, 이 단계는 production 변경 후 경계를 확인한다.

---

### Task 4: 단일 cancellation boundary와 primary/suppressed 정책 구현

Files:
- Modify: aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt

- [ ] Step 1: raw publisher cancellation 결과를 호출자-owned bounded accumulator로
  전달하는 helper를 정의한다.

    private fun <T : Any> SdkPublisher<T>.cancelImmediately(): Throwable? =
        try {
            subscribe(
                object : Subscriber<T> {
                    override fun onSubscribe(subscription: Subscription) = subscription.cancel()
                    override fun onNext(item: T) = Unit
                    override fun onError(throwable: Throwable) = Unit
                    override fun onComplete() = Unit
                },
            )
            null
        } catch (failure: Throwable) {
            failure
        }

성공 시 반환값은 `null`이며 호출자 accumulator는 변경되지 않는다. 반환된 첫 non-CE
failure를 호출자가 기록하면 primary가 되고 이후
`MAX_RETAINED_SUPPRESSED_FAILURES`개의 failure만 suppressed sample로 보존되며 초과분은
count만 증가한다. terminal 경계에서 `throwable()`을 한 번 호출해 sample과 단일
`SuppressedFailureOverflow` marker를 primary에 materialize한다. 정상
`CancellationException`은 `record`에서 제외한다. 기존 private extension의 subscriber
동작과 request semantics는 그대로 둔다.

- [ ] Step 2: StreamAttempt.cancelOnce()가 pre/post-handoff attempt cancel outcome을 모두 관찰하게 한다.

`StreamAttempt`가 `attempt`에 등록된 뒤의 취소는 항상 post-handoff로 취급하고,
등록 전 `replace`/rejection의 local publisher만 직접 cancel한다. 따라서 attempt에는
다음과 같은 공유 결과 경계를 둔다. 구현에서는 이 상태와 반환 의미를 그대로
보존한다. `cancelActiveAttempt()`는 `Mutex`에서
현재 attempt를 한 번만 분리/claim하고 `current = null`을 선형화한 뒤 lock 밖에서 이
경계를 호출한다. terminal 함수와 outer `finally`가 같은 attempt를 동시에 정리해도
실제 `cancelOnce`와 `recordOperationFailure` 기록은 claim owner 한 곳에서만 수행한다.

    import kotlinx.coroutines.NonCancellable
    import kotlinx.coroutines.withContext

    private val cancellationStarted = AtomicBoolean()
    private val cancellationResult = CompletableDeferred<Throwable?>()

    suspend fun cancelOnce(): Throwable? {
        if (cancellationStarted.compareAndSet(false, true)) {
            val failure = withContext(NonCancellable) {
                try {
                    val job = jobReady.await()
                    job.cancel()
                    job.join()
                    when (val outcome = completion.await()) {
                        is AttemptCompletion.Cancelled -> outcome.cleanupFailure
                        AttemptCompletion.Succeeded,
                        is AttemptCompletion.Failed -> null
                    }
                } catch (_: CancellationException) {
                    null
                } catch (cause: Throwable) {
                    cause
                }
            }
            cancellationResult.complete(failure)
        }
        return withContext(NonCancellable) { cancellationResult.await() }
    }

`completion`은 단순 `Result<Unit>`가 아니라 정상 종료, 일반 실패, 취소 cleanup을
구분하는 private outcome으로 보관한다.

    private sealed interface AttemptCompletion {
        data object Succeeded : AttemptCompletion
        data class Failed(val cause: Throwable) : AttemptCompletion
        data class Cancelled(val cleanupFailure: Throwable?) : AttemptCompletion
    }

`CancellationException` 자체는 정상 취소 신호이므로 `cancelOnce`의 cleanup failure로
병합하지 않는다. cancellation `catch`에서는 취소를 요청한 Job의 정상적인 CE를
`Cancelled(null)`로 기록하고, publisher `cancel()`이 별도로 던진 non-CE만
`Cancelled.cleanupFailure`로 기록한다. 일반 예외는 `Failed`로 기록한다.
`completion`은 launch가 취소·실패하거나 body가 수집 전에 종료되는
경우에도 반드시 완료된다(early-cancel은 `Cancelled(null)` 또는 명시적 cleanup failure로
종료). 첫 호출이 request→join→completion-result를
수행하는 동안 후속 호출은 `AtomicBoolean`만 보고 반환하지 않고 같은
`cancellationResult`를 await한다. `completion.await()`가 `Cancelled(cleanupFailure)`를
반환할 때만 cleanup failure를 호출자 accumulator에 기록하고, `Succeeded`/`Failed` 및
`Cancelled(null)`은 cancellation failure로 취급하지 않는다. `jobReady.await()`/`join()`
자체의 non-CE 예외만 cancellation failure로 정규화한다. pre-handoff direct cancel은
`StreamAttempt` 밖의 callback completion이 `cancelImmediately()` 반환값을 callback-local
bounded accumulator에 기록한다. publisher
`asFlow().collect`의 정상 종료, 일반 예외, CancellationException 및 cancellation
`finally`에서 발생한 subscription failure는 위 outcome으로 구분해 반환하고,
early return/launch failure도 `completion`을 완료해 `cancelOnce`가 영원히 대기하지
않게 한다.

정상 post-handoff cancellation의 RED/GREEN acceptance는 collector primary가
CancellationException이고 `suppressed`가 비어 있는지 확인하는 것이다. publisher
`cancel()`이 non-CE를 던질 때만 같은 primary에 해당 throwable이 suppressed된다.
`outerFinallyDoesNotDuplicateCancellationSuppression` 회귀 테스트는 terminal cancellation
경로와 outer `finally`가 동일 active attempt를 각각 정리하려는 경합을 만들고, publisher
cancel failure가 primary의 `suppressed`에 정확히 한 번만 들어가는지 확인한다.

호출자는 outer `finally`에서 `materializeOperationFailure()`를 한 번 호출해 원래
primary를 다시 던지거나, primary가 없을 때만 cleanup failure를 표면화한다. 이 suspend
경계는 callback lock과 `Mutex` 밖에서만 호출하며, callback lock 안에서는 await/join을
수행하지 않는다.

- [ ] Step 3: replacement과 rejected callback 호출자가 반환된 failure를 처리하게 한다.

replacement의 이전 attempt 취소는 operationFailures에 결과를 기록하는 컴파일 가능한
명시적 경계로 처리한다.

    previous?.cancelOnce()?.let(::recordOperationFailure)

handoff가 완료되지 않은 publisher의 finally는 현재 `operationFailures`와 별개의
rejection-local accumulator를 보존하고 pre-handoff
`publisher.cancelImmediately()?.let(rejectionAccumulator::record)` 결과를 callback
completion에 기록한다. acceptance 거부 경로는 completion을 등록하지 않으므로 lock 밖에서
같은 반환값을 rejection accumulator에 기록한 뒤 failure를 조용히 버리지 않고 callback
호출자에게 동기적으로 다시 던진다. 즉 rejection 전용 accumulator를 materialize한 뒤
`rejectionAccumulator.throwable()?.let { throw it }`로 callback boundary에 재전파한다.
resolved SDK transformer source는 `onStream`의
handler 호출을 catch하지 않으므로 이 synchronous rejection failure가 operation future로
전달된다고 주장하지 않는다. 별도 테스트는 callback 직접 호출의 thrown identity와
operation future가 독립적으로 pending/terminal인 경계를 확인한다. 이미 선택된
operation/collector primary가 있으면 해당 primary가 유지되고 rejection failure는
suppressed 정책을 따른다.

- [ ] Step 4: operation failure, collector cancellation, outer finally에 primary를 전달한다.

`StreamCoordinator`는 cold Flow collection 하나마다 `operationFailures` 하나를 소유하며,
callback drain, replacement cleanup, terminal 함수, outer `finally`가 이 동일 accumulator를
공유한다. accumulator의 모든 접근은 `callbackLock`이 제공하는 짧은 non-suspending
경계로 직렬화한다. callback coroutine은 accumulator를 직접 호출하지 않고
`recordOperationFailure`를 사용하며, terminal/outer 경로도 동일 경계를 사용한다. 따라서
`ArrayList`, identity set, primary/overflow 상태를 concurrent하게 읽거나 쓰지 않는다.
terminal 함수는 callback close snapshot과 terminal/attempt claim을 선형화하고, 모든
await가 끝날 때까지 materialize하지 않는다. `futureSucceeded()`는 먼저 현재 attempt와
terminal 상태를 선형화하고 callback close snapshot을 소유한 뒤, 두 lock을 놓고 현재
publisher `completion`을 await한다. `AttemptCompletion.Failed(cause)`일 때만
`selectOperationPrimary(cause)`로 publisher primary를 선택하며,
`AttemptCompletion.Succeeded`/`Cancelled`는 primary를 추가하지 않는다. 그 다음 pending
callback result를 등록 sequence 순서로 await하고 bounded failure를 병합한다.
`futureFailed(cause)`와 `cancel(cause: CancellationException)`는 callback snapshot을
병합하기 전에 각각 operation/collector 원인을
`selectOperationPrimary(cause)`로 고정한다. active attempt의 cancellation은
이 세 함수와 outer `finally`가 중복 호출하지 않도록 `cancelActiveAttempt()`가 현재
attempt를 한 번만 claim한 뒤 lock 밖에서 `cancelOnce()`를 호출하고 결과를
`recordOperationFailure(...)`하는 단일 경계로 둔다. 따라서 동일 attempt의 cleanup
failure를 같은 primary에 두 번 기록하지 않는다.
`futureFailed(cause)`와 `cancel(cause)`의 순서는 (1) 이미 알려진 authoritative primary
선택, (2) callback close snapshot과 terminal/attempt claim, (3) lock 밖에서 pending
callback result await, (4) `mergeOperationFailures(...)`, (5) return이다. `futureSucceeded()`는
앞서 선형화한 callback snapshot/terminal claim을 유지한 채 publisher completion을 lock
밖에서 await하고, `AttemptCompletion.Failed(cause)`일 때만 publisher primary를 선택한
뒤 pending result를 등록 sequence 순서로 await하고 merge한다. 어느 terminal 함수도
`materializeOperationFailure()`를 호출하지 않는다.
세 terminal 함수는 active attempt를 직접 취소하지 않고 terminal/close와 drain 결과만
확정하며, 현재 attempt의 단일 cancellation owner는 outer `finally`다. replacement는
새 attempt를 current로 publish하기 전에 이전 attempt를 한 번만 claim해 취소한다.
이미 수락된 callback이 `Mutex`를 필요로 해도 terminal 함수가 그 lock을 잡은 채 기다리지
않는다. collector cancellation 경로의 snapshot await와 attempt cleanup은
`withContext(NonCancellable)`로 실행해 이미 취소된 caller가 drain을 중단하지 않게 한다.
이 세 함수와 outer `finally`는 cleanup failure가 기존 primary를 대체하도록 throw하지
않으며, primary가 없는 경우에만 단일 cancellation owner가 cleanup failure를 표면화한다.
public flow의 catch/finally는 다음 순서를 지킨다.

    try {
        operation = converseStream(request, handler)
        operation.await()
        coordinator.futureSucceeded()
    } catch (ce: CancellationException) {
        coordinator.cancel(ce)
    } catch (cause: Throwable) {
        coordinator.futureFailed(cause)
    } finally {
        coordinator.cancelActiveAttempt()
        coordinator.materializeOperationFailure()?.let { failure -> throw failure }
    }

`cancelActiveAttempt()`는 terminal 함수가 이미 attempt를 claim했다면 같은 attempt를 다시
취소하지 않고, cleanup failure는 operationFailures에 한 번만 기록한다.
`materializeOperationFailure()`는 `cancelActiveAttempt()`와 모든 callback drain이 완료된
뒤 이 outer `finally`에서 한 번만 `operationFailures.throwable()`을 호출한다. terminal
함수는 materialize하지 않는다. primary가 없을 때 cleanup failure를 throw하고, primary가
있을 때는 같은 primary에 bounded sample/overflow marker를 붙인 뒤 그 primary를 한 번
재전파한다. catch에서 직접 throw하지 않는 이유는 이 단일 owner가 operation/collector
primary와 마지막 cleanup failure를 모두 합친 뒤 정확히 한 번 표면화해야 하기 때문이다.

- [ ] Step 5: RED 테스트를 다시 실행해 cancellation contract를 GREEN으로 만든다.

    ./gradlew :bluetape4k-aws-java:test \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest' \
      --no-build-cache --no-daemon

Expected: 신규 취소 오류 테스트와 기존 20개 테스트가 모두 통과한다. 실패하면 먼저 primary identity, suppressed 순서, post-handoff completion result를 확인하고, 예외를 조용히 삼키는 catch는 추가하지 않는다.

---

### Task 5: callback drain·replacement·backpressure GREEN 증거 확인

Files:
- Test: aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensionsTest.kt

- [ ] Step 1: Task 2 Step 9의 close 이후 late callback RED 테스트를 GREEN으로 확인한다.

collector를 취소해 coordinator close를 완료한 뒤 publisher callback을 전달한다. callback coroutine이 생성되지 않고 publisher cancel count가 1이며, callback completion이 withTimeout(1_000) 안에 반환되는지 확인한다. timeout은 정상 종료를 제한할 뿐 blocking publisher의 latency를 선점하는 증거로 사용하지 않는다.

- [ ] Step 2: Task 2 Step 9의 accepted-before-handoff operation failure race와 suspended callback drain 테스트를 GREEN으로 확인한다.

callback이 acceptance를 통과한 직후 scheduler를 멈춘 상태에서 operation failure를 전달하고, callback launch가 끝난 뒤 publisher cancel completion이 primary에 suppressed되는지 확인한다. collector가 suspend 중인 동안 replacement를 전달하고 close snapshot이 모든 callback completion을 await하는지 `withTimeout(1_000)`으로 확인한다.

- [ ] Step 3: Task 2 Step 9의 high-volume deterministic replacement drain 테스트를 GREEN으로 확인한다.

repeat(100)으로 callback publisher를 전달하고 runCurrent()로 scheduler를 전진시킨다. 각 이전 publisher가 한 번만 취소되고 마지막 publisher만 event를 전달하도록 한 뒤 마지막 publisher completion과 operation future success를 완료한다. collector가 withTimeout(1_000) 안에 종료되고 결과가 마지막 generation event 하나인지 확인한다. 이 테스트의 acceptance는 deterministic lifecycle/output과 source-level pending removal로 한정하며 실제 heap/retention 수치를 주장하지 않는다. pending count를 관찰할 수 있는 private test hook을 추가하지 않는 경우 retention은 후속 P2로 남긴다.

- [ ] Step 4: 기존 backpressure와 terminal ordering 테스트를 변경 없이 통과시킨다.

다음 테스트가 그대로 통과하는지 확인한다: slow collector preserves order with one outstanding request, future success waits for latest publisher terminal and preserves publisher failure, publisher completion waits for operation future success, replacement activates while previous event collector is suspended.

- [ ] Step 5: 테스트 helper 변경 필요성을 재평가한다.

RecordingSdkPublisher의 onCancelled 주입만으로 throwing cancellation과 deterministic replacement를 모두 표현할 수 있으면 helper를 수정하지 않는다. 추가 상태가 반드시 필요할 때만 helper에 최소한의 read-only counter를 추가하고, helper의 기존 synchronized(lock)은 issue #485 production callback lock 변경 대상이 아님을 review에 기록한다.

---

### Task 6: 정적 경계·Kotlin 패턴·문서 consistency 검증

Files:
- Review: aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt
- Review: aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensionsTest.kt
- Modify after implementation: docs/review/2026-08-12-issue-485-bedrock-callback-code-review.md

- [ ] Step 1: callback monitor와 stale map이 제거됐는지 확인한다.

    test -z "$(rg -n "synchronized\(callbackLock\)|handlerFailures" \
      aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt)"
    rg -n "ReentrantLock|withReentrantLock|callbackCompletions\.clear|cancelOnce\(" \
      aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt

Expected: 첫 command는 성공하고, 두 번째 command는 명시적인 lock, pending clear, cancellation boundary를 찾는다.

- [ ] Step 2: lock 내부에 금지된 suspension/외부 호출이 없는지 source review한다.

withReentrantLock 각 블록의 본문을 읽어 await, join, emit, collect, Mutex.withLock, publisher.cancelImmediately, AWS SDK call이 포함되지 않았음을 확인한다. Mutex.withLock 블록도 callback lock을 획득하지 않는지 역방향을 확인한다.

- [ ] Step 3: Kotlin pattern과 API 호환성을 확인한다.

다음을 확인한다.

- nullable primary는 Throwable?로 전달하고 불필요한 !!를 사용하지 않는다.
- mutable callback list와 generation state는 coordinator private scope에 남긴다.
- CancellationException은 먼저 catch하고 다시 던진다.
- collector cancellation cleanup은 `withContext(NonCancellable)`에서만 drain/join하며,
  callback lock 안에는 NonCancellable 또는 다른 suspend 호출을 넣지 않는다.
- public extension signature와 KDoc의 client ownership/backpressure 문장을 바꾸지 않는다.
- 새 dependency, catalog entry, logging, blocking runBlocking을 추가하지 않는다.

- [ ] Step 4: 구현 code review artifact를 작성한다.

docs/review/2026-08-12-issue-485-bedrock-callback-code-review.md에 source diff, 테스트 목록, static check, remaining P2(external publisher latency와 실제 memory 수치), P0/P1 판정을 한국어로 기록한다. review artifact에는 설계 review의 승인 범위를 넘어선 API/dependency 변경이 없음을 명시한다.

---

### Task 7: rollback 및 재실행 checkpoint 고정

Files:
- Review: git history and workflow receipts
- Preserve: Task 2/3 RED test evidence and pre-implementation spec/plan/review commit

- [ ] Step 1: coordinator 변경을 한 단위로 rollback할 절차를 기록한다.

취소 boundary와 callback drain은 서로 독립적으로 되돌릴 수 없는 하나의 production
coordinator 변경으로 취급한다. implementation commit의 exact SHA와 production path를
먼저 기록하고, rollback이 필요하면 clean worktree인지 `git status --short`로 확인한 뒤
해당 coordinator commit만 `git revert <implementation-commit-sha>`로 되돌린다. 기존
사용자 변경이 섞인 dirty worktree에서는 revert를 실행하지 않고 clean checkpoint를
복구한다. RED 테스트, spec/plan/review commit, receipt evidence는 보존하며
cancellation만 되돌리거나 drain만 남기는 partial rollback은 허용하지 않는다.

rollback 직후 다음을 순서대로 실행한다.

    ./gradlew :bluetape4k-aws-java:test \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest' \
      --no-build-cache --no-daemon
    git diff --check

Expected: rollback baseline targeted test가 통과하고 production coordinator diff가
사라진다. 실패하면 rollback을 재시도하지 않고 blocker evidence를 기록한 뒤 plan-review와
architect lane을 다시 연다.

- [ ] Step 2: 재실행 지점을 기록한다.

rollback 또는 계획 수정 후에는 Task 2 dependency/source evidence → Task 2 RED →
Task 3/4 compile/implementation → Task 5 GREEN → Task 8 broader verification 순으로
재실행한다. baseline drift가 다시 발생하면 Task 1의 fast-forward 확인부터 반복한다.

---

### Task 8: 순차 검증과 Type A DoD 증적 수집

Files:
- Modify through workflow receipts: .bluetape/runs/20260812T124952Z-bceda7ab/
- Review: all changed files in this plan

- [ ] Step 1: targeted Bedrock 테스트를 fresh 실행한다.

    ./gradlew :bluetape4k-aws-java:test \
      --tests 'io.bluetape4k.aws.bedrock.BedrockRuntimeFlowExtensionsTest' \
      --no-build-cache --no-daemon

Expected: 기존 20개와 신규 회귀 테스트가 모두 통과한다.

- [ ] Step 2: 전체 aws-java 테스트를 targeted 결과와 분리해 실행한다.

    ./gradlew :bluetape4k-aws-java:test --no-build-cache --no-daemon

Expected: aws-java test task가 성공한다. 실패 시 targeted와 전체 실패를 섞지 않고 원인을 분리해 수정한다.

- [ ] Step 3: Detekt와 diff check를 실행한다.

    ./gradlew detekt --no-build-cache --no-daemon
    git diff --check
    for file in \
      docs/superpowers/specs/2026-08-12-issue-485-bedrock-callback-design.md \
      docs/superpowers/plans/2026-08-12-issue-485-bedrock-callback-plan.md \
      docs/review/2026-08-12-issue-485-bedrock-callback-review.md \
      docs/review/2026-08-12-issue-485-bedrock-callback-plan-review.md \
      docs/review/2026-08-12-issue-485-bedrock-callback-code-review.md \
      docs/lessons/2026-08-12-issue-485-bedrock-callback.md; do
      output=$(git diff --no-index --check /dev/null "$file" 2>&1)
      status=$?
      if [ "$status" -gt 1 ] || [ -n "$output" ]; then
        printf '%s\n' "$output"
        exit 1
      fi
    done

Expected: Detekt 성공, tracked whitespace 오류 없음, untracked 문서도 whitespace 오류
없이 통과한다. `git diff --no-index`는 파일이 `/dev/null`과 다르다는 정상 상태에서
exit 1을 반환하므로, wrapper는 exit 1+empty output을 허용하고 output 또는 exit >1만
실패로 판정한다.

- [ ] Step 4: required check evidence를 receipt에 기록한다.

각 명령의 exit status와 핵심 결과를 check-result 및 component-evidence에 기록하고, workflow script가 요구하는 순서 lane-complete -> check-result -> component-evidence -> completion-check -> complete -> verify를 지킨다. receipt JSONL이나 owner token을 수동 편집하지 않는다.

- [ ] Step 5: completion-check가 모든 required evidence를 확인할 때까지 수정한다.

    python3 /Users/debop/.codex/skills/bluetape-workflow/scripts/bluetape-flow.py \
      --state-root .bluetape completion-check \
      --run-id 20260812T124952Z-bceda7ab
    python3 /Users/debop/.codex/skills/bluetape-workflow/scripts/bluetape-flow.py \
      --state-root .bluetape verify \
      --run-id 20260812T124952Z-bceda7ab

Expected: required component coverage, four required checks, main verification, replacement lineage가 모두 충족된다. 하나라도 missing이면 완료를 주장하지 않고 해당 evidence를 보강한다.

---

### Task 9: lesson·commit·PR handoff를 별도 gate로 준비

Files:
- Create: docs/lessons/2026-08-12-issue-485-bedrock-callback.md
- Review: docs/review/2026-08-12-issue-485-bedrock-callback-code-review.md
- Commit only after all previous tasks pass: implementation/test/lesson/code-review files

- [ ] Step 1: lesson 문서를 실제 결과로 작성한다.

다음 항목을 한국어로 기록한다: callback identity 없는 SDK handler를 generation map으로 저장하지 않은 이유, callback lock과 Mutex를 분리한 이유, cancellation primary/suppressed 테스트가 발견한 경계, 외부 publisher timeout을 후속 범위로 보류한 이유, fresh verification 결과와 남은 P2.

- [ ] Step 2: Lore commit message를 작성한다.

첫 줄은 의도(왜 monitor 경계를 명시적 lock과 오류 우선순위로 고정했는지)를 설명하고 다음 trailers를 포함한다.

    Constraint: AWS callback에는 publisher/generation identity가 없고 public Flow 계약을 유지해야 했다.
    Rejected: callback 상태를 Mutex 하나로 통합 | non-suspending SDK callback에서 suspend와 deadlock 위험이 있다.
    Confidence: high
    Scope-risk: moderate
    Directive: 외부 publisher timeout/dispatcher는 별도 lifecycle 이슈에서 정의한다.
    Tested: 문서 diff-check, 계획 6관점 read-only review
    Not-tested: production Kotlin, targeted/full test, CI

- [ ] Step 3: PR 생성 전 exact diff와 상태를 read-back한다.

    git status --short --branch
    git diff --stat
    git diff -- aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt \
      aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensionsTest.kt

PR 생성은 사용자가 승인한 target repository/base/head 범위에서만 수행하고, merge는 CI·review·exact head를 다시 확인한 뒤 별도 승인을 받는다.

---

## 계획 self-review 및 traceability

- Spec coverage: lock 교체(Task 3), handler failure map 제거(Task 3), pending drain(Task 2 RED/Task 3 implementation/Task 5 GREEN), cancellation precedence(Task 2/4), replacement/backpressure 회귀(Task 2 RED/Task 5 GREEN), static boundary(Task 6), rollback(Task 7), required verification(Task 8), lesson/commit gate(Task 9)로 설계의 모든 목표와 DoD를 연결했다.
- Unresolved-marker scan: 미완성 표식과 이전 작업 참조를 사용하지 않았고, 각 code 변경 단계에 실제 함수·필드·명령·기대 결과를 적었다.
- Type consistency: cancelOnce(): Throwable?, cancel(cause: CancellationException), cancelActiveAttempt(), recordOperationFailure/selectOperationPrimary/mergeOperationFailures, materializeOperationFailure(), CallbackCompletion/CallbackDrain, ReentrantLock alias, callbackCompletions.clear()를 모든 후속 단계에서 동일하게 사용한다. coordinator-owned `BoundedFailureAccumulator`는 callbackLock 소유 경계 안에서만 접근하며 identity dedupe, 16개 sample, Long saturating overflow, one-shot materialization을 유지한다.
- Scope check: 하나의 private coordinator와 하나의 test class에 한정되며, dependency/API/catalog 변경을 별도 subsystem으로 확장하지 않는다.
- Plan-review evidence: 6개 관점 lane의 독립 산출물 또는 blocked 판정과 main integration,
  writer SPW-01(독자·목적·근거·미확정), SPW-02(계획 구조), SPW-03(한국어 기술 문체),
  SPW-04(traceability), SPW-05(read-back/checklist), resolved dependency/source proof,
  baseline drift, implementation lane validity, rollback checkpoint를 Task 1과
  plan-review artifact에 명시했다.

## 구현 시작 조건과 종료 조건

구현 시작 조건은 이 계획의 6관점 review에서 P0=0/P1=0이고, 사용자의 설계 승인(2026-08-12)이 기록된 상태다. 종료 조건은 fresh targeted/full test, Detekt, diff check, source boundary review, lesson, workflow completion/verify evidence가 모두 통과하는 것이다. CI, PR merge, branch cleanup은 이 계획 종료 후 별도 승인 gate다.
