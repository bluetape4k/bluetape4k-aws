# Issue 294 구현 검토

## 게이트 판정

- P0: 0
- P1: 0
- 판정: PASS

## 7단계 검토

### 1. 정확성

- `AwsSecretString`은 이제 private constructor와 검증된 companion/top-level factory를 사용한다.
- `readResolve()`는 `AwsSecretString.of(value)`를 통해 반환하므로 역직렬화된 값도 검증된 factory 경로를 사용한다.
- Ktor runtime client 종료 경로는 `runInterruptible(Dispatchers.IO)`를 직접 사용한다.
- 동기 DynamoDB `batchWriteItem`과 non-suspend SQS listener reflection 호출도 `runInterruptible(Dispatchers.IO)`를 사용한다.

### 2. Cancellation 및 Coroutine 안전성

- 중첩 `withContext(Dispatchers.IO) { runInterruptible { ... } }` 스캔: 결과 0건.
- 변경된 coroutine 경로의 광범위한 예외 처리는 여전히 `CancellationException`을 다시 던진다.
- suspend SQS listener 호출은 handler 자체가 suspend될 수 있으므로 기존 `withContext(Dispatchers.IO)` 동작을 유지하고, non-suspend reflection 호출만 `runInterruptible`로 옮겼다.

### 3. bluetape4k 생태계 재사용

- `aws-exposed`에 `bluetape4k-jdbc` catalog alias와 의존성을 추가했다.
- 직접 Hikari를 생성하는 코드를 `io.bluetape4k.jdbc.hikari.hikariDataSourceOf`로 교체했다.
- DynamoDB filter nonce의 원시 `Random.nextInt`를 `Base58.randomString`으로 교체했다.
- 남은 직접 `DriverManager` 사용은 RDS IAM 연결별 token 주입으로 격리되며, 후속 issue #295에서 재사용 가능한 `bluetape4k-jdbc` 추상화를 추적한다.

### 4. 직렬화와 값 객체

- 게시 모듈의 운영/테스트 data class 스캔에서 분류되지 않은 `Serializable` 누락은 0건이다.
- 기존 sealed parent 사례는 계속 상속된다(`KinesisStartingPosition`, `S3KtorServerSideEncryption`, `DynamoFunction`, SQS `Parameter`).
- 변경된 data class와 fixture에 `serialVersionUID`를 추가했다.

### 5. 테스트와 Assertion

- `org.junit.jupiter.api.assertThrows`, `Assertions.assertSame`, `kotlin.test.assertFailsWith` 원시 assertion import 스캔 결과 `aws-*/src`에서 0건이다.
- 영향받은 테스트를 `bluetape4k-assertions`로 교체했다.
- 저장소 전체 Kotlin 소스의 `!!` 스캔 결과는 0건이다.
- `aws-kotlin` AWS SDK response 테스트는 이제 Kotlin non-null assertion 대신 `shouldNotBeNull()`을 사용한다.

### 6. 하위 호환성

- `AwsSecretString.of(value)`와 `awsSecretStringOf(value)`는 계속 사용할 수 있다.
- `AwsSecretString` 직접 constructor는 이전에 public이었으나 이 PR은 factory guard를 강제하도록 의도적으로 범위를 좁힌다. 공개 factory API가 의도된 생성 경로를 제공한다.
- `HikariAwsJdbcDataSourceFactory.create(...)`는 계속 `HikariDataSource`를 반환한다.

### 7. 검증 증거

- `./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-kotlin:compileKotlin :bluetape4k-aws-exposed:compileKotlin :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-spring-boot:compileKotlin`: PASS.
- `./gradlew :bluetape4k-aws-kotlin:test`: PASS, 489개 통과 + 12개 pending.
- `./gradlew :bluetape4k-aws-exposed:test --tests "io.bluetape4k.aws.exposed.AwsExposedDatabaseFactoryTest" :bluetape4k-aws-kotlin:test --tests "io.bluetape4k.aws.kotlin.http.*" --tests "io.bluetape4k.aws.kotlin.kinesis.*" --tests "io.bluetape4k.aws.kotlin.sesv2.SesV2ClientExtensionsMockTest" :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.kms.KmsEncryptedFieldCodecTest" --tests "io.bluetape4k.aws.spring.secretsmanager.SecretsValueTest" --tests "io.bluetape4k.aws.spring.parameterstore.ParameterStoreValueTest"`: PASS, 9 exposed + 82 kotlin + 9 spring tests.
- `./gradlew :bluetape4k-aws-java:compileTestKotlin :bluetape4k-aws-kotlin:compileTestKotlin :bluetape4k-aws-exposed:compileTestKotlin :bluetape4k-aws-ktor:test --tests "io.bluetape4k.aws.ktor.*" :bluetape4k-aws-spring-boot:compileTestKotlin`: PASS, 150 Ktor tests.
- `./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-kotlin:compileKotlin :bluetape4k-aws-exposed:compileKotlin :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-exposed:test --tests "io.bluetape4k.aws.exposed.AwsExposedDatabaseFactoryTest" :bluetape4k-aws-ktor:test --tests "io.bluetape4k.aws.ktor.*" :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.kms.KmsEncryptedFieldCodecTest" --tests "io.bluetape4k.aws.spring.secretsmanager.SecretsValueTest" --tests "io.bluetape4k.aws.spring.parameterstore.ParameterStoreValueTest"`: PASS, 150 Ktor + 9 spring tests; exposed targeted task was up-to-date.
- `./gradlew --rerun-tasks :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-exposed:compileKotlin :bluetape4k-aws-kotlin:compileTestKotlin :bluetape4k-aws-spring-boot:compileTestKotlin`: PASS.
- `./gradlew --rerun-tasks :bluetape4k-aws-kotlin:compileKotlin :bluetape4k-aws-kotlin:test --tests "io.bluetape4k.aws.kotlin.kinesis.*" :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.kms.KmsEncryptedFieldCodecTest"`: PASS, 68 kotlin + 7 spring tests.
- `git diff --check`: PASS.

## 후속 조치

- #295: RDS IAM JDBC connection 생성을 재사용 가능한 `bluetape4k-jdbc` 추상화 뒤로 분리한다.
