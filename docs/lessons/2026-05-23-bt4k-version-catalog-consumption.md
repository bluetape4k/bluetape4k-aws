# bt4k 버전 카탈로그 사용

## 배경

`bluetape4k-aws`는 게시된 `bluetape4k-dependencies` 버전 카탈로그에 이미 있는 공통
의존성의 버전을 로컬에서도 별도로 고정하고 있었다.

## 결정

공통 카탈로그를 `bt4k`로 가져오고 의존성 관리에서 `bt4kVersion(alias)`를 통해
공통 개별 의존성 버전을 해석한다. 빌드가 여전히 로컬 플러그인 별칭에 의존하는 플러그인
및 BOM 트레인 버전은 로컬에 유지한다.

## 결과

선택한 공통 의존성 별칭은 로컬 카탈로그에서 버전을 갖지 않으며 실제 버전은
`bluetape4k-dependencies`에서 가져온다.

## 검증

- `git diff --check`
- `./gradlew help --no-daemon --no-configuration-cache`
- `./gradlew compileKotlin --no-daemon --no-configuration-cache`

## 향후 지침

공통 AWS 관련 의존성이나 일반 의존성을 추가할 때는 먼저 버전을
`bluetape4k-dependencies`에 추가하고 이 저장소에서는 `bt4k`를 통해 사용한다.
