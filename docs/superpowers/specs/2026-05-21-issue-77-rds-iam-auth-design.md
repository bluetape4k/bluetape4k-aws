# 이슈 #77 RDS IAM 인증 설계

날짜: 2026-05-21
저장소: `bluetape4k-aws`
브랜치: `feat/issue-77-rds-iam-auth`

## 문제

이슈 #75와 #76에는 #74의 Exposed 데이터베이스 기반에서 Amazon RDS IAM 데이터베이스 인증을
사용할 공유 방법이 필요하다. RDS IAM 인증은 수명이 짧은 서명 토큰을 JDBC 비밀번호로 사용하므로,
시작 시 정적 풀 비밀번호 하나를 설정하고 새 연결에도 계속 유효하다고 가정할 수 없다.

## 현재 근거

- #77은 #74에 의존하며 #75와 #76에서 사용한다.
- #74는 `bluetape4k-aws-exposed`, `AwsDatabaseConnectionProperties`, `AwsSecretString`,
  `AwsJdbcDataSourceFactory`, Hikari 기반 Exposed 데이터베이스 생성을 도입했다.
- AWS RDS 문서에 따르면 IAM 인증 토큰은 비밀번호 대신 사용하며 15분 동안 유효하다.
- AWS SDK for Java 2.x는 `RdsUtilities.generateAuthenticationToken(...)`을 제공한다.
  요청에는 호스트 이름, 포트, 사용자 이름, 리전, 자격 증명 공급자 재정의 필드가 들어간다.
- AWS SDK `RdsUtilities` 문서에 따르면 토큰을 생성할 때 네트워크 호출을 하지 않는다.
- AWS RDS Java 문서는 토큰을 생성할 때 DB 인스턴스 엔드포인트 대신 사용자 정의 Route 53 DNS 레코드를 사용하지 말라고 경고한다.

주요 출처:

- https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.IAMDBAuth.Connecting.html
- https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/java_rds_code_examples.html
- https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/rds/RdsUtilities.html
- https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/rds/model/GenerateAuthenticationTokenRequest.html

## 제약 조건

- 공유 계약을 `aws-exposed`에 유지하며, Spring Boot 및 Ktor 어댑터가 나중에 프레임워크/클라이언트 수명 주기를 연결한다.
- 테스트에서 프로덕션 AWS에 접속하지 않는다.
- `AwsSecretString`을 통해 토큰을 마스킹한다.
- 독자적인 연결 풀을 만들지 않는다.
- AWS 토큰 TTL 의미를 넘어 장기간 토큰을 캐시하지 않는다.
- 공개 KDoc은 영어로 유지한다.
- 지역화 README가 있는 경우 README 갱신을 다국어로 유지한다.
- 기존 AWS SDK 의존성 정책을 따른다. 해당 서비스 통합을 사용하는 소비자에게만 서비스 SDK 모듈이 필요하다.
- RDS IAM 토큰 서명은 구성된 RDS 엔드포인트 호스트 이름을 그대로 사용한다. 토큰 생성에 사용자 정의 DNS 별칭을 지원하지 않는다.
- JDBC 드라이버는 비밀번호를 `String`으로 요구하므로 JDBC 경계에서 토큰을 공개하는 것은 허용한
  JVM 힙 노출 절충안이다. 구현은 연결을 여는 호출 범위 밖에서 토큰을 기록하거나 공개된 토큰 문자열을 저장하면 안 된다.

## 설계 선택지

### 선택지 A: `AwsDatabaseSettingsResolver`에서 토큰을 한 번 생성

거부한다. #74 해석기 형태는 보존하지만 토큰 새로 고침 요구 사항을 충족하지 못한다.
Hikari가 토큰 만료 후 새 물리 연결을 만들면서 오래된 비밀번호를 재사용할 수 있다.

### 선택지 B: Hikari를 사용자 정의 풀로 교체

거부한다. 이슈 #77은 독자적인 풀 구현을 명시적으로 제외하며 Hikari는 이미 `DataSource` 래핑을 지원한다.

### 선택지 C: 새로 고침 인식 비밀번호 공급자와 DataSource 경로 추가

선택한다. 명시적 인증 모드 프로퍼티, 작은 토큰 요청/생성기 계약, 캐시하는 RDS IAM 비밀번호
공급자, RDS IAM 모드에서 Hikari 팩토리가 사용하는 내부 `DataSource`를 추가한다. Hikari는
계속 풀 역할을 하며, 물리 연결은 열릴 때 공급자에게 현재 토큰을 요청한다.

## API 형태

패키지: `io.bluetape4k.aws.exposed`

- `AwsDatabaseAuthenticationMode`
  - `STATIC_PASSWORD`
  - `RDS_IAM`
