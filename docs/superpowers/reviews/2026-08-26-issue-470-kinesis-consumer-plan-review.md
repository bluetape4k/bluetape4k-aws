# Issue #470 구현 계획 검토

## 검토 범위와 기준 정보

- **Artifact:** Type-A 구현 계획 및 Step 3-P 위험 예측
- **독자:** 구현자, 독립 reviewer, PR 검증자
- **언어:** 한국어 기술 문서
- **기준 정보:** 승인된 설계 `docs/superpowers/specs/2026-08-26-issue-470-kinesis-consumer-design.md`,
  구현 계획 `docs/superpowers/plans/2026-08-26-issue-470-kinesis-consumer-plan.md`,
  저장소 `AGENTS.md`, `bluetape-full-feature` Step 3-R 항목,
  `aws-java`/`aws-kotlin` 현재 Kinesis extension·fixture·Floci test 구조
- **범위 밖:** 실제 AWS 호출, KCL 도입, 영속 adapter 구현, Spring Boot listener runtime,
  benchmark module 생성, dependency/BOM/version 변경

검토는 승인된 public API를 임의로 바꾸지 않는다는 전제에서 수행했다. lease와 checkpoint
SPI는 설계대로 분리하지만, 영속 adapter는 lease/checkpoint/`ShardEnd`를 하나의
consistency domain에서 조건부 commit해야 한다. 임의로 분리된 backend의 원자성은 이
런타임이 대신 보장한다고 주장하지 않는다.

## 독립 관점 통합 판정

| 관점 | 판정 | 근거와 계획 반영 | 잔여 상태 |
|---|---|---|---|
| Performance | P0=0, P1=0 | `buffer(0)`, `maxShardConcurrency`, `maxRecordsPerPoll`, page/shard caps, Java/Kotlin `pollInterval >= 200ms`, `emptyBackoff`의 `max(..., 200ms)` clamp, non-empty/empty cadence, `N records -> N saves/metrics`, slow save/metrics, request limit, control-plane cadence와 virtual-time/call-count를 사용한다. `aws-java`/`aws-kotlin`에 Kinesis-specific benchmark source가 없고 공개 throughput/allocation을 주장하지 않으므로 benchmark는 N/A이며 structural in-flight/contention counter를 대체 증거로 둔다. 보완 plan read-back 재검토는 P0=0/P1=0으로 PASS했다. | P2: 실제 AWS quota/latency는 범위 밖 N/A |
| Stability | P0=0, P1=0 | heartbeat loss, blocked collector, retry/backoff cancellation, release timeout, 원래 cause 보존, takeover barrier, full shard-list discard, client lifecycle을 unit/fake test로 고정한다. 모든 store 호출은 cancellation-cooperative 계약이다. pre-emit validation 직후 takeover의 in-flight duplicate는 허용하고, 관측 이후 새 emit/save와 stale commit은 차단한다. | P2: production network partition/retention은 AWS-only N/A |
| Security | P0=0, P1=0 | stream/group/identity/shard/owner/sequence 형식·길이 검증과 caller-owned owner uniqueness, length-prefixed key, redacted metrics/log field, opaque malformed/oversized payload, monotonic/terminal checkpoint fencing, explicit Floci endpoint/static credentials guard를 계획에 반영한다. 보완 plan read-back 재검토는 P0=0/P1=0/P2 actionable=0으로 PASS했다. | P2: IAM/DynamoDB 권한과 adapter 보안은 이슈 밖 |
| Operator/Ops | P0=0, P1=0 | metrics cardinality와 callback 실패 종료 계약, cross-version checkpoint rollback gate, Floci wrapper의 all-services/no-selector 사실, health/readiness/liveness N/A, stop→drain→canary→scale rollout 및 release timeout 후 lease expiry 대기를 plan/risk/spec에 반영했다. 최신 Ops 재검토에서 P0/P1/P2=0 PASS를 확인했다. | P2=0; Gradle/Floci/AWS는 재검토에서 실행하지 않음 |
| Developer/API | P0=0, P1=0 | public SPI는 승인 spec과 동일하다. persistent adapter consistency-domain 책임과 takeover barrier가 split SPI의 fencing 공백을 닫는다. shard child direct `emit`을 제거하고 rendezvous pending/ack + 단일 outer emitter를 사용한다. Java in-memory/position/options/serialization parity, sealed-event major-version 경고, exact fixture task를 포함한다. | P2: SDK model 차이로 타입은 모듈별 중복하되 이름/assertion/fixture로 parity |
| User/caller | P0=0, P1=0 | durable store 사용 시에만 restart at-least-once라는 문장을 문서 첫 의미론으로 고정하고, Noop 단일 프로세스 제한, stable identity/unique owner, `take(n)`/cancellation, `toList()` 금지, module-specific migration, Spring Boot 연결을 README/manual에 요구한다. | P2: LocalStack은 선택적 fallback이며 이 실행에서는 사용하지 않음 |
| Main integration | P0=0, P1=0 | six-lane 결과를 중복 제거하고, no AWS/KCL/Spring Binder/no new dependency, exact Java→Kotlin Floci order, release pin, Korean public artifacts, Lore checkpoint를 하나의 plan으로 통합했다. | 구현·테스트 후 최신 diff로 재확인 |

