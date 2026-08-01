# 이슈 #6 Secrets Manager / Parameter Store 설계

## 배경

- 저장소: `bluetape4k-aws`
- 이슈: <https://github.com/bluetape4k/bluetape4k-aws/issues/6>
- 대상 모듈: `aws-spring-boot`
- 작업 유형: 새로운 Spring Boot 기능, 전체 설계 절차

이슈 #6은 awspring 없이 AWS Secrets Manager와 SSM Parameter Store를 Spring
`Environment` 소스로 통합하도록 요구한다. 사용자가 원격 값을 `@ConfigurationProperties`에
참여시키려면 일반 빈 바인딩보다 먼저 이 통합이 이루어져야 한다.

## 근거

- 현재 `aws-spring-boot` 자동 구성은 `AutoConfiguration.imports`, 문자열
  `@ConditionalOnClass`, 서비스별 `@ConfigurationProperties`, `compileOnly` AWS SDK 서비스
  의존성을 통해 서비스 클라이언트를 등록한다.
- Spring Boot 4.0.3 문서는 Environment를 조기에 변경하기 위한 `EnvironmentPostProcessor`를
  `META-INF/spring.factories`에 등록하도록 안내한다.
- AWS SDK Java v2는 `GetSecretValueRequest.secretId`와
  `GetSecretValueResponse.secretString`을 사용하는 `SecretsManagerClient.getSecretValue`를 제공한다.
- AWS SDK Java v2는 SSM `SsmClient.getParameter`와 `SsmClient.getParametersByPath`를 제공하며,
  SSM 파라미터 값은 `Parameter.value`에서 읽는다.
- GNO에는 이슈 #6 전용 과거 설계 자료가 없다. 현재 구현 근거는 저장소 소스와 공식
  Spring/AWS 문서에서 얻었다.

## 목표

1. AWS Secrets Manager와 SSM SDK 별칭 및 `compileOnly` 의존성을 추가한다.
2. 빈을 만들기 전에 구성된 원격 소스를 로딩하는 Environment 후처리기를 추가한다.
3. Secrets Manager와 Parameter Store 소스 목록을 위한 타입 안전 프로퍼티를 추가한다.
4. 관련 SDK 모듈이 있을 때만 AWS SDK 클라이언트를 추가한다.
5. 구성한 소스에 선택적 지연 새로 고침 기능을 추가한다.
6. Spring `@Value`를 조합한 `@SecretsValue`와 `@ParameterStoreValue` 애너테이션을 추가한다.
7. 소스 로딩과 새로 고침을 검증하는 ApplicationContextRunner 및 LocalStack 테스트를 추가한다.
8. `README.md`와 `README.ko.md`를 갱신한다.

## 제외 범위

- awspring 또는 Spring Cloud 의존성을 추가하지 않는다.
- 런타임 새로 고침 스케줄러를 추가하지 않는다. 시작 시 로딩을 안정적인 Spring Boot
  계약으로 유지하고, 선택적 새로 고침은 프로퍼티 접근 시 지연 수행하며 재로딩에 실패하면
  이전 값을 유지한다.
- Secrets Manager 바이너리 값을 지원하지 않는다.
- 계정 간 AssumeRole 도우미를 추가하지 않는다.

## 구성 모델

Secrets Manager 접두사: `bluetape4k.aws.secrets-manager`

- `enabled: Boolean = true`
- `region: String? = null`
- `endpointOverride: URI? = null`
- `failFast: Boolean = true`
- `refreshInterval: Duration? = null`
- `sources: List<Source> = emptyList()`

시크릿 소스:

- `name: String? = null`
- `secretId: String`
- `prefix: String? = null`
- `optional: Boolean = false`
- `format: SecretFormat = JSON`

시크릿 형식:

- `JSON`: JSON 객체를 프로퍼티 키로 파싱한다.
- `TEXT`: 전체 시크릿 문자열을 `prefix` 또는 소스 `name`에 노출한다.

Parameter Store 접두사: `bluetape4k.aws.parameter-store`

- `enabled: Boolean = true`
- `region: String? = null`
- `endpointOverride: URI? = null`
- `failFast: Boolean = true`
- `refreshInterval: Duration? = null`
- `sources: List<Source> = emptyList()`

파라미터 소스:

- `name: String? = null`
- `path: String`
- `prefix: String? = null`
- `recursive: Boolean = true`
- `withDecryption: Boolean = true`
- `optional: Boolean = false`

검증:

