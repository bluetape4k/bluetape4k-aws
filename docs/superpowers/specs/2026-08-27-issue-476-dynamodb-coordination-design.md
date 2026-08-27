# #476 DynamoDB coroutine coordination primitive 설계

<!-- 이슈 #476 | bluetape4k-aws -->

**상태**: 설계 승인됨 — 사용자 승인 메시지 `승인` 확인
**작성일**: 2026-08-27
**이슈**: [#476](https://github.com/bluetape4k/bluetape4k-aws/issues/476)
**대상 모듈**: `aws-kotlin`
**검증 원칙**: 실제 AWS 계정은 사용하지 않고 `FlociServer`만 사용

## 1. 문제와 목표

`aws-kotlin`에는 DynamoDB 요청 DSL과 Streams/Kinesis용 process-local lease·checkpoint
타입이 있지만, 여러 coroutine worker가 하나의 이름을 원자적으로 점유하고 갱신하는
영속 primitive가 없다. 애플리케이션마다 조건식, 만료 처리, stale writer 방어를
다르게 작성하면 lock 유실과 오래된 owner의 쓰기가 섞일 수 있다.

이번 변경은 다음 두 계약을 제공한다.

- DynamoDB conditional write를 사용하는 coroutine `DistributedLock`과
  `LockLease` fencing token
- 같은 테이블을 사용할 수 있는 `String` 값 기반 `MetadataStore`와 조건부 put/delete

lock은 owner·lease duration·renew(heartbeat)·release를 제공한다. 만료된 항목은 새
fencing token으로 takeover할 수 있고, 이전 token의 renew/release는 실패한다.
메타데이터는 선택적 TTL을 epoch seconds로 저장하며 논리적으로 만료된 값은 읽지 않는다.

다음은 이슈 범위에서 제외한다.

- Spring Integration `LockRegistry` 또는 channel adapter 복제
- exactly-once, 분산 트랜잭션, clock synchronization 보장
- DynamoDB Streams/Kinesis consumer와의 checkpoint 자동 통합
- Java SDK v2 동등 API, 새 module/dependency, production telemetry
- 실제 AWS 계정의 conditional smoke와 운영 quota 측정 (사용자 제약으로 N/A)

## 2. 현재 근거와 재사용 경계

| 근거 | 확인한 사실과 재사용 |
| --- | --- |
| `aws-kotlin/.../kinesis/KinesisLeaseStore.kt` | `acquire`/`renew`/`release` suspend SPI와 `null` lease 의미를 유지한다. |
| `aws-kotlin/.../kinesis/KinesisLease.kt` | owner와 양의 counter를 immutable `Serializable` token으로 검증하는 패턴을 재사용한다. |
| `aws-kotlin/.../kinesis/InMemoryKinesisLeaseStore.kt` | 만료·takeover·owner fencing의 의미를 DynamoDB 조건식으로 옮긴다. |
| `aws-kotlin/.../dynamodb/model/AttributeValue.kt` | `String`·`Long`을 `AttributeValue.S/N`으로 변환하는 기존 converter를 사용한다. |
| `aws-kotlin/.../dynamodb/DynamoDbClientSupport.kt` | client/HTTP engine lifecycle은 호출자 소유로 두고 `withDynamoDbClient`를 재사용한다. |
| `aws-kotlin/src/test/.../AbstractAwsTest.kt` | 기본 emulator가 `FlociServer.Launcher.floci`이며 LocalStack은 명시적 fallback이다. |
| Issue #469/#470 docs | checkpoint·lease의 at-least-once와 fencing 경계를 재사용하되 Streams/Kinesis wiring은 넣지 않는다. |

AWS Kotlin SDK의 `PutItemRequest`/`UpdateItemRequest`/`DeleteItemRequest`는
`conditionExpression`, expression name/value map, conditional failure exception을
제공한다. DynamoDB 조건부 put의 `attribute_not_exists`는 기존 항목 덮어쓰기를 막고,
TTL은 epoch seconds 속성으로 설정한다.

- [AWS SDK for Kotlin `PutItemRequest`](https://docs.aws.amazon.com/sdk-for-kotlin/api/latest/dynamodb/aws.sdk.kotlin.services.dynamodb.model/-put-item-request/)
- [DynamoDB condition expressions](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.ConditionExpressions.html)
- [DynamoDB TTL](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/time-to-live-ttl-before-you-start.html)

## 3. 대안과 선택

### 대안 A — `AttributeValue` 저수준 공개 SPI

lock과 metadata 모두 DynamoDB `AttributeValue` map을 그대로 노출한다. 서비스 표현력은
높지만 SDK model이 public contract에 퍼지고, metadata 직렬화·타입 안전성을 각 소비자가
책임져야 한다. 기존 model DSL과 중복되는 API도 생긴다.

### 대안 B — `String` metadata와 immutable `LockLease` (선택)

metadata value는 `String`으로 고정하고, lock 결과는 logical key·owner·fencing token·만료
epoch seconds를 가진 도메인 값 객체로 반환한다. DynamoDB model은 구현 경계에 남고,
JSON/bytes가 필요하면 애플리케이션이 codec을 선택한다. Floci 계약 테스트와 KDoc이
짧고 명확하며 기존 Kinesis lease 의미와도 맞는다.

### 대안 C — 라이브러리 제공 JSON codec

범용 value codec과 JSON dependency를 추가해 임의 객체를 저장한다. 사용성은 좋아 보이지만
새 dependency·schema migration·직렬화 호환성 책임이 생긴다. #476의 conditional storage
계약보다 범위가 크므로 선택하지 않는다.

대안 B를 선택한다. 이 문서의 `String` 선택은 앞선 설계 대화에서 별도 응답이 없었던
부분에 대해 정한 실행 가정이며, 공개 API의 SDK 결합을 최소화한다.

## 4. 공개 API와 물리 스키마

새 public type은 모두 `io.bluetape4k.aws.kotlin.dynamodb.coordination` 패키지에
둔다. 기존 `dynamodb.model`의 request DSL과 이름이 겹치지 않도록 service adapter와
domain contract를 분리한다.

### 4.1 이름·테이블 스키마

`DynamoDbCoordinationSchema`는 다음을 구성한다.

- `tableName`: 기존 DynamoDB table 이름
- `partitionKeyAttributeName`: String partition key 속성명 (기본 `id`)
- `namespace`: 하나의 table을 여러 애플리케이션이 공유할 때의 namespace
- `ownerAttributeName`, `expiresAtAttributeName`, `fencingTokenAttributeName`,
  `valueAttributeName`, `ttlAttributeName`: lock/metadata 속성명과 metadata 전용 TTL
  속성명 (기본 `ttlEpochSeconds`)
- `DynamoDbCoordinationNameResolver`: `(namespace, kind, logicalKey)`를 충돌 없이
  물리 partition-key 문자열로 바꾸는 함수. 기본 resolver는 각 조각의 길이를 함께
  인코딩해 delimiter가 key 안에 들어가도 tuple 경계가 충돌하지 않게 한다. custom
  resolver는 **전체 tuple** `(namespace, kind, logicalKey)`에 대해 결정적·injective여야
  하며, namespace 간 또는 `LOCK`/`METADATA` kind 간 결과 충돌도 허용하지 않는다.
  기본 resolver만 이 보장을 library 내부에서 구성적으로 제공한다. custom resolver의
  전체 입력 공간 injectivity는 library가 사후 검출할 수 없으므로 caller 책임이며,
  충돌 결과는 undefined behavior로 문서화한다. library는 custom 결과의 blank/control/
  UTF-8 상한만 검증한다.

```kotlin
fun interface DynamoDbCoordinationNameResolver {
    fun resolve(namespace: String, kind: DynamoDbCoordinationEntryKind, logicalKey: String): String
}

class DynamoDbCoordinationOptions(
    val defaultLeaseDuration: Duration = 60.seconds,
    val consistentRead: Boolean = true,
    val clock: Clock = Clock.systemUTC(),
)
```

`DynamoDbCoordinationEntryKind`는 `LOCK`과 `METADATA`를 구분한다. 따라서 같은 table에서
같은 logical key를 lock과 metadata에 동시에 사용해도 item이 섞이지 않는다. schema는
빈 이름·control character·중복 attribute name을 거부하고 resolver가 빈 key를 반환하면
실패시킨다. tableName도 DynamoDB table-name 문자·길이 규칙을 사전 검증한다. namespace,
logical key, ownerId와 각 attribute name은 UTF-8 기준 256-byte
이내여야 하며, metadata value는 item overhead를 남기기 위해 UTF-8 350,000-byte 이내로
제한한다. resolver 결과에도 non-blank·control character 금지와 DynamoDB String
partition-key의 2,048-byte 한도를 직접 적용한다. `Duration.INFINITE`, 1초 미만 또는
fractional second 값은 거부하고, lease/TTL은
유한한 1초 단위이며 최대 365일이다. epoch-second 덧셈 overflow도 요청 전에 거부한다.
`DynamoDbCoordinationOptions`는 실행 시 주입하는 immutable configuration이며
`Serializable` 계약을 제공하지 않는다. custom `Clock`은 테스트/runtime 전용이고 durable
configuration에 직렬화하지 않는다.

구현 상수는 `MAX_IDENTIFIER_UTF8_BYTES = 256`,
`MAX_METADATA_VALUE_UTF8_BYTES = 350_000`, `MAX_COORDINATION_DURATION = 365.days`로
고정한다. 이 상한은 DynamoDB 400KB item 한도와 expression/request overhead를 남기기 위한
보수적 경계이며, 초과 입력은 네트워크 호출 전에 `IllegalArgumentException`으로 거부한다.

table 생성/TTL 활성화는 caller가 담당한다. schema는 sort key를 요구하지 않으며, sort-key
table은 이 primitive의 지원 범위가 아니다. `ttlAttributeName`(기본 `ttlEpochSeconds`)은
metadata에만 기록하는 별도 속성이다. DynamoDB TTL은 이 속성에만 활성화해야 하며
`expiresAt`에는 활성화하지 않는다. lock item은 release 후 fencing counter를 보존해야 하므로
물리 TTL/자동 삭제 대상이 아니다. 만료 lock row를 임의로 삭제하면 token이 재사용될 수
있으므로 별도 durable counter 설계 없이 삭제하지 않는다.

물리 item 예시는 다음과 같다.

| kind | partition key | attributes |
| --- | --- | --- |
| `LOCK` | resolver 결과 | `ownerId`, `expiresAt`, `fencingToken` |
| `METADATA` | resolver 결과 | `value`, 선택적 `expiresAt`, 선택적 `ttlEpochSeconds` |

`expiresAt`는 lock의 correctness 판단과 metadata logical expiry에 모두 쓰며, TTL 자체의
대상은 아니다. metadata가 TTL을 요청하면 `ttlEpochSeconds`에도 같은 epoch seconds를
기록한다. DynamoDB TTL 삭제는 비동기 정리일 뿐 takeover·read 결과를 결정하지 않는다.

최소한의 사용 예는 다음과 같다. table은 `partitionKeyAttributeName`과 동일한 String
partition key를 caller가 먼저 생성한다.

```kotlin
withDynamoDbClient(endpointUrl, region, credentialsProvider) { client ->
    val schema = DynamoDbCoordinationSchema(tableName = "coordination")
    val lock = DynamoDbDistributedLock(client, schema)
    val lease = lock.tryAcquire("orders", "worker-1", 30.seconds)
    if (lease != null) {
        var processingFailure: Throwable? = null
        try {
            processOrders()
        } catch (error: Throwable) {
            processingFailure = error
            throw error
        } finally {
            val releaseFailure = try {
                val released = withContext(NonCancellable) {
                    withTimeout(5.seconds) { lock.release(lease) }
                }
                if (!released) IllegalStateException("lease was lost before release") else null
            } catch (error: Throwable) {
                error
            }
            if (releaseFailure != null) {
                processingFailure?.addSuppressed(releaseFailure) ?: throw releaseFailure
            }
        }
    }
}
```

`ownerId`는 deployment/instance 단위로 caller가 유일하게 지정한다. `LockLease`를
직렬화해 외부 시스템으로 전달할 때는 token을 신뢰할 수 없는 입력으로 취급하고,
downstream write가 현재 token을 다시 조건으로 검사하도록 구성한다.

### 4.2 Lock contract

```kotlin
interface DistributedLock {
    suspend fun tryAcquire(key: String, ownerId: String, leaseDuration: Duration): LockLease?
    suspend fun renew(lease: LockLease, leaseDuration: Duration): LockLease?
    suspend fun heartbeat(lease: LockLease, leaseDuration: Duration): LockLease?
    suspend fun release(lease: LockLease): Boolean
}

data class LockLease(
    val key: String,
    val ownerId: String,
    val fencingToken: Long,
    val expiresAtEpochSeconds: Long,
    val tableName: String,
    val partitionKeyAttributeName: String,
    val namespace: String,
    val physicalKey: String,
    val scopeId: String,
) : Serializable
```

`scopeId`는 table/partition-key/namespace와 lock attribute 이름을 length-prefixed
canonical encoding으로 묶은 adapter scope 식별자다. 이는 accidental schema mismatch
방지용 값일 뿐 account, region,
endpoint, IAM 또는 인증 경계가 아니다. deployment generation을 namespace에 포함하고,
필요하면 table/권한을 분리하는 책임은 caller에게 있다. coordinator는 renew/release 전에
lease의 `scopeId`, table, namespace, resolved physical key를 현재 schema와 비교한다. 불일치하거나 lease의
identifier/token/expiry invariant가 깨지면 DynamoDB를 호출하지 않고 `IllegalArgumentException`
또는 `IllegalStateException`으로 거부한다. `LockLease`는 `serialVersionUID`와
`readObject` 재검증을 구현하며, 외부에서 받은 임의 `ObjectInputStream`을 신뢰하지 않는다는
경고를 manual에 둔다.

실제 구현은 `DynamoDbDistributedLock(client, schema, options)`이며 `heartbeat`는
`renew`와 같은 조건부 연장 동작이다. `options.defaultLeaseDuration`을 사용하는
편의 overload를 제공하되, SPI의 핵심 메서드는 호출자가 duration을 명시한다.

- acquire는 bounded two-phase `UpdateItem`을 사용한다. 첫 요청은
  `attribute_not_exists(#partitionKey)` 조건으로 새 item에만 적용하고, 성공 시
  owner·만료 시각·`if_not_exists(#fencingToken, :zero) + :one`을 기록한다.
  기존 item으로 조건이 실패하면 `ReturnValuesOnConditionCheckFailure.AllOld`를 읽어
  검증한다. active lock이면 `null`, malformed item이면 `IllegalStateException`, token이
  `Long.MAX_VALUE`이면 `IllegalStateException("fencing token exhausted")`를 반환한다.
  검증 가능한 expired item에 대해서만
  관찰한 owner/expiry/token을 모두 equality condition으로 고정한 두 번째 `UpdateItem`을
  한 번 시도한다. 이 두 번째 조건이 race로 실패하면 `null`이며 더 반복하지 않는다.
  따라서 새/active 경로는 SDK 호출 1회, expiry takeover 경로는 최대 2회이고 pre-read나
  unbounded retry는 없다. 두 단계 모두 `ReturnValue.AllNew`로 새 token을 읽는다.
  active owner의 재진입은 acquire가 아니라 renew를 사용한다.
- renew/heartbeat: lease에 담긴 owner·fencing token·기존 expiry가 현재 item과 모두
  equality로 일치하고 `expiresAt > now`일 때만 만료 시각을 갱신한다. 따라서 fractional
  또는 다른 scope의 expiry는 성공 경로에 들어오지 않는다. 조건 실패 시 유효한 stale
  lease면 `null`, malformed item이면 `IllegalStateException`이다.
- heartbeat는 renew의 명시적 alias이며 동일한 조건·호출 수·반환 규칙을 사용한다.
- release: lease에 담긴 owner·token·기존 expiry가 현재 item과 모두 equality로 일치하고
  `expiresAt > now`일 때만 `UpdateItem`으로 owner를 제거하고 `expiresAt = now`로 바꾼다.
  `fencingToken`은 보존해 release 후 재획득에서도 token이 증가하도록 한다.
  `ReturnValue.AllOld`로 이전 item을 받아 성공은 `true`다. 조건 실패 시 `AllOld`로
  stale/만료 lease면 `false`, malformed item이면 `IllegalStateException`을 반환한다.
  stale/만료 lease 또는 이전 expiry를 가진 lease가 새 owner의 item을 갱신하거나 지울 수 없다.
  release는 lock item을 물리 삭제하지 않는다.
- `LockLease`의 token은 downstream side effect나 checkpoint 저장 시 함께 검사해야 한다.
  library가 외부 resource의 fencing을 자동으로 수행하지는 않는다.

`ownerId`와 logical key는 bounded non-blank identifier이며, duration은 양수이고 최소
1초 이상이어야 한다. 만료 epoch 계산 overflow는 예외로 처리한다. `Clock`은
`DynamoDbCoordinationOptions`로 주입하며 기본값은 UTC system clock이다. 구체 구현체는
다음 convenience overload를 제공한다: `tryAcquire(key, ownerId)`,
`renew(lease)`, `heartbeat(lease)`는 `options.defaultLeaseDuration`을 사용한다. SPI의
명시적 duration 메서드는 그대로 유지하고, heartbeat가 renew alias라는 사실을 KDoc에
고정한다.

### 4.3 Metadata contract

```kotlin
interface MetadataStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String, ttl: Duration? = null)
    suspend fun putIfAbsent(key: String, value: String, ttl: Duration? = null): Boolean
    suspend fun remove(key: String): Boolean
    suspend fun removeIfValue(key: String, expectedValue: String): Boolean
}
```

`DynamoDbMetadataStore(client, schema, options)`은 `GetItem`에
`consistentRead = options.consistentRead`를 설정한다. `get`은 `expiresAt <= now`인
metadata를 논리적으로 없는 값으로 반환하지만 TTL 삭제를 기다리지 않는다. `put`은
값과 선택적 TTL을 저장하고 TTL이 없으면 이전 `expiresAt`과 `ttlEpochSeconds`를 모두
제거한다. `put`은 명시적 overwrite API이므로 기존 metadata를 새 String 값으로 교체한다.

- `putIfAbsent`: 첫 PutItem은 key 부재 조건만 사용한다. 조건 실패 시 `AllOld`로
  metadata schema를 검증해 non-expiring/active item이면 `false`, malformed item이면
  `IllegalStateException`을 반환한다. 검증 가능한 `expiresAt <= now` item에 대해서만
  관찰한 value/expiry를 equality condition으로 고정한 두 번째 PutItem을 한 번 시도한다.
  이때도 race 실패는 `false`이며 overwrite loop는 없다.
- `remove`: 첫 DeleteItem은 key 부재 조건만 사용해 빈 key를 한 번의 no-op으로 처리한다.
  기존 item이 반환되면 schema와 logical expiry를 검증한 뒤, valid item에 대해서만 관찰한
  value/expiry를 equality condition으로 고정한 두 번째 DeleteItem을 한 번 시도한다.
  이전 item이 논리적으로 존재하면 `true`, 만료됐거나 없으면 `false`다. malformed item은
  두 번째 mutation 전에 `IllegalStateException`으로 표면화한다.
- `removeIfValue`: 위와 같은 bounded two-phase DeleteItem을 사용하되 첫 `AllOld`의 value가
  expected와 일치할 때만 두 번째 equality delete를 시도한다. 논리적으로 존재하고 값이
  일치하면 `true`, 만료됐거나 없으면 `false`이며, 동일 값 재기록에 대한 ABA 방어/ownership
  용도가 아니다. metadata CAS가 필요하면 caller-managed unique version를 value에 넣거나
  `DistributedLock`의 fencing token을 사용한다.

모든 conditional request는 expression 문자열에 caller 입력을 보간하지 않는다. 고정된
expression template과 `#alias`/`:value` name-value map만 사용하고, partition key·owner·
value·token은 attribute value map으로 전달한다. 조건 실패 응답의 `AllOld`는 추가
`GetItem` 없이 active/expired/malformed 상태를 판별하기 위한 진단 데이터다.

metadata API는 JSON/bytes codec을 제공하지 않는다. `String` payload의 schema와 escaping은
호출자가 소유한다.

lock item은 partition key, String `ownerId`(release 상태에서는 생략 가능), 0 이상 정수
Number `expiresAt`, 양의 정수 Number `fencingToken`을 가져야 한다. metadata item은
partition key, String `value`, 없거나 0 이상 정수 Number인 `expiresAt`/`ttlEpochSeconds`를 가져야
한다. 필수 속성 누락·wrong type·음수/비정수 값은 active/expired 여부와 무관하게
`IllegalStateException`으로 fail-closed 한다. `AllOld` 진단이 지원되지 않는 emulator/API
경로에서는 malformed 판별을 위해 별도 pre-read를 추가하지 않고 해당 contract test를
지원 gap으로 기록한다.

### 4.4 호환성과 마이그레이션

새 item은 `namespace + kind` 접두어를 사용하므로 기존 애플리케이션 item을 덮어쓰지
않는다. 기존 table을 재사용하려면 String partition key의 attribute name과 table key
형식이 schema와 일치해야 하며, sort-key table은 새 table 또는 별도 adapter가 필요하다.
TTL을 활성화해도 lock/metadata의 logical expiry 계약은 변하지 않는다. TTL 속성은
`ttlEpochSeconds`로 별도 migration한 뒤 metadata에만 사용한다. 이전 namespace/key
형식에서 이동할 때 metadata는 두 schema를 읽는 migration adapter를 호출자 쪽에 둘 수
있지만, lock은 split-brain을 막기 위해 worker를 drain/quiesce한 뒤 하나의 authoritative
schema로 전환해야 한다. 이 primitive는 자동 rename·backfill·dual-write를 수행하지 않는다.

## 5. 처리·오류·lifecycle

1. `DynamoDbDistributedLock`/`DynamoDbMetadataStore`는 주입된 client의 suspend member를
   호출한다. blocking call이나 자체 thread를 만들지 않는다.
2. `ConditionalCheckFailedException`만 contract 결과(`null`/`false`)로 매핑한다.
   `CancellationException`은 catch하지 않고 재전파한다.
3. throttling과 transport retry/backoff는 AWS Kotlin SDK client 설정에 위임한다. 조건부
   실패에는 library 재시도 loop를 적용하지 않아 경쟁 결과가 바뀌지 않게 한다.
4. client와 HTTP engine은 호출자 소유다. 짧은 scope에는 기존 `withDynamoDbClient`를
   사용하고, coordinator가 client를 닫지 않는다.
5. cancellation 중에는 SDK suspend 호출의 취소가 그대로 전파되며 local lock state를
   기록하지 않는다. retry/backoff 중 취소와 transport/throttling 예외도 adapter가
   삼키거나 재시도하지 않고 전파한다. retry attempt 수·request timeout의 bounded
   종료는 caller가 구성한 SDK client policy의 책임이며, adapter는 무한 retry를 만들지
   않는다. timeout 뒤 성공 여부를 알 수 없는 acquire는 작업을 시작하지 말고 lease
   expiry까지 대기하거나, 호출자가 별도 진단 read를 수행한 뒤 다시 시도한다. active
   lease에 대한 즉시 `null`은 성공 증거가 아니다. 취소된 작업의 lease cleanup은 caller가
   `withContext(NonCancellable)`과 bounded `withTimeout` 안에서 release하고, 원래
   `CancellationException`을 release 오류가 가리지 않도록 suppressed exception으로
   보존한다.

각 logical operation은 adapter 관점에서 fast path SDK 호출 1회이며, 이미 존재하는
expired item의 takeover/conditional delete처럼 `AllOld`로 관찰한 경우에만 equality-guarded
두 번째 호출을 최대 1회 만든다. SDK client가 자체 retry를 설정할 수 있으므로 logical
호출 수와 실제 network attempt 수는 다르다. adapter는 별도 GetItem pre-read, polling,
hidden background heartbeat, local mutex 또는 unbounded retry state를 만들지 않는다.

clock skew는 caller clock과 DynamoDB item의 epoch 비교에 영향을 준다. NTP/배포 clock
정합성은 caller 책임이며 library는 분산 clock을 보정하지 않는다. skew가 있어도 이전
lease의 owner+fencing token 조건은 새 token item을 갱신하거나 release할 수 없다. 다만
downstream이 fencing token을 검사하지 않으면 stale side effect까지 막을 수 없다는
경계를 KDoc과 manual에 명시한다. namespace/ownerId는 IAM 또는 인증 경계가 아니며,
access-control lock으로 사용하지 않는다. least-privilege IAM과 필요 시 table 분리를
호출자가 구성하고, downstream side effect는 일반 read 후 write가 아니라 fencing token을
포함한 원자적 conditional write로 보호한다. metadata에는 secret/PII를 저장하지 말고
Secrets Manager/KMS 등 전용 저장소를 사용한다. 로그와 예외에는 metadata value, ownerId,
fencing token을 포함하지 않는다.

## 6. 테스트·Floci 검증 계약

### Unit

- schema/name resolver가 namespace·kind·delimiter가 포함된 key를 충돌 없이 만들고,
  기본 resolver의 전체 tuple injectivity를 보장한다. custom resolver는 caller 계약으로
  남기며 library는 blank/control/UTF-8 상한만 검증한다. resolver를 한 logical operation에서
  한 번만 호출해 caller가 결정성을 유지할 책임을 진다. 임의 custom collision은 library가
  사후 검출하거나 예외화한다고 주장하지 않는다.
- `LockLease`의 identifier·expiry/token/scope invariant와 serialization, options의
  기본값·invariant를 검증한다. duration 검증은 options와 acquire/metadata TTL 입력에서
  별도로 수행한다.
- release 후 재획득과 TTL 경과 후 재획득에서도 fencing token이 단조 증가하고, token
  `Long.MAX_VALUE`에서 명시적으로 exhaustion을 표면화하는지 검증한다.
- MockK `DynamoDbClient`로 acquire/renew/release 요청의 condition expression, token,
  consistent read와 conditional failure mapping을 확인한다.
- 신규/active fast path가 SDK member를 한 번, expired takeover/conditional delete가 최대
  두 번 호출되는지 확인하고, 그 밖의 throttling/transport 예외는 adapter가 재호출하지
  않은 채 그대로 전파되는지 확인한다.
- SDK 호출이 `CancellationException`을 던지면 그대로 재전파한다.
- metadata put/get/putIfAbsent/removeIfValue와 만료 logical read를 검증한다. remove 계열은
  expired item을 정리해도 논리 결과가 `false`인지 확인한다.
- fake client에 반복 경합을 주입해 adapter 내부 직렬화나 retry가 없고 매 라운드 token이
  중복되지 않는지 확인한다. 이 구조 검사는 heap/latency 수치를 주장하지 않는다.
- malformed persisted lock/metadata item의 key, owner, value, expiry, fencing token 타입·
  누락·범위 오류는 조용히 takeover/없음으로 바꾸지 않고 `AllOld` 진단 뒤 명시적
  `IllegalStateException`으로 표면화한다. release의 조건 실패도 stale/만료와 malformed를
  같은 방식으로 구분한다.
- fixed/jump `Clock`으로 fast/slow clock과 expiry/takeover/stale renew/release 행렬을
  결정적으로 검증한다.
- retry/backoff 중 cancellation과 transport/throttling 예외가 한 logical call에서
  adapter 재호출 없이 전파되는지 확인한다. `withDynamoDbClient`의 정상·예외·취소 close와
  caller-owned HTTP engine 비종료도 최소 lifecycle 테스트로 확인한다. 취소된 caller가
  `NonCancellable + withTimeout` cleanup을 수행해 원래 cancellation을 보존하는지도
  확인한다.

### Floci

`AbstractAwsTest`의 `FlociServer.Launcher.floci`와 `withDynamoDbClient`를 사용하고,
테이블 작업은 공유 Docker 자원을 위해 순차 실행한다.

- partition-key-only table을 생성하고 준비될 때까지 기존 `waitForTableReady`를 사용한다.
- 2개와 8개의 독립 coroutine이 같은 key를 동시에 acquire할 때 매 라운드 결과가 정확히
  한 개의 lease이고 나머지는 모두 `null`이며, 테스트가 local mutex로 직렬화되지 않았음을
  barrier와 별도 coordinator 인스턴스로 확인한다.
- 짧은 lease 만료 뒤 새 owner가 더 큰 fencing token으로 takeover하고, release 후에도
  lock counter가 삭제되거나 재사용되지 않는다. lock item에는 `ttlEpochSeconds`를 쓰지
  않아 metadata TTL 설정이 lock counter에 영향을 주지 않는다. Floci에서는 metadata
  item에 TTL 속성이 기록되고 lock item에는 기록되지 않는 격리만 검증하며, 실제 DynamoDB
  TTL의 비동기 삭제 지연은 AWS-only gap이다.
- 이전 owner의 renew/release가 실패하고 새 owner item을 보존한다.
- heartbeat/renew 성공은 expiration만 늘리고 token은 보존한다.
- metadata 조건부 put/delete, `consistentRead`, TTL epoch seconds와 logical expiry를
  확인한다.
- 고유 run/table 이름으로 fixture를 격리하고, 전체 테스트를 `try/finally`로 감싼다.
  cleanup은 `NonCancellable`과 bounded timeout으로 수행한다. Floci backend/endpoint를
  assertion하고 공유 Docker 자원 때문에 테스트는 순차 실행한다. 실제 AWS credentials·
  endpoint는 읽지 않는다.

Floci가 제공하지 않는 실제 throttling latency, TTL 삭제 지연, clock skew와 AWS
conditional retry timing은 AWS-only gap으로 기록한다. 사용자의 실제 AWS 금지 조건 때문에
real DynamoDB conditional smoke는 N/A이며, Floci operation 결과가 대체 증거가 된다.

## 7. 문서·변경 표면

- public KDoc은 한국어로 작성하고, `aws-kotlin` manual의 English/Korean 페이지에 같은
  구조의 미출시 `DynamoDB coordination` 절을 추가한다. manual에는 runtime DynamoDB
  service dependency, partition-key-only table 생성, metadata 전용 TTL 속성, lock row를
  삭제하지 않는 운영 규칙, `release=false`/eventual read 의미, 취소 시
  `NonCancellable + withTimeout` cleanup, indeterminate acquire 복구 절차, fencing token을
  downstream conditional write에 적용하는 예를 포함한다.
- README 요약에 새 public type을 중복해서 늘리지 않고 manual 링크로 연결한다.
- `docs/superpowers/plans/2026-08-27-issue-476-dynamodb-coordination-plan.md`, risk,
  lesson과 workflow checklist에 명령·결과·N/A 사유를 기록한다.
- dependency catalog와 build script는 기존 `aws-kotlin:dynamodb` compileOnly/test
  extension을 재사용하므로 변경하지 않는다.
- Streams/Kinesis checkpoint store는 별도 issue의 public SPI를 그대로 두며 이 구현과
  import/wiring하지 않는다.
- production metrics/health endpoint/latency SLO와 heap·allocation benchmark는 이번
  범위의 public contract가 아니므로 N/A로 기록한다. 운영자는 SDK/client metrics와
  DynamoDB capacity alarms를 기존 플랫폼에서 관측하며, 이 adapter는 metadata value,
  ownerId, fencing token을 로그/telemetry에 자동 수집하지 않는다.

## 8. 수용 기준

1. 같은 key의 동시 acquire에서 Floci가 한 winner만 관찰하고 loser는 `null`을 받는다.
2. expiry takeover와 release 후 재획득은 fencing token을 단조 증가시키고, 이전 token
   renew/release는 실패한다. lock item 물리 삭제/TTL로 token을 초기화하지 않는다.
3. renew/heartbeat는 owner+token+유효 expiry 조건을 만족할 때만 성공한다.
4. metadata의 String put/get, TTL logical expiry, putIfAbsent, removeIfValue가
   consistent-read 옵션과 함께 동작한다.
5. conditional failure/throttling/cancellation이 영구 local lock을 남기지 않고, SDK
   retry/backoff 경계가 문서와 테스트에 명시된다. adapter 자체 retry가 없고, caller가
   구성한 SDK timeout/retry 경계 안에서 취소가 전파되는지 `withTimeout` fake로 확인한다.
6. 각 논리 연산의 adapter-level SDK 호출 수가 fast path 1회, 명시된 expired/conditional
   path 최대 2회이고, custom retry/pre-read/background state가 없다는 unit/구조 검사가
   통과한다. 실제 network retry·heap·latency 목표는 설정하지 않는다.
7. malformed persisted item, token exhaustion, scope mismatch, 기본 resolver의 충돌 없는
   결과와 custom resolver 결과의 blank/control/UTF-8 위반, identifier/payload/duration
   상한이 명시적 예외로 표면화된다. 임의 custom resolver collision은 caller 책임이다.
8. Floci targeted test, affected module test, `detekt`, `git diff --check`가 통과한다.
9. 실제 AWS smoke는 실행하지 않았다는 사실과 그 대체 Floci evidence를 기록한다.
10. public KDoc/manual English·Korean 구조가 일치하고, runtime DynamoDB dependency,
   table PK/metadata TTL 구성, `release=false`/eventual read, indeterminate acquire,
   fencing 적용, IAM·secret 경계를 설명하며 SPW-01..05와 KT checklist
   evidence가 lesson/checklist에 남는다.

## 9. 롤백과 후속 경계

신규 coordination production source/dependency를 기존 source와 분리한 채 추가하므로,
실패 시 coordination source/test와 동반 문서를 한 단위로 되돌리고 기존 DynamoDB/Kinesis
API targeted test를 재실행한다.
Floci fidelity가 부족한 항목은 조건식을 완화하지 않고 AWS-only gap으로 남긴다. JSON
codec, Java parity, Streams/Kinesis adapter, background heartbeat scheduler 요구는
별도 issue로 분리한다.

## Writer DoD

- **SPW-01:** 독자·목적·Type-A 범위와 source ledger를 고정했다.
- **SPW-02:** 공개 계약, 물리 schema, 처리 흐름, 오류/lifecycle, 테스트, rollback을 포함했다.
- **SPW-03:** 한국어 기술 문체를 적용하고 API 이름·URL·수치·불확실성을 보존했다.
- **SPW-04:** 기존 Kinesis lease, DynamoDB model/client, Floci test, Issues #469/#470과
  공식 DynamoDB 문서를 대조했다.
- **SPW-05:** 최종 Markdown read-back에서 headings/table/code token/link를 확인한다.

## 상태

설계 문서는 사용자 승인 메시지 `승인`으로 승인되었다. 이제 `writing-plans`로
RED→GREEN 구현 계획을 고정하며, 계획·리스크·실행 리뷰가 완료되기 전에는 production
code를 작성하지 않는다.
