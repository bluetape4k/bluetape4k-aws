# Issue #472 AWS emulator Testcontainers ServiceConnection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `$subagent-driven-development` or `$executing-plans`. Each task below is independently verifiable and uses checkbox syntax.

**Goal:** 기존 `aws-spring-boot` 모듈에 선택적인 Spring Boot 4 Testcontainers
`@ServiceConnection` 연결을 추가해 Floci/LocalStack의 endpoint, region, 테스트
credential을 S3, SQS, SNS, DynamoDB, Kinesis 자동 구성에 전달한다. 운영 runtime
POM에는 Testcontainers를 전이하지 않고, 기존 properties-only 소비자 경로와
단일 bucket/소유 리소스 경계를 보존한다.

**Architecture:** `io.bluetape4k.aws.spring.connection`의 public
`AwsServiceConnectionDetails` 계약과 5개
`ContainerConnectionDetailsFactory`가 exact Floci/LocalStack container만
인식한다. 서비스 auto-configuration은 details → service properties → shared
properties 순으로 endpoint/region을 해석하고, `AwsAutoConfiguration`은 모든
details의 credential tuple을 한 번 검증한 뒤 static provider를 만든다. named
connection을 기본값으로 하고 unnamed all-services는 명시적 opt-in으로만
허용한다. factory는 리소스 URL이나 container lifecycle을 소유하지 않는다.

**Tech Stack:** Kotlin, Spring Boot 4.1.x, Spring Boot Testcontainers,
Testcontainers JUnit 5, AWS SDK v2, Gradle version catalog, JUnit 5,
ApplicationContextRunner, FilteredClassLoader, Floci, LocalStack, Spring AOT.

**Source of truth:**
[approved design spec](../specs/2026-08-24-issue-472-service-connection-design.md)
(commit `d312418`). 구현은 이 계획의 파일 경계와 명령을 벗어나지 않는다.

---

## 1. 변경 파일과 책임

### Production source

- `gradle/libs.versions.toml`
  - `libs.spring.boot.testcontainers` alias를 추가한다.
- `aws-spring-boot/build.gradle.kts`
  - `spring-boot-testcontainers`와
    `bt4k.bluetape4k.testcontainers`를 `compileOnly`로 둔다.
  - 기존 `testImplementation extendsFrom(compileOnly, runtimeOnly)`를
    활용해 library test classpath를 구성하고
    `libs.testcontainers.junit.jupiter`를 명시한다.
  - 모든 Test task에서 `filter.setFailOnNoMatchingTests(true)`를 고정한다.
- `aws-spring-boot/src/main/resources/META-INF/spring.factories`
  - Boot 4.1 runtime이 읽는 `ConnectionDetailsFactory` key와 호환 계약인
    `ContainerConnectionDetailsFactory` key에 5개 factory를 등록한다.
  - 기존 EnvironmentPostProcessor와 ConfigData key는 변경하지 않는다.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/connection/AwsServiceConnectionDetails.kt`
  - `ConnectionDetails`, 공통 `AwsServiceConnectionDetails`, 그리고 정확히
    `S3ConnectionDetails`, `SqsConnectionDetails`, `SnsConnectionDetails`,
    `DynamoDbConnectionDetails`, `KinesisConnectionDetails` public interface와
    non-blank/absolute-URI KDoc을 둔다.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/connection/AwsServiceConnectionConfigurationException.kt`
  - public `Reason`, `serviceNames`, `candidateCount`만 안정 계약으로 갖는
    secret-free startup exception을 둔다. message는 진단용이며 parsing 대상이
    아니다.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/connection/AwsServiceConnectionDetailsSupport.kt`
  - `FlociServer`/`LocalStackServer` exact allow-list, endpoint/region/
    credential validation, immutable value copy, service-details unique
    resolver, redacted representation을 구현한다.
  - `AwsEmulatorServer`만 구현한 임의 container와 MiniStack은 `null`로
    거부한다. 지원 container의 malformed 값은 위 exception으로 실패시킨다.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/connection/S3ContainerConnectionDetailsFactory.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/connection/SqsContainerConnectionDetailsFactory.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/connection/SnsContainerConnectionDetailsFactory.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/connection/DynamoDbContainerConnectionDetailsFactory.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/connection/KinesisContainerConnectionDetailsFactory.kt`
  - `ContainerConnectionDetailsFactory<Container<*>, D>` exact generic,
    `ContainerConnectionSource<Container<*>>` constructor, required AWS SDK
    class name, Boot protected nested details subclass를 각각 구현한다.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/connection/AwsServiceConnectionCredentialsResolver.kt`
  - 공통 details `ObjectProvider`를 materialize하고 동일
    `(accessKey, secretKey)` tuple만 하나의 static credential value로
    deduplicate한다. 서로 다르면 secret 없이 예외를 낸다.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/AwsClientBuilderSupport.kt`
  - details를 첫 입력으로 받는 endpoint/region resolver overload와
    service-specific unique details helper를 추가한다. 기존 properties-only
    overload와 region validation은 유지한다.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/AwsAutoConfiguration.kt`
  - 기본 `AwsCredentialsProvider`에 공통 details resolver를 주입한다.
  - custom provider, web-identity provider, `@ConditionalOnMissingBean` 우선
    순서를 보존하고 details가 없을 때만 `DefaultCredentialsProvider`를 쓴다.
- 다음 5개 auto-configuration에 해당 details `ObjectProvider`를 주입한다.
  - `.../s3/S3AutoConfiguration.kt`
  - `.../sqs/SqsAutoConfiguration.kt`
  - `.../sns/SnsAutoConfiguration.kt`
  - `.../dynamodb/DynamoDbAutoConfiguration.kt`
  - `.../kinesis/KinesisAutoConfiguration.kt`
  - 각 client builder의 details → service/shared property precedence와
    기존 custom client back-off를 유지한다.

### Tests

- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/connection/AwsServiceConnectionDetailsFactoryTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/connection/AwsServiceConnectionDetailsRedactionTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/connection/AwsServiceConnectionAutoConfigurationTest.kt`
  - factory signature/discovery, allow-list, malformed/duplicate/conflicting
    details, precedence, provider/client back-off, optional classpath matrix를
    `ApplicationContextRunner`와 `FilteredClassLoader`로 검증한다.
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/connection/AwsServiceConnectionTestFixtures.kt`
  - fake details, owner-token fixture, sanitized primary/suppressed failure,
    no-secret assertion을 공유한다.
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/connection/AwsServiceConnectionLifecycleTest.kt`
  - cleanup/context/container 순서, cancellation 재전파, close 실패 승격과
    재시작 시 새 기준 값 생성을 emulator acceptance count와 분리해 검증한다.
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/connection/AwsServiceConnectionFlociAwsEmulatorTest.kt`
  - `@JvmField @Container @ServiceConnection(name = "s3")`와
    `FlociServer.Launcher.floci`를 사용해 S3 single-bucket round-trip을
    수행한다.
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/connection/AwsServiceConnectionLocalStackAwsEmulatorTest.kt`
  - 동일 계약을 `LocalStackServer.Launcher.getLocalStack("s3")`로 별도
    실행하고 actual backend type을 assert한다. container 종료 뒤에도
    불변 기준값을 사용한다.
