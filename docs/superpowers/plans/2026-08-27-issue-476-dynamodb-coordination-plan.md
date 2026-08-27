# DynamoDB Coordination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `aws-kotlin` 모듈에 Floci에서 검증 가능한 DynamoDB 기반 `DistributedLock`과
`MetadataStore`를 추가한다. 조건부 쓰기만으로 bounded two-phase 연산, monotonic fencing
token, logical expiry, fail-closed schema 검증을 제공하고 실제 AWS 호출은 수행하지 않는다.

**Architecture:** public contract와 immutable schema/options를
`io.bluetape4k.aws.kotlin.dynamodb.coordination`에 둔다. `DynamoDbDistributedLock`과
`DynamoDbMetadataStore`는 주입된 AWS Kotlin `DynamoDbClient`의 suspend member만 호출하며,
고정 expression template과 `ReturnValuesOnConditionCheckFailure.AllOld`로 pre-read 없는
최대 두 단계 연산을 구현한다. lock row는 release 후에도 물리 삭제하지 않고 fencing counter를
보존한다. 기존 `AbstractKotlinDynamoDbTest`/`AbstractAwsTest`의 Floci endpoint와 DynamoDB
helper를 재사용한다.

**Tech Stack:** Kotlin, AWS SDK for Kotlin DynamoDB 1.8.26, kotlinx-coroutines, JUnit 5,
MockK, bluetape assertions, Testcontainers `FlociServer`, Gradle, 기존
`withDynamoDbClient`/`createTable`/`deleteTableIfExists` helper.

---

## 1. 변경 표면과 고정 계약

### 파일 지도

| 경로 | 책임 | 변경 방식 |
| --- | --- | --- |
| `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/DynamoDbCoordinationSchema.kt` | entry kind, resolver, schema, identifier/attribute/UTF-8 검증, canonical scope | 신규 |
| `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/DynamoDbCoordinationOptions.kt` | 기본 lease duration, consistent read, injected `Clock` | 신규 |
| `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/LockLease.kt` | 직렬화 가능한 immutable lease와 read-back invariant | 신규 |
| `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/DistributedLock.kt` | suspend lock SPI와 default-duration overload | 신규 |
| `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/MetadataStore.kt` | String metadata SPI | 신규 |
| `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/DynamoDbCoordinationSupport.kt` | `ResolvedCoordinationKey`, fixed aliases/templates, `AttributeValue` encode/decode, old-item 상태 검증, expiry 계산 | 신규 internal |
| `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/DynamoDbDistributedLock.kt` | acquire/renew/heartbeat/release DynamoDB adapter | 신규 |
| `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/DynamoDbMetadataStore.kt` | get/put/putIfAbsent/remove/removeIfValue adapter | 신규 |
| `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/DynamoDbCoordinationSchemaTest.kt` | 입력·scope·resolver·duration 계약 | 신규 |
| `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/LockLeaseTest.kt` | lease invariant/serialization | 신규 |
| `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/DynamoDbCoordinationSupportTest.kt` | parser/expiry/resolver 호출 경계 | 신규 |
| `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/DynamoDbDistributedLockUnitTest.kt` | request shape, bounded calls, stale/malformed/cancellation mapping | 신규 |
| `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/DynamoDbMetadataStoreUnitTest.kt` | metadata request shape, CAS, TTL/logical expiry | 신규 |
| `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/DynamoDbCoordinationFlociTest.kt` | Floci 동시성·expiry·metadata·cleanup contract | 신규 |
| `README.md` | native Kotlin DynamoDB coordination 기능 요약과 Floci command | 기존 갱신 |
| `README.ko.md` | native Kotlin DynamoDB coordination 기능 요약과 Floci command | 기존 갱신 |
| `aws-kotlin/README.md` | 모듈 지원 표와 coordination 사용 예제 | 기존 갱신 |
| `aws-kotlin/README.ko.md` | 모듈 지원 표와 coordination 사용 예제 | 기존 갱신 |
| `CHANGELOG.md` | `[미출시]` `추가` 항목에 #476 기록 | 기존 갱신 |
| `docs/manual/en/modules/bluetape4k-aws-kotlin.md` | 영어 수동 문서의 DynamoDB coordination 절 | 기존 갱신 |
| `docs/manual/ko/modules/bluetape4k-aws-kotlin.md` | 한국어 수동 문서의 DynamoDB coordination 절 | 기존 갱신 |
| `docs/lessons/2026-08-27-issue-476-dynamodb-coordination.md` | 결정·실패·검증 lesson | 신규 |
| `docs/superpowers/checklists/2026-08-27-issue-476-dynamodb-coordination.md` | workflow/implementation evidence | 기존 갱신 |

`aws-kotlin/build.gradle.kts`, root version catalog, 기존 `dynamodb.model` 파일은 변경하지
않는다. DynamoDB service SDK는 이미 compile-only/runtime fixture 경계에 있으므로 dependency를
추가하지 않는다. `aws-java`, Spring Boot, Ktor, DynamoDB Streams/Kinesis adapter에는 parity
변경을 하지 않는다. README는 root/module EN·KO 네 파일의 같은 API 범위만 요약하고, 상세
계약은 manual에 중복하지 않는다.

