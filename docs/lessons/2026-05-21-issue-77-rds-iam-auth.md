# Issue #77 RDS IAM 인증 토큰 제공자

날짜: 2026-05-21
저장소: `bluetape4k-aws`
이슈: #77

## 배경

`aws-exposed`에는 Spring Boot 및 Ktor Exposed 어댑터보다 먼저 공통 RDS IAM
데이터베이스 인증 경로가 필요했다. AWS 토큰 만료 후 새 물리 연결이 열릴 수 있으므로
Hikari는 시작 시점의 정적 비밀번호를 RDS IAM에 안전하게 재사용할 수 없다.

## 결정

`AwsDatabaseConnectionProperties`에 명시적인 `STATIC_PASSWORD` 및 `RDS_IAM` 모드를
추가하고 정적 비밀번호 동작은 변경하지 않는다. RDS IAM 모드는 물리 연결을 생성할 때
새 제공자 토큰을 요청하는 Hikari `DataSource` 래퍼를 거친다.

토큰 제공자는 주입 가능한 `Clock`, `ReentrantLock` 단일 실행 갱신, AWS 15분 토큰 TTL
이전의 갱신 경계를 사용한다. AWS SDK RDS는 `compileOnly`를 통해 선택 사항으로
유지한다. RDS IAM 모드를 사용하는 소비자는 런타임에
`software.amazon.awssdk:rds`를 추가한다.

## 결과

- RDS IAM token request/generator/provider 계약을 추가했다.
- AWS SDK Java v2 `RdsUtilities` 구현을 추가했다.
- 갱신 시점을 고려하는 토큰 캐시와 Hikari `DataSource` 통합을 추가했다.
- 유효성 검사, 요청 매핑, 토큰 재사용/재생성, 동시 갱신 병합, 실패 래핑, JDBC 연결
  열기를 위한 단위 테스트를 추가했다.
- 영문/한글 모듈 README와 CHANGELOG를 갱신했다.

## 검증

- `./gradlew :bluetape4k-aws-exposed:compileKotlin :bluetape4k-aws-exposed:compileTestKotlin --no-configuration-cache`
- `./gradlew :bluetape4k-aws-exposed:cleanTest :bluetape4k-aws-exposed:test :bluetape4k-aws-exposed:koverXmlReport :bluetape4k-aws-exposed:koverVerify --no-build-cache --no-configuration-cache`
  - 테스트 12개 통과
- `git diff --check`
- `:bluetape4k-aws-exposed:detekt`를 시도했지만 모듈에 `detekt` 작업이 없었다.
- 코드 검토에 Claude Code Opus 조언자를 시도했지만 로컬 Claude가 사용량 크레딧
  차단 오류를 반환했다.

## 향후 보호 장치

수명이 짧은 자격 증명을 JDBC/Hikari에 추가할 때 `HikariConfig.password`에 넣지 않는다.
물리 연결을 여는 경계에서 자격 증명을 얻고 결정적 시계와 동시성 테스트로 갱신 동작을
검증한다.
