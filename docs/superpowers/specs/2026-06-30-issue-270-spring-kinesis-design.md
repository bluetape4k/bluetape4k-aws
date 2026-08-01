# 이슈 #270 Spring Boot Kinesis 설계

작성일: 2026-06-30
이슈: #270 `feat(aws-spring-boot): add Kinesis auto-configuration and operations`
마일스톤: 0.5.0
저장소: `bluetape4k-aws`

## 문제

`bluetape4k-aws-spring-boot`는 현재 S3, SQS, SNS, SES, DynamoDB, CloudWatch, IMDS, KMS, Secrets Manager, Parameter Store를 위한 Spring Boot 4 auto-configuration과 operation을 제공하지만, service coverage chart는 여전히 Spring Boot module의 Kinesis를 미지원으로 표시한다.

core module은 이미 Kinesis를 지원한다.

- `aws-java`는 `io.bluetape4k.aws.kinesis` 아래에 AWS SDK v2 `KinesisClient` / `KinesisAsyncClient` factory와 coroutine extension을 제공한다.
- `aws-kotlin`은 `io.bluetape4k.aws.kotlin.kinesis` 아래에 native suspend helper와 `recordFlow`를 제공한다.
- `aws-spring-boot`는 이미 `:bluetape4k-aws-java`를 `api`로 의존하고 SQS와 SNS 같은 service에 `AsyncClient + Operations + CoroutinesTemplate + Properties + AutoConfiguration` pattern을 따른다.

차이는 Spring Boot integration layer다. 사용자가 Spring bean을 직접 작성하지 않고 Kinesis client와 coroutine 중심 operation을 주입할 수 있어야 한다.

## 현재 근거

