# 이슈 #259 Central snapshot retry

## 배경
GitHub runner가 Central Portal snapshot metadata에서 일시적인 HTTP 403 response를
받으면 하위 CI 및 Nightly 실행이 실패할 수 있습니다.

## 결정
Gradle command의 의미를 바꾸지 않으면서 최상위 Gradle build와 Nightly detekt gate를 최대
세 번 실행하는 제한된 retry loop로 감쌉니다.

## 검증
- `git diff --check`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`

## 향후 지침
bluetape4k SNAPSHOT dependency가 Central metadata 403으로 실패하면 먼저 upstream publish
상태를 확인합니다. 그런 다음 dependency 또는 catalog를 불필요하게 변경하기보다 횟수가
제한된 workflow retry를 우선합니다.
