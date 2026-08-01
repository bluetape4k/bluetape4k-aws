# Exposed BOM implementation 범위

## 배경

`bluetape4k-dependencies 1.2.0` 릴리스 트레인은 `bluetape4k-exposed-bom`을
`1.10.0`으로 올린다. `aws-exposed`에는 정렬된 Exposed 헬퍼 버전이 필요하지만
bluetape4k Exposed BOM 플랫폼을 API 의존성으로 게시해서는 안 된다.

## 결정

`aws-exposed`의 `libs.bluetape4k.exposed.bom`을
`implementation(platform(...))`으로 유지하고 카탈로그 버전을 `1.10.0`에 맞춘다.
구체적인 `bluetape4k-exposed-jdbc` API 의존성에는 명시적인 카탈로그 버전을 지정한다.

## 결과

모듈은 승격된 Exposed 헬퍼 버전으로 컴파일하고 테스트하면서도 소비자 API 범위에서
BOM 플랫폼을 제외할 수 있다. 버전이 없는 구체적인 API 의존성은 implementation
범위의 플랫폼에 의존할 수 없다. 따라서 모듈 경계를 넘는 공개 아티팩트에는 자체
버전이 있거나 소비자에게 보이는 플랫폼에서 버전을 제공해야 한다.

## 검증

- Maven Central의 `bluetape4k-exposed-bom:1.10.0`에서 HTTP 200 응답을 받았다.
- `./gradlew :bluetape4k-aws-exposed:build --no-daemon --console=plain`
  통과했다.

## 향후 지침

`bluetape4k-exposed-bom`을 다시 `api(platform(...))`으로 승격하지 않는다. 공개
계약에 포함된 구체적인 Exposed API 아티팩트만 노출한다.
