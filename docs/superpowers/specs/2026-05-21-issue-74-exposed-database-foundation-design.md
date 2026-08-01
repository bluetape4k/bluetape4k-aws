# 이슈 #74 Exposed 데이터베이스 기반 설계

날짜: 2026-05-21
저장소: `bluetape4k-aws`
브랜치: `feat/issue-74-exposed-db-foundation`

## 문제

이슈 #82의 Spring Boot 및 Ktor 예제는 #74의 공유 Exposed 우선 AWS 데이터베이스 기반과
#75 및 #76의 프레임워크 어댑터에 의존하므로 먼저 구현할 수 없다. 이슈 #74는 AWS 기반
데이터베이스 설정, 교체 가능한 시크릿/구성 해석, Exposed `Database` 생성, 기본/이름 지정
데이터베이스 레지스트리 동작을 위한 재사용 가능한 핵심 계약을 제공해야 한다.

## 현재 근거

- `WIP.md`는 데이터베이스 작업 순서를 `#74 -> #75/#76 -> #77 -> #82`로 정한다.
- 이슈 #82는 #74, #75, #76에 명시적으로 의존한다.
- `bluetape4k-exposed`는 `ExposedSpringDataAutoConfiguration`에서 Exposed JDBC
  `Database.connect(dataSource)`를 사용한다.
- `bluetape4k-exposed` 저장소 패턴은 호출자가 AWS 소유 추상화 내부가 아니라 Exposed
  트랜잭션 안에서 저장소 작업을 실행하도록 요구한다.
- 기존 `aws-spring-boot` Secrets Manager 및 Parameter Store 지원은 키/값 구성을 로딩하지만 Spring에 종속된다.
- Context7 공식 문서 조회를 시도했으나 월간 할당량 소진으로 차단되었다. 따라서 서드파티 API
  가정은 로컬 `bluetape4k-exposed` 소스와 의존성 컴파일/테스트 검증을 근거로 삼는다.

## 제약 조건

- AWS 통합이 시크릿/구성 로딩과 향후 RDS IAM 토큰 훅을 소유한다.
- Exposed가 `Database`, 트랜잭션, 저장소, SQL 동작을 소유한다.
- awspring JDBC 호환성, JPA/Hibernate, 프로덕션 RDS 네트워크 테스트를 제공하지 않는다.
- 테스트나 예제에서 실제 AWS 자격 증명을 사용하지 않는다.
- 시크릿을 담은 모델 값이 우발적인 `toString()`을 통해 유출되면 안 된다.
- 공개 API와 KDoc은 영어여야 한다.
- README 변경 시 `README.md`와 `README.ko.md`를 모두 갱신해야 한다.
- 새 모듈 작업은 `settings.gradle.kts`, BOM/배포 범위, CI, Nightly, README 표, Gradle 검증을 갱신해야 한다.

## 설계 선택지

### 선택지 A: `bluetape4k-aws-java`에 기반 추가

거부한다. 기본 AWS Java SDK 래퍼의 모든 소비자에게 Exposed, Hikari, JDBC 데이터베이스
관심사를 추가하게 된다. 의존성과 API 영향 범위가 #74의 요구 범위보다 넓다.

### 선택지 B: Spring Boot 및 Ktor 모듈에 프레임워크별 기반 추가

거부한다. 데이터베이스 프로퍼티, 해석기 계약, 팩토리, 레지스트리, 시크릿 마스킹 로직이
중복된다. 공유 계약이 안정되기 전에 #75와 #76이 서로 달라지게 된다.

### 선택지 C: `bluetape4k-aws-exposed` 추가

선택한다. 범위가 좁은 배포 가능 모듈이 데이터베이스 설정, 해석기 계약, Hikari 기반
DataSource 생성, Exposed `Database` 핸들, 기본/이름 지정 레지스트리를 노출할 수 있다.
Spring Boot 및 Ktor 어댑터는 기본 AWS 모듈을 오염시키지 않고 이 모듈에 의존할 수 있다.

## API 형태

패키지: `io.bluetape4k.aws.exposed`

- `AwsDatabaseProperties`
  - `defaultDatabase: AwsDatabaseConnectionProperties`
  - `namedDatabases: Map<String, AwsDatabaseConnectionProperties>`
- `AwsDatabaseConnectionProperties`
  - `url`, `driverClassName`, `username`, `password`, `pool`, `metadata`
  - 어댑터가 사용할 선택적 `secretSource` 및 `parameterSource` 설명자
- `AwsSecretString`
  - 민감한 값을 감싸고 `toString()`에서 마스킹된 문자열을 반환한다.
  - 연결을 만들 때만 사용할 `reveal()`을 노출한다.
- `AwsDatabasePoolProperties`
  - 최대 풀 크기, 최소 유휴 수, 제한 시간 메타데이터, 선택적 풀 이름
