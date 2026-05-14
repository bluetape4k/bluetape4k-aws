# Issues #59 and #13 KMS Field Encryption and Spring SQS Examples

Date: 2026-05-14
Issues:

- https://github.com/bluetape4k/bluetape4k-aws/issues/59
- https://github.com/bluetape4k/bluetape4k-aws/issues/13

## Context

Issue #59 asked for KMS-backed field encryption after the initial KMS Spring
Boot support. Issue #13 asked for Spring Boot SQS usage examples. Both had to
fit the repository rule that AWS service SDK dependencies stay consumer-provided
`compileOnly` surfaces.

## Decision or Finding

For #59, use an explicit field codec instead of transparent persistence
encryption:

- `@KmsEncrypted` is field-only and runtime-retained.
- `KmsEncryptedFieldCodec` encrypts/decrypts `String` and nullable `String`
  values only.
- Ciphertext is versioned with `b4k-kms:v1:` and Base64 URL encoding.
- Validation is deliberately limited to directly declared Java fields in this
  first slice.

For #13, add a dedicated example module under `examples/` instead of folding
sample controllers into `aws-spring-boot`:

- Queue create/send/receive examples use `SqsOperations`.
- SNS fanout and DLQ setup examples use SDK async clients with coroutine
  `await()`.
- Listener examples use the Spring Boot listener auto-configuration through
  `@SqsListener`.

## Outcome

Claude review found several P1/P2 risks before publishing. The final code:

- avoids blocking `CompletableFuture.get()` inside suspend services;
- separates service encryption failures from usage errors in the KMS exception
  hierarchy;
- uses an SNS service principal in generated queue policies;
- rejects duplicate KMS encryption-context entries;
- extends LocalStack listener polling enough for CI-grade startup variance.

## Verification

- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.kms.KmsEncryptedFieldCodecTest' --tests 'io.bluetape4k.aws.spring.kms.KmsAutoConfigurationTest' --tests 'io.bluetape4k.aws.spring.kms.KmsCoroutinesEncryptorLocalStackTest' -Dbluetape4k.aws.emulator=localstack`
- `./gradlew :aws-spring-boot-sqs-examples:test -Dbluetape4k.aws.emulator=localstack`
- `./gradlew :aws-spring-boot:build -x test :aws-spring-boot-sqs-examples:build -x test detekt`
- `git diff --check`

## Future Guidance

Keep `@KmsEncrypted` field-only until a broader Kotlin property/reflection and
persistence lifecycle contract is designed. Do not silently recurse through
inherited fields or nested graphs without tests for annotation precedence and
mapper lifecycle behavior.

For Spring SQS/SNS examples, prefer coroutine `await()` on AWS SDK async calls
and keep generated IAM policies narrow. Example modules should prove behavior
with LocalStack tests before README snippets are expanded.
