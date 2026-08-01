## 배경
Central Portal이 Exposed snapshot metadata에 대해 HTTP 403 response를 반복해서 반환해
초기 retry guard 적용 후에도 AWS post-merge CI가 실패했습니다.

## 결정
더 길지만 횟수가 제한된 retry window를 사용하고 snapshot dependency가 있는 compile
gate에서는 configuration cache를 비활성화합니다. source나 dependency version을 바꾸지
않으면서 일시적인 Central edge failure에 workflow가 견딜 수 있게 합니다.

## 결과
workflow는 compile-only build를 30초 backoff로 최대 다섯 번 실행하며, classpath가
해석되지 않은 동안 configuration-cache serialization failure를 방지합니다.

## 검증
- `git diff --check`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`

## 향후 지침
한 runner에서 Central snapshot metadata가 HTTP 403을 반복해서 반환하면 code
regression으로 판단하기 전에 제한된 retry window를 늘립니다.