- 각 emulator class의 필수 test ID는
  `serviceConnectionUsesExpectedBackend`와
  `s3RoundTripStaysWithinOwnerBucket`이며 lane마다 정확히 2개가 실행되어야
  한다. cleanup은 `fixture cleanup → context close → container teardown` 순서를
  지키고 독립 cleanup client는 `use`/`finally`로 닫는다. primary failure가
  있으면 sanitized cleanup/close failure를 suppressed로 붙이고, 없으면
  cleanup/close failure를 primary로 승격하며 cancellation은 재전파한다.

### Consumer examples and docs

- `examples/aws-spring-boot-s3-examples/build.gradle.kts`
- `examples/aws-spring-boot-sqs-examples/build.gradle.kts`
- `examples/aws-spring-boot-dynamodb-examples/build.gradle.kts`
  - `testImplementation(libs.spring.boot.testcontainers)`를 추가하고 기존
    `bt4k.bluetape4k.testcontainers`와 함께 consumer classpath를 고정한다.
  - 다음 exact test source에 dedicated AOT contract declaration을 두어
    `processAot`/`processTestAot`가 실제 consumer syntax를 컴파일하게 한다.
    Floci와 LocalStack source type은 각각 별도 fixture로 유지한다.
    - `examples/aws-spring-boot-s3-examples/src/test/kotlin/io/bluetape4k/aws/examples/spring/s3/S3ServiceConnectionAotTest.kt`
    - `examples/aws-spring-boot-sqs-examples/src/test/kotlin/io/bluetape4k/aws/examples/spring/sqs/SqsServiceConnectionAotTest.kt`
    - `examples/aws-spring-boot-dynamodb-examples/src/test/kotlin/io/bluetape4k/aws/examples/spring/dynamodb/DynamoDbServiceConnectionAotTest.kt`
- `aws-spring-boot/README.md`
- `aws-spring-boot/README.ko.md`
- `docs/manual/en/modules/bluetape4k-aws-spring-boot/auto-configuration.md`
- `docs/manual/ko/modules/bluetape4k-aws-spring-boot/auto-configuration.md`
  - named/unnamed migration, dependency alias, properties fallback,
    resource URL ownership, single bucket, Floci/LocalStack 순서와
    cancellation/cleanup 경계를 동일 구조로 문서화한다.

---

## 2. 구현 순서와 TDD 작업

### Task 1 — optional classpath와 no-match gate 고정

- [ ] `gradle/libs.versions.toml`에
  `spring-boot-testcontainers = { module = "org.springframework.boot:spring-boot-testcontainers" }`
  를 추가한다.
- [ ] `aws-spring-boot/build.gradle.kts`에서 두 Testcontainers artifact를
  `compileOnly`로 선언하고 `libs.testcontainers.junit.jupiter`를 test scope에
  명시한다. 기존 `testImplementation(bt4k.bluetape4k.testcontainers)`는
  compileOnly 상속으로 중복되지 않게 정리한다.
- [ ] `tasks.withType<Test>().configureEach { filter.setFailOnNoMatchingTests(true) }`
  를 두고, `skipAwsEmulatorTests`가 있을 때 `**/*AwsEmulatorTest.class`를
  제외한다. 이 저장소의 두 canonical emulator test만 해당 suffix를 사용하며,
  no-match selector는 별도 fail-closed gate로 고정한다.
- [ ] 세 example build file에 `testImplementation(libs.spring.boot.testcontainers)`를
  추가한다. production `implementation`이나 settings/BOM module은 추가하지
  않는다.

**Validation:**

