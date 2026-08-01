# 릴리스 카탈로그 재정의 보호 장치

## 배경

0.3.0 태그로 시작한 릴리스는 GitHub 저장소 변수
`BLUETAPE4K_DEPENDENCIES_CATALOG_REF`가 여전히 이전 `bluetape4k-dependencies`
카탈로그를 가리켜 처음에 실패했다. 이 변수는 Gradle이 빌드 스크립트를 컴파일하기
전에 저장소에 반영된 `settings.gradle.kts`의 기본 카탈로그 값을 덮어썼다.

## 결정

태그로 시작한 릴리스는 저장소에 반영된 `settings.gradle.kts`의 기본 카탈로그를
사용해야 한다. `workflow_dispatch`에서는 명시적인 `catalogRef` 입력으로
카탈로그를 재정의할 수 있으며, 운영상 대체 수단으로 저장소 변수를 사용할 수 있다.

## 결과

이제 release workflow는 선택한 카탈로그 출처를 기록하고 Maven Central Portal에
게시하기 전에 필수 카탈로그 별칭을 검증한다. 오래된 저장소 변수 때문에 나중에
Gradle 스크립트 컴파일이 실패하는 대신, 즉시 실패하거나 태그 릴리스에서 해당
변수를 무시한다.

## 검증

태그 push 및 수동 dispatch 경로의 카탈로그 선택 shell 로직을 검증하고, 현재
릴리스 카탈로그에서 필수 별칭을 확인한 뒤 `actionlint`와 `git diff --check`를
실행했다.

## 향후 지침

일반적인 카탈로그 변경은 downstream 저장소 파일을 갱신해 반영한다. GitHub 저장소
변수는 수동 릴리스 재정의 수단으로만 취급하고, 릴리스 트레인의 기준 정보로 사용하지
않는다.