- `endpointOverride`에는 `region`이 필요하다.
- 소스 이름과 접두사가 있으면 공백이 아니어야 한다.
- 시크릿 소스에는 공백이 아닌 `secretId`가 필요하다.
- 파라미터 소스에는 `/`로 시작하며 공백이 아닌 절대 경로가 필요하다.
- 기능이 비활성화되었거나 소스가 없으면 원격 조회를 건너뛴다.
- `refreshInterval`이 있으면 양수여야 한다.

## 프로퍼티 매핑

Secrets Manager:

- `JSON` 시크릿은 점 표기법으로 평탄화한다.
- `TEXT` 시크릿에는 `prefix` 또는 `name`이 필요하며, 전체 시크릿 문자열을 해당 키에 할당한다.
- 생성한 모든 키 앞에 `prefix`를 붙인다.

Parameter Store:

- `nextToken`이 빌 때까지 `getParametersByPath`를 페이지 단위로 호출한다.
- 각 파라미터 이름에서 구성한 소스 경로를 제거한다.
- 남은 경로 세그먼트를 점으로 구분한 프로퍼티 키로 변환한다.
- `prefix`가 구성되었으면 앞에 붙인다.

프로퍼티 소스 순서:

- 명령줄 인수가 있으면 그 뒤에 원격 프로퍼티 소스를 추가하고, 그렇지 않으면
  `MutablePropertySources`의 맨 앞에 추가한다.
- 나중에 구성한 소스가 동일한 키를 가진 이전 소스를 예기치 않게 재정의하면 안 된다.
  각 소스는 별도의 이름 있는 프로퍼티 소스로 유지하며, Spring 프로퍼티 소스 순서가
  해석을 제어한다.

## 시작 및 실패 동작

- 소스가 구성되고 활성화되었는데 SDK 클래스가 없으면 조치 방법이 분명한
  `IllegalStateException`으로 실패한다.
- 선택적 소스를 로딩할 수 없으면 건너뛴다.
- `failFast=false`이면 실패한 소스를 로그에 기록하고 건너뛴다.
- AWS 클라이언트는 후처리기 내부에서 만들고 소스 로딩 직후 닫는다.
- Spring Environment 변경은 블로킹 시작 단계이므로 후처리기는 동기식 AWS SDK 클라이언트를 사용한다.
- `refreshInterval`을 설정하면 갱신 가능 `PropertySource`가 해당 간격이 지난 뒤 지연 재로딩한다.
  재로딩에 성공하면 메모리 내 맵을 교체하고, 건너뛰거나 실패하면 이전 맵을 유지한다.

## 테스트

ApplicationContextRunner 테스트:

- 소스를 구성하지 않으면 원격 조회가 발생하지 않는다.
- 소스를 구성했을 때만 SDK 클래스 누락으로 실패한다.
- 리전 없이 엔드포인트를 재정의하면 바인딩에 실패한다.
- JSON 시크릿 값이 Environment 프로퍼티가 된다.
- 텍스트 시크릿에는 명시적인 키가 필요하다.
- 파라미터 경로 값이 Environment 프로퍼티가 된다.
- 비활성화된 기능은 원격 조회를 건너뛴다.
- 선택적 소스는 누락된 원격 값을 건너뛴다.

LocalStack 테스트:

- 시크릿을 만들고 JSON으로 로딩해 Environment에 바인딩한다.
- 한 경로 아래에 SSM 파라미터를 만들고 재귀적으로 로딩해 Environment에 바인딩한다.
- LocalStack 시크릿/파라미터를 갱신한 뒤 `refreshInterval`이 지나면 갱신 가능 프로퍼티
  소스가 새 값을 읽는지 검증한다.

## README 갱신

`README.md`와 `README.ko.md`를 모두 다음과 같이 갱신한다.

- `aws-spring-boot`에 필요한 `software.amazon.awssdk:secretsmanager`와
  `software.amazon.awssdk:ssm` 런타임 의존성을 추가한다.
- Secrets Manager와 Parameter Store 구성 코드 조각을 추가한다.
- `@ConfigurationProperties`와 조합 값 애너테이션이 원격에서 로딩한 값을 사용하는 예제를 보여 준다.

## 수용 기준

- Secrets Manager와 SSM SDK를 `compileOnly`로 두고 `aws-spring-boot`가 컴파일된다.
- Environment 후처리기가 `META-INF/spring.factories`에 등록된다.
- 소스를 구성하지 않으면 기본적으로 AWS 조회가 발생하지 않는다.
- `refresh-interval`이 구성한 소스를 지연 재로딩하고, 재로딩에 실패하면 이전 값을 유지한다.
- `@SecretsValue`와 `@ParameterStoreValue`가 일반 Spring 자리표시자를 해석한다.
- 공개 API KDoc은 영어다.
- 대상 이슈 #6 테스트가 통과한다.
- `./gradlew :aws-spring-boot:test`가 통과한다.
