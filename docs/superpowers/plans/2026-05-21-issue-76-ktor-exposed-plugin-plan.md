# 이슈 #76 Ktor Exposed 플러그인 계획

날짜: 2026-05-21
설계: `docs/superpowers/specs/2026-05-21-issue-76-ktor-exposed-plugin-design.md`

## 작업 1: 선택적 의존성 연결

복잡도: 중간

- `aws-ktor`에 `compileOnly(project(":bluetape4k-aws-exposed"))`를 추가한다.
- `testImplementation(project(":bluetape4k-aws-exposed"))`를 추가한다.
- 라우트 수준 JDBC 테스트를 위한 H2 테스트 런타임 의존성을 추가한다.
- 기존 Ktor 서버 및 AWS 서비스 클라이언트 의존성 정책에 맞춰 README에서 이 의존성을 선택 사항으로 유지한다.
- 검증: `./gradlew :bluetape4k-aws-ktor:compileKotlin`.

## 작업 2: Ktor Exposed 런타임 및 플러그인 구현

복잡도: 높음

`$bluetape4k-patterns`, `kotlin-coroutines-skill`, Exposed 규칙을 적용한다.

- `io.bluetape4k.aws.ktor.exposed` 패키지를 추가한다.
- `AwsExposedPluginConfig`를 구현한다.
- `AwsExposedConnectionConfig`와 풀/소스 도우미 빌더를 구현한다.
- `AwsExposedKtorRuntime`을 구현한다.
- `AwsExposedPlugin`과 `AwsExposedKtorRuntimeKey`를 구현한다.
- `MonitoringEvent(ApplicationStarted/ApplicationStopping)`을 사용한다.
- `startTimeout`과 `stopTimeout`을 추가하고 수명 주기 `runBlocking(Dispatchers.IO)` 브리지에 모두 적용한다.
- 두 수명 주기 브리지에 `runBlocking`을 허용하는 이유를 설명하는 주석을 추가한다.
- 레지스트리 전환에 명시적인 원자적 수명 주기 상태를 사용한다.
- 안전한 경우 `start()`와 `stop()`을 멱등하게 만들고, 중지 후 시작은 명확히 실패시킨다.
- 직접 `databaseProperties(...)`와 DSL 데이터베이스 빌더를 함께 사용하지 못하게 한다.
- 이름 지정 데이터베이스의 중복 등록을 거부한다.
- 현실적인 사용 예제와 함께 공개 KDoc을 영어로 유지한다.
- 검증: `./gradlew :bluetape4k-aws-ktor:compileKotlin`.

## 작업 3: 애플리케이션 및 호출 도우미 구현

복잡도: 중간

- `Application.awsExposed()`를 추가한다.
- `ApplicationCall.awsExposed()`를 추가한다.
- 기본 및 이름 지정 데이터베이스용 핸들/데이터베이스 도우미 함수를 추가한다.
- Exposed JDBC `newSuspendedTransaction` 기반 suspend 트랜잭션 도우미를 추가한다.
- 기본 트랜잭션 컨텍스트는 `Dispatchers.IO`여야 하며 호출자가 재정의할 수 있다.
- 플러그인을 설치하지 않았거나 시작하지 않았을 때 발생하는 `IllegalStateException`을 공개 도우미 KDoc에 설명한다.
- 더 이상 권장하지 않는 Exposed import를 사용하지 않는다.
- 검증: 컴파일 및 라우트 수준 테스트.

## 작업 4: 테스트 추가

복잡도: 높음

- H2 구성을 사용한 플러그인 수명 주기 테스트를 추가한다.
- `testApplication`을 사용하는 라우트 수준 suspend 트랜잭션 테스트를 추가한다.
- 이름 지정 데이터베이스 조회 테스트를 추가한다.
- 렌더링된 구성/런타임 진단 또는 캡처한 플러그인 로그에 센티널 비밀번호가 나타나지 않음을
  검증해 시크릿 마스킹을 입증하는 사용자 정의 해석기/소스 설명자 테스트를 추가한다.
