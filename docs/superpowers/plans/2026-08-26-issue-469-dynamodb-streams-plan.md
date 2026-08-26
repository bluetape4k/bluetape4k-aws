# 구현 계획 — DynamoDB Streams coroutine `Flow`와 checkpoint (#469)

**설계 명세**: `docs/superpowers/specs/2026-08-26-issue-469-dynamodb-streams-design.md`
**브랜치**: `feat/issue-469-dynamodb-streams`
**작성일**: 2026-08-26
**승인 범위**: `aws-java`, `aws-kotlin`, `gradle`, `README.md`, `docs`
**실행 backend**: `FlociServer.Launcher.floci` only

## 0. 선행 gate와 변경 원칙

- 설계 명세와 본 계획을 각각 관점별 리뷰하고 커밋한 뒤 production code를 수정한다.
- 테스트를 먼저 추가하고 `runTest` virtual time, MockK, bluetape4k assertions로
  polling/retry/checkpoint/cancellation 계약을 잠근다.
- Java SDK generated model은 `software.amazon.awssdk.services.dynamodb.model`을
  사용한다. 별도 Java Streams artifact alias를 만들지 않는다.
- Kotlin SDK는 `aws.sdk.kotlin:dynamodbstreams`를 compileOnly/test fixture에
  등록한다. consumer가 실제 service dependency를 제공해야 하는 기존 BOM 정책을
  유지한다.
- Flow에 주입된 client는 닫지 않는다. 소유 client helper만 `finally`/`useSafe`에서
  닫는다. `ShutdownQueue`는 JVM 종료 안전망으로만 사용한다.
- 실제 AWS credential/endpoint를 읽는 테스트, LocalStack 기본 경로, 무제한 buffer,
  `GlobalScope`, `runBlocking` production code를 추가하지 않는다.

## 1. 의존성·등록 (root-config lane)

### T1.1 Version catalog와 dependency-management

**파일**: `gradle/libs.versions.toml`, `build.gradle.kts`

- `aws-kotlin-dynamodbstreams = { module = "aws.sdk.kotlin:dynamodbstreams" }`
  alias 추가
- root `dependencyManagement.dependencies`에
  `aws.sdk.kotlin:dynamodbstreams:${bt4kVersion("aws-kotlin")}` 추가
- `aws-kotlin/build.gradle.kts`에 `compileOnly(libs.aws.kotlin.dynamodbstreams)` 추가
- `aws-java`는 이미 `compileOnly(libs.aws2.dynamodb.enhanced)`가 저수준
  `software.amazon.awssdk:dynamodb`를 제공하므로 `aws2-dynamodbstreams` alias를 만들지
  않는다. dependency resolution으로 generated Streams classes를 확인한다.

### T1.2 Consumer fixture

**파일**: `build.gradle.kts`, 필요 시
`aws-kotlin/src/consumerFixture/kotlin/.../KotlinServiceConsumerFixture.kt`

- `awsKotlinServiceConsumerFixtureClasspath`에 새 alias를 추가한다.
- fixture에서 `DynamoDbStreamsClient`, `DynamoDbStreamsStartingPosition`의 compile
  surface를 import해 compileOnly 누락을 잡는다.
- Java fixture가 저수준 Streams client와 Flow API를 compile하는지 확인한다.

**완료 증거**: catalog parse, dependency-management resolution, consumer fixture
compile 로그. 실패 시 source 구현으로 진행하지 않는다.

## 2. 테스트 우선: public value types와 store

### T2.1 Kotlin tests

**신규 테스트**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/dynamodbstreams/`

- `DynamoDbStreamsStartingPositionTest`: 네 variant, blank sequence validation,
  Java serialization round-trip와 singleton `readResolve`
- `DynamoDbStreamsRecordFlowOptionsTest`: batch/poll/backoff/retry/concurrency/page
  범위와 informative validation message
- `DynamoDbStreamsCheckpointStoreTest`: in-memory store의 shard key 분리, save/load
  overwrite, unknown key null

### T2.2 Java tests

**신규 테스트**: `aws-java/src/test/kotlin/io/bluetape4k/aws/dynamodbstreams/`

- Kotlin과 같은 value/serialization/validation matrix를 Java SDK model package에
  맞춰 검증한다.
- `DynamoDbStreamsShardRecord`가 stream ARN과 shard ID를 보존하고 record payload를
  변형하지 않는지 확인한다.

모든 단위 테스트는 `runTest`/MockK/bluetape4k assertions를 사용한다. 이 단계에서
Floci를 시작하지 않는다.

## 3. Kotlin SDK 구현

### T3.1 지원 타입과 lifecycle

**신규 파일**: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/dynamodbstreams/`

