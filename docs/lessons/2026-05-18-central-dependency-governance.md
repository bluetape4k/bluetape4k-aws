# 중앙 dependency governance 동기화

## 배경

하위 저장소의 Dependabot PR이 공통 dependency version을 저장소별로 갱신해 bluetape4k
조직 전체에 version drift가 발생했습니다.

## 결정

공통 dependency version은 먼저 `bluetape4k-dependencies`에서 변경한 뒤
`sync-shared-versions.py`로 이 저장소에 반영합니다. 이후 PR이 중앙 기준 원본을 거치도록
이 저장소의 Dependabot에서는 중앙에서 관리하는 dependency name도 무시합니다.

## 결과

local version catalog와 `.github/dependabot.yml`이 중앙 dependency governance 정책을
따르게 됐습니다.

## 검증

- 이 저장소에서 `sync-shared-versions.py --write --check --summary`
- 이 저장소에서 `sync-dependabot-ignores.py --write --check --summary`
- `git diff --check`

## 향후 지침

중앙에서 관리하는 dependency의 저장소별 Dependabot PR은 병합하지 않습니다.
`bluetape4k-dependencies`를 갱신한 뒤 이 저장소를 동기화합니다.
