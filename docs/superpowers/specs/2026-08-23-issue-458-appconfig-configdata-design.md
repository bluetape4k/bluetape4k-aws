# AWS AppConfig Data ConfigData와 runtime reload 설계

## 1. 문제와 목표

Issue #458은 AWS AppConfig Data API를 Spring Boot 4의 표준 external
configuration 경로에 연결한다. 애플리케이션은 `spring.config.import`에서
AppConfig application, configuration profile, environment를 지정하고, 초기
구성은 Spring `Environment`에 들어온다. 선택적으로 AppConfig Data의 long-poll
계약을 사용해 runtime에 최신 값을 읽을 수 있어야 한다.

이번 변경의 목표는 다음과 같다.

- `aws-app-config:` ConfigData resolver/loader를 제공한다.
- application/profile/environment를 이름 또는 identifier 문자열로 지정하고,
  기본 `#` 구분자와 사용자 정의 구분자를 지원한다.
- `StartConfigurationSession`과 `GetLatestConfiguration`의 단일 사용 token,
  다음 poll token, 빈 응답, server 권장 poll interval을 보존한다.
- YAML, properties, JSON payload를 Spring property map으로 평탄화한다.
- `optional:`과 `fail-fast`, region, endpoint override, 기존 client customizer
  규칙을 기존 AWS source와 맞춘다.
- refresh를 기본 비활성으로 두고, 명시적인 `refresh-interval`에서만 context
  수명 주기의 중복 없는 poller를 실행한다.
- `Environment` 조회와 `@ConfigurationProperties` 재바인딩의 차이를 문서화한다.
- SDK가 runtime classpath에 없을 때 ConfigData SPI가 깨지지 않도록
  `AppConfigDataClient`를 compileOnly/조건부 경계 뒤에 둔다.

Spring Boot는 `ConfigDataLocationResolver`와 `ConfigDataLoader`로 custom
location을 등록하고 `optional:` import를 처리한다. resolver/loader 등록은
Spring Boot의 ConfigData SPI에 맞춘다.

참고:

