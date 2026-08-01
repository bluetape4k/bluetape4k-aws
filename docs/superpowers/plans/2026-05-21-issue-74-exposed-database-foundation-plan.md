# 이슈 #74 Exposed 데이터베이스 기반 계획

날짜: 2026-05-21
설계: `docs/superpowers/specs/2026-05-21-issue-74-exposed-database-foundation-design.md`

## 작업 1: 새 모듈 등록

복잡도: 중간

- `aws-exposed/`에 매핑되는 `:bluetape4k-aws-exposed`를 추가한다.
- `gradle/libs.versions.toml`에 Exposed, HikariCP, H2, PostgreSQL, Testcontainers PostgreSQL 별칭을 추가한다.
- 배포 라이브러리 의존성을 포함한 `aws-exposed/build.gradle.kts`를 추가한다.
- `README.md`, `README.ko.md`, `src/test/resources/junit-platform.properties`, `src/test/resources/logback-test.xml`을 추가한다.
- 검증: `./gradlew projects`.

## 작업 2: 기반 공개 API 구현

복잡도: 높음

`$bluetape4k-patterns`와 `$ecc-kotlin-exposed`를 적용한다.

- 직렬화 가능한 공개 모델 클래스를 구현한다.
  - `AwsDatabaseProperties`
  - `AwsDatabaseConnectionProperties`
  - `AwsDatabasePoolProperties`
  - `AwsDatabaseConfigSource`
  - `AwsDatabaseConfigSourceType`
  - `AwsSecretString`
- 공개 KDoc은 영어로 유지한다.
- 팩토리 경계에서 이름, URL, 드라이버 이름이 공백이 아닌지 검증한다.
- 민감한 값이 `toString()`에서 마스킹되도록 보장한다.
- 검증: `./gradlew :bluetape4k-aws-exposed:compileKotlin`.

## 작업 3: 해석기, 팩토리, 레지스트리 구현

복잡도: 높음

`$bluetape4k-patterns`, `$ecc-kotlin-exposed`, 코루틴 취소 규칙을 적용한다.

- 교체 가능한 suspend 해석기로 `AwsDatabaseSettingsResolver`를 구현한다.
- `NoopAwsDatabaseSettingsResolver`를 구현한다.
- `AwsExposedDatabaseFactory`를 구현한다.
- 닫을 수 있는 `AwsExposedDatabaseHandle`을 구현한다.
- 닫을 수 있는 `AwsExposedDatabaseRegistry`를 구현한다.
- 레지스트리를 일부만 만든 뒤 실패하면 앞서 만든 핸들을 닫도록 보장한다.
- 이 모듈에서 AWS SDK, Spring, Ktor 타입을 사용하지 않는다.
- 검증: 대상 단위 테스트와 `compileKotlin`.

## 작업 4: H2 및 PostgreSQL 테스트 추가

복잡도: 중간

`$ecc-kotlin-testing`과 `$ecc-kotlin-exposed`를 적용한다.

- 모델 검증 및 시크릿 마스킹 테스트를 추가한다.
- H2 Exposed 생성/읽기 트랜잭션 테스트를 추가한다.
- PostgreSQL Testcontainers Exposed 생성/읽기 트랜잭션 테스트를 추가한다.
- 레지스트리 조회 및 부분 실패 정리 테스트를 추가한다.
- bluetape4k assertion과 `@TestInstance(PER_CLASS)`를 사용한다.
- Testcontainers 명령은 순차 실행해야 한다.
- 검증: `./gradlew :bluetape4k-aws-exposed:cleanTest :bluetape4k-aws-exposed:test --no-build-cache`.

## 작업 5: 문서 및 CI/Nightly 갱신

복잡도: 중간

- 루트 `README.md`와 `README.ko.md`의 모듈 표 및 로컬 테스트 명령을 갱신한다.
- 모듈 README 쌍에 의존성과 실제 AWS가 필요 없는 예제를 추가한다.
- CI 및 Nightly 모듈 작업에 `:bluetape4k-aws-exposed:test`를 추가한다.
- 기존 워크플로가 모듈별 아티팩트를 요구하는 곳에 Kover 보고서/업로드 범위를 추가한다.
- 워크플로 편집 후 `actionlint`를 실행한다.
- 검증: README/소스 검색, `actionlint`, 대상 Gradle 테스트.

## 작업 6: 검토, 학습 문서, 커밋, PR

복잡도: 중간

- 6단계 기준으로 diff를 현재 세션에서 코드 검토한다.
- Claude Code CLI 검토를 시도하고 계속 막히면 할당량/사용 불가 공백을 기록한다.
- `docs/lessons/2026-05-21-issue-74-exposed-database-foundation.md`를 만든다.
- Lore 트레일러를 포함해 커밋한다.
- 브랜치를 푸시하고 `debop`에게 할당한 PR을 연다.
- 병합 요청 전에 PR 생성 후 검토와 CI 게이트를 통과해야 한다.

## 3-R 단계 검토 기록

Claude Code Opus 자문: 실행하지 못했다. 현재 로컬 CLI 할당량을 소진했다.

| 우선순위 | 발견 사항 | 결정 |
|---|---|---|
| P1 | 새 모듈 계획에 CI, Nightly, BOM/배포, README 쌍, `./gradlew projects`가 포함되어야 한다. | 수용: 작업 1과 작업 5가 이 검사를 다룬다. |
| P1 | 테스트가 시크릿 마스킹과 부분 레지스트리 정리를 모두 입증해야 한다. | 수용: 작업 4에 두 사례를 모두 포함한다. |
| P2 | 이슈 문구에 따라 실제 AWS 해석기 구현이 필요하다고 해석할 수 있다. | #74 구현에서는 거부: 이슈는 교체 가능한 계약을 요구하며, 프레임워크별 AWS 클라이언트는 #75/#76 범위다. |
| P2 | Context7 할당량 때문에 공식 문서를 사용할 수 없다. | 기록: 로컬 소스와 컴파일/테스트 근거를 사용한다. |

수렴 결과: 계획 편집을 수용한 뒤 P0 = 0, P1 = 0이다.

## 검증 명령

```bash
./gradlew projects
./gradlew :bluetape4k-aws-exposed:compileKotlin
./gradlew :bluetape4k-aws-exposed:cleanTest :bluetape4k-aws-exposed:test --no-build-cache
actionlint
```
