# Central Release POM 메타데이터

## 배경

Import한 BOM이 관리하는 dependency의 version metadata가 생성한 Maven POM에서 빠져
0.1.0 Central Portal release 검증이 실패했다.

## 결정

생성한 POM에 dependency management entry가 포함되도록 release POM의 Spring dependency
management customization을 유지한다.

## 결과

이제 생성한 publication POM의 `dependencyManagement`에
`io.github.bluetape4k:bluetape4k-bom:1.8.0`이 포함되고 `SNAPSHOT` 참조는 없다.

## 검증

- `./gradlew generatePomFileForBluetapeAwsPublication --no-daemon --no-configuration-cache --no-build-cache`
- 생성한 `pom-default.xml` 파일에서 `SNAPSHOT`을 검색했다.

## 향후 지침

Central release tag를 만들기 전에 Maven POM을 로컬에서 생성하고, 관리하는 dependency가
명시적 version 또는 유효한 POM dependency management로 표현되는지 확인한다.

## 2026-07-17 후속 작업

저장소 전체 snapshot audit에서 이 규칙을 수동 release check가 아니라 실행 가능한
검증으로 만들어야 함을 확인했다. 게시하는 BOM import는 이제 version이 있는 central
`bt4k` alias를 사용한다. `scripts/publication/validate_poms.rb`는 생성한 모든 POM을
구조적으로 검사하고 Maven effective-model도 구성한다. 일반 dependency는 같은 POM의
dependency management 또는 version이 있는 imported BOM을 통해 Maven이 해석할 수 있을
때만 version을 생략할 수 있다.
