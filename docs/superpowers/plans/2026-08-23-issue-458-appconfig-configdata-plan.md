# Issue #458 AWS AppConfig Data ConfigData 구현 계획

## 목표와 완료 조건

`bluetape4k-aws-spring-boot`에 `aws-app-config:` ConfigData import와 선택적
runtime reload를 추가한다. Spring Boot ConfigData 초기 로딩, AWS AppConfig Data
단일 사용 token, properties/YAML/JSON decoder, 조건부
`AppConfigDataClient` bean, context 수명 주기 poller와 운영 문서를 하나의
검증 가능한 변경으로 제공한다.

완료 조건은 다음과 같다.

1. `spring.config.import=aws-app-config:<application>#<profile>#<environment>`가
   이름 또는 identifier 세 개를 해석하고 custom separator를 지원한다.
2. `optional:`·`fail-fast`·region·endpoint·global/service customizer 경계가
   기존 AWS ConfigData 규칙과 일치한다.
3. `StartConfigurationSession` 후 첫 `GetLatestConfiguration`을 수행하고,
   매 응답의 `nextPollConfigurationToken`을 한 번만 사용한다.
4. 빈 응답은 기존 값을 유지하고, properties/YAML/JSON과 prefix flatten을
   지원한다. decoder/transport 오류는 마지막 정상 값을 보존하고 token/session
   정책에 따라 재시도한다.
5. refresh는 기본 비활성이고 `refresh-interval`이 명시될 때만 하나의 context
   scheduler와 resource당 하나의 fixed-delay poller를 만든다. 종료 시 예약,
   task, executor, client 순서를 검증한다.
6. runtime `Environment` 조회는 최신 값을 읽지만 `@ConfigurationProperties`
   자동 rebind나 Spring Cloud Context 의존성은 제공하지 않는다.
7. SDK가 없는 classpath에서 ConfigData discovery가 깨지지 않으며, fake session
   contract와 opt-in real smoke가 수용 기준을 증명한다.

## 고정된 범위와 제외

- 대상 이슈: #458, 상위 Epic #500의 현재 하위 이슈 하나만 변경한다.
- 대상 모듈: `bluetape4k-aws-spring-boot`; version/release/publish/tag/merge는
  이번 작업에서 수행하지 않는다.
- 제외: #463, #466, #472, Spring Cloud Context의 `RefreshScope`/event bus,
  자동 `@ConfigurationProperties` rebind, secret rotation watcher, AppConfig
  deployment/hosted configuration/IAM 자동 생성.
- AWS SDK `software.amazon.awssdk:appconfigdata`는 `compileOnly`로 유지하고
  소비자가 runtime dependency를 추가한다.
- 사용자 제공 endpoint는 명시적 운영 설정으로 취급하며 credential/secret을
  코드·로그·문서 예제에 포함하지 않는다.

## 사전 상태와 설계 근거

- baseline: `./gradlew :bluetape4k-aws-spring-boot:test --no-daemon`에서
  기존 539개 테스트가 통과했다.
- 기준 branch/commit: `origin/develop` / `95413c42473875bd0fb312d9e4fc59c5dc49f215`.
- 설계 원본: `docs/superpowers/specs/2026-08-23-issue-458-appconfig-configdata-design.md`.
- 6관점 검토: `docs/review/2026-08-23-issue-458-appconfig-configdata-spec-review.md`.
- 설계 수정 후 P0=0, P1=0. 성능 P1 네 건과 IAM P1 한 건은 설계에 반영했다.

## 구현 순서

### 1. 의존성과 공통 모델을 먼저 고정

변경 파일:

- `gradle/libs.versions.toml`
- `aws-spring-boot/build.gradle.kts`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/config/AwsConfigDataBackend.kt`
- `AwsConfigDataLocation.kt`, `AwsConfigDataLocationParser.kt`,
  `AwsConfigDataSupport.kt`, `AwsConfigDataResource.kt`

작업:

- catalog에 `aws2-appconfigdata = { module = "software.amazon.awssdk:appconfigdata" }`
  alias를 추가하고 `compileOnly`/`testImplementation`만 등록한다.
- backend enum과 source sealed model에 application/profile/environment,
  `format`, `prefix`를 추가한다. 기존 S3/Parameter Store/Secrets Manager
  parser 결과와 equality/opaque identity는 회귀시키지 않는다.
- `aws-app-config:` prefix, default `#`, custom separator property, 세 component
  정확성, percent decode 1회, control/duplicate/unknown/blank 거부를 구현한다.