```bash
./gradlew :bluetape4k-aws-spring-boot:dependencies \
  --configuration runtimeClasspath --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:outgoingVariants \
  --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:dependencyInsight \
  --dependency org.springframework.boot:spring-boot-testcontainers \
  --configuration compileClasspath --no-daemon --max-workers=1
```

Expected: runtime classpath/outgoing runtime variant에
`spring-boot-testcontainers`와 `bluetape4k-testcontainers`가 없고, Gradle
configuration이 성공한다. `spring-boot-testcontainers`의 resolved version은
독립적으로 pin하지 않고 workspace의 `bt4k.versions.spring.boot` 및
`spring-boot-dependencies`가 선택한 동일한 Boot 4.1.x version으로 고정되며,
dependencyInsight 출력에 그 exact version을 증거로 남긴다.

### Task 2 — RED contract tests와 public API

- [ ] 위의 세 contract test 파일을 먼저 만들고, public interface와
  `AwsServiceConnectionConfigurationException`의 exact package/fields/KDoc를
  정의한다.
- [ ] public API identifier를 다음 exact declaration으로 고정한다.

  ```kotlin
  interface AwsServiceConnectionDetails : ConnectionDetails {
      val endpoint: URI
      val region: String
      val accessKey: String
      val secretKey: String
  }
  interface S3ConnectionDetails : AwsServiceConnectionDetails
  interface SqsConnectionDetails : AwsServiceConnectionDetails
  interface SnsConnectionDetails : AwsServiceConnectionDetails
  interface DynamoDbConnectionDetails : AwsServiceConnectionDetails
  interface KinesisConnectionDetails : AwsServiceConnectionDetails

  class AwsServiceConnectionConfigurationException(
      val reason: Reason,
      val serviceNames: Set<String>,
      val candidateCount: Int,
      causeSummary: String? = null,
  ) : IllegalStateException(/* secret-free diagnostic; causeSummary is class-name-only */) {
      enum class Reason { FACTORY_LINKAGE, DUPLICATE_DETAILS, CREDENTIAL_CONFLICT, MALFORMED_DETAILS }
  }
  ```

  named 소비자 선언과 unnamed all-services opt-in은 `@Testcontainers` class의
  `companion object` 안에서 다음 exact 형태로 고정한다. 두 선언은 동시에
  사용하지 않는다.

  ```kotlin
  // AOT contract source: no @Testcontainers extension, so this declaration does not start Docker.
  class S3ServiceConnectionAotTest {
      companion object {
          @JvmField
          @Container
          @ServiceConnection(name = "s3")
          val floci: FlociServer = FlociServer()
      }
  }

  // AOT contract source: no @Testcontainers extension, so this declaration does not start Docker.
  class AllAwsServicesConnectionAotTest {
      companion object {
          @JvmField
          @Container
          @ServiceConnection
          val allServices: FlociServer = FlociServer()
      }
  }
  ```
- [ ] `AwsServiceConnectionDetailsFactoryTest`에 5개 factory service name,
  required SDK class name, exact `spring.factories` line, Kotlin
  `@ServiceConnection(name = "s3")`/unnamed declaration compile contract,
  Floci/LocalStack allow-list, GenericContainer/MiniStack rejection,
  malformed supported container failure을 추가한다.
- [ ] `AwsServiceConnectionDetailsRedactionTest`에 immutable value copy,
  `[REDACTED]` toString, log/exception/metric/serialization secret 부재,
  raw cause/suppressed message·stacktrace 제거, container stop 후 stale details
  불변을 추가한다.
- [ ] public configuration exception은 raw `Throwable`을 cause 또는 suppressed로
  연결하지 않는다. production helper는 throwable class name만 가진
  secret-free summary를 만들고, cancellation만 원래 throwable을 재전파한다.
  cause-chain/stacktrace/message에 access key, secret key, endpoint가 남지 않는
  것을 단위 테스트로 고정한다.
- [ ] `AwsServiceConnectionAutoConfigurationTest`에 아직 구현되지 않은
  details precedence, common credential conflict, duplicate service candidate,
  custom provider/client back-off assertion을 추가한다.
- [ ] 각 factory의 required SDK class가 classpath에 있을 때만 discovery되고,
  `FilteredClassLoader`로 해당 class를 제거하면 linkage가
  `FACTORY_LINKAGE`로 fail-closed 되는 compile/context contract를 추가한다.

**Validation (RED expected):**

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests '*AwsServiceConnectionDetailsFactoryTest' \
  --tests '*AwsServiceConnectionDetailsRedactionTest' \
  --tests '*AwsServiceConnectionAutoConfigurationTest' \
  --no-daemon --max-workers=1
