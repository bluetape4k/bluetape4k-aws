# 이슈 294 코드 패턴 사전 점검 계획

## 목표

0.4.0 배포 전 code-pattern preflight로, repo-wide scan에서 확인된 위반 중
release-prep PR로 안전하게 처리할 수 있는 항목을 고친다. 넓은 개선은 follow-up issue로
남기고 이번 PR의 diff를 reviewable하게 유지한다.

## 실행 순서

1. **Value object 보호**
   - `AwsSecretString`를 private constructor + companion `operator fun invoke`로 바꾼다.
   - `of(value)`와 `awsSecretStringOf(value)`에서 `value.requireNotBlank("value")`를 먼저 수행한다.
   - Serialization `readResolve()`도 companion creation path를 사용한다.
   - 테스트: blank factory rejection, redaction, serialization round trip.

2. **Production data class 직렬화**
   - Published modules의 `src/main` data class scan 결과를 기준으로 missing `Serializable`
     및 missing `serialVersionUID`를 보강한다.
   - Public/configuration/value DTO는 우선 수정한다.
   - Private implementation record는 compile-safe이면 수정하고, behavior risk가 있으면 review artifact에
     follow-up으로 남긴다.

3. **Coroutine blocking 정리**
   - `withContext(Dispatchers.IO) { runInterruptible { close() } }`를
     `runInterruptible(Dispatchers.IO) { close() }`로 단순화한다.
   - Synchronous Ktor/Spring lifecycle bridge의 `runBlocking(Dispatchers.IO)`는 framework hook이
     suspend가 아니므로 이번 PR에서는 예외로 유지한다.

4. **Assertion/test 생태계 정리**
   - Raw `kotlin.test.assertFailsWith`, JUnit `assertThrows`, JUnit `assertSame`, `fail` 사용 후보를
     `bluetape4k-assertions`로 바꾼다.
   - AWS SDK nullable response 검증에서 Kotlin `!!` 대신 `shouldNotBeNull()`을 사용한다.
   - Testcontainers-backed tests는 serial targeted execution만 수행한다.

5. **생태계 재사용 후속 작업 분류**
   - `AwsJdbcDataSourceFactory`의 Hikari 생성부는 중앙 catalog 좌표를 확인한 뒤
     `bluetape4k-jdbc` alias와 `hikariDataSourceOf` helper로 대체한다.
   - RDS IAM `DataSource` 내부의 per-connection `DriverManager` path는 토큰을 매 연결마다
     주입해야 하므로 이번 safe preflight에서 강제로 제거하지 않고 follow-up issue로 분리한다.
   - Production nonce helper는 `Base58.randomString`으로 대체한다.
   - UUID/random candidates가 tests/examples에 남아 있으면 touched files만 정리하고 나머지는 follow-up으로 분리한다.

6. **증거 및 PR gate**
   - `git diff --check`
   - 범위가 좁은 모듈 compile/test:
     - `./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-kotlin:compileKotlin :bluetape4k-aws-exposed:compileKotlin :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-spring-boot:compileKotlin`
     - `./gradlew :bluetape4k-aws-exposed:test --tests "io.bluetape4k.aws.exposed.AwsExposedDatabaseFactoryTest"`
     - `./gradlew :bluetape4k-aws-ktor:test --tests "io.bluetape4k.aws.ktor.*"`
     - `./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.kms.KmsEncryptedFieldCodecTest"`
     - `./gradlew :bluetape4k-aws-kotlin:test`
     - 구현이 기계적인 직렬화 선언을 넘어 동작에 영향을 주면 더 좁은 모듈 테스트를 추가한다.
   - `P0 = 0`, `P1 = 0`을 요구하는 Step 6-R 7-Tier review.
   - Lesson under `docs/lessons/`.
   - `## DoD Status`로 끝나는 PR 본문.

## 검증 기준

- 유효하지 않은 `AwsSecretString`을 직접 생성하는 경로가 남아 있지 않다.
- 공개 모듈의 production data class scan에서 `Serializable`과 UID 개수가 개선된다.
- 변경한 coroutine close 경로가 compile되고 cancellation 친화적인 blocking 경계를 보존한다.
- 변경한 테스트가 raw assertion API를 더 이상 import하지 않고 저장소 전체 Kotlin 소스 scan에 `!!`가 없다.
- 수정하지 않은 광범위한 생태계 재사용 항목은 근거와 함께 후속 이슈로 추적한다.

## 리스크와 대응

| 위험 | 대응 |
|---|---|
| 많은 data class에 `Serializable`을 추가하면 광범위하고 불필요한 diff가 생긴다. | 기계적 변경으로 제한하고 동작 변경을 피하며 범위가 좁은 compile을 실행한다. |
| Private constructor가 Java 호출자를 깨뜨린다. | `AwsSecretString.of(value)`를 보존하고 Kotlin에서 편리한 `invoke`를 추가하며 공개 factory는 제거하지 않는다. |
| JDBC/DataSource 구현 교체가 runtime 동작을 바꾼다. | Hikari 생성에만 `bluetape4k-jdbc`를 사용하고 RDS IAM `DriverManager` 추상화는 #295로 미룬다. |
| Testcontainers 검사가 느리거나 불안정하다. | 변경한 container 기반 경로에만 범위가 좁은 검사를 직렬로 실행한다. |
