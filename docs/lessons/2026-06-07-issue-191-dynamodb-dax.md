# Issue 191 DynamoDB DAX auto-configuration

## Context

`aws-spring-boot` needed optional DynamoDB Accelerator(DAX) support without
forcing the DAX client onto normal DynamoDB users. The dependency is not part of
the AWS SDK BOM, so the consuming application must opt into the runtime jar.

## Decision

- Keep `software.amazon.dax:amazon-dax-client` as `compileOnly` in the module
  and `testImplementation` for auto-configuration slice tests.
- Register a separate DAX auto-configuration phase before the normal DynamoDB
  auto-configuration.
- Expose DAX through the existing `DynamoDbAsyncClient` / `DynamoDbEnhancedAsyncClient`
  path so repository code remains unchanged.
- Use `ApplicationContextRunner` with static dummy credentials in DAX-enabled
  tests because the DAX `Configuration.Builder` resolves credentials during
  client construction.

## Outcome

The DAX path is opt-in through `bluetape4k.aws.dynamodb.dax.enabled=true` and a
required `url`. The normal DynamoDB path remains active when DAX is disabled,
when a user client exists, or when the DAX class is absent.

## Verification

- `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.dynamodb.DynamoDbAutoConfigurationTest'`
  passed with 12 tests.
- `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.dynamodb.*'`
  passed with 13 tests, including the emulator-backed repository test.
- `./gradlew :bluetape4k-aws-spring-boot:test` passed with 157 tests.
- `dependencyInsight` proved `amazon-dax-client:2.0.9` and aligned its
  transitive `software.amazon.awssdk:dynamodb:2.38.5` request to `2.46.0`.

## Future Guard

Do not reuse AWS SDK `DynamoDbAsyncClientBuilder` customizers for DAX directly;
`ClusterDaxAsyncClient` uses its own `software.amazon.dax.Configuration`
builder. Keep live DAX cluster validation separate from emulator-backed
LocalStack, Floci, and DynamoDB Local tests.

When adding a README-visible feature, update the related README diagram asset in
the same PR. For auto-configuration tests, keep reusable MockK collaborators as
class fields and clear them in `@BeforeEach` when the test class already has
multiple custom-bean backoff scenarios.
