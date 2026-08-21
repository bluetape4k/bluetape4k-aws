# 이슈 #467 Spring Boot AWS ConfigData import 구현 계획

- 이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/467
- 에픽: #500 Spring Boot AWS ConfigData import
- 스택 순서: `SPRING-1` / 첫 번째 구현 PR
- 명세: `docs/superpowers/specs/2026-08-20-issue-467-configdata-design.md`
- 구현 모듈: `bluetape4k-aws-spring-boot`
- 게이트: 설계 승인 후 구현 계획 검토 대기

## 목표와 변경 경계

Spring Boot 4의 표준 `spring.config.import` lifecycle에서 S3, Parameter
Store, Secrets Manager 설정을 읽을 수 있게 한다. 기존
`EnvironmentPostProcessor`와 lazy refresh 경계는 보존하고, ConfigData 경로는
startup 시 한 번 읽는 별도 adapter로 둔다. 새 공개 converter/strategy API,
AppConfig, rotation watcher, Spring Cloud Config Server, 새로운 dependency는
추가하지 않는다.

계획은 `$bluetape-workflow`, `$bluetape-kotlin-patterns`,
`$bluetape-full-feature`, `$bluetape-writer`, TDD를 적용한다. 모든 테스트는
JUnit 5 + MockK + `bluetape4k-assertions`의 의미 있는 matcher를 사용하며,
임의의 `shouldBeTrue/False` 대신 `shouldBeEqualTo`,
`shouldBeLessOrEqualTo`, `shouldContain`, `shouldNotContain` 등을 우선한다.
`Base58.randomString(16)`은 emulator/resource 식별자와 opaque sentinel에만
사용하고, parser malformed input·hash digest·redaction golden 값은 고정
fixture로 둔다.

구현 시작 전 이 계획과 승인된 설계를 별도 계획 커밋으로 고정한다. 계획
커밋과 구현 커밋을 섞지 않으며, 구현은 아래 RED 증거가 남은 뒤에만 시작한다.

## 파일 구성

### 생성할 production 파일

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataLocation.kt`
  - backend, decoded source, query options, optional 상태를 갖는 내부 불변 모델
  - S3/Parameter Store/Secrets Manager source 변환을 위한 sealed 모델
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataLocationParser.kt`
  - prefix 제거, query 분리·단일 percent decode, 허용 키/enum/Boolean 검증,
    CR/LF/NUL·중복·빈 값 거부
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataResource.kt`
  - public Spring SPI carrier인 `ConfigDataResource` 구현; optional 플래그를
    자체 보관하고, identity는
    `(backend, canonical decoded source, query options, optional)`로만 계산
  - `toString()`은 `bluetape4k.aws.configdata.<backend>.<sha256-12>`와 안전한
    query key만 출력하며 raw location, client, logger, bound properties를
    포함하지 않음
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataSupport.kt`
  - Binder property 결합, SDK classpath guard, opaque identity/SHA-256 redaction,
    sanitized failure만 담당하며 AWS SDK type을 참조하지 않음
  - bootstrap client registration/close listener의 exactly-once 경계는
    SDK-free bridge에 위임
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataBootstrapBridge.kt`
  - Spring SPI가 AWS SDK 없는 classpath에서도 로드되도록 SDK type 없는
    `Class<*>`/`Any` 경계, bootstrap holder, initialized-only close를 담당
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataFailurePolicy.kt`
  - legacy broad-catch와 ConfigData strict not-found 정책을 분리하는 내부 정책
    및 throw-only fetch/parse 결과 경계
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ConfigDataSdkAdapter.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/parameterstore/ParameterStoreConfigDataSdkAdapter.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/secretsmanager/SecretsManagerConfigDataSdkAdapter.kt`
  - 각 SDK type을 실제 classpath guard 이후에만 로드하는 내부 adapter
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ConfigDataLocationResolver.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ConfigDataLoader.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/parameterstore/ParameterStoreConfigDataLocationResolver.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/parameterstore/ParameterStoreConfigDataLoader.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/secretsmanager/SecretsManagerConfigDataLocationResolver.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/secretsmanager/SecretsManagerConfigDataLoader.kt`

