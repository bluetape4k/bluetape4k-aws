# 이슈 #191 DynamoDB DAX Spring Boot 계획

작성일: 2026-06-07
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/191
명세: `docs/superpowers/specs/2026-06-07-issue-191-dynamodb-dax-spring-boot-design.md`

## 게이트 상태

- 명세 검토: 통과, `P0=0`, `P1=0`
- 계획 검토: 보류

## 구현 단계

### 1. 의존성 경계

- 로컬 catalog alias를 추가한다.
  - `dax-client = "2.0.9"`
  - `aws-dax-client = { module = "software.amazon.dax:amazon-dax-client", version.ref = "dax-client" }`
- `aws-spring-boot/build.gradle.kts`에 다음을 추가한다.
  - `compileOnly(libs.aws.dax.client)`
  - `testImplementation(libs.aws.dax.client)`
- dependency insight를 실행한다.
  - `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --configuration testCompileClasspath --dependency amazon-dax-client`
  - `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --configuration testCompileClasspath --dependency software.amazon.awssdk:dynamodb`
- `amazon-dax-client:2.0.9`가 있고 AWS SDK DynamoDB가 저장소/catalog에서 선택한 버전
  계열을 유지하는지 확인한다.

### 2. 프로퍼티

- `DynamoDbDaxProperties`를 추가한다.
- `DynamoDbProperties`에 `val dax: DynamoDbDaxProperties = DynamoDbDaxProperties()`를
  추가한다.
- 기존 `endpointOverride` 검증을 유지한다.
- DAX가 활성화되고 클래스 경로에 있을 때 DAX 자동 구성 경로에서만 DAX
  URL/timeout/retry를 검증한다.
- 값 타입에 맞으면 bluetape4k 검증 도우미를 사용한다. `Duration`/`URI` 의미에 맞는
  기존 도우미가 없을 때만 `require`를 사용한다.

### 3. 공유 DynamoDB 자동 구성 도우미

필요한 경우 `DynamoDbAutoConfiguration`에서 다음 private 도우미를 추출한다.

- `resolveCredentialsProvider`
- `resolveAwsProperties`

새 공개 API가 생기지 않도록 package-private/internal로 유지한다.

### 4. DAX 자동 구성

- `DynamoDbDaxAutoConfiguration`을 추가한다.
- 다음 조건을 적용한다.
  - `AwsAutoConfiguration` 뒤
  - `DynamoDbAutoConfiguration` 앞
  - `@ConditionalOnClass(name = ["software.amazon.dax.ClusterDaxAsyncClient"])`
  - `@ConditionalOnProperty(prefix = "bluetape4k.aws.dynamodb", name = ["enabled"], havingValue = "true", matchIfMissing = true)`
  - `@ConditionalOnProperty(prefix = "bluetape4k.aws.dynamodb.dax", name = ["enabled"], havingValue = "true")`
  - `@ConditionalOnMissingBean(DynamoDbAsyncClient::class)`
- 다음 방식으로 `ClusterDaxAsyncClient`를 만든다.
  - `ClusterDaxAsyncClient.builder().overrideConfiguration(Configuration.builder()...build()).build()`
- 다음 설정을 적용한다.
  - `url`
  - `dax.region ?: dynamodb.region ?: aws.region`의 `region`
  - 기존 Spring AWS resolver의 credentials provider
  - timeout/retry/concurrency/hostname verification 프로퍼티
- AWS SDK 비동기 클라이언트 customizer는 적용하지 않고 DAX 전용 프로퍼티 조정임을
  문서화한다.
- `Configuration.Builder`가 빈 생성 중 credentials를 확인하므로 DAX 활성 context
  테스트는 dummy 정적 `AwsCredentialsProvider`를 등록해야 한다.

### 5. 자동 구성 등록

`DynamoDbDaxAutoConfiguration`을
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에서
`DynamoDbAutoConfiguration`보다 먼저 등록한다.

### 6. 테스트

`DynamoDbAutoConfigurationTest`를 갱신하거나 전용
`DynamoDbDaxAutoConfigurationTest`를 추가한다.

필수 사례는 다음과 같다.

- 기본 DynamoDB 경로가 일반 `DynamoDbAsyncClient` 하나와 enhanced client 하나를
  등록한다.
- `software.amazon.dax` 클래스 경로를 걸러낸 상태에서 `dax.enabled=true`여도 DAX
  클라이언트를 만들지 않고 기본 DynamoDB 경로를 사용할 수 있다.
- `dax.url` 없이 `dax.enabled=true`이면 명확하게 실패한다.
- `dax.url`이 있는 `dax.enabled=true`는 유일한 `DynamoDbAsyncClient`로
  `ClusterDaxAsyncClient`를 만든다.
- DAX 클라이언트를 선택해도 enhanced client가 존재한다.
- 사용자 `DynamoDbAsyncClient` 빈은 DAX와 기본 클라이언트 생성을 백오프시킨다.

### 7. 문서와 lesson

- 루트 `README.md`와 `README.ko.md`를 갱신한다.
- `aws-spring-boot/README.md`와 `aws-spring-boot/README.ko.md`를 갱신한다.
- `docs/lessons/2026-06-07-issue-191-dynamodb-dax.md`를 추가한다.
- 다음 내용을 설명한다.
  - 소비자 런타임 의존성
  - DAX는 LocalStack/DynamoDB Local이 아니라 실제 AWS DAX 클러스터용이다.
  - cache 일관성/latency tradeoff
  - 기존 저장소 코드는 계속 `DynamoDbEnhancedAsyncClient`를 사용한다.

### 8. 검증

다음 순서로 실행한다.

```bash
./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --configuration testCompileClasspath --dependency amazon-dax-client
./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --configuration testCompileClasspath --dependency software.amazon.awssdk:dynamodb
./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.dynamodb.*'
./gradlew :bluetape4k-aws-spring-boot:test
git diff --check
```

관련 없는 emulator/runtime 문제로 `:bluetape4k-aws-spring-boot:test`가 너무 넓거나
차단되면 대상 DynamoDB 테스트 결과를 유지하고 blocker를 명시적으로 기록한다.

## PR 범위

예상 변경 파일은 다음과 같다.

- `gradle/libs.versions.toml`
- `aws-spring-boot/build.gradle.kts`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/dynamodb/*`
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/dynamodb/*`
- `README.md`
- `README.ko.md`
- `aws-spring-boot/README.md`
- `aws-spring-boot/README.ko.md`
- `docs/lessons/2026-06-07-issue-191-dynamodb-dax.md`

## 완료 조건

- 구현이 컴파일된다.
- 대상 테스트가 통과한다.
- 로컬 검토가 `P0=0`, `P1=0`을 보고한다.
- PR 본문이 `## DoD Status`로 끝나는 것을 검증하고 PR을 생성한다.
