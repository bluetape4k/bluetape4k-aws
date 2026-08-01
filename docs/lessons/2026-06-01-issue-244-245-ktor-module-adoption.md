# Issue #244/#245 - 공통 Ktor 모듈 채택

## 배경

Issues #244와 #245에서는 일반적인 Ktor 동작을 이미 소유한 공통
`bluetape4k-ktor-*` 모듈 계열을 AWS Ktor 모듈과 예제에서 우선 사용하도록 요청했다.

## 결정

Ktor용 AWS 모듈과 예제에 `bluetape4k-ktor-core`와 `bluetape4k-ktor-testing`을
도입했다. 다만 명시적인 런타임 또는 직렬화 선택에 해당하는 Ktor artifact는 직접
의존성을 유지했다.

- `ktor-client-cio`, `ktor-server-cio`, AWS service client는 애플리케이션/런타임의
  선택으로 유지한다.
- DTO가 kotlinx serialization 대신 Jackson을 사용하는 예제에는
  `ktor-serialization-jackson`과 content-negotiation client 의존성을 의도적으로
  유지한다.
- S3 test는 AWS/S3 request shape를 검증하므로 Ktor `MockEngine` 동작을 명시적으로
  유지한다.

## 결과

이제 `aws-ktor`는 공통 Ktor core baseline을 제공한다. 예제는 적용 가능한 곳에서 공통
route parameter helper를 사용하고, test는 공통 Ktor response assertion surface를
사용한다.

## 검증

- `./gradlew :bluetape4k-aws-ktor:compileTestKotlin :aws-ktor-dynamodb-examples:compileTestKotlin :aws-ktor-exposed-examples:compileTestKotlin :aws-ktor-s3-examples:compileTestKotlin :aws-ktor-sqs-examples:compileTestKotlin`
- `./gradlew :bluetape4k-aws-ktor:test :aws-ktor-dynamodb-examples:test :aws-ktor-exposed-examples:test :aws-ktor-s3-examples:test :aws-ktor-sqs-examples:test --max-workers=1`
- `./gradlew :bluetape4k-aws-ktor:dependencyInsight --configuration compileClasspath --dependency bluetape4k-ktor-core`
- `git diff --check`
- 로컬 7단계 검토: P0/P1 지적 없음. 주요 검토 위험은 Jackson과 kotlinx serialization
  경계, transitive Ktor server dependency, route parameter 동작이었다.

## 향후 보호 장치

DTO를 kotlinx serialization으로 migration하고 route/test 동작을 다시 검증하기 전에는
Jackson 기반 예제 content negotiation을 `bluetape4k-ktor-core`의 kotlinx JSON
installer로 교체하지 않는다.