### 공통 구현 규칙

- public type은 spec의 패키지와 이름을 그대로 사용하며, `DynamoDbCoordinationOptions`는
  `data class`가 아닌 immutable `class`로 둔다.
- 모든 식별자/attribute 이름은 blank/control/UTF-8 256-byte, resolver 결과는
  non-blank/control/2,048-byte를 검사한다. metadata value는 UTF-8 350,000-byte를 넘지
  않게 한다. table 이름과 중복 attribute name은 constructor에서 거부한다. sort-key 부재는
  schema가 DescribeTable로 추론하지 않고 caller가 PK-only table을 생성·확인하는 precondition으로 둔다.
- duration은 finite, 양수, 정수 초, 최대 365일만 허용한다. `Clock`으로 현재 epoch second를
  계산하고 덧셈 overflow를 호출 전에 거부한다.
- `ConditionalCheckFailedException`만 `null`/`false` 결과로 매핑한다. `CancellationException`,
  timeout, throttling 및 다른 SDK 예외는 그대로 전달하며 adapter 자체 retry를 만들지 않는다.
- coroutine-heavy adapter마다 `companion object: KLoggingChannel()`을 두고 terminal SDK
  failure, malformed item, unsupported `AllOld` 경계를 `operation`, `table`, `kind`,
  `namespace` 수준의 낮은 cardinality context로 기록한다. owner, logical/physical key,
  value, fencing token, credential은 log message와 exception context에 넣지 않는다.
- release는 `UpdateItem`으로 owner 제거와 `expiresAt=now`를 수행하고 fencing token을
  보존한다. lock item을 `DeleteItem` 또는 TTL로 삭제하지 않는다.
- request expression에는 caller 입력을 보간하지 않는다. 모든 입력은 expression attribute
  name/value map으로 전달하고, 조건 실패에는 `AllOld`를 요청한다.

## 2. Task 1 — schema, resolver, options를 먼저 고정한다

**Files:** 위 파일 지도에서 `DynamoDbCoordinationSchema.kt`,
`DynamoDbCoordinationOptions.kt`, `DynamoDbCoordinationSupport.kt` 일부,
`DynamoDbCoordinationSchemaTest.kt`.

### RED

1. 테스트 파일을 만들고 다음 테스트를 작성한다.

```kotlin
@Test fun `default resolver는 delimiter가 있는 tuple을 injective하게 인코딩한다`()
@Test fun `custom resolver 결과와 table 및 attribute 이름을 사전 검증한다`()
@Test fun `identifier와 metadata value의 UTF-8 상한을 적용한다`()
@Test fun `duration은 정수 초와 365일 범위만 허용한다`()
@Test fun `scopeId는 schema identity를 안정적으로 표현한다`()
```

2. 구현 전 실행한다.

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*DynamoDbCoordinationSchemaTest'
```

기대 결과: 새 타입이 없으므로 compile failure 또는 unresolved reference가 발생한다(RED).

### GREEN

1. `DynamoDbCoordinationEntryKind { LOCK, METADATA }`와
   `fun interface DynamoDbCoordinationNameResolver`를 추가한다.
2. 기본 resolver는 각 tuple 조각을 length-prefixed UTF-8 형식으로 결합하고 `kind`를
   포함한다. custom resolver에는 사후 injectivity 검사를 시도하지 않고 caller 책임을
   KDoc에 남긴다.
3. `DynamoDbCoordinationSchema` constructor에서 table/namespace/attribute/resolver
   결과를 검증하고, support에 `internal data class ResolvedCoordinationKey(
   logicalKey: String, physicalKey: String, scopeId: String)`와
   `internal fun DynamoDbCoordinationSchema.resolve(kind: DynamoDbCoordinationEntryKind, logicalKey: String): ResolvedCoordinationKey`
   를 둔다. lock lease의 scope identity에는 table, partition key, namespace와 lock
   attribute 이름(owner, expiresAt, fencingToken)만 length-prefixed로 포함한다. metadata
   attribute 변경은 이미 발급된 lock lease의 scope를 바꾸지 않는다.
4. `DynamoDbCoordinationOptions`에 `defaultLeaseDuration = 60.seconds`,
   `consistentRead = true`, `clock = Clock.systemUTC()`를 넣고 `Serializable`을 구현하지
   않는다. duration 검증과 `nowPlus` overflow 검증은 support 내부 함수로 재사용한다.

### 검증

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*DynamoDbCoordinationSchemaTest'
git diff --check
```

기대 결과: schema 테스트가 PASS하고 `git diff --check`가 출력 없이 종료된다.

## 3. Task 2 — public contract와 LockLease를 TDD로 구현한다

