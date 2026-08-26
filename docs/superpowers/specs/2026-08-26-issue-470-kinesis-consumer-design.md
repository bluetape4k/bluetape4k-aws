# 설계 명세 — Kinesis multi-shard consumer·lease·checkpoint runtime

<!-- 이슈 #470 | bluetape4k-aws -->

**상태**: 설계 검토 중
**작성일**: 2026-08-26
**이슈**: [#470](https://github.com/bluetape4k/bluetape4k-aws/issues/470)
**대상 모듈**: `aws-java`, `aws-kotlin`
**검증 원칙**: 실제 AWS 계정은 사용하지 않고 `FlociServer`만 사용

## 1. 문제와 목표

현재 Kinesis API는 put/get와 Kotlin의 단일 샤드 cold `recordFlow`까지 제공하지만,
애플리케이션이 장기 실행 consumer를 만들 때 필요한 shard discovery, ownership,
checkpoint, restart, reshard 처리를 공통 계약으로 제공하지 않는다. 이 상태에서는
같은 스트림을 소비하는 애플리케이션마다 중복·유실·재배분 정책이 달라진다.

이 변경의 목표는 다음과 같다.

- `ListShards` pagination으로 열린 샤드와 닫힌 샤드를 계속 발견하고 새 child shard를
  동적으로 시작한다.
- 한 샤드에서는 `GetShardIterator`/`GetRecords`를 순차 호출해 sequence ordering을
  유지하고, 서로 다른 샤드는 제한된 병렬성으로 소비한다.
- 교체 가능한 lease/ownership store와 checkpoint store를 공개 SPI로 정의한다.
- `AT_SEQUENCE_NUMBER`, `AFTER_SEQUENCE_NUMBER`, `LATEST`를 포함한 시작 위치와
  checkpoint 재개 의미를 명시한다. 기존 `TrimHorizon`과 `AtTimestamp`도 보존한다.
- backpressure, retry/throttle, lease loss, poison record, cancellation, client
  lifecycle을 문서와 테스트 계약으로 고정한다.

KCL, Spring Cloud Stream Kinesis Binder 전체를 복제하지 않는다. KCL dependency를
필수로 추가하지 않으며, exactly-once나 AWS 전용 장기 운영 지표를 주장하지 않는다.
기존 단일 샤드 `recordFlow`의 시그니처와 동작은 회귀 없이 유지한다.

## 2. 현재 구현과 외부 근거

### 2.1 로컬 재사용 경계

- `aws-kotlin/.../kinesis/KinesisRecordFlow.kt`의 cold `flow {}`,
  `currentCoroutineContext().ensureActive()`, cancellation 재전파, empty-batch
  backoff, iterator/throttle retry, `getShardIterator` 매핑을 재사용한다.
- `aws-kotlin/.../kinesis/KinesisStartingPosition.kt`와
  `KinesisRecordFlowOptions.kt`의 직렬화·생성자 검증과 public KDoc 스타일을
  유지한다. Java SDK 쪽에는 동일 의미의 모듈 전용 position/options 타입을 둔다.
- `aws-kotlin`과 `aws-java`의 `AbstractAwsTest`는 기본 backend가
  `FlociServer.Launcher.floci`이고 `localstack`은 명시적 fallback이다.
- Issue #469의 DynamoDB Streams 구현에서 checkpoint after emit, shard envelope,
  bounded `flatMapMerge`, in-memory store, no-op metrics 패턴을 재사용하되 Kinesis
  model과 lease 요구사항에 맞춰 별도 타입으로 정의한다. 두 AWS SDK의 model 타입을
  모듈 사이에서 섞지 않는다.

### 2.2 공식 API와 emulator 근거

- [`ListShards` AWS SDK for Kotlin API](https://docs.aws.amazon.com/sdk-for-kotlin/api/latest/kinesis/aws.sdk.kotlin.services.kinesis/list-shards.html)는
  suspend API이며 `nextToken`, `maxResults`, `exclusiveStartShardId`,
  `ShardFilter`를 제공한다. KCL도 이 operation으로 shard를 발견한다.
- [`ListShardsRequest`](https://docs.aws.amazon.com/sdk-for-kotlin/api/latest/kinesis/aws.sdk.kotlin.services.kinesis.model/-list-shards-request/)
  의 pagination token을 소비 루프의 페이지 상한과 함께 사용한다.
- [`DescribeStream` API](https://docs.aws.amazon.com/kinesis/latest/APIReference/API_DescribeStream.html)는
  shard 순서 자체를 chronology로 간주하지 말고 parent ID로 관계를 해석하도록
  안내한다. Kinesis `Shard`의 `parentShardId`와
  `adjacentParentShardId`를 모두 dependency로 취급한다.
- [Kinesis API reference](https://docs.aws.amazon.com/kinesis/latest/APIReference/)의
  `GetShardIterator`/`GetRecords` 시작 위치와 iterator 오류 계약을 기존
  `recordFlow`의 retry 경계에 연결한다.
- [KCL shared-throughput lease 개념](https://docs.aws.amazon.com/streams/latest/dev/shared-throughput-kcl-consumers.html)과
  [lease lifecycle](https://github.com/awslabs/amazon-kinesis-client/blob/master/docs/lease-lifecycle.md)는
  한 worker가 한 shard lease를 보유하고 checkpoint와 lease counter를 함께
  검증하는 모델의 참고 자료다. KCL 자체는 dependency로 가져오지 않는다.
- [Floci Kinesis service matrix](https://floci.io/floci/services/)와
  [Kinesis 환경 변수](https://floci.io/floci/configuration/environment-variables/)
  (`FLOCI_SERVICES_KINESIS_ENABLED=true`)를 emulator 검증 근거로 사용한다.

외부 문서가 설명하는 실제 AWS quota·retention·reshard timing은 Floci가 재현한
범위와 분리한다. 테스트가 통과했다는 사실만으로 실제 AWS 운영 동작을 주장하지
않는다.

## 3. 공개 API 설계

`aws-java`와 `aws-kotlin`에 동일한 개념명을 두되 각 SDK의 `Record`와 `Shard` 타입은
모듈 안에 가둔다. 기존 단일 샤드 API는 변경하지 않고 새 runtime은 별도
`consumerFlow` 진입점으로 추가한다.

### 3.1 시작 위치와 record envelope

기존 Kotlin `KinesisStartingPosition`은 유지하고 Java 모듈에도 동일한 다섯 변형을
제공한다.

| 변형 | 의미 |
|---|---|
| `TrimHorizon` | 보존 중인 가장 오래된 record부터 읽음 |
| `Latest` | iterator를 얻은 뒤 기록된 record부터 읽음 |
| `AtSequenceNumber(value)` | 지정 sequence number를 포함해 읽음 |
| `AfterSequenceNumber(value)` | 지정 sequence number 다음부터 읽음 |
| `AtTimestamp(value)` | 지정 시각 이후의 record부터 읽음 |

sequence number와 stream/shard 식별자는 `requireNotBlank`와 최대 길이·control
character 검증을 거친다. public 값 객체는 tuple key canonicalization을 한 곳에서
수행하고, `Serializable` 타입에는 `serialVersionUID`와 역직렬화 검증을 적용한다.

각 모듈은 다음 envelope를 공개한다.

```kotlin
// 개념 스케치: 실제 컴파일 타입은 각 모듈의 SDK Record 모델로 선언한다.
data class KinesisShardRecord(
    val streamName: String,
    val shardId: String,
    val record: <SDK-specific Kinesis Record>,
)
```

서로 다른 샤드 사이의 전역 순서는 보장하지 않는다. 한 샤드의 `record` 순서는
`GetRecords` 응답과 동일하다.

### 3.2 consumer group, checkpoint와 lease SPI

`consumerGroup`은 같은 stream을 독립적으로 읽는 애플리케이션을 구분하는 필수
namespace이고, `streamIdentity`는 stream generation까지 포함하는 호출자 제공 stable
identity다. 둘 다 API에서 필수로 받으며, `streamName`을 자동 identity로 대체하지
않는다. store key는 `KinesisShardKey(streamIdentity, consumerGroup, shardId)` 한
객체로만 만들고 lease 내부 key와 인자 key를 중복 전달하지 않아 불일치를 차단한다.
Kinesis API 호출에는 `streamName`을 사용하되, stream ARN이나 재생성 세대 UUID를
`streamIdentity`로 제공한다. 이 구분 없이 stream name만 key로 쓰면 독립 consumer의
checkpoint가 섞이거나 재생성된 stream이 오래된 checkpoint를 재사용한다.

```kotlin
data class KinesisShardKey(
    val streamIdentity: String,
    val consumerGroup: String,
    val shardId: String,
) : Serializable

sealed interface KinesisCheckpoint : Serializable {
    data class Sequence(val sequenceNumber: String) : KinesisCheckpoint
    data object ShardEnd : KinesisCheckpoint
}

interface KinesisCheckpointStore {
    suspend fun load(key: KinesisShardKey): KinesisCheckpoint?
    suspend fun save(key: KinesisShardKey, checkpoint: KinesisCheckpoint, lease: KinesisLease)
}

data class KinesisLease(
    val key: KinesisShardKey,
    val ownerId: String,
    val leaseCounter: Long,
)

interface KinesisLeaseStore {
    suspend fun acquire(
        key: KinesisShardKey,
        ownerId: String,
        leaseDuration: Duration,
    ): KinesisLease?

    suspend fun renew(lease: KinesisLease, leaseDuration: Duration): KinesisLease?
    suspend fun release(lease: KinesisLease)
}
```

`null` lease는 다른 worker의 lease가 아직 유효함을 의미한다. 만료된 lease는 새
`leaseCounter`로 takeover할 수 있다. `renew`와 `release`는 `key + ownerId +
leaseCounter`를 조건으로 원자적으로 수행하며, `renew`가 `null`을 반환하면 consumer는
`KinesisLeaseLostException`을 던지고 해당 shard에서 더 이상 record를 emit하거나
checkpoint를 저장하지 않는다.

checkpoint 저장은 `lease`를 함께 받아 fencing한다. 영속 store는 저장 시점에
`leaseCounter`가 현재 owner보다 오래된지 조건부로 확인하고 stale 저장을 거부해야
한다. lease와 checkpoint를 서로 다른 backend에 둘 때도 이 fencing 계약을 깨뜨리지
않아야 한다. `ShardEnd`는 parent/child graph를 여러 worker가 공유할 수 있게 하는
durable 상태이며, sequence checkpoint와 같은 key에 저장한다.

각 모듈에 다음 구현을 함께 제공한다.

- `NoopKinesisCheckpointStore`: checkpoint를 저장하지 않는 단일 프로세스 전용 구현.
- `InMemoryKinesisCheckpointStore`: 단위 테스트와 Floci contract 검증용 thread-safe
  store. `leaseCounter`보다 낮은 저장을 거부하고 `Sequence`/`ShardEnd`를 보존한다.
- `NoopKinesisLeaseStore`: 단일 프로세스 전용 구현. 모든 요청을 현재
  owner가 소유하는 의미이므로 다중 worker 조정에는 사용하지 않는다.
- `InMemoryKinesisLeaseStore`: owner, counter, expiry를 원자적으로 검증하는 local
  store. DynamoDB/KCL table adapter는 이 이슈 범위에 포함하지 않는다.

Noop 조합은 프로세스 재시작·다중 worker·lease takeover·durable `ShardEnd`를 보장하지
않는다. at-least-once restart 계약과 lease fencing이 필요하면 호출자는 반드시 영속
store를 주입해야 한다. Store의 lifecycle은 호출자 소유다. consumer가 store를 닫거나 background thread를
만들지 않는다. 실제 영속 lease store는 `acquire`/`renew`/`release`의 원자성을
자체적으로 보장하고, checkpoint store는 `leaseCounter` fencing과 `ShardEnd` 저장을
보장해야 한다. 이 이슈는 두 store를 하나의 DynamoDB table로 묶는 adapter까지
구현하지 않지만, adapter가 이 계약을 구현할 수 있는 public token을 제공한다.

### 3.3 옵션, metrics, 진입점

`KinesisConsumerOptions`는 `ownerId`를 필수로 받고, 기존
`KinesisRecordFlowOptions`를 감싸며 다음 값을 검증한다. `ownerId`는 자동 UUID를
생성하지 않고 worker 간 전역적으로 유일한 값(예: deployment·instance UUID)을
호출자가 제공한다.

| 옵션 | 계약 |
|---|---|
| `recordOptions` | 기존 batch/poll/iterator/throttle 제한을 재사용 |
| `maxShardConcurrency` | 1 이상; 동시에 lease를 보유할 최대 샤드 수 |
| `discoveryInterval` | 양수; `ListShards` 재검색 간격 |
| `leaseDuration` | `leaseRenewInterval`보다 큼 |
| `leaseRenewInterval` | 양수; 각 active shard의 갱신 주기 |
| `maxListShardsPages` | 1 이상; pagination을 무한히 따르지 않음 |
| `maxDiscoveryRetries` | 0 이상; `ListShards` retry budget |
| `maxUnknownParentDiscoveries` | 1 이상; parent 누락을 무한히 보류하지 않음 |
| `maxDiscoveredShards` | 1 이상; graph 메모리 상한 |
| `maxRecordsPerPoll` | 1 이상; 한 번의 `GetRecords` 처리 상한 |
| `leaseReleaseTimeout` | 양수; cancellation cleanup 상한 |
| `ownerId` | 비어 있지 않고 길이·control character·전역 유일성 검증 |

`KinesisFlowEvent`는 payload·credential·request token을 포함하지 않는 sealed event
타입이다. `KinesisFlowMetrics`는 `suspend fun onEvent(event)` callback과 no-op 구현을
제공하며, shard 시작/완료, batch 크기, discovery page, lease acquire/renew/loss,
retry, checkpoint 저장만 관측한다. callback 예외는 숨기지 않고 consumer를 실패시킨다.

```kotlin
fun KinesisClient.consumerFlow(
    streamName: String,
    consumerGroup: String,
    streamIdentity: String,
    position: KinesisStartingPosition,
    options: KinesisConsumerOptions,
    checkpointStore: KinesisCheckpointStore,
    leaseStore: KinesisLeaseStore,
    metrics: KinesisFlowMetrics = NoopKinesisFlowMetrics,
): Flow<KinesisShardRecord>
```

`consumerGroup`, `streamIdentity`, `ownerId`는 길이·control character·비공백을
생성 시 검증한다. `KinesisCheckpointStore`나 `KinesisLeaseStore`를 사용하지 않는
단일 프로세스 호출자는 위 API에 Noop 구현을 명시적으로 주입해야 한다. Java 모듈은
동일한 필수 인자·반환 의미로 `KinesisAsyncClient.consumerFlow`를 제공한다.
기존 `recordFlow(streamName, shardId, ...)`는 시그니처와 단일 샤드 semantics를
그대로 둔다. Java 모듈에는 이 consumer가 재사용하는 private async shard poller와
동일한 오류/position mapping을 두며, 기존 Java primitive coroutine wrapper의
public 시그니처를 변경하지 않는다. 주입된 AWS client는 호출자 소유이며, 짧은 수명에는 기존
`withKinesisClient`/`withKinesisAsyncClient` helper를 사용한다.

## 4. 소비 흐름과 ordering

1. `consumerFlow`는 `ListShards`를 페이지 단위로 읽고, 각 page에
   `maxListShardsPages`와 `maxDiscoveryRetries`를 적용한다. `ExpiredNextTokenException`
   또는 token 만료가 의심되는 오류가 나면 현재 partial snapshot을 폐기하고 처음부터
   새 snapshot을 만든다. page 상한을 넘거나 bounded retry가 소진되면 원래 예외를
   전파하며 부분 graph를 적용하지 않는다. 중복 shard ID는 한 snapshot에서 병합한다.
2. 완전한 snapshot을 `KinesisShardKey` graph로 만들고 `parentShardId`,
   `adjacentParentShardId`를 모두 dependency로 기록한다. parent ID가 명시적으로
   `null`인 shard만 root다. parent ID가 있는데 snapshot에 해당 parent가 없으면
   절대 root로 승격하지 않고 `maxUnknownParentDiscoveries`까지 다음 완전한
   snapshot에서 재시도한다. 한도를 넘으면 `KinesisShardGraphException`으로 전체
   consumer를 실패시킨다.
3. child는 process-local `completedShardIds`가 아니라 공용 checkpoint store의
   `KinesisCheckpoint.ShardEnd`를 조회해 dependency가 모두 완료된 경우에만 launch
   후보가 된다. 이미 active이거나 해당 key의 durable `ShardEnd`가 있는 shard는
   중복 launch하지 않는다. 이 규칙은 서로 다른 worker가 merge parent를 처리해도
   child ordering을 유지한다.
4. launch는 `Semaphore(maxShardConcurrency)`와 `maxDiscoveredShards`로 제한한다.
   lease를 획득하지 못한 shard는 record를 emit하지 않고 다음 discovery에서 다시
   시도한다. `acquire == null`은 유효한 다른 owner를 뜻하며, 만료 lease takeover만
   지원한다. proactive stealing·cooperative rebalance는 이 이슈 범위가 아니다.
5. shard job은 시작할 때 checkpoint를 읽는다. `ShardEnd`면 polling을 생략하고 완료
   처리한다. `Sequence(value)`면 `AtSequenceNumber(value)`로 inclusive 재개하고,
   checkpoint가 없으면 호출자 `position`을 사용한다. 따라서 checkpoint가 최초
   `position`보다 우선하며, inclusive resume은 마지막 record 중복을 허용한다.
6. 한 shard job은 Kotlin의 기존 polling 의미를 재사용하고 Java에는 private async
   poller를 둔다. process-local `lastEmittedSequenceNumber`는 iterator expiry 복구에
   사용해 `AfterSequenceNumber(lastEmitted)`으로 재획득한다. process restart에서는
   durable `Sequence`를 inclusive하게 사용한다. checkpoint 사용 시 sequence number가
   없는 record는 emit 전에 `KinesisCheckpointException`으로 실패한다.
7. shard lifecycle마다 polling과 독립된 heartbeat coroutine을 둔다. heartbeat는
   `leaseRenewInterval`마다 `renew`하고, loss 시 shard job과 전체 consumer를 취소한다.
   각 emit과 fenced checkpoint save 직전에 현재 lease token을 재검증하며, loss 이후에는
   emit/save를 허용하지 않는다. 정상 종료와 취소의 release는 `NonCancellable` 안에서
   `leaseReleaseTimeout`으로 제한한다.
8. shard 완료는 `nextShardIterator == null`만으로 결정하지 않는다. Kinesis
   `SequenceNumberRange.endingSequenceNumber`가 있으면 마지막 처리 sequence까지
   도달했는지 확인해 `ShardEnd`를 저장하고, ending range가 없을 때만 null iterator를
   보조 신호로 사용한다. 이 규칙은 Floci 1.6.0의 closed-shard iterator 차이를 흡수한다.
9. shard job은 public buffer capacity 옵션 없이 rendezvous `buffer(0)` semantics를 사용한다.
   `GetRecords` batch도 `maxRecordsPerPoll` 안에서 한 record씩 collector의 실제
   `emit` 반환 뒤 checkpoint를 저장하며, downstream 처리보다 checkpoint가 앞서지
   않는다. 서로 다른 shard의 전역 순서는 약속하지 않는다.
10. discovery와 모든 shard job은 하나의 consumer scope에 속한다. collector/poison
    예외, store 오류, lease loss, graph 오류는 자동 skip 없이 전체 consumer를 실패시키고
    모든 lease를 정리한다. cancellation은 원래 `CancellationException`을 보존한다.

이 흐름은 애플리케이션 프로세스가 살아 있는 동안 cold Flow를 계속 유지한다.
정상 종료는 collector가 취소하거나 scope가 취소될 때 발생하며, 스트림의 현재
모든 shard가 끝났다는 이유만으로 consumer가 자동 완료되지는 않는다. 테스트는
`take(n)` 또는 명시적 cancellation으로 종료한다.

## 5. 오류·복구·처리 의미론

| 상황 | 계약 |
|---|---|
| `CancellationException` | 가장 먼저 재전파하고 discovery·shard job을 취소한다 |
| retryable Kinesis service error | shard polling은 기존 full-jitter backoff와 bounded retry budget을 사용한다. `ListShards`도 page retry를 적용한다 |
| non-retryable service error | 즉시 전파한다 |
| `ExpiredNextTokenException` | partial snapshot을 폐기하고 전체 `ListShards` snapshot을 재시작한다. `maxDiscoveryRetries` 소진 뒤 원래 예외를 전파한다 |
| `ExpiredIteratorException` | 같은 실행에서 local 마지막 emit 위치를 `AfterSequenceNumber`로 사용한다. 재시작에서는 durable checkpoint를 `AtSequenceNumber`로 사용한다. 둘 다 없고 `Latest`이면 즉시 전파한다 |
| `KinesisLeaseStore.acquire == null` | 유효한 다른 owner로 간주해 해당 shard를 건너뛰고 다음 discovery에서 재시도한다 |
| `renew == null` | `KinesisLeaseLostException`; 즉시 emit/save를 차단하고 전체 consumer를 실패시킨다 |
| checkpoint `save` 실패 또는 fenced 저장 거부 | Flow를 실패시키고 memory상 위치를 전진시키지 않는다 |
| checkpoint 사용 중 nullable sequence | emit 전에 `KinesisCheckpointException`으로 실패한다 |
| store `load`/`renew`/`release` 오류 | `acquire == null` 외에는 자동 재시도하지 않고 consumer 오류로 전파한다. release 오류는 원래 예외를 덮지 않는다 |
| collector/poison record 예외 | 예외를 삼키거나 자동 skip하지 않는다. 전체 consumer와 모든 lease를 정리한 뒤 호출자에게 전파한다 |
| 느린 collector | public buffer 없이 rendezvous `buffer(0)`과 suspend `emit`으로 다음 AWS 호출을 지연한다 |
| shard split/merge | 두 부모의 durable `ShardEnd`를 확인한 뒤 child를 discovery하고 child sequence를 새로 checkpoint한다 |
| unknown parent | `maxUnknownParentDiscoveries` 초과 시 root 승격 없이 `KinesisShardGraphException`으로 실패한다 |
| client가 먼저 닫힘 | 호출자 lifecycle 위반으로 SDK 예외가 발생할 수 있다. client는 consumer가 닫지 않는다 |

기본 의미는 at-least-once다. `emit` 성공과 checkpoint 저장은 동일 트랜잭션이
아니므로 process crash와 lease loss에서 중복이 발생할 수 있다. checkpoint 저장
후의 외부 side effect를 원자화하지 않으며 exactly-once를 보장하지 않는다. poison
record skip/dead-letter 정책은 호출자 collector 또는 별도 adapter의 책임이다.

`release`는 정상 완료와 cancellation의 `finally`에서
`withContext(NonCancellable)`와 `withTimeoutOrNull(leaseReleaseTimeout)`으로
호출한다. timeout·release 실패를 원래 cancellation/consumer 예외로 덮지 않으며,
payload·credential·request token은 metrics와 로그에 남기지 않는다.
`CancellationException`은 절대 일반 retry로 변환하지 않는다.

## 6. 테스트와 Floci 경계

### Floci에서 검증할 범위

`AbstractAwsTest`의 기본 `FlociServer.Launcher.floci` endpoint(고정 image
`1.6.0`)에서 Java 모듈을 먼저, Kotlin 모듈을 다음에 순차 실행한다. 각 integration
class는 `@Execution(SAME_THREAD)`와 고유 stream cleanup을 사용하고,
`FLOCI_SERVICES_KINESIS_ENABLED=true` selector를 보존한다.

Floci integration 범위:

- `ExplicitHashKey`를 각 `HashKeyRange` 안에서 사용해 실제로 서로 다른 shard ID가
  배정됐는지 `PutRecordResponse.shardId`로 확인한다.
- 소규모 single-page multi-shard, shard envelope, shard 내부 sequence ordering,
  기본 polling/tailing과 `take`/cancellation을 검증한다.
- in-memory lease/checkpoint의 acquire/renew counter, 다른 owner 거부, expiry,
  fenced save와 durable `ShardEnd`를 검증한다.
- 같은 process 재개와 제한된 restart contract를 확인하되 durable store를 쓸 때만
  at-least-once restart를 주장한다.

MockK fake + `runTest`/virtual time unit 범위:

- `ListShards` token 누적, page limit, duplicate discovery, token expiry 후 전체
  snapshot 재시작, partial graph 폐기와 bounded retry
- parent가 뒤늦게 나타나는 경우, unknown-parent timeout, `adjacentParentShardId`
  포함 두 부모 `ShardEnd` gating, duplicate launch 방지
- 정확한 `LATEST` 경계, closed shard `endingSequenceNumber`/null iterator 종료,
  iterator expiry의 local-vs-durable 복구
- lease fencing 경쟁, 독립 heartbeat·lease loss, cancellation release 보존,
  nullable sequence와 save 실패, collector/poison 전체 실패
- `buffer(0)`, `maxShardConcurrency`, `maxRecordsPerPoll`에서 느린 collector가
  다음 `GetRecords`를 앞서 호출하지 않는 backpressure

다음 항목은 pinned Floci 1.6.0 gap이며 fake/unit으로만 증명한다.

- `ListShards` 입력 token을 이어주지 않는 pagination 구현
- closed shard에도 `NextShardIterator`를 반환하는 동작
- iterator 생성 시점이 아닌 첫 `GetRecords` 시점에 평가되는 `LATEST`

Floci가 제공하지 않거나 실제 AWS에서만 의미 있는 항목은 완료 증거에서 AWS-only
N/A로 분리한다.

- 실제 24시간 retention 경과와 `ExpiredIteratorException`/trim timing
- production Kinesis quota에 따른 throttling 비율과 latency 분포
- 장시간 split/merge reshard timing, 여러 프로세스 간 네트워크 partition
- IAM, CloudWatch, KCL lease table 운영 권한과 DynamoDB conditional-write latency

실제 AWS credential을 읽는 테스트를 만들지 않는다. `localstack`은 저장소의 명시적
fallback 계약이지만 이 이슈의 기본 검증 backend로 전환하지 않는다. 실제 AWS-only
N/A와 Floci pinned-image gap을 같은 것으로 합치지 않는다.

## 7. 변경 표면과 문서 계약

- `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/`에 consumer options,
  lease/checkpoint SPI와 구현, envelope, metrics, multi-shard flow를 추가한다.
- `aws-java/src/main/kotlin/io/bluetape4k/aws/kinesis/`에 동일 개념의 Java SDK v2
  coroutine API를 추가한다. 기존 compileOnly Kinesis SDK alias와 coroutine helper를
  재사용하며 KCL이나 새 dependency는 추가하지 않는다.
- 두 모듈의 Kinesis unit/Floci test에 위 수용 기준을 고정하고 기존
  `KinesisRecordFlow*Test`를 회귀 기준으로 유지한다. Java/Kotlin consumer fixture의
  타입명·position·options·오류 parity와 Java ABI compile fixture도 추가한다.
  emulator-backed 테스트는 공유 Docker 자원을 고려해 `aws-java` 후 `aws-kotlin`
  순서로 실행한다.
- `README.md`, `README.ko.md`, `docs/manual/en|ko/modules/bluetape4k-aws-java.md`,
  `docs/manual/en|ko/modules/bluetape4k-aws-kotlin.md`에 public API, at-least-once,
  lease/checkpoint 책임, Noop 단일 프로세스 경고, consumerGroup/streamIdentity/ownerId
  구분, client 소유권과 종료, `compileOnly` 서비스 SDK 추가, 기존 `recordFlow`에서
  `consumerFlow`로의 migration 차이, Floci 1.6.0 gap과 실행 환경을 반영한다.
- 구현 lesson은 checkpoint·lease counter·dynamic discovery와 Floci/AWS-only
  경계를 재사용 가능한 규칙으로 기록한다.

새 public abstraction, dependency, Spring/Ktor adapter, KCL adapter를 추가해야 하면
이 명세와 실행계획을 먼저 갱신하고 별도 review를 받는다.

## 8. 이슈 수용 기준 매핑

| 이슈 기준 | 설계·검증 증거 |
|---|---|
| 여러 shard 독립 소비와 shard 내 순서 | `consumerFlow`, shard job의 순차 polling, semaphore/rendezvous contract, Floci explicit-hash test |
| lease loss/rebalance와 restart checkpoint | lease SPI·counter·fencing·독립 heartbeat, `KinesisLeaseLostException`, durable `ShardEnd`, inclusive checkpoint test; rebalance는 expiry takeover으로 한정 |
| 중복/유실, commit 시점, backpressure 문서화 | §5의 오류 표와 at-least-once 설명, Noop 경고, public KDoc, slow collector test |
| Floci/LocalStack fallback에서 기본 contract 검증과 AWS-only 표시 | Floci 1.6.0 sequential test matrix와 fake-only gap 목록; 실제 AWS 호출 없음 |
| 기존 단일 shard API 회귀 없음 | 기존 `recordFlow` 시그니처 보존과 모듈별 regression test |

## 9. 설계 결정과 대안

### 채택: 모듈별 저수준 Flow + 교체 가능한 SPI

기존 coroutine Flow와 SDK model을 그대로 활용하고, 영속 lease/checkpoint 구현은
호출자에게 맡긴다. 한 모듈에서 다른 SDK model을 재수출하지 않으므로 binary surface가
명확하고 KCL dependency가 없다. Floci에서 in-memory SPI와 기본 multi-shard contract를
검증할 수 있다.

### 검토했지만 채택하지 않은 대안

1. **KCL 직접 의존** — lease table, reshard, worker lifecycle을 빠르게 얻지만 KCL
   version/런타임·DynamoDB 운영 dependency가 public library에 강제되고 Kotlin/Java
   SDK parity가 깨진다. 이슈의 “KCL 필수 아님” 범위 경계를 넘으므로 제외한다.
2. **Spring Cloud Stream Binder 복제** — listener/ack/binder 설정은 제공하지만
   현재 모듈의 저수준 API 범위를 크게 넓히고 Spring 없는 consumer를 막는다. 별도
   follow-up으로 남긴다.
3. **단일 global queue에 모든 record를 적재** — 구현은 단순하지만 queue 크기와
   downstream 속도에 따라 memory가 무한히 커지고 shard별 ordering을 쉽게 잃는다.
   shard별 순차 Flow와 bounded merge를 유지한다.

## 10. 설계 DoD와 writer gate

- [x] **SPW-01** — 대상 모듈·독자·한국어 artifact 목적, 로컬 근거, 공식 URL,
  미지원/미검증 범위를 §1·§2·§6에 고정했다.
- [x] **SPW-02** — spec 계약(경계, API, data flow, 오류, compatibility, acceptance,
  DoD)을 §3–§8에 작성했다.
- [x] **SPW-03** — `bluetape-writer` Korean naturalness checklist
  `KO-01..KO-07`을 적용해 식별자·명령·URL·불확실성을 보존하고 번역투·홍보 문구를
  제거했다.
- [x] **SPW-04** — 현재 소스, #469 재사용 패턴, AWS API, Floci capability와 설계
  결정을 source-to-claim으로 대조했다. 실제 AWS 동작은 주장하지 않는다.
- [x] **SPW-05** — Markdown headings/table/code fence와 링크를 read-back했고,
  이 설계 명세가 `design` lane의 검토·승인 입력임을 기록했다.

**다음 게이트**: 6개 관점 설계 review와 사용자 설계 승인 전에는 구현·plan artifact를
시작하지 않는다.
