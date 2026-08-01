# 이슈 #75 Spring Exposed 자동 구성 계획

날짜: 2026-05-21
저장소: `bluetape4k-aws`
브랜치: `feat/issue-75-spring-exposed-autoconfig`
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/75

## 범위

`:bluetape4k-aws-spring-boot` 안에 `:bluetape4k-aws-exposed`용 Spring Boot 어댑터를 구현한다.

## 작업

1. 빌드 의존성
   - `compileOnly(project(":bluetape4k-aws-exposed"))`를 추가한다.
   - H2 기반 자동 구성 테스트에 필요한 테스트 의존성을 추가한다.

2. Spring API
   - `io.bluetape4k.aws.spring.exposed` 아래에 `AwsExposedProperties`를 추가한다.
   - 해석기/팩토리/레지스트리 빈을 위한 `AwsExposedAutoConfiguration`을 추가한다.
   - 클래스 경로에 있다는 이유만으로 애플리케이션 시작이 실패하지 않도록
     `bluetape4k.aws.exposed.default-database.url`로 레지스트리 생성을 보호한다.
   - Spring 로컬 연결 DTO를 바인딩하고, 프레임워크 중립 프로퍼티를 만들기 전에 비밀번호를
     `AwsSecretString`으로 변환한다.
   - 기본 핸들, `DataSource`, Exposed `Database` 별칭을 위한
     `AwsExposedDefaultDatabaseAutoConfiguration`을 추가한다.
   - 두 자동 구성 단계를 `AutoConfiguration.imports`에 등록한다.

3. 테스트
   - `ApplicationContextRunner`를 사용하는 `AwsExposedAutoConfigurationTest`를 추가한다.
   - 명시적 H2 프로퍼티, 비활성 프로퍼티, 클래스 경로 백오프, 기본 URL 누락 시 무동작,
     해석기/팩토리/레지스트리/사용자 기본 빈 백오프, 이름 지정 데이터베이스 바인딩,
     시크릿 기반 프로퍼티 소스 바인딩, Exposed 트랜잭션 사용을 다룬다.

4. 문서
   - `aws-spring-boot/README.md`를 갱신한다.
   - `aws-spring-boot/README.ko.md`를 갱신한다.
   - `bluetape4k-aws-exposed` 의존성과 `bluetape4k.aws.exposed` 프로퍼티 접두사를 설명한다.

5. 검증
   - `:bluetape4k-aws-spring-boot` 대상 컴파일과 테스트를 실행한다.
   - `git diff --check`를 실행한다.
   - 현재 세션 코드 검토와 Claude CLI 자문 검토를 가능한 경우 실행하고, 자문 공백을 기록한다.

6. 전달
   - `docs/lessons/2026-05-21-issue-75-spring-exposed-autoconfig.md`를 추가한다.
   - Lore 트레일러를 포함해 커밋한다.
   - 브랜치를 푸시하고 `debop`에게 할당한 PR을 만든다. 존재하는 경우 `aws-spring-boot`,
     `spring-boot`, `exposed`, `database` 레이블을 붙인다.
   - PR CI를 확인하고 병합 전에 멈춘다.

## 위험

- Spring 구성 바인딩이 `AwsSecretString`을 직접 대상으로 삼으면 안 된다. Spring 프로퍼티
  모델을 바인딩 가능하게 유지하고 바인딩 후 공통 모델로 변환한다.
- 레지스트리 생성은 Spring 빈 초기화에서 suspend 팩토리를 호출한다. 범위가 엄격히 제한된
  `runBlocking(Dispatchers.IO)` 경계를 사용하고 장기 실행 루프에서는 취소 문제를 분리한다.
- 기본 `DataSource` 빈 별칭이 풀을 중복으로 닫으면 안 되며, 레지스트리가 수명 주기를 소유한다.

## 3-R 단계 검토 기록

Claude Code Opus 자문: 2-R 단계 시도에서 로컬 사용 크레딧 소진을 보고했으므로 별도로
다시 실행하지 않았다. 아티팩트: `.omx/artifacts/claude-issue-75-spec-review-20260521.md`.

| 우선순위 | 영역 | 발견 사항 | 필요한 계획 편집 |
|---|---|---|---|
| P1 | 시작 | 기본 DB URL이 없을 때 클래스 경로만으로 시작이 실패하는 상황을 계획에서 막지 않았다. | 레지스트리 URL 보호 절차와 URL 누락 시 무동작 테스트 작업을 추가했다. |
| P2 | 바인딩 | 테스트하지 않으면 `AwsSecretString` 값 클래스 바인딩이 조용히 실패할 수 있다. | Spring 로컬 DTO를 사용하고 `AwsSecretString`으로 변환하며, 마스킹과 값 공개 동작을 테스트한다. 2026-05-22에 대체됨: 이제 `AwsSecretString`은 일반 직렬화 가능 클래스이므로 Java 역직렬화가 `readResolve`를 통해 검증을 다시 실행할 수 있다. |
| P2 | 수명 주기 | 핸들에서 파생된 `DataSource`/`Database` 별칭 빈이 풀 종료를 소유하면 안 된다. | 레지스트리를 수명 주기 소유자로 유지하고 종료 메서드가 없는 별칭을 사용한다. |

수렴 결과: URL 보호 절차와 구성 없음 테스트를 추가한 뒤 P0 = 0, P1 = 0이다.
