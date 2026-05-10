# AWS Spring Boot SQS Implementation Plan

Date: 2026-05-10 KST
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/2
Spec: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/2-spring-boot-sqs/docs/superpowers/specs/2026-05-10-aws-spring-boot-sqs-design.md`
Worktree: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/2-spring-boot-sqs`
Branch: `feat/2-spring-boot-sqs`

## Implementation Strategy

Implement `#2` in one PR, but split commits:

1. spec/plan commit
2. SQS implementation + tests + docs commit

Keep the implementation aligned with the merged S3 Spring Boot structure:

- `io.bluetape4k.aws.spring.sqs` package
- `SqsAutoConfiguration` registered in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- AWS SDK SQS dependency remains `compileOnly`
- Spring owns `SqsAsyncClient` bean lifecycle
- LocalStack tests run under `aws-spring-boot`

## Task List

### 1. Build and Auto-Configuration Registration

Files:

- `aws-spring-boot/build.gradle.kts`
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

Changes:

- Add `compileOnly(libs.aws2.sqs)`.
- Add `testImplementation(libs.aws2.sqs)`.
- Register `io.bluetape4k.aws.spring.sqs.SqsAutoConfiguration`.

Checks:

- `./gradlew :aws-spring-boot:compileKotlin --no-daemon`

### 2. Properties and Model Types

Files under:

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/`

Add:

- `SqsProperties.kt`
- `SqsReceivedMessage.kt`
- `SqsOperations.kt`

Details:

- `SqsProperties` validates:
  - endpoint override requires region
  - max messages `1..10`
  - wait time `0..20`
  - visibility timeouts `0..43_200`
  - concurrency `>= 1`
  - stop timeout `>= 1`
  - redrive policy target ARN nonblank and max receive count `>= 1`
- `SqsReceivedMessage` exposes queue URL, AWS `Message`, body, receipt handle.
- `SqsOperations` defines:
  - `getQueueUrl`
  - `createQueue`
  - `createConfiguredQueue`
  - `send`
  - `receive`
  - `delete`
  - `changeVisibility`
  - `receiveFlow`

Checks:

- KDoc in Korean for public API.
- No production `runBlocking`, `Thread.sleep`, `GlobalScope`.

### 3. SQS Client Auto-Configuration Foundation

File:

- `SqsAutoConfiguration.kt`

Details:

- Annotate with:
  - `@AutoConfiguration(after = [AwsAutoConfiguration::class])`
  - string `@ConditionalOnClass` for:
    - `software.amazon.awssdk.http.async.SdkAsyncHttpClient`
    - `software.amazon.awssdk.services.sqs.SqsAsyncClient`
  - `@ConditionalOnProperty(prefix = "bluetape4k.aws.sqs", name = ["enabled"], havingValue = "true", matchIfMissing = true)`
  - `@EnableConfigurationProperties(SqsProperties::class)`
- Bean methods:
  - `SqsAsyncClient` only in this foundation step
- Use `ObjectProvider<AwsCredentialsProvider>` with
  `DefaultCredentialsProvider.builder().build()` fallback.
- Use optional `ObjectProvider<SdkAsyncHttpClient>`.
- Apply `region` and `endpointOverride` from properties.
- If `region` is absent and no endpoint override is configured, use the AWS SDK
  default region provider chain.
- If `endpointOverride` is configured, `SqsProperties` requires `region`.
- Declare the SQS client bean with `@Bean(destroyMethod = "close")` so Spring
  owns shutdown explicitly.

Backoff rules:

- Back off for custom `SqsAsyncClient`.

Compile after this step before adding beans that depend on later listener types.

### 4. Template Implementation

File:

- `SqsCoroutinesTemplate.kt`

Details:

- Delegate to existing `io.bluetape4k.aws.sqs` coroutine extensions where possible.
- Implement receive with explicit wait time, visibility timeout, and max messages:
  - `receiveMessage { queueUrl(...); maxNumberOfMessages(...); waitTimeSeconds(...) }`
  - set `visibilityTimeout(...)` when `visibilityTimeoutSeconds` is non-null
  - wrap results as `SqsReceivedMessage`
- Implement `createConfiguredQueue`:
  - resolve `SqsProperties.queues[queueName]`
  - if `redrivePolicy` exists, set `QueueAttributeName.REDRIVE_POLICY`
  - build JSON string locally without adding a JSON dependency:
    `{"deadLetterTargetArn":"...","maxReceiveCount":"..."}`
- Implement `receiveFlow` as cold infinite flow:
  - loop while active
  - emit received messages
  - do not delete automatically
  - rethrow `CancellationException`

Checks:

- Compile after this step.
- Unit tests for validation and simple template methods.

### 5. Listener Annotation and Endpoint Parsing

Files:

- `SqsListener.kt`
- `SqsListenerEndpoint.kt`
- `SqsListenerMethodInvoker.kt`
- `SqsListenerAnnotationBeanPostProcessor.kt`

Details:

- `@SqsListener` targets functions and retains runtime.
- Resolve `${...}` placeholders with `Environment.resolvePlaceholders`.
- Reject SpEL strings containing `#{`.
- Discover annotations on `AopUtils.getTargetClass(bean)`.
- Invoke through the proxy bean, not the target class instance.
- Supported signatures:
  - `String`
  - AWS `Message`
  - `SqsReceivedMessage`
  - same for `suspend` one-parameter functions