- 이슈 #270은 Kinesis auto-configuration과 operation, conditional bean/property binding/대표 경로 test, 신뢰할 수 있을 때 emulator 기반 test, README chart 갱신을 요구한다.
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`는 현재 많은 service auto-configuration을 등록하지만 Kinesis auto-configuration은 등록하지 않는다.
- `aws-spring-boot/build.gradle.kts`는 현재 많은 AWS SDK v2 service를 `compileOnly`/`testImplementation`으로 선언하지만 `libs.aws2.kinesis`는 선언하지 않는다.
- `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg`는 `aws-spring-boot` + `Kinesis`를 `-`로 표시한다.
- 이 설계 전 baseline 검증에서 `./gradlew :bluetape4k-aws-spring-boot:test --no-configuration-cache`가 test 221개로 통과했다.
- Spring Boot 4.1 문서는 기존 pattern인 `@AutoConfiguration`, classpath condition, configuration property, imports 등록, `ApplicationContextRunner` slice test를 지원한다.
- AWS SDK v2 문서와 local code는 service client가 `region`, `endpointOverride`, `credentialsProvider` 같은 builder method를 통해 설정됨을 확인해 준다.

## 제약 조건

- `bluetape4k-aws-spring-boot`를 기존 core module 계약 위에서 얇게 유지한다.
- 기존 S3/SQS/SNS/KMS module style과 맞게 Spring Boot auto-configuration에 AWS Java SDK v2 `KinesisAsyncClient`를 사용한다.
- awspring dependency 또는 Spring Cloud Stream/Kinesis Binder compatibility layer를 도입하지 않는다.
- service SDK dependency를 `compileOnly`로 유지한다. application은 runtime Kinesis SDK dependency를 포함해야 한다.
- `AwsProperties`의 shared AWS 기본값과 `KinesisProperties`의 service-specific override를 적용한다.
- global `AwsAsyncClientCustomizer`와 service-specific `AwsClientCustomizer<KinesisAsyncClientBuilder>`를 지원한다.
- public API에는 영문 KDoc이 필요하다.
- README 변경은 `README.md`와 `README.ko.md`를 모두 갱신하고 module-facing 문서가 바뀌면 `aws-spring-boot/README.md`와 `aws-spring-boot/README.ko.md`도 갱신해야 한다.
- README service coverage chart 갱신에는 SVG 및 PNG 재생성/시각 검증이 필요하다.

## 설계 선택지

### 선택지 A: Java SDK v2 AsyncClient operation

`KinesisAsyncClient` 기반 `KinesisAutoConfiguration`, `KinesisProperties`, `KinesisOperations`, `KinesisCoroutinesTemplate`을 추가한다.

operation surface는 일반적인 producer, stream metadata, shard iterator, polling, single-shard Flow 소비 경로를 다룬다.

- `createStream`
- `deleteStream`
- `describeStream`
- `putRecord`
- `putRecords`
- `getShardIterator`
- `getRecords`
- `recordFlow`

`recordFlow`는 `GetRecords`로 하나의 shard를 polling하고 record를 emit하며 `nextShardIterator`를 진행시키다가 shard가 닫히면 중지하고 cancellation을 즉시 전파하는 cold Flow다.

기존 Spring Boot service pattern과 일치하고 Spring Boot API surface에 두 번째 AWS SDK family를 추가하지 않으므로 이 선택지를 권장한다.

### 선택지 B: `aws-kotlin` `recordFlow` wrapping

Spring Boot에서 기존 AWS Kotlin SDK `KinesisClient.recordFlow`를 직접 재사용한다.

성숙한 Flow 동작을 재사용할 수 있지만 Spring Boot module이 Java SDK v2 client와 함께 Kotlin SDK client를 노출하게 된다. 기존 Spring auto-configuration은 일관되게 Java SDK v2 async client를 사용하므로 Spring 사용자가 dependency와 customizer 동작을 예측하기 어려워진다.

이 PR에서는 기각한다.

### 선택지 C: SQS style annotation listener runtime

SQS와 유사한 `@KinesisListener`, listener container registry, converter hook, retry/backoff, observability를 추가한다.

향후 유용하지만 ordering, checkpointing, resharding, lease coordination, failure 의미를 포함하는 더 큰 runtime이므로 첫 Kinesis Spring Boot 지원 PR에 묶으면 안 된다.

이 PR에서는 기각한다. 첫 PR은 operation과 단순 shard Flow를 제공하고, 후속 이슈에서 annotation listener/checkpoint 의미를 정의할 수 있다.

## 선택한 설계

선택지 A를 구현한다.

### 구성 요소

- `KinesisProperties`
  - 접두사: `bluetape4k.aws.kinesis`
  - 필드: `enabled`, `region`, `endpointOverride`, `streams`, `consumer`
  - `streams`는 configuration 기반 stream 생성을 위해 설정된 stream 이름을 `shardCount`에 mapping한다.
  - 필요하기 전에 Spring facade가 Kinesis service 기능을 과도하게 modeling하지 않도록 고급 create-stream option은 이 PR에서 raw SDK client에 유지한다.
  - `consumer`는 Flow polling의 안전한 기본값인 `batchLimit`, `pollInterval`, `emptyBackoff`, retry limit, throttle backoff를 보관한다.

- `KinesisAutoConfiguration`
  - `AutoConfiguration.imports`에 등록한다.
  - `@ConditionalOnClass(name = ["software.amazon.awssdk.http.async.SdkAsyncHttpClient", "software.amazon.awssdk.services.kinesis.KinesisAsyncClient"])`로 보호한다.
  - `@ConditionalOnProperty(prefix = "bluetape4k.aws.kinesis", name = ["enabled"], havingValue = "true", matchIfMissing = true)`로 보호한다.
  - shared 기본값, credential, 선택형 async HTTP client, global customizer, service customizer와 함께 `KinesisAsyncClient`를 등록한다.
  - `KinesisOperations`를 `KinesisCoroutinesTemplate`로 등록한다.

- `KinesisOperations`
  - Spring application을 위한 public coroutine API.
  - 고급 사용자가 service metadata를 잃지 않도록 AWS SDK response type을 유지한다.
  - 같은 type의 parameter 실수를 줄이는 곳에만 request data class를 사용한다.

- `KinesisCoroutinesTemplate`
  - 기존 `io.bluetape4k.aws.kinesis` coroutine extension이 이미 계약을 표현하면 해당 function에 위임한다.
  - Spring 중심 configuration 기반 stream 생성과 Flow polling을 추가한다.
  - broad exception handling보다 먼저 `CancellationException`을 다시 던진다.
  - polling loop 내부에서 `currentCoroutineContext().ensureActive()`를 사용한다.

### API 형태

operation API는 명시적이고 작아야 한다.

```kotlin
interface KinesisOperations {
    suspend fun createStream(streamName: String, shardCount: Int = 1): CreateStreamResponse
    suspend fun createConfiguredStream(streamName: String): CreateStreamResponse
    suspend fun deleteStream(streamName: String): DeleteStreamResponse
    suspend fun describeStream(streamName: String): DescribeStreamResponse
    suspend fun putRecord(request: KinesisPutRecordRequest): PutRecordResponse
    suspend fun putRecords(streamName: String, entries: List<PutRecordsRequestEntry>): PutRecordsResponse
    suspend fun getShardIterator(request: KinesisShardIteratorRequest): GetShardIteratorResponse
    suspend fun getRecords(shardIterator: String, limit: Int = 100): GetRecordsResponse
    fun recordFlow(request: KinesisRecordFlowRequest): Flow<Record>
}
```

`putRecord`, shard iterator 조회, Flow 소비 API는 자연스럽게 여러 `String` parameter를 포함하므로 이름 있는 request value를 사용한다.

### 오류 처리

- validation failure는 `require*` style check를 통한 `IllegalArgumentException`이다.
- AWS SDK exception을 변경 없이 전파한다.
- Flow cancellation을 즉시 전파한다.
- retry 동작은 기존 `aws-kotlin` Kinesis Flow 동작을 본뜬 Flow iterator/throttle 복구로 제한한다.
- Java SDK v2 exception handling은 `software.amazon.awssdk.services.kinesis.model`에서 사용할 수 있는 구체적인 Kinesis/AWS SDK exception type을 사용해야 하며 Kotlin SDK 전용 metadata에서 retry 가능성을 추론하면 안 된다.
- 이 PR에서는 checkpoint persistence를 도입하지 않는다.

### 테스트

필수 test:

- auto-configuration이 `KinesisAsyncClient`, `KinesisProperties`, `KinesisOperations`, `KinesisCoroutinesTemplate`을 등록한다.
- auto-configuration이 비활성화되면 back off한다.
- custom `KinesisAsyncClient`와 custom `KinesisOperations` bean을 존중한다.
- Kinesis SDK가 없으면 classpath guard가 back off한다.
- endpoint override에는 region이 필요하고 shared 기본값으로 region을 제공할 수 있다.
- global 및 Kinesis-specific async customizer를 순서대로 실행한다.
- property binding이 설정된 stream과 consumer setting을 다룬다.
- property validation이 `shardCount >= 1`, `batchLimit in 1..10_000`, 음수가 아닌 delay, 양수 retry count, 유효한 jitter bound를 다룬다.
- template unit test가 대표 operation의 validation과 request mapping을 다룬다.
- 이 저장소에서 Floci/LocalStack Kinesis 지원이 신뢰할 수 있으면 emulator 기반 smoke test가 create, put, describe, shard iterator 조회, record 조회, Flow collection을 다뤄야 한다. service 제한으로 emulator 지원이 실패하면 단계 DoD에 fallback 이유를 기록하고 unit/slice test를 gate로 유지한다.

### 문서

- 루트 README와 한국어 README의 module table 및 service section에서 Spring Boot Kinesis operation을 언급해야 한다.
- `aws-spring-boot/README.md`와 `README.ko.md`에 dependency snippet과 coroutine 사용법을 포함한 Kinesis operation section을 추가해야 한다.
- 구현/test가 통과한 뒤에만 service coverage chart의 `aws-spring-boot × Kinesis`를 `-`에서 `S`로 변경해야 한다.
- 생성한 SVG 및 PNG chart asset은 XML/render validation과 visual inspection을 통과해야 한다.

## 위험과 완화책

1. **listener 의미의 범위 확장**
   - 위험: 전체 listener runtime은 checkpointing, resharding, concurrency, operational 의미로 확장된다.
   - 완화: 이 PR은 operation과 single-shard Flow까지만 다룬다. listener runtime은 후속 작업이다.

2. **emulator 신뢰성**
   - 위험: Kinesis emulator 지원은 Floci, LocalStack, MiniStack마다 다를 수 있다.
   - 완화: 집중된 emulator smoke를 시도한다. 신뢰할 수 없으면 정확한 blocker를 기록하고 결정적인 unit/slice coverage를 유지한다.

3. **dependency 누출**
   - 위험: Kinesis SDK를 implementation으로 추가하면 저장소의 compileOnly service SDK policy를 위반한다.
   - 완화: `libs.aws2.kinesis`를 `compileOnly`와 `testImplementation`으로만 추가한다.

4. **Flow data loss 가정**
   - 위험: checkpoint 없이 `LATEST`에서 만료된 iterator를 복구하면 record를 건너뛸 수 있다.
   - 완화: 기존 `aws-kotlin` 설계를 따른다. 마지막으로 본 sequence number 없이 `LATEST`에서 조용히 복구하지 않는다.

5. **public 문서 drift**
   - 위험: README example이 API가 존재하기 전 또는 변경된 후의 이름을 사용할 수 있다.
   - 완화: 구현 후 README API 이름을 source와 대조해 grep하고 해당 검사를 validation에 포함한다.

## 인수 기준

- `bluetape4k-aws-spring-boot`가 Kinesis auto-configuration과 coroutine operation을 제공한다.
- Kinesis SDK dependency는 production에서 compile-only, test에서 test-only로 유지한다.
- conditional bean 생성, property binding, customizer ordering, classpath backoff test가 통과한다.
- 대표 Kinesis operation을 unit/slice test로 다루고, 신뢰할 수 있으면 emulator smoke도 수행한다.
- 현재 source에 맞춰 README locale set과 service coverage chart를 갱신한다.
- 단계 2-R, 단계 3-R, 단계 6-R, 단계 7-R review gate가 P0/P1 = 0으로 수렴한다.
- PR 본문의 마지막 `##` section이 `## DoD Status`다.
