# 이슈 #76 Ktor Exposed 플러그인 설계

날짜: 2026-05-21
저장소: `bluetape4k-aws`
브랜치: `feat/issue-76-ktor-exposed-plugin`
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/76

## 문제

이슈 #82에는 AWS 기반 Exposed 데이터베이스의 Ktor 예제가 필요하지만, `aws-ktor`는 아직
#74의 공유 `:bluetape4k-aws-exposed` 기반을 위한 Ktor 수명 주기 어댑터를 제공하지 않는다.
애플리케이션에는 `AwsExposedDatabaseRegistry`를 만들어 애플리케이션 속성에 저장하고,
애플리케이션/호출 도우미를 제공하며, 코루틴 친화적 트랜잭션 도우미로 Exposed JDBC 작업을
실행하는 플러그인이 필요하다.

## 현재 근거

- #74는 `AwsDatabaseProperties`, `AwsExposedDatabaseFactory`, `AwsExposedDatabaseRegistry`,
  마스킹된 `AwsSecretString`, 소스 설명자, 교체 가능한 `AwsDatabaseSettingsResolver`를 추가했다.
- #75는 Spring Boot에서 프레임워크 어댑터 패턴을 입증했다. 어댑터 로컬 구성을 바인딩하고,
  공유 모델로 변환하며, 닫을 수 있는 레지스트리를 만들고, 트랜잭션 의미를 소유하지 않은 채
  기본/이름 지정 핸들을 노출한다.
- `aws-ktor`의 기존 Ktor 플러그인은 런타임 객체를 애플리케이션 속성에 저장하고 수명 주기 작업에
  `MonitoringEvent(ApplicationStarted/ApplicationStopping)`을 사용한다.
- Ktor 3.5 공식 문서는 사용자 정의 플러그인용 `createApplicationPlugin`과 수명 주기 정리용
  `MonitoringEvent(ApplicationStarted/ApplicationStopped)`을 안내한다.
- `bluetape4k-exposed` JDBC 코루틴 테스트는 코루틴 친화적 JDBC 작업에 Exposed
  `newSuspendedTransaction(context = Dispatchers.IO, db = database)`을 사용한다.
- Context7 문서 조회를 시도했으나 할당량 소진으로 차단되었다. 외부 API 근거는 공식 Ktor 문서와 로컬 소스다.

## 제약 조건

- Spring Boot 의존성을 추가하지 않는다.
- AWS SDK 서비스 클라이언트를 선택 사항으로 유지하고 테스트에서 실제 AWS 자격 증명을 사용하지 않는다.
- `:bluetape4k-aws-exposed`의 공유 데이터베이스 모델이나 Hikari/Exposed 팩토리를 중복하지 않는다.
- `aws-ktor`는 선택적 Ktor 서버, AWS 서비스, Exposed 통합 의존성에 `compileOnly`를 사용하며,
  애플리케이션은 설치하는 기능의 런타임 아티팩트를 추가한다.
- 생성된 진단 정보와 로그에서 시크릿 값이 마스킹된 상태를 유지해야 한다.
- Ktor 모니터링 이벤트는 동기식이므로 suspend 시작/종료 작업에는 기존 Ktor 플러그인과 같은
  범위 제한 `runBlocking(Dispatchers.IO)` 브리지가 필요하다.
- 공개 API와 KDoc은 영어여야 한다.
- README 변경 시 `aws-ktor/README.md`와 `aws-ktor/README.ko.md`를 모두 갱신해야 한다.
- 테스트는 bluetape4k assertion을 사용하고 JUnit/kotlin.test assertion을 피해야 한다.

## 설계 선택지

### 선택지 A: Exposed 설정을 라우트 도우미에 직접 배치

거부한다. 라우트 로컬 팩토리 생성은 라우트별 또는 요청별 연결 풀을 너무 쉽게 만들게 하며,
Hikari 풀을 닫을 명확한 수명 주기 소유자를 Ktor에 제공하지 못한다.

### 선택지 B: Secrets Manager 및 Parameter Store용 완전한 Ktor 구성 소스 로더 추가

이 작업 단위에서는 거부한다. 기반에 이미 소스 설명자와 해석기 훅이 있다. Ktor에 일반 구성
소스 추상화가 생기기 전에 완전한 로더를 추가하면 Spring Boot의 프로퍼티 소스 체계를 중복한다.
Ktor 플러그인은 `AwsDatabaseSettingsResolver`를 받아들이고 소스 설명자를 보존해야 하며,
애플리케이션은 Secrets Manager, Parameter Store, 테스트 대역, 향후 공유 로더를 연결할 수 있다.

### 선택지 C: `AwsExposedPlugin`과 런타임/DSL/도우미 계층 추가

선택한다. 플러그인이 Ktor 수명 주기 경계를 소유하고, 공유 기반이 데이터베이스 생성을 소유하며,
Exposed가 트랜잭션 동작을 소유한다. 어댑터를 작고 테스트 가능하게 유지하면서도 직접 프로퍼티,
이름 지정 데이터베이스, 사용자 정의 해석기, suspend 라우트 트랜잭션을 지원한다.

