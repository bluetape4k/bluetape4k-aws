# 설계 명세 — DynamoDB Streams coroutine `Flow`와 checkpoint

<!-- 이슈 #469 | bluetape4k-aws -->

**상태**: 승인된 구현 전 설계 v1
**작성일**: 2026-08-26
**이슈**: [#469](https://github.com/bluetape4k/bluetape4k-aws/issues/469)
**대상 모듈**: `aws-java`, `aws-kotlin`
**검증 원칙**: 실제 AWS 계정은 사용하지 않고 `FlociServer`만 사용

## 1. 문제와 목표

현재 저장소에는 Java SDK 저수준 `DynamoDbStreamsClient`/`DynamoDbStreamsAsyncClient`
생성 도우미는 있지만, 두 SDK 모두에서 다음 기능을 재사용할 수 있는 coroutine
`Flow` primitive가 없다.

- `TrimHorizon`, `Latest`, sequence number 기반 shard 시작 위치
- `DescribeStream` pagination을 통한 shard discovery와 parent/child shard 처리
- shard별 순차 polling, 여러 shard의 bounded 병렬 처리와 downstream backpressure
- pluggable checkpoint 저장소와 재시작 시 at-least-once 의미
- iterator 만료, trim, throttling, cancellation, client lifecycle과 관측 지점

이 변경은 Kinesis multi-shard runtime이나 KCL/Spring Integration 전체 복제가 아니다.
exactly-once를 주장하지 않으며, 호출자가 idempotent 처리와 checkpoint 저장소를 선택할
수 있는 저수준 소비 primitive를 제공한다.

## 2. 현재 근거와 SDK 경계

로컬 구현을 먼저 재사용한다.

- `aws-kotlin/.../kinesis/KinesisRecordFlow.kt`의 cold `flow {}`, `ensureActive`,
  cancellation 재전파, empty-batch backoff, full-jitter retry 구조
- `aws-kotlin/.../kinesis/KinesisRecordFlowOptions.kt`의 constructor validation과
  직렬화 가능한 옵션 패턴
- `aws-java/.../dynamodb/DynamoDbStreamsAsyncClientSupport.kt`의
  `ShutdownQueue` 등록과 endpoint/credential/http client 생성 패턴
- `FlociServer.Launcher.floci`를 사용하는 `AbstractAwsTest` 테스트 기반

AWS SDK 공식 API는 DynamoDB Streams에 `ListStreams`, `DescribeStream`,
`GetShardIterator`, `GetRecords` 네 operation을 제공한다. Java SDK v2의 generated
model은 `software.amazon.awssdk.services.dynamodb.model` 패키지에 있으며,
Kotlin SDK는 `aws.sdk.kotlin.services.dynamodbstreams`와
`aws.sdk.kotlin.services.dynamodbstreams.model`을 사용한다.

외부 근거:

- [AWS SDK for Kotlin DynamoDB Streams API](https://docs.aws.amazon.com/sdk-for-kotlin/api/latest/dynamodbstreams/)
- [AWS SDK for Java v2 DynamoDbStreamsAsyncClient](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/dynamodb/streams/DynamoDbStreamsAsyncClient.html)
- [Floci DynamoDB service support](https://floci.io/floci/services/dynamodb/)
- [Floci repository](https://github.com/floci-io/floci)

AWS API 사실(24시간 Streams retention, iterator TTL 15분, `GetRecords` 최대
1,000건/1MB, `TrimmedDataAccessException`)은 public KDoc과 테스트 계약에 반영한다.
실제 AWS에서만 측정 가능한 장기 retention·실제 throttling timing은 Floci capability
검증 대상이 아니며 AWS-only 계약으로 명시한다.

## 3. 공개 API 설계

각 SDK가 노출하는 model 타입이 다르므로 `aws-java`와 `aws-kotlin`에 동일한 이름의
모듈별 타입을 둔다. 한 모듈의 AWS model을 다른 모듈의 공개 API로 재수출하지 않는다.

### 3.1 시작 위치

각 모듈의 `DynamoDbStreamsStartingPosition`은 다음 네 variant만 제공한다.

| variant | 의미 |
|---|---|
| `TrimHorizon` | 보존 중인 가장 오래된 record부터 읽음 |
| `Latest` | iterator를 얻은 뒤 기록된 record부터 읽음 |
| `AtSequenceNumber(value)` | 지정 sequence number를 포함해 읽음 |
| `AfterSequenceNumber(value)` | 지정 sequence number 다음부터 읽음 |

sequence number variant는 `requireNotBlank`로 생성·역직렬화 모두 검증한다.
DynamoDB Streams API에는 Kinesis의 `AtTimestamp`가 없으므로 새 timestamp variant를
발명하지 않는다.

### 3.2 polling 옵션과 관측

`DynamoDbStreamsRecordFlowOptions`는 다음을 검증한다.

- `batchLimit`: 1..1,000
- `pollInterval`: 200ms 이상(샤드당 `GetRecords` 5회/초 계약을 보수적으로 준수)
- `emptyBackoff`: `pollInterval` 이상
- iterator/throttle retry 횟수: 0 이상
- `initialThrottleBackoff`/`maxThrottleBackoff`: 양수이고 상한이 시작값 이상
- `maxShardConcurrency`: 1 이상
- `maxDescribePages`: 1 이상

Micrometer 의존성을 추가하지 않고 `DynamoDbStreamsFlowMetrics` 콜백을 선택적으로
받는다. 기본값은 no-op이며 callback은 record payload를 기록하지 않는다. 최소 이벤트는
shard 시작/완료, batch 크기, checkpoint 저장, retry이다. 관측 callback 예외는 소비
경로의 의미를 바꾸지 않도록 library가 삼키지 않고 호출자에게 전파한다.

### 3.3 checkpoint와 record envelope

각 모듈에 다음 계약을 둔다.

```kotlin
interface DynamoDbStreamsCheckpointStore {
    suspend fun load(streamArn: String, shardId: String): String?
    suspend fun save(streamArn: String, shardId: String, sequenceNumber: String)
}

data class DynamoDbStreamsShardRecord(
    val streamArn: String,
    val shardId: String,
    val record: <SDK-specific DynamoDB Streams Record>,
)
```

checkpoint 저장소는 Flow가 소유하거나 닫지 않는다. 기본값은 checkpoint를 사용하지
않는 no-op store이고, 테스트용 in-memory store는 public contract를 단순하게 검증할
수 있도록 제공한다. 호출자가 저장소를 제공하면 `load`는 shard Flow가 시작될 때 한 번
호출하고 `save`는 각 record의 `emit` 반환 뒤 순차적으로 호출한다.

### 3.4 Flow entry point와 소유권

- `recordFlow(streamArn, shardId, position, options, checkpointStore, metrics)`
  는 한 shard의 SDK `Record`를 내보내는 cold `Flow`다.
- `shardRecordFlow(streamArn, position, options, checkpointStore, metrics)`는
  `DescribeStream` pagination으로 발견한 shard를 `flatMapMerge`의
  `maxShardConcurrency` 범위에서 소비하고 `DynamoDbStreamsShardRecord` envelope를
  내보낸다.
- 한 shard 내부의 record 순서는 `GetRecords` 응답 순서와 동일하다. 서로 다른 shard
  사이에는 전역 순서를 약속하지 않는다.
- `DescribeStream`으로 전체 페이지를 읽어 `parentShardId -> children` graph와 root
  shard를 만든다. 각 root의 Flow는 parent shard가 `nextShardIterator == null`로
  끝난 뒤에만 child tree를 재귀적으로 소비한다. 열린 parent를 기다리는 동안 child를
  먼저 읽지 않으며, 동일 shard ID는 set으로 중복 소비하지 않는다.
- Flow extension에 주입된 client는 호출자 소유이며 Flow가 닫지 않는다. 각 모듈은
  `withDynamoDbStreamsClient`/`withDynamoDbStreamsAsyncClient`를 제공해 client와 내부
  HTTP 자원을 `finally`에서 닫는다. `ShutdownQueue` 등록은 JVM 종료 안전망이지 Flow
  수명 소유권을 바꾸지 않는다.

## 4. 처리 흐름과 backpressure

1. `shardRecordFlow`가 `DescribeStream`을 `lastEvaluatedShardId`까지 페이지 단위로
   읽고 shard graph와 root 목록을 만든다. `maxDescribePages`에 도달했는데 다음
   페이지가 남으면 누락을 막기 위해 예외를 전파한다.
2. 각 shard consumer는 checkpoint를 조회한 뒤 checkpoint가 있으면
   `AtSequenceNumber(checkpoint)`로 iterator를 얻는다. 이 inclusive resume은
   저장 직전 장애에서 누락을 방지하고 정상 재시작 시 마지막 record가 한 번 더 나올
   수 있음을 명확히 한다.
3. 한 shard에서는 `GetShardIterator` 한 개와 `GetRecords` 한 개만 순차적으로
   활성화한다. batch를 emit한 뒤 checkpoint를 저장하고 `pollInterval` 또는
   empty-batch `emptyBackoff`를 기다린다.
4. 여러 root shard tree는 `flatMapMerge(concurrency = maxShardConcurrency)`로
   제한한다. 한 root tree 안에서 child는 parent 완료 뒤 순차적으로 읽는다. 별도
   무제한 channel/buffer를 만들지 않아 느린 collector의 backpressure가 각 shard
   Flow까지 전파된다.
5. iterator가 만료되면 마지막 checkpoint를 inclusive하게 다시 읽는다. `Latest`에서
   아직 emit한 record와 checkpoint가 없다면 새 `Latest` iterator가 조용히 구간을
   건너뛸 수 있으므로 즉시 exception을 전파한다.
6. `TrimmedDataAccessException`은 24시간 보존 창을 벗어난 데이터이므로
   `TrimHorizon`으로 자동 fallback하지 않고 즉시 전파한다.
7. retry 가능한 SDK service exception만 full-jitter exponential backoff로 제한된
   횟수만큼 재시도한다. `CancellationException`은 가장 먼저 다시 던진다.

## 5. 실패·복구·의미론

| 상황 | 계약 |
|---|---|
| `CancellationException` | retry/delay보다 먼저 재전파하고 child coroutines를 취소한다 |
| `ExpiredIteratorException` | 마지막 checkpoint를 inclusive하게 재조회; 한도 초과 시 전파 |
| `TrimmedDataAccessException` | 데이터 손실 위험 때문에 fallback 없이 전파 |
| retryable throttling/service error | full-jitter backoff + shard별 retry budget; 초과 시 전파 |
| non-retryable service error | 즉시 전파 |
| checkpoint `save` 실패 | Flow를 실패시키며 in-memory 위치를 전진시키지 않음 |
| collector가 느림 | `emit`에서 중단되어 다음 SDK 호출을 하지 않음 |
| client가 먼저 닫힘 | caller lifecycle 위반으로 SDK exception이 발생할 수 있음; helper 사용 권장 |
| shard end | `nextShardIterator == null`이면 정상 완료, child를 discovery queue에 추가 |

기본 semantics는 at-least-once다. `emit(record)`가 정상 반환된 뒤에만 checkpoint를
저장하며, checkpoint에는 마지막으로 전달한 sequence number를 기록한다. 재시작은
그 값을 `AtSequenceNumber`로 재생하므로 checkpoint 저장과 process side effect의
원자성을 보장하지 않는다. 따라서 정상 재시작에도 마지막 record가 중복될 수 있고,
checkpoint 저장 실패 시 중복 범위는 마지막으로 성공적으로 저장된 checkpoint 이후의
batch다. exactly-once는 제공하지 않는다.

## 6. Floci 검증과 AWS-only 경계

`FlociServer.Launcher.floci`를 사용하는 테스트를 모듈별로 순차 실행한다.

Floci에서 검증할 범위:

- DynamoDB table stream 활성화와 stream ARN 조회
- `ListStreams`/`DescribeStream` pagination
- `GetShardIterator`의 네 시작 위치
- `GetRecords`의 record envelope, empty batch, shard end
- 여러 shard discovery와 checkpoint 재시작의 local behavior
- client helper의 close/finally와 cancellation

AWS-only로 남기고 테스트에서는 N/A로 명시할 범위:

- 24시간 retention 경과를 기다리는 실제 `TrimmedDataAccessException` timing
- 실제 서비스 throttling quota와 retry latency 분포
- production resharding의 split/merge timing 및 장시간 open-shard lifecycle

N/A는 실제 AWS 호출을 하지 않았다는 증거와 Floci가 제공하는 operation capability
증거를 함께 기록한다. LocalStack을 기본 backend로 사용하거나 AWS credential을
읽는 테스트는 추가하지 않는다.

## 7. 변경 표면과 문서 계약

- `gradle/libs.versions.toml` 및 root dependency-management에
  `aws.sdk.kotlin:dynamodbstreams` alias를 추가한다. Java Streams는 현재
  `software.amazon.awssdk:dynamodb` artifact의 generated classes를 재사용하므로
  별도 `aws2-dynamodbstreams` alias를 만들지 않는다.
- `aws-java`와 `aws-kotlin`의 source/test에 public KDoc, unit test, Floci capability
  test를 추가한다.
- `README.md`와 `README.ko.md`의 서비스 표와 의존성 예제를 함께 갱신하고,
  `docs/manual/en|ko`에는 Flow/checkpoint와 Floci-only 검증 경계를 연결한다.
- 구현 후 lesson note에 재사용 가능한 checkpoint/resharding·emulator 결정을
  기록한다.

## 8. 수용 기준 매핑

| 이슈 수용 기준 | 설계·검증 증거 |
|---|---|
| 여러 shard 독립 소비 + 순서 | shard별 순차 Flow, bounded `flatMapMerge`, unit/Floci test |
| checkpoint 실패/재시작 명확성 | inclusive `AtSequenceNumber`, save-after-emit, failure test와 KDoc |
| Floci/AWS-only 분리 | Floci capability test + 명시적 N/A 표 |
| blocking 경계·client close | async SDK 사용, Kotlin helper `useSafe`, Java `finally`, lifecycle test |
| retry/cancellation/metrics | exception matrix, virtual-time unit test, metrics callback test |

구현은 이 명세와 승인된 실행계획에 없는 public abstraction이나 dependency를 추가하지
않는다. 변경이 이 계약을 넓히면 먼저 spec/plan review를 갱신한다.
