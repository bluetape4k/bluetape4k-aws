---
title: Java 21 호환 모듈과 nightly Floci 표기 정렬
date: 2026-08-12
issue: 488
module: repository-docs-ci
---

# Java 21 호환 모듈과 nightly Floci 표기 정렬

## 상황

루트 `build.gradle.kts`는 `bluetape4k-aws-exposed`를 Java 21 호환성
프로젝트로 관리하고 있었지만, 영문·국문 README 목록에는 이 모듈이 빠져
있었다. 또한 `aws`, `aws-kotlin`, `aws-ktor` nightly job의 주석과 표시 이름은
실제 테스트 기본값인 Floci 대신 LocalStack으로 남아 있었다.

## 발견 사항

- 호환성 모듈 목록은 루트 `java21CompatibilityProjects` 집합을 기준으로 해야 한다.
- 각 모듈의 `tasks.test`가 Floci를 기본 emulator로 설정하므로 nightly 표시도 Floci와
  일치해야 한다.
- LocalStack은 명시적 fallback으로 계속 지원하므로 이번 정리는 fallback 동작이나
  테스트 명령을 변경하지 않는다.

## 결정

- 영문·국문 README의 Java 21 호환 모듈 목록에 `bluetape4k-aws-exposed`를 추가한다.
- 세 nightly job의 주석과 `name`만 `Floci`로 정렬한다.
- 실행 명령, retry 정책, emulator 선택 로직은 변경하지 않는다.

## 검증

`actionlint .github/workflows/nightly-tests.yml`,
`manual_contract_test.rb`(9 runs, 44 assertions),
`export_manifest.rb --check`, README locale·nightly reference assertion, `git diff --check`를
통과했다. 변경 범위 밖의 Gradle emulator 통합 테스트와 전체 빌드는 실행하지 않았다.

## 향후 지침

호환성 모듈이나 emulator 기본값을 변경할 때는 build 설정을 canonical source로
삼아 README 두 locale과 nightly job 표시 이름을 함께 확인한다. 실제 backend 기본값과
운영 표기가 다르면 테스트 결과를 잘못 해석할 수 있으므로, LocalStack fallback을
유지하더라도 기본값 표기는 별도로 정렬한다.