- `AwsDatabaseConfigSource`
  - Secrets Manager 또는 Parameter Store 소스를 위한 저장소 중립 설명자
- `AwsDatabaseSettingsResolver`
  - 팩토리가 DataSource를 만들기 전에 이름 지정 연결 하나를 해석하는 교체 가능한 suspend 계약
- `AwsExposedDatabaseFactory`
  - 해석된 설정을 검증한다.
  - Hikari `DataSource`를 만든다.
  - `Database.connect(dataSource)`로 Exposed JDBC `Database`를 만든다.
  - 닫을 수 있는 `AwsExposedDatabaseHandle`을 반환한다.
- `AwsExposedDatabaseRegistry`
  - 기본 핸들과 이름 지정 핸들을 보관한다.
  - nullable/기본 이름 또는 명시적 이름으로 핸들을 조회한다.
  - 소유한 핸들을 생성 역순으로 닫는다.

## 실패 모드 및 완화책

- 모델 로그를 통한 시크릿 유출: 비밀번호를 `AwsSecretString`으로 표현하고 `toString()` 마스킹을 테스트한다.
- 부분 레지스트리 생성 누수: 앞선 핸들을 만든 뒤 이름 지정 데이터베이스 생성에 실패하면,
  예외를 다시 던지기 전에 이미 만든 핸들을 닫는다.
- 잘못된 풀 설정: Hikari를 초기화하기 전에 풀 크기와 제한 시간 값을 검증한다.
- JDBC 드라이버 누락: 필요에 따라 `Class.forName`으로 `driverClassName`을 로딩하고 명확한
  `IllegalArgumentException`을 노출한다.
- 프레임워크 결합: 이 모듈에서 AWS SDK 클라이언트와 Spring/Ktor 타입을 사용하지 않는다.
  실제 AWS 해석기는 나중에 프레임워크 어댑터에서 제공한다.
- 테스트 오탐: Exposed를 통해 H2 및 PostgreSQL Testcontainers 생성/읽기 트랜잭션 테스트를 실행한다.

## 수용 기준

- 모델이 URL, 드라이버, 사용자 이름, 비밀번호, 풀 메타데이터, 이름 지정 데이터베이스 항목을 지원한다.
- Secrets Manager / Parameter Store 해석을 교체할 수 있으며 시크릿 값을 로그에 기록하지 않는다.
- H2 및 PostgreSQL Testcontainers로 Exposed `Database` 생성을 테스트한다.
- 공개 API가 `bluetape4k-exposed` 저장소 및 트랜잭션 규칙과 일치한다.
- 새 모듈이 등록되고 배포 가능하며 BOM 집계, CI, Nightly 검증에 포함된다.
- 루트 영어/한국어 README가 실제 AWS 자격 증명을 요구하지 않고 모듈과 로컬 검증을 설명한다.

## 2-R 단계 검토 기록

Claude Code Opus 자문: 실행하지 못했다. 로컬 CLI가 현재 사용 크레딧 소진을 보고하며,
Context7 문서 조회도 할당량 소진을 보고한다.

| 우선순위 | 발견 사항 | 결정 |
|---|---|---|
| P1 | 데이터 클래스 비밀번호가 생성된 `toString()`을 통해 유출될 수 있다. | 수용: 비밀번호 값에 `AwsSecretString`을 사용하고 마스킹 테스트를 추가한다. |
| P1 | 레지스트리를 일부만 만든 뒤 실패하면 Hikari 풀이 누수될 수 있다. | 수용: 이후 이름 지정 데이터베이스 생성이 실패하면 팩토리가 이미 만든 핸들을 닫는다. |
| P2 | 실제 AWS 해석기를 추가하면 #74가 프레임워크 중립 범위를 벗어난다. | 수용: #74는 해석기 계약만 제공하고 #75/#76에서 Secrets Manager/Parameter Store 클라이언트를 연결한다. |
| P2 | Context7 및 Claude 자문 공백으로 외부 검토 범위가 줄어든다. | 기록: PR 전에 컴파일/테스트와 로컬 소스 근거가 필요하다. |

수렴 결과: 설계 편집을 수용한 뒤 P0 = 0, P1 = 0이다.

## 완료 조건

- 구현 전에 설계와 계획을 커밋한다.
- `./gradlew projects`에 `:bluetape4k-aws-exposed`가 나타난다.
- `:bluetape4k-aws-exposed` 대상 테스트가 통과한다.
- 워크플로 편집에 대해 `actionlint`가 통과한다.
- 현재 세션의 코드 검토에서 P0/P1이 발견되지 않는다.
- CLI 할당량을 계속 사용할 수 없으면 Claude 검토 공백을 기록한다.
- `docs/lessons/` 아래에 학습 문서를 만든다.
