# aws-spring-boot DynamoDB 저장소 설계

날짜: 2026-05-10
저장소: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository`
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/3

## 문제

`aws-spring-boot`는 현재 공통 `AwsAutoConfiguration`과 S3 자동 구성을 제공한다.
이슈 #3은 awspring에 의존하지 않는 다음 Spring Boot 4 DynamoDB 통합을 요구한다.

- `DynamoDbAutoConfiguration`
- `DynamoDbAsyncClient` 및 `DynamoDbEnhancedAsyncClient` 빈
- `bluetape4k.aws.dynamodb`에 바인딩되는 `DynamoDbProperties`
- `CoroutinesDynamoDbRepository<T, ID>` 인터페이스
- `AbstractCoroutinesDynamoDbRepository<T, ID>` 기반 구현
- `@DynamoDbBean` 매핑 지원
- Kotlin `Flow` 기반 페이징, 스캔, 쿼리
- 보조 인덱스 쿼리 지원
- LocalStack + Testcontainers CRUD, 페이징, 쿼리 검증

공개 Spring API는 코루틴 우선이어야 하며, 적합한 곳에서 기존 `aws` 모듈의
DynamoDB Enhanced Async 도우미를 재사용해야 한다.

## 근거

### 현재 저장소

- `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository/aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3AutoConfiguration.kt`
  는 가장 가까운 자동 구성 패턴이다. `@AutoConfiguration`, `@ConditionalOnClass`,
  `@ConditionalOnProperty`, `@EnableConfigurationProperties`,
  `ObjectProvider<AwsCredentialsProvider>`, 선택적 HTTP 클라이언트 빈,
  `@ConditionalOnMissingBean`을 사용한다.
- `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository/aws/src/main/kotlin/io/bluetape4k/aws/dynamodb/repository/DynamoDbCoroutineRepository.kt`
  는 `DynamoDbEntity`를 구현하는 엔티티용 코루틴 저장소를 이미 제공하지만 해당
  엔티티 모델에 결합돼 있고 Spring Boot 자동 구성이 없다.
- `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository/aws/src/main/kotlin/io/bluetape4k/aws/dynamodb/enhanced/DynamoDbAsyncTableExtensions.kt`
  는 `DynamoDbAsyncTable<T>`용 코루틴/Flow 도우미를 이미 제공한다.
  `getItem`, `putItem`, `deleteItem`, `scanAll`, `queryAll`, `queryByPartition`,
  `findAll`, `findByPartition`, and `exists`.
- `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository/aws/src/main/kotlin/io/bluetape4k/aws/dynamodb/enhanced/DynamoDbEnhancedAsyncClientSupport.kt`
  는 `DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(client).build()`를 감싼다.
- `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository/aws/src/test/kotlin/io/bluetape4k/aws/dynamodb/examples/food/repository/CustomerRepository.kt`
  는 `DynamoDbEnhancedAsyncClient.table(...)`, `QueryEnhancedRequest`,
  `QueryConditional`을 통한 현재 저장소 사용법을 보여 준다.
- `gradle/libs.versions.toml`은 `software.amazon.awssdk:dynamodb-enhanced`용
  `libs.aws2.dynamodb.enhanced`를 이미 노출한다.

### 공식 문서

- AWS SDK for Java 2.x는 `DynamoDbAsyncClient`에서 `DynamoDbEnhancedAsyncClient`를 만든다.
- Enhanced 비동기 단일 항목 연산은 `CompletableFuture`를 반환한다.
- Enhanced 비동기 스캔/쿼리 연산은 `PagePublisher<T>` / `SdkPublisher`를 반환하며
  비동기로 소비해야 한다.
- `TableSchema.fromBean(MyClass::class.java)`은 `@DynamoDbBean` 매핑을 지원한다.
- 테이블 및 인덱스 쿼리 지원은 `QueryEnhancedRequest`, `QueryConditional`과 함께
  `DynamoDbAsyncTable<T>`, `DynamoDbAsyncIndex<T>`를 통해 노출된다.
- Spring Boot 자동 구성은 클래스패스 조건, `@ConditionalOnMissingBean`, 타입 지정
  구성 속성을 사용해야 한다.

## 목표

1. AWS DynamoDB Enhanced SDK가 있을 때 다음 DynamoDB 빈을 자동 구성한다.
   - `DynamoDbAsyncClient`
   - `DynamoDbEnhancedAsyncClient`
2. 다음 `bluetape4k.aws.dynamodb.*` 속성을 바인딩한다.
   - `enabled`
   - `region`
   - `endpoint-override`
   - 필요할 때 테이블 접두사/기본 용량/테스트 친화적 기본값
3. Spring 친화적인 코루틴 저장소 기반 타입을 제공한다.
   - `CoroutinesDynamoDbRepository<T, ID>`
   - `AbstractCoroutinesDynamoDbRepository<T, ID>`
4. `TableSchema.fromBean(...)`을 통해 `@DynamoDbBean` 엔티티를 지원한다.
5. 다음 공통 연산을 지원한다.
   - 저장, ID로 조회, ID로 삭제
   - 항목 갱신/삭제 오버로드
   - `Flow<T>` 기반 스캔
   - `Flow<T>` 기반 쿼리
   - `Flow<T>` 기반 인덱스 쿼리
   - 유용한 경우 크기가 제한된 첫 페이지 도우미
6. 빈 등록, CRUD, 스캔 페이징, 쿼리, 인덱스 쿼리를 검증하는 LocalStack 테스트를 추가한다.
7. README.md와 README.ko.md를 동기화한다.

## 제외 범위

- awspring 또는 Spring Data DynamoDB를 추가하지 않는다.
- 첫 PR에서 모든 저장소 인터페이스를 자동 발견하는 어노테이션 스캔을 구현하지 않는다.
- 완전한 Spring Data 저장소 팩토리, 파생 쿼리 파서 또는 트랜잭션 추상화를 만들지 않는다.
- 엔티티가 기존 `DynamoDbEntity`를 구현하도록 요구하지 않는다. 새 Spring 저장소는
  일반 `@DynamoDbBean` 클래스와 함께 동작해야 한다.
- 애플리케이션 시작 시 운영 테이블을 자동 생성하지 않는다. 테스트는 테이블을
  명시적으로 생성할 수 있다.
- DynamoDB 일관성/용량 절충을 마법 같은 기본값으로 숨기지 않는다.

## 접근 선택지

### 선택지 A: 기존 `DynamoDbCoroutineRepository` 재사용

기존 `aws` 모듈 저장소 인터페이스를 직접 사용한다.

장점:
- API 표면이 매우 작다.
- 검증된 코드를 재사용한다.

단점:
- `T : DynamoDbEntity`를 요구해 이슈 #3의 일반 `@DynamoDbBean` 매핑 목표와 충돌한다.
- `ID`를 모델링하지 않는다.
- Spring 사용자에게 깔끔한 추상 저장소 기반을 제공하지 않는다.

결정: 기본 Spring API로는 거부하지만 그 아이디어와 Enhanced 비동기 확장 함수는 재사용한다.

### 선택지 B: Spring Data 형태의 저장소 팩토리

저장소 인터페이스 스캔, 생성 프록시, 메서드 이름 기반 쿼리 파생을 구현한다.

장점:
- Spring 사용자에게 익숙한 경험을 제공한다.

단점:
- 영향 범위가 크다.
- 쿼리 파생은 별도의 제품 API다.
- 첫 DynamoDB PR에서 안전하게 완성하기 어렵다.

결정: #3에서는 거부한다. 기반 저장소를 검증한 뒤 향후 이슈로 남긴다.

### 선택지 C: 명시적 추상 저장소 기반

작은 제네릭 인터페이스와 추상 클래스를 제공한다. 애플리케이션은 기반 클래스를
확장하고 테이블 이름, 스키마, 키 매핑을 제공해 저장소 빈을 정의한다.

장점:
- 일반 `@DynamoDbBean`과 동작한다.
- 매핑/키/인덱스 결정을 명시적으로 유지한다.
- LocalStack으로 테스트하기 쉽다.
- AWS Enhanced Async 클라이언트를 직접 재사용한다.

단점:
- 저장소마다 상용구가 조금 더 많다.
- 파생 쿼리 메서드가 없다.

결정: #3에서 채택한다.

## 제안 API

패키지:

```text
io.bluetape4k.aws.spring.dynamodb
  DynamoDbAutoConfiguration
  DynamoDbProperties
  CoroutinesDynamoDbRepository
  AbstractCoroutinesDynamoDbRepository
  DynamoDbTableNameResolver
  DefaultDynamoDbTableNameResolver