- Fail fast for:
  - no parameters
  - more than one parameter
  - unsupported parameter type
  - SpEL queue/id values
- Assign endpoint id:
  - annotation id if nonblank
  - otherwise `${beanName}.${methodName}.${queue}`

Implementation note:

- Use Kotlin reflection for suspend detection/invocation because root
  subprojects already depend on `kotlin-reflect`.
- Catch `CancellationException` before generic `Throwable`.
- If reflection wraps handler failures in `InvocationTargetException`, unwrap it;
  if the target cause is `CancellationException`, rethrow it.

### 6. Listener Container and Registry

Files:

- `SqsMessageListenerContainer.kt`
- `SqsMessageListenerContainerRegistry.kt`

Details:

- Container implements `SmartLifecycle`.
- Constructor dependencies:
  - endpoint
  - `SqsOperations`
  - effective listener properties
  - method invoker
- `phase` defaults to `Int.MAX_VALUE`.
- `start()`:
  - resolve queue URL once and cache it
  - create `SupervisorJob`
  - launch `concurrency` polling coroutines on `Dispatchers.IO`
- Polling loop:
  - call `operations.receive`
  - pass the effective `visibilityTimeoutSeconds` into receive
  - process messages sequentially within a batch
  - on success call `operations.delete`
  - on handler failure:
    - rethrow `CancellationException`
    - if error visibility timeout is configured, call `changeVisibility`
    - otherwise do not delete and do not change visibility
- `stop(callback)`:
  - prevent new receives
  - cancel job
  - wait up to `stopTimeoutMillis`
  - call callback regardless
- Registry implements `SmartLifecycle` and starts/stops all registered containers.

### 6b. Complete SQS Auto-Configuration Wiring

Return to:

- `SqsAutoConfiguration.kt`

Add the remaining beans only after Task 4, Task 5, and Task 6 types exist:

- `SqsCoroutinesTemplate`
- `SqsMessageListenerContainerRegistry`
- `SqsListenerAnnotationBeanPostProcessor`

Backoff rules:

- `SqsCoroutinesTemplate` uses `@ConditionalOnMissingBean(SqsOperations::class)`.
- registry uses `@ConditionalOnMissingBean`.
- annotation BPP uses `@ConditionalOnMissingBean`.

Checks:

- `./gradlew :aws-spring-boot:compileKotlin --no-daemon`

Tests:

- ack deletes message after successful handler.
- failed handler leaves message available after visibility timeout.
- stop returns within timeout.
- concurrency creates expected number of polling loops indirectly via processing
  multiple messages with `concurrency=2`.

### 7. Auto-Configuration Tests

Files:

- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsAutoConfigurationTest.kt`
- `NoopSqsOperations.kt`
- `aws-spring-boot/src/test/resources/logback-test.xml`

Use `ApplicationContextRunner`.

Test cases:

- registers `SqsAsyncClient`, `SqsProperties`, `SqsOperations`,
  `SqsCoroutinesTemplate`, registry, and BPP.
- backs off when `bluetape4k.aws.sqs.enabled=false`.
- backs off for custom `SqsOperations`.
- endpoint override without region fails.
- endpoint override URI binding is accepted when region is present.
- `FilteredClassLoader` removing SQS classes disables SQS auto-config.
- invalid listener properties fail binding.
- context close invokes `SqsAsyncClient.close()` on auto-configured/custom spy
  client when feasible.
- `${...}` placeholder values resolve.
- SpEL queue/id values fail fast.
- Add `<logger name="io.bluetape4k.aws.spring.sqs" level="DEBUG"/>`.

### 8. LocalStack Integration Tests

Files:

- `SqsCoroutinesTemplateLocalStackTest.kt`
- `SqsListenerLocalStackTest.kt`

Patterns:

- Use `LocalStackServer().withServices("sqs")`.
- Register `AwsCredentialsProvider` bean with `localStack.getCredentialProvider()`.
- Set:
  - `bluetape4k.aws.sqs.region=${localStack.regionName}`
  - `bluetape4k.aws.sqs.endpoint-override=${localStack.awsEndpoint}`
  - small wait/visibility values for test speed

Test cases:

- template create/send/receive/delete.
- `receiveFlow` emits and requires explicit delete.
- listener handles `String` body and deletes on success.
- suspend listener handles `SqsReceivedMessage`.
- failing listener does not delete; message can be received again after short
  visibility timeout.
- `concurrency=2` processes multiple messages through two polling coroutines
  without creating more than the configured number of loops.
- placeholder queue value resolves from property.
- proxied bean listener is discovered.
- unsupported signature fails context startup.

### 9. Documentation

Files:

- `README.md`
- `README.ko.md`

Add:

- Gradle dependency snippet for `aws-spring-boot` + `software.amazon.awssdk:sqs`.
- YAML properties:
  - region
  - endpoint override
  - listener max messages, wait time, concurrency
- `SqsOperations` example.
- `@SqsListener` sync and suspend examples.
- at-least-once/idempotent handler warning.

### 10. Verification

Run:

```bash
./gradlew :aws-spring-boot:compileKotlin --no-daemon
./gradlew :aws-spring-boot:test --no-daemon
./gradlew :aws-spring-boot:koverHtmlReport --no-daemon
./gradlew detekt --parallel --no-daemon
./gradlew build -x test --parallel --no-daemon
rg 'runBlocking|Thread\\.sleep|GlobalScope' aws-spring-boot/src/main/kotlin
rg 'CancellationException' aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs
rg 'SqsClientFactory' aws-spring-boot/src/main/kotlin
yq e '.' .github/workflows/nightly.yml >/dev/null
git diff --check
```

If `:aws-spring-boot:detekt` is unavailable, use root `detekt` and record that.

### 11. Review and Cleanup

- Run local code review pass focused on:
  - lifecycle cancellation
  - message deletion only after success
  - optional classpath safety
  - listener signature validation
  - public KDoc and README consistency
- Remove duplicated helper code if it can be safely shared with S3 later only
  when it does not broaden this PR.
- Do not refactor S3 in this PR unless compilation requires it.

### 12. Commit and PR

Commit 1:

- spec/plan only
- Lore commit protocol
- `Co-authored-by: OmX <omx@oh-my-codex.dev>`

Commit 2:

- implementation/tests/docs
- Lore commit protocol
- `Co-authored-by: OmX <omx@oh-my-codex.dev>`

PR:

- Title: `[feat] Add Spring Boot SQS integration`
- Body in Korean.
- Include `Closes #2`.
- Include verification commands and test outcome.

## Risks

| Risk | Mitigation |
|---|---|
| Kotlin reflection invocation edge cases | Keep supported signatures narrow and fail fast. |
| Shutdown test flakiness due AWS long polling | Use low wait/visibility values and `stopTimeoutMillis`. |
| LocalStack queue timing | Use unique queue names and direct receives with short polling in assertions. |
| Overbuilding awspring clone | Keep payload conversion/manual ack/metrics/heartbeat out of first PR. |
| Optional classpath failure | Add FilteredClassLoader test before implementation is considered done. |

## Step 3 Checklist

| Item | Status | Notes |
|---|---|---|
| Tasks ordered by dependency | Done | Client auto-config foundation before template/listener, final auto-config wiring after listener types exist. |
| Verification commands included | Done | Targeted and broad checks listed. |
| Advisor review pending | Done | Claude advisor reviewed and edits are integrated. |
| Commit/PR protocol included | Done | Lore commit, Korean PR body, `[feat]` title. |

## Claude Code Opus Advisor

Artifact:
`/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/2-spring-boot-sqs/.omx/artifacts/ask-claude-aws-spring-boot-sqs-plan-20260510-185205.md`

Model: `${CLAUDE_ADVISOR_MODEL:-claude-opus-4-7}`

| Severity | Finding | Decision | Follow-up |
|---|---|---|---|
| high | Auto-config task would reference types before they exist. | Accepted | Split into client foundation and 6b final wiring. |
| high | SQS client lifecycle directive missing. | Accepted | Added `@Bean(destroyMethod = "close")`. |
| high | Concurrency LocalStack test not explicit. | Accepted | Added Task 8 concurrency test. |
| high | `visibilityTimeoutSeconds` was dead config. | Accepted | Added receive/flow visibility parameter and container wiring. |
| high | New package logger missing. | Accepted | Added `logback-test.xml` update. |
| medium | Per-bean backoff ambiguous. | Accepted | Added explicit backoff rules. |
| medium | Region fallback behavior unspecified. | Accepted | Added AWS SDK default region-chain behavior. |
| medium | Reflection may wrap cancellation. | Accepted | Added `InvocationTargetException` unwrap rule. |
| low | Add `SqsClientFactory` grep. | Accepted | Added verification grep. |
