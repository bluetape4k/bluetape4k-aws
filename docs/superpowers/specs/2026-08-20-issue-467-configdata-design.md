# Spring Boot AWS ConfigData import 설계

## 문제와 목표

현재 `aws-spring-boot`는 S3, Parameter Store, Secrets Manager 설정을
`EnvironmentPostProcessor`와 `PropertySource`로 읽는다. 이 경로는 기존
애플리케이션과의 호환성에는 유용하지만 Spring Boot의 표준
`spring.config.import` lifecycle에서 제공하는 `optional` import, profile별
구성, import 순서에 따른 precedence를 사용할 수 없다.

Issue #467의 목표는 세 AWS 설정 backend에 ConfigData resolver/loader를
추가하는 것이다. 기존 `EnvironmentPostProcessor`와 lazy refresh 경로는
property-source 순서·refresh 동작을 보존한 legacy 호환 경로로 유지한다.
보안상 raw identifier를 제거하는 로그·예외 메시지 정리는 허용되는 진단
변경이다.

### 독자와 범위

- 독자: `aws-spring-boot` 사용자가 Spring Boot 초기 구성과 AWS 외부 설정을
  선택하는 경우
- 구현 모듈: `aws-spring-boot` 한정
- public API: Spring Boot가 `spring.factories`에서 찾는 resolver/loader 외에
  새 공개 확장 API를 만들지 않는다.
- 외부 부작용: ConfigData import가 실제 AWS client를 만들고 startup 시 원격
  값을 읽을 수 있다. 테스트는 Floci 우선, LocalStack fallback으로 실행한다.