세 resolver/loader와 공통 `AwsConfigDataResource`만 Spring SPI가 요구하는
bytecode-visible public type이다. Resource의 constructor와 backend/query 모델,
공통 support 멤버는 Kotlin `internal`로 두고, resource KDoc에 소비자 확장용
API가 아님을 명시한다. SDK type은 메서드가 실제로 호출될 때까지 지연 참조하고,
disabled 경로는 SDK class guard와 bootstrap supplier 접근 전에 반환한다.

Resource의 ABI 계약은 다음으로 고정한다. `AwsConfigDataResource`는 public
class이지만 constructor는 JVM `private`이며, 내부 factory만 resolver에서
호출한다. public SPI에는 resource 자체만 노출한다. backend/source/query/options/
optional/bound-properties는 내부 immutable fields이고, `isOptionalResource`,
`isDisabled`, `backendKey` 같은 loader 전용 accessor도 `internal`이다. 세 resolver/loader는 모두 정확히
`ConfigDataLocationResolver<AwsConfigDataResource>` /
`ConfigDataLoader<AwsConfigDataResource>`를 구현한다.

### 수정할 production 파일

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ConfigPropertySourceLoader.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/parameterstore/ParameterStorePropertySourceLoader.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/secretsmanager/SecretsManagerPropertySourceLoader.kt`
  - 기존 `load(properties)` facade와 legacy fail-fast/refresh 동작은 유지한다.
  - 주입된 client와 명시적인 `AwsConfigDataFailurePolicy`를 받는 내부
    단일-source load 경계를 추가한다.
  - ConfigData는 이 경계를 사용하고 client를 생성하거나 닫지 않는다.
  - strict policy는 not-found만 optional skip하며 auth/credential/network/parse/
    configuration 오류를 삼키지 않는다.
  - legacy 로그·합성 예외의 skip/refresh 메시지에서 raw bucket/key/path/secretId를
    제거하되 기존 property-source 이름과 동작 호환성은 유지한다.
- `aws-spring-boot/src/main/resources/META-INF/spring.factories`
  - 기존 EnvironmentPostProcessor 세 항목을 그대로 유지한다.
  - `ConfigDataLocationResolver`와 `ConfigDataLoader`에 위 concrete 세 쌍을
    추가하고 등록 순서와 중복을 테스트로 고정한다.

### 생성할 테스트 파일

- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataLocationParserTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataResourceTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataBootstrapBridgeTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataLocationResolverTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataLoaderTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataFactoryRegistrationTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataImportApplicationTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/config/ConfigDataLegacyPrecedenceTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataClasspathGuardTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataSpiAbiTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataEmulatorTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataDocumentationParityTest.kt`

기존 `S3ConfigEnvironmentPostProcessorAwsEmulatorTest`,
`ParameterStoreEnvironmentPostProcessorAwsEmulatorTest`,
`SecretsManagerEnvironmentPostProcessorAwsEmulatorTest`,
`AwsGlobalEnvironmentPostProcessorDisableTest`는 변경하지 않고 회귀 실행한다.
필요한 raw identifier redaction assertion만 각 기존 loader 전용 단위 테스트에
추가하며 legacy 동작의 범위를 넓히지 않는다.

### 수정할 문서와 증적

- `aws-spring-boot/README.md`
- `aws-spring-boot/README.ko.md`
- `docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md`
- `docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md`
- `docs/review/evidence/2026-08-20-issue-467-configdata.md`

영어/한국어 문서는 canonical properties 한 줄 예제, YAML list 예제, profile과
precedence 표, optional/fail-fast truth table, legacy EPP migration 표를 같은
구조로 유지한다. 문서에는 실제 AWS credential이 필수가 아니며 Floci 우선,
LocalStack fallback이라는 검증 경계를 명시한다.

## 단계별 실행 계획

### 작업 0 — 계획·baseline·의존성 계약 고정

**목적:** 구현 전에 현재 branch와 Spring Boot API를 재현 가능하게 고정한다.

- [ ] 설계 SHA-256과 현재 `origin/develop` merge-base를 기록하고 worktree가
  clean인지 확인한다.
- [ ] `spring-boot-4.0.6` source jar에서 `ConfigDataResource`의 optional
  visibility, `ConfigDataLoader` nullable return, `BootstrapRegistry`의
  `registerIfAbsent`/`addCloseListener` 계약을 기록한다.
- [ ] dependency graph가 변경되지 않음을 baseline으로 저장한다.
- [ ] 이 계획을 작성·검토·커밋하고, 구현 전 사용자 계획 승인 대기 상태를
  남긴다.

