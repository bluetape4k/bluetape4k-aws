# 2026-06-04 이슈 257 Nightly configuration cache 및 catalog

## 배경

Nightly workflow는 snapshot 및 BOM으로 관리하는 dependency를 사용하므로, 오래된
Gradle/configuration 상태에서 version이 없는 dependency coordinate가 나타날 수 있습니다.

## 결정

Nightly Gradle command에는 `--no-configuration-cache`를 유지하고, local bluetape4k
alias의 version은 해당 BOM ref로 관리합니다.

## 결과

Nightly command는 dependency refresh 중 configuration cache에 의존하지 않으며,
저장소별 catalog alias는 `group:artifact:.` coordinate를 생성하지 않습니다.

## 검증

- 예정: `actionlint`, `git diff --check`, command audit, catalog alias audit.

## 향후 지침

snapshot을 refresh하는 Nightly job에서는 저장소별 근거가 달리 정하지 않는 한 Gradle
action cache와 configuration cache를 모두 비활성화합니다.