- `AwsRdsIamAuthenticationProperties`
  - `region: String`
  - `hostname: String`
  - `port: Int`
  - `username: String?`
  - `tokenTtl: Duration = 15 minutes`
  - `refreshBeforeExpiry: Duration = 2 minutes`
- `AwsDatabaseConnectionProperties`
  - `authenticationMode`를 추가한다.
  - 선택적 `rdsIam`을 추가한다.
  - 정적 비밀번호 모드에서는 기존 `password`를 유지한다.
- `AwsRdsIamAuthTokenRequest`
  - 테스트와 생성기가 사용하는 직렬화 가능 요청 형태
- `AwsRdsIamAuthTokenGenerator`
  - `AwsSecretString`을 반환하는 작은 블로킹 인터페이스
- `AwsSdkRdsIamAuthTokenGenerator`
  - `RdsUtilities` 기반 AWS SDK Java v2 구현
- `AwsDatabasePasswordProvider`
  - 물리 연결 생성 경계에서 사용하는 블로킹 스레드 안전 비밀번호 공급자
- `AwsDatabasePasswordProviders`
  - 정적 비밀번호 및 RDS IAM 모드용 팩토리 도우미
- `AwsRdsIamAuthTokenException`
  - 토큰 생성 실패를 위한 마스킹 안전 래퍼

검증:

- `region`과 `hostname`은 공백이 아니어야 한다.
- `port`는 `1..65535` 범위여야 한다.
- 실제 사용자 이름은 공백이 아니어야 한다.
- `tokenTtl`은 양수이며 15분을 넘지 않아야 한다.
- `refreshBeforeExpiry`는 양수이고 `tokenTtl`보다 작아야 한다.
- `RDS_IAM` 모드에 정적 `password`가 있으면 안 된다.
- `STATIC_PASSWORD` 모드에 `rdsIam` 설정이 있으면 안 된다.

## 동작

- `STATIC_PASSWORD`는 기존 Hikari 동작을 유지한다.
- `RDS_IAM`에는 리전, 호스트 이름, 포트, `rdsIam.username` 또는
  `AwsDatabaseConnectionProperties.username`에서 얻은 실제 사용자 이름이 필요하다.
- RDS IAM 비밀번호 공급자는 생성한 토큰을 `issuedAt + tokenTtl - refreshBeforeExpiry`까지 캐시한 뒤 새 토큰을 생성한다.
- 공급자는 새로 고침의 단일 실행을 보장한다. 새로 고침 경계를 동시에 넘는 호출자는 생성기 호출 하나를 관찰하고 동일한 갱신 토큰을 받는다.
- 공급자는 반환 시점에 새로 고침 구간 밖에 있는 토큰만 반환한다.
- 공급자는 JDBC 연결 생성 시 `AwsSecretString.reveal()`을 통하는 경우를 제외하고 원본 토큰을 기록하거나 노출하지 않는다.
- Hikari 팩토리는 RDS IAM 모드에 내부 갱신 `DataSource`를 사용한다. Hikari는 해당 래퍼를
  `dataSource`로 설정하고 `HikariConfig.username`이나 `HikariConfig.password`를 설정하지 않는다.
  래퍼는 구성된 사용자 이름과 새로 받은 토큰으로 각 물리 JDBC 연결을 연 뒤
  `DriverManager.getConnection(...)` 또는 동등한 드라이버 `DataSource` 경로에 위임한다.

## 실패 모드 및 완화책

- 새 연결의 오래된 토큰: 연결 생성 시 공급자를 통해 생성하고 만료 전에 새로 고친다.
- 시크릿 유출: 토큰을 `AwsSecretString`으로 유지하고 마스킹 테스트를 추가한다.
- 모호한 서명 엔드포인트: 명시적 RDS 호스트 이름/포트를 요구하고 사용자 정의 DNS에서 추론하지 않는다.
- 토큰 생성 실패: 마스킹 안전 메시지와 원래 원인을 가진 `AwsRdsIamAuthTokenException`을 노출한다.
- 누락되거나 잘못된 RDS SDK 구성: 리전 파싱 및 선택적 RDS SDK 클래스 사용 가능 여부를 포함해
  가능한 경우 SDK 기반 생성기 설정을 즉시 검증한다. RDS IAM 모드에는 런타임 클래스 경로의 AWS SDK RDS 모듈이 필요함을 문서화한다.
- 블로킹 AWS 자격 증명 해석: 토큰 생성 자체는 로컬 서명이지만 자격 증명 공급자 해석은 블로킹될 수 있다. 프레임워크 어댑터가 #75/#76에서 자격 증명 공급자 수명 주기를 선택할 수 있다.
- 드라이버 SSL/TLS 요구 사항: 호출자가 대상 엔진의 RDS IAM SSL 요구 사항에 따라 JDBC URL 또는 데이터 소스 프로퍼티를 구성해야 함을 문서화한다.