검증 명령:

```bash
git status --short
git merge-base HEAD origin/develop
./gradlew :bluetape4k-aws-spring-boot:dependencies --configuration compileClasspath --no-daemon > /tmp/issue-467-configdata-compileClasspath.before.txt
git diff --check
```

### 작업 1 — parser와 immutable resource (RED → GREEN)

**목적:** remote I/O와 SDK class 접근 없이 location 문법과 resource identity를
완성한다.

- [ ] 먼저 `AwsConfigDataLocationParserTest`에 실패 테스트를 작성한다.
  - 세 prefix와 optional 분리, comma/YAML 값의 단일 location parsing
  - S3 `/bucket/key`와 `auto|properties|yaml|json`, Parameter Store
    `prefix|recursive|withDecryption`, Secrets `prefix|format=json|text`
  - percent decode 1회, query key camelCase 대소문자 구분
  - duplicate/unknown/empty key·value, malformed Boolean/enum, CR/LF/NUL,
    빈 source, secret text without prefix 거부
  - secret name/ARN control character 거부와 나머지 SDK ARN 검증 위임
- [ ] 같은 RED 단계에서 resource `equals`/`hashCode`/`toString` 테스트를
  작성한다. 동일 canonical source/options/optional만 동등하고 bound properties,
  logger, client가 달라도 동등해야 하며 raw sentinel은 `toString`과 예외에
  없어야 한다. query 순서를 바꾼 동일 location은 같은 equality/hash/opaque
  identity를, option 또는 optional을 바꾼 location은 다른 identity를 가져야 한다.
- [ ] 테스트 실행으로 클래스/함수 부재에 따른 RED를 남긴다.
- [ ] parser는 URI parser가 query의 `&`, `%`를 재해석하지 않도록 작은
  표준-library 구현으로 추가한다. decode 전후 control/empty 검증과 backend별
  허용 key table을 한 곳에서 관리한다.
- [ ] `AwsConfigDataResource`는 `ConfigDataResource(optional)`를 호출하되
  loader가 사용할 자체 `optional` accessor를 보유한다. identity hash input은
  `backend + "\u0000" + optional + "\u0000" + canonicalDecodedLocation`
  UTF-8의 SHA-256 lowercase first 12로 고정한다. 여기서
  `canonicalDecodedLocation`은 decoded source와 query options를 허용된 key의
  사전순으로 정렬한 canonical form이다. 따라서 동일 source의 다른
  `prefix`/`format`/optional import가 identity를 공유하지 않는다. raw source는
  equality/toString/log message에 재사용하지 않는다. query key 정렬과
  percent-decoded canonical serializer를 equality와 hash에 함께 사용한다.
- [ ] 다음 RED 명령을 구현 전에 실행한다. 예상 결과는 대상 테스트 클래스/
  production symbol 부재에 따른 non-zero exit이며, status와 전체 stdout/stderr를
  두 artifact에 남긴다.

  ```bash
  set +e
  ./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsConfigDataLocationParserTest' --tests '*AwsConfigDataResourceTest' --no-configuration-cache > /tmp/issue-467-configdata-task1-red.log 2>&1
  status=$?
  set -e
  printf '%s\n' "$status" > /tmp/issue-467-configdata-task1-red.exit
  test "$status" -ne 0
  ```

