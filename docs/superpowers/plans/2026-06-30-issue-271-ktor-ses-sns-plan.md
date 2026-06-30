# Ktor SES v2 and SNS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add SES v2 and SNS integration APIs to `bluetape4k-aws-ktor` for issue #271.

**Architecture:** Follow the existing `CloudWatchKtorPlugin` pattern: service operations, template, runtime, plugin config, plugin, and application accessors. Ktor gets local Spring-free SES/SNS value objects; Spring public APIs are not migrated in this PR.

**Tech Stack:** Kotlin 2.3, Java 25, Gradle Kotlin DSL, AWS SDK Java v2 SES v2/SNS, Ktor 3, JUnit 5, MockK, bluetape4k-assertions, Floci/LocalStack where reliable.

---

## Preconditions

- Feature worktree:
  `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat-aws-ktor-ses-sns`
- Branch: `feat/aws-ktor-ses-sns`
- Base: `origin/develop` at `c93f7d5`
- Issue: #271, milestone `0.5.0`, assignee `debop`
- Required skills loaded before implementation:
  - `bluetape4k-workflow`
  - `bluetape4k-full-feature`
  - `bluetape4k-code-patterns`
  - `ecc-kotlin-testing`
  - `test-driven-development`
  - `verification-before-completion`
  - `bluetape4k-diagram`
  - `bluetape4k-blog`

## File Map

### Modify

- `aws-ktor/build.gradle.kts`: add `libs.aws2.sesv2` and `libs.aws2.sns` as
  `compileOnly` plus `testImplementation`.
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/AwsKtorCore.kt`: add SES v2
  and SNS shared customizer support.
- `README.md`, `README.ko.md`: update service coverage text/chart references if
  needed.
- `aws-ktor/README.md`, `aws-ktor/README.ko.md`: document SES v2 and SNS usage.
- `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg`
  and matching PNG: mark `aws-ktor` SES/v2 and SNS support.

### Create

- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorOperations.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorModels.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorTemplate.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorRuntime.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorPluginConfig.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorPlugin.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorOperations.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorModels.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsHttpMessageParser.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorTemplate.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorRuntime.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorPluginConfig.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorPlugin.kt`
- `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorPluginTest.kt`
- `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorTemplateTest.kt`
- `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sns/SnsHttpMessageParserTest.kt`
- `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorPluginTest.kt`
- `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorTemplateTest.kt`
- `docs/review/2026-06-30-issue-271-ktor-ses-sns-code-review.md`
- `docs/lessons/2026-06-30-issue-271-ktor-ses-sns.md`

## Task 1: Dependencies and shared defaults

complexity: medium
skill: `bluetape4k-code-patterns`

- [ ] Add `compileOnly(libs.aws2.sesv2)` and `compileOnly(libs.aws2.sns)` to
  `aws-ktor/build.gradle.kts`.
- [ ] Add matching `testImplementation` dependencies.
- [ ] Extend `AwsKtorDefaults` constructor with:
  - `sesV2AsyncClientCustomizers: List<AwsKtorSesV2AsyncClientCustomizer>`
  - `snsAsyncClientCustomizers: List<AwsKtorSnsAsyncClientCustomizer>`
- [ ] Store both lists as transient values, expose public getters, include them
  in `equalProperties`, `hashCode`, and `buildStringHelper`.
- [ ] Extend `AwsKtorCoreConfig` with mutable customizer lists and public DSL
  methods:
  - `sesV2AsyncClient { ... }`
  - `snsAsyncClient { ... }`
- [ ] Add fun interfaces for `SesV2AsyncClientBuilder` and
  `SnsAsyncClientBuilder`.
- [ ] Add tests in `AwsKtorCoreTest` proving defaults retain both customizer
  lists and equality/hash string behavior stays consistent.
- [ ] RED command:
  `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.AwsKtorCoreTest'`
- [ ] GREEN command:
  same command passes after implementation.

## Task 2: SES v2 Ktor operations

complexity: high
skill: `bluetape4k-code-patterns`, `ecc-kotlin-testing`, `test-driven-development`