## 수용 기준

- 정적 비밀번호와 RDS IAM 토큰 모드를 명시한다.
- 요청 형태 테스트가 리전, 호스트, 포트, 사용자 이름 매핑을 검증한다.
- 새로 고침 동작 테스트가 만료 전 토큰 재사용과 새로 고침 경계 후 재생성을 검증한다.
- 동시 새로 고침 테스트가 단일 실행 생성을 검증한다.
- 실패 테스트가 토큰 값을 유출하지 않고 생성기 오류를 `AwsRdsIamAuthTokenException`으로 전파함을 검증한다.
- 구성 테스트가 잘못된 호스트, 포트, 사용자 이름, TTL, 혼합된 정적/RDS IAM 설정이 `IllegalArgumentException`으로 실패함을 검증한다.
- 마스킹 테스트가 `AwsSecretString.toString()`과 공급자 문자열 출력에 원본 토큰 문자가 없음을 검증한다.
- `aws-exposed` 테스트가 실제 AWS 없이 컴파일되고 통과한다.
- README가 필요한 `rds-db:connect` 권한, 정확한 RDS 엔드포인트 요구 사항, SSL/TLS 호출자 구성, IAM 모드의 런타임 RDS SDK 의존성을 문서화한다.

## 2-R 단계 검토 기록

### Claude Code Opus 자문

아티팩트: `.omx/artifacts/claude-issue-77-spec-review-20260521-213335.md`
모델: `${CLAUDE_ADVISOR_MODEL:-claude-opus-4-7}`

| 우선순위 | 발견 사항 | 결정 | 후속 조치 |
|---|---|---|---|
| P0 | Hikari 정적 비밀번호 경로는 IAM 토큰을 새로 고칠 수 없다. | 수용 | 설계에서 갱신 내부 `DataSource`를 요구하고 RDS IAM 모드의 Hikari 사용자 이름/비밀번호를 설정하지 않는다. |
| P0 | 토큰 캐시 범위와 동시 새로 고침 설명이 부족했다. | 수용 | 설계에서 스레드 안전 단일 실행 새로 고침을 요구하고 새로 고침 구간 안의 토큰을 반환하지 않는다. |
| P0 | 호스트 이름/엔드포인트 계약과 검증이 너무 약했다. | 수용 | 공백이 아닌 엔드포인트 호스트 이름, 정확한 RDS 엔드포인트 서명, 사용자 정의 DNS 별칭 금지를 요구한다. |
| P0 | 토큰 생성 오류와 SDK 설정 실패에 마스킹 안전 계약이 없었다. | 수용 | `AwsRdsIamAuthTokenException`과 가능한 즉시 검증을 추가한다. |
| P1 | JVM `String`으로 토큰을 공개하는 데 명시적 보안 절충이 필요했다. | 수용 | 공개 범위를 JDBC 경계로 제한하고 마스킹 수용 테스트를 추가한다. |
| P1 | 새로 고침 경계에 결정적 시계와 단일 실행 동작이 필요하다. | 수용 | 결정적 테스트와 단일 실행 새로 고침 동작을 요구한다. |
| P1 | 정적 비밀번호와 RDS IAM 구성을 혼합해도 조용히 성공할 수 있었다. | 수용 | 잘못된 모드/프로퍼티 조합을 거부한다. |
| P1 | `tokenTtl`이 AWS의 15분 토큰 수명을 넘으면 안 된다. | 수용 | `tokenTtl <= 15 minutes`를 검증하고 기본 `refreshBeforeExpiry = 2 minutes`를 사용한다. |

### Codex 다각도 통합 검토

| 우선순위 | 영역 | 발견 사항 | 결정 |
|---|---|---|---|
| P0 | 보안/DB 수명 주기 | 풀 수준 정적 비밀번호는 RDS IAM 만료 의미를 위반한다. | 갱신 `DataSource` 요구 사항으로 해결했다. |
| P0 | API 검증 | 잘못된 엔드포인트, 포트, 사용자 이름, TTL, 혼합 인증 모드 설정이 늦게 실패한다. | 명시적 검증 규칙으로 해결했다. |
| P1 | 테스트 가능성 | 새로 고침 동작과 실패 경로에 실제 AWS가 필요하면 안 된다. | 가짜 생성기와 결정적 시계 수용 기준으로 해결했다. |
| P2 | 문서 | 사용자에게 엔드포인트, SSL/TLS, 권한, 런타임 의존성 안내가 필요하다. | README 작업에서 수용했다. |

수렴 결과: 편집을 수용한 뒤 P0 = 0, P1 = 0이다.