```

Expected: target test가 없거나 구현 assertion이 실패한다. no-match가 녹색으로
통과하면 build gate 설정이 잘못된 것이다.

### Task 3 — details model, support, five factory implementation

- [ ] `AwsServiceConnectionDetailsSupport.kt`에 endpoint absolute URI,
  non-blank region/credential, exact concrete class validation, immutable
  value copy, safe diagnostics를 구현한다.
- [ ] 각 factory가 다음 exact constructor를 사용하게 한다.

| Factory | Constructor call | Required class | Public detail interface / nested implementation |
| --- | --- | --- | --- |
| S3 | `super("s3", "software.amazon.awssdk.services.s3.S3Client")` | `S3Client` | `S3ConnectionDetails` / `S3ContainerConnectionDetails` |
| SQS | `super("sqs", "software.amazon.awssdk.services.sqs.SqsClient")` | `SqsClient` | `SqsConnectionDetails` / `SqsContainerConnectionDetails` |
| SNS | `super("sns", "software.amazon.awssdk.services.sns.SnsClient")` | `SnsClient` | `SnsConnectionDetails` / `SnsContainerConnectionDetails` |
| DynamoDB | `super("dynamodb", "software.amazon.awssdk.services.dynamodb.DynamoDbClient")` | `DynamoDbClient` | `DynamoDbConnectionDetails` / `DynamoDbContainerConnectionDetails` |
| Kinesis | `super("kinesis", "software.amazon.awssdk.services.kinesis.KinesisClient")` | `KinesisClient` | `KinesisConnectionDetails` / `KinesisContainerConnectionDetails` |

- [ ] 각 detail 구현체는
  `ContainerConnectionDetailsFactory.ContainerConnectionDetails<Container<*>>`
  를 상속하고 `(ContainerConnectionSource<Container<*>>)`만 받는다. Boot의
  protected 중첩 타입 접근 문제를 피하기 위해 각 detail 구현체는 대응
  factory subclass 내부의 `private class Details(...)`로만 선언한다. top-level
  detail class는 만들지 않으며, factory는 다음 모양을 고정한다.

  ```kotlin
  class S3ContainerConnectionDetailsFactory :
      ContainerConnectionDetailsFactory<Container<*>, S3ConnectionDetails>(
          "s3", "software.amazon.awssdk.services.s3.S3Client"
      ) {
      override fun getContainerConnectionDetails(
          source: ContainerConnectionSource<Container<*>>,
      ): S3ConnectionDetails = S3ContainerConnectionDetails(source)

      private class S3ContainerConnectionDetails(source: ContainerConnectionSource<Container<*>>) :
          ContainerConnectionDetailsFactory.ContainerConnectionDetails<Container<*>>(source),
          S3ConnectionDetails {
          // immutable validated values delegated to shared support
      }
  }
  ```

  나머지 4개 factory도 같은 중첩 위치와 `ContainerConnectionSource` import를
  사용하며, Boot 4.1.x의 resolved artifact가 제공하는 위 constructor overload와
  protected nested type을 compile test로 고정한다.
- [ ] `spring.factories`에 5개 fully-qualified factory를 comma-separated로
  등록한다. `AutoConfiguration.imports`에는 testcontainers factory를 넣지
  않는다.

**Validation (factory GREEN):**

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests '*AwsServiceConnectionDetailsFactoryTest' \
  --tests '*AwsServiceConnectionDetailsRedactionTest' \
  --no-daemon --max-workers=1
```

Expected: exact factory discovery, source matching, validation, redaction,
unsupported/null과 malformed/error 분리가 통과한다.

### Task 4 — credential resolver와 endpoint/region precedence

- [ ] `AwsServiceConnectionCredentialsResolver`를 구현해
  `ObjectProvider<AwsServiceConnectionDetails>.orderedStream().toList()`를
  한 번 materialize한다. 동일 credential tuple은 deduplicate하고 서로 다른
  tuple은 `CREDENTIAL_CONFLICT`로 실패시킨다. 예외에 secret을 넣지 않는다.
- [ ] `AwsAutoConfiguration.defaultAwsCredentialsProvider`가 resolver 결과를
  `StaticCredentialsProvider`로 변환하고, details 없음은 기존
  `DefaultCredentialsProvider`, custom/web identity provider는 기존 조건으로
  유지한다.
- [ ] `AwsClientBuilderSupport`에 details-first overload와 service-specific
  unique resolver를 추가한다. 동일 service details가 둘이면
  `DUPLICATE_DETAILS`로 실패한다.
- [ ] S3 sync/async/presigner, SQS, SNS, DynamoDB, Kinesis client bean method에
  해당 details `ObjectProvider`를 추가하고 existing custom client/provider
  back-off를 보존한다.

**Validation:**

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests '*AwsServiceConnectionAutoConfigurationTest' \
  --no-daemon --max-workers=1
```

Expected: 5 services의 details > service property > shared property precedence,
common credential consistency, duplicate/conflict startup failure,
custom provider/client back-off, no-details properties-only 회귀가 통과한다.

### Task 5 — optional-classpath/linkage와 properties-only matrix

- [ ] `FilteredClassLoader` 네 조합을 분리한다: 두 optional dependency 모두
  있음, `spring-boot-testcontainers`만 없음,
  `bluetape4k-testcontainers`만 없음, 둘 다 없음.
- [ ] annotation 없는 조합은 기존 properties/default provider를 성공으로
  인정한다. annotation이 있는데 factory linkage가 빠진 조합은
  `FACTORY_LINKAGE` startup error를 기대한다.
- [ ] S3/SQS/SNS/DynamoDB/Kinesis 각각에 대해 details 없음 + service/shared
  property와 optional dependency filtered 조건을 모두 확인한다.
- [ ] `spring.factories` resource assertion이 실제 application classpath에서
  모든 factory를 발견하는지 확인한다.

**Validation:**

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests '*AwsServiceConnectionAutoConfigurationTest' \
  --no-daemon --max-workers=1
```

Expected: 5 × 2 properties-only matrix와 four-way optional classpath matrix가
실행되고, resource/client fixture API 호출 없이 기존 경로가 유지된다.

### Task 6 — Floci/LocalStack integration and lifecycle safety

- [ ] `AwsServiceConnectionFlociAwsEmulatorTest`에 exact static `FlociServer`
  declaration을 두고 one-owner bucket/object S3 round-trip을 수행한다.
