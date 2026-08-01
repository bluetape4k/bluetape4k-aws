# Issue 241 Floci 테스트 Migration

## 배경

Issue #241은 #239/#240의 Floci 우선 정책에 따라 LocalStack을 기본으로 사용하던 AWS
test에서 LocalStack 기본값을 제거한다.

## 결정

Emulator-aware fixture를 제공하는 Java/Kotlin SDK wrapper test, Ktor runtime test, AWS
example test의 기본 emulator로 Floci를 사용한다. Automatic fallback chain 대신
LocalStack을 명시적 fallback으로 유지한다. Floci가 지원하지 않는 API는 assumption으로
보호해 기본 실행을 성공 상태로 유지하고, legacy coverage는 계속 LocalStack으로
입증한다.

## 결과

이제 공통 Java/Kotlin test base는 기본적으로 `floci`를 선택하고
`-Dbluetape4k.aws.emulator=localstack`을 받는다. Ktor 및 AWS example module test도
LocalStack을 직접 만들던 곳에 같은 Floci 우선 selector를 사용한다. Java DynamoDB food
Spring test는 더 이상 `testcontainers.localstack.port`에 의존하지 않는다. 선택한
emulator에서 endpoint, region, credentials를 받는다.

## 검증

- `./gradlew :bluetape4k-aws-java:compileTestKotlin :bluetape4k-aws-kotlin:compileTestKotlin`
  통과
- `./gradlew :bluetape4k-aws-java:test -Dbluetape4k.aws.emulator=floci` 통과: 243개 통과,
  14개 pending
- `./gradlew :bluetape4k-aws-kotlin:test -Dbluetape4k.aws.emulator=floci` 통과: 489개 통과,
  12개 pending
- `./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.kms.KsmClientTest' --tests 'io.bluetape4k.aws.sns.SnsClientTest' -Dbluetape4k.aws.emulator=localstack`
  통과: 20개 통과, 1개 pending
- `./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.kms.KmsClientTest' --tests 'io.bluetape4k.aws.kotlin.sns.SnsClientExtensionsTest' -Dbluetape4k.aws.emulator=localstack`
  통과: 23개 통과, 1개 pending
- `./gradlew :bluetape4k-aws-ktor:test :aws-ktor-dynamodb-examples:test :aws-spring-boot-dynamodb-examples:test :aws-spring-boot-s3-examples:test :aws-spring-boot-sqs-examples:test -Dbluetape4k.aws.emulator=floci`
  통과: 69 + 2 + 3 + 1 + 1개 테스트
- `./gradlew :bluetape4k-aws-ktor:test :aws-ktor-dynamodb-examples:test :aws-spring-boot-dynamodb-examples:test :aws-spring-boot-s3-examples:test :aws-spring-boot-sqs-examples:test -Dbluetape4k.aws.emulator=localstack`
  통과: 69 + 2 + 3 + 1 + 1개 테스트

## 향후 지침

순서가 있는 integration test에 automatic emulator fallback chain을 구현하지 않는다.
Test task마다 emulator 하나를 선택하고 지원하지 않는 operation을 명시적으로 기록하며,
별도의 명시적 실행으로 fallback 동작을 검증한다.
