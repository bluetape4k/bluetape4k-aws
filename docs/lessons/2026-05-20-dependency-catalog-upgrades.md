# 의존성 카탈로그 업그레이드

## 배경

`bluetape4k-dependencies`는 AWS SDK Java 및 AWS SDK Kotlin Dependabot PR을 중앙
의존성 업그레이드 묶음에 통합했습니다.

## 결정

저장소별 Dependabot 버전 상향을 수용하는 대신 중앙 카탈로그 버전을 AWS 저장소에
반영합니다.

## 결과

- AWS SDK Java를 `2.44.9`로 올렸습니다.
- AWS SDK Kotlin을 `1.6.77`로 올렸습니다.

## 검증

- `./gradlew build -x test --parallel --no-daemon`
