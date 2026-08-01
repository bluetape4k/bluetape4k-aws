## 배경

공통 dependency catalog에 중앙 plugin alias가 추가된 뒤 AWS Exposed example 모듈에
JetBrains Exposed Gradle plugin을 적용했습니다.

## 결정

library 저장소는 관리형 `bt4k` catalog의 plugin alias를 사용하고 기본 catalog ref를
`catalog/2026-05-26-00`으로 고정합니다.

## 결과

`aws-spring-boot-exposed-examples`와 `aws-ktor-exposed-examples`는 명시적인 table
package 및 H2 migration database 설정과 함께 `generateMigrations` task를 제공합니다.

## 검증

두 Exposed example 모듈에서 `git diff --check`, `./gradlew -q help`, `tasks --all`을
실행했습니다.

## 향후 지침

workshop형 저장소는 관리형 catalog와 독립적으로 유지합니다. bluetape4k library 저장소만
`bt4k.plugins.exposed.plugin`을 사용합니다.