## 공개 API 형태

패키지: `io.bluetape4k.aws.ktor.exposed`

- `AwsExposedPlugin`
  - Ktor `ApplicationPlugin<AwsExposedPluginConfig>`
  - `AwsExposedKtorRuntime`을 만들고 시작한다.
  - 런타임을 `AwsExposedKtorRuntimeKey` 아래에 저장한다.
  - `ApplicationStopping`에서 레지스트리를 닫는다.
- `AwsExposedPluginConfig`
  - Ktor 친화적 DSL로 `AwsDatabaseProperties`를 만든다.
  - `databaseProperties`, `settingsResolver`, `databaseFactory`, `transactionContext: CoroutineContext`를 받는다.
  - `startTimeout: Duration = 30.seconds`와 `stopTimeout: Duration = 10.seconds`를 노출한다.
  - `defaultDatabase { ... }`와 `database("name") { ... }`을 지원한다.
- `AwsExposedConnectionConfig`
  - 일반 비밀번호 문자열을 `AwsSecretString`으로 변환하는 Ktor 로컬 가변 빌더다.
  - `secretSource(...)`와 `parameterSource(...)`를 통해 소스 설명자를 노출한다.
- `AwsExposedKtorRuntime`
  - `AwsExposedDatabaseFactory.createRegistry(...)`를 호출해 시작한다.
  - 원자적 상태 머신으로 수명 주기를 관리한다.
  - `registry`, `handle(name)`, `database(name)`, `transaction(name, context) { ... }`을 노출한다.
  - 레지스트리를 한 번 닫아 중지한다.
- 도우미:
  - `Application.awsExposed()`
  - `ApplicationCall.awsExposed()`
  - `Application.awsExposedHandle(name)`
  - `ApplicationCall.awsExposedHandle(name)`
  - `Application.awsExposedTransaction(name, context) { ... }`
  - `ApplicationCall.awsExposedTransaction(name, context) { ... }`

## 의존성

`aws-ktor/build.gradle.kts`에 다음 항목을 추가한다.

- `compileOnly(project(":bluetape4k-aws-exposed"))`
- `testImplementation(project(":bluetape4k-aws-exposed"))`
- `testImplementation(libs.h2.v2)`

어떤 Ktor Exposed 코드 경로도 Spring Boot를 요구하면 안 된다. 해당 아티팩트를 추가한 호출자는
`:bluetape4k-aws-exposed` API 의존성을 통해 Exposed JDBC와 Hikari를 받는다. Exposed를 사용하지
않는 `aws-ktor` 소비자가 통합을 우발적으로 상속하지 않도록 README 예제는 선택적
`bluetape4k-aws-exposed` 의존성을 계속 명시한다.

## 구성 해석

플러그인은 상호 배타적인 두 가지 구성 경로를 지원한다.

1. 직접 모델 경로: `databaseProperties(AwsDatabaseProperties(...))`.
2. DSL 경로: `defaultDatabase { ... }`와 `database("name") { ... }`.

한 플러그인 설치에서 두 경로를 함께 사용하면 잘못된 구성으로 간주해 `IllegalArgumentException`을
던진다. 이 방식은 암묵적 우선순위와 병합으로 인한 예상 밖 동작을 피한다. 이름 지정 데이터베이스의 중복 등록도 허용하지 않는다.

`AwsExposedConnectionConfig` 필드는 `AwsDatabaseConnectionProperties`와 대응한다:
`url`, `driverClassName`, `username`, `password`, `pool`, `dataSourceProperties`, `metadata`,
`secretSource`, `parameterSource`, `authenticationMode`, `rdsIam`. 빈 URL, 이름 지정
데이터베이스 이름, 사용자 이름, 드라이버 이름, 소스 ID,
비밀번호 문자열은 레지스트리를 만들기 전에 호출자 입력 예외로 거부한다.

`secretSource(sourceId) { ... }`는
`AwsDatabaseConfigSource(SECRETS_MANAGER, sourceId, prefix, optional)`.
`parameterSource(sourceId) { ... }`는
`AwsDatabaseConfigSource(PARAMETER_STORE, sourceId, prefix, optional)`.
Ktor 어댑터는 이 설명자를 보존하고 실제 값 해석을 구성된 `AwsDatabaseSettingsResolver`에 위임한다.

RDS IAM 인증은 공유 기반 관심사로 유지한다. Ktor 플러그인은 `authenticationMode`와 `rdsIam`
모델 값을 기반에 전달하지만 별도의 IAM 동작을 추가하지 않는다.

## 수명 주기

1. 플러그인 설치 시 런타임을 만들어 애플리케이션 속성에 넣는다.
2. `ApplicationStarted`가 `runBlocking(Dispatchers.IO)`을 통해 `runtime.start()`를 호출하고 `startTimeout`을 적용한다.
3. `runtime.start()`는 레지스트리를 정확히 하나 만든다. 수명 주기 상태는
   `NEW -> STARTING -> STARTED -> STOPPING -> STOPPED`이며, `STARTED` 뒤 반복 시작은
   아무 작업도 하지 않고 `STOPPED` 뒤 시작은 명확히 실패한다.