**Files:** `LockLease.kt`, `DistributedLock.kt`, `MetadataStore.kt`,
`LockLeaseTest.kt`. support의 lease validation은 Task 3에서만 추가한다.

### RED

다음 테스트를 먼저 추가한다.

```kotlin
@Test fun `LockLease는 positive token과 epoch expiry 및 scope를 검증한다`()
@Test fun `LockLease serialization readObject도 invariant를 다시 검증한다`()
```

실행:

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*LockLeaseTest'
```

기대 결과: 계약/구현이 아직 없어서 RED compile failure가 발생한다.

### GREEN

1. `DistributedLock`의 명시적 suspend 메서드를 spec 그대로 선언한다. SPI interface에는
   options를 숨긴 모호한 default method를 만들지 않는다. 구체 구현 overload는 Task 4에서
   adapter와 함께 추가한다.
2. `LockLease`는 `data class`, `Serializable`, `serialVersionUID=1L`, constructor와
   `readObject`의 identifier/token/expiry 검증을 갖는다. key와 owner는 bounded identifier,
   token은 positive, expiry는 non-negative로 고정한다.
3. `MetadataStore`는 `String` payload와 optional TTL의 다섯 suspend 메서드를 선언한다.
   JSON/bytes codec이나 client lifecycle은 public contract에 넣지 않는다.

### 검증

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*LockLeaseTest'
```

기대 결과: `LockLeaseTest` 전체 PASS, serialization 실패 입력은 DynamoDB 호출 없이 예외를
낸다.

## 4. Task 3 — lock 상태 parser와 support를 구현한다

**Files:** `DynamoDbCoordinationSupport.kt`, `DynamoDbCoordinationSupportTest.kt`.

### RED

MockK 기반 테스트를 먼저 작성한다. support가 만드는 상태와 expression 입력을 캡처해
문자열과 map을 직접 확인한다.

```kotlin
@Test fun `malformed lock item의 fractional number와 missing field는 fail closed한다`()
@Test fun `metadata no-expiry와 expired expiry 상태를 구분한다`()
@Test fun `logical operation당 resolver를 한 번만 호출한다`()
@Test fun `fixed clock과 expiry overflow를 검증한다`()
```

실행:

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*DynamoDbCoordinationSupportTest'
```

기대 결과: parser와 support type이 없어 RED compile failure가 발생한다.

### GREEN

1. `ResolvedCoordinationKey`와 고정 alias(`pk`, `owner`, `expiresAt`, `fencingToken`) 및
   fixed condition/update template을 정의한다. caller 값은 `AttributeValue` map으로만 만든다.
2. lock item parser는 partition key, optional String owner, non-negative integer
   expiresAt, positive integer fencingToken을 확인한다. DynamoDB `N` 문자열은 canonical
   integer로 변환하고 `1.0`, fraction, wrong type, missing field를 모두 malformed로
   분류한다. `Long.MAX_VALUE` token은 별도의 exhaustion 상태로 분류한다.
3. `oldItem`이 없거나 필요한 `AllOld` 정보가 없는 경우를 malformed/unsupported 경계로
   명시한다. parser는 active/expired를 계산하되 clock을 직접 읽지 않고 injected `now`를
   받는다.
4. `LockLease`와 현재 schema의 table, namespace, physical key, scopeId, partition
   attribute를 비교하는 guard를 만들어 renew/release 전에 실행한다. 성공한 `AllNew`/`AllOld`
   응답도 lease/item parser로 재검증해 condition expression만으로 malformed 값을 통과시키지
   않는다. lock `acquire`의 첫 conditional failure와 metadata `putIfAbsent`/`remove`의
   첫 conditional failure에서 `oldItem == null`은 `AllOld` capability/응답 손상으로 간주해
   예외를 내고 second mutation을 금지한다. 반대로 `renew`/`release`의 conditional failure에서
   `oldItem == null`은 item 부재에 따른 정상 stale 결과(`null`/`false`)이며, old item이 있을
   때만 parser로 malformed 여부를 판단한다.

### 검증

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*DynamoDbCoordinationSupportTest'
```

기대 결과: parser 테스트가 PASS하고 resolver 호출 횟수가 logical operation당 한 번이며,
malformed 입력은 두 번째 mutation을 시도하지 않는다.

## 5. Task 4 — DynamoDbDistributedLock를 bounded two-phase로 구현한다

**Files:** `DynamoDbDistributedLock.kt`, `DynamoDbDistributedLockUnitTest.kt`.

### RED

MockK `DynamoDbClient` coroutine stubbing을 먼저 추가한다.