- [ ] `AwsServiceConnectionLocalStackAwsEmulatorTest`에 exact static
  `LocalStackServer` declaration을 두고 `-Dbluetape4k.aws.emulator=localstack`이
  실제 backend를 선택하는지 assert한다.
- [ ] fixture helper가 owner token을 bucket/object/queue/topic/table/stream
  literal에 포함하고 request/cleanup 직전에 literal ownership을 검증한다.
  wildcard, foreign resource, bucket enumeration을 AWS 호출 전에 거부한다.
- [ ] 정상, startup failure, cancellation, cleanup failure, container restart의
  lifecycle test를 `AwsServiceConnectionLifecycleTest`에 추가한다. 독립 cleanup
  client close ownership과 primary/suppressed/primary promotion을 assertion하고,
  두 emulator acceptance class에는 지정된 두 test ID만 둔다.
- [ ] SQS/SNS/DynamoDB/Kinesis는 URL/ARN/name을 factory에서 만들지 않고
  explicit fixture가 create → inject → owner cleanup하는 경로를 테스트 helper와
  문서 예시에 남긴다.

**Validation order (fail-fast):**

```bash
set -euo pipefail
verify_emulator_report() {
  local path="$1"
  ruby -rrexml/document -e '
    path = ARGV.fetch(0)
    suite = REXML::Document.new(File.read(path)).root
    normalize = ->(name) { name.sub(/\(\)\z/, "").split(".").last }
    names = suite.elements.to_a("testcase").map { |testcase| normalize.call(testcase.attributes.fetch("name")) }.sort
    expected = ["s3RoundTripStaysWithinOwnerBucket", "serviceConnectionUsesExpectedBackend"].sort
    tests = suite.attributes.fetch("tests").to_i
    skipped = suite.attributes.fetch("skipped", "0").to_i
    failures = suite.attributes.fetch("failures", "0").to_i
    errors = suite.attributes.fetch("errors", "0").to_i
    abort "invalid emulator report: #{path}" unless tests == 2 && names == expected && skipped == 0 && failures == 0 && errors == 0
  ' "$path"
}

./gradlew :bluetape4k-aws-spring-boot:test \
  --tests '*AwsServiceConnectionAutoConfigurationTest' \
  -PskipAwsEmulatorTests=true --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests '*AwsServiceConnectionLifecycleTest' \
  -PskipAwsEmulatorTests=true --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests 'io.bluetape4k.aws.spring.connection.AwsServiceConnectionFlociAwsEmulatorTest' \
  -Dbluetape4k.aws.emulator=floci --no-daemon --max-workers=1
verify_emulator_report \
  aws-spring-boot/build/test-results/test/TEST-io.bluetape4k.aws.spring.connection.AwsServiceConnectionFlociAwsEmulatorTest.xml

./gradlew :bluetape4k-aws-spring-boot:test \
  --tests 'io.bluetape4k.aws.spring.connection.AwsServiceConnectionLocalStackAwsEmulatorTest' \
  -Dbluetape4k.aws.emulator=localstack --no-daemon --max-workers=1
verify_emulator_report \
  aws-spring-boot/build/test-results/test/TEST-io.bluetape4k.aws.spring.connection.AwsServiceConnectionLocalStackAwsEmulatorTest.xml
```

The first command is the properties-only rollback baseline. If it fails, stop
the lane. If Floci fails, is no-match, or is entirely skipped, record the
properties baseline evidence and stop; LocalStack may be run only as compatibility
evidence and never promotes the feature to acceptance PASS. Each backend class
must report exactly the two required test IDs, with zero skipped tests. Record
`command`, `backend`, `timestamp`, `commit` (`git rev-parse HEAD`),
`ci_run_id_or_local` (`GITHUB_RUN_ID` or `local`), Docker context/info, owner,
actual test count, JUnit XML report path, and result. Docker unavailable is
`PENDING`, not PASS. The Floci XML gate must pass before the LocalStack command
is started; a failed, skipped, or under-count Floci lane stops acceptance and
leaves any later LocalStack result compatibility-only.

### Task 7 — consumer AOT, docs, and migration contract

- [ ] 세 example build file에 Spring Boot Testcontainers test dependency를
    추가하고, 위 세 exact AOT test source에 `@ServiceConnection` static
    declaration을 둔다. S3는 `name = "s3"`, SQS는 `name = "sqs"`,
    DynamoDB는 `name = "dynamodb"`를 사용하며, Floci와 LocalStack source
    type을 섞지 않는다. 해당 declaration은 AOT compile contract만 검증하고,
    emulator smoke lifecycle은 Task 6의 library test가 소유한다.
- [ ] README EN/KO와 manual EN/KO에 다음 before/after를 같은 구조로 추가한다.
  - `DynamicPropertySource` → named ServiceConnection migration
  - unnamed all-services explicit opt-in
  - `-Dbluetape4k.aws.emulator`는 backend selector이며 resource URL source가 아님
  - SQS queue URL, SNS topic ARN, DynamoDB table name, Kinesis stream name은
    fixture가 생성·주입·소유 cleanup
  - one bucket/owner token/wildcard rejection와 cleanup order
  - optional dependency 누락과 `FACTORY_LINKAGE` 대응
- [ ] 다음 AOT task를 순차 실행하고 generated output에서 factory discovery와
    optional linkage를 확인한다. 각 example의 generated output 경로는
    `build/generated/aotSources`, `build/generated/aotResources`,
    `build/generated/aotTestSources`, `build/generated/aotTestResources`로
    고정하고, 네 디렉터리 존재/비어 있지 않음과
    `AwsServiceConnection|ContainerConnection` 문자열을 assertion한다.