- [Spring Boot externalized configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [ConfigDataLocationResolver API](https://docs.spring.io/spring-boot/docs/current/api/org/springframework/boot/context/config/ConfigDataLocationResolver.html)
- [AWS StartConfigurationSession](https://docs.aws.amazon.com/appconfig/2019-10-09/APIReference/API_appconfigdata_StartConfigurationSession.html)
- [AWS GetLatestConfiguration](https://docs.aws.amazon.com/appconfig/2019-10-09/APIReference/API_appconfigdata_GetLatestConfiguration.html)
- [AWS AppConfig service authorization](https://docs.aws.amazon.com/service-authorization/latest/reference/list_appconfig.html)

## 2. 독자와 범위

- 독자: Spring Boot 애플리케이션에서 AWS AppConfig를 external config로
  사용하는 개발자와 운영자
- 구현 모듈: `bluetape4k-aws-spring-boot`
- 공개 사용 경계: Spring Boot ConfigData SPI, 조건부
  `AppConfigDataClient` bean, `AppConfigProperties` 설정
- AWS SDK 정책: `software.amazon.awssdk:appconfigdata`는 모듈의
  `compileOnly`이고 소비자가 runtime dependency를 추가한다.
- 외부 부작용: required import는 startup 중 AWS를 호출한다. refresh를 켜면
  context 수명 동안 추가 `GetLatestConfiguration` 호출이 발생한다.

다음 항목은 이번 이슈에서 구현하지 않는다.

- Spring Cloud Context의 `RefreshScope`, 자동 `@ConfigurationProperties`
  rebind, refresh event bus
- secret rotation watcher 또는 별도 configuration server
- AppConfig deployment orchestration, hosted configuration 작성, IAM policy
  자동 생성
- #463, #466, #472의 기능과 Epic #500 종료
- release, publish, tag, PR merge

## 3. 현재 코드와 재사용 경계

현재 `aws-spring-boot`는 `AwsConfigDataLocation`, parser, resource,
bootstrap bridge, failure policy를 공통 ConfigData 경계로 사용한다. S3,
Parameter Store, Secrets Manager resolver/loader는 다음 규칙을 이미 고정했다.

- Binder로 `bluetape4k.aws`와 backend properties를 결합한다.
- disabled 경로에서는 SDK class guard와 client supplier를 호출하지 않는다.
- bootstrap에 backend별 lazy client를 등록하고 initialized instance만 닫는다.
- ConfigData resource 문자열과 진단 로그에는 opaque identity를 사용한다.
- legacy `EnvironmentPostProcessor`와 ConfigData는 별도 lifecycle이다.

AppConfig 구현은 이 공통 경계를 확장한다. 기존 backend의 parser와 loader
동작, property-source precedence, legacy lazy refresh는 변경하지 않는다.
JSON flatten과 Spring `PropertiesPropertySourceLoader`/
`YamlPropertySourceLoader` 사용 패턴은 기존 S3/Secrets Manager loader에서
재사용한다.

## 4. 선택지와 결정

### 선택지 A — 기존 ConfigData SPI + context-owned runtime source (선택)

resolver/loader는 기존 공통 resource와 bootstrap client를 사용한다. loader는
초기 기준 데이터를 읽어 내부 `AppConfigDataPropertySource`를 반환한다. refresh가
명시되면 application context가 만든 조건부 `AppConfigDataClient`를
`SmartLifecycle` 기반 reload manager에 연결한다.

장점:

- Issue #467에서 고정한 ConfigData registration, redaction, optional 정책을
  그대로 확장한다.
- Bootstrap client는 초기 load 뒤 닫고, runtime client는 application context가
  소유하므로 bootstrap 종료와 long-poll 수명이 충돌하지 않는다.
- 하나의 scheduler와 resource identity registry로 중복 poller를 차단한다.
- Spring Cloud Context 의존성을 추가하지 않고 `Environment` 갱신만 제공한다.

단점:

- 초기 load와 runtime refresh가 서로 다른 client instance를 사용할 수 있다.
  session token은 client가 아니라 AppConfig Data session에 속하므로 context
  client로 다음 poll을 이어가며, 양쪽 client의 close를 각각 lifecycle로
  증명해야 한다.

### 선택지 B — bootstrap client가 poller까지 소유

ConfigData loader가 bootstrap client와 scheduler를 함께 만들고 resource가
직접 poll한다.

기각 이유: Spring Boot bootstrap context의 종료 시점이 application context의
runtime 수명보다 빠를 수 있다. 이 구조는 poller가 닫힌 client를 사용하거나
bootstrap client를 애플리케이션 종료까지 붙잡는 leak을 만들 위험이 있다.

### 선택지 C — Spring Cloud AWS AppConfig 구현 위임

Spring Cloud AWS의 import 문법, property source, reload detector를 참조한다.

기각 이유: awspring 자동 구성과 Spring Cloud Context 의존성을 끌어오면 현재
모듈의 독립적인 AWS SDK compileOnly 정책과 dependency boundary가 바뀐다. 해당
프로젝트의 문법·IAM 예시는 참고하되 구현은 이 저장소의 lifecycle 경계를
사용한다. ([Spring Cloud AWS AppConfig 문서](https://github.com/awspring/spring-cloud-aws/blob/main/docs/src/main/asciidoc/appconfig.adoc))

## 5. Import 문법과 설정 계약

### 5.1 Import location

기본 문법은 다음과 같다.

```properties
spring.config.import=aws-app-config:orders#production#prod
```

세 component는 순서대로 application, configuration profile, environment다.
각 문자열은 AWS API의 `applicationIdentifier`,
`configurationProfileIdentifier`, `environmentIdentifier`에 그대로 전달한다.
AWS API가 이름과 identifier를 모두 허용하므로 resolver가 이름/identifier를
추측하거나 변환하지 않는다.

구분자는 `bluetape4k.aws.app-config.separator`로 바꾼다.

```yaml
bluetape4k:
  aws:
    app-config:
      separator: "|"

spring:
  config:
    import: aws-app-config:orders|production|prod
```

separator는 비어 있지 않아야 하며 CR/LF/NUL을 포함할 수 없다. `Pattern.quote`로
분리하므로 정규식 metacharacter도 값 그대로 동작한다. component가 정확히 세
개가 아니거나 빈 값이면 resolver 단계에서 안전한 configuration error를 낸다.
`optional:`은 Spring Boot location prefix로만 지정한다.

```properties
spring.config.import=optional:aws-app-config:orders#production#prod
```

location query는 다음 두 key만 허용한다.

| Key | 값 | 기본값 | 의미 |
|---|---|---|---|
| `format` | `auto`, `properties`, `yaml`, `json` | `auto` | response content type 또는 명시 형식으로 payload 해석 |
| `prefix` | 비어 있지 않은 property prefix | 없음 | 평탄화한 key 앞에 붙이는 prefix |

query key/value는 percent-decode를 한 번만 수행하며 중복, 빈 값, unknown key,
control character는 거부한다.

### 5.2 Application properties

```yaml
bluetape4k:
  aws:
    app-config:
      enabled: true
      region: ap-northeast-2
      endpoint-override: null
      separator: "#"
      fail-fast: true
      refresh-interval: null
      required-minimum-poll-interval: 15s
```

`region`과 `endpoint-override`는 기존 AWS service source 규칙을 따른다.
유효한 endpoint override에는 유효한 region이 필요하다. `refresh-interval`이
없으면 refresh를 실행하지 않는다. 값이 있으면 15초 이상이어야 하며, 초 단위
변환은 overflow-safe하게 수행한다. `required-minimum-poll-interval`도 15초
이상 24시간 이하로 제한하고 `StartConfigurationSession`의
`RequiredMinimumPollIntervalInSeconds`로 전달한다. 실제 요청 간격은 이 두 설정과
AppConfig 응답의 `NextPollIntervalInSeconds` 중 가장 긴 값을 사용하며, 서버 값이
없거나 15초~24시간 범위를 벗어나면 설정한 최소 간격으로 대체한다.

### 5.3 조건부 client bean

`AppConfigAutoConfiguration`은 다음 조건을 모두 만족할 때만 활성화한다.

- `software.amazon.awssdk.services.appconfigdata.AppConfigDataClient`가
  classpath에 있다.
- `bluetape4k.aws.enabled=true`이고
  `bluetape4k.aws.app-config.enabled=true`다.
- 기존 `AppConfigDataClient` bean이 없다.

bean builder는 공유 AWS 기본값, AppConfig service 설정, credentials provider,
기존 `AwsSyncClientCustomizer` 및 typed
`AwsClientCustomizer<AppConfigDataClientBuilder>` 순서를 따른다. ConfigData
bootstrap client는 명시적으로 등록한 bootstrap customizer만 적용한다. 일반
application bean customizer를 bootstrap 단계에서 자동 검색하지 않는다.

## 6. 구조와 데이터 흐름

```text
spring.config.import
        |
        v
AppConfigDataLocationResolver
  - separator/source/format 검증
  - Binder + enabled/class guard
  - bootstrap client lazy registration
        |
        v
AppConfigDataResource
        |
        v
AppConfigDataLoader -- StartConfigurationSession --> AppConfigDataClient
        |                                             |
        |                                   GetLatestConfiguration
        v                                             v
AppConfigDataPropertySource <--- initial values + next token
        |
        +--> ConfigData / Environment
        |
        +--> AppConfigReloadLifecycle (refresh-interval 명시 시)
                    |
                    +--> context-owned client + single scheduler
                    +--> token 교체 / empty response 보존
                    +--> atomic property map 교체
```

### 6.1 Session adapter

SDK type이 public ConfigData SPI descriptor로 새지 않도록 내부 adapter를 둔다.
테스트 가능한 contract는 다음과 같다.

```kotlin
internal interface AppConfigDataSessionClient {
    fun startSession(context: AppConfigRequestContext): String
    fun getLatest(token: String): AppConfigDataSnapshot
}

internal data class AppConfigDataSnapshot(
    val nextToken: String,
    val nextPollInterval: Duration?,
    val contentType: String?,
    val content: ByteArray,
)
```

실제 SDK adapter는 request/response를 위 모델로 변환한다. token, content,
response object는 log 또는 exception message에 전달하지 않는다.
`nextToken`은 공백이 아닌 값이어야 하며, 응답의 token이 비어 있으면 sanitized
adapter 오류로 처리한다.

초기 load 순서:

1. `StartConfigurationSession`으로 initial token을 받는다.
2. 그 token으로 `GetLatestConfiguration`을 한 번 호출한다.
3. response의 `nextPollConfigurationToken`을 다음 state로 저장한다.
4. content가 비어 있으면 값은 빈 map으로 시작하고, 다음 token과 delay만
   반영한다.
5. content가 있으면 format decoder와 prefix flatten을 적용해 map을 만든다.

runtime poll 순서:

1. 현재 next token으로 `GetLatestConfiguration`을 호출한다.
2. 성공한 모든 response에서 token을 즉시 새 token으로 교체한다.
3. 빈 response는 기존 기준 데이터를 유지하고 새 token/delay만 반영한다.
4. content가 있으면 token을 먼저 교체한 뒤 완전히 파싱한 immutable map을
   atomic reference로 교체한다. decoder 실패도 기존 기준 데이터를 유지하며,
   이미 교체한 새 token과 서버 delay를 사용해 다음 poll에서 재시도한다.
5. 정상 응답 뒤 다음 실행은 `max(refresh-interval,
   required-minimum-poll-interval, nextPollInterval)` 이후로 예약한다.
6. transport/session/token-invalid 오류는 기존 값을 유지하고 session을 폐기한다.
   다음 시도는 bounded exponential backoff와 full jitter(1초 시작, 5분 상한)를
   적용한 뒤 새 session을 시작한다. 성공하면 backoff를 초기화한다. context
   종료에 따른 cancellation은 조용히 종료한다.

### 6.2 PropertySource와 lifecycle

`AppConfigDataPropertySource`는 `EnumerablePropertySource`를 확장하고 현재
값을 immutable map 기준 데이터로 보관한다. `getProperty`,
`containsProperty`, `getPropertyNames`는 현재 기준 데이터만 읽는다. poller는
property source의 map을 부분 수정하지 않고 새 map으로 교체한다.

`AppConfigReloadLifecycle`은 application context refresh 이후 environment의
`AppConfigDataPropertySource`를 찾아 다음을 보장한다.

- context당 scheduler는 하나만 만든다.
- scheduler는 활성 resource 수와 8 중 작은 값(최소 1)을 pool size로 갖는
  `ScheduledThreadPoolExecutor` 하나이며, resource를 추가로 발견해도 executor를
  다시 만들지 않는다.
- opaque resource identity당 poller는 하나만 시작한다.
- refresh가 비활성인 source는 scheduler에 등록하지 않는다.
- resource당 동시에 실행 중이거나 예약된 task는 하나이며, task 완료 후
  self-reschedule하는 fixed-delay 방식만 사용한다. `scheduleAtFixedRate`는
  사용하지 않고, scheduler queue 크기는 활성 resource 수를 넘지 않는다.
- stop/close는 신규 예약 차단 → future cancel → bounded drain 대기 순서로
  수행한다. 5초 안에 종료되지 않으면 `shutdownNow`로 강제 종료하고
  interrupt 상태를 보존한다. executor는 daemon thread, cancel task 제거 정책,
  종료 후 delayed task 미실행을 보장한다.
- lifecycle이 client를 새로 만들거나 client close를 중복 수행하지 않는다.

초기 ConfigData load에 사용한 bootstrap client는 기존 bootstrap close listener가
닫는다. runtime poller는 context-owned client만 사용하고 Spring bean destroy
순서에서 poller가 먼저 멈춘 뒤 client가 닫힌다.

### 6.3 Refresh semantics

runtime refresh가 켜져도 다음 경계를 유지한다.

| 소비 방식 | refresh 결과 |
|---|---|
| `Environment.getProperty` | 다음 조회부터 최신 기준 데이터를 읽는다. |
| 새로 생성되는 `@Value` 주입 | 주입 시점의 Spring semantics를 따른다. 기존 bean field는 자동 변경하지 않는다. |
| `@ConfigurationProperties` | 초기 bind는 수행하지만 자동 rebind하지 않는다. |
| Spring Cloud Context refresh | 의존성을 추가하지 않으므로 제공하지 않는다. |

따라서 mutable runtime configuration이 필요한 caller는 `Environment`를
명시적으로 조회하거나 별도 application-level rebind 전략을 소유해야 한다.

## 7. Format decoding과 오류 정책

### 7.1 Format

- `properties`: `PropertiesPropertySourceLoader`로 읽는다.
- `yaml`: `YamlPropertySourceLoader`로 읽는다.
- `json`: 기존 `flattenJsonObject`로 object를 dotted key와 array index로
  평탄화한다.
- `auto`: `contentType`을 우선한다. `text/plain`은 properties로 처리하고,
  알려지지 않은 content type은 fail-fast configuration error로 처리한다.
- payload 값과 원본 body는 로그에 남기지 않는다.
- payload bytes는 1 MiB, flatten depth는 32, 최종 property 수는 10,000을
  넘지 않아야 한다. 한도를 넘거나 중간 구조 생성에 실패하면 decoder 오류로
  처리하고 기존 기준 데이터를 보존하며 raw payload와 중간 구조를 즉시
  해제한다.

### 7.2 Startup failure

| 조건 | `optional:` | required |
|---|---|---|
| AppConfig resource not found | `null` 반환으로 import 생략 | `ConfigDataResourceNotFoundException` |
| `fail-fast=false`의 기타 초기 오류 | 빈 source | 빈 source |
| `fail-fast=true`의 인증/network/parse 오류 | sanitized `AwsConfigDataLoadException` | sanitized `AwsConfigDataLoadException` |
| cancellation | 항상 cancellation 전파 | 항상 cancellation 전파 |
| disabled | client/network 없이 빈 no-op source | client/network 없이 빈 no-op source |

`ResourceNotFoundException` 등 SDK별 not-found 판정은 AppConfig adapter 경계에서
내부적으로 분류한다. 예외 message와 cause에는 raw application/profile/
environment, endpoint, token, body를 복사하지 않는다.

### 7.3 Runtime failure

runtime request 또는 decoder 실패 시 마지막 정상 map을 유지한다. transport/
token-invalid 실패의 다음 poll은 bounded backoff 후 새 session으로 재시도하고,
decoder 실패는 이미 저장한 새 token과 서버 delay로 재시도한다. 로그에는
`bluetape4k.aws.configdata.app-config.` opaque identity와 예외 class 이름만
남긴다. 종료 cancellation은 warning을 남기지 않는다.

## 8. 보안·운영·비용

- runtime consumer는 `software.amazon.awssdk:appconfigdata`를 직접 추가한다.
  SDK가 없는 classpath에서 ConfigData factory discovery가 `NoClassDefFoundError`를
  내지 않아야 한다.
- 최소 권한 IAM 문서는 다음 두 data-plane action만 포함한다. 현재 AWS Service
  Authorization 표에서 이 두 action은 resource type과 condition key를 제공하지
  않으므로 `Resource: "*"`를 사용해야 하며, 애플리케이션이 허용하는 account/
  region은 role boundary·조직 정책·네트워크 경계로 제한하도록 안내한다. 이후
  AWS가 resource-level 조건을 제공하면 문서와 예제를 함께 갱신한다.

  - `appconfig:StartConfigurationSession`
  - `appconfig:GetLatestConfiguration`

- credential, token, response body, decoded secret 값을 로그·metric tag·예외에
  넣지 않는다.
- GetLatest 호출은 AWS AppConfig Data 사용량과 비용에 영향을 준다. server가
  반환한 poll interval을 무시하거나 짧은 fixed interval로 덮어쓰지 않는다.
- Floci/LocalStack이 AppConfig Data API를 충분히 지원하지 않으면 fake session
  contract test를 필수 증거로 삼고, 실제 AWS smoke는 명시적 환경 변수로만
  실행한다. emulator 성공을 production IAM·비용 증거로 간주하지 않는다.
- refresh 중단, context close, client close 순서를 운영 manual에 기록한다.

## 9. 테스트 전략과 수용 기준 매핑

모든 Kotlin 테스트는 JUnit 5, MockK, `bluetape4k-assertions`를 사용한다.
SDK 호출이 필요한 테스트는 fake `AppConfigDataSessionClient`를 우선하고,
실제 SDK adapter 계약은 mock client로 분리한다.

| 수용 기준 | 테스트 증거 |
|---|---|
| import key parsing, custom separator, name/identifier | parser 단위 테스트: 3 component, separator quoting, 빈/control/중복/unknown 입력 |
| optional/missing config | loader 테스트: optional not-found `null`, required not-found exception, disabled no-op |
| format decoding | properties/YAML/JSON content type와 explicit format, prefix/array flatten |
| token/version handling | fake client contract: initial token, 매 응답 next token 교체, stale token 재사용 금지 |
| empty response | 기존 기준 데이터 유지 + next token/delay 갱신 |
| client failure/cancellation | sanitized exception, fail-fast false, cancellation 전파/무로그 |
| conditional bean/client customizer | `ApplicationContextRunner`, class guard, global/service region·endpoint 우선순위, customizer 순서 |
| refresh lifecycle | lifecycle 테스트: source별 중복 poller 0, scheduler 1, task cancel, client close 순서 |
| concurrency | 동시에 실행된 poll이 하나의 state lock을 사용하고 기준 데이터가 부분 상태를 노출하지 않음 |
| real smoke | `BLUETAPE4K_APPCONFIG_REAL_SMOKE=true`와 명시적인 AWS 설정이 있을 때만 initial load/update 감지 |
| 문서 | README/한국어 README, manual 양언어 parity, manifest inventory, IAM/cost 예시 |

주요 테스트 클래스 후보:

- `AppConfigDataLocationParserTest`
- `AppConfigDataLoaderTest`
- `AppConfigDataSessionTest`
- `AppConfigDataPropertySourceTest`
- `AppConfigReloadLifecycleTest`
- `AppConfigAutoConfigurationTest`
- `AppConfigDataClasspathGuardTest`
- `AppConfigDataDocumentationParityTest`
- `AppConfigDataRealSmokeTest` (opt-in)

## 10. 변경 파일 경계

예상 production 변경:

- `gradle/libs.versions.toml`
- `aws-spring-boot/build.gradle.kts`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/appconfig/`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/config/`의 backend
  dispatch/failure 확장
- `aws-spring-boot/src/main/resources/META-INF/spring.factories`
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

예상 test 변경:

- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/appconfig/`
- 기존 ConfigData SPI/registration/documentation parity 회귀 테스트

예상 문서 변경:

- `aws-spring-boot/README.md`
- `aws-spring-boot/README.ko.md`
- `docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md`
- `docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md`
- `docs/manual/manifest.yaml` 또는 generator가 갱신하는
  `docs/manual/generated/manifest.json`

새 public converter, Spring Cloud Context dependency, unrelated Epic child
파일은 변경하지 않는다.

## 11. 위험과 완화

| 위험 | 완화 |
|---|---|
| Bootstrap client가 runtime 중 닫힘 | 초기 load와 runtime client를 분리하고 context lifecycle 테스트로 순서를 고정 |
| 단일 사용 token 재사용 | 성공 response마다 next token으로 교체하고 실패 시 session 재시작 |
| empty response가 기존 값을 지움 | content length 0이면 map을 교체하지 않는 테스트 |
| poller 중복/스레드 leak | opaque identity registry, scheduler 1개, close/cancel 검증 |
| SDK 없는 소비자 classpath | `@ConditionalOnClass(name=...)`, string class guard, filtered classloader 테스트 |
| `@ConfigurationProperties`가 자동 갱신된다는 오해 | manual/README에 Environment와 binding semantics 표를 함께 제공 |
| AWS 비용 증가 | refresh 기본 disabled, server next interval 존중, IAM/cost 문서와 smoke guard |
| emulator API 차이 | fake contract test를 필수로 두고 real smoke를 opt-in으로 분리 |

## 12. 문서 품질 확인

이 문서는 `$bluetape4k-writer` 계약에 따라 다음을 확인한다.

- SPW-01: 현재 코드·Issue #458·공식 AWS/Spring Boot 링크를 사실 근거로
  분리했다.
- SPW-02: 독자, public boundary, application-owned/context-owned/bootstrap-owned
  lifecycle을 구분했다.
- SPW-03: 한국어 자연스러움과 용어 일관성을 점검하고, 식별자·명령·URL은
  원문을 보존한다.
- SPW-04: code token과 문서 예제를 실제 구현/README parity 테스트에 연결한다.
- SPW-05: 구현 plan과 review에서 변경 파일, 테스트 명령, 미구현 범위를 다시
  대조한다.

이 문서는 설계 승인 후 구현 plan의 기준 원본이며, 구현 중 새로운 요구가
발견되면 임의로 범위를 넓히지 않고 별도 decision 기록으로 남긴다.
