# 이슈 #269 RDS IAM core helper 설계

작성일: 2026-06-30
저장소: `bluetape4k-aws`
이슈: #269
브랜치: `feat/aws-rds-iam-core`

## 문제

`bluetape4k-aws-exposed`는 이미 JDBC connection 생성을 위한 Amazon RDS IAM authentication token을 생성하지만 token request, generator, redaction-safe failure 계약은 Exposed module 내부에 있다. 루트 README와 service coverage chart가 이제 RDS IAM을 AWS 기능으로 나열하므로 Java SDK v2 consumer는 Exposed, HikariCP, JDBC data source 생성에 의존하지 않고 framework-neutral RDS IAM token helper를 사용할 수 있어야 한다.

이슈 #269는 RDS IAM token-generation 경계를 core AWS module로 승격하면서 `aws-exposed`가 JDBC integration, refresh-aware password provider, Hikari/DriverManager 동작을 계속 담당하게 한다.

## 현재 근거

- live GitHub 이슈 #269는 `debop`에게 할당되고 milestone은 `0.5.0`이며 framework-neutral RDS IAM helper API, redaction-safe request/exception handling, test, `aws-exposed` 재사용, README service coverage 갱신을 요청한다.
- 이전 이슈 #77과 PR #163은 다음 항목으로 `aws-exposed`에 RDS IAM 지원을 추가했다.
  `AwsRdsIamAuthenticationProperties`, `AwsRdsIamAuthTokenRequest`,
  `AwsRdsIamAuthTokenGenerator`, `AwsSdkRdsIamAuthTokenGenerator`,
  `AwsRdsIamAuthTokenException`, `AwsDatabasePasswordProvider`, and
  `RdsIamRefreshingDataSource`.
- 기존 Exposed 구현은 hostname, port, username, region과 함께 AWS SDK Java v2 `RdsUtilities.generateAuthenticationToken(...)`을 호출한다.
- local AWS SDK Java v2 artifact `software.amazon.awssdk:rds:2.46.17`은 `RdsUtilities.generateAuthenticationToken(...)`이 `GenerateAuthenticationTokenRequest`를 받고 해당 request가 hostname, port, username, region, credentials-provider field를 제공함을 확인해 준다.
- `aws-java`는 AWS service module을 `compileOnly`로 선언하고 consumer는 runtime에 사용하는 service SDK를 추가한다. 현재 `aws-java`에는 RDS dependency가 없다.
- `aws-kotlin`에는 RDS IAM source helper가 없고 repo catalog에도 AWS Kotlin SDK RDS alias가 없다. 따라서 향후 AWS Kotlin RDS API가 추가되기 전까지 Java SDK 기반으로 구현해야 한다.
- CodeGraph가 worktree의 RDS IAM symbol을 resolve하지 못했으므로 source inspection, GNO 결과, local Gradle catalog, local SDK bytecode inspection이 현재 evidence 경로다.

## 제약 조건

- framework-neutral token 생성을 `:bluetape4k-aws-java`에 유지한다.
- JDBC password refresh, Hikari, DriverManager, Exposed `Database` 생성을 `:bluetape4k-aws-exposed`에 유지한다.
- 여기서 이슈 #295를 구현하지 않는다. `bluetape4k-jdbc` 추출은 별도 upstream/design 작업으로 남긴다.
- catalog와 SDK surface에서 native RDS IAM API를 사용할 수 있음이 입증되지 않는 한 새 AWS Kotlin SDK RDS dependency를 추가하지 않는다.
- `compileOnly` service SDK policy를 유지한다. `aws-java`는 `software.amazon.awssdk:rds`를 대상으로 compile하고 RDS IAM을 사용하는 consumer가 runtime에 이를 추가한다.
- token redaction을 유지한다. raw token string은 명시적인 caller/JDBC 경계에서만 드러낼 수 있다.
- public API KDoc을 영문으로 유지한다.
- `README.md`와 `README.ko.md`를 source-equivalent하게 유지한다.
- service coverage chart가 바뀌면 SVG와 PNG를 함께 갱신하고 rendered visual validation을 실행한다.

## 설계 선택지

### 선택지 A: 기존 Exposed type을 `aws-java`로 이동

`AwsRdsIamAuthTokenRequest`, `AwsRdsIamAuthTokenGenerator`, `AwsSdkRdsIamAuthTokenGenerator`, `AwsRdsIamAuthTokenException`을 `io.bluetape4k.aws.exposed`에서 `io.bluetape4k.aws.rds`로 옮기고 `aws-exposed` import를 갱신한다.

