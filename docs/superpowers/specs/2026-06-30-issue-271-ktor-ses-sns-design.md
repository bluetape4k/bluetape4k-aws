# Issue #271 Ktor SES v2 and SNS Design

Date: 2026-06-30
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/271
Work type: Type A Full Feature

## Problem

`bluetape4k-aws-ktor` already has Ktor-facing helpers for shared AWS defaults,
SigV4, S3, S3 Access Grants, S3 Vectors, SQS, DynamoDB, IMDS, CloudWatch, and
CloudWatch Logs. The root README service coverage chart still marks SES/v2 and
SNS as missing for `aws-ktor`, while `aws-spring-boot` already has SES v2 and
SNS coroutine operations.

Ktor applications need the same lightweight email and messaging surface without
depending on Spring Boot. The implementation must follow the existing Ktor
plugin shape:

- application-level defaults live in `AwsKtorCore` and are inherited by service
  plugins unless service-local configuration overrides them.
- service plugins store operations in `Application.attributes`.
- plugin-created AWS Java v2 async clients are owned by the plugin runtime and
  closed on `ApplicationStopping`.
- injected clients and injected operations remain application-owned.
- plugin-created clients are built once during plugin installation, reused by
  every operation call, and closed once during shutdown.

## Current Evidence

- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/AwsKtorCore.kt` already has
  Java SDK v2 builder customizer lists for SQS, CloudWatch, CloudWatch Logs,
  S3 Control, and S3 Vectors.
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/*` provides the
  closest service pattern: `Operations`, `Template`, `Runtime`, `PluginConfig`,
  and `Plugin`.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/ses/*` has SES v2
  request value objects and a coroutine sender over `SesV2AsyncClient`.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/*` has SNS
  publish, SMS, topic, and HTTP message parsing models.
- `aws-java/src/main/kotlin/io/bluetape4k/aws/sns/*` has SNS Java SDK v2
  coroutine helpers. Existing SES helpers target SES v1, so SES v2 needs a new
  narrow helper or direct Ktor template calls.
- `gradle/libs.versions.toml` already contains `libs.aws2.sesv2` and
  `libs.aws2.sns`; `aws-ktor/build.gradle.kts` does not yet declare them.
- `aws-spring-boot` SNS HTTP parsing uses Spring Boot `JsonParserFactory`, so
  Ktor needs a Spring-free parser path.

## Scope

### In

- Add Ktor SES v2 plugin support under
  `io.bluetape4k.aws.ktor.ses`.
- Add Ktor SNS plugin support under
  `io.bluetape4k.aws.ktor.sns`.
- Add `AwsKtorDefaults` customizer support for `SesV2AsyncClientBuilder` and
  `SnsAsyncClientBuilder`.
- Add Spring-free Ktor SNS HTTP message parsing helpers backed by the existing
  optional Jackson 3 dependency already available to `aws-ktor`. The parser can
  parse SNS HTTP(S) endpoint payloads and optionally validate the
  `x-amz-sns-message-type` header against the JSON `Type`.
- Parsed SNS HTTP messages are untrusted data. State-changing helpers do not
  accept an unverified parsed message directly; confirmation by message requires
  an explicit caller-verified wrapper.
- Reuse existing `aws-java` SNS coroutine extensions where they fit. For SES
  v2, add a narrow `aws-java` coroutine extension only if it reduces duplication
  without forcing broader SES v2 helper design.
- Add tests for plugin lifecycle, request mapping, parsing validation, and
  representative send/publish behavior.
- Update root and module README locale sets and the service coverage chart.

### Out

- Full SNS signature cryptographic verification. The parser will expose
  signature fields and validate certificate URL shape, but callers must still
  perform trust validation before processing messages.
- Spring Boot public API migration. Existing Spring package models stay in
  place to avoid a broad source-compatibility change in this PR.
- New example module. README examples and plugin hooks are enough for #271
  unless implementation reveals an existing examples workflow that can be
  updated without new module registration.
- SES emulator proof if the local emulator does not reliably support SES v2.
  Any gap will be documented in the PR DoD and README notes.

## Design Options

### Option A: Ktor-local plugins with Ktor-local value objects

Create SES/SNS Ktor packages mirroring the current CloudWatch plugin pattern.
Copy/adapt the existing Spring request-model shapes where module boundaries
prevent direct reuse.

Pros:
- keeps `aws-ktor` free of Spring dependencies;
- aligns with existing Ktor plugin lifecycle and defaults;
- keeps the #271 blast radius mostly inside `aws-ktor` plus a small
  `AwsKtorCore` extension.

Cons:
- duplicates some Spring model shapes until a future common-model extraction is
  worth the compatibility cost.

### Option B: Extract common SES/SNS models into `aws-java`

Move or introduce common SES/SNS request models under non-Spring packages, then
make both Spring and Ktor use them.

Pros:
- strongest reuse story;
- avoids long-term duplicate validation rules.

Cons:
- broadens the PR into a Spring API migration;
- requires compatibility aliases or deprecation strategy;
- increases review and regression surface beyond #271.

### Option C: Expose only raw AWS SDK clients through Ktor plugins

Install SES/SNS clients and leave all request construction to application code.

Pros:
- minimal implementation.

Cons:
- does not satisfy the issue's helper and mapping requirements;
- weak discoverability compared with Spring SES/SNS support;
- repeats boilerplate in every Ktor application.

## Decision

Use Option A for this PR. Add Ktor-local SES/SNS operations and request models,
reuse `aws-java` SNS coroutine helpers where already present, and keep the
common-model extraction as a possible follow-up only if review finds the
duplication materially harmful.

## API Shape

### SES v2

- `SesKtorPlugin`
- `SesKtorPluginConfig`
- `SesKtorRuntime`
- `SesKtorOperations`
- `SesKtorTemplate`
- value objects adapted from Spring SES models:
  - `SesEmailAddressSet`
  - `SesEmailBody`
  - `SesEmailAttachment`
  - `SesEmailRequest`
  - `SesTemplateEmailRequest`
  - `SesRawEmailRequest`

The operations API will expose:

- `suspend fun sendEmail(request: SesEmailRequest): SendEmailResponse`
- `suspend fun sendTemplateEmail(request: SesTemplateEmailRequest): SendEmailResponse`
- `suspend fun sendRawEmail(request: SesRawEmailRequest): SendEmailResponse`
- `suspend fun send(request: SendEmailRequest): SendEmailResponse`

### SNS

- `SnsKtorPlugin`
- `SnsKtorPluginConfig`
- `SnsKtorRuntime`
- `SnsKtorOperations`
- `SnsKtorTemplate`
- value objects adapted from Spring SNS models:
  - `SnsPublishRequest`
  - `SnsSmsRequest`
  - `SnsSmsType`
  - `SnsFifoThroughputScope`
  - `SnsHttpMessageType`
- `SnsHttpMessage`
- `TrustedSnsHttpMessage`
  - `SnsHttpMessageParser`

The operations API will expose:

- topic creation and lookup;
- topic publish;
- direct SMS publish;
- confirmation from explicit topic/token;
- confirmation from a caller-verified `TrustedSnsHttpMessage`.

### Defaults

`AwsKtorCoreConfig` will gain:

- `fun sesV2AsyncClient(customizer: AwsKtorSesV2AsyncClientCustomizer)`
- `fun snsAsyncClient(customizer: AwsKtorSnsAsyncClientCustomizer)`

`AwsKtorDefaults` will store and expose both customizer lists. Service-local
customizers run after shared customizers, matching the CloudWatch tests.

## Failure Modes And Mitigation

1. **Spring dependency leak into `aws-ktor`**
   - Mitigation: keep all Ktor SES/SNS value objects under `aws-ktor` packages
     and parse SNS JSON with Jackson 3 rather than Spring Boot APIs.
2. **Plugin-created client lifecycle leak**
   - Mitigation: copy the existing runtime ownership model and test injected
     client versus owned client closing behavior with actual Ktor
     `ApplicationStopping` tests. Plugin-created clients are built once and
     reused; injected operations never create clients.
3. **Unsafe SNS HTTP trust assumption**
   - Mitigation: parser KDoc and README must state that signature fields are
     exposed but cryptographic verification is caller responsibility. The
     parser validates HTTPS, Amazon SNS host shape, and a certificate path
     ending in `.pem`; it does not fetch certificates or verify signatures.
     Parsed `SnsHttpMessage` values are always untrusted. Callers must perform
     their own cryptographic validation and then wrap the message as
     `TrustedSnsHttpMessage` before using message-based confirmation helpers.
4. **SES/SNS local emulator drift**
   - Mitigation: unit tests cover mapping and lifecycle; emulator-backed SNS is
     attempted with the repo's Floci-first policy and a LocalStack fallback only
     when needed; SES v2 gap is documented if unsupported.
5. **README coverage drift**
   - Mitigation: grep public API names in source before final docs; update both
     English and Korean README files plus the service coverage chart.
6. **Async cancellation or failed SDK futures are hidden**
   - Mitigation: every suspending SES/SNS operation uses coroutine-friendly
     `CompletableFuture.await()` or an existing bluetape4k coroutine helper, not
     blocking `get()`/`join()`/`runBlocking`. Tests must prove cancellation
     cancels or stops awaiting the backing future and failed futures propagate
     their original AWS SDK error contract.
7. **Large raw email/attachment payloads are copied repeatedly**
   - Mitigation: copy byte arrays at public trust boundaries for immutability,
     then avoid extra large-buffer copies during SDK request mapping where the
     AWS SDK API can safely consume the already defensive copy.
8. **SNS HTTP parser accepts deceptive certificate URLs or hostile JSON**
   - Mitigation: use Jackson object parsing only, cap payload size, reject
     non-object payloads, reject missing or non-string required fields, reject
     duplicate security-sensitive fields, and validate `SigningCertURL` with
     exact URL rules: `https`, no userinfo, no query, no fragment, no custom
     port, supported Amazon SNS host, `.pem` certificate path, and region/
     partition consistency with `TopicArn` when both are present.
9. **Payload-controlled client configuration**
   - Mitigation: AWS region, endpoint override, credentials, and client
     customizers are read only from application configuration, injected clients,
     or Ktor plugin configuration. SNS HTTP payload/header fields never control
     AWS client endpoint, region, credentials, or signing behavior.
10. **Sensitive data appears in diagnostics**
   - Mitigation: new model `toString`/exception/logging paths must not expose
     AWS credentials, SNS confirmation tokens, signatures, raw email content,
     attachment bytes, or recipient lists. Operations return unwrapped SDK
     responses so callers can inspect SES/SNS message ids and SDK request
     metadata without library-side logging of payloads.

## Acceptance Criteria

- `aws-ktor` declares optional/test AWS SDK dependencies for SES v2 and SNS.
- `AwsKtorCore` stores shared SES v2 and SNS async-client customizers.
- `SesKtorPlugin` and `SnsKtorPlugin` install operations into Ktor application
  attributes and inherit shared defaults.
- Plugin-created SES/SNS async clients are created once per plugin installation,
  reused across operations, and closed once by the Ktor stopping hook.
- SES operations map bluetape4k request value objects to SES v2
  `SendEmailRequest` variants.
- SNS operations cover create topic, create FIFO topic, find topic ARN,
  publish, publish SMS, and confirm subscription.
- SNS HTTP message parsing validates required fields, type/header parity,
  confirmation-token requirements, strict JSON object/string-field structure,
  bounded body size, and exact Amazon SNS signing certificate URL rules without
  Spring Boot dependencies.
- SNS topic publish and SMS publish are separate request types. Topic publish
  validates nonblank topic ARN/message, FIFO fields only for FIFO topics, and
  `messageGroupId` for FIFO topics. SMS publish validates phone-number target,
  message, and SMS-only attributes without allowing topic fields.
- SES/SNS suspending operations propagate AWS SDK failures and cancellation
  instead of converting them to generic success/failure wrappers.
- SNS topic ARN lookup is documented as a paged lookup path, not a hot publish
  path; callers should cache topic ARNs for repeated publish operations.
- README examples document runtime SES/SNS AWS SDK dependencies, region,
  local-emulator endpoint override, test credentials, production credential
  provider caveats, SES sandbox/verified identity constraints, the 40 MB SES
  attachment limit, emulator-support caveats, message-id/request-id diagnostic
  surfaces, and rollback/ownership boundaries for disabling plugins or using
  injected/raw AWS SDK clients.
- Tests cover plugin lifecycle, mapping, parser validation, representative
  send/publish behavior, and reliable emulator-backed SNS behavior if available.
- `README.md`, `README.ko.md`, `aws-ktor/README.md`,
  `aws-ktor/README.ko.md`, and the service coverage chart reflect SES v2 and
  SNS Ktor support.
- Review gates converge to P0 = 0 and P1 = 0 before PR creation.

## Stop Condition

Stop after PR creation, post-PR review, CI verification, and Step 9 DoD report.
Do not merge until the user explicitly requests merge.
