# 소스에 근거한 README 시각 자료

## 배경

루트 README 모듈 표에는 `settings.gradle.kts`의 모든 예제가 포함되지 않았고,
생성한 개요의 label에는 서로 다른 `Ktor` 표기가 섞여 있었다.

## 결정

루트 모듈 및 예제 커버리지의 기준 정보로 `settings.gradle.kts`를 사용하고, 생성한
시각 자료의 label을 실제 모듈 이름과 일치시킨다.

## 결과

이제 README 표에는 현재 AWS Ktor 및 Spring Boot 예제가 모두 포함된다. 루트 개요의
label은 `aws-ktor-*`를 사용하고 component map은 더 작은 화살촉과 직교 연결 경로를
사용한다.

## 검증

- `git diff --check`
- 변경한 SVG asset에 `xmllint --noout` 실행
- `rsvg-convert`로 PNG 렌더링
- README 이미지 링크 존재 여부 검사

## 다음 작업

예제를 추가할 때는 `settings.gradle.kts`, 루트 README 표, 루트 시각 asset을 함께
갱신한다.