- [ ] parser/resource 테스트를 GREEN으로 실행한다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsConfigDataLocationParserTest' --tests '*AwsConfigDataResource*' --no-configuration-cache
```

### 작업 2 — resolver와 bootstrap client registry (RED → GREEN)

**목적:** resolver가 startup metadata만 만들고, 동일 backend client를 bootstrap
scope에서 한 번만 소유하도록 한다.

- [ ] `AwsConfigDataLocationResolverTest`와 `AwsConfigDataBootstrapBridgeTest`,
  `AwsConfigDataClasspathGuardTest`의 RED
  테스트를 먼저 작성한다.
  - global/backend disabled는 SDK class guard·supplier·network 이전에 disabled
    resource를 반환한다.
  - enabled는 Binder로 `AwsProperties`와 backend properties를 결합하고
    service region/endpoint가 global보다 우선한다.
  - web identity가 활성인데 role/session/token 설정이 잘못되면 default chain으로
    fallback하지 않고 sanitized configuration error를 낸다.
  - bootstrap `registerIfAbsent(clientType)`와 close listener는 동일 backend
    다중 import에서 정확히 한 번만 등록한다. loader는 close하지 않는다.
  - `resolveProfileSpecific`는 빈 목록이며 profile suffix를 remote source에
    붙이지 않는다.
  - resolver는 client creation/network를 하지 않고, filtered ClassLoader에서
    SDK class missing을 명시 dependency 오류로 보고하며 client creation 횟수 0을
    보장한다.
- [ ] `AwsConfigDataSupport`에 다음 순서의 내부 경계를 구현한다.
  1. global/backend enabled 판정
  2. SDK classpath guard
  3. source/parser mapping
  4. `BootstrapRegistry.isRegistered` → `registerIfAbsent`
  5. bridge가 supplier의 common defaults, bootstrap credential provider, 명시
     등록된 customizer를 SDK guard 이후 service adapter에 전달
  6. initialized client holder 기반 close listener 단일 등록
- [ ] SPI resolver/loader에는 AWS SDK type을 생성자·generic·필드 descriptor로
  노출하지 않는다. `AwsConfigDataBootstrapBridge`가 문자열 class name으로
  classpath를 먼저 확인하고, 통과한 경우에만 service SDK adapter를 로드해
  typed builder와 `BootstrapRegistry` supplier를 연결한다. filtered ClassLoader로
  `SpringFactoriesLoader`가 세 concrete SPI를 먼저 읽어도 SDK `NoClassDefFoundError`
  가 발생하지 않고, active import만 명시 dependency 오류를 내는 것을 고정한다.
- [ ] bootstrap credential provider factory를 concrete symbol로 둔다.
  `AwsConfigDataBootstrapBridge.createCredentialsProvider`는 web identity가
  enabled이면 먼저 STS class 존재를 확인한 뒤 role ARN/session/token file을 모두 검증하고
  `WebIdentityTokenFileCredentialsProvider`를 만든다. 설정이 잘못되면
  sanitized dependency/configuration failure로 fail-closed하며 default chain으로
  fallback하지 않는다. 비활성일 때만 SDK default chain을 사용한다. provider가
  아직 생성되지 않은 bootstrap shutdown에서는 holder가 supplier를 깨우지 않는다.
- [ ] `AwsSyncClientCustomizer`는 application bean을 자동 탐색하지 않는다.
  애플리케이션이 표준 `BootstrapRegistryInitializer`에서 해당 customizer를
  명시 등록한 경우에만 bridge가 읽어 적용한다. 적용 순서는 AWS 기본값·credential
  → service 설정 → bootstrap customizer → build로 고정한다.
- [ ] concrete 세 resolver의 public 생성자와 SPI 시그니처를 다음처럼 고정한다.
  Spring이 주입할 수 있는 타입만 constructor parameter로 사용하고, 임의
  support constructor는 노출하지 않는다.

  ```kotlin
  public class S3ConfigDataLocationResolver(
      DeferredLogFactory,
      Binder,
      ConfigurableBootstrapContext,
  ) : ConfigDataLocationResolver<AwsConfigDataResource> {
      override fun isResolvable(context: ConfigDataLocationResolverContext, location: ConfigDataLocation): Boolean
      override fun resolve(context: ConfigDataLocationResolverContext, location: ConfigDataLocation): List<AwsConfigDataResource>
      override fun resolveProfileSpecific(context: ConfigDataLocationResolverContext, location: ConfigDataLocation, profiles: Profiles): List<AwsConfigDataResource> = emptyList()
  }
  ```

  세 loader는 `DeferredLogFactory, ConfigurableBootstrapContext`만 constructor로
  받고 다음 generic/return 계약을 사용한다.

  ```kotlin
  public class S3ConfigDataLoader(
      DeferredLogFactory,
      ConfigurableBootstrapContext,
  ) : ConfigDataLoader<AwsConfigDataResource> {
      override fun isLoadable(context: ConfigDataLoaderContext, resource: AwsConfigDataResource): Boolean
      override fun load(context: ConfigDataLoaderContext, resource: AwsConfigDataResource): ConfigData?
  }
  ```

  Parameter Store/Secrets Manager도 같은 시그니처로 backend만 바꾼다. 모든
  public SPI constructor/method/resource KDoc은 저장소 언어 정책에 맞춰
  한국어로 작성하고, SDK type은 public descriptor에 노출하지 않는다.
- [ ] concrete 세 resolver는 공통 support를 호출하고 backend-specific
  logic만 제공하고, `isResolvable`/`resolve`/`resolveProfileSpecific` 전체 SPI
  override는 위의 고정 시그니처를 따른다.
- [ ] 다음 RED 명령을 구현 전에 실행한다. 예상 결과는 새 SPI/bridge 테스트
  클래스 또는 bridge symbol 부재에 따른 non-zero exit이며, status와 로그를
  `/tmp/issue-467-configdata-task2-red.exit`와 `.log`에 보존한다.

  ```bash
  set +e
  ./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsConfigDataLocationResolverTest' --tests '*AwsConfigDataBootstrapBridgeTest' --tests '*AwsConfigDataClasspathGuardTest' --no-configuration-cache > /tmp/issue-467-configdata-task2-red.log 2>&1
  status=$?
  set -e
  printf '%s\n' "$status" > /tmp/issue-467-configdata-task2-red.exit
  test "$status" -ne 0
  ```
- [ ] disabled resolver가 호출하는 `AwsConfigDataSupport`와
  `AwsConfigDataBootstrapBridge`에는 AWS SDK import/typed builder/customizer
  descriptor/static initializer를 두지 않는다. SDK-dependent adapter는 문자열
  class name과 `Class.forName`으로 guard 이후에만 로드한다. filtered ClassLoader
  테스트와 `jdeps` 또는 bytecode dependency 확인으로 transitive reachability를
  고정한다.
- [ ] resolver/classpath/registry 테스트를 GREEN으로 실행한다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsConfigDataLocationResolverTest' --tests '*AwsConfigDataBootstrapBridgeTest' --tests '*AwsConfigDataClasspathGuardTest' --no-configuration-cache
```

