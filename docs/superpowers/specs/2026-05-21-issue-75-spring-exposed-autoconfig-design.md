# 이슈 #75 Spring Exposed 자동 구성 설계

날짜: 2026-05-21
저장소: `bluetape4k-aws`
브랜치: `feat/issue-75-spring-exposed-autoconfig`
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/75

## 문제

이제 `bluetape4k-aws-exposed`가 #74의 프레임워크 중립 데이터베이스 기반을 제공한다.
Spring Boot 사용자는 Spring 구성 프로퍼티를 바인딩하고 `AwsExposedDatabaseRegistry`를
만들며 기본 Exposed `Database`와 `DataSource`를 노출하는 얇은 어댑터가 여전히 필요하다.
그래야 `bluetape4k-exposed` Spring 저장소/트랜잭션 규칙을 AWS 기반 데이터베이스 설정과 조합할 수 있다.

## 현재 근거

- `docs/superpowers/plans/2026-05-14-awspring-gap-wip-plan.md`는 데이터베이스 작업 순서를
  `#74 -> #75 -> #76 -> #77 -> #82`로 정한다.
- #75는 #74에 의존하며 Spring Boot 4 자동 구성, 기본/이름 지정 데이터베이스,
  명시적/시크릿 기반 구성, 사용자 빈 백오프, README 범위를 요구한다.
- #74는 `:bluetape4k-aws-exposed`에 `AwsDatabaseProperties`, `AwsExposedDatabaseFactory`,
  `AwsExposedDatabaseRegistry`를 추가했다.
- `aws-spring-boot`는 이미 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`를
  통해 자동 구성을 등록한다.
- 기존 Secrets Manager 및 Parameter Store 지원은 임의 접두사 키를 Spring `Environment`에
  로딩하므로 별도의 AWS 클라이언트 경로 없이 `bluetape4k.aws.exposed.*`에 값을 제공할 수 있다.
- Spring Boot 4 공식 문서는 외부 자동 구성을 `AutoConfiguration.imports`에 나열하고
  `@AutoConfiguration`과 클래스 경로/프로퍼티/사용자 빈 백오프용 조건부 애너테이션을 사용하도록 안내한다.
- Context7 조회를 시도했으나 월간 할당량 소진으로 차단되었다. 따라서 Spring Boot 가정은
  공식 `docs.spring.io` 문서와 컴파일/테스트 검증을 근거로 삼는다.

## 제약 조건

- AWS/Exposed 데이터베이스 생성은 `:bluetape4k-aws-exposed`에 유지한다. Spring 모듈은
  두 번째 데이터베이스 계층이 아니라 어댑터여야 한다.
- awspring JDBC 호환성, JPA/Hibernate, Flyway/Liquibase 마이그레이션, 프로덕션 AWS 통합 테스트를 제공하지 않는다.
- 로그나 생성된 진단 정보에 시크릿을 남기지 않으며, 비밀번호 값은 `AwsSecretString` 안에 둔다.
- Spring 바인딩은 Spring 로컬 바인딩 가능 DTO를 사용한 뒤 비밀번호를 `AwsSecretString`으로
  변환한다. 프레임워크 중립 값 객체를 직접 바인딩하지 않는다.
- compileOnly 타입이 빈 시그니처에 나타나면 `@ConditionalOnClass(name = [...])`로 자동 구성을 보호한다.
- 모든 자동 구성 단계 클래스에 `@ConditionalOnProperty`를 적용한다.
- `@ConditionalOnBean`이 필요하면 빈 순서 단계를 분리한다.
- 공개 API KDoc, PR, 커밋 텍스트는 영어여야 한다.
- README를 갱신할 때 `README.md`와 `README.ko.md`를 모두 다룬다.

## 선택한 설계

`:bluetape4k-aws-spring-boot` 안에 Spring Boot Exposed 지원을 추가한다.

- `AwsExposedProperties`
  - `@ConfigurationProperties(prefix = "bluetape4k.aws.exposed")`
  - 필드: `enabled`, `defaultDatabase`, `namedDatabases`
  - `AwsDatabaseProperties`로 변환한다.
- `AwsExposedAutoConfiguration`
  - 없으면 기본 `AwsDatabaseSettingsResolver`를 만든다.
  - 없으면 기본 `AwsExposedDatabaseFactory`를 만든다.
  - `bluetape4k.aws.exposed.default-database.url`이 구성되고 기존 빈이 없으면 닫을 수 있는
    `AwsExposedDatabaseRegistry`를 만든다.
- `AwsExposedDefaultDatabaseAutoConfiguration`
  - 레지스트리 생성 뒤 실행되는 별도의 순서 지정 단계다.
  - 사용자 빈이 아직 없으면 기본 `AwsExposedDatabaseHandle`, `DataSource`, Exposed `Database` 빈을 노출한다.
  - 레지스트리가 풀 수명 주기를 소유하도록 핸들에서 파생한 별칭에 `destroyMethod = ""`를 사용한다.

기존 Environment 로더를 통해 원격 구성을 재사용한다.

```yaml
bluetape4k:
  aws:
    secrets-manager:
      sources:
        - secret-id: app/database
          prefix: bluetape4k.aws.exposed.default-database
    exposed:
      default-database:
        driver-class-name: org.postgresql.Driver
        url: ${database.url}
        username: ${database.username}
        password: ${database.password}
