# Issue #6 Secrets Manager / Parameter Store 교훈

## 배경

Issue #6에서는 `aws-spring-boot`의 Spring Environment source로 Secrets Manager와
SSM Parameter Store를 추가한다.

## 결정

일반 자동 구성 bean이 아니라 `META-INF/spring.factories`를 통해 등록한 Spring Boot 4
`org.springframework.boot.EnvironmentPostProcessor`를 사용한다. 원격 값은
`@ConfigurationProperties`를 binding하기 전에 사용할 수 있어야 한다.

## 결과

- 설정한 Secrets Manager 및 Parameter Store source를 시작 시점에 Environment로
  불러오는 기능을 추가했다.
- `refresh-interval`을 통한 선택적 lazy refresh를 추가했다. 다시 불러오지 못하면 이전에
  읽은 값을 유지한다.
- 일반 placeholder 의미를 보존하는 Spring `@Value` 조합 annotation으로
  `@SecretsValue`와 `@ParameterStoreValue`를 추가했다.
- AWS 서비스 SDK 의존성은 `compileOnly`로 유지했다.
- Source를 설정하지 않으면 `spring.factories`가 서비스 SDK type을 해석하지 않고도
  post-processor class를 불러올 수 있도록 post-processor와 SDK 기반 loader를 분리했다.

## 검증

- `./gradlew :aws-spring-boot:compileKotlin`
- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.secretsmanager.*' --tests 'io.bluetape4k.aws.spring.parameterstore.*'` — 4개 통과
- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.env.AwsEnvironmentPropertySourceSupportTest' --tests 'io.bluetape4k.aws.spring.secretsmanager.SecretsValueTest' --tests 'io.bluetape4k.aws.spring.parameterstore.ParameterStoreValueTest'` — 5개 통과
- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.secretsmanager.*' --tests 'io.bluetape4k.aws.spring.parameterstore.*' --tests 'io.bluetape4k.aws.spring.env.AwsEnvironmentPropertySourceSupportTest' -Dbluetape4k.aws.emulator=localstack` — 12개 통과
- `./gradlew :aws-spring-boot:test -Dbluetape4k.aws.emulator=localstack` — 86개 통과

## 향후 보호 장치

Environment source를 통합할 때 compileOnly 서비스 SDK type을 `spring.factories`에서
불러오는 class에 직접 넣지 않는다. 이 class를 작게 유지하고 classpath에 type이 있는지
먼저 확인한 뒤 SDK 기반 loader에 위임한다.

갱신 가능한 source는 scheduler를 추가하지 말고 `PropertySource` 내부에서 지연 갱신한다.
그래야 시작 계약이 단순해지고 Spring Environment 기반 구조에 숨겨진 thread가 생기지
않는다.

Refresh 구현은 backing map을 직접 변경하지 말고 volatile reference 교체로 불변
snapshot을 게시한다. 그래야 일부만 갱신된 값의 노출을 막고 reader의 가시성을 명확히
할 수 있다.