- 명확한 `IllegalStateException` 메시지를 검증하는 설치 전 및 시작 전 접근 테스트를 추가한다.
- 종료 횟수를 세는 테스트 대역을 사용해 `registry.close()`가 정확히 한 번 호출됨을 입증하는 필수 중지/종료 멱등성 테스트를 추가한다.
- 제어 가능한 대역을 사용해 시작 제한 시간 및 중지 제한 시간 테스트를 추가한다.
- 트랜잭션 예외 전파 및 롤백 테스트를 추가한다.
- 테스트 대역을 작고 읽기 쉽게 유지할 수 있다면 선택적으로 Ktor 경계의 부분 생성 실패 테스트를 추가한다.
- bluetape4k assertion만 사용한다.
- 실제 IO/suspend 데이터베이스 테스트에는 `runSuspendIO`를 사용한다.
- 검증:
  `./gradlew :bluetape4k-aws-ktor:cleanTest :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.exposed.*' --no-build-cache --no-configuration-cache --no-daemon`.

## 작업 5: README 언어 세트 갱신

복잡도: 중간

- `aws-ktor/README.md`를 갱신한다.
- `aws-ktor/README.ko.md`를 갱신한다.
- `bluetape4k-aws-exposed`, Ktor 서버 코어, H2/PostgreSQL JDBC 드라이버 예제,
  Exposed JDBC, Exposed 사용법의 의존성 코드 조각을 포함한다.
- `install(AwsExposedPlugin)`, 이름 지정 데이터베이스 조회, `call.awsExposedTransaction { ... }`을 보여 준다.
- 이 작업 단위의 Ktor 원격 소스 로딩은 해석기 기반임을 명시한다.
- `io.bluetape4k.aws.ktor.exposed` 패키지 경로를 명시한다.
- 검증: 실제 소스 이름을 기준으로 예제를 검색한다.

## 작업 6: 검토, 학습 문서, 커밋, PR

복잡도: 중간

- DB/Exposed 수명 주기에 집중해 최소 Tier 4 + Tier 5 수준으로 현재 세션 코드 검토를 수행한다.
- 설계/계획 및 코드에 대한 Claude Code Opus 자문 검토를 시도하고, 아티팩트를 저장하거나 할당량/사용 불가 공백을 기록한다.
- `docs/lessons/2026-05-21-issue-76-ktor-exposed-plugin.md`를 만든다.
- 검토 게이트를 통과하면 구현 전에 설계/계획을 커밋한다.
- 구현과 학습 문서를 Lore 트레일러와 함께 커밋한다.
- 브랜치를 푸시하고 `debop`에게 할당한 PR을 연다.
- PR 댓글과 공식 검토 항목을 게시한다.
- CI 상태를 확인하며 사용자 요청 없이는 병합하지 않는다.

## 3-R 단계 검토 기록

Claude Code Opus 자문 아티팩트:
`.omx/artifacts/claude-issue-76-plan-review-20260521.md`.

| 우선순위 | 발견 사항 | 결정 |
|---|---|---|
| P1 | 수명 주기 제한 시간 작업이 누락되었다. | 수용: 작업 2에 시작/중지 제한 시간 구현과 검증을 추가한다. |
| P1 | 시작 전 접근 실패 경로를 테스트하지 않았다. | 수용: 작업 4에 설치 전 및 시작 전 테스트를 추가한다. |
| P1 | 시크릿 마스킹 검증 방식을 명시하지 않았다. | 수용: 작업 4에서 진단/로그를 대상으로 센티널 검증을 요구한다. |
| P1 | 한 번만 종료하는 테스트가 선택 사항이었다. | 수용: 작업 4에서 한 번만 종료하는 멱등성을 필수로 만든다. |
| P2 | 도우미 KDoc과 의존성 문서의 표현을 더 명확히 해야 했다. | 수용: 작업 3과 작업 5를 갱신했다. |

현재 Codex 통합 검토: 편집을 수용한 뒤 P0 = 0, P1 = 0이다.

## 검증 명령

```bash
./gradlew :bluetape4k-aws-ktor:compileKotlin
./gradlew :bluetape4k-aws-ktor:cleanTest :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.exposed.*' --no-build-cache --no-configuration-cache --no-daemon
./gradlew :bluetape4k-aws-ktor:test --no-build-cache --no-configuration-cache --no-daemon
./gradlew :bluetape4k-aws-ktor:detekt
git diff --check
```