- [ ] Write failing tests in `SesKtorPluginTest` for:
  - injected operations stored in `Application.attributes`;
  - disabled plugin stores no operations;
  - injected client remains application-owned;
  - Ktor `ApplicationStopping` closes plugin-owned client exactly once;
  - repeated stopping is idempotent;
  - injected clients remain open after `ApplicationStopping`;
  - plugin-created client is built once at install/startup and reused across
    multiple operation calls;
  - shared customizer runs before service customizer.
- [ ] Write failing tests in `SesKtorTemplateTest` for:
  - simple email request maps destination, subject, body, sender, reply-to,
    headers, attachments, and configuration set;
  - template email maps template name or ARN;
  - raw email maps SDK bytes and optional destination;
  - default `from` is required when request omits sender;
  - AWS SDK exceptions propagate without generic wrapping;
  - coroutine cancellation cancels or stops awaiting an in-flight
    `CompletableFuture`.
- [ ] Name the SES cancellation tests per suspend API:
  - `sendEmail cancels the backing future when coroutine is cancelled`;
  - `sendTemplateEmail cancels the backing future when coroutine is cancelled`;
  - `sendRawEmail cancels the backing future when coroutine is cancelled`;
  - `send raw SDK request cancels the backing future when coroutine is cancelled`.
- [ ] Add negative validation tests for email header injection:
  - custom header names reject CR, LF, NUL, colon, whitespace, and non-token
    characters;
  - header values reject CR, LF, and NUL;
  - `from`, `replyTo`, raw-email metadata, and attachment metadata reject CR,
    LF, and NUL.
- [ ] Add tests or review checklist coverage that `toString` and validation
  exceptions do not include raw email bytes, attachment bytes, recipient lists,
  AWS credentials, SNS tokens, or signatures.
- [ ] Add failing SES model tests for:
  - body text validation and default sender validation;
  - defensive byte-array copying for raw email and attachments;
  - the 40 MB attachment limit.
- [ ] Implement every SES SDK async call with `CompletableFuture.await()` or an
  existing repo coroutine helper. Do not use `get()`, `join()`, `runBlocking`,
  or blocking waits in SES operation paths.
- [ ] Add named cancellation tests using controllable `CompletableFuture`s for:
  - `sendEmail`;
  - `sendTemplateEmail`;
  - `sendRawEmail`;
  - raw `send(SendEmailRequest)`.
- [ ] Add named exceptional-future tests proving SES SDK failures preserve the
  original error contract for simple, template, raw, and raw SDK requests.
- [ ] Before adding any Ktor-local SES coroutine wrapper, check whether an
  existing `aws-java` helper can express the same await/error/cancellation
  contract; add only a narrow SES helper when it removes duplication without
  importing Ktor concerns into `aws-java`.
- [ ] Implement `SesKtorOperations` with suspending methods for SES value
  objects and raw `SendEmailRequest`.
- [ ] Implement `SesKtorModels` adapted from Spring SES models. Keep value
  objects `Serializable`; copy byte arrays defensively; validate CR/LF/NUL
  header values; preserve SES 40 MB attachment limit.
- [ ] Keep byte-array copying to the public trust boundary. Review raw email and
  attachment mapping to avoid extra large-buffer copies beyond the defensive
  model copy when building `SdkBytes`.
- [ ] Implement `SesKtorTemplate` over `SesV2AsyncClient`.
- [ ] Implement `SesKtorRuntime`, `SesKtorPluginConfig`, and `SesKtorPlugin`.
- [ ] Add English KDoc to public classes, interfaces, and accessors.
- [ ] RED commands:
  - `./gradlew :bluetape4k-aws-ktor:test --tests '*SesKtorPluginTest'`
  - `./gradlew :bluetape4k-aws-ktor:test --tests '*SesKtorTemplateTest'`
- [ ] GREEN commands:
  same commands pass after implementation.

## Task 3: SNS Ktor operations and HTTP parser

complexity: high
skill: `bluetape4k-code-patterns`, `ecc-kotlin-testing`, `test-driven-development`

- [ ] Write failing tests in `SnsKtorPluginTest` for:
  - injected operations stored in `Application.attributes`;
  - disabled plugin stores no operations;
  - injected client remains application-owned;
  - Ktor `ApplicationStopping` closes plugin-owned client exactly once;
  - repeated stopping is idempotent;
  - injected clients remain open after `ApplicationStopping`;
  - plugin-created client is built once at install/startup and reused across
    multiple operation calls;
  - shared customizer runs before service customizer.
