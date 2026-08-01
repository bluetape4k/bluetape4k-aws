# Exposed 헬퍼 아티팩트에 bluetape4k-exposed BOM 사용

## 배경

`bluetape4k-aws`는 JetBrains `exposed-bom`을 가져오면서도
`bluetape4k-exposed-jdbc`에 버전을 직접 지정했다. 이 때문에 bluetape4k Exposed
헬퍼 아티팩트에 `bluetape4k-exposed-bom`의 제약이 적용되지 않았다.

## 결정

`io.github.bluetape4k.exposed:bluetape4k-exposed-bom`을 버전 카탈로그에 추가한다.
`bluetape4k-exposed-jdbc`의 직접 버전을 제거하고, 이 아티팩트에 직접 의존하는 모든
모듈에 bluetape4k Exposed 플랫폼을 추가한다.

Exposed를 사용하지 않는 모듈에도 제약이 필요하지 않다면 루트에서
`bluetape4k-exposed-bom`을 전역으로 가져오지 않는다. 범위는
`io.github.bluetape4k.exposed:*` 아티팩트를 사용하는 모듈로 제한한다.

변경 당시 이 저장소에는 `bluetape4k-exposed-jdbc-tests` 의존성이 없었다. 나중에
추가한다면 같은 BOM을 사용해야 한다.

## 검증

- `./gradlew :bluetape4k-aws-exposed:compileKotlin :aws-spring-boot-exposed-examples:compileKotlin :aws-ktor-exposed-examples:compileKotlin --no-daemon --max-workers=1`
- `./gradlew :bluetape4k-aws-exposed:dependencies --configuration compileClasspath --no-daemon --max-workers=1`

의존성 보고서에는 `org.jetbrains.exposed:exposed-bom:1.3.0`과
`io.github.bluetape4k.exposed:bluetape4k-exposed-bom:1.8.1-SNAPSHOT`이 모두
표시되며, `bluetape4k-exposed-jdbc`는 BOM을 통해 `1.8.1-SNAPSHOT`으로 해석된다.

## 향후 보호 장치

`bluetape4k-aws`에 `io.github.bluetape4k.exposed:*` 의존성을 추가할 때는 먼저 해당
모듈에 `platform(libs.bluetape4k.exposed.bom)`을 추가하거나 이미 있는지 확인한다.