```

저장소 계약:

```kotlin
interface CoroutinesDynamoDbRepository<T: Any, ID: Any> {
    val tableName: String
    val table: DynamoDbAsyncTable<T>

    suspend fun save(item: T): T
    suspend fun findById(id: ID): T?
    suspend fun existsById(id: ID): Boolean
    suspend fun deleteById(id: ID): T?
    suspend fun delete(item: T): T?
    suspend fun update(item: T): T?

    fun scan(builder: ScanEnhancedRequest.Builder.() -> Unit = {}): Flow<T>
    fun query(
        queryConditional: QueryConditional,
        builder: QueryEnhancedRequest.Builder.() -> Unit = {},
    ): Flow<T>
    fun queryIndex(
        indexName: String,
        queryConditional: QueryConditional,
        builder: QueryEnhancedRequest.Builder.() -> Unit = {},
    ): Flow<T>
}
```

추상 기반:

```kotlin
abstract class AbstractCoroutinesDynamoDbRepository<T: Any, ID: Any>(
    private val enhancedClient: DynamoDbEnhancedAsyncClient,
    private val tableNameResolver: DynamoDbTableNameResolver,
    private val entityClass: Class<T>,
) : CoroutinesDynamoDbRepository<T, ID> {
    abstract override val tableName: String
    abstract fun keyFromId(id: ID): Key
    open fun keyFromItem(item: T): Key = error("Override when delete(item) is used")
}
```

`table`은 다음에서 만든다.

```kotlin
enhancedClient.table(tableNameResolver.resolve(tableName), TableSchema.fromBean(entityClass))
```

정적 스키마로 표현하는 편이 나은 엔티티에는 protected 생성자 또는 재정의 가능한
`tableSchema`를 허용한다.

```kotlin
protected open val tableSchema: TableSchema<T> = TableSchema.fromBean(entityClass)
```

## 자동 구성

`DynamoDbAutoConfiguration`은 S3/SQS 패턴을 따라야 한다.

- `@AutoConfiguration(after = [AwsAutoConfiguration::class])`
- `@ConditionalOnClass` 문자열:
  - `software.amazon.awssdk.http.async.SdkAsyncHttpClient`
  - `software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient`
  - `software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient`
- `@ConditionalOnProperty(prefix = "bluetape4k.aws.dynamodb", name = ["enabled"], havingValue = "true", matchIfMissing = true)`
- `@EnableConfigurationProperties(DynamoDbProperties::class)`

빈:

- `DynamoDbAsyncClient`, `@ConditionalOnMissingBean`, `destroyMethod = "close"`
- `DynamoDbEnhancedAsyncClient`, `@ConditionalOnMissingBean`
- `DynamoDbTableNameResolver`, `@ConditionalOnMissingBean`

빌더 규칙:

- `ObjectProvider`의 `AwsCredentialsProvider`를 사용하고, 없으면
  `DefaultCredentialsProvider.builder().build()`를 사용한다.
- 구성됐을 때만 `Region.of(properties.region)`을 적용한다.
- 구성됐을 때만 `endpointOverride`를 적용한다.
- 선택적 `SdkAsyncHttpClient`를 받는다.
- AWS 서명자에는 여전히 리전이 필요하므로 `region` 없는 `endpointOverride`를 거부한다.

## 속성

```kotlin
@ConfigurationProperties(prefix = "bluetape4k.aws.dynamodb")
data class DynamoDbProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val tablePrefix: String = "",
)
```

`tablePrefix`는 `DynamoDbTableNameResolver`에서만 사용한다. 테스트와 다중 환경
애플리케이션이 저장소 코드를 안정적으로 유지할 수 있게 한다.

## 위험과 실패 모드

1. **Kotlin `@DynamoDbBean` 가변성 요구 사항.** Enhanced Client 빈 매핑에는 호환되는
   getter/setter와 인자 없는 생성자가 필요하다. 테스트는 불변 데이터 클래스가 아니라
   현실적인 가변 빈 클래스를 사용해야 한다.
2. **인덱스 생성 불일치.** LocalStack 테스트 테이블에 일치하는 어노테이션과 테이블
   생성 메타데이터를 가진 GSI가 있을 때만 저장소 인덱스 쿼리 지원이 유용하다.
3. **Publisher 변환과 취소.** 쿼리/스캔은 AWS publisher에 reactive `asFlow()`를
   사용하고 블로킹 수집을 피해야 한다.
4. **테이블 자동 생성 유혹.** 자동 구성에서 테이블을 자동 생성하면 운영 부작용이
   생긴다. 테스트에서 테이블 생성을 명시적으로 유지한다.
5. **컴파일 전용 AWS 의존성 경계.** 운영 코드에는
   `compileOnly(libs.aws2.dynamodb.enhanced)`, 테스트에는
   `testImplementation(libs.aws2.dynamodb.enhanced)`를 추가해야 한다.

## 인수 기준

- SDK 클래스가 있으면 `DynamoDbAutoConfiguration`이 비동기 및 Enhanced 비동기
  클라이언트를 등록한다.
- 사용자 정의 빈이 있으면 자동 구성 기본값이 물러난다.
- 비활성화 속성은 DynamoDB 빈 등록을 막는다.
- 리전 없는 엔드포인트 재정의는 바인딩/시작에 실패한다.
- 저장소 기반이 LocalStack에서 `@DynamoDbBean` CRUD를 지원한다.
- 스캔/쿼리가 `Flow<T>`를 반환한다.
- 테스트 테이블에서 GSI 쿼리가 동작한다.
- README.md와 README.ko.md에 의존성, 속성, 저장소 사용법을 문서화한다.

## 완료 정의

- 구현 전에 명세와 계획을 커밋한다.
- 전체 절대 경로로 자문 리뷰를 시도하고 수용한 지적을 반영한다.
- `./gradlew :aws-spring-boot:compileKotlin :aws-spring-boot:compileTestKotlin --no-daemon`
- `./gradlew :aws-spring-boot:test --no-daemon`
- `./gradlew :aws-spring-boot:koverHtmlReport detekt :aws-spring-boot:build -x test --no-daemon`
- `git diff --check`
- PR 제목은 `[codex]`가 아니라 `[feat]`를 사용한다.
- PR 본문은 한국어로 작성하고 `Closes #3`을 포함한다.