- [ ] Write failing tests in `SnsKtorTemplateTest` for:
  - standard topic creation;
  - FIFO topic creation attributes;
  - topic lookup across paged `ListTopics`;
  - topic publish request mapping;
  - SMS publish request mapping;
  - confirm subscription from explicit token and caller-verified trusted HTTP
    message;
  - AWS SDK exceptions propagate without generic wrapping;
  - coroutine cancellation cancels or stops awaiting an in-flight
    `CompletableFuture`.
- [ ] Name the SNS cancellation tests per suspend API:
  - `createTopic cancels the backing future when coroutine is cancelled`;
  - `createFifoTopic cancels the backing future when coroutine is cancelled`;
  - `findTopicArn cancels paged listing when coroutine is cancelled`;
  - `publish cancels the backing future when coroutine is cancelled`;
  - `publishSms cancels the backing future when coroutine is cancelled`;
  - `confirmSubscription cancels the backing future when coroutine is cancelled`.
- [ ] Add named negative tests for SNS request constraints:
  - `SnsPublishRequest` rejects blank topic ARN and blank message;
  - standard-topic publish rejects FIFO-only fields;
  - FIFO-topic publish requires `messageGroupId`;
  - `SnsSmsRequest` rejects blank phone number and blank message;
  - SMS-only fields are modeled only on `SnsSmsRequest`, not topic publish.
- [ ] Add tests proving parsed untrusted `SnsHttpMessage` cannot be passed
  directly to a state-changing confirmation helper. The message-based helper
  accepts only `TrustedSnsHttpMessage`, created by an explicit caller-verified
  wrapper/factory.
- [ ] Implement every SNS SDK async call with `CompletableFuture.await()` or an
  existing repo coroutine helper. Do not use `get()`, `join()`, `runBlocking`,
  or blocking waits in SNS operation paths.
- [ ] Reuse existing `aws-java` SNS coroutine extensions when their request and
  error contracts match the Ktor API. If a Ktor-local wrapper is still needed,
  document the mismatch in the implementation comment or review artifact.
- [ ] Add named cancellation tests using controllable `CompletableFuture`s for:
  - `createTopic`;
  - `createFifoTopic`;
  - `findTopicArn`;
  - `publish`;
  - `publishSms`;
  - `confirmSubscription`.
- [ ] Add named exceptional-future tests proving SNS SDK failures preserve the
  original error contract for create topic, list topics, publish, SMS publish,
  and confirm subscription paths.
- [ ] Write failing tests in `SnsHttpMessageParserTest` adapted from Spring
  parser tests for:
  - subscription confirmation payload;
  - notification payload;
  - unsubscribe confirmation payload;
  - header and JSON type mismatch rejection;
  - confirmation payload without token rejection;
  - non-HTTPS signing certificate URL rejection;
  - non-SNS signing certificate host rejection;
  - signing certificate path that does not end with `.pem` rejection;
  - deceptive hosts such as `sns.us-west-2.amazonaws.com.evil.example`;
  - userinfo in URL;
  - custom ports;
  - query or fragment;
  - wrong certificate path;
  - region mismatch between `TopicArn` and `SigningCertURL`;
  - partition mismatch between `TopicArn` and `SigningCertURL`;
  - malformed JSON;
  - non-object JSON;
  - oversized JSON body;
  - duplicate security-sensitive fields such as `Type`, `TopicArn`,
    `SigningCertURL`, or `Signature`;
  - missing required fields;
  - required fields with non-string scalar/object/array values.
- [ ] Implement `SnsKtorOperations`, models, parser, template, runtime,
  config, and plugin.
- [ ] Use Spring-free JSON parsing with Jackson 3
  `tools.jackson.databind.ObjectMapper`, which is already available to
  `aws-ktor` through `compileOnly(libs.bluetape4k.jackson3)`.
- [ ] Keep parser construction explicit enough for optional-runtime users:
  expose a default parser and an overload/factory that accepts an application
  `ObjectMapper` when callers already manage one.
- [ ] Do not use ad hoc string parsing or regex parsing for SNS HTTP JSON.
  Parse only to a JSON tree/object map, cap the raw body size before parsing,
  and extract only string fields needed by SNS.
