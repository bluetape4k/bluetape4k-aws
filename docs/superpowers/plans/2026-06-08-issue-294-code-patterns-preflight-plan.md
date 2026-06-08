# Issue 294 Code Patterns Preflight Plan

## 목표

0.4.0 배포 전 code-pattern preflight로, repo-wide scan에서 확인된 위반 중
release-prep PR로 안전하게 처리할 수 있는 항목을 고친다. 넓은 개선은 follow-up issue로
남기고 이번 PR의 diff를 reviewable하게 유지한다.

## 실행 순서

1. **Value object guard**
   - `AwsSecretString`를 private constructor + companion `operator fun invoke`로 바꾼다.
   - `of(value)`와 `awsSecretStringOf(value)`에서 `value.requireNotBlank("value")`를 먼저 수행한다.
   - Serialization `readResolve()`도 companion creation path를 사용한다.
   - 테스트: blank factory rejection, redaction, serialization round trip.

2. **Production data class serialization**
   - Published modules의 `src/main` data class scan 결과를 기준으로 missing `Serializable`
     및 missing `serialVersionUID`를 보강한다.
   - Public/configuration/value DTO는 우선 수정한다.
   - Private implementation record는 compile-safe이면 수정하고, behavior risk가 있으면 review artifact에
     follow-up으로 남긴다.

3. **Coroutine blocking cleanup**
   - `withContext(Dispatchers.IO) { runInterruptible { close() } }`를
     `runInterruptible(Dispatchers.IO) { close() }`로 단순화한다.
   - Synchronous Ktor/Spring lifecycle bridge의 `runBlocking(Dispatchers.IO)`는 framework hook이
     suspend가 아니므로 이번 PR에서는 예외로 유지한다.

4. **Assertion/test ecosystem cleanup**
   - Raw `kotlin.test.assertFailsWith`, JUnit `assertThrows`, JUnit `assertSame`, `fail` 사용 후보를
     `bluetape4k-assertions`로 바꾼다.
   - AWS SDK nullable response 검증에서 Kotlin `!!` 대신 `shouldNotBeNull()`을 사용한다.
   - Testcontainers-backed tests는 serial targeted execution만 수행한다.

5. **Ecosystem reuse follow-up classification**
   - `AwsJdbcDataSourceFactory`의 Hikari 생성부는 중앙 catalog 좌표를 확인한 뒤
     `bluetape4k-jdbc` alias와 `hikariDataSourceOf` helper로 대체한다.
   - RDS IAM `DataSource` 내부의 per-connection `DriverManager` path는 토큰을 매 연결마다
     주입해야 하므로 이번 safe preflight에서 강제로 제거하지 않고 follow-up issue로 분리한다.
   - Production nonce helper는 `Base58.randomString`으로 대체한다.
   - UUID/random candidates가 tests/examples에 남아 있으면 touched files만 정리하고 나머지는 follow-up으로 분리한다.

6. **Evidence and PR gates**
   - `git diff --check`
   - Targeted module compile/tests:
     - `./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-kotlin:compileKotlin :bluetape4k-aws-exposed:compileKotlin :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-spring-boot:compileKotlin`
     - `./gradlew :bluetape4k-aws-exposed:test --tests "io.bluetape4k.aws.exposed.AwsExposedDatabaseFactoryTest"`
     - `./gradlew :bluetape4k-aws-ktor:test --tests "io.bluetape4k.aws.ktor.*"`
     - `./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.kms.KmsEncryptedFieldCodecTest"`
     - `./gradlew :bluetape4k-aws-kotlin:test`
     - Add narrower module tests if implementation touches behavior beyond mechanical serialization declarations.
   - Step 6-R 7-Tier review with `P0 = 0`, `P1 = 0`.
   - Lesson under `docs/lessons/`.
   - PR body ending with `## DoD Status`.

## 검증 기준

- No remaining direct construction path for invalid `AwsSecretString`.
- Published module production data class scan shows improved `Serializable` and UID counts.
- Touched coroutine close paths compile and preserve cancellation-friendly blocking boundaries.
- Touched tests no longer import raw assertion APIs, and repo-wide Kotlin source scan has no `!!`.
- Broad ecosystem reuse items not fixed are tracked as follow-up issues with rationale.

## 리스크와 대응

| Risk | Response |
|---|---|
| Adding `Serializable` to many data classes causes noisy broad diff | Keep mechanical, avoid behavior changes, run targeted compile. |
| Private constructor breaks Java callers | Preserve `AwsSecretString.of(value)` and add Kotlin ergonomic `invoke`; do not remove public factory. |
| Replacing JDBC/DataSource implementation changes runtime behavior | Use `bluetape4k-jdbc` for Hikari construction only; defer RDS IAM `DriverManager` abstraction to #295. |
| Testcontainers checks are slow or flaky | Run serial targeted checks only for touched container-backed paths. |