```bash
set -euo pipefail
./gradlew :aws-spring-boot-s3-examples:processAot \
  :aws-spring-boot-s3-examples:processTestAot --no-daemon --max-workers=1
./gradlew :aws-spring-boot-sqs-examples:processAot \
  :aws-spring-boot-sqs-examples:processTestAot --no-daemon --max-workers=1
./gradlew :aws-spring-boot-dynamodb-examples:processAot \
  :aws-spring-boot-dynamodb-examples:processTestAot --no-daemon --max-workers=1

for module in aws-spring-boot-s3-examples aws-spring-boot-sqs-examples aws-spring-boot-dynamodb-examples; do
  test -d "examples/$module/build/generated/aotSources"
  test -d "examples/$module/build/generated/aotResources"
  test -d "examples/$module/build/generated/aotTestSources"
  test -d "examples/$module/build/generated/aotTestResources"
  for output in aotSources aotResources aotTestSources aotTestResources; do
    directory="examples/$module/build/generated/$output"
    file_count=$(find "$directory" -type f | wc -l | tr -d ' ')
    test "$file_count" -gt 0
  done
  find "examples/$module/build/generated/aotSources" \
       "examples/$module/build/generated/aotTestSources" -type f \
       -exec rg -l 'AwsServiceConnection|ContainerConnection' {} + >/dev/null
done
test -f aws-spring-boot/src/main/resources/META-INF/spring.factories
for factory in S3ContainerConnectionDetailsFactory SqsContainerConnectionDetailsFactory \
    SnsContainerConnectionDetailsFactory DynamoDbContainerConnectionDetailsFactory \
    KinesisContainerConnectionDetailsFactory; do
  rg -n "$factory" aws-spring-boot/src/main/resources/META-INF/spring.factories
done
```

- [ ] README/manual parity와 manifest를 검증한다.

```bash
ruby scripts/manual/manual_contract_test.rb
ruby scripts/manual/export_manifest.rb \
  docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
ruby -e '
  en = File.read("docs/manual/en/modules/bluetape4k-aws-spring-boot/auto-configuration.md")
  ko = File.read("docs/manual/ko/modules/bluetape4k-aws-spring-boot/auto-configuration.md")
  headings = ->(text) { text.lines.grep(/^#+\s/).map { |line| line[/^#+/].length } }
  abort "manual heading structure mismatch" unless headings.call(en) == headings.call(ko)
  %w[manualId chapterId].each do |key|
    en_value = en[/^#{key}:\s*(.+)$/, 1]
    ko_value = ko[/^#{key}:\s*(.+)$/, 1]
    abort "manual front matter mismatch: #{key}" unless en_value == ko_value
  end
'
ruby -e '
  paths = ["aws-spring-boot/README.md", "aws-spring-boot/README.ko.md"]
  texts = paths.to_h { |path| [path, File.read(path)] }
  levels = texts.values.map { |text| text.lines.grep(/^#+\s/).map { |line| line[/^#+/].length } }
  abort "README heading structure mismatch" unless levels.uniq.one?
  concept_tokens = %w[
    @ServiceConnection DynamicPropertySource bluetape4k.aws.emulator FACTORY_LINKAGE
    owner-token wildcard cleanup SQS SNS DynamoDB Kinesis
  ]
  manual_paths = [
    "docs/manual/en/modules/bluetape4k-aws-spring-boot/auto-configuration.md",
    "docs/manual/ko/modules/bluetape4k-aws-spring-boot/auto-configuration.md",
  ]
  (texts.keys + manual_paths).each do |path|
    text = texts[path] || File.read(path)
    concept_tokens.each do |token|
      abort "documentation concept missing: #{path}:#{token}" unless text.downcase.include?(token.downcase)
    end
  end
'
git diff --check
```

### Task 8 — full verification and completion evidence

- [ ] targeted connection tests와 두 emulator lane의 실제 실행 test count를
  기록한다.
- [ ] runtime POM, consumer `testRuntimeClasspath`, outgoing variants에서
  Testcontainers runtime leak가 없는지 확인한다.
- [ ] production publication metadata를 생성하고 exact output에서
  Testcontainers가 runtime dependency로 나오지 않는지 확인한다.