- [ ] Keep parser KDoc explicit: it validates structure and certificate URL
  shape, not cryptographic signatures.
- [ ] Add KDoc/README warning that `findTopicArn` paginates SNS topics and
  should not run per message on hot publish paths; callers should cache topic
  ARNs for repeated publishing.
- [ ] Add invariant tests or review checks that SNS HTTP payload/header fields
  never influence AWS client region, endpoint override, credentials, or
  customizers. Those values come only from Ktor/AWS application configuration or
  injected clients.
- [ ] RED commands:
  - `./gradlew :bluetape4k-aws-ktor:test --tests '*SnsKtorPluginTest'`
  - `./gradlew :bluetape4k-aws-ktor:test --tests '*SnsKtorTemplateTest'`
  - `./gradlew :bluetape4k-aws-ktor:test --tests '*SnsHttpMessageParserTest'`
- [ ] GREEN commands:
  same commands pass after implementation.

## Task 4: Reliable emulator proof

complexity: medium
skill: `ecc-kotlin-testing`

- [ ] Inspect existing `aws-ktor` Floci/LocalStack tests for SQS/S3/DynamoDB
  patterns and environment flags.
- [ ] Run the Floci-first SNS smoke check serially:
  `./gradlew :bluetape4k-aws-ktor:test -Dbluetape4k.aws.emulator=floci --tests '*Sns*Ktor*LocalStackTest' --no-build-cache`
  after creating the emulator-backed test class, or record that no matching
  class was added because Floci does not support the required SNS operation set.
- [ ] Required SNS emulator operation set: create standard topic, publish to the
  topic, and confirm the SDK response contains a nonblank message id. If any of
  these operations is unsupported by Floci, classify Floci SNS as unsupported
  rather than partially passing.
- [ ] If Floci is unsupported, run one serial LocalStack fallback command:
  `./gradlew :bluetape4k-aws-ktor:test -Dbluetape4k.aws.emulator=localstack --tests '*Sns*Ktor*LocalStackTest' --no-build-cache`.
- [ ] Treat emulator verification as flaky if the same command has inconsistent
  pass/fail results across two immediate serial runs without source changes.
  Do not merge flaky skipped tests; document exact command, emulator, result,
  and unsupported/flaky reason in PR DoD.
- [ ] Do not add SES emulator proof unless SES v2 `SendEmail` is supported
  reliably by the current emulator path under the same serial two-run rule.
- [ ] If emulator support is unavailable or flaky, document the gap in the PR
  DoD and lesson rather than adding a false-positive or skipped test.
- [ ] Run Testcontainers/emulator commands serially only.

## Task 5: README locale set and coverage chart

complexity: medium
skill: `bluetape4k-code-patterns`, `bluetape4k-blog`, `bluetape4k-diagram`

- [ ] Update root README and Korean README service coverage text/chart embeds if
  the chart source changes.
- [ ] Update `aws-ktor/README.md` and `aws-ktor/README.ko.md` feature bullets,
  dependency snippets, shared defaults section, SES usage example, SNS usage
  example, and SNS HTTP parsing warning.
- [ ] README snippets must include runtime `software.amazon.awssdk:sesv2` and
  `software.amazon.awssdk:sns` dependencies because `aws-ktor` keeps them
  optional/compileOnly.
- [ ] README examples must show region, local Floci/LocalStack endpoint
  override, dummy local test credentials, and a production credential-provider
  caveat.
- [ ] README/KDoc SNS HTTP examples must label parsed messages as untrusted
  until caller-owned cryptographic signature verification succeeds; examples
  must not process or confirm the parsed message before verification.
- [ ] README warnings must cover SES sandbox and verified identity constraints,
  region-specific SES identities, the SES 40 MB attachment limit, and local
  emulator limitations for SES/SNS.
- [ ] README diagnostics guidance must mention SES `messageId`, SNS publish or
  confirm response ids where available, and AWS SDK request metadata/error
  surfaces for failed sends/publishes.
- [ ] README/KDoc rollback guidance must say callers can disable the plugins,
  inject application-owned clients/operations, or fall back to raw AWS SDK
  clients; injected clients remain caller-owned and are not closed by the
  plugin.