public `aws-exposed` type을 갑자기 제거하거나 이름을 바꾸므로 직접적인 구현 형태로 기각한다. compatibility alias 또는 wrapper를 유지할 때만 허용할 수 있다.

### 선택지 B: `String`을 반환하는 core generator 추가

raw token string을 반환하고 downstream module이 wrapping하거나 redact하게 하는 `aws-java` helper를 추가한다.

기각한다. `String`을 주요 API로 반환하면 #269에서 명시적으로 보존하려는 redaction 계약이 약해지고 실수로 diagnostic leakage가 발생하기 쉬워진다.

### 선택지 C: core redacted token API 추가 및 `aws-exposed` adaptation

선택한다. `aws-java`에 redacted token value, request model, generator interface, AWS SDK Java v2 generator, redaction-safe exception을 포함한 framework-neutral `io.bluetape4k.aws.rds` package를 추가한다. 그다음 `aws-exposed`의 기본 generator가 token 생성을 core generator에 위임하도록 갱신하면서 기존 JDBC-facing `AwsSecretString`, refresh-aware provider API, 가능한 source-facing public name을 유지한다.

기존 Exposed user-facing JDBC 동작을 보존하고 raw token string을 core API로 사용하지 않으며 non-Exposed Java SDK v2 consumer에게 직접 사용할 RDS IAM helper를 제공한다.

## API 형태

패키지: `io.bluetape4k.aws.rds`

- `AwsRdsIamAuthToken`
  - redacted serializable 값 object.
  - `reveal(): String`은 명시적인 caller 경계에서만 token을 제공한다.
  - `toString()`은 항상 redacted marker를 반환한다.
  - equality는 JVM byte-array 비교가 허용하는 범위에서 constant-time style로 raw value를 비교한다.
- `awsRdsIamAuthTokenOf(value: String)`
  - nonblank validation이 있는 편의 factory.
- `AwsRdsIamAuthTokenRequest`
  - `region`, `hostname`, `port`, `username`을 갖는 serializable request 형태.
  - nonblank region/hostname/username과 `port in 1..65535`를 validation한다.
- `AwsRdsIamAuthTokenGenerator`
  - `AwsRdsIamAuthToken`을 반환하는 blocking `fun interface`.
  - KDoc은 token signing에서 credential을 resolve할 수 있고 caller가 실행 위치를 선택한다고 문서화한다.
- `AwsSdkRdsIamAuthTokenGenerator`
  - `RdsUtilities` 기반 AWS SDK Java v2 구현.
  - 기본 constructor는 `DefaultCredentialsProvider`로 `RdsUtilities`를 구성한다.
  - caller-managed `RdsUtilities`를 받는 constructor는 test와 custom lifecycle에 계속 제공한다.
  - token value나 credential을 포함하지 않고 runtime failure를 `AwsRdsIamAuthTokenException`으로 감싼다.
- `AwsRdsIamAuthTokenException`
  - repo-standard AWS exception base인 `AwsBluetapeException`을 확장한다.
  - message에는 token 또는 credential data가 아니라 endpoint host/port context를 포함한다.

패키지: `io.bluetape4k.aws.exposed`

- `AwsRdsIamAuthenticationProperties`, `AwsDatabasePasswordProvider`, `AwsDatabasePasswordProviders`, `RdsIamRefreshingDataSource`를 `aws-exposed`에 유지한다.
- type alias에 JVM 또는 Kotlin overload ambiguity가 없음이 구현에서 입증되지 않는 한 기존 Exposed public generator/request/exception name을 compatibility adapter로 유지한다.
- `aws-exposed`의 `AwsSdkRdsIamAuthTokenGenerator`가 core `io.bluetape4k.aws.rds.AwsSdkRdsIamAuthTokenGenerator`에 위임하고 `AwsRdsIamAuthToken.reveal()`을 `AwsSecretString`으로 adapt하도록 갱신한다.
- 기존 Kotlin caller의 모호한 lambda overload를 피하도록 `AwsDatabasePasswordProviders.rdsIam(...)`가 Exposed generator interface를 받게 유지한다.
- lambda 호출 위치를 모호하게 만들지 않을 때만 core generator용 overload 또는 helper를 추가한다. 그렇지 않으면 기본 Exposed SDK generator가 재사용 지점이며 명세 검토에서 해당 compatibility 제약을 기록한다.

## 동작

