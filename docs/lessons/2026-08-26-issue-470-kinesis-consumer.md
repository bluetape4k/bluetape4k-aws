# Issue #470 Kinesis 멀티 샤드 consumer 교훈

Issue #470에서는 Java SDK v2와 AWS SDK for Kotlin에 공통 의미의 Kinesis 멀티 샤드
`consumerFlow`를 추가했다. 목표는 KCL을 복제하는 것이 아니라 기존 bluetape4k
coroutine·Flow·client lifecycle 패턴을 재사용하면서 discovery, lease fencing,
checkpoint와 reshard ordering의 경계를 명확히 제공하는 것이었다.

## 재사용한 생태계 경계

- 기존 `KinesisRecordFlowOptions`, `KinesisStartingPosition`, `withKinesisClient`와
  Java async client builder를 그대로 재사용했다.
- 새 public API는 각 SDK 모듈의 native `Record`를 감싸는 `KinesisShardRecord`와
  `KinesisShardKey`를 중심으로 두고, 서비스 SDK dependency는 기존 compileOnly 정책을
  유지했다.
- `InMemoryKinesisLeaseStore`와 `InMemoryKinesisCheckpointStore`는 단위 테스트 및
  Floci 계약 검증용이다. 실제 다중 worker 배포에는 호출자가 같은 consistency domain의
  durable adapter를 주입해야 한다.

## 핵심 계약

1. `ListShards`의 전체 목록만 graph로 적용한다. `parentShardId` 또는
   `adjacentParentShardId`가 누락된 응답은 root로 승격하지 않고 bounded discovery retry를
   수행한다.
2. 한 shard 안의 polling은 순차적이며, shard 사이의 동시성은
   `maxShardConcurrency`로 제한한다. child는 모든 부모의 durable `ShardEnd` 뒤에만
   시작한다.
3. rendezvous channel과 하나의 outer emitter를 사용한다. downstream `emit`이 반환된
   뒤에만 sequence checkpoint를 저장하므로 느린 collector가 다음 AWS 호출과 checkpoint를
   자연스럽게 늦춘다.
4. sequence checkpoint는 inclusive resume이다. 따라서 재시작·lease takeover에서 마지막
   record 중복은 허용하고, checkpoint 저장과 외부 side effect를 exactly-once로 주장하지
   않는다.
5. lease는 `key + ownerId + leaseCounter`를 fencing token으로 사용한다. `renew == null`
   이후 새 emit/save를 시작하지 않으며, 검증 직후 takeover되는 TOCTOU 구간의 in-flight
   중복은 at-least-once 경계로 문서화한다.
6. metrics에는 유한 label과 deterministic redacted token만 전달한다. payload, credential,
   request token과 원본 stream/shard/owner ID는 로그·callback에 포함하지 않는다.

## Floci와 운영 경계

Floci는 실제 AWS credential 없이 explicit hash key, multi-shard envelope, 순서와
checkpoint 시점을 검증하는 첫 번째 경로다. Floci가 증명하지 않는 production retention,
throttling 비율, resharding timing, durable store 원자성은 AWS 및 호출자 adapter의
검증 영역으로 남긴다. LocalStack은 Floci coverage gap을 재현할 때만 명시적으로 선택한다.

Consumer는 client, store, health/readiness/liveness probe의 수명을 소유하지 않는다.
정상 종료는 collecting scope 취소로 시작해 lease release timeout 안에서 stop/drain하고,
rollout은 stop → drain → canary → scale 순서를 따른다. rollback은 마지막 durable
checkpoint를 재사용하며 checkpoint를 삭제하거나 되감지 않는다.

## 검증 기록

- Java와 Kotlin consumer state/flow 단위 테스트에서 inclusive checkpoint, pagination,
  parent dependency, ordered emit-after-save, lease expiry/fencing, metrics redaction을
  고정했다.
- `FlociServer.Launcher.floci`를 사용해 Java 후 Kotlin 순서로 multi-shard consumer
  integration을 실행했다. 실제 AWS endpoint나 credential은 사용하지 않았다.
- Java/Kotlin consumer fixture publication compile을 통해 양 모듈의 public API가
  선언된 fixture source에서 노출되는지 확인했다.

## 남은 책임

영속 lease/checkpoint adapter의 conditional write, poison record dead-letter 정책,
exactly-once 외부 side effect, production IAM·quota·retention 검증은 이 이슈의 구현 범위가
아니다. 해당 책임을 추가할 때도 기존 inclusive checkpoint와 fencing 계약을 먼저 보존해야
한다.
