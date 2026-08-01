# Dependencies catalog 2026-05-26-01

## 배경

`bluetape4k-dependencies`는 중앙에서 관리하는 security dependency line을 포함한
`catalog/2026-05-26-01`을 발행했습니다.

## 결정

공통 external library version을 local에서 고정하는 대신 하위 저장소의 기본
`bluetape4kDependenciesCatalogRef`를 새 catalog tag로 갱신합니다.

## 결과

저장소는 이제 기본적으로 `catalog/2026-05-26-01`에서 공통 dependency version을
해석합니다.

## 검증

`settings.gradle.kts`의 catalog ref를 확인했습니다.

## 향후 지침

공통 external library는 먼저 `bluetape4k-dependencies`를 갱신하고 catalog tag를 만든 뒤
하위 저장소를 해당 tag로 옮깁니다.