```

Parameter Store의 소스 접두사가 `bluetape4k.aws.exposed.default-database`를 가리키게 하면 동일한 구조를 사용할 수 있다.

## 거부한 선택지

- Spring 전용 데이터베이스 팩토리 생성. #74의 수명 주기, 검증, 레지스트리, RDS IAM 훅을 중복하므로 거부한다.
- 레지스트리 빈 생성 중 Secrets Manager 또는 Parameter Store 직접 조회. `aws-spring-boot`가
  이미 Environment 로딩과 새로 고침 동작을 소유하며, AWS 클라이언트 경로를 중복하면 수명
  주기 및 로깅 위험이 커지므로 거부한다.
- 이름 지정 `Database` 빈의 동적 노출. 동적 빈 등록이 자동 구성 계약을 복잡하게 하므로
  #75에서는 거부한다. 이름 지정 핸들은 `AwsExposedDatabaseRegistry`를 통해 계속 사용할 수 있다.

## 수용 기준

- `ApplicationContextRunner`가 명시적 H2 프로퍼티로 레지스트리, 팩토리, 해석기, 기본
  `DataSource`, 기본 Exposed `Database` 빈이 등록됨을 입증한다.
- 자동 구성이 비활성화되거나 사용자 레지스트리, 팩토리, 해석기, 기본 `DataSource`, 기본
  `Database` 빈이 있을 때 백오프함을 테스트로 입증한다.
- 모듈이 클래스 경로에 있더라도 기본 데이터베이스 URL이 없으면 레지스트리/데이터베이스 별칭을 만들지 않음을 테스트로 입증한다.
- 기존 Secrets Manager / Parameter Store 로더가 게시할 키와 동일한 Spring 프로퍼티 소스를
  주입해 시크릿 기반 구성을 테스트로 입증한다.
- 이름 지정 데이터베이스 프로퍼티가 레지스트리에 바인딩됨을 테스트로 입증한다.
- 트랜잭션 테스트가 기본 Exposed `Database`를 Exposed JDBC 트랜잭션과 함께 사용할 수 있음을 입증한다.
- 영어/한국어 README가 Spring Boot 사용법과 원격 구성 접두사 연결을 문서화한다.

## 2-R 단계 검토 기록

Claude Code Opus 자문을 시도했으나 로컬 CLI가 사용 크레딧 소진을 보고했다.
아티팩트: `.omx/artifacts/claude-issue-75-spec-review-20260521.md`.

| 우선순위 | 발견 사항 | 결정 |
|---|---|---|
| P1 | `aws-exposed`가 클래스 경로에 있지만 기본 데이터베이스 URL을 구성하지 않으면 레지스트리 생성으로 시작이 실패한다. | 수용: 레지스트리 빈은 `bluetape4k.aws.exposed.default-database.url`을 요구하고, 테스트가 URL 누락 시 무동작 경로를 다뤄야 한다. |
| P2 | Binder의 값 클래스 지원 여부에 따라 `AwsSecretString` 바인딩에 Spring 변환기가 필요할 수 있다. | `AwsSecretString`으로 변환하는 Spring 로컬 바인딩 가능 DTO로 해결하며, 테스트가 마스킹과 값 공개 동작을 다룬다. 2026-05-22에 대체됨: 이제 `AwsSecretString`은 일반 직렬화 가능 클래스이므로 Java 역직렬화가 `readResolve`를 통해 검증을 다시 실행할 수 있다. |
| P2 | 이름 지정 `Database` 빈을 동적으로 제공하면 편리하지만 빈 등록 복잡도가 커진다. | #75에서는 거부: 이름 지정 핸들은 `AwsExposedDatabaseRegistry`를 통해 계속 사용할 수 있다. |

수렴 결과: 기본 URL 활성화 조건을 설계에 추가한 뒤 P0 = 0, P1 = 0이다.