- `AppConfigProperties`를 `bluetape4k.aws.app-config` prefix로 추가한다.
  `enabled=true`, `fail-fast=true`, `refresh-interval=null`,
  `required-minimum-poll-interval=15s`, region/endpoint를 기본으로 둔다.
  15초~24시간 범위와 overflow-safe 초 변환을 한 곳에서 검증한다.

TDD RED:

- `AppConfigDataLocationParserTest`: 3 component, name/identifier, default/custom
  separator, query format/prefix, optional, percent/control/duplicate/unknown/blank.
- `AppConfigPropertiesTest`: defaults, duration bounds, endpoint-region invariant,
  disabled/fail-fast and redacted `toString`.
- 기존 parser/support 회귀 테스트를 먼저 실행해 baseline을 고정한다.

### 2. SDK adapter와 초기 ConfigData loader

변경 파일:

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/appconfig/AppConfigDataSessionClient.kt`
- `AppConfigDataSdkAdapter.kt`, `AppConfigDataSnapshot.kt`, `AppConfigDataDecoder.kt`
- `AppConfigDataConfigDataLocationResolver.kt`
- `AppConfigDataConfigDataLoader.kt`
- `AwsConfigDataFailurePolicy.kt`와 `AwsConfigDataSupport.kt`의 필요한 backend dispatch

작업:

- public SPI에 AWS SDK 타입을 노출하지 않는 internal fake contract를 만든다.
  `startSession(context)`와 `getLatest(token)`은 opaque token/bytes를 반환하되
  token/body를 로그·예외에 복사하지 않는다.
- 실제 adapter는 `StartConfigurationSession`의 세 identifier와
  `RequiredMinimumPollIntervalInSeconds`를 전달하고, 첫 Get 이후 response token,
  content type, next poll seconds를 내부 기준 데이터로 변환한다. 비어 있는 token은
  sanitized adapter 오류로 처리한다.
- resolver는 기존 `AwsConfigDataSupport`의 Binder/bootstrap bridge/class guard를
  재사용한다. loader는 disabled/optional/required/fail-fast 오류 정책을 적용하고
  초기 map을 `AppConfigDataPropertySource`로 반환한다.
- SDK가 없는 classpath에서 resolver factory가 로드되더라도 실제 adapter를
  호출하지 않도록 string class guard와 `compileOnly` 경계를 유지한다.

TDD RED:

- `AppConfigDataSessionClientTest`: initial token, next token 단일 사용, empty
  response, invalid token, sanitized exception.
- `AppConfigDataDecoderTest`: auto/properties/YAML/JSON, prefix/array flatten,
  unknown content type, 1 MiB/depth/property budget, malformed payload.
- `AppConfigDataLoaderTest`/resolver test: optional not-found `null`, required
  exception, fail-fast false empty source, disabled no client/network.

### 3. 동적 PropertySource와 lifecycle poller

변경 파일:

- `AppConfigDataPropertySource.kt`
- `AppConfigReloadLifecycle.kt`
- 필요 시 `AwsEnvironmentPropertySourceSupport.kt`의 재사용 가능한 map/flatten 경계

작업:

- `EnumerablePropertySource`가 immutable map reference를 atomic하게 교체한다.
  `getProperty`, `containsProperty`, `getPropertyNames`는 한 기준 데이터만 읽고
  partial map을 노출하지 않는다.
- context당 `ScheduledThreadPoolExecutor` 하나를 만들고 pool size는
  `min(max(1, active resource count), 8)`로 한다. resource별 running/reserved
  task 합계는 1개이며 완료 후 fixed-delay self-reschedule만 사용한다.
- 정상 응답은 `max(refresh-interval, required-minimum-poll-interval,
  nextPollInterval)`을 적용한다. transport/session/token-invalid 오류는 1초~5분
  bounded exponential full jitter 후 새 session, decoder 오류는 새 token과
  마지막 정상 map을 유지한다.
- payload bytes 1 MiB, flatten depth 32, property 10,000을 넘으면 마지막 값을
  유지하고 중간 구조를 해제한다.
- `SmartLifecycle` stop 순서를 신규 예약 차단→future cancel→5초 bounded drain→
  `shutdownNow` fallback→client close로 고정한다. daemon thread,
  remove-on-cancel, delayed task 미실행, interrupt 보존과 client close 중복 방지를
  테스트한다.
- scheduler/resource 등록 중 하나라도 실패하면 이미 등록한 task와 client를 같은
  close 순서로 rollback한 뒤 context startup 오류를 전파한다. 부분적으로 살아 있는
  poller를 남기지 않는다.

TDD RED:

- `AppConfigDataPropertySourceTest`: atomic replacement, empty response retention,
  property names and no partial state.
- `AppConfigReloadLifecycleTest`: one scheduler, resource deduplication, one task
  per resource, fixed-delay/self-reschedule, backoff reset, cancel/close order,
  bounded shutdown, startup rollback and cancellation quietness.
- `AppConfigEnvironmentRefreshSemanticsTest`: Environment 최신 조회,
  `@ConfigurationProperties` no auto-rebind, Spring Cloud Context absent.

### 4. 조건부 client auto-configuration와 등록

변경 파일:

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/appconfig/AppConfigAutoConfiguration.kt`
- `AwsClientBuilderSupport.kt` 또는 service-specific builder helper
- `META-INF/spring.factories`
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

