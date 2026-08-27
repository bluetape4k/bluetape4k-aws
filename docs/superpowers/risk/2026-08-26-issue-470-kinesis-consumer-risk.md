# Issue #470 구현 위험 예측

## 범위와 공통 완화 원칙

이 문서는 승인된 Kinesis multi-shard consumer 설계와 구현 계획을 기준으로 작성한
Step 3-P 위험 기록이다. 실제 AWS는 사용하지 않으며 FlociServer 1.6.0과 MockK/fake를
경계로 삼는다. KCL, Spring Binder, 새 dependency, 새 BOM/version pin, 영속 adapter 구현은
범위에 넣지 않는다.

모든 위험은 먼저 작은 fake/virtual-time 테스트로 재현하고, 그 결과가 통과한 뒤 Java
Floci, Kotlin Floci 순서로 실행한다. 실패한 module은 해당 Lore checkpoint로 되돌려 같은
unit 증거부터 재실행한다.

## 위험·신호·완화·복구

| 위험 | 조기 신호 | 완화/검증 | 복구·재실행 |
|---|---|---|---|
| stale lease가 record/checkpoint를 덮음 | takeover 직후 이전 owner의 save가 성공하거나 `leaseCounter`가 역행함 | 영속 adapter는 lease/checkpoint/`ShardEnd`를 같은 consistency domain에서 조건부 commit; shared test double의 takeover→new owner first save→stale save barrier; in-memory CAS | 해당 store/consumer unit만 고치고 계약/runtime checkpoint부터 재실행 |
| Flow context invariant 위반 또는 checkpoint 선행 | child shard가 직접 `emit`하거나 collector 반환 전에 save됨 | rendezvous `Channel<PendingRecord>` + 단일 outer emitter + emit 반환 ack; slow collector/barrier test | Kotlin runtime checkpoint 이전으로 되돌리고 Flow unit 재실행 |
| blocked collector와 heartbeat loss 교착 | virtual time lease loss 후 collector/job/release가 bounded time 안에 끝나지 않음 | 독립 heartbeat, cooperative suspend store, `NonCancellable` bounded release, blocked fake cancellation test | heartbeat/release unit을 먼저 재현하고 Floci를 보류 |
| ListShards partial graph 또는 무한 retry | token 만료 뒤 일부 shard가 active가 되거나 retry/page/shard cap을 넘음 | full shard-list atomic apply, `ExpiredNextToken` restart, retry budget, page/unknown-parent/discovered-shard cap tests | graph fake evidence 복원 후 discovery만 재실행 |
| parent 누락이 root로 승격됨 | delayed/unknown parent child가 먼저 polling함 | non-null parent는 root 금지, `maxUnknownParentDiscoveries`, durable `ShardEnd` 두 부모 gating | graph test와 ShardEnd state test를 함께 재실행 |
| polling/call quota 또는 hot-path 비용 초과 | Java poller가 `200ms`보다 빨리 재호출하거나 `N` records의 save/metrics 비용이 다음 poll보다 앞섬 | 양 모듈 `pollInterval >= 200ms`, consumer empty delay `max(emptyBackoff, 200ms)`, `limit <= maxRecordsPerPoll`, `N -> N saves/metrics`, slow store/metrics와 control-plane cadence counter | `emptyBackoff=1ms` cadence/cost fake test 통과 전 Floci 보류 |
| metrics cardinality 폭증 또는 callback 실패 은닉 | 고유 stream/shard/owner가 raw label이 되거나 callback throw/hang/cancellation이 consumer 종료·원래 cause·lease cleanup을 잃음 | `eventKind/outcome/reason/retryClass` 유한 label, 길이 제한 deterministic redacted token, 고유 ID 다량 cardinality test, callback failure matrix에서 원래 cause·one-time release·consumer 종료 확인 | metrics contract/unit을 먼저 고치고 Floci 보류 |
| Floci false-green | 한 shard에만 record가 배정되거나 `shardId` 응답과 기대가 다름 | `ExplicitHashKey` 두 range, `PutRecordResponse.shardId` 확인, pinned gaps는 fake-only | Java Floci를 먼저 중단하고 unit/fake 결과와 image gap을 분리 기록 |
| Floci selector 사실 오기재 | pinned `FlociServer.Launcher.floci`가 모든 서비스를 켜는데 불필요한 export를 필수로 안내함 | wrapper source를 대조해 기본 test는 selector export 불필요, 외부 image 직접 실행만 조건부 설정으로 문서화 | 문서/fixture 경계를 수정하고 Java→Kotlin 순차 재실행 |
| Floci가 기본 AWS endpoint/credential chain으로 탈출 | local test가 `FlociServer`가 아닌 환경 자격 증명이나 기본 endpoint를 사용함 | `FlociServer.Launcher.floci.awsEndpoint`와 static emulator credentials를 client에 직접 주입하고 endpoint/provider guard를 assertion | 해당 fixture만 중단하고 client construction을 수정 |
| Java/Kotlin public contract drift | fixture compile 또는 exhaustive metrics handling이 한 모듈에서만 실패 | 동일 필수 인자·return 의미·오류/serialization assertion, 실제 fixture RED→GREEN task | 실패 module checkpoint만 되돌리고 parity fixture 재실행 |
| buffer/resource 상한 누락 | 느린 collector 중 `GetRecords` 호출·active shard·graph/page가 증가 | `buffer(0)`, `maxShardConcurrency`, `maxRecordsPerPoll`, `maxListShardsPages`, `maxDiscoveredShards` call-count | flow unit에서 상한을 고정한 뒤 Floci를 재실행 |
| cancellation이 retry 또는 release를 삼킴 | `CancellationException`이 일반 retry로 바뀌거나 원래 collector/poison cause가 사라짐 | retry/backoff cancellation, collector/poison/lease-loss/save-failure matrix, `NonCancellable` one-time release | failure matrix부터 재실행; 원래 cause를 보존하지 못하면 PR gate 차단 |
| store 호출이 cancellation을 차단 | blocking fake가 scope 취소 후 반환하지 않음 | 모든 suspend SPI의 cooperative 계약과 bounded termination test; release timeout은 저장소 호출을 무한 대기시키지 않음 | adapter contract/unit만 수정하고 runtime을 재검증 |
| ShardEnd/terminal 재사용 오류 | closed shard에서 duplicate launch, terminal 후 두 번째 호출, truncated final batch 누락 | ending sequence + 마지막 sequence 검증, null iterator 보조 신호, logical EOF·double-terminal·post-terminal fake | graph/consumer unit 재실행 후 Floci single-page만 수행 |
| client lifecycle 누수 또는 조기 close | consumer가 client를 닫거나 호출자 선행 close 오류를 숨김 | client 소유권 KDoc/manual, no-close test, 선행 close SDK error propagation | lifecycle unit 실패 시 runtime commit을 PR에 포함하지 않음 |
| metrics/serialization 정보 노출 | payload, credential, request token이 event/log에 포함되거나 위조 object가 통과 | payload-free sealed event, redaction assertion, round-trip/`readResolve`/invalid deserialization | state/metrics tests를 먼저 고치고 문서 예제를 다시 검토 |
| rollback binary가 durable checkpoint를 읽지 못함 | rollback 후 `Sequence`/`ShardEnd` decode 실패 또는 운영자가 state를 삭제/rewind함 | consumer stop→drain, checkpoint 불변 정책, newer writer→target reader cross-version fixture, 비호환 시 controlled replay/migration 전 rollback 금지, release timeout 후 lease expiry 대기 | target read compatibility가 PASS할 때까지 rollback하지 않고 migration/replay 계획을 별도 승인 |
| 식별자 충돌·권한 경계 오해 | delimiter concatenate로 서로 다른 tuple이 같은 key가 되거나 `consumerGroup`을 IAM 경계로 오해함 | length-prefixed canonical key, 외부 식별자 동일 검증, owner 전역 유일성·IAM 책임 분리, bounded opaque IDs | value-object/docs test를 먼저 재실행 |
| untrusted/poison payload가 실행·로그됨 | malformed/oversized record가 자동 deserialize/execute/log되거나 poison을 조용히 skip함 | opaque bytes 계약, sentinel payload/exception redaction, collector/poison 전체 실패·원래 cause·lease cleanup, DLQ는 caller 책임 | state/consumer unit과 KDoc/manual을 함께 수정 |
| 문서·release 기준 정보 불일치 | `releaseRef=0.5.0` tree에 develop API가 섞이거나 English/Korean 구조가 다름 | Unreleased/develop callout, releaseRef/source link 보존, manifest/manual contract, Korean terminology audit | docs checkpoint 전으로 되돌리고 manual validation 재실행 |