### 작업 3 — 기존 loader 내부 경계와 strict failure 정책 (RED → GREEN)

**목적:** ConfigData loader가 기존 파싱 구현을 재사용하면서 client ownership,
optional not-found, redaction을 분리한다.

- [ ] `AwsConfigDataLoaderTest`와 기존 세 loader 단위 테스트에 RED를 먼저
  작성한다.
  - `load(properties)` legacy facade는 기존 client create/use/reload/legacy
    `failFast=false` 동작을 유지한다.
  - `internal load(client, source, failurePolicy)`는 새 client를 만들거나 닫지
    않고 단일 source만 읽는다.
  - disabled resource는 빈 `MapPropertySource`를 반환하고 client/network 호출이
    정확히 0회다.
  - 동일 backend 다중 resource는 bootstrap client 한 개를 공유하며 close는
    bootstrap close event에서 정확히 한 번이다.
  - S3 not-found는 `NoSuchBucket`, `NoSuchKey`, HTTP 404만 optional skip;
    403/기타 S3Exception은 실패한다.
  - Parameter Store는 `ParameterNotFoundException`만 skip하며 정상 empty
    parameters는 성공 empty config이다.
  - Secrets Manager는 `ResourceNotFoundException`만 skip하고 SecretString
    없음은 optional이어도 data-format/configuration 오류다.
  - required not-found와 auth/credential/network/parse/config 오류는
    required not-found는 `ConfigDataResourceNotFoundException(resource)`로,
    그 밖의 오류는 raw SDK cause를 chain하지 않는 sanitized configuration
    failure로 보존한다. optional not-found만 `ConfigData? = null`로 매핑한다.
  - property-source name, warning, exception, cause message에 raw bucket/key/path/
    secretId/secret value가 없고 opaque identity/backend/error class만 남는다.
- [ ] 다음 RED 명령을 구현 전에 실행한다. 예상 결과는 주입 client/failure
  policy와 loader type 부재에 따른 non-zero exit이며, status와 로그를
  `/tmp/issue-467-configdata-task3-red.exit`와 `.log`에 보존한다.

  ```bash
  set +e
  ./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsConfigDataLoaderTest' --no-configuration-cache > /tmp/issue-467-configdata-task3-red.log 2>&1
  status=$?
  set -e
  printf '%s\n' "$status" > /tmp/issue-467-configdata-task3-red.exit
  test "$status" -ne 0
  ```

