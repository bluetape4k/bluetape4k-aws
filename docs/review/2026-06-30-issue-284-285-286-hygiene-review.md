# Review: issue 284/285/286 hygiene

## Scope

- Issues: #284, #285, #286.
- Branch: `chore/aws-0.5.0-hygiene`.
- Modules: `aws-spring-boot`, `aws-ktor`.

## Evidence

- `git diff --check`: PASS.
- `rg -n "[가-힣]" aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/dynamodb aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/imds aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/cloudwatch aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/imds`: PASS, no matches.
- `rg -n "\brequire\(" aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/imds aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/imds aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/cloudwatch aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/dynamodb`: PASS with documented exceptions below.
- #286 trailing-LF check: PASS, all issue-listed files have exactly one trailing LF.
- `./gradlew :bluetape4k-aws-ktor:testClasses :bluetape4k-aws-spring-boot:testClasses`: PASS.
- `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.imds.*' :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.imds.*' --tests 'io.bluetape4k.aws.spring.cloudwatch.*' --tests 'io.bluetape4k.aws.spring.dynamodb.*'`: PASS.

## Validation Helper Exceptions

The remaining plain `require(...)` calls in the #285 search scope are kept intentionally:

- `CloudWatchProperties`: endpoint override requires a non-blank region.
- `CloudWatchLogsProperties`: endpoint override requires a non-blank region.
- `DynamoDbProperties`: endpoint override requires a non-blank region.

These are cross-field constraints, and #285 explicitly allows plain `require(...)`
for cross-field validation where no bluetape4k helper improves clarity.

## Findings

- P0: 0.
- P1: 0 after repairing #286 trailing-LF findings.
- P2/P3: none.

## Residual Risk

- IntelliJ diagnostics were not available in this session; targeted Gradle compile
  and tests were used as the fallback.