## 원래 finding의 처분

| 원래 finding | 처분 | 정확한 계획 변경 | 재검토 증거 |
|---|---|---|---|
| P0 split lease/checkpoint fencing | 수정 완료 | 영속 adapter가 lease/checkpoint/`ShardEnd`를 같은 consistency domain에서 조건부 commit; test-only shared consistency-domain double로 takeover → 새 owner first save → stale save race를 검증; public SPI는 변경하지 않음 | developer/API 재검토에서 P0=0 확인 |
| P0 child coroutine direct `emit` | 수정 완료 | rendezvous `Channel<PendingRecord>`와 단일 outer emitter, downstream `emit` 반환 후 ack/save, Flow invariant·checkpoint 선행 금지 barrier test | developer/API 재검토에서 P0=0 확인 |
| P0 pre-emit TOCTOU stale delivery | 수정 완료 | 검증 이후 takeover를 원자적으로 막는다고 주장하지 않고, 관측 후 새 emit/save만 차단하며 이미 시작된 in-flight duplicate는 at-least-once로 허용; fenced save 거부와 새 owner inclusive replay를 검증 | developer/API 재검토에서 P0=0 확인 |
| P1 Java parity/serialization | 수정 완료 | Java in-memory checkpoint, starting position/options, state/serialization/invalid payload test와 Kotlin 동일 assertion | Task 4 Step 1–2 |
| P1 ABI fixture 위치/명령 | 수정 완료 | Task 2 Step 0 RED, Task 5 Step 1 GREEN에 실제 세 Gradle task와 “Java SDK 모듈을 소비하는 Kotlin fixture” 용어를 고정 | RED/GREEN exact command |
| P1 cancellation/deadlock/discovery budget | 수정 완료 | blocked collector/heartbeat loss, cancellation-cooperative store, retry budget/partial shard-list/cap, release cause matrix를 unit plan에 추가 | Task 3–4 virtual-time matrix |
| P1 checkpoint/fixture/docs commit | 수정 완료 | Kotlin contract/runtime, Java runtime, fixture/docs를 별도 Lore checkpoint로 분리 | 각 checkpoint `git show --stat` |
| P1 security identifier/redaction/payload/Floci guard | 수정 완료 | 외부 식별자 검증, opaque payload 및 sentinel redaction, monotonic/terminal fencing, explicit Floci endpoint/static credentials를 state/Floci/docs task에 추가 | security 재검토와 구현 테스트 |
| P1 performance Java cadence/hot path | 수정 완료 | Java/Kotlin cadence, request limit, `N -> N saves/metrics`, slow store/metrics, control-plane call-count를 virtual-time에 추가 | performance 재검토와 구현 테스트 |
| P1 performance empty-batch quota | 수정 완료 | 기존 `recordFlow`는 보존하고 `consumerFlow` empty delay를 `max(emptyBackoff, MIN_POLL_INTERVAL=200ms)`로 clamp; `emptyBackoff=1ms` cadence test 추가 | performance 재검토와 구현 테스트 |
| P1 metrics cardinality/callback lifecycle | 수정 완료 | 유한 `eventKind/outcome/reason/retryClass` label, deterministic redacted token, 고유 ID 다량 cardinality, callback throw/hang/cancellation의 원인 보존·one-time release·consumer 종료 test를 양 모듈에 추가 | 최신 Ops 재검토와 구현 테스트 |
| P1 rollback checkpoint compatibility | 수정 완료 | stop→drain, checkpoint 삭제/rewind 금지, newer writer→target reader `Sequence`/`ShardEnd` cross-version fixture, 비호환 시 controlled replay/migration 전 rollback 금지, release timeout 후 expiry 대기 | 최신 Ops 재검토와 구현 fixture |
| P2 Floci selector 사실 | 수정 완료 | pinned `FlociServer.Launcher.floci`가 모든 서비스를 활성화하므로 기본 실행 selector export 불필요; 외부 image만 조건부 문서화 | 최신 Ops 재검토와 source read-back |
| P2 health/readiness/rollout 운영 범위 | 수정 완료 | endpoint/Actuator N/A, probe·graceful shutdown caller 책임, stop→drain→canary→scale 및 stable identity/unique owner/replay/lease expiry runbook | 최신 Ops 재검토와 docs read-back |
| P2 manual/release/KDoc/CHANGELOG/file boundary | 수정 완료 | manual contract 명령, `releaseRef=0.5.0` 보존, Unreleased/develop, public KDoc 대상, 기존 extension 무분별 수정 제거, 명시적 CHANGELOG #470 | Task 5–6 read-back |