- [ ] 세 기존 loader에 fetch/parse throw-only core를 추출하고,
  `AwsConfigDataFailurePolicy`를 주입한다. legacy facade는 기존 broad catch와
  `failFast=false` 정책을 adapter에서 선택하고, ConfigData adapter는 strict
  policy를 선택한다. S3 reload callback은 legacy facade에서만 유지한다.
- [ ] strict policy의 not-found classifier와 sanitized failure를 common
  support에서 구현한다. optional not-found는 loader가 `null`을 반환해 Boot가
  import를 건너뛰고, disabled는 `ConfigData(empty MapPropertySource)`로
  명시적인 no-op source를 반환한다.
- [ ] loader가 SDK client를 닫지 않는다는 ownership assertion과 bootstrap close
  listener assertion을 포함한다.
- [ ] loader 단위 테스트와 기존 EPP 회귀 테스트를 GREEN으로 실행한다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsConfigDataLoaderTest' --tests '*EnvironmentPostProcessor*' --no-configuration-cache
```

### 작업 4 — SPI 등록과 Spring Boot precedence 통합 (RED → GREEN)

**목적:** 실제 Boot lifecycle에서 comma-separated properties, YAML list,
profile, import ordering, legacy winner를 검증한다.

- [ ] `AwsConfigDataFactoryRegistrationTest` RED를 먼저 작성해
  `spring.factories`의 기존 EPP와 ConfigData resolver/loader 세 쌍, 중복 없는
  등록을 검증한다.
- [ ] `AwsConfigDataImportApplicationTest` RED를 작성한다.
  - properties 한 줄과 YAML list 모두에서 세 backend 값이 binding된다.
  - 뒤에 선언한 ConfigData import가 앞 import를 override한다.
  - imported data가 declaring document보다 우선한다.
  - profile 문서에 선언한 import는 Boot profile document 경계를 따르고
    resolver는 remote `-profile` suffix를 만들지 않는다.
- [ ] `ConfigDataLegacyPrecedenceTest` RED를 작성해 동일 key 혼합 시 현재
  Boot 4 규칙(legacy EPP source가 ConfigData보다 우선)을 고정하고, warning은
  backend/opaque identity만 포함하도록 한다.
- [ ] 다음 RED 명령을 구현 전에 실행한다. 예상 결과는 `spring.factories` 등록과
  application fixture 부재에 따른 non-zero exit이며, status와 로그를
  `/tmp/issue-467-configdata-task4-red.exit`와 `.log`에 보존한다.

  ```bash
  set +e
  ./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsConfigDataFactoryRegistrationTest' --tests '*AwsConfigDataImportApplicationTest' --tests '*ConfigDataLegacyPrecedenceTest' --no-configuration-cache > /tmp/issue-467-configdata-task4-red.log 2>&1
  status=$?
  set -e
  printf '%s\n' "$status" > /tmp/issue-467-configdata-task4-red.exit
  test "$status" -ne 0
  ```

- [ ] `spring.factories` 등록과 세 concrete SPI를 구현한다. resource 중복을
  허용하지 않고, loader의 `isLoadable`은 backend를 정확히 claim한다.
- [ ] `ApplicationContextRunner` 또는 최소 Boot application fixture를 사용해
  실제 Binder/bootstrap lifecycle을 검증한다. 테스트 fixture의 AWS 호출은
  mock/bootstrap client 또는 emulator를 사용하며 real credential은 요구하지
  않는다.
- [ ] SPI/Boot 통합 테스트를 GREEN으로 실행한다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsConfigDataFactoryRegistrationTest' --tests '*AwsConfigDataImportApplicationTest' --tests '*ConfigDataLegacyPrecedenceTest' --no-configuration-cache
```

### 작업 5 — Floci 우선 emulator 증적과 문서 (RED → GREEN)

**목적:** 실제 서비스 payload와 사용자 migration 계약을 고정한다.

- [ ] `AwsConfigDataEmulatorTest` RED를 작성해 S3, SSM, Secrets Manager의
  startup import, format/prefix/decryption 옵션, not-found optional/required를
  순차적으로 검증한다. 테스트 값·bucket/key/secret 식별자는
  `Base58.randomString(16)`으로 만들고, canonical payload key와 expected digest는
  고정 fixture로 둔다. Docker 공유 자원 때문에 병렬 실행하지 않는다.
- [ ] `AwsConfigDataDocumentationParityTest` RED를 작성해 README는 manual
  링크/요약만 제공하고 상세 계약은 manual source of truth라는 규칙, EN/KO의
  canonical properties/YAML snippet·heading·link 대응을 고정한다.