작업:

- `AppConfigDataClient` classpath, `bluetape4k.aws.enabled`,
  `bluetape4k.aws.app-config.enabled`, missing bean 조건을 적용한다.
- shared AWS defaults → AppConfig properties → credentials/region/endpoint →
  global `AwsSyncClientCustomizer` → typed service customizer 순서를 기존 contract와
  맞춘다. bootstrap client에는 명시적으로 허용한 bootstrap customizer만 적용한다.
- ConfigData resolver/loader와 auto config registration을 각각 추가하고 기존
  factory ordering을 깨뜨리지 않는다.

TDD RED:

- `AppConfigAutoConfigurationTest`: class guard, disabled/no SDK/no bean paths,
  existing bean preservation, region/endpoint/customizer precedence and order.
- `AwsConfigDataFactoryRegistrationTest`와 classpath guard/ABI tests에 AppConfig
  factory와 public property contract를 추가한다.

### 5. 문서와 manifest

변경 파일:

- `aws-spring-boot/README.md`, `aws-spring-boot/README.ko.md`
- `docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md`
- `docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md`
- `docs/manual/manifest.yaml` 및 generator 출력이 필요한 경우
  `docs/manual/generated/manifest.json`
- 관련 documentation parity test

작업:

- import 문법, `separator`, format/prefix, dependency, region/endpoint,
  optional/fail-fast, default refresh disabled, poll/cost/IAM `Resource: "*"`,
  endpoint 신뢰 경계와 real smoke 실행 조건을 예제와 함께 설명한다.
- `Environment`와 `@ConfigurationProperties` refresh semantics 표를 양언어로
  동일하게 유지하고 Spring Cloud Context 자동 rebind를 제공하지 않는다고 명시한다.
- 직접 AWS Data API polling과 AppConfig Agent의 운영 선택 차이를 설명하되
  이번 이슈에서 Agent 의존성을 추가하지 않는다.
- README는 manual 전체를 복제하지 않고 manual chapter로 연결한다. 영어/한국어
  구조와 anchor/API/URL을 보존한다.

TDD/문서 RED:

- `AwsConfigDataDocumentationParityTest`에 AppConfig key/section/semantics token을
  추가하고 양언어 count/anchor parity를 확인한다.
- `exportManualModuleInventory`, `manual_contract_test.rb`, manifest generator
  check를 문서 변경 후 실행한다.

## 검증 순서와 중단 규칙

의존성 순서를 지킨다. parser/decoder/fake contract가 GREEN이 되기 전 lifecycle
또는 real smoke를 실행하지 않는다.

1. **RED 확인**: 신규 테스트가 의도한 실패를 보이는지 기록한다.
2. **targeted GREEN**:
   `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.appconfig.*' --no-daemon`
3. **기존 ConfigData 회귀**:
   `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.config.*' --no-daemon`