- `DynamoDbStreamsStartingPosition.kt`
- `DynamoDbStreamsRecordFlowOptions.kt`
- `DynamoDbStreamsCheckpointStore.kt`와 no-op/in-memory 구현
- `DynamoDbStreamsFlowMetrics.kt`
- `DynamoDbStreamsClientSupport.kt` (`dynamoDbStreamsClientOf`,
  `withDynamoDbStreamsClient`)

모든 public KDoc은 한국어로 작성하고 AWS operation, duplicate semantics, client
ownership, Floci-only test 경계를 설명한다. sequence number는
`io.bluetape4k.support.requireNotBlank`를 사용한다.

### T3.2 단일 shard Flow

**신규 파일**: `DynamoDbStreamsRecordFlow.kt`

- `DynamoDbStreamsClient.recordFlow(...) : Flow<model.Record>` 구현
- cold `flow {}` 안에서만 iterator를 조회한다.
- checkpoint load → iterator 생성 → `GetRecords` 순차 polling → `emit` 반환 후
  checkpoint save 순서를 지킨다.
- `ExpiredIteratorException`은 inclusive checkpoint로 복구한다. 최초 `Latest`에
  checkpoint가 없으면 조용한 건너뛰기를 막기 위해 즉시 전파한다.
- `TrimmedDataAccessException`은 자동 `TrimHorizon` fallback 없이 전파한다.
- retryable service exception은 `sdkErrorMetadata.isRetryable` 기반 bounded
  full-jitter backoff를 사용한다. `CancellationException`은 첫 catch에서 재전파한다.
- 모든 성공 응답 뒤 `pollInterval`, empty batch 뒤 `emptyBackoff`를 적용하고,
  `nextShardIterator == null`이면 정상 완료한다.

### T3.3 Multi-shard Flow

- `DescribeStream` pagination을 `lastEvaluatedShardId`까지 수행하고
  `maxDescribePages`를 넘으면 예외를 던진다.
- `parentShardId -> children` graph와 root를 만든다.
- root별 recursive Flow가 parent 완료 뒤 child를 순차 consume한다.
- root Flow만 `flatMapMerge(concurrency = maxShardConcurrency)`로 병합해 bounded
  concurrency와 backpressure를 보장한다.
- `DynamoDbStreamsShardRecord(streamArn, shardId, record)` envelope를 emit한다.
- duplicate shard ID는 visited set으로 제거하고 전역 순서는 보장하지 않는다.

## 4. Java SDK 구현

### T4.1 지원 타입과 lifecycle

**신규 파일**: `aws-java/src/main/kotlin/io/bluetape4k/aws/dynamodbstreams/`

- Kotlin과 동등한 `DynamoDbStreamsStartingPosition`, options, checkpoint store,
  metrics, shard record envelope를 Java generated model에 맞춰 제공한다.
- 기존 `io.bluetape4k.aws.dynamodb.DynamoDbStreamsAsyncClientSupport` 패턴을
  재사용하고 `withDynamoDbStreamsAsyncClient`는 `finally { client.close() }`를
  보장한다. 주입된 client extension은 close하지 않는다.

### T4.2 Async coroutine Flow

- `DynamoDbStreamsAsyncClient.recordFlow(...) : Flow<model.Record>`와
  `shardRecordFlow(...) : Flow<DynamoDbStreamsShardRecord>`를 구현한다.
- `CompletableFuture.await()`를 사용하고, SDK 호출 자체를 blocking thread로
  옮기지 않는다. 별도 synchronous client 호출이 생기면 `Dispatchers.IO` 경계를
  둔다.
- Kotlin 구현과 같은 checkpoint ordering, retry/cancellation, root/child graph,
  backpressure 계약을 유지한다.
- `DynamoDbStreamsAsyncClientCoroutinesExtensions.kt`에는 low-level
  `describeStream`, `getShardIterator`, `getRecords` suspend wrappers를 추가해
  request builder 중복을 줄인다.

## 5. 핵심 Flow 단위 테스트 (TDD)

### T5.1 Kotlin Flow unit matrix

**파일**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/dynamodbstreams/DynamoDbStreamsRecordFlowUnitTest.kt`

- 한 batch의 record 순서와 `nextShardIterator == null` 정상 완료
- 빈 batch의 virtual-time backoff와 non-empty poll interval
- `ExpiredIteratorException` 복구 시 checkpoint inclusive 재조회
- `Latest` + checkpoint 없음 fail-fast
- `TrimmedDataAccessException` 전파 및 fallback 없음
- retryable/non-retryable exception과 max retry 초과
- `emit` 뒤 save 순서, save 실패 시 Flow 실패와 위치 미전진
- collector cancellation이 retry/delay를 우회하고 SDK 호출을 더 하지 않음
- root 두 개의 bounded merge, child가 parent 완료 전 호출되지 않음, child 중복 제거
- metrics event 수와 payload 미전달

### T5.2 Java Flow unit matrix

**파일**: `aws-java/src/test/kotlin/io/bluetape4k/aws/dynamodbstreams/DynamoDbStreamsRecordFlowUnitTest.kt`

MockK `DynamoDbStreamsAsyncClient`와 `CompletableFuture.completedFuture`/exceptionally
completed future를 사용해 T5.1의 의미를 반복한다. `await()` cancellation이
전파되는지와 `close` helper가 block 성공/실패 모두에서 호출되는지도 검증한다.

## 6. Floci capability/integration 테스트

### T6.1 Kotlin Floci

**파일**: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/dynamodbstreams/DynamoDbStreamsFlociTest.kt`

