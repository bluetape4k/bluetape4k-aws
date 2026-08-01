# Issue 82 Exposed 예제 모듈

## 배경

Issue #82에서는 공통 `bluetape4k-aws-exposed` 데이터베이스 기반을 사용하는 Spring
Boot 및 Ktor 예제 모듈을 추가했다.

## 결정

- Spring MVC repository 호출은 `transaction(database)` 안에서 수행한다.
- Ktor repository 호출은 `call.awsExposedTransaction` 안에서 수행한다.
- 컨테이너를 직접 생성하는 대신 `PostgreSQLServer.Launcher.postgres`를 사용한다.
- 새 예제 모듈을 `settings.gradle.kts`, CI path filter/job, Nightly에 추가한다.

## 결과

예제 모듈 두 개를 추가했다.

- `:aws-spring-boot-exposed-examples`
- `:aws-ktor-exposed-examples`

Spring AOT/test와 Ktor 테스트가 재사용하는 공통 PostgreSQL singleton을 두고 경합할
수 있으므로 이제 Nightly 예제 테스트 명령은 `--max-workers=1`을 사용한다.

## 검증

- `./gradlew projects`
- `./gradlew :aws-spring-boot-exposed-examples:test :aws-ktor-exposed-examples:test --no-daemon --continue --max-workers=1`
- `./gradlew build -x test --parallel`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- Claude Code CLI 코드 검토: `.omx/artifacts/claude-issue-82-exposed-examples-code-review-rereview-small-20260522093611.md`, `Gate: PASS P0=0 P1=0`

## 향후 보호 장치

Nightly step에 새 모듈을 추가할 때는 dynamic discovery step만 유일한 커버리지
수단으로 사용하기로 결정하고 이를 문서화한 경우가 아니라면, 이전에 명시한 모듈
task를 유지한다.
