# PR 279 Micrometer 검토 의견 수정

## 범위

PR #279의 magic string 및 operation-specific Micrometer record helper 의견 후속 작업이다. Ktor Micrometer/S3/SQS, Spring Boot Micrometer/S3/SQS, 집중 test를 변경했다.

## 결과

P0/P1/P2=0, 차단 문제 없음.

## 검토 내용

- Service/tag/outcome/exception/operation 문자열을 관련 support object/class 상수로 모았다.
- `MicrometerS3KtorClient`는 `putObjectRecord`/`getObjectRecord` 같은 operation-specific helper를 호출한다.
- Ktor/Spring support는 별도 `recordSuspend`/`recordBlocking` support helper 대신 suspend/blocking lambda용 inline `record` overload를 제공한다.
- Suspend 함수 안 `() -> T`와 `suspend () -> T` 모호성을 피해야 하는 decorator-local helper는 `recordBlocking`을 유지한다.
- Ktor SQS observer producer도 operation/outcome/tag 상수를 사용한다.
- 공개 metric/tag 값은 바뀌지 않고 production 상수와 독립적인 test contract 상수로 검증한다.
- Dependency/public runtime 동작 변경 없음.

## 검증

- `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-spring-boot:compileKotlin`: PASS
- `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.s3.MicrometerS3KtorClientTest' --tests 'io.bluetape4k.aws.ktor.sqs.MicrometerSqsConsumerObserverTest' :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.s3.MicrometerS3OperationsTest' --tests 'io.bluetape4k.aws.spring.sqs.MicrometerSqsOperationsTest' --tests 'io.bluetape4k.aws.spring.sqs.MicrometerSqsListenerInterceptorTest'`: 3 Ktor+3 Spring PASS
- `./gradlew :bluetape4k-aws-ktor:test :bluetape4k-aws-spring-boot:test`: 85 Ktor+195 Spring PASS
- `git diff --check`: PASS
- `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.s3.MicrometerS3KtorClientTest' --tests 'io.bluetape4k.aws.ktor.sqs.MicrometerSqsConsumerObserverTest' --tests 'io.bluetape4k.aws.ktor.sqs.SqsConsumerRuntimeAdvancedTest' :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.s3.MicrometerS3OperationsTest' --tests 'io.bluetape4k.aws.spring.sqs.MicrometerSqsOperationsTest' --tests 'io.bluetape4k.aws.spring.sqs.MicrometerSqsListenerInterceptorTest'`: 7 Ktor+3 Spring PASS
- Contract test는 production 상수가 아닌 test-side object의 external tag name/value로 meter를 조회한다.

## Gate

PASS. P0=0, P1=0.