```kotlin
@Test fun `new acquire 성공은 SDK 호출 한 번과 새 token을 반환한다`()
@Test fun `active lock acquire는 AllOld 검증 후 null이고 재시도하지 않는다`()
@Test fun `expired takeover는 관찰 owner expiry token equality로 두 번째 호출만 한다`()
@Test fun `takeover race의 두 번째 conditional failure는 null이고 loop를 만들지 않는다`()
@Test fun `renew은 stale lease에서 null을 반환한다`()
@Test fun `heartbeat은 stale lease에서 null을 반환한다`()
@Test fun `release는 stale lease에서 false를 반환한다`()
@Test fun `token Long MAX_VALUE는 fencing token exhausted로 거부한다`()
@Test fun `CancellationException과 SDK timeout은 결과 매핑 없이 전달된다`()
@Test fun `convenience overload는 options default duration을 전달한다`()
@Test fun `renew와 release의 old item 부재는 각각 null과 false로 끝난다`()
@Test fun `acquire malformed AllOld 부재는 예외와 second-call 금지로 끝난다`()
@Test fun `throttling exception은 adapter retry 없이 그대로 전달된다`()
@Test fun `suspended SDK fake가 withTimeout에서 cancellation을 보존한다`()
@Test fun `renew request는 고정 alias와 owner token expiry equality를 사용한다`()
@Test fun `release request는 고정 alias와 owner token expiry equality를 사용한다`()
@Test fun `release request는 owner 제거와 expiresAt now를 사용하고 token은 건드리지 않는다`()
@Test fun `malformed AllNew와 AllOld 성공 응답은 fail closed한다`()
@Test fun `scope mismatch는 DynamoDB 호출 없이 거부한다`()
```