4. 라우트는 애플리케이션 또는 호출 도우미에서 런타임에 접근한다.
5. `ApplicationStopping`이 `runBlocking(Dispatchers.IO)`을 통해 `runtime.stop()`을 호출하고 `stopTimeout`을 적용한다.
6. `runtime.stop()`은 성공적으로 시작하기 전에도 안전하며, 레지스트리를 한 번 닫고 런타임 상태를 지운다.

## 실패 모드 및 완화책

- 플러그인 설치 누락: 원본 Ktor 속성 오류를 노출하지 않고 도우미가 명확한 메시지와 함께 `IllegalStateException`을 던진다.
- 시작 전 라우트 사용: 런타임이 명확한 "not started" 메시지와 함께 `IllegalStateException`을 던진다.
- 시작 제한 시간 초과: 플러그인 시작이 제한 시간 메시지와 함께 실패하고 일부만 만든 레지스트리를 저장하지 않는다.
- 종료 제한 시간 초과: 플러그인이 경고를 기록하고 상태를 지워 반복 종료 시 동일한 레지스트리를 다시 닫지 않는다.
- 부분 데이터베이스 생성 실패: 실패 시 이미 만든 핸들을 닫는 `AwsExposedDatabaseFactory.createRegistry`에 위임한다.
- 시크릿 유출: 비밀번호 DSL이 `AwsSecretString`으로 변환하며, 렌더링된 구성/런타임 진단과 로그에 시크릿 센티널 원문이 없음을 테스트한다.
- 이벤트 루프에서 블로킹 JDBC 작업: 트랜잭션 도우미는 기본적으로 `Dispatchers.IO`를 사용하고 고급 호출자를 위한 재정의 지점을 제공한다.
- 종료 누수: 중지 시 레지스트리를 한 번 닫고, 테스트 레지스트리로 종료 동작을 입증한다.
- 지원하지 않는 원격 구성 로더: 소스 설명자는 프로퍼티에 남고 사용자 정의
  `AwsDatabaseSettingsResolver`가 이를 해석할 수 있다. 이 작업 단위에 Ktor AWS 소스 로더가 포함되지 않음을 README에 명시한다.

## 수용 기준

- Ktor 플러그인 수명 주기 테스트가 설치, 시작, 도우미 접근, 중지 동작을 입증한다.
- H2 라우트 테스트가 `ApplicationCall`에서 suspend 트랜잭션 사용을 입증한다.
- 애플리케이션/호출 도우미를 통한 이름 지정 데이터베이스 조회를 테스트한다.
- 사용자 정의 해석기 테스트가 시크릿을 기록하지 않고 소스 설명자를 해석할 수 있음을 입증한다.
- 플러그인 설치 전 및 시작 전 접근이 명확한 `IllegalStateException` 메시지와 함께 실패한다.
- 시작/중지 멱등성과 한 번만 닫는 동작을 종료 횟수를 세는 테스트 대역으로 검증한다.
- 제어 가능한 대역으로 시작 및 중지 제한 시간 동작을 테스트한다.
- 트랜잭션 예외 전파와 롤백을 테스트한다.
- README와 README.ko가 의존성, 직접 H2 방식 구성, 이름 지정 데이터베이스 조회, suspend 트랜잭션 사용법을 보여 준다.
- 대상 `:bluetape4k-aws-ktor` 컴파일/테스트가 통과한다.
- 현재 세션 검토와 Claude 자문 공백/아티팩트를 기록한다.

## 2-R 단계 검토 기록

Claude Code Opus 자문 아티팩트:
`.omx/artifacts/claude-issue-76-spec-review-20260521.md`.

| 우선순위 | 발견 사항 | 결정 |
|---|---|---|
| P0 | 범위가 제한되지 않은 수명 주기 `runBlocking`이 Ktor 시작/종료를 멈추게 할 수 있다. | 수용: `startTimeout`과 `stopTimeout`을 추가하고 수명 주기 이벤트에서 적용한다. |
| P1 | `databaseProperties`와 DSL의 우선순위를 정의하지 않았다. | 수용: 직접 모델과 DSL 구성을 함께 사용하면 잘못된 구성으로 간주한다. |
| P1 | `aws-ktor -> aws-exposed` 의존성 전략이 암묵적이었다. | 수용: 의존성을 나열하고 README에 선택적 런타임 아티팩트를 명시한다. |
| P1 | 멱등성에 구체적인 상태 머신이 필요했다. | 수용: 원자적 수명 주기 상태를 설계에 포함한다. |
| P1 | 런타임/도우미 실패 경로와 시크릿 마스킹 테스트 설명이 부족했다. | 수용: 수용 기준에서 미설치/미시작, 제한 시간, 한 번만 종료, 롤백, 마스킹 테스트를 요구한다. |

현재 Codex 통합 검토: 편집을 수용한 뒤 P0 = 0, P1 = 0이다.