- `withDynamoDbClient`로 stream-enabled table을 만들고 `FlociServer` endpoint를
  사용한다.
- `ListStreams` → `DescribeStream` → `GetShardIterator` → `GetRecords`를 실제
  Floci wire protocol로 확인한다.
- table item write 후 record envelope와 `TrimHorizon`/`Latest`/sequence position을
  확인한다.
- 테스트는 shared Floci 자원을 고려해 순차 실행하고 항상 table을 정리한다.

### T6.2 Java Floci

**파일**: `aws-java/src/test/kotlin/io/bluetape4k/aws/dynamodbstreams/DynamoDbStreamsFlociTest.kt`

- 기존 `AbstractAwsTest`/`DynamoDbStreamsAsyncClientSupport`와
  `SdkAsyncHttpClientProvider`를 재사용한다.
- Kotlin 테스트와 같은 네 operation 및 `shardRecordFlow` capability를 검증한다.
- 실제 AWS, LocalStack fallback, 외부 credential은 사용하지 않는다.

AWS-only 항목(24시간 trim timing, real quota/throttling, production reshard timing)은
테스트 코드에서 `N/A (AWS-only; real AWS 금지)`로 증거표에 기록한다.

## 7. 문서와 lesson

**파일**: `README.md`, `README.ko.md`, 필요 시 `docs/manual/en|ko`,
`docs/lessons/2026-08-26-issue-469-dynamodb-streams.md`

- 서비스 표에 AWS Java/Kotlin DynamoDB Streams Flow와 checkpoint를 추가한다.
- dependency 예제는 `bluetape4k-dependencies` BOM과 unversioned alias를 사용하고,
  service SDK는 compileOnly/consumer responsibility로 설명한다.
- duplicate 범위, inclusive checkpoint, 전역 순서 부재, close helper, retry와
  `Dispatchers.IO` 경계를 양 locale에서 같은 의미로 설명한다.
- Floci capability와 AWS-only N/A를 명시하고 실제 AWS를 테스트하지 않았음을 남긴다.
- lesson은 재사용할 root shard graph, checkpoint save-after-emit, Floci 확인 명령을
  한국어로 기록한다.

## 8. 검증 순서와 stop 조건

1. `git diff --check`
2. focused Kotlin unit tests
3. focused Java unit tests
4. Kotlin Floci test (single Gradle invocation)
5. Java Floci test (별도 sequential invocation)
6. `:bluetape4k-aws-kotlin:compileKotlin`, `:bluetape4k-aws-java:compileKotlin`
7. consumer fixture compile, `detekt`, `kover`/ABI task가 repository contract상
   required인지 확인하고 실행
8. README/manual contract와 `git diff --check`
9. final six-perspective code review 및 `verification-before-completion`

어느 단계에서든 실패하면 원인 로그를 읽고 해당 단계만 수정·재실행한다. Docker가
없어 Floci를 실행할 수 있으면 구현을 성공으로 주장하지 않고 `PENDING`으로 남긴다.
실제 AWS 호출이 필요한 항목은 `N/A` 근거를 남기며 억지로 우회하지 않는다.

## 9. 통합·PR gate

- spec/plan review와 Lore commit이 구현 전에 존재한다.
- 구현 완료 후 lesson commit을 별도로 만든다.
- lane changed paths, check-result, component-evidence, completion-check, main
  verification을 최신 receipt head로 기록한다.
- PR은 승인된 대상 repository/base/head로 한국어 본문을 만들고 `debop`을 assign하며
  마지막에 `## DoD Status`를 둔다.
- exact-head CI/check/review/thread/mergeability/linked issue를 재확인해 merge-ready
  보고까지만 진행한다. merge는 새 명시적 승인 이후에만 한다.

## 10. rollback

dependency alias와 새 package 파일을 각각 되돌리면 기존 Kinesis/low-level Streams
API에 영향이 없다. Floci test가 backend gap을 드러내면 public Flow 계약을 낮추지
않고 capability test를 명시적 N/A로 격리하며 AWS credential 경계를 유지한다.