- [ ] 다음 RED 명령을 구현 전에 실행한다. 예상 결과는 새 emulator/parity test
  부재에 따른 non-zero exit이며 status와 로그를
  `/tmp/issue-467-configdata-task5-red.exit`와 `.log`에 보존한다.

  ```bash
  set +e
  ./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsConfigDataEmulatorTest' --tests '*AwsConfigDataDocumentationParityTest' --no-configuration-cache > /tmp/issue-467-configdata-task5-red.log 2>&1
  status=$?
  set -e
  printf '%s\n' "$status" > /tmp/issue-467-configdata-task5-red.exit
  test "$status" -ne 0
  ```

- [ ] Floci 우선 명령을 실행한다. Floci startup/coverage 인프라 실패 또는
  명시된 API gap일 때만 LocalStack fallback을 실행하며, 제품 assertion/parse/
  precedence/redaction 실패는 즉시 BLOCK으로 분류해 fallback하지 않는다.
  각 명령의 exit, stdout/stderr, emulator 선택, skipped coverage를 evidence에
  기록한다.
- [ ] README 두 언어와 manual 두 언어를 spec의 canonical snippet과 동일하게
  갱신한다. public 문서는 한국어를 기준으로 작성하고 API/명령/URL은 보존한다.
- [ ] manual contract test, canonical snippet parity, `git diff --check`를
  실행한다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsConfigDataEmulatorTest' -Dbluetape4k.aws.emulator=floci --no-configuration-cache
./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsConfigDataEmulatorTest' -Dbluetape4k.aws.emulator=localstack --no-configuration-cache
./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsConfigDataDocumentationParityTest' --no-configuration-cache
ruby scripts/manual/manual_contract_test.rb
git diff --check
```

문서 parity test는 고정 canonical snippet과 명시된 heading/link mapping을
출력 파일 `/tmp/issue-467-configdata-doc-parity.txt`에 기록하고, EN/KO 어느
한쪽만 갱신되거나 README에 상세 manual을 복제하면 실패한다.

`docs/review/evidence/2026-08-20-issue-467-configdata.md`에는 exact command,
commit SHA, emulator 선택, 통과/실패/skipped 분류, Docker socket 또는
Testcontainers 오류가 제품 결함이 아님을 구분해 기록한다. 외부 AWS latency,
cleanup telemetry, 실제 heap/throughput 수치는 이 PR의 DoD가 아니며 별도 후속
이슈로 추적한다.

### 작업 6 — 전체 검증·review·rollback checkpoint

- [ ] `./gradlew :bluetape4k-aws-spring-boot:test --no-configuration-cache`
  로 전체 모듈 회귀를 실행한다.
- [ ] `./gradlew detekt --no-configuration-cache`와
  `./gradlew :bluetape4k-aws-spring-boot:compileKotlin --no-configuration-cache`
  를 실행한다. public SPI의 `javap` signature와 compile/test ABI 경계를
  확인한다. `AwsConfigDataSpiAbiTest`는 public resource의 JVM private
  constructor, 세 resolver/loader의 정확한 generic interface, Boot-supported
  constructor parameter만 존재하는지 검증한다. dependency before/after를
  비교해 새 dependency가 없는지도 확인한다.
- [ ] Kotlin pattern 리뷰에서 null-safety, immutable resource, SDK
  compileOnly, client close ownership, `withContext(Dispatchers.IO)` 필요 여부,
  assertion matcher 사용을 재검토한다.
- [ ] Type A 여섯 관점(API/호환성, lifecycle, 사용자 migration, 성능/자원,
  보안/진단, 안정성/회귀)의 독립 리뷰와 통합 review를 남기고 P0/P1=0인지
  확인한다. 1인 개발자 정책으로 human review gate는 N/A이며, CI와 독립 검증
  증거는 유지한다.
- [ ] 독립 review 통합 결과를
  `docs/review/2026-08-20-issue-467-configdata-plan-review.md`에 기록한다.
  문서에는 여섯 관점, P0-P3, plan SHA, spec SHA, RED/GREEN/CI evidence와
  human-review `N/A (single-developer lane)` 근거를 남긴다.
- [ ] 구현 변경은 계획 커밋과 분리한 단일 ConfigData 구현 커밋으로 묶어 rollback
  경계를 만든다. rollback 집합은 새 `spring.factories` ConfigData 등록,
  `io/bluetape4k/aws/spring/config` 패키지, 수정된 S3/SSM/Secrets loader 3개,
  새 테스트와 문서이며, 기존 EPP 등록·legacy 동작은 보존 회귀 대상으로 명시한다.
- [ ] rollback checkpoint에서는 실제 구현 commit SHA를 고정해
  `git revert <configdata-commit>`으로 위 집합을 함께 되돌린 뒤 다음 회귀를
  실행하고 결과를 evidence에 기록한다.

```bash
git revert <configdata-commit>
./gradlew :bluetape4k-aws-spring-boot:test --tests '*EnvironmentPostProcessor*' --no-configuration-cache
```

rollback 검증 후 정상 작업을 계속해야 하면 revert를 되돌리는 별도 커밋을
만들지 않고, 머지 전 작업 branch를 보존한 채 결과만 기록한다. branch 삭제나
worktree 정리는 사용자가 명시한 merge/cleanup gate 전에는 수행하지 않는다.

### 작업 7 — PR·CI·exact-head delivery gate

구현과 로컬 검증이 끝난 뒤에만 첫 번째 stacked-train PR을 만든다. PR 본문과
commit은 한국어 Lore 형식으로 작성하고, 제목/본문에 `Closes #467`와
`SPRING-1`을 포함한다. assignee `debop`, issue와 같은 milestone/labels,
`## DoD Status`를 마지막 H2로 설정하고, 계획·설계·review·evidence 경로를
본문에 연결한다.