## Step 3-R required checks

| # | 검사 | 결과 | 근거 |
|---:|---|---|---|
| 1 | spec/DoD → concrete task mapping | PASS | Task 2–6과 §8 row 추적표, unit/Floci/manual command |
| 2 | 현재 codebase에 실행 가능한 순서 | PASS | fixture RED → contracts → Kotlin/Java runtime → Java→Kotlin Floci → docs/fixture GREEN |
| 3 | later-task dependency 부재 | PASS | public fixture RED는 구현 전, docs는 runtime 후, Floci는 Java unit 후 |
| 4 | success/failure/edge/concurrency/coroutine/lifecycle/backend paths | PASS | graph, lease, checkpoint, Flow, retry, cancellation, client, Floci/fake matrix |
| 5 | concrete targeted commands | PASS | Gradle module/test selectors, fixture publication, manual contract, diff/build |
| 6 | README/localized README | PASS | README English/Korean 수정과 parity read-back |
| 7 | Korean KDoc/comments/GitHub/CHANGELOG/release-facing prose | PASS | public KDoc 대상, Korean CHANGELOG/PR gate, releaseRef 보존 |
| 8 | new module settings/BOM/CI/coverage | N/A | 새 module을 만들지 않고 기존 publishable modules만 수정 |
| 9 | Spring Boot auto-configuration | N/A | Spring Boot listener/runtime를 추가하지 않음; low-level link만 문서화 |
| 10 | Exposed guards | N/A | Exposed surface 없음 |
| 11 | coroutine cancellation/dispatcher | PASS | cancellation-cooperative store, retry/backoff cancellation, Flow invariant, blocking call 금지 |
| 12 | performance/stability/cleanup/Testcontainers | PASS | bounded options, request/cadence/control-plane/per-record call-count, virtual-time, release timeout, sequential Floci |
| 13 | cross-module duplication decision | PASS | AWS SDK model 차이로 모듈별 계약을 유지하고 common naming/assertion/fixture로 parity |
| 14 | rollback/compatibility/migration | PASS | checkpoint commits, stop→drain, immutable checkpoint, newer writer→target reader `Sequence`/`ShardEnd` fixture, incompatibility controlled replay/migration gate, module-specific migration, Noop/durable boundary |

### 조건부 검사

- Streaming: logical EOF, `endingSequenceNumber`가 있는 truncated final batch, terminal 이후
  재사용과 double-terminal call을 Task 3–4 fake matrix에 포함한다.
- Suspend API: discovery/poll/backoff/store 호출 cancellation 전파와 `CancellationException`
  원형 보존을 포함한다.
- Client/resource: client는 호출자 소유이며 consumer가 닫지 않고, 선행 close 오류를 전파한다.
- JDK preview, 새 module, Spring auto-configuration, Exposed: 모두 범위 밖 N/A이며 계획에
  임의의 dependency 또는 운영 surface를 추가하지 않는다.

## Writer gate와 최종 판정

- [x] **SPW-01:** artifact 종류·독자·언어·목적·기준 정보·범위 밖을 위에 고정했다.
- [x] **SPW-02:** 관점 표, finding 처분, 14개 required check, 조건부 검사, 재검토 조건을 포함했다.
- [x] **SPW-03:** Korean technical register를 적용하고 API/명령/identifier는 그대로 보존했다.
- [x] **SPW-04:** 승인 spec·현재 저장소 구조·plan task·Gradle/manual 명령을 상호 대조했다.
- [x] **SPW-05:** Markdown 표·코드 fence·체크리스트를 read-back하고 workflow evidence에 기록한다.
- [x] **KO-01:** 사실·수치·identifier·명령·범위 밖을 보존했다.
- [x] **KO-02:** 성능/안정성 주장을 call-count·virtual-time 또는 N/A 근거로 치환했다.
- [x] **KO-03:** 번역투와 기계적 나열을 줄이고 concrete verb를 사용했다.
- [x] **KO-04:** `consumerFlow`, `recordFlow`, `ShardEnd`, `Noop`, `durable` 용어를 일관되게 사용했다.
- [x] **KO-05:** 비유·홍보 문구 없이 실패 모드와 책임 경계를 직접 기술했다.
- [x] **KO-06:** 제목·표·링크·명령·코드 fence를 read-back했다.
- [x] **KO-07:** `audit-korean-terms.mjs`를 변경된 spec/plan/review/risk 4개 파일에 실행했고
  findings=0으로 통과했다.

**Verdict:** developer/API·security·performance·operator/Ops와 보완된 plan/risk/spec
read-back에서 **P0=0/P1=0/P2=0**을 확인했다. Step 3-R PASS, Step 3-P triggered이며
사용자 승인 이후 implementation gate를 열고 Task 2 fixture RED부터 시작한다. Gradle/Floci/
AWS는 구현 전 계획 검토 범위 밖이므로 아직 실행하지 않았고, public API를 조용히 변경하지
않는다.
