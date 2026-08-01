# Issue 191 DynamoDB DAX 자동 구성

## 배경

`aws-spring-boot`에는 일반 DynamoDB 사용자에게 DAX client를 강제하지 않는 선택적
DynamoDB Accelerator(DAX) 지원이 필요했다. 이 dependency는 AWS SDK BOM에 포함되지
않으므로 사용하는 애플리케이션이 runtime jar를 직접 선택해야 한다.

## 결정

- Module의 `software.amazon.dax:amazon-dax-client`는 `compileOnly`, 자동 구성 slice
  test에서는 `testImplementation`으로 유지한다.
- 일반 DynamoDB 자동 구성보다 앞에 별도의 DAX 자동 구성 단계를 등록한다.
- Repository code를 바꾸지 않도록 기존 `DynamoDbAsyncClient` /
  `DynamoDbEnhancedAsyncClient` 경로로 DAX를 제공한다.
- DAX `Configuration.Builder`가 client 생성 중 credentials를 해석하므로 DAX 활성화
  test에서는 정적 dummy credentials와 `ApplicationContextRunner`를 사용한다.

## 결과

DAX 경로는 `bluetape4k.aws.dynamodb.dax.enabled=true`와 필수 `url`을 통해 명시적으로
활성화한다. DAX가 비활성화됐거나 사용자 client가 있거나 DAX class가 없으면 일반
DynamoDB 경로가 계속 활성화된다.

## 검증

- `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.dynamodb.DynamoDbAutoConfigurationTest'`
  에서 테스트 12개 통과
- `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.dynamodb.*'`
  에서 emulator 기반 repository test를 포함한 테스트 13개 통과
- `./gradlew :bluetape4k-aws-spring-boot:test`에서 테스트 157개 통과
- `dependencyInsight`로 `amazon-dax-client:2.0.9`와 transitive
  `software.amazon.awssdk:dynamodb:2.38.5` 요청이 `2.46.0`으로 정렬됨을 확인

## 향후 보호 장치

AWS SDK `DynamoDbAsyncClientBuilder` customizer를 DAX에 직접 재사용하지 않는다.
`ClusterDaxAsyncClient`는 자체 `software.amazon.dax.Configuration` builder를 사용한다.
실제 DAX cluster 검증은 emulator 기반 LocalStack, Floci, DynamoDB Local test와 분리한다.

README에 보이는 기능을 추가할 때는 같은 PR에서 관련 README diagram asset도 갱신한다.
자동 구성 test class에 사용자 bean backoff scenario가 여러 개 있으면 재사용하는 MockK
collaborator를 class field로 두고 `@BeforeEach`에서 초기화한다.