```bash
set -euo pipefail
./gradlew :bluetape4k-aws-spring-boot:generatePomFileForBluetapeAwsPublication \
  :bluetape4k-aws-spring-boot:generateMetadataFileForBluetapeAwsPublication \
  --no-daemon --max-workers=1
test -f aws-spring-boot/build/publications/BluetapeAws/pom-default.xml
test -f aws-spring-boot/build/publications/BluetapeAws/module.json
! rg -n '<artifactId>(spring-boot-testcontainers|bluetape4k-testcontainers)</artifactId>|"module"\s*:\s*"(spring-boot-testcontainers|bluetape4k-testcontainers)"' \
  aws-spring-boot/build/publications/BluetapeAws/pom-default.xml \
  aws-spring-boot/build/publications/BluetapeAws/module.json
! rg -n '<groupId>org\.testcontainers</groupId>|"group"\s*:\s*"org\.testcontainers"' \
  aws-spring-boot/build/publications/BluetapeAws/pom-default.xml \
  aws-spring-boot/build/publications/BluetapeAws/module.json
./gradlew :bluetape4k-aws-spring-boot:dependencies \
  --configuration runtimeClasspath --no-daemon --max-workers=1 > /tmp/aws-spring-boot-runtime-classpath.txt
! rg -n 'org\.testcontainers|spring-boot-testcontainers|bluetape4k-testcontainers' \
  /tmp/aws-spring-boot-runtime-classpath.txt
./gradlew :bluetape4k-aws-spring-boot:outgoingVariants \
  --no-daemon --max-workers=1 > /tmp/aws-spring-boot-outgoing-variants.txt
! rg -n 'org\.testcontainers|spring-boot-testcontainers|bluetape4k-testcontainers' \
  /tmp/aws-spring-boot-outgoing-variants.txt
for module in aws-spring-boot-s3-examples aws-spring-boot-sqs-examples aws-spring-boot-dynamodb-examples; do
  ./gradlew ":$module:dependencies" --configuration testRuntimeClasspath \
    --no-daemon --max-workers=1 > "/tmp/$module-test-runtime-classpath.txt"
  rg -n 'spring-boot-testcontainers|org\.testcontainers|bluetape4k-testcontainers' \
    "/tmp/$module-test-runtime-classpath.txt"
  ./gradlew ":$module:dependencies" --configuration runtimeClasspath \
    --no-daemon --max-workers=1 > "/tmp/$module-runtime-classpath.txt"
  ! rg -n 'spring-boot-testcontainers|org\.testcontainers|bluetape4k-testcontainers' \
    "/tmp/$module-runtime-classpath.txt"
done
```

- [ ] 다음 명령으로 stale XML을 먼저 제거한 뒤 emulator를 제외한 full module
  regression을 실행한다. 전용 Floci/LocalStack lane이 이미 exact two-ID
  acceptance를 증명하므로 이 회귀 명령에서 emulator를 중복 실행하지 않는다.
  Afterward, fail if any `*AwsEmulatorTest.xml` report exists:

```bash
set -euo pipefail
./gradlew :bluetape4k-aws-spring-boot:cleanTest \
  :bluetape4k-aws-spring-boot:test \
  -PskipAwsEmulatorTests=true --no-daemon --max-workers=1
if find aws-spring-boot/build/test-results/test -type f \
    -name '*AwsEmulatorTest.xml' -print -quit | grep -q .; then
  echo 'emulator tests were not excluded by skipAwsEmulatorTests' >&2
  exit 1
fi
```
- [ ] 세 consumer의 non-emulator AOT contract test를 별도로 실행한다.

```bash
set -euo pipefail
./gradlew :aws-spring-boot-s3-examples:test \
  --tests '*S3ServiceConnectionAotTest' --no-daemon --max-workers=1
./gradlew :aws-spring-boot-sqs-examples:test \
  --tests '*SqsServiceConnectionAotTest' --no-daemon --max-workers=1
./gradlew :aws-spring-boot-dynamodb-examples:test \
  --tests '*DynamoDbServiceConnectionAotTest' --no-daemon --max-workers=1
```

- [ ] 위 consumer test는 container를 시작하지 않는 annotation/AOT contract만
  실행한다. 기존 example의 emulator integration test 전체 실행은 이 계획의
  acceptance가 아니며, 실행하지 않은 경우 최종 DoD에 명시적인 validation gap으로
  남긴다.
- [ ] `./gradlew :bluetape4k-aws-spring-boot:detekt --no-daemon --max-workers=1`
  과 `./gradlew detekt --no-daemon --max-workers=1`를 실행한다.
- [ ] `./gradlew build -x test --parallel --no-daemon`으로 compile/package를
  확인한다.
- [ ] `git diff --check`, writer audit, changed-file review를 다시 실행한다.
  writer 결과는 JSON `findings == []`로 기계 검증하고, changed-file review는
  `git status --short`, `git diff --name-status`, `git diff --stat`와
  이 계획의 변경 파일 목록을 대조한 결과를 DoD에 남긴다.

```bash
set -euo pipefail
git diff --check
writer_report=$(mktemp)
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  --json docs/superpowers/plans/2026-08-24-issue-472-service-connection-plan.md \
  | tee "$writer_report"
ruby -rjson -e 'abort "writer findings remain" unless JSON.parse(File.read(ARGV.fetch(0))).fetch("findings").empty?' "$writer_report"
rm -f "$writer_report"
git status --short
git diff --name-status
git diff --stat
```

- [ ] 최종 DoD에 changed files, commands/results, exact test IDs/counts와 JUnit
  report path, PENDING Docker evidence(`commit`, `ci_run_id_or_local`, owner,
  Docker context/info), runtime POM/classpath proof, generated AOT paths,
  documentation parity, known risks와 validation gaps를 기록한다.

---

## 3. Acceptance mapping

| Acceptance | Plan evidence |
| --- | --- |
| 하나의 ServiceConnection으로 endpoint 연결 | Task 3 factory, Task 4 five-service builder wiring, Task 6 backend smoke |
| 불필요한 container/property 방지 | named default, service enabled conditions, no resource creation in factories |
| Floci 및 LocalStack context/smoke | Task 6 separate classes and baseline-first fail-fast commands |
| emulator selector 충돌 없음 | exact `-Dbluetape4k.aws.emulator` lanes and backend type assertions |
| 운영 credential/profile 제외 | static test credentials only when details exist; custom/web identity precedence preserved |
| Spring Cloud AWS 복사 금지 | Boot 4 factory API only; no new Spring Cloud dependency |
| 단일 bucket 권한 경계 | owner-token literal fixture and pre-AWS foreign/wildcard rejection |
| runtime dependency 격리 | compileOnly, consumer test alias, runtimeClasspath/POM/outgoing preflight |
| AOT와 문서 사용성 | three example AOT tasks and four EN/KO parity targets |
| public API 호환성 | exact five interface identifiers, exception fields/Reason, nested factory declaration, named/unnamed compile contract |
| secret-free failure | redacted toString/log/serialization, sanitized cause summary, no raw suppressed throwable |
| lifecycle/cancellation | separate lifecycle test, cleanup → context close → teardown order, cancellation rethrow and primary promotion |

