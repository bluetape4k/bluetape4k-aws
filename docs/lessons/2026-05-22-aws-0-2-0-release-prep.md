# AWS 0.2.0 릴리스 준비

## 배경

AWS 0.2.0 마일스톤에서는 Exposed 데이터베이스 기반, Spring Boot 및 Ktor
어댑터, RDS IAM 인증, Exposed 예제, SES 발신자 관련 이슈를 마무리했다. 이 릴리스는
`bluetape4k-projects` 1.9.0과 `bluetape4k-exposed` 1.9.0에 의존한다.

## 결정

`baseVersion=0.2.0`, `snapshotVersion=`, 그리고 `bluetape4k-bom:1.9.0` 및
`bluetape4k-exposed-bom:1.9.0`으로 고정한 버전 카탈로그로 릴리스 태그를
준비한다.

## 결과

생성한 게시 메타데이터는 변경할 수 없는 `io.github.bluetape4k.aws` 0.2.0
아티팩트를 게시하고 변경할 수 없는 상위 BOM을 import한다. 이제 WIP 대기열은
담당자가 지정된 미해결 이슈가 없음을 보여 준다.

## 검증

- `./gradlew properties --no-configuration-cache --no-daemon --quiet`
- `./gradlew clean generatePomFileForBluetapeAwsPublication --no-daemon --no-configuration-cache --no-build-cache`
- 생성한 POM에서 `SNAPSHOT|examples|demo|benchmark`를 검사했다.
- 생성한 POM에서 `bluetape4k-bom:1.9.0`, `bluetape4k-exposed-bom:1.9.0`,
  아티팩트 버전 `0.2.0`을 검사했다.
- `actionlint .github/workflows/release.yml .github/workflows/publish-snapshot.yml .github/workflows/nightly-tests.yml .github/workflows/ci.yml`
- `./gradlew build -x test -x koverVerify publishToMavenLocal --no-daemon --no-configuration-cache --no-build-cache`

## 향후 보호 장치

Exposed 1.9.0이 Maven Central에 표시될 때까지 AWS 0.2.0 태그를 만들지 않는다.
하위 릴리스 트레인은 상위 BOM의 스냅샷을 사용해서는 안 된다. 이
게이트가 열렸을 때 GraalVM 메타데이터 작업이 Gradle의 exclusive-lock 보호 장치에
걸리면 `--parallel` 없이 컴파일 및 게시 검증을 다시 실행한다.
