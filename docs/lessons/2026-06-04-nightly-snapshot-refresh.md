# Nightly 스냅샷 새로 고침

## 배경

Nightly는 Gradle cache를 restore하고 변경 가능한 bluetape4k Central snapshot artifact를
사용합니다. 오래된 snapshot metadata나 동시에 발생한 Central snapshot metadata request로
인해 test 실행 전에 module job이 실패할 수 있습니다.

## 결정

Nightly Gradle 실행에 `--refresh-dependencies`를 전달하고 scheduled cron의 분 값을
분산합니다. 하위 저장소를 모두 동시에 시작하지 않으면서 snapshot metadata를 다시
검사합니다.

## 결과

Nightly는 build state의 cache 재사용을 유지하면서 변경 가능한 metadata를 refresh하고,
예약된 저장소 간 Central snapshot contention을 줄입니다.

## 검증

- `actionlint .github/workflows/nightly-tests.yml`
- `git diff --check`
