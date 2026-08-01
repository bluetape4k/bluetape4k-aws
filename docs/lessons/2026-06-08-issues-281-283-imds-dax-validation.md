# Issues #281-#283 IMDS 및 DAX 유효성 검사 후속 작업

날짜: 2026-06-08

## 배경

AWS 0.4.0 버전 작업을 병합한 뒤 검토에서 P2 공백 세 가지를 발견했다. Ktor IMDS
유효성 검사가 주입한 연산에도 적용되었고, 사용자가 비동기 HTTP 클라이언트를 제공해도
Spring Boot IMDS가 Netty를 요구했으며, DAX 용량 설정이 0을 허용했다.

## 결정

- 주입한 연산을 사용자가 완전히 제공한 IMDS 런타임 경로로 취급하고 클라이언트 생성 설정의 유효성 검사를 건너뛴다.
- 선택적 기본 클라이언트 구현 검사를 사용자가 제공한 추상화 경로와 분리한다.
- DAX 최대 동시 실행 수 및 대기 중 획득 수 같은 용량 설정은 0 이상이 아니라 양수인지 검증한다.

## 결과

- 세 검토 이슈 모두에 회귀 검증을 추가했다.
- 로컬 도우미 로직을 추가하지 않고 `SdkAsyncHttpClientProvider.defaultHttpClient`와 `requirePositiveNumber`를 재사용했다.
- 실제 Ktor 플러그인 설치 경로, 비동기 HTTP 클라이언트 API가 없을 때 Spring Boot 클래스 경로 물러서기, DAX 최소 양수 경계를 추가로 강화했다.

## 향후 보호 장치

- 선택적 AWS HTTP 클라이언트는 기본 클라이언트와 제공된 클라이언트의 클래스 경로를 모두 테스트한다.
- 설정 프로퍼티에서는 사용자가 볼 수 있는 모든 용량 설정의 0 경계를 테스트한다.
- 검토 후 P2 수정은 이슈로 추적하고 PR을 만들기 전에 기록한 검토 증거를 포함한다.

## 검증

- `./gradlew :bluetape4k-aws-ktor:test --tests "io.bluetape4k.aws.ktor.imds.ImdsKtorPluginTest"`: 통과
- `./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.imds.ImdsAutoConfigurationTest" --tests "io.bluetape4k.aws.spring.dynamodb.DynamoDbAutoConfigurationTest"`: 통과
- `./gradlew :bluetape4k-aws-ktor:test :bluetape4k-aws-spring-boot:test`: 통과
- `./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.imds.ImdsAutoConfigurationTest" --tests "io.bluetape4k.aws.spring.dynamodb.DynamoDbAutoConfigurationTest" :bluetape4k-aws-ktor:test --tests "io.bluetape4k.aws.ktor.imds.ImdsKtorPluginTest" --rerun-tasks`: 통과, 대상 테스트 31개 실행
- `./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.imds.ImdsAutoConfigurationTest" --tests "io.bluetape4k.aws.spring.dynamodb.DynamoDbAutoConfigurationTest" :bluetape4k-aws-ktor:test --tests "io.bluetape4k.aws.ktor.imds.ImdsKtorPluginTest" --rerun-tasks`: 후속 강화 후 통과, 대상 테스트 33개 실행
- `git diff --check`: 통과
