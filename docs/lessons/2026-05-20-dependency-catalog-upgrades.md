# Dependency catalog upgrade

## 배경

`bluetape4k-dependencies`는 AWS SDK Java 및 AWS SDK Kotlin Dependabot PR을 중앙
dependency upgrade 묶음에 통합했습니다.

## 결정

저장소별 Dependabot version bump를 수용하는 대신 중앙 catalog version을 AWS 저장소에
반영합니다.

## 결과

- AWS SDK Java moved to `2.44.9`.
- AWS SDK Kotlin moved to `1.6.77`.

## 검증

- `./gradlew build -x test --parallel --no-daemon`
