# Issue #11 Ktor DynamoDB 통합

Issue #11은 처음에 Spring Boot DynamoDB 작업과 비슷한 Ktor DynamoDB repository 구조를
지향했다. 구현 방향은 Kotlin 우선 경로로 바뀌었다. `aws-ktor`는 AWS Java SDK v2
Enhanced Client를 기본으로 사용하지 않고 저장소의 `:aws-kotlin` 모듈과 공식 AWS SDK
for Kotlin DynamoDB client를 재사용한다.

## 결정

`aws-ktor`는 또 다른 DynamoDB 추상화 stack이 아니라 얇은 Ktor 수명 주기 통합을
제공한다. `DynamoDbKtorPlugin`은 `DynamoDbKtorRuntime`을 설치하고 application
attribute에 저장한다. 설정에 따라 명시적으로 등록했지만 없는 table을 생성하며,
plugin이 소유한 AWS Kotlin SDK client만 닫는다.

Repository mapping은 `DynamoItemMapper<T>`와 새 `DynamoItemReader<T>`를 통해 명시적으로
유지한다. AWS Kotlin DynamoDB Mapper가 Developer Preview API인 동안 첫 범위를 안정적으로
유지하기 위한 결정이다.

기존 Ktor S3/SQS/SigV4 코드에는 Java SDK v2 surface가 남아 있다. 이 issue가 광범위한
Ktor 재작성으로 커지지 않도록 migration은 issue #85에서 별도로 추적한다.

구현 중 저장소를 `bluetape4k-jackson3`로 통일했다. 이제 모든 AWS 모듈이 이 artifact를
참조하며, `:aws-kotlin`에서 DynamoDB JSON helper를 직접 사용하는 코드는
`tools.jackson` / `io.bluetape4k.jackson3`를 import한다.

## 검증

- `./gradlew :aws-kotlin:compileKotlin :aws-ktor:compileKotlin`
- `./gradlew :aws-kotlin:compileTestKotlin :aws-ktor:compileTestKotlin`
- `git diff --check`
- `./gradlew :aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.dynamodb.DynamoItemMapperTest' :aws-ktor:test --tests 'io.bluetape4k.aws.ktor.dynamodb.*'`
- `./gradlew :aws-kotlin:test :aws-ktor:test`
- `./gradlew :aws:compileKotlin :aws-kotlin:compileKotlin :aws-spring-boot:compileKotlin :aws-ktor:compileKotlin :aws-kotlin:compileTestKotlin :aws-spring-boot:compileTestKotlin :aws-ktor:compileTestKotlin`
- `./gradlew :aws:test :aws-kotlin:test :aws-spring-boot:test :aws-ktor:test`
- `./gradlew detekt`는 `NO-SOURCE`를 반환했다. 이 build에는 모듈 수준의
  `:aws-kotlin:detekt` 및 `:aws-ktor:detekt` task가 등록되지 않았다.

결과적으로 mapper/DynamoDB 대상 테스트가 통과했다. `:aws-kotlin:test`는 444개 통과와
5개 pending, `:aws-ktor:test`는 plugin 소유 client 종료 검증을 추가한 뒤 40개 통과,
`:aws:test`는 252개 통과와 2개 pending, `:aws-spring-boot:test`는 85개 통과였다.