`coVerify(exactly = 1)`/`coVerify(exactly = 2)`로 호출 상한을 고정한다. 실행:

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*DynamoDbDistributedLockUnitTest'
```

기대 결과: 구현 전 RED compile failure.

### GREEN

1. `DynamoDbDistributedLock(client, schema, options)`을 만들고 client를 닫지 않는다.
2. `DynamoDbDistributedLock`에 `tryAcquire(key, ownerId)`, `renew(lease)`,
   `heartbeat(lease)` overload를 두고 `options.defaultLeaseDuration`을 전달한다.
   `DistributedLock` 명시적 duration SPI와 overload의 호출 경계를 각각 테스트한다.
3. `tryAcquire`는 입력/expiry를 검증한 뒤 첫 `UpdateItem`을
   `attribute_not_exists(#pk)`, `ReturnValue.AllNew`, `AllOld` failure return으로 보낸다.
   성공 응답에서 token/expiry를 검증해 `LockLease`를 반환한다.
4. 첫 조건 실패에서는 exception의 old item을 parser로 검사한다. active는 `null`, malformed는
   `IllegalStateException`, max token은 `IllegalStateException("fencing token exhausted")`다.
   expired valid item만 관찰 owner(없으면 absence), expiry, token과 max guard를 포함한
   equality condition의 두 번째 `UpdateItem`으로 takeover한다. 두 번째 조건 실패는 `null`이다.
5. `renew`와 `heartbeat`는 같은 owner/token/previous expiry와 `expiresAt > now` 조건으로
   한 번의 `UpdateItem`을 보낸다. 성공 시 token은 같고 expiry만 새 값인 lease를 반환한다.
6. `release`는 같은 equality guard로 `SET #expiresAt = :now REMOVE #owner`를 보내고
   `ReturnValue.AllOld`를 요청한다. 조건 성공은 `true`, stale/expired conditional failure는
   `false`, malformed old item은 예외다. DeleteItem을 호출하지 않는다.
7. catch는 `ConditionalCheckFailedException` 단일 타입만 결과로 매핑하고, 다른 예외와
   coroutine cancellation을 절대 삼키지 않는다.
8. terminal SDK failure와 malformed/unsupported item에는 `KLoggingChannel` lazy log를
   남기되 operation/table/kind/namespace만 기록하고 owner/key/value/token은 제외한다.

### 검증

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*DynamoDbDistributedLockUnitTest'
./gradlew :bluetape4k-aws-kotlin:compileKotlin
```

기대 결과: unit tests와 Kotlin compile이 `BUILD SUCCESSFUL`로 끝난다. `coVerify`는 fast
path/active/stale에 1회, expired takeover에 최대 2회만 허용하고 `getItem`/retry/polling/
background scope 호출은 0회다. 다음 구조 검사는 일치 결과가 없을 때 exit 0이어야 한다.

```bash
if rg -n 'launch\s*\{|async\s*\{|GlobalScope|retry\s*\{|while\s*\(|delay\(|\.getItem\(|\.deleteItem\(' \
  aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/DynamoDbDistributedLock.kt; then
  echo 'unexpected loop/retry/pre-read/delete implementation in DynamoDbDistributedLock.kt'
  exit 1
fi
```

## 6. Task 5 — DynamoDbMetadataStore를 logical expiry/CAS와 함께 구현한다

**Files:** `DynamoDbMetadataStore.kt`, `DynamoDbMetadataStoreUnitTest.kt`, support metadata
parser/request 부분.

### RED

```kotlin
@Test fun `get은 consistentRead와 만료 logical null을 사용한다`()
@Test fun `put은 ttl이 없으면 expiresAt과 ttlEpochSeconds를 제거한다`()
@Test fun `putIfAbsent active item은 false이고 expired item은 최대 두 호출로 교체한다`()
@Test fun `remove는 value expiry equality를 사용한다`()
@Test fun `removeIfValue는 expected value expiry equality를 사용한다`()
@Test fun `malformed metadata는 두 번째 mutation 전에 예외를 낸다`()
@Test fun `metadata value와 ttl duration 입력 상한을 호출 전에 검증한다`()
@Test fun `putIfAbsent와 remove의 AllOld 부재는 unsupported로 fail closed한다`()
@Test fun `get의 malformed metadata는 null로 숨기지 않고 예외를 낸다`()
@Test fun `get의 consistentRead false와 no-expiry active item을 보존한다`()
@Test fun `putIfAbsent와 remove는 pre-read GetItem 없이 bounded 호출을 유지한다`()
@Test fun `no-expiry putIfAbsent와 remove request는 두 attribute 부재 조건을 캡처한다`()
```

실행:

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*DynamoDbMetadataStoreUnitTest'
```

기대 결과: RED compile failure.

### GREEN

1. `get`은 `GetItemRequest.consistentRead = options.consistentRead`를 사용하고, 없거나
   `expiresAt <= now`인 item은 `null`로 반환한다. 필수 value가 없거나 wrong type/fraction
   expiry인 item은 `IllegalStateException`으로 표면화하고 null로 숨기지 않는다. TTL 삭제를
   기다리거나 background task를 만들지 않는다.
2. `put`은 `PutItem` replacement로 String value를 저장한다. ttl이 있으면
   `expiresAt`과 `ttlEpochSeconds`를 같은 epoch second로 넣고, 없으면 두 attribute를
   생략해 이전 값을 제거한다. 값/키/duration을 network 전에 검증한다.
3. `putIfAbsent`의 첫 `PutItem`은 `attribute_not_exists(#pk)`와 `AllOld`를 사용한다.
   active/non-expiring old item은 false, valid expired item만 관찰 value/expiry equality
   condition으로 두 번째 PutItem을 한 번 시도한다. non-expiring branch는
   `attribute_not_exists(#expiresAt) AND attribute_not_exists(#ttl)`를 active 판정에
   사용하고 second mutation을 하지 않는다. race failure는 false다.
4. `remove`와 `removeIfValue`는 첫 `DeleteItem`의 `AllOld`를 parser로 검사하고, valid
   existing item에만 관찰 value/expiry(및 expected value) equality의 두 번째 delete를
   시도한다. 논리적으로 active이고 조건이 맞아 삭제되면 true, valid expired item은 stale
   cleanup delete가 성공해도 API 결과는 false, missing은 false이며 malformed는 두 번째
   mutation 전 예외다. non-expiring item의 second delete condition에는
   `attribute_not_exists(#expiresAt) AND attribute_not_exists(#ttl)`를 포함해 expiry가
   생기는 ABA를 막는다.
   no-expiry request test는 `conditionExpression`과
   `expressionAttributeNames/Values`를 캡처해 두 attribute 부재 조건과 고정 alias가 실제
   요청에 함께 들어갔는지 확인한다.
5. removeIfValue의 동일 값 ABA 방어를 주장하지 않는다. caller가 unique version 또는
   DistributedLock fencing token을 value에 넣어야 한다는 KDoc을 둔다.
6. terminal SDK failure와 malformed/unsupported item에는 lock adapter와 같은
   `KLoggingChannel` context 규칙을 적용하고 value/key/token을 로그에서 제외한다.

### 검증

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*DynamoDbMetadataStoreUnitTest'
./gradlew :bluetape4k-aws-kotlin:compileKotlin
```

기대 결과: metadata unit tests와 compile이 PASS하며 `ttlEpochSeconds`는 metadata item에만
나타난다. `putIfAbsent`/`remove`/`removeIfValue`는 각 fast/expired 경로에서
`coVerify(exactly = 0) { client.getItem(any()) }`를 만족하고, `get`은 options의
`consistentRead` 값을 그대로 전달한다.

## 7. Task 6 — FlociServer 통합 contract를 구현한다

**Files:** `DynamoDbCoordinationFlociTest.kt`.

### RED

테스트는 기존 `AbstractKotlinDynamoDbTest`, `withDynamoDbClient`, `createTable`,
`waitForTableReady`, `deleteTableIfExists`, `runSuspendIO`를 import하고 unique table name을
사용한다. 클래스에 `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`,
`@TestMethodOrder(MethodOrderer.OrderAnnotation::class)`, `@AfterAll` fallback cleanup을
선언한다. `TABLE_NAME`은 클래스가 생성하고 `@AfterAll`이 유일한 소유자로 삭제하며, order 5의
idempotent cleanup은 별도 증거로만 실행한다. 초기화/order 중간 실패에도 `@AfterAll` cleanup이
시도되므로 다음 순서의 테스트를 먼저 작성한다.

```kotlin
@Test @Order(1) fun `Floci PK-only table과 coordination client를 준비한다`()
@Test @Order(2) fun `2개와 8개 독립 coroutine 경쟁에서 winner는 하나다`()
@Test @Order(3) fun `만료 takeover는 fencing token을 증가시키고 stale renew release를 차단한다`()
@Test @Order(4) fun `metadata conditional put remove와 logical expiry 및 TTL attribute를 검증한다`()
@Test @Order(5) fun `finally cleanup은 NonCancellable bounded timeout으로 완료한다`()
```

실행:

```bash
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-kotlin:test --tests '*DynamoDbCoordinationFlociTest'
```

기대 결과: 구현 전 RED compile failure 또는 missing public type failure.

### GREEN

1. PK attribute `id`만 가진 String table을 만들고 sort key를 선언하지 않는다. 생성 직후
   `UpdateTimeToLiveRequest`로 metadata `ttlEpochSeconds`를 활성화하고 응답의
   `timeToLiveSpecification.attributeName/enabled`를 캡처해 capability를 기록한다. Floci가
   TTL 설정 자체를 지원하지 않으면 metadata raw attribute assertion만 capability gap으로
   기록할 수 있지만, `AllOld`, conditional write, lock expiry correctness와 logical expiry는
   필수이며 이 capability가 없으면 해당 run을 `PENDING/BLOCKED`로 남기고 구현·PR을 진행하지
   않는다.

```kotlin
val ttlResponse = client.updateTimeToLive(
    UpdateTimeToLiveRequest {
        tableName = tableName
        timeToLiveSpecification = TimeToLiveSpecification {
            attributeName = schema.ttlAttributeName
            enabled = true
        }
    }
)
check(ttlResponse.timeToLiveSpecification?.attributeName == schema.ttlAttributeName)
check(ttlResponse.timeToLiveSpecification?.enabled == true)
```

   `DescribeTable` 응답은 key schema가 `id` HASH 하나이고 RANGE가 없는지 함께 캡처한다.
2. barrier로 2/8 coroutine을 동시에 시작하고 각각 별도 `DynamoDbDistributedLock`와
   고유 owner를 주입한다. 결과를 모아 정확히 한 winner와 나머지 `null`을 확인한다.
3. winner lease의 expiry를 injected mutable clock 또는 짧은 정수 duration으로 경과시킨 뒤
   새 owner takeover를 확인하고 token이 strictly 증가하는지 검증한다. 이전 lease의 renew와
   release가 각각 `null`/`false`인지 확인하며 lock row가 남아 fencing counter를 보존하는지
   `GetItem`으로 확인한다.
4. metadata에 String value와 TTL을 put하고 raw `GetItem`으로 `ttlEpochSeconds`와
   `expiresAt`를 확인한다. clock을 만료 시각 이후로 이동한 `get`이 null이고, stale
   `putIfAbsent`가 false인지 검증한다. 별도 assertion에서 stale `removeIfValue`도 false인지
   확인한다.
5. cleanup은 다음 경계를 사용한다.

```kotlin
finally {
    withContext(NonCancellable) {
        withTimeout(5.seconds) { client.deleteTableIfExists(tableName) }
    }
}
```

   `@AfterAll`도 같은 `NonCancellable` + `withTimeout(5.seconds)` 경계를 사용한다. order 5는
   cleanup을 한 번 더 호출해 idempotence를 확인하고, 별도의 bounded polling으로 raw table
   부재를 확인한다.

```kotlin
withTimeout(5.seconds) {
    while (client.existsTable(tableName)) {
        delay(50.milliseconds)
    }
}
```

   삭제 요청과 부재 확인 모두 5초 경계를 넘으면 실패로 기록하며 무기한 table-deletion wait를
   허용하지 않는다.

   Floci backend와 endpoint를 `AbstractAwsTest` 설정에서 확인하고, 실제 AWS credential,
   throttling latency, asynchronous TTL deletion, clock skew, heap/latency/quota는 실행하지
   않았음을 test/lesson에 기록한다.

### 검증

```bash
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-kotlin:test --tests '*DynamoDbCoordinationFlociTest'
```

기대 결과: Floci test가 `BUILD SUCCESSFUL`로 끝나고 실제 AWS endpoint가 사용되지 않는다.

## 8. Task 7 — public KDoc, manual, lesson을 함께 마감한다

**Files:** `docs/manual/en/modules/bluetape4k-aws-kotlin.md`,
`docs/manual/ko/modules/bluetape4k-aws-kotlin.md`,
`docs/lessons/2026-08-27-issue-476-dynamodb-coordination.md`, 각 public Kotlin 파일 KDoc.

1. root/module README 네 파일에 짧은 `DynamoDB coordination` 요약과 Floci targeted command를
   추가하고, 상세 설명은 manual 링크로 연결한다. 영어/한국어 문장은 각각 자연스럽게 쓰되
   API·command·issue URL은 보존한다.
2. `CHANGELOG.md` `[미출시]` 아래 `추가`에 #476을 기록하고 `Added` 같은 영어 category를
   사용하지 않는다.
3. English/Korean manual에 동일한 heading과 anchor를 유지하며 다음 내용을 예제와 함께
   설명한다: runtime DynamoDB dependency, PK-only table, metadata TTL attribute만 활성화,
   lock row 물리 삭제 금지, `release=false`와 eventual read, indeterminate acquire, downstream
   fencing 조건, cancellation의 `NonCancellable` bounded cleanup, IAM/secret 경계, Floci
   command, 실제 AWS smoke N/A. 새 절 상단에는 `Unreleased/develop` 표식을 두어 현재
   manifest의 `releaseRef: "0.5.0"`에 포함되지 않음을 분명히 한다.
4. Korean 페이지는 자연스러운 한국어로 작성하고 API 이름, command, URL, 수치, exception
   message는 원문을 보존한다. 영어 페이지와 `rg` 기반 heading/code/link parity를 확인한다.
5. lesson에는 처음 발견한 fencing-token reset 위험, AllOld malformed fail-closed, custom
   resolver caller 책임, Floci 한계와 재발 방지 guard를 decision/evidence 형식으로 남긴다.

검증:

```bash
git diff --check
ruby scripts/manual/manual_contract_test.rb
rg -n 'DynamoDbCoordination|ttlEpochSeconds|fencing|Floci' \
  docs/manual/en/modules/bluetape4k-aws-kotlin.md \
  docs/manual/ko/modules/bluetape4k-aws-kotlin.md
diff -u <(rg -o '\{#[^}]+\}' docs/manual/en/modules/bluetape4k-aws-kotlin.md | sort) \
  <(rg -o '\{#[^}]+\}' docs/manual/ko/modules/bluetape4k-aws-kotlin.md | sort)
test "$(rg -c '^```' docs/manual/en/modules/bluetape4k-aws-kotlin.md)" = \
  "$(rg -c '^```' docs/manual/ko/modules/bluetape4k-aws-kotlin.md)"
for token in 'DynamoDbCoordinationSchema' 'DynamoDbDistributedLock' 'MetadataStore' \
  'ttlEpochSeconds' 'NonCancellable' 'Floci'; do
  rg -q "$token" docs/manual/en/modules/bluetape4k-aws-kotlin.md &&
    rg -q "$token" docs/manual/ko/modules/bluetape4k-aws-kotlin.md || exit 1
done
ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml \
  docs/manual/generated/manifest.json --check
```

기대 결과: diff check, manual contract, manifest check, anchor parity가 PASS하고 양쪽
manual의 필수 토큰이 존재한다. no-match marker search의 exit 1은 `|| true`로 감싸지 않고
실제 필수 토큰 검색은 exit 0이어야 한다.

## 9. Task 8 — 통합 검증, checklist evidence, commit/PR 준비

1. 전체 변경을 read-back하고 placeholder marker 검색 결과가 없어야 한다. 계획·리스크·리뷰·
   checklist를 함께 검사하되 명령 안의 marker 문자열은 shell 인접 문자열로 분할해 자기
   자신을 매칭하지 않게 한다.

```bash
marker="T""B""D|TO""DO|FIX""ME"
for file in \
  docs/superpowers/plans/2026-08-27-issue-476-dynamodb-coordination-plan.md \
  docs/superpowers/risk/2026-08-27-issue-476-dynamodb-coordination-risk.md \
  docs/superpowers/reviews/2026-08-27-issue-476-dynamodb-coordination-plan-review.md \
  docs/superpowers/checklists/2026-08-27-issue-476-dynamodb-coordination.md; do
  if rg -n "$marker" "$file"; then
    echo "placeholder marker found in $file"
    exit 1
  fi
done
```

marker가 없으면 이 검사는 exit 0이다.
2. 다음 순서로 검증한다. emulator 자원 공유 때문에 Floci lane은 sequential로 실행한다.

```bash
./gradlew :bluetape4k-aws-kotlin:test \
  --tests '*DynamoDbCoordinationSchemaTest' \
  --tests '*LockLeaseTest' \
  --tests '*DynamoDbDistributedLockUnitTest' \
  --tests '*DynamoDbMetadataStoreUnitTest'
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-kotlin:test --tests '*DynamoDbCoordinationFlociTest'
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-kotlin:test
./gradlew --no-parallel --max-workers=1 detekt
git diff --check
```

구현 파일 구조 scan도 lock과 metadata adapter에 동일하게 적용한다.

```bash
for file in \
  aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/DynamoDbDistributedLock.kt \
  aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/dynamodb/coordination/DynamoDbMetadataStore.kt; do
  if rg -n 'launch\s*\{|async\s*\{|GlobalScope|retry\s*\{|while\s*\(|delay\(|\.getItem\(|\.deleteItem\(' "$file"; then
    echo "unexpected loop/retry/pre-read/delete implementation in $file"
    exit 1
  fi
done
```

두 파일에서 일치 결과가 없어야 하며, metadata의 public `get`이 호출하는 단일 `GetItem`은
이 scan의 pre-read 금지 대상이 아니라 Task 5의 명시적 read contract로 검증한다.

기대 결과: 각 Gradle invocation이 `BUILD SUCCESSFUL`; 해당 unit/Floci tests가 PASS; detekt와
diff check가 통과한다. 실패 시 실패한 명령/첫 오류를 lesson과 checklist에 기록하고 원인을
수정한 뒤 해당 명령부터 재실행한다. root/module README EN·KO와 CHANGELOG에는 #476, public
type, Floci 명령이 각각 존재해야 하며, 다음 검사가 exit 0이어야 한다.

```bash
for file in README.md README.ko.md aws-kotlin/README.md aws-kotlin/README.ko.md; do
  for marker in '#476' 'DynamoDbDistributedLock' 'MetadataStore' 'Floci'; do
    rg -q "$marker" "$file" || { echo "missing $marker in $file"; exit 1; }
  done
done
for marker in '#476' 'DynamoDbDistributedLock' 'MetadataStore' 'Floci'; do
  rg -q "$marker" CHANGELOG.md || { echo "missing $marker in CHANGELOG.md"; exit 1; }
done
```
3. checklist의 WF/A/CG/KT/SPW 항목을 실제 경로·명령·exit status로 갱신한다. real AWS,
   aws-java parity, Streams/Kinesis adapter, dependency/module/benchmark는 N/A로 유지한다.
4. `git status --short`, `git diff --stat`, `git diff --check`를 확인하고, `.bluetape/`는
   ignored runtime state로 commit하지 않는다.
5. 모든 구현/문서가 검증된 뒤 Lore trailer를 사용해 한국어 commit을 만든다.

```text
#476 DynamoDB coordination을 Floci 검증 계약으로 제공한다

조건부 lease와 metadata CAS를 승인된 bounded/fencing 설계로 구현한다.
Constraint: 실제 AWS 계정과 credential 없이 FlociServer만 사용한다.
Rejected: lock row 삭제와 adapter 자체 retry | fencing token 재사용과 indeterminate 성공을 만든다.
Confidence: high
Scope-risk: broad
Directive: downstream write는 LockLease.fencingToken을 다시 조건으로 검사해야 한다.
Tested: unit tests; Floci targeted test; affected module test; detekt; manual contract; diff check.
Not-tested: 실제 AWS throttling/TTL 지연/clock skew/운영 quota.
```

6. PR은 base `develop`, head `feat/issue-476-dynamodb-coordination`로 생성한다. PR body는
   한국어로 작성하고 마지막에 정확히 `## DoD Status`를 둔다. PR 생성 후에는 fresh exact-head
   checks/review/threads를 다음 명령으로 재확인하고, merge는 별도 사용자 승인 전에는 수행하지
   않는다.

```bash
gh pr view <number> --json number,url,baseRefName,headRefName,headRefOid,state,mergedAt,body,labels,milestone
gh pr checks <number> --json name,state,bucket,link,workflow,completedAt
gh api repos/bluetape4k/bluetape4k-aws/pulls/<number>/reviews
gh api repos/bluetape4k/bluetape4k-aws/pulls/<number>/comments
```

   기대 결과: `headRefOid`가 local exact head와 일치하고 required checks/reviews/threads가
   fresh 상태다. merge approval, merge SHA, canonical develop sync, branch/worktree 삭제는
   이 계획의 마지막 unchecked gate로 남긴다.

## 10. 구현 중단·롤백 규칙

- Floci가 `AllOld` 또는 required conditional behavior를 제공하지 않으면 조건식을 완화하거나
  pre-read/fallback을 추가하지 않는다. run을 `PENDING/BLOCKED`로 표시하고 해당 capability가
  복구될 때까지 구현·PR을 진행하지 않는다. 실제 AWS smoke로 대체하지 않는다.
- malformed item을 판별할 수 없으면 second mutation을 보내지 않는다.
- 테스트 실패가 실제 AWS fidelity 문제인지 코드 오류인지 분리하고, 실제 AWS smoke를
  실행하는 방향으로 범위를 넓히지 않는다.
- rollback이 필요하면 새 coordination production source/test/manual/lesson을 한 단위로
  되돌리고 기존 DynamoDB/Kinesis targeted test를 다시 실행한다. 기존 파일과 사용자의
  unrelated 변경은 건드리지 않는다.

## 계획 self-review

- [x] 승인된 spec의 API·schema·bounded 호출·Floci 제약을 모두 파일/단계에 매핑했다.
- [x] 각 production 단계가 RED → GREEN → 검증 순서를 가진다.
- [x] 모든 경로와 명령은 현재 repository layout과 기존 helper 이름을 사용한다.
- [x] placeholder marker와 빈 placeholder 없이 실제 테스트명·기대 결과·rollback을 적었다.
- [ ] 구현 완료 후 실제 test output, changed paths, PR head/checks evidence로 DoD를 채운다.