- [ ] PR 생성 직전 `git rev-parse HEAD`, base `develop`, worktree clean,
  linked issue, assignee/milestone/labels, diff와 마지막 DoD를 다시 읽는다.
- [ ] GitHub Actions current workflow의 required surface를 다음 이름으로
  확인한다: `Build (compile only)`, `Test / aws-spring-boot`, `Coverage Report`,
  `CI Status`. path-filtered job은 skipped를 green으로 세지 않고 N/A로
  기록한다.
- [ ] exact head에 대해 다음을 실행하고 receipt를 evidence에 남긴다.

  ```bash
  gh pr view <pr-number> --repo bluetape4k/bluetape4k-aws --json number,headRefOid,baseRefName,mergeable,reviewDecision,statusCheckRollup,assignees,labels,milestone,body
  gh pr checks <pr-number> --repo bluetape4k/bluetape4k-aws
  gh api repos/bluetape4k/bluetape4k-aws/branches/develop/protection/required_status_checks
  ```

  report 형식은 `Required checks: X/Y; N/A: N; Blocked: N`으로 고정하고,
  `CI Status`가 skipped jobs를 success로 취급한다는 workflow 사실과
  path-filtered coverage를 별도로 설명한다. PR review는 1인 개발자 정책상
  `human review: N/A`, 그러나 독립 plan review artifact와 CI는 필수다.
- [ ] merge/auto-merge는 이 계획의 자동 단계가 아니다. exact-head CI,
  mergeability, metadata, linked issue, final DoD를 fresh read-back한 뒤
  사용자의 별도 merge 승인을 기다린다. merge 후에만 issue/epic 상태와 default
  branch sync, bounded worktree cleanup을 검증한다.

## 수용 기준과 중단 조건

완료로 보고하려면 parser/resolver/loader/Boot/emulator/legacy 테스트, 문서 parity,
detekt, dependency 비교, Type A review, evidence artifact가 모두 fresh green이어야
한다. Green CI라도 path-filtered/skipped emulator job이 있으면 전체 통과로
주장하지 않고 gap으로 남긴다. Floci와 LocalStack이 모두 인프라 이유로 실행되지
않으면 제품 실패와 분리해 `PENDING`으로 보고하며, 구현 correctness가 확인되지
않은 상태에서 PR/merge를 만들지 않는다.

외부 AWS latency/cleanup telemetry와 실제 heap·throughput 측정은 이 계획에서
후속 이슈로 명시적으로 추적한다. 구현 중 public API/새 dependency/ConfigData와
legacy precedence를 재설계해야 하는 요구가 나오면 즉시 구현을 멈추고 설계
변경 review로 되돌린다.

## 계획 승인 기록

- 2026-08-20: 설계 명세 승인 후 구현 계획 작성 시작.