- 제외: AWS AppConfig runtime reload(#458), secret rotation watcher, 전체
  Spring Cloud Config Server, 공개 `BatchExecutionStrategy`/converter 추상화
  (#514)

## 현재 근거

### 로컬 구현

- `AwsEnvironmentPropertySourceSupport.kt`는 전역
  `bluetape4k.aws.enabled` 판정, Binder `bindOrCreate`, AWS property source
  삽입, JSON flatten, Parameter Store path key 변환을 제공한다.
- `S3ConfigProperties`, `ParameterStoreProperties`,
  `SecretsManagerProperties`는 region, endpoint, fail-fast, refresh, source
  모델을 이미 정의한다.
- 세 `*PropertySourceLoader`는 SDK client를 만들고 source를 읽어
  `AwsLoadedPropertySource`를 반환한다. S3의 properties/YAML/JSON 파싱과
  Secrets Manager의 JSON/TEXT 처리는 이 loader를 기준으로 재사용한다.
- 세 `*EnvironmentPostProcessor`는 기존 설정을 유지해야 하므로 ConfigData
  구현에서 제거하거나 property-source 순서·refresh 동작을 바꾸지 않는다.
  단, source identifier가 로그·예외로 새어 나가지 않도록 진단 경계는
  보강한다.

### 공식 참고

Spring Cloud AWS의 현재 구현은 다음 계약을 사용한다.

- location prefix: `aws-s3:`, `aws-parameterstore:`,
  `aws-secretsmanager:`
- `optional:`은 Spring Boot `ConfigDataLocation`의 optional 상태로 처리한다.
- resolver는 `ConfigDataLocationResolverContext`의 Binder와 BootstrapContext를
  사용하고, loader는 `ConfigDataLoaderContext`에서 client를 가져온다.
- 등록 키는 `META-INF/spring.factories`의
  `org.springframework.boot.context.config.ConfigDataLocationResolver`와
  `ConfigDataLoader`이다.

참고 링크:

- [Spring Cloud AWS ConfigData resolver 공통 구현](https://github.com/awspring/spring-cloud-aws/blob/main/spring-cloud-aws-autoconfigure/src/main/java/io/awspring/cloud/autoconfigure/config/AbstractAwsConfigDataLocationResolver.java)
- [S3 ConfigData resolver](https://github.com/awspring/spring-cloud-aws/blob/main/spring-cloud-aws-autoconfigure/src/main/java/io/awspring/cloud/autoconfigure/config/s3/S3ConfigDataLocationResolver.java)
- [Parameter Store ConfigData resolver](https://github.com/awspring/spring-cloud-aws/blob/main/spring-cloud-aws-autoconfigure/src/main/java/io/awspring/cloud/autoconfigure/config/parameterstore/ParameterStoreConfigDataLocationResolver.java)
- [Secrets Manager ConfigData resolver](https://github.com/awspring/spring-cloud-aws/blob/main/spring-cloud-aws-autoconfigure/src/main/java/io/awspring/cloud/autoconfigure/config/secretsmanager/SecretsManagerConfigDataLocationResolver.java)
- [Spring Cloud AWS ConfigData 등록](https://github.com/awspring/spring-cloud-aws/blob/main/spring-cloud-aws-autoconfigure/src/main/resources/META-INF/spring.factories)

## 선택지와 결정

### 선택지 A — 기존 loader를 감싸는 내부 ConfigData adapter (선택)

각 resolver가 location을 기존 `*Properties.Source`로 변환하고, 각 loader가
이미 검증된 client/파싱/fail-fast 경계를 실행하도록 한다. ConfigData loader는
반환된 값만 `MapPropertySource`로 감싼다.

- 장점: AWS 호출·파싱 경계를 중복하지 않고 기존 EPP의 안정된 동작을
  보존하면서 ConfigData에는 명시적인 not-found 정책만 적용한다.
- 단점: ConfigData resource가 startup용 source 설정을 보관해야 하고, lazy
  refresh는 ConfigData 경로에 자동으로 붙지 않는다.

### 선택지 B — 공통 AWS ConfigData SPI를 새로 만들고 EPP와 함께 사용

세 backend를 하나의 내부 SPI로 통합하고 EPP와 ConfigData가 동일 SPI를
호출하도록 loader를 크게 재구성한다.

- 장점: 장기적으로 공통 lifecycle을 만들 수 있다.
- 단점: 이번 이슈에 필요한 범위를 넘어 loader와 기존 refresh 경계를 동시에
  변경하며, 회귀 범위가 크다.
- 기각 이유: 기존 loader의 안정된 동작을 보존해야 하고 #467은 ConfigData
  import 추가이지 전체 환경 source SPI 재설계가 아니다.

### 선택지 C — Spring Cloud AWS 의존성에 위임

`io.awspring.cloud` ConfigData 구현을 compileOnly/runtime dependency로
가져와서 위임한다.

- 장점: 공식 구현을 직접 재사용할 수 있다.
- 단점: 현재 모듈의 SDK compileOnly 정책과 충돌하고, Spring Cloud AWS의
  자동 구성·버전·의존성 그래프를 함께 끌어온다.
- 기각 이유: 이 저장소의 독립적인 AWS SDK wrapper 경계를 넓히고 사용자가
  추가해야 할 runtime dependency를 바꾼다.

## location 문법

Spring Cloud AWS와 동일한 prefix를 사용하고, 이번 모듈의 추가 요구사항인
prefix/format/경로 옵션은 URI query로 표현한다. `optional:`은 Spring Boot가
처리하므로 query에 중복해서 넣지 않는다.

```properties
spring.config.import=optional:aws-s3:/config-bucket/application.yml?prefix=app&format=yaml,aws-parameterstore:/application?prefix=app&recursive=true&withDecryption=true,optional:aws-secretsmanager:application?prefix=app&format=json
```

YAML에서는 같은 값을 indexed property로 나누지 않고 list로 작성한다.

```yaml
spring:
  config:
    import:
      - optional:aws-s3:/config-bucket/application.yml?prefix=app&format=yaml
      - aws-parameterstore:/application?prefix=app&recursive=true&withDecryption=true
      - optional:aws-secretsmanager:application?prefix=app&format=json
```

지원 규칙:

- `aws-s3:/<bucket>/<key>`: `format`은 `auto`, `properties`, `yaml`, `json`;
  `prefix`는 기존 S3 parser의 property prefix다.
- `aws-parameterstore:/<path>`: `prefix`, `recursive`, `withDecryption`을
  지원한다.
- `aws-secretsmanager:<secret-id>`: `prefix`, `format`(`json` 또는 `text`)을
  지원한다. `format=text`이면 property name을 결정할 `prefix`를 반드시
  지정하며, `prefix` 없이 text 형식을 지정하면 resolver 단계에서
  configuration error로 거부한다.
- `failFast`는 세 backend의 기존 바인딩 속성으로 적용한다. location의
  `optional:`은 해당 import 하나의 누락 동작을 결정하며, 두 방식을 query에서
  중복 선언하지 않는다.
- query key는 backend별 허용 목록만 받으며 중복·빈 값·잘못된 Boolean/enum은
  startup configuration error로 거부한다.
- query와 location에서 CR/LF/NUL은 거부한다. 오류·진단 로그에는 secret
  value와 raw secret identifier를 포함하지 않는다.
- location path와 query value는 percent-decoding을 한 번만 수행한다. decode 후
  빈 값·CR/LF/NUL은 거부하고, query key는 문서에 적은 camelCase를
  대소문자 구분해 사용한다. Secret ID는 AWS 이름 또는 ARN을 허용하되
  비어 있거나 control character를 포함하면 resolver 오류로 거부하며,
  나머지 ARN 검증은 AWS SDK 오류를 보존한다.
- 여러 location의 순서와 profile별 import 파일 선택은 Spring Boot
  ConfigData lifecycle에 맡긴다. resolver는 `resolveProfileSpecific`에서
  remote location 이름을 profile suffix로 변형하지 않고 빈 목록을 반환한다.
  대신 `application-prod.yml`처럼 profile 문서에 선언된 import는 Boot가
  활성 profile에 맞춰 처리한다.

## 아키텍처와 lifecycle

### Resolver

각 resolver는 `DeferredLogFactory` 생성자를 사용하고 다음 순서로 동작한다.

1. prefix를 판정한다.
2. `ConfigDataLocation`의 optional 상태와 non-prefixed value를 읽는다.
3. Binder로 `bluetape4k.aws`, backend 공통 속성, service-specific 속성을
   만든다.
4. `bluetape4k.aws.enabled` 또는 backend `enabled=false`면 SDK client를
   만들지 않는 disabled resource를 반환한다.
5. 활성 상태이면 SDK class 존재를 검증하고 location query를 source 모델로
   변환한 resource를 반환한다. 활성 backend의 client supplier는
   `BootstrapRegistry`에 backend별 client type(`S3Client`, `SsmClient`,
   `SecretsManagerClient`)로 한 번만 lazy 등록한다. 이미 등록된 type에는
   supplier나 close listener를 다시 추가하지 않는다.

Resource는 backend, source, optional, bound properties, safe property-source
name을 불변 값으로 보관한다. `equals`/`hashCode`는
`(backend, canonical decoded source, query options, optional)`만 사용하고
bound properties, client instance, raw identifier, deferred logger는 identity에
포함하지 않는다. `toString`도 opaque identity와 query key만 출력한다.
Resolver는 startup metadata만 만들고 원격 I/O는 loader 단계까지 미룬다.

### Loader

각 loader는 `ConfigDataLoader`를 구현한다.

1. disabled resource면 빈 `MapPropertySource`를 반환한다. 이 경로에서는
   client 생성과 네트워크 호출이 없다.
2. 활성 resource면 `ConfigDataLoaderContext`의 Bootstrap client를 가져와
   기존 `*PropertySourceLoader`의 `internal load(client, source,
   strictPolicy)` 단일-source 경계에 전달한다. 기존 EPP용 `load(properties)`
   facade는 client 생성·legacy policy를 유지하고, ConfigData adapter만
   injectable client와 strict policy를 사용한다. 같은 backend의 여러 import는
   하나의 client를 공유한다.
3. 반환된 값은 `MapPropertySource` 하나로 감싸 `ConfigData`로 반환한다.
4. resolver가 `BootstrapRegistry.addCloseListener`로 각 singleton client의
   `BootstrapContextClosedEvent` close를 한 번 등록하고, loader는 개별 client를
   만들거나 닫지 않는다. client supplier는 전역/service region·endpoint와
   `AwsProperties.credentials.webIdentity`를 반영하며, web identity가
   비활성일 때는 AWS SDK default credential chain을 사용한다.
5. ConfigData 전용 단일-source 경계는 `optional:`이면 not-found만 생략하고,
   인증·credential·network·parse·configuration 오류는 보존한다.
   `failFast=false`는 legacy EPP의 기존 정책에만 적용하며 ConfigData의
   비-not-found 오류를 삼키지 않는다.
6. ConfigData 경로에는 lazy refresh callback을 등록하지 않는다. refresh가
   필요한 legacy 사용자는 기존 EPP 경로를 계속 사용하며, ConfigData는
   startup 시점의 구성 기준값만 제공한다.

### 공통 client 설정

서비스별 region/endpoint가 있으면 전역 `bluetape4k.aws` 값보다 우선한다.
endpoint override에는 region이 필요하다는 기존 검증을 유지한다. Bootstrap
단계에서는 기존 AWS SDK default credential chain을 사용하고, 이미 제공되는
web identity 설정을 해석할 수 있는 공통 helper를 재사용한다.

애플리케이션 bean customizer는 아직 ApplicationContext가 만들어지기 전인
ConfigData 단계에서 자동 주입할 수 없다. 따라서 기존 `AwsSyncClientCustomizer`는
애플리케이션이 Spring Boot 표준 `BootstrapRegistryInitializer`로 명시적으로
등록한 경우에만 적용하고, 등록되지 않은 일반 bean customizer는 ConfigData
startup client에 적용하지 않는다. 적용 순서는 AWS 공통 기본값·credential,
service 설정, bootstrap customizer, build 순서로 고정한다. 새 public
converter/strategy API는 추가하지 않는다.

### 진단 redaction

ConfigData의 resource/property-source 이름과 startup warning은
`bluetape4k.aws.configdata.<backend>.<sha256-12>` 형태의 opaque identity를
사용한다. hash input은 `backend + "\u0000" + decodedLocation`을 UTF-8로
인코딩한 SHA-256의 소문자 앞 12자리로 고정한다. 원문 location은 메모리의
source 모델에서만 사용하고 로그·예외 메시지·`Throwable` cause message에
복사하지 않는다. legacy property-source
이름은 호환성을 위해 유지할 수 있지만 skip, refresh failure, 합성 예외의
메시지는 opaque identity와 backend, 예외 class 이름만 포함한다. SDK 예외를
그대로 cause로 연결하지 않고 sanitized exception으로 감싸 raw message가
전파되지 않게 한다. 이 규칙은 missing, no-SecretString, parse,
authentication, endpoint 오류를 각각 log-capture와 exception assertion으로
검증한다.

### not-found 분류와 bootstrap 등록

ConfigData strict policy의 not-found 집합은 backend별로 고정한다.

- S3: `NoSuchBucketException`, `NoSuchKeyException`, 또는 HTTP status 404만
  not-found다. 403과 그 밖의 `S3Exception`은 credential/auth/network 오류다.
- Parameter Store: `ParameterNotFoundException`만 not-found다. 정상 응답의
  빈 `parameters()`는 빈 구성으로 성공한 것이며 누락으로 재분류하지 않는다.
- Secrets Manager: `ResourceNotFoundException`만 not-found다. Secret String이
  없는 응답은 configuration/data-format 오류이며 optional이어도 생략하지
  않는다.

Resolver는 활성 backend마다 `BootstrapRegistry.isRegistered(clientType)`를
확인한 뒤 `registerIfAbsent(clientType)`, `addCloseListener`를 한 쌍으로
한 번만 실행한다. supplier는 전역/service region·endpoint를 병합하고,
web identity가 활성인데 provider 생성에 필요한 값이 잘못되면 default chain으로
fallback하지 않고 sanitized configuration error로 fail-closed 한다. close
listener는 `event.getBootstrapContext().get(clientType)?.close()`를 한 번만
호출하며 loader에는 close 권한이 없다. 이 registration·provider·close 순서를
resolver 단위 테스트와 동일 backend 다중 import 통합 테스트로 고정한다.

### Precedence와 호환성

- ConfigData import 사이의 precedence는 Spring Boot가 결정한다. 같은
  `spring.config.import` 목록에서는 뒤에 선언한 location이 앞의 location을
  override하고, profile 문서의 import는 Boot의 profile-specific document
  precedence를 따른다. imported data는 import를 선언한 문서보다 우선한다.
- 기존 EPP는 현재 source 이름·삽입 순서·lazy refresh를 보존한다.
- 현재 Boot 4 property-source 적용 순서에서 기존 EPP는
  `commandLineArgs` 바로 뒤에 AWS source를 삽입하고 ConfigData import는
  뒤쪽에 추가하므로, 동일 key가 겹치면 legacy EPP 값이 ConfigData 값보다
  우선한다. 이 혼합 winner는 `ConfigDataLegacyPrecedenceTest`로 고정하는
  migration 계약이며 새 구성에서는 동일 key를 겹치게 만들지 않는다.
- ConfigData와 legacy source의 혼합 key를 자동 병합하거나 ConfigData 내부
  순서를 EPP 삽입 규칙으로 재정렬하지 않는다. 애플리케이션이 migration 중
  겹치는 key를 사용하면 startup warning에 backend와 opaque source identity만
  표시하고, 위 winner 규칙을 따른다.

## 실패 모드와 대응

| 실패 모드 | 대응 |
|---|---|
| AWS SDK service class가 runtime classpath에 없음 | resolver가 dependency notation을 포함한 명시적 오류를 반환하고 client를 만들지 않는다. disabled 경로는 class 검증 전에 종료한다. |
| 필수 location이 없거나 이름/ARN이 잘못됨 | `optional:`이면 not-found만 빈 ConfigData로 생략하고, 필수 location은 `ConfigDataResourceNotFoundException`으로 보존한다. 빈/control-character location은 resolver configuration error로 즉시 거부하고, 나머지 SDK ARN/name 오류는 raw identifier 없이 보존한다. |
| query 문법 오류·지원하지 않는 key·잘못된 enum/Boolean | resolver 단계에서 안전한 configuration error로 조기 거부하며 secret value를 로그에 남기지 않는다. |
| global/backend `enabled=false` | disabled resource와 빈 property source만 반환한다. SDK client 생성·외부 호출 횟수는 0이다. |
| 여러 import가 같은 backend client를 반복 생성하거나 닫지 않음 | BootstrapRegistry에 backend별 lazy client를 한 번만 등록하고 bootstrap lifecycle에 close를 위임한다. loader는 client ownership을 갖지 않는다. |
| 기존 EPP lazy refresh와 ConfigData를 동시에 사용 | ConfigData는 startup 시점의 구성 기준값, EPP는 legacy refresh로 분리한다. 겹치는 key는 startup warning과 documented legacy winner로 처리하며 관련 회귀 테스트로 현재 EPP 동작을 고정한다. |
| AWS 오류 로그·예외에 source identifier가 노출됨 | ConfigData resource와 새 진단은 backend별 SHA-256 기반 opaque identity만 사용한다. 기존 EPP의 property-source 이름 호환성은 유지하되 refresh/skip 로그와 Secrets Manager의 합성 예외에서 raw bucket/key/path/secretId를 제거한다. secret value는 로그·예외·property-source name에 기록하지 않는다. |

## 테스트 전략

모든 새 Kotlin 테스트는 JUnit 5, MockK, `bluetape4k-assertions`, Given/When/Then
구조를 사용한다.

1. 순수 parser 테스트: 세 prefix, query decode, optional 분리, duplicate/
   unknown/malformed query, CR/LF/NUL, secret text prefix, ARN/name control
   character 거부.
2. resolver 테스트(`AwsConfigDataLocationResolverTest`): global/backend
   disabled, SDK class guard, bound region/endpoint/credentials, bootstrap
   registration exactly-once, `resolveProfileSpecific`의 empty 결과, source
   mapping, resource equality/toString의 opaque identity.
3. loader 테스트(`AwsConfigDataLoaderTest`): 기존 facade와
   `internal load(client, source, strictPolicy)` 경계, MapPropertySource 변환,
   backend별 not-found 분류, required 예외, auth/parse/network 예외 보존,
   disabled에서 client/network 0회, 같은 backend import의 client 1회 생성과
   bootstrap close, opaque redaction.
4. Spring Boot ConfigData slice(`AwsConfigDataImportApplicationTest`):
   properties comma-separated와 YAML list를
   모두 사용해 세 backend가 로드되는지 확인하고, profile 문서 import,
   뒤쪽 import wins, imported-vs-declaring document precedence 및
   `ConfigDataLegacyPrecedenceTest`의 EPP winner를 고정한다.
5. `META-INF/spring.factories`의
   `ConfigDataLocationResolver`/`ConfigDataLoader`에 세 concrete resolver와
   loader가 등록되는지 `AwsConfigDataFactoryRegistrationTest`로 확인한다.
6. 기존 세 `EnvironmentPostProcessor` 테스트는 그대로 실행해 legacy 경로와
   lazy refresh 회귀를 확인한다.
7. Floci 우선 통합 테스트에서 S3/SSM/Secrets Manager startup import를
   순차적으로 확인하고, Docker/에뮬레이터 실패는 제품 실패와 분리해 기록한다.

## 문서와 migration

- `aws-spring-boot/README.md`의 external configuration section과
  `aws-spring-boot/README.ko.md`의 대응 section을 갱신한다.
- `docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md`와
  `docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md`에
  동일한 canonical properties/YAML import 예제, profile/precedence 표,
  optional/fail-fast truth table, legacy EPP migration 표를 추가한다.
- `spring.config.import`와 기존 `bluetape4k.aws.*.config.sources`의 차이,
  startup 시점의 구성 기준값과 lazy refresh의 차이, IAM 권한,
  optional/fail-fast를 표로
  설명한다.
- EN/KO 문서는 heading·snippet·link 구조를 맞추고, 다음 parity 검사를
  DoD 증적으로 남긴다: `git diff --check`, manual contract test,
  README/manual canonical snippet 비교 결과.
- 실제 AWS credential/비용이 필요한 smoke는 이번 PR의 필수 검증이 아니다.
  Floci/LocalStack 범위를 넘어서는 검증은 별도 후속 이슈로 기록한다.

## 수용 기준과 DoD

- 세 backend가 `spring.config.import`로 동작하고 prefix/optional/profile/
  precedence/fail-fast 계약이 테스트와 문서에 있다.
- disabled/classpath-missing 경계에서 불필요한 SDK client와 네트워크 호출이
  없다.
- 기존 EPP와 lazy refresh 사용자가 회귀 없이 동작한다.
- malformed location, missing required source, optional source, secret log
  redaction을 테스트한다.
- `NoSuch*`/`ResourceNotFound`/`ParameterNotFound`만 optional skip되고,
  403·credential·parse·network·no-SecretString는 sanitized exception으로
  실패하는 backend별 truth table을 통과한다.
- Floci 우선 통합 테스트와 LocalStack fallback 결과를 다음 명령과
  evidence artifact에 기록한다.
  `./gradlew :bluetape4k-aws-spring-boot:test --tests '*ConfigData*' -Dbluetape4k.aws.emulator=floci`
  및 동일 명령의 `-Dbluetape4k.aws.emulator=localstack` 실행 결과를
  `docs/review/evidence/2026-08-20-issue-467-configdata.md`에 남긴다.
- absent-SDK는 service SDK를 숨기는 `ClassLoader` fixture로 resolver의
  classpath guard와 client creation 0회를 검증한다. 별도 dependency graph를
  변경하지 않는다.
- English/Korean manual/README가 동기화된다.
- rollback은 새 `ConfigData` `spring.factories` 등록과 새 config 패키지 파일을
  함께 되돌리고, 기존 EPP 파일은 건드리지 않는 단일 구현 commit 단위다.
  rollback checkpoint에서 `git revert <configdata-commit>` 후
  `./gradlew :bluetape4k-aws-spring-boot:test --tests '*EnvironmentPostProcessor*'`
  를 재실행해 legacy no-network/refresh 회귀가 없는지 증적을 남긴다.

## 설계 승인 기록

- 2026-08-20: 사용자가 ConfigData precedence는 Spring Boot 표준을 따르고
  기존 EPP는 legacy 호환 경로로 유지하는 방향을 승인했다.
