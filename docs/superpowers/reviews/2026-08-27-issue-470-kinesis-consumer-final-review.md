# Issue #470 Kinesis consumer 최종 6관점 리뷰

**리뷰 일자**: 2026-08-27
**대상**: `feat/issue-470-kinesis-consumer`
**범위**: `aws-java`, `aws-kotlin`, public consumer fixture, README/manual/lesson/CHANGELOG
**판정**: PASS — P0=0, P1=0. PR 생성 가능하며 merge는 별도 승인 게이트다.

## SPW-01 — 독자·목적·근거 고정

- **독자/언어**: bluetape4k 사용자와 유지보수자, 한국어 기술 리뷰(코드/API/명령/URL은 원문 유지).
- **목적**: Issue #470 수용 기준과 승인된 설계·실행 계획이 구현·문서·검증 결과에 반영됐는지 최종 판정한다.
- **근거**: `docs/superpowers/specs/2026-08-26-issue-470-kinesis-consumer-design.md`, `docs/superpowers/plans/2026-08-26-issue-470-kinesis-consumer-plan.md`, Java/Kotlin Kinesis source와 test, Floci 실행 로그, Gradle fixture/manual 결과.
- **외부 경계**: 실제 AWS 계정·credential·KCL·Spring Cloud Stream Binder는 사용하지 않았다. Floci pinned wrapper가 재현하지 않는 retention, throttling, reshard timing은 검증하지 않았다.

## SPW-02 — 리뷰 계약과 6관점 결과

| 관점 | P0 | P1 | 결과와 근거 |
|---|---:|---:|---|
| 성능 | 0 | 0 | rendezvous `Channel`, shard별 순차 polling, `maxShardConcurrency`, `maxRecordsPerPoll`, `pollInterval >= 200ms`, empty delay clamp를 양 모듈에 적용했다. Java/Kotlin Kinesis 전체 테스트와 Floci 회귀가 통과했다. 공개 throughput benchmark는 목표가 없어 N/A다. |
| 안정성 | 0 | 0 | parent/adjacent-parent graph, bounded discovery/retry, heartbeat lease fencing, inclusive checkpoint, collector cancellation, `NonCancellable` bounded release를 구현했다. Java 526 tests와 Kotlin 696 tests가 모두 실패 없이 통과했다. |
| 보안 | 0 | 0 | identifier/control-character/길이 검증, length-prefixed key, positive lease counter, monotonic/terminal checkpoint, finite metrics label과 redacted token을 적용했다. payload/credential/request token은 event·로그에 넣지 않으며 state/redaction 테스트가 통과했다. |
| 운영/Ops | 0 | 0 | lease acquire/renew/loss·discovery·retry·checkpoint metrics를 bounded event로 제공한다. client/store/probe 수명은 caller 소유이고 stop→drain→canary→scale 및 rollback checkpoint 규칙을 README/manual/lesson에 기록했다. durable adapter와 health endpoint는 caller 범위로 명시했다. |
| 개발자/API | 0 | 0 | Java SDK v2와 AWS SDK for Kotlin에 동일한 필수 identity/options/store 계약과 SDK별 envelope를 추가했다. KCL 의존성·새 dependency·기존 `recordFlow` 변경은 없다. consumer fixture publication compile이 통과했다. |
| 사용자/호출자 | 0 | 0 | `Sequence` inclusive replay와 at-least-once 의미, Noop/InMemory 제한, child `ShardEnd` barrier, Floci 선택 명령과 cancellation 종료 방식을 양 locale 문서에 반영했다. 정확히 한 번의 외부 side effect는 호출자 책임으로 명시했다. |

### 결함·보완 판정

- **P0/P1**: 없음.
- **P2/N/A(의도된 경계)**: 실제 AWS retention·throttling·resharding timing, production durable lease/checkpoint adapter의 conditional write, IAM/quota, non-cooperative callback의 watchdog, health/readiness/liveness endpoint, 공개 throughput benchmark는 이 이슈 범위 밖이다. 문서에서 검증 주장을 Floci·fake-client·구조적 상한으로 제한했다.
- Floci 1.6.0이 `shardCount`/`ExplicitHashKey`를 한 shard로 축약할 수 있는 경계는 테스트 주석과 lesson에 기록했고, multi-shard graph/ordering은 fake-client 테스트로 별도 검증했다.

## SPW-03 — 한국어 기술 문체·용어

- 용어는 `shard`, `consumer`, `checkpoint`, `lease`, `redacted token`, `Floci` 등 API 생태계의 고정 표현을 유지했다.
- README, 한국어 manual, lesson, 이 리뷰의 문장을 자연스러운 엔지니어 기술 문체로 읽어 보았으며, 영어 manual은 동일 구조의 별도 locale로 유지했다.
- `audit-korean-terms.mjs`에서 새 한국어 문서 4개가 `findings: []`를 반환했다. 전체 `CHANGELOG.md`의 기존 `snapshot-loanword` 6건은 #470 변경 밖이므로 수정하지 않았다.

## SPW-04 — 기술 의미·추적성

| 주장 | 구현/문서 근거 | 검증 |
|---|---|---|
| emit 뒤 checkpoint 저장 | `KinesisAsyncConsumerFlow.kt`, `KinesisConsumerFlow.kt`의 pending/ack rendezvous 경계 | Java/Kotlin flow unit test |
| child는 두 parent `ShardEnd` 뒤 시작 | `KinesisShardGraph.kt`와 discovery launcher | graph/ordering unit test |
| stale save fencing | `KinesisLease`, `InMemoryKinesis*Store`, public SPI KDoc | state unit test |
| Floci-first/no real AWS | `KinesisConsumerFlociTest.kt`, `FlociServer.Launcher.floci`, static credentials | Java → Kotlin Floci 순차 실행 |
| public parity | 두 consumer fixture와 `verifyAwsConsumerFixturePublication` | fixture compile/verify 성공 |
| release/manual 경계 | `Unreleased/develop`, `releaseRef: 0.5.0`, generated manifest | manual contract 성공 |

수정 후 실제 source와 문서의 숫자·명령·API 이름을 다시 대조했으며, unsupported AWS 운영 주장은 제거하거나 명시적 공백으로 남겼다.

## SPW-05 — 최종 read-back과 DoD

- Markdown heading/table/list/code fence를 각 locale 문맥에서 재독해했고, `git diff --check`가 통과했다.
- 수동 계약: `export_manifest.rb --check` 성공, `manual_contract_test.rb` **9 runs / 44 assertions / 0 failures**.
- 정적/빌드: `detekt` 성공, `./gradlew build -x test --parallel` 성공.
- 모듈 회귀: `:bluetape4k-aws-java:check` **526 tests, failures=0, errors=0**, `:bluetape4k-aws-kotlin:check` **696 tests, failures=0, errors=0**.
- Floci 회귀: Java `KinesisConsumerFlociTest` 성공, 이어서 Kotlin `KinesisConsumerFlociTest` 성공.
- **SPW 상태**: SPW-01 PASS, SPW-02 PASS, SPW-03 PASS, SPW-04 PASS, SPW-05 PASS.

**최종 DoD**: 구현·테스트·문서·리뷰 증거는 완료됐다. 남은 상태는 Lore 커밋, PR 생성, hosted CI 확인이며, merge는 새 사용 승인 전까지 수행하지 않는다.
