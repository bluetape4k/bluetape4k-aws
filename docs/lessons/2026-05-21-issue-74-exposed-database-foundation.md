# Issue #74 Exposed 데이터베이스 기반

날짜: 2026-05-21
저장소: `bluetape4k-aws`

## 배경

Issue #82는 #74, #75, #76에 막혀 있었다. 따라서 첫 단계는 Spring Boot/Ktor 예제가
아니라 프레임워크 중립적인 Exposed 데이터베이스 기반을 마련하는 것이었다.

## 결정

`bluetape4k-aws-java`에 Exposed 및 Hikari 의존성을 추가하는 대신 게시 가능한 새
`bluetape4k-aws-exposed` 모듈을 추가한다. AWS 클라이언트 해석은
`AwsDatabaseSettingsResolver`를 통해 교체할 수 있게 유지하고, 프레임워크별 Secrets
Manager 및 Parameter Store 연결은 #75/#76에서 담당한다.

## 결과

이제 모듈은 데이터베이스 속성, 가려진 비밀값 문자열, Hikari 데이터 소스 팩터리,
Exposed 데이터베이스 팩터리, 기본/이름별 레지스트리를 제공한다. 테스트에서는 비밀값
가림, H2 생성/읽기, PostgreSQL Testcontainers 생성/읽기, 해석기 재정의, 레지스트리
조회, 부분 실패 정리를 검증한다.

## 검증

- `./gradlew :bluetape4k-aws-exposed:cleanTest :bluetape4k-aws-exposed:test :bluetape4k-aws-exposed:koverXmlReport --no-build-cache --no-configuration-cache`에서 테스트 6개가 통과했다.
- `./gradlew projects --no-configuration-cache`에 `:bluetape4k-aws-exposed`가 표시되었다.
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`이 통과했다.
- `rg -n "\\\\'" .github/workflows`에서 escape된 GitHub expression 따옴표가 발견되지 않았다.

## 향후 보호 장치

새 컨테이너 기반 모듈에는 변경한 모듈 경로를 위한 PR CI를 추가한다. 다만 테스트가
daily smoke에 포함할 만큼 충분히 저렴하다고 명확히 확인하지 않았다면 Nightly 실행은
weekly/full lane에 유지한다. 실제 AWS 해석기 구현은 프레임워크 중립적인 기반에
포함하지 않는다.
