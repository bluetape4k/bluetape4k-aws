# Issue #76 Ktor Exposed 플러그인

날짜: 2026-05-21
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/76

## 배경

`aws-ktor`에는 Secrets Manager 또는 Parameter Store 로딩 정책을 중복하지 않으면서
#74의 `bluetape4k-aws-exposed` 기반을 Ktor 수명 주기와 통합하는 기능이 필요했다.

## 결정

기존 `aws-ktor` 모듈 안에 `AwsExposedPlugin`을 추가하고 `bluetape4k-aws-exposed`는
선택적 컴파일 의존성으로 유지한다. 플러그인은 제한된 수명 주기 런타임 하나를
애플리케이션 속성에 저장하고 `ApplicationStarted`에서 공통 레지스트리를 생성하며
`ApplicationStopping`에서 한 번만 닫는다. 핸들, 데이터베이스, 중단 가능한 트랜잭션에
접근하는 애플리케이션/호출 도우미도 제공한다.

## 결과

플러그인은 미리 만든 `AwsDatabaseProperties` 또는 Ktor DSL 중 하나를 받으며 두 설정
방식을 섞으면 거부한다. 해석기 기반 로딩을 위한 AWS 설정 소스 서술자를 보존하고
`AwsSecretString`으로 정적 비밀번호를 가린다. 영문 및 한글 README 예제를
갱신했다.

## 검증

- `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:compileTestKotlin`
- `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.exposed.AwsExposedPluginTest'`
- `git diff --check`
- 로컬 Codex 검토: P0=0, P1=0
- Claude CLI 검토 공백을 `.omx/artifacts/claude-issue-76-code-review-20260521.md`에 기록

## 향후 보호 장치

블로킹 JDBC 리소스를 닫는 Ktor 수명 주기 플러그인에서는 시간제한과 함께
`runInterruptible(Dispatchers.IO)`를 사용하고 중단된 종료 경로를 테스트한다. 공통
레지스트리 종료 코드가 감싼 `InterruptedException`은 종료 경로에서만 예상한 시간제한
신호로 취급하고 일반 종료 실패는 전파한다.
