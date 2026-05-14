# Issue #11 Ktor DynamoDB

Issue #11 originally pointed toward a Ktor DynamoDB repository shape similar to
the Spring Boot DynamoDB work. The implementation direction changed to a
Kotlin-first path: `aws-ktor` reuses the repository's `:aws-kotlin` module and
the official AWS SDK for Kotlin DynamoDB client instead of defaulting to the AWS
Java SDK v2 Enhanced Client.

## Decision

`aws-ktor` should expose a thin Ktor lifecycle integration, not another
DynamoDB abstraction stack. `DynamoDbKtorPlugin` installs a
`DynamoDbKtorRuntime`, stores it in application attributes, creates missing
explicitly registered tables when configured, and closes only plugin-owned AWS
Kotlin SDK clients.

Repository mapping stays explicit through `DynamoItemMapper<T>` and the new
`DynamoItemReader<T>`. This keeps the first slice stable while the AWS Kotlin
DynamoDB Mapper remains a Developer Preview API.

Existing Ktor S3/SQS/SigV4 code still has Java SDK v2 surfaces. That migration
is intentionally tracked separately in issue #85 so this issue does not become
a broad Ktor rewrite.

During implementation, the repository was also standardized on
`bluetape4k-jackson3`. All AWS modules now reference that artifact, and the
direct DynamoDB JSON helper usage in `:aws-kotlin` imports `tools.jackson` /
`io.bluetape4k.jackson3`.

## Verification

- `./gradlew :aws-kotlin:compileKotlin :aws-ktor:compileKotlin`
- `./gradlew :aws-kotlin:compileTestKotlin :aws-ktor:compileTestKotlin`
- `git diff --check`
- `./gradlew :aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.dynamodb.DynamoItemMapperTest' :aws-ktor:test --tests 'io.bluetape4k.aws.ktor.dynamodb.*'`
- `./gradlew :aws-kotlin:test :aws-ktor:test`
- `./gradlew :aws:compileKotlin :aws-kotlin:compileKotlin :aws-spring-boot:compileKotlin :aws-ktor:compileKotlin :aws-kotlin:compileTestKotlin :aws-spring-boot:compileTestKotlin :aws-ktor:compileTestKotlin`
- `./gradlew :aws:test :aws-kotlin:test :aws-spring-boot:test :aws-ktor:test`
- `./gradlew detekt` returned `NO-SOURCE`; module-level `:aws-kotlin:detekt`
  and `:aws-ktor:detekt` tasks are not registered in this build.

Result: targeted mapper/DynamoDB tests passed, `:aws-kotlin:test` passed with
444 passing and 5 pending tests, `:aws-ktor:test` passed with 40 tests after
the plugin-owned close coverage was added, `:aws:test` passed with 252 passing
and 2 pending tests, and `:aws-spring-boot:test` passed with 85 tests.