- core token generation은 caller가 제공한 정확한 RDS endpoint hostname과 port에 서명한다.
- core token generation은 JDBC connection 생성, token caching, Hikari pool configuration, refresh scheduling을 소유하지 않는다.
- `aws-exposed`는 physical JDBC connection의 token refresh 시점을 결정하는 유일한 module로 유지된다.
- runtime에 `software.amazon.awssdk:rds`가 없으면 consumer에게 RDS SDK module 추가를 알리는 redaction-safe message로 실패한다.
- generator failure는 original cause를 보존하면서 message에서 raw token, username password, credential secret material을 제외한다.
- 기본 generator는 AWS default credentials provider resolution을 사용할 수 있다. test는 fake generator 또는 caller-supplied `RdsUtilities`를 사용하며 production AWS에 연결하지 않는다.

## 문서

- 루트 `README.md`와 `README.ko.md` module table에는 `bluetape4k-aws-java`가 Java SDK 기반 RDS IAM token helper를 포함한다고 명시해야 한다.
- `bluetape4k-aws-java` 설치 snippet에는 RDS IAM 사용자를 위한 선택형 `software.amazon.awssdk:rds`를 포함해야 한다.
- 구현을 추가하지 않는 한 `bluetape4k-aws-kotlin` 문서에서 native AWS Kotlin RDS IAM facade를 제공한다고 주장하면 안 된다.
- `aws-exposed/README.md`와 `aws-exposed/README.ko.md`는 shared core generator를 가리키면서 JDBC refresh 동작, endpoint exactness, SSL/TLS caller 책임, runtime RDS SDK dependency를 계속 문서화해야 한다.
- 루트 service coverage chart는 새 `aws-java` RDS IAM 지원을 반영해야 한다. visual matrix가 현재 RDS IAM을 Exposed 전용으로 표시하거나 Java module을 누락하면 SVG와 PNG를 함께 갱신한다.

## 인수 기준

- `:bluetape4k-aws-java`가 영문 KDoc과 framework-neutral RDS IAM token helper API를 제공한다.
- `:bluetape4k-aws-java`가 `libs.aws2.rds`를 `compileOnly`로 선언하고 runtime service dependency policy를 바꾸지 않으면서 test에서 사용한다.
- core test가 request validation, request-to-AWS-SDK mapping, redacted token `toString()`, factory validation, token leakage 없는 failure wrapping을 다룬다.
- `:bluetape4k-aws-exposed`가 compatibility adapter를 통해 core SDK 기반 generator를 재사용하고 legacy Exposed generator signature를 유지하는 이유를 기록한다.
- refresh-boundary, single-flight, failure-redaction, JDBC connection-opening 동작을 포함한 기존 Exposed RDS IAM test가 계속 통과한다.
- 루트 README, 한국어 README, Exposed README locale pair가 새 module 경계를 일관되게 설명한다.
- 변경한 diagram/chart asset은 일치하는 SVG/PNG output과 rendered inspection evidence를 갖는다.
- local 검증에는 production AWS 호출이 필요하지 않다.

## 범위 제외

- DriverManager/DataSource token refresh 동작을 `bluetape4k-jdbc`로 추출(#295).
- Spring Boot 또는 Ktor RDS IAM auto-configuration.
- native AWS Kotlin SDK RDS facade 제외.
- Secrets Manager 및 Parameter Store wrapper(#268).
- Kinesis auto-configuration 제외(#270).
- SES/v2 및 SNS Ktor integration(#271).

## 단계 2-R 검토 기록

### Codex 명세 검토

| 우선순위 | 발견 사항 | 결정 |
|---|---|---|
| P0 | 기존 `aws-exposed` public RDS IAM type을 제거하거나 옮기면 이미 0.4.x를 사용하는 사용자에게 피할 수 있는 API breakage가 발생한다. | 수용. Exposed public name을 compatibility wrapper/adapter로 유지하고 SDK 기반 구현을 새 core helper에 위임한다. |
| P1 | core 및 Exposed `rdsIam(..., tokenGenerator)` overload를 모두 추가하면 Kotlin lambda 호출 위치가 모호해질 수 있다. | 수용. 구현 중 모호하지 않은 helper 형태가 입증되지 않는 한 provider factory에 기존 Exposed generator signature를 유지한다. |
| P1 | 명세에서 exception base를 구현에 따라 달라지게 두면 안 된다. | 수용. core `AwsRdsIamAuthTokenException`은 `AwsBluetapeException`을 확장하고 Exposed compatibility exception은 redaction을 보존하면서 이를 확장하거나 감쌀 수 있다. |
| P1 | README/chart 작업이 visual redraw churn으로 확장될 수 있다. | 수용. chart 갱신은 #269가 요구하는 service coverage 의미로 제한하며 변경한 asset에만 SVG/PNG parity와 rendered validation을 적용한다. |

수용한 수정 후 수렴 상태: P0 = 0, P1 = 0.