## 복잡도와 재검토 트리거

다음 조건 때문에 high-complexity 경로를 적용한다: multi-shard concurrency, coroutine
cancellation, lease/checkpoint fencing, dynamic reshard graph, external Kinesis API,
Testcontainers emulator, bilingual public documentation. 어느 하나라도 public signature,
consistency-domain 책임, Floci image assumption, dependency/BOM, releaseRef를 바꾸면
승인된 설계와 Step 3-R을 다시 검토한다.

throughput benchmark는 `aws-java`/`aws-kotlin`에 Kinesis-specific benchmark source가 없고 이슈가 공개
throughput 목표를 정의하지 않으므로 N/A다. 대신 bounded call-count와 virtual-time
stability가 계획의 성능·안정성 증거이며, 실제 AWS quota/latency·retention·IAM은 AWS-only
N/A로 분리한다.

이 library는 health/readiness/liveness endpoint나 Actuator integration을 제공하지 않는다.
probe와 graceful shutdown은 caller가 소유하며, rollout은 stop→drain→canary→scale 순서로
진행한다. `streamIdentity`는 안정적으로 유지하고 worker별 `ownerId`를 재사용하지 않는다.

## Writer gate

- [x] **SPW-01:** 문서 목적, 기준 정보, 실제 AWS 제외, 구현 범위를 명시했다.
- [x] **SPW-02:** 위험마다 신호·완화·검증·복구 지점을 매핑했다.
- [x] **SPW-03:** 한국어 기술 문체와 일관된 Kinesis 용어를 사용했다.
- [x] **SPW-04:** 승인 spec·plan의 fencing/Flow/Floci/cancellation 경계와 대조했다.
- [x] **SPW-05:** 표·목록·명령 토큰·범위 밖을 read-back했다.
- [x] **KO-01:** 사실·수치·identifier·scope boundary를 보존했다.
- [x] **KO-02:** 성능 주장을 call-count/virtual-time/N/A 근거로 제한했다.
- [x] **KO-03:** 구체적인 실패 신호와 동작 중심 문장을 사용했다.
- [x] **KO-04:** 같은 개념을 같은 용어로 유지했다.
- [x] **KO-05:** 비유나 홍보 표현 없이 위험과 책임을 기술했다.
- [x] **KO-06:** 표와 heading 및 code token을 read-back했다.
- [x] **KO-07:** `audit-korean-terms.mjs`를 변경된 spec/plan/review/risk 4개 파일에 실행했고
  findings=0으로 통과했다.

**Step 3-P 상태:** high-complexity trigger 충족. 위 위험을 Task 2–6의 RED/GREEN,
Java→Kotlin Floci, docs/manual, final review에서 재확인한다. 미해결 P0/P1이 남으면
implementation 또는 PR gate를 닫지 않는다.