---

## 4. Rollback and stop conditions

- If any contract test fails, stop before emulator round-trip work.
- If properties-only baseline fails, do not continue to Floci or LocalStack; revert
  only the implementation files explicitly listed by the current change after
  recording `git status --short` and `git diff --name-only`; preserve unrelated
  dirty work and the approved spec/plan. Do not use broad reset/checkout.
- If Floci fails/no-matches/skips, mark the feature FAIL, retain the baseline
  evidence, and do not promote LocalStack compatibility to acceptance.
- If runtime POM contains Testcontainers, remove the dependency from production
  configuration before proceeding; do not solve the leak by adding a new module.
- If Docker is unavailable, record `PENDING` with the required evidence fields and
  continue only with unit/context/AOT checks that do not claim emulator success.
- Do not publish, tag, create/merge a PR, delete branches, or alter remote refs in
  this plan. Those are separate explicit gates.

## 5. Step 3-R integrated review

Six independent perspectives reviewed this exact plan after the final edits.
P0/P1 are zero in every lane; all P2 findings were closed by the tasks and
commands above. The only remaining P3 disposition is optional automation of the
PENDING/semantic-parity evidence artifact, which is explicitly non-blocking and
must not be promoted to acceptance evidence without the required fields.

| Perspective | Reviewer lane | P0 | P1 | P2 | P3 | Disposition / rerun lane |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| Performance | `plan_api_466` | 0 | 0 | 0 | 0 | Exact selectors/counts, sequential `--max-workers=1`, AOT/POM cost gates; rerun targeted → emulator → full regression |
| Stability | `plan_api_466` | 0 | 0 | 0 | 0 | Nested protected type, lifecycle separation, stale-report skip gate, rollback stop conditions; rerun factory/lifecycle/full skip |
| Security | `plan_security_466` | 0 | 0 | 0 | 1 | P3 evidence-writer automation is optional; raw-cause prohibition, owner-token boundary, sequential Floci gate are fixed |
| Operator/Ops | `plan_security_466` | 0 | 0 | 0 | 1 | P3 semantic evidence artifact is non-blocking; PENDING schema, commit/run ID, report path, Docker context and mutation boundary are fixed |
| Developer/API | `plan_user_466` | 0 | 0 | 0 | 0 | Exact five interfaces, exception enum/fields, nested factory generic/source signature, companion declarations; rerun compile/factory/AOT |
| User/Caller | `plan_user_466` | 0 | 0 | 0 | 0 | Consumer paths, named/unnamed migration, docs parity, ownership/cleanup DoD; rerun consumer AOT/docs |

## 6. Step 3-P risk prediction

| Predicted risk | Trigger / failure signal | Mitigation fixed in this plan | Evidence and stop owner |
| --- | --- | --- | --- |
| Boot 4 constructor or protected nested type drift | factory compile failure or inaccessible nested class | exact constructor, imports, nested implementation names and compile contract in Tasks 2–3 | Task 3 factory test; stop before client wiring |
| Optional linkage or runtime dependency leak | `FilteredClassLoader` failure or Testcontainers in library runtime/POM/variant | `compileOnly`, four-way matrix, dependencyInsight, runtime/POM/metadata negative checks | Tasks 1, 5, 8; stop before emulator |
| Credential/endpoint secret disclosure | secret appears in message, cause, suppressed error, log, metric, or serialization | immutable values, `[REDACTED]`, class-name-only sanitized summary, raw `Throwable` prohibition | Redaction/exception tests; stop on any secret match |
| Cross-owner or broad AWS resource access | foreign/wildcard/bucket-enumeration literal reaches AWS API | owner-token literal validation before every request/cleanup; factory never creates resource URLs/names | Fixture/lifecycle tests; stop before retry |
| Cleanup failure masks primary failure or leaks client | close/cleanup error ordering, cancellation, or restart test failure | independent cleanup client with `use`/`finally`, suppressed vs primary promotion, cancellation rethrow | `AwsServiceConnectionLifecycleTest`; stop before emulator acceptance |
| False-green emulator lane | no-match, all-skipped, under-count, wrong test ID, or Floci failure | `failOnNoMatchingTests`, exact two IDs, JUnit XML gate after Floci and before LocalStack | Task 6 report gate; stop and mark FAIL/PENDING |
| Consumer AOT declaration mismatch | `processAot`/`processTestAot` or generated-source assertion fails | exact three source paths, named/unnamed companion syntax, generated directory and factory registration checks | Task 7 AOT lane; record validation gap |
| EN/KO documentation drift | heading/front-matter/token parity check fails | four target docs, README/manual parity scripts, writer audit JSON `findings == []` | Task 7–8 docs gate; stop documentation completion |

Implementation remains gated on explicit user approval of this plan. After
approval, use `$subagent-driven-development` or `$executing-plans`, preserve the
file boundaries above, and rerun the Task 6 baseline before any emulator lane.