- [ ] Add a README parity checklist: `README.md`/`README.ko.md` and
  `aws-ktor/README.md`/`aws-ktor/README.ko.md` must contain matching feature
  bullets, dependency snippets, SES/SNS examples, SNS trust warning, SES
  emulator caveat when applicable, chart surrounding text, and language switch.
- [ ] Ensure root README chart surrounding text or caption names `aws-ktor` SES
  v2 and SNS support and links readers to `aws-ktor/README.md`; mirror the
  searchable text in `README.ko.md`.
- [ ] Update the SVG service coverage chart and render the PNG using the repo
  diagram workflow.
- [ ] Validate SVG XML.
- [ ] Render PNG with CairoSVG.
- [ ] Inspect the touched PNG at full size.
- [ ] Grep README API names against source before final docs claim:
  `rg -n 'SesKtorPlugin|SnsKtorPlugin|SesKtorOperations|SnsKtorOperations|SnsHttpMessageParser' aws-ktor/src/main/kotlin aws-ktor/README.md aws-ktor/README.ko.md`

## Task 6: Module verification

complexity: medium
skill: `verification-before-completion`

- [ ] Run targeted tests:
  `./gradlew :bluetape4k-aws-ktor:test --tests '*Ses*' --tests '*Sns*'`
- [ ] Run explicit cancellation-focused tests:
  `./gradlew :bluetape4k-aws-ktor:test --tests '*SesKtorTemplateTest*cancel*' --tests '*SnsKtorTemplateTest*cancel*'`
- [ ] Run full affected module tests:
  `./gradlew :bluetape4k-aws-ktor:test`
- [ ] Run compile gates:
  `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:compileTestKotlin --warning-mode all`
- [ ] Run static/build readiness gates:
  - `./gradlew detekt`
  - `./gradlew build -x test --parallel`
- [ ] Run non-remote publication metadata verification:
  `./gradlew :bluetape4k-aws-ktor:generateMetadataFileForBluetapeAwsPublication :bluetape4k-aws-ktor:generatePomFileForBluetapeAwsPublication`
- [ ] Run whitespace check:
  `git diff --check`
- [ ] If README chart changed, include diagram validation ledger in review and
  PR DoD.

## Task 7: Review, lesson, commit, PR

complexity: high
skill: `bluetape4k-full-feature`, `verification-before-completion`

- [ ] Run Step 5 verifier against this spec and plan.
- [ ] Run Step 6-R 7-Tier code review for `:bluetape4k-aws-ktor`.
- [ ] Store review artifact at
  `docs/review/2026-06-30-issue-271-ktor-ses-sns-code-review.md`.
- [ ] Fix every P0/P1 and rerun affected tests/review lanes until P0 = 0 and
  P1 = 0.
- [ ] Write `docs/lessons/2026-06-30-issue-271-ktor-ses-sns.md`.
- [ ] Commit with Lore trailers.
- [ ] Push branch and create PR with:
  - title in English;
  - `Closes #271`;
  - assignee `debop`;
  - milestone `0.5.0`;
  - issue labels mirrored where GitHub permits;
  - final Markdown section exactly `## DoD Status`.
- [ ] Verify live PR metadata and body:
  `gh pr view <number> --json body,assignees,milestone,labels`
- [ ] Run Step 7-R post-PR review.
- [ ] Wait for CI or inspect statusCheckRollup until all required checks are
  `SUCCESS` or `SKIPPED`.
- [ ] Deliver Step 9 DoD report and ask the user for merge.

## Rollback Points

- If SES/SNS Ktor models create excessive duplicate API surface, stop before
  implementation and revise the spec toward common-model extraction.
- If JSON parsing needs a new dependency, stop and evaluate whether the
  dependency is justified or whether a narrower parser should be implemented.
- If emulator-backed SNS or SES tests prove unreliable, keep deterministic unit
  tests and document the emulator gap rather than merging skipped/flaky tests.

## Acceptance Mapping

| Spec criterion | Plan task |
|---|---|
| SES/SNS dependencies | Task 1 |
| Shared defaults customizers | Task 1 |
| SES plugin and operations | Task 2 |
| SNS plugin and operations | Task 3 |
| SNS HTTP parsing | Task 3 |
| Emulator-backed proof or documented gap | Task 4 |
| README locale set and chart | Task 5 |
| Tests and compile verification | Task 6 |
| Review, lesson, PR, CI, DoD | Task 7 |
