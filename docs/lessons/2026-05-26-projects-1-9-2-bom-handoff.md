# Projects 1.9.2 BOM 인계

## 배경

`bluetape4k-projects` 1.9.2가 출시됐고 Maven Central에서
`bluetape4k-bom:1.9.2`을 확인할 수 있습니다.

## 결정

이 release-prep branch에서는 대응하는 projects snapshot 대신 안정 버전인
`bluetape4k-bom` 1.9.2 개발선을 사용합니다. 이번 인계는 이미 출시된 projects BOM만
승격하므로 Exposed BOM reference는 현재 개발선을 유지합니다.

## 결과

version catalog는 이 저장소의 release line을 바꾸지 않으면서
`io.github.bluetape4k:bluetape4k-bom`을 안정 버전 1.9.2에서 해석합니다.

## 검증

- `bluetape4k-bom:1.9.2`에 대한 Maven Central HTTP 200
- `./gradlew help --refresh-dependencies --no-daemon --no-configuration-cache --no-build-cache`
