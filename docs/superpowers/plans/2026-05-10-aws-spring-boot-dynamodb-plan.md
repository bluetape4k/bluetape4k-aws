# aws-spring-boot DynamoDB repository 계획

작성일: 2026-05-10
명세: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository/docs/superpowers/specs/2026-05-10-aws-spring-boot-dynamodb-design.md`
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/3

## 실행 규칙

- `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository`에서 작업한다.
- 직접 dependency가 발견되지 않는 한 #3을 PR #30(`aws #2`)과 독립적으로 유지한다.
- awspring을 사용하지 않는다.
- AWS service SDK dependency는 main에서 `compileOnly`, test에서 명시적인 `testImplementation`으로 유지한다.
- public API에 한국어 KDoc을 작성한다.
- README.md와 README.ko.md를 동기화한다.

## 단계 1: build와 등록

1. `aws-spring-boot/build.gradle.kts`를 갱신한다.
   - `compileOnly(libs.aws2.dynamodb.enhanced)`를 추가한다.
   - `testImplementation(libs.aws2.dynamodb.enhanced)`를 추가한다.
2. `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 `DynamoDbAutoConfiguration`을 추가한다.
3. 일찍 compile한다.
   - `./gradlew :aws-spring-boot:compileKotlin --no-daemon`

## 단계 2: property와 table name resolver

`io.bluetape4k.aws.spring.dynamodb` package를 생성한다.

파일:

- `DynamoDbProperties.kt`
- `DynamoDbTableNameResolver.kt`
- `DefaultDynamoDbTableNameResolver.kt`

작업:

1. prefix `bluetape4k.aws.dynamodb`를 사용하는 `DynamoDbProperties`를 구현한다.
2. `endpointOverride == null || region is not blank`를 강제한다.
3. `tablePrefix: String = ""`를 추가한다.
4. resolver를 `tablePrefix + tableName`으로 구현한다.
5. public type에 한국어 KDoc을 추가한다.

테스트:

- property binding 성공.
- region 없는 endpoint override 실패.
- resolver가 prefix 적용.

## 단계 3: auto-configuration

`DynamoDbAutoConfiguration.kt`를 생성한다.

bean 메서드:

1. `dynamoDbAsyncClient(...)`
   - `@Bean(destroyMethod = "close")`
   - `@ConditionalOnMissingBean`
   - `DynamoDbAsyncClient.builder()`
   - 자격 증명 provider fallback
   - 선택형 region, endpoint, async HTTP client
2. `dynamoDbEnhancedAsyncClient(dynamoDbAsyncClient)`
   - `@ConditionalOnMissingBean`
   - `DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(dynamoDbAsyncClient).build()`
3. `dynamoDbTableNameResolver(properties)`
   - `@ConditionalOnMissingBean`
   - `DefaultDynamoDbTableNameResolver(properties.tablePrefix)`

ContextRunner 테스트:

- 기본적으로 모든 bean 등록.
- `enabled=false`이면 back off.
- custom `DynamoDbAsyncClient`가 있으면 back off.
- custom `DynamoDbEnhancedAsyncClient`가 있으면 back off.
- custom resolver가 있으면 back off.
- classpath에 없으면 `FilteredClassLoader`로 back off.

## 단계 4: repository 계약과 base class

파일:

- `CoroutinesDynamoDbRepository.kt`
- `AbstractCoroutinesDynamoDbRepository.kt`

계약:

- `save(item): T`
- `findById(id): T?`
- `existsById(id): Boolean`
- `deleteById(id): T?`
- `delete(item): T?`
- `update(item): T?`
- `scan(...): Flow<T>`
- `query(...): Flow<T>`
- `queryIndex(...): Flow<T>`

기반 class:

1. constructor가 다음을 받는다.
   - `DynamoDbEnhancedAsyncClient`
   - `DynamoDbTableNameResolver`
   - `entityClass: Class<T>`
2. subclass가 다음을 제공한다.
   - `tableName`
   - `keyFromId(id: ID)`
    - 선택형 `keyFromItem(item: T)`
    - 선택형 `tableSchema`
3. resolve한 table name과 schema로 table을 lazy하게 구성한다.
4. 단일 operation에 `CompletableFuture.await()`를 사용한다.
5. `table.scan(request).items().asFlow()`를 사용한다.
6. `table.query(request).items().asFlow()`를 사용한다.
7. `table.index(indexName).query(request).items().asFlow()`를 사용한다.
8. catch boundary를 도입하면 `CancellationException`을 다시 던진다.

## 단계 5: LocalStack test model

`aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/dynamodb` 아래에 test 전용 model을 생성한다.

모델:

- `OrderDocument`
- `@DynamoDbBean`
- partition key 필드: `orderId`
- sort key 필드: `createdAt`
- GSI partition key 필드: `customerId`
- GSI sort key 필드: `createdAt`
- Enhanced Client와 호환되는 mutable property와 public no-arg constructor.

저장소:

- `OrderRepository : AbstractCoroutinesDynamoDbRepository<OrderDocument, OrderId>`
- `OrderId(orderId: String, createdAt: String)`
- `queryIndex`를 사용하는 helper `findByCustomer(customerId): Flow<OrderDocument>`.

table 설정:

- `DynamoDbAsyncClient.createTable` 또는 enhanced table `createTable`을 사용한다.
- primary key schema와 `customer-createdAt-index` GSI를 포함한다.
- bounded Awaitility로 table이 존재하고 active 상태가 될 때까지 기다린다.

## 단계 6: test

ApplicationContextRunner 검증:

1. `DynamoDbAsyncClient`, `DynamoDbEnhancedAsyncClient`, `DynamoDbTableNameResolver`, `DynamoDbProperties`를 등록한다.
2. disabled property는 DynamoDB bean을 등록하지 않는다.
3. custom bean이 있으면 back off한다.
4. endpoint override에는 region이 필요하다.
5. table prefix를 binding하고 resolver가 적용한다.
6. classpath에 없으면 back off한다.

LocalStack 검증:

1. CRUD 순서: save -> findById -> existsById -> update -> deleteById.
2. scan Flow가 삽입한 모든 item을 반환한다.
3. query Flow가 partition key의 item을 반환한다.
4. GSI query Flow가 customer의 item을 반환한다.
5. test property에서 prefix를 붙인 table name을 사용해 table prefix 동작을 검증한다.

## 단계 7: 문서

루트 문서를 갱신한다.

- `README.md`
- `README.ko.md`

문서화 항목:

- AWS SDK runtime dependency 항목: `software.amazon.awssdk:dynamodb-enhanced`
- 자동 구성된 bean
- `bluetape4k.aws.dynamodb.*` 속성
- `@DynamoDbBean`을 사용하는 repository extension example
- table 생성은 명시적이며 auto-config가 수행하지 않는다는 참고

## 단계 8: 검증

순서대로 실행한다.

1. `./gradlew :aws-spring-boot:compileKotlin :aws-spring-boot:compileTestKotlin --no-daemon`
2. `./gradlew :aws-spring-boot:test --no-daemon`
3. `./gradlew :aws-spring-boot:koverHtmlReport detekt :aws-spring-boot:build -x test --no-daemon`
4. `rg 'runBlocking|Thread\\.sleep|GlobalScope' aws-spring-boot/src/main/kotlin`
5. `git diff --check`

환경 문제로 LocalStack이 실패하면 구체적인 failure를 검사하고 한 번 재시도한 뒤 environment-only로 분류한다.

## 단계 9: 검토, commit, PR

1. 다음 항목에 집중해 local self-review를 실행한다.
   - AWS publisher Flow cancellation 검증
   - repository key mapping 검증
   - GSI schema/query mismatch 검증
   - Spring conditional back-off 검증
2. 전체 absolute path로 Claude advisor 검토를 시도하고 artifact를 저장한다.
3. Lore trailer와 `Co-authored-by: OmX <omx@oh-my-codex.dev>`를 포함해 구현을 commit한다.
4. `feat/3-dynamodb-repository`를 push한다.
5. PR title을 생성한다.
   - `[feat] aws-spring-boot DynamoDB coroutine repository`
6. PR 본문은 한국어로 작성하고 `Closes #3`을 포함한다.

## 확인 목록

| 항목 | 상태 | 기록 |
|---|---|---|
| worktree 범위 지정 | 완료 | `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/3-dynamodb-repository` |
| 기존 저장소 재사용 확인 | 완료 | 기존 `aws` DynamoDB Enhanced Async helper를 구현에 반영. |
| 공식 문서 확인 | 완료 | AWS SDK Java v2 Enhanced Async Client와 Spring Boot auto-config pattern. |
| 구현 순서의 dependency 안전성 | 완료 | repository/test/docs 전에 build/property/autoconfig 수행. |
| 검증 명령 나열 | 완료 | compile, test, kover/build, static scan, diff check. |