4. **module full**:
   `./gradlew :bluetape4k-aws-spring-boot:test --no-daemon`
5. **정적/compileOnly**:
   `./gradlew :bluetape4k-aws-spring-boot:detekt :bluetape4k-aws-spring-boot:compileKotlin --no-daemon`
   및 classpath consumer/ABI fixture.
6. **emulator**:
   `./gradlew :bluetape4k-aws-spring-boot:test -Dbluetape4k.aws.emulator=floci --no-daemon`를
   순차 실행한다. Floci/LocalStack이 AppConfig Data API를 제공하지 않으면
   fake contract와 classpath/decoder/lifecycle 증거를 필수로 남기고 real smoke를
   N/A로 분류한다. 지원되는 경우에만 emulator route를 추가한다.
7. **opt-in real smoke**: 명시적인
   `BLUETAPE4K_APPCONFIG_REAL_SMOKE=true`와 AWS region/credentials/identifiers가
   모두 있을 때만 `AppConfigDataRealSmokeTest`를 실행한다. 자격 증명이 없으면
   실행하지 않고 PENDING/N/A를 명시한다.
8. **문서/계약**:
   `./gradlew exportManualModuleInventory --no-daemon`,
   `ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check`,
   `ruby scripts/manual/manual_contract_test.rb`, `git diff --check`.

실패 시 다음 단계로 넘어가지 않고 원인·로그·재현 명령을 기록한다. emulator
실패를 fake test GREEN으로 대체하지 않으며, skipped/path-filtered CI는 coverage
증거로 세지 않는다.

## Issue #458 수용 기준 추적

| 수용 기준 | 구현 경계 | 필수 증거 |
|---|---|---|
| `aws-app-config:` import와 custom separator | parser/resolver/resource | parser·resolver targeted test, README/manual 예제 |
| application/profile/environment name 또는 identifier | StartConfigurationSession request model | fake adapter request assertion, opt-in real smoke |
| optional/fail-fast/format/prefix/empty/token | loader, failure policy, decoder, session contract | loader·decoder·session tests |
| region/endpoint/customizer와 conditional client | `AppConfigAutoConfiguration`, builder support | ApplicationContextRunner/customizer/classpath tests |
| long poll과 runtime reload 기본 disabled | 기준 데이터/property source/lifecycle | lifecycle scheduling, interval/backoff, cancellation/close tests |
| `Environment` 최신 조회와 binding semantics | dynamic property source + docs | refresh semantics test, 양언어 manual parity |
| token/body/secret 비로그와 IAM/cost guidance | adapter/redaction + README/manual | redaction test, writer audit, manual contract |
| emulator/real smoke 경계 | fake contract + optional smoke | Floci 결과 또는 N/A 사유, explicit env smoke evidence |
| Spring Cloud Context clone 금지 | dependency graph와 docs | dependency report, source scan, docs assertion |

## 파일 소유권과 rollback

메인 구현 lane만 위 파일을 수정한다. 테스트/문서가 실제 변경 경로를 모두
소유하는지 diff와 변경 경로 manifest로 확인한다. rollback은 AppConfig backend
registration, auto config, dependency alias, tests/docs를 함께 제거하는 단일
revert로 가능해야 하며 기존 세 backend와 legacy EPP는 변경하지 않는다.

## 구현 DoD

- [ ] issue acceptance 각 항목에 테스트·문서·명령 증거가 연결됨
- [ ] P0/P1 최종 리뷰 0건, P2 위험과 후속 범위가 기록됨
- [ ] fake session contract, parser/format/token/empty/failure/cancellation/lifecycle 테스트 통과
- [ ] module full, detekt, classpath/ABI, manual contract 검증 통과
- [ ] emulator 결과와 real smoke N/A/PENDING 사유가 명시됨
- [ ] Korean README/manual parity와 IAM/cost/refresh semantics가 확인됨
- [ ] Lore commit protocol을 따르는 설계·계획/구현/lesson commit 준비
- [ ] exact-head PR CI/review가 merge-ready가 될 때까지 merge하지 않음

## 다음 게이트

이 계획을 6관점으로 재검토해 P0/P1을 0으로 만든 뒤 설계·계획·검토 문서를
Lore commit으로 고정한다. 그 다음 TDD RED/GREEN 구현을 시작한다.
