# Issues #59 및 #13 KMS 필드 암호화와 Spring SQS 예제

날짜: 2026-05-14
이슈:

- https://github.com/bluetape4k/bluetape4k-aws/issues/59
- https://github.com/bluetape4k/bluetape4k-aws/issues/13

## 배경

Issue #59에서는 초기 KMS Spring Boot 지원에 이어 KMS 기반 필드 암호화를 요청했다.
Issue #13에서는 Spring Boot SQS 사용 예제를 요청했다. 두 작업 모두 AWS 서비스 SDK
의존성을 소비자가 제공하는 `compileOnly` surface로 유지한다는 저장소 규칙을 따라야 했다.

## 결정 또는 발견

#59에서는 투명한 persistence 암호화 대신 명시적인 필드 codec을 사용한다.

- `@KmsEncrypted`는 필드에만 적용하고 runtime에 유지한다.
- `KmsEncryptedFieldCodec`은 `String` 및 nullable `String` 값만 암호화하고 복호화한다.
- 암호문은 `b4k-kms:v1:`과 Base64 URL encoding으로 versioning한다.
- 첫 범위에서는 직접 선언한 Java 필드만 검증하도록 의도적으로 제한한다.

#13에서는 sample controller를 `aws-spring-boot`에 넣지 않고 `examples/` 아래에 전용 예제
모듈을 추가한다.

- Queue 생성/전송/수신 예제는 `SqsOperations`를 사용한다.
- SNS fanout 및 DLQ 설정 예제는 SDK async client와 coroutine `await()`을 사용한다.
- Listener 예제는 `@SqsListener`를 통해 Spring Boot listener 자동 구성을 사용한다.

## 결과

Claude 검토에서 게시 전에 P1/P2 위험 여러 개를 발견했다. 최종 코드는 다음과 같다.

- suspend service 안에서 blocking `CompletableFuture.get()`을 사용하지 않는다.
- KMS exception hierarchy에서 service 암호화 실패와 사용 오류를 구분한다.
- 생성한 queue policy에서 SNS service principal을 사용한다.
- 중복 KMS encryption-context entry를 거부한다.
- CI 환경의 시작 편차를 감당하도록 LocalStack listener polling 시간을 늘린다.

## 검증

- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.kms.KmsEncryptedFieldCodecTest' --tests 'io.bluetape4k.aws.spring.kms.KmsAutoConfigurationTest' --tests 'io.bluetape4k.aws.spring.kms.KmsCoroutinesEncryptorLocalStackTest' -Dbluetape4k.aws.emulator=localstack`
- `./gradlew :aws-spring-boot-sqs-examples:test -Dbluetape4k.aws.emulator=localstack`
- `./gradlew :aws-spring-boot:build -x test :aws-spring-boot-sqs-examples:build -x test detekt`
- `git diff --check`

## 향후 지침

더 넓은 Kotlin property/reflection 및 persistence 수명 주기 계약을 설계할 때까지
`@KmsEncrypted`는 필드에만 적용한다. Annotation 우선순위와 mapper 수명 주기 동작을
검증하지 않고 상속 필드나 중첩 graph를 암묵적으로 순회하지 않는다.

Spring SQS/SNS 예제에서는 AWS SDK async 호출에 coroutine `await()`을 사용하고 생성하는
IAM policy 범위를 좁게 유지한다. README snippet을 늘리기 전에 예제 모듈의 LocalStack
테스트로 동작을 입증한다.
