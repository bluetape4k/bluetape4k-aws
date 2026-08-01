# 스냅샷 캐시 조치

## 배경

저장소가 Central 스냅샷 저장소의 변경 가능한 bluetape4k SNAPSHOT 아티팩트에
의존하는 동안 Nightly는 의존성을 강제로 새로 고쳤다.

## 결정

`--refresh-dependencies`와 Nightly의 `cache-disabled: true`를 제거하고, 루트의
changing-module 캐시 TTL을 0초에서 하루로 변경한다.

## 결과

Nightly는 기존 모듈 및 예제 작업 구조를 유지한다. 다만 일반적인 의존성 해석은
모든 작업에서 Central 스냅샷 메타데이터를 강제로 요청하는 대신 Gradle 캐시
메타데이터를 사용할 수 있다.

## 검증

- `actionlint .github/workflows/*.yml`
- `rg -n -- '--refresh-dependencies|cache-disabled: true' .github/workflows` -> no matches
- `./gradlew help --no-daemon`
- `git diff --check`

## 향후 지침

명시적인 의존성 새로 고침은 게시 후 최신 상태를 확인하는 전용 검사에서만
사용한다. 일반 CI, Nightly, Examples workflow는 캐시된 changing-module
메타데이터를 사용하고, 테스트 전용 SNAPSHOT 의존성이 필요할 때만 해당 대상을
미리 불러온다.
