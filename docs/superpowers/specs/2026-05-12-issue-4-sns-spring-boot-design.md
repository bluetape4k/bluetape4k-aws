# Issue #4 SNS Spring Boot Design

## Context

- Repository: `bluetape4k-aws`
- Issue: <https://github.com/bluetape4k/bluetape4k-aws/issues/4>
- Target module: `aws-spring-boot`
- Work type: new Spring Boot feature, Full Design lane
- Related future work: issue #13 remains out of scope for this PR.

Issue #4 asks for self-managed SNS support in `aws-spring-boot`, without depending on awspring. The feature must provide Spring Boot 4 auto-configuration, a coroutine publishing template, topic ARN lookup, FIFO topic support, and fanout-oriented usage evidence.

## Evidence

- Existing local pattern: `SqsAutoConfiguration` registers SDK async clients with `@AutoConfiguration(after = [AwsAutoConfiguration::class])`, string-based `@ConditionalOnClass`, `@ConditionalOnProperty`, `@EnableConfigurationProperties`, `@ConditionalOnMissingBean`, `ObjectProvider<AwsCredentialsProvider>`, and optional `SdkAsyncHttpClient`.
- Existing local pattern: `SqsCoroutinesTemplate` implements an `SqsOperations` contract and uses AWS SDK v2 async calls with `kotlinx.coroutines.future.await()`.
- Existing local pattern: `aws-spring-boot` keeps service SDK dependencies as `compileOnly` in production and `testImplementation` for integration tests.
- Spring Boot 4 documentation confirms auto-configuration discovery through `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, with conditional registration through `@AutoConfiguration`, `@ConditionalOnClass`, `@ConditionalOnMissingBean`, and `@EnableConfigurationProperties`.
- AWS SDK Java v2 documentation confirms SNS publish uses `PublishRequest` with `topicArn`, `message`, optional `subject`, message attributes, `messageGroupId`, and `messageDeduplicationId`; FIFO topics use topic attributes such as `FifoTopic`, `ContentBasedDeduplication`, and optional FIFO throughput scope.
- Existing `aws` module already contains SNS helpers under `io.bluetape4k.aws.sns`, including topic creation helpers and `publishRequestOf`.

## Goals

1. Add `SnsAutoConfiguration` to `aws-spring-boot`.
2. Add `SnsProperties` bound to `bluetape4k.aws.sns`.
3. Add a coroutine-first `SnsOperations` contract and `SnsCoroutinesTemplate`.
4. Support standard topic creation, configured topic creation, FIFO topic creation, topic ARN lookup, and publish.
5. Register SNS auto-configuration through `AutoConfiguration.imports`.
6. Add LocalStack-backed integration tests and ApplicationContextRunner tests.
7. Update README dependency/configuration snippets for SNS.

## Non-Goals

- No Spring Cloud AWS or awspring dependency.
- No SNS listener/message receiver framework.
- No full SQS-SNS application example module. That remains issue #13.
- No SES/email support.
- No cross-account subscription management API.
- No synchronous/blocking SDK client.

## Public API

Package: `io.bluetape4k.aws.spring.sns`

### SnsProperties

Configuration prefix: `bluetape4k.aws.sns`

Fields:

- `enabled: Boolean = true`
- `region: String? = null`
- `endpointOverride: URI? = null`
- `topics: Map<String, Topic> = emptyMap()`

Nested `Topic` fields:

- `fifo: Boolean = false`
- `contentBasedDeduplication: Boolean = true`
- `fifoThroughputScope: SnsFifoThroughputScope? = null`
- `attributes: Map<String, String> = emptyMap()`

Validation:

- `endpointOverride` requires `region`, matching existing SQS behavior.
- If `region` is null, the AWS SDK default region provider chain applies.
- Configured FIFO topics must use names ending in `.fifo`.

### SnsPublishRequest

Use a request data class instead of positional same-type parameters.

Fields:

- `topicArn: String`
- `message: String`
- `subject: String? = null`
- `messageAttributes: Map<String, MessageAttributeValue> = emptyMap()`
- `messageGroupId: String? = null`
- `messageDeduplicationId: String? = null`

Validation:

- `topicArn` and `message` must not be blank.
- If `topicArn` ends with `.fifo`, `messageGroupId` must not be blank.
- If `topicArn` does not end with `.fifo`, `messageGroupId` and `messageDeduplicationId` must be null.

### SnsFifoThroughputScope

Enum values:

- `TOPIC`, serialized to AWS SNS attribute value `Topic`
- `MESSAGE_GROUP`, serialized to AWS SNS attribute value `MessageGroup`

### SnsOperations

Methods:

- `suspend fun createTopic(topicName: String, attributes: Map<String, String> = emptyMap()): String`
- `suspend fun createFifoTopic(
    topicName: String,
    contentBasedDeduplication: Boolean = true,
    fifoThroughputScope: SnsFifoThroughputScope? = null,
    attributes: Map<String, String> = emptyMap(),
): String`
- `suspend fun createConfiguredTopic(topicName: String): String`
- `suspend fun findTopicArn(topicName: String): String?`
- `suspend fun publish(request: SnsPublishRequest): PublishResponse`

Validation:

- `topicName` must not be blank.
- FIFO topic names must end with `.fifo`.
- `findTopicArn` must traverse all SNS `ListTopics` pages before returning null.
- `createConfiguredTopic` delegates to SNS `CreateTopic`. AWS returns the existing topic ARN for an existing name; this method does not reconcile changed attributes on an existing topic.

## Auto-Configuration

`SnsAutoConfiguration` must:

- Run after `AwsAutoConfiguration`.
- Use string-based `@ConditionalOnClass` for `SdkAsyncHttpClient` and `SnsAsyncClient`, because SNS SDK is `compileOnly`.
- Use `@ConditionalOnProperty(prefix = "bluetape4k.aws.sns", name = ["enabled"], havingValue = "true", matchIfMissing = true)`.
- Enable `SnsProperties`.
- Register `SnsAsyncClient` with destroy method `close`.
- Use custom `AwsCredentialsProvider` and `SdkAsyncHttpClient` beans when available.
- Apply `region` and `endpointOverride` from properties.
- Register `SnsCoroutinesTemplate` as `SnsOperations` with `@ConditionalOnMissingBean(SnsOperations::class)`.

## Fanout Boundary

Issue #4 should prove that the SNS publisher can participate in SQS-SNS fanout, but issue #13 owns the full example application. This PR will add one focused integration test or README snippet showing an SNS topic publishing path that can target an SQS subscription. If LocalStack subscription policy behavior is unstable, the PR may keep fanout as documented usage and rely on standard/FIFO publish integration tests for executable coverage.

The preferred executable proof is a single LocalStack SNS-to-SQS test: create a topic, create a queue, subscribe the queue ARN to the topic, publish through `SnsOperations`, and receive the message from SQS. If this proves unstable in CI, keep the test explicitly documented with the observed blocker and retain the README snippet as the issue #4 fanout example while leaving the full application flow to issue #13.

## Tests

ApplicationContextRunner tests:

- Registers `SnsAsyncClient` and `SnsOperations` when SNS SDK classes are present.
- Does not register when `bluetape4k.aws.sns.enabled=false`.
- Backs off for custom `SnsAsyncClient`.
- Backs off for custom `SnsOperations`.
- Fails validation when `endpointOverride` is set without `region`.
- Does not register when `SnsAsyncClient` is filtered out.
- Validates FIFO topic configuration names and throughput scope.

LocalStack tests:

- Creates a standard topic and looks it up by name.
- Publishes a standard message and returns a `PublishResponse.messageId`.
- Creates a FIFO topic and publishes with `messageGroupId`, asserting `messageId` and, when available, `sequenceNumber`.
- Creates a configured topic from properties.
- Rejects FIFO-only publish fields on a standard topic before calling AWS.
- Propagates AWS publish errors for invalid/non-existent topic ARNs.
- Verifies SNS-to-SQS fanout if LocalStack behavior is stable in the current test stack.

## README Updates

Update both `README.md` and `README.ko.md`:

- Add `software.amazon.awssdk:sns` runtime dependency to `aws-spring-boot` examples.
- Add `bluetape4k.aws.sns` configuration snippet.
- Add coroutine publish example using `SnsOperations` and `SnsPublishRequest`.
- Mention that the complete SQS-SNS application example is tracked separately by issue #13.

## Acceptance Criteria

- `aws-spring-boot` compiles with SNS SDK as `compileOnly`.
- SNS auto-configuration is listed in `AutoConfiguration.imports`.
- Public API has English KDoc, because KDoc is contributor-facing public
  documentation under the workspace language policy.
- Targeted `aws-spring-boot` tests pass.
- Strict code review has no unresolved correctness, cancellation, Spring Boot, or public API blockers.
- PR is opened against `develop`, assigned to `debop`, and linked to issue #4.

## Spec Review Notes

- Claude Code advisor review artifact: `.omx/artifacts/ask-claude-issue-4-sns-spec-review.md`.
- Resolved blocker: FIFO-only publish fields are now rejected for standard topics.
- Resolved high-risk notes: `findTopicArn` pagination, SDK default region behavior, configured topic idempotency, and FIFO option duplication are specified.
- Remaining implementation risk: LocalStack SNS-to-SQS fanout can be environment-sensitive; the implementation must attempt an executable integration test and document any CI blocker if it cannot be made stable.
