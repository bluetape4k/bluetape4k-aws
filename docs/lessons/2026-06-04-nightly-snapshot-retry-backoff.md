## 배경

Nightly 및 CI matrix job이 Central snapshot에서 upstream `1.11.0-SNAPSHOT` artifact를
해석하는 중 간헐적으로 실패했습니다. local Central metadata 검사는 HTTP 200을 반환했지만,
GitHub-hosted runner는 간헐적으로 HTTP 403을 받았습니다.

## 결정

CI, Nightly, example workflow의 Gradle step에 같은 retry 정책을 적용합니다. 최대 다섯 번
시도하고 각 시도 사이에 30초를 기다립니다.

## 결과

workflow는 module test를 실패로 표시하기 전에 일시적인 Central snapshot metadata failure가
복구될 시간을 더 확보합니다.

## 검증

- `git diff --check`
- `actionlint .github/workflows/*.yml`

## 향후 지침

하위 bluetape4k 저장소가 아직 출시하지 않은 upstream snapshot을 사용할 때는 먼저
upstream을 안정화합니다. upstream CI와 Nightly gate가 통과한 뒤 하위 Nightly를 다시
실행합니다.
