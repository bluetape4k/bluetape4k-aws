# 설계 리뷰 — Kinesis multi-shard consumer·lease·checkpoint runtime

<!-- 이슈 #470 | 2026-08-26 | Type-A 6관점 통합 리뷰 -->

**대상 명세**: [`2026-08-26-issue-470-kinesis-consumer-design.md`](../specs/2026-08-26-issue-470-kinesis-consumer-design.md)
**검토 범위**: `aws-java`, `aws-kotlin`, FlociServer 전용 검증
**초기 판정**: 설계 승인 보류
**통합 후 판정**: P0/P1 보완을 명세에 반영한 뒤 사용자 설계 승인 요청 가능

## 1. 통합 결론

여섯 독립 관점은 구현 전에 소비자 namespace, durable parent completion, checkpoint
fencing, heartbeat, unknown-parent failure, Floci pinned-image gap을 명시해야 한다는
데 의견이 일치했다. API와 호출자 관점은 `ShardEnd` 재개 의미와 Noop store의
재시작 한계를 P0로 분류했다. 명세는 다음 결정으로 이를 해소했다.

- `KinesisShardKey(streamIdentity, consumerGroup, shardId)`와 `KinesisLease`를 단일
  key로 묶어 lease/checkpoint 인자 불일치를 차단한다.
- `Sequence`와 durable `ShardEnd`를 저장하고 process-local completion 집합에 의존하지
  않는다. `ShardEnd`가 모든 parent에 존재할 때만 child를 시작한다.
- checkpoint 저장은 lease counter fencing을 요구하고, heartbeat는 polling/collector와
  독립적으로 실행한다. emit/save 직전 token을 재검증하고 lease loss를 전파한다.
- `buffer(0)` rendezvous와 `maxRecordsPerPoll`을 사용해 collector 처리보다 checkpoint가
  앞서지 않도록 한다. public buffer capacity 옵션은 제공하지 않는다.
- parent ID가 있는데 snapshot에 없으면 root로 승격하지 않고
  `maxUnknownParentDiscoveries` 초과 시 `KinesisShardGraphException`으로 실패한다.
- “rebalance”는 proactive stealing이 아니라 만료 lease takeover/failover로 한정한다.
- Noop store는 단일 프로세스·재시작 없는 실행에만 사용하며 durable restart와 다중
  worker 계약은 fencing 가능한 영속 store를 주입할 때만 주장한다.

## 2. 관점별 결과와 처분

| 관점 | 초기 핵심 finding | 명세 처분 | 검증 증거 |
|---|---|---|---|
| Architecture | consumer group 누락, process-local parent 완료, buffered checkpoint, heartbeat와 discovery retry 미정 | `KinesisShardKey`, durable `ShardEnd`, fenced save, 독립 heartbeat, full-snapshot retry, failover-only로 반영 | `§3.2`, `§4.1–§4.10`, `§5` |
| Failure | cancellation release 취소, local/durable iterator 혼동, store 오류·poison 범위 미정, nullable sequence | `NonCancellable` bounded release, local `AfterSequenceNumber`/restart `AtSequenceNumber`, store fail-fast, 전체 consumer 실패, `KinesisCheckpointException`으로 반영 | `§4.6–§4.10`, `§5` |
| Tests | Floci 1.6.0은 pagination·closed iterator·LATEST를 재현하지 않음; shard 분리 false-green 위험 | Floci 단일 page·explicit hash 검증과 fake `runTest` matrix를 분리하고 pinned gap을 기록 | `§6` |
| Security | owner uniqueness, Noop 오용, 자원 상한, 로그 redaction 미정 | 필수 ownerId, Noop 경고, page/shard/record/release hard cap, payload·credential·token 비노출 | `§3.2–§3.3`, `§5`, `§7` |
| API | lease/key 중복 인자, stream identity 기본값, 옵션 기본값, bufferCapacity 잔존, metrics 타입 미정 | 단일 `KinesisShardKey`, 필수 stable identity/options/stores, sealed event, buffer(0) 고정, Java/Kotlin parity fixture | `§3.1–§3.3`, `§7` |
| User/Docs | `ShardEnd` resume와 Noop/Floci 계약 모호, migration·환경·종료 예제 부족 | checkpoint 우선순위와 inclusive 중복, Noop 제한, Floci env/gap, compileOnly/client lifecycle/migration 문서 요구 | `§3.2`, `§4.5`, `§6–§8` |

초기 리뷰 lane은 모두 read-only였고 파일 변경은 없었다. 통합 명세만 main
`design` lane에서 수정한다. P2 수준의 metrics event 상세·식별자 길이·ABI fixture는
구현 계획과 테스트 명세에 추적한다.

## 3. 남은 위험과 명시적 범위

- Floci 1.6.0의 `ListShards` token pagination, closed-shard null iterator, 정확한
  `LATEST` 시점은 emulator 증거로 주장하지 않고 fake unit으로 검증한다.
- 실제 retention/trim timing, production quota, 다중 프로세스 네트워크 partition,
  IAM, KCL/DynamoDB 운영 latency는 AWS-only N/A다.
- 영속 store adapter 자체는 이슈 범위가 아니다. 단, public SPI는 lease counter
  fencing과 durable `ShardEnd`를 구현할 수 있어야 한다.
- 전체 consumer 실패 정책은 poison/collector/store/lease/graph 오류에 일관되게
  적용한다. 자동 skip 또는 dead-letter는 후속 adapter 책임이다.

## 4. 설계 문서 writer gate

- [x] **SPW-01** — 대상 독자, 모듈, 로컬 재사용, 공식 근거, Floci/AWS 경계를 한국어로
  고정했다.
- [x] **SPW-02** — API, data flow, ordering, 오류, compatibility, acceptance와 DoD를
  명세의 §3–§8에 연결했다.
- [x] **SPW-03** — `KO-01..KO-07`을 적용해 식별자·명령·URL·불확실성을 보존하고
  번역투와 홍보 문구를 제거했다.
- [x] **SPW-04** — 현재 Kinesis flow, #469 checkpoint/lease 패턴, AWS API, Floci
  1.6.0 source gap을 source-to-claim으로 대조했다.
- [x] **SPW-05** — heading/table/code fence/link를 read-back하고 명세와 본 리뷰가
  별도 설계 승인 입력임을 확인했다.

## 5. 승인 게이트

명세와 본 리뷰의 `git diff --check` 및 read-back이 통과하면, 다음 단계는 구현이
아니라 사용자에게 별도 설계 승인을 요청하는 것이다. 설계 승인 전에는 구현 계획,
테스트 코드, public API 코드, README/manual 적용을 시작하지 않는다.
