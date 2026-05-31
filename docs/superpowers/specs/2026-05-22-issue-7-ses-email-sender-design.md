# Issue #7 SES Email Sender Design

## Context

- Repository: `bluetape4k-aws`
- Issue: <https://github.com/bluetape4k/bluetape4k-aws/issues/7>
- Target module: `aws-spring-boot`
- Work type: Spring Boot public API feature, Full Design lane
- Base branch: `develop`

Issue #7 asks for SES v2 email sending support in `aws-spring-boot` without
adding Spring Cloud AWS or awspring as runtime dependencies. The module should
auto-configure an AWS SDK v2 `SesV2AsyncClient`, expose a coroutine-first mail
sender, support SES template and attachment APIs, and provide an optional
Spring `JavaMailSender` adapter for users that already model email as MIME.

## Evidence

- Current `aws-spring-boot` patterns use `compileOnly` AWS service SDK
  dependencies, `ApplicationContextRunner` tests, string-based
  `@ConditionalOnClass`, and `@ConditionalOnProperty` for each service
  auto-configuration phase.
- `SnsAutoConfiguration` and `SqsAutoConfiguration` show the expected client
  builder shape: `AwsAutoConfiguration` first, optional
  `AwsCredentialsProvider`, optional `SdkAsyncHttpClient`, property-driven
  `region` and `endpointOverride`, destroy method `close`, and
  `@ConditionalOnMissingBean` backoff.
- The `aws` module already contains SES v1 helpers under
  `io.bluetape4k.aws.ses`, but issue #7 explicitly targets
  `software.amazon.awssdk:sesv2` and Spring Boot integration. Reusing the old
  v1 request builders would leak the wrong SDK model.
- Existing repo guidance requires public data classes to implement
  `Serializable`; however, Kotlin data classes with `ByteArray` use reference
  equality. Attachment/raw request models that carry bytes must therefore be
  regular serializable classes or implement content-aware equality explicitly.
- SES v2 sends have a 40 MB message size limit. This module should fail fast
  with clear validation errors for raw MIME bytes and modeled attachments that
  exceed that boundary instead of surfacing obscure service-side send failures.
- AWS SES v2 `SendEmail` accepts `EmailContent.simple`, `EmailContent.template`,
  and `EmailContent.raw` content modes:
  <https://docs.aws.amazon.com/ses/latest/APIReference-V2/API_SendEmail.html>.
- Local AWS SDK source jars confirm current SES v2 model support:
  `Message` and `Template` both include `attachments`, `Attachment` carries raw
  content plus MIME metadata, and `RawMessage` carries full MIME bytes for
  JavaMail-style sending.
- Local AWS SDK source jar for `software.amazon.awssdk:sesv2:2.44.9` confirms
  `software.amazon.awssdk.services.sesv2.model.Message` and `Template` both
  expose `headers(Collection<MessageHeader>)` builders, so simple/template
  header mapping is supported by the cataloged SDK version.
- Spring `JavaMailSender` lives in `org.springframework:spring-context-support`
  and uses Jakarta Mail `MimeMessage`. It is not currently on the
  `aws-spring-boot` compile classpath, so it must remain optional and
  classpath-guarded.
- Spring Boot auto-configuration is discovered through
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  and optional phases should be separate classes when ordering and classpath
  guards matter:
  <https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html>.

## Goals

1. Add `SesAutoConfiguration` for `SesV2AsyncClient` and coroutine SES
   operations.
2. Add `SesProperties` bound to `bluetape4k.aws.ses`.
3. Add `SesOperations` and `SesCoroutinesMailSender` with suspend APIs for
   simple, template, raw, and direct SDK `SendEmailRequest` sends.
4. Model simple/template requests with named value objects to avoid ambiguous
   same-typed parameter lists.
5. Support attachments through SES v2 `Attachment` for simple and template
   content.
6. Add an optional `JavaMailSender` adapter that converts `MimeMessage` to SES
   v2 raw email content.
7. Register all SES auto-configuration phases through
   `AutoConfiguration.imports`.
8. Update `README.md` and `README.ko.md` with runtime dependencies,
   configuration, coroutine usage, template usage, attachment usage, and
   JavaMail adapter notes.

## Non-Goals

- No awspring or Spring Cloud AWS runtime dependency.
- No SES identity/domain/template management API.
- No bulk email API.
- No inbound email, receipt rules, or SNS bounce/complaint handling.
- No blocking `SesV2Client`.
- No real AWS integration test that sends email from CI.
- No example module in this PR; issue #82 owns examples.

## Public API

Package: `io.bluetape4k.aws.spring.ses`

### SesProperties

Configuration prefix: `bluetape4k.aws.ses`

Fields:

- `enabled: Boolean = true`
- `region: String? = null`
- `endpointOverride: URI? = null`
- `defaultFrom: String? = null`
- `configurationSetName: String? = null`
- `javaMailSender: JavaMailSenderProperties = JavaMailSenderProperties()`

Nested `JavaMailSenderProperties` fields:

- `enabled: Boolean = true`

Validation:

- `endpointOverride` requires `region`, matching the existing SNS/SQS/KMS
  property rule.
- `defaultFrom`, when set, must not be blank.
- `defaultFrom`, when set, must reject CR/LF/NUL characters.
- `configurationSetName`, when set, must not be blank.

### SesEmailAddressSet

Serializable value object for recipients:

- `to: List<String>`
- `cc: List<String> = emptyList()`
- `bcc: List<String> = emptyList()`

Validation:

- At least one recipient must be present.
- Recipient strings must not be blank.
- Recipient strings must reject CR/LF/NUL characters before the SES SDK call.

### SesEmailBody

Serializable value object for simple content:

- `text: String? = null`
- `html: String? = null`
- `charset: String = "UTF-8"`

Validation:

- At least one of `text` or `html` must be present and non-blank.
- `charset` must not be blank and must be supported by `java.nio.charset.Charset`.

### SesEmailAttachment

Serializable value object for SES v2 attachments. This must not rely on the
default Kotlin `data class` `ByteArray` equality semantics; implement
content-aware equality/hashCode or use a non-data class with explicit methods.
It must still implement `Serializable` and define `serialVersionUID`.

- `fileName: String`
- `content: ByteArray`
- `contentType: String`
- `contentDisposition: AttachmentContentDisposition = ATTACHMENT`
- `contentTransferEncoding: AttachmentContentTransferEncoding = BASE64`
- `contentDescription: String? = null`
- `contentId: String? = null`

Validation:

- `fileName`, `contentType`, and `content` must be non-empty.
- Inline attachments may set `contentId`.
- Byte arrays require content-aware equality tests.
- Attachment payloads participate in the SES 40 MB message-size guard.

Enum mapping:

- `AttachmentContentDisposition`: `ATTACHMENT`, `INLINE`.
- `AttachmentContentTransferEncoding`: `BASE64`, `SEVEN_BIT`,
  `QUOTED_PRINTABLE`.

### SesEmailRequest

Serializable value object for simple SES email:

- `from: String? = null`
- `destination: SesEmailAddressSet`
- `subject: String`
- `body: SesEmailBody`
- `replyTo: List<String> = emptyList()`
- `attachments: List<SesEmailAttachment> = emptyList()`
- `headers: Map<String, String> = emptyMap()`
- `configurationSetName: String? = null`

Defaults:

- `from` falls back to `SesProperties.defaultFrom`.
- `configurationSetName` falls back to `SesProperties.configurationSetName`.

Validation:

- `subject` must not be blank.
- Header names and values must not be blank and must reject CR/LF characters to
  avoid header injection.
- `subject`, `from`, `replyTo`, and every destination string must reject
  CR/LF/NUL characters.

### SesTemplateEmailRequest

Serializable value object for SES template email:

- `from: String? = null`
- `destination: SesEmailAddressSet`
- `templateName: String? = null`
- `templateArn: String? = null`
- `templateData: String? = null`
- `attachments: List<SesEmailAttachment> = emptyList()`
- `headers: Map<String, String> = emptyMap()`
- `configurationSetName: String? = null`

Validation:

- Exactly one of `templateName` or `templateArn` must be provided.
- `templateData`, when set, must not be blank. The caller owns JSON validity,
  because SES accepts the data as a string and this module should not force a
  JSON dependency.
- Header names and values must not be blank and must reject CR/LF characters to
  avoid header injection.
- `from` and every destination string must reject CR/LF/NUL characters.

### SesRawEmailRequest

Serializable value object for raw MIME email. This must not rely on the default
Kotlin `data class` `ByteArray` equality semantics; implement content-aware
equality/hashCode or use a non-data class with explicit methods. It must still
implement `Serializable` and define `serialVersionUID`.

- `rawContent: ByteArray`
- `from: String? = null`
- `destination: SesEmailAddressSet? = null`
- `configurationSetName: String? = null`

Validation:

- `rawContent` must not be empty.
- `rawContent` must not exceed the SES v2 40 MB message-size limit.
- `from` and `destination` are optional because raw MIME carries headers, but
  setting them should pass explicit envelope values to SES when needed.
- `from` and explicit destination strings must reject CR/LF/NUL characters.

### SesOperations

Coroutine-first interface:

- `suspend fun sendEmail(request: SesEmailRequest): SendEmailResponse`
- `suspend fun sendTemplateEmail(request: SesTemplateEmailRequest): SendEmailResponse`
- `suspend fun sendRawEmail(request: SesRawEmailRequest): SendEmailResponse`
- `suspend fun send(request: SendEmailRequest): SendEmailResponse`
- `fun sendEmailAsync(request: SesEmailRequest): CompletableFuture<SendEmailResponse>`
- `fun sendTemplateEmailAsync(request: SesTemplateEmailRequest): CompletableFuture<SendEmailResponse>`
- `fun sendRawEmailAsync(request: SesRawEmailRequest): CompletableFuture<SendEmailResponse>`
- `fun sendAsync(request: SendEmailRequest): CompletableFuture<SendEmailResponse>`

The direct `SendEmailRequest` method is an escape hatch for advanced SES fields
not modeled by the convenience value objects.
The async methods exist for blocking adapter bridges such as `JavaMailSender`;
suspend methods delegate through the same request mapping and await the future.

### SesCoroutinesMailSender

Concrete `SesOperations` implementation backed by `SesV2AsyncClient`.

Contract:

- Map simple requests to `EmailContent.simple`.
- Map template requests to `EmailContent.template`.
- Map raw requests to `EmailContent.raw`.
- Map attachments to SES v2 `Attachment` on `Message` or `Template`.
- Await SDK `CompletableFuture` with `kotlinx.coroutines.future.await()`.
- Let AWS SDK exceptions propagate.
- Do not catch `CancellationException`.
- Do not log raw email content, template data, body text, attachments, or full
  recipient lists. Diagnostic logs, if added later, must stay metadata-only.
- For direct `send(SendEmailRequest)`, respect the caller-provided SDK request
  exactly and do not apply `SesProperties` defaults. The convenience request
  methods own default `from` and `configurationSetName` behavior.

### SesJavaMailSender

Optional `JavaMailSender` implementation backed by `SesOperations`.

Contract:

- `createMimeMessage()` creates a Jakarta Mail `MimeMessage` using a local
  `Session`.
- `send(MimeMessage...)` serializes each MIME message to bytes and calls SES v2
  raw email through `SesOperations.sendRawEmail`.
- `send(SimpleMailMessage...)` converts Spring simple messages to
  `SesEmailRequest` and sends through `SesOperations.sendEmail`.
- The adapter is blocking because `JavaMailSender` is a blocking Spring
  contract. It must not use `runBlocking`; it should bridge to a non-suspend
  future-backed `SesOperations` send path and block the caller thread
  intentionally.
- `send(MimeMessage...)` must extract the outer SES envelope from MIME headers
  when explicit envelope fields are absent: `From` from `getFrom()` and
  recipients from `getAllRecipients()`.
- `send(SimpleMailMessage...)` must reject null or blank message text with
  `MailParseException`.
- Failure mapping:
  - Jakarta `MessagingException` and MIME serialization failures map to
    `MailParseException`.
  - SES `NotAuthorizedException`, access-denied style SDK service failures, or
    AWS SDK auth/credentials failures map to `MailAuthenticationException`.
  - Other `SesV2Exception`, `SdkClientException`, or future completion failures
    map to `MailSendException` after unwrapping `CompletionException`.
  - `CancellationException` is rethrown.
- Batch `send(MimeMessage...)` and `send(SimpleMailMessage...)` should attempt
  every message and throw `MailSendException` with failed-message details when
  one or more messages fail, matching Spring's batch send contract.
- This bean is only created when `org.springframework.mail.javamail.JavaMailSender`
  and Jakarta Mail classes are present.
- KDoc must warn that JavaMail send methods block the caller thread while the
  AWS SDK async client performs network IO.

## Auto-Configuration

### SesAutoConfiguration

Registers SES SDK client and coroutine operations.

Rules:

- `@AutoConfiguration(after = [AwsAutoConfiguration::class])`
- `@ConditionalOnClass(name = ["software.amazon.awssdk.http.async.SdkAsyncHttpClient", "software.amazon.awssdk.services.sesv2.SesV2AsyncClient"])`
- `@ConditionalOnProperty(prefix = "bluetape4k.aws.ses", name = ["enabled"], havingValue = "true", matchIfMissing = true)`
- `@EnableConfigurationProperties(SesProperties::class)`
- `SesV2AsyncClient` bean uses `destroyMethod = "close"`.
- Back off for user-provided `SesV2AsyncClient`.
- Back off for user-provided `SesOperations`.

### SesJavaMailSenderAutoConfiguration

Registers optional JavaMail adapter.

Rules:

- Separate class registered directly in `AutoConfiguration.imports`, ordered
  after `SesAutoConfiguration`.
- Use `@AutoConfiguration(after = [SesAutoConfiguration::class])`; do not rely
  only on import file ordering for `@ConditionalOnBean(SesOperations::class)`.
- `@ConditionalOnClass(name = ["org.springframework.mail.javamail.JavaMailSender", "jakarta.mail.internet.MimeMessage"])`
- `@ConditionalOnBean(SesOperations::class)`
- `@ConditionalOnMissingBean(JavaMailSender::class)`
- `@ConditionalOnProperty(prefix = "bluetape4k.aws.ses.java-mail-sender", name = ["enabled"], havingValue = "true", matchIfMissing = true)`
- The compileOnly `JavaMailSender` type appears only in this class and is
  guarded with string-based `@ConditionalOnClass`.
- The adapter should depend on `SesOperations`, not directly on
  `SesV2AsyncClient`, so custom `SesOperations` beans keep the same backoff and
  extension behavior.

## Dependencies

Add to `aws-spring-boot`:

- `compileOnly(libs.aws2.sesv2)`
- `testImplementation(libs.aws2.sesv2)`
- `compileOnly(libs.spring.context.support)` after adding a version-catalog
  alias backed by the Spring Boot BOM.
- `testImplementation(libs.spring.context.support)`

Do not add awspring SES starter dependencies.

## Tests

ApplicationContextRunner tests:

- Registers `SesV2AsyncClient`, `SesProperties`, `SesOperations`, and
  `SesCoroutinesMailSender` when SES SDK classes are present.
- Does not register when `bluetape4k.aws.ses.enabled=false`.
- Backs off for custom `SesV2AsyncClient`.
- Backs off for custom `SesOperations`.
- Does not register when `SesV2AsyncClient` is filtered out.
- Fails validation when `endpointOverride` is set without `region`.
- Binds `defaultFrom`, `configurationSetName`, and JavaMail adapter properties.
- Registers `JavaMailSender` only when JavaMail classes are present and adapter
  property is enabled.
- Binds `bluetape4k.aws.ses.java-mail-sender.enabled=false` and skips the
  JavaMail adapter.
- Backs off for custom `JavaMailSender`.
- Does not register `JavaMailSender` when JavaMail classes are filtered out.

Unit tests:

- Simple email maps source, destination, subject, text/html body,
  reply-to addresses, headers, attachments, and configuration set.
- Template email maps template name/ARN, template data, headers, attachments,
  destination, and configuration set.
- Raw email maps bytes to `RawMessage`.
- Raw email omits the SDK `Destination` field when
  `SesRawEmailRequest.destination` is null.
- Defaults from `SesProperties` are applied when request-level values are null.
- Resolved defaults are validated after fallback so a poisoned
  `SesProperties.defaultFrom` fails before the SDK call.
- Missing `from` without `defaultFrom` fails before calling AWS for simple and
  template sends.
- Blank subject/body/recipient/template fields fail before calling AWS.
- Header CR/LF, address CR/LF/NUL, subject CR/LF/NUL, and oversize raw or
  attachment payloads fail before calling AWS.
- Unsupported charset names and template name/ARN XOR violations fail before
  calling AWS.
- Validation failures use `IllegalArgumentException` through `require*`
  helpers and tests use bluetape4k `assertFailsWith<IllegalArgumentException>`.
- Byte-array attachment assertions use content equality.
- Suspend send cancellation propagates when the underlying SDK future is still
  pending.
- JavaMail adapter serializes `MimeMessage` to raw SES content and converts
  `SimpleMailMessage` to the simple SES path.
- JavaMail adapter extracts `From` and all recipients from MIME messages when
  building the SES raw envelope.
- JavaMail adapter translates send failures to Spring mail exceptions without
  including body or attachment content in exception messages.

Integration tests:

- No real AWS send is required for this issue. SES delivery in CI would require
  verified identities and credentials.
- If the emulator stack exposes enough SES v2 behavior, add one disabled-by-
  environment or emulator-gated smoke test. Otherwise, use MockK/unit coverage
  for request mapping and ApplicationContextRunner for auto-configuration.
- Do not add LocalStack as the default for new tests; the repo default emulator
  is floci.

## README Updates

Update both `aws-spring-boot/README.md` and `aws-spring-boot/README.ko.md`:

- Feature list entry for SES.
- Runtime dependency snippet for `software.amazon.awssdk:sesv2`.
- Optional JavaMail dependency snippet for `org.springframework:spring-context-support`.
- No BOM entry change is required for this PR because the existing
  `bluetape4k-aws-bom` publishes modules, not third-party dependency
  constraints.
- `bluetape4k.aws.ses` configuration snippet.
- Coroutine simple email example.
- Template email example.
- Attachment example.
- JavaMail adapter note explaining blocking semantics and raw MIME sending.

## Acceptance Criteria

- `aws-spring-boot` compiles with SES SDK and JavaMail dependencies as
  `compileOnly`.
- SES auto-configuration phases are listed in `AutoConfiguration.imports`.
- New public API has English KDoc and realistic examples where useful.
- `README.md` and `README.ko.md` stay synchronized.
- Targeted SES tests pass.
- `./gradlew :bluetape4k-aws-spring-boot:test` passes or any emulator-only
  environment gap is recorded with evidence.
- Strict review has no unresolved P0/P1 correctness, cancellation, Spring Boot,
  or public API blockers.
- A concise lesson is added under `docs/lessons/`.
- PR is opened against `develop`, assigned to `debop`, and linked to issue #7.

## Step 2-R Review Notes

### Codex Multi-Perspective Findings

┌──────────┬──────────────┬────────────────────────────────────────────────────┬──────────────────────────────┐
│ Priority │ Area         │ Finding                                            │ Decision                     │
├──────────┼──────────────┼────────────────────────────────────────────────────┼──────────────────────────────┤
│ P1       │ API model    │ ByteArray in attachment/raw models must not rely   │ Accepted. Spec now requires  │
│          │              │ on Kotlin data class reference equality.           │ content-aware equality.      │
├──────────┼──────────────┼────────────────────────────────────────────────────┼──────────────────────────────┤
│ P1       │ Spring       │ JavaMail adapter should depend on `SesOperations`, │ Accepted. Auto-config now    │
│          │ integration  │ not direct client, to preserve custom operations   │ uses `SesOperations`.        │
│          │              │ backoff.                                           │                              │
├──────────┼──────────────┼────────────────────────────────────────────────────┼──────────────────────────────┤
│ P1       │ Security     │ Header values need CR/LF rejection and content     │ Accepted. Validation and     │
│          │              │ must not be logged.                                │ no-content-logging rules     │
│          │              │                                                    │ added.                       │
├──────────┼──────────────┼────────────────────────────────────────────────────┼──────────────────────────────┤
│ P2       │ Tests        │ Cancellation propagation should be explicit.       │ Accepted. Test requirement   │
│          │              │                                                    │ added.                       │
├──────────┼──────────────┼────────────────────────────────────────────────────┼──────────────────────────────┤
│ P1       │ Claude       │ Raw request ByteArray equality, JavaMail ordering, │ Accepted. Spec now includes  │
│          │ spec review  │ and exception mapping were underspecified.         │ explicit contracts.          │
├──────────┼──────────────┼────────────────────────────────────────────────────┼──────────────────────────────┤
│ P2       │ Claude       │ Address injection, SES size limit, validation      │ Accepted. Added validation   │
│          │ spec review  │ exception/assertion expectations were implicit.    │ and test requirements.       │
└──────────┴──────────────┴────────────────────────────────────────────────────┴──────────────────────────────┘

### Claude Code Opus Advisor

Artifact:
`.omx/artifacts/claude-issue-7-ses-spec-review-2026-05-22.md`

Latest artifact:
`.omx/artifacts/claude-issue-7-ses-spec-review-2026-05-22-rerun.md`

Post-P2 artifact:
`.omx/artifacts/claude-issue-7-ses-spec-post-p2-review-2026-05-22.md`

Final artifact:
`.omx/artifacts/claude-issue-7-ses-spec-final-rereview-2026-05-22.md`

┌──────────┬────────────────────────────────────────────────────┬──────────────────────────────────────────────┐
│ Priority │ Finding                                            │ Decision                                     │
├──────────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ N/A      │ Claude CLI could not run because usage credits were │ Recorded as advisor gap; Codex review used   │
│          │ exhausted until 4am Asia/Seoul.                    │ for convergence.                             │
├──────────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ P1/P2    │ Rerun found three P1 contract gaps and four P2      │ Accepted and applied to the spec.            │
│          │ validation/test gaps.                              │                                              │
├──────────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ P1       │ Post-P2 rerun required SDK evidence for simple and  │ Accepted. Added SES v2 2.44.9 source-jar     │
│          │ template header mapping.                           │ evidence.                                    │
├──────────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ 0        │ Final rerun reported P0 = 0 and P1 = 0.             │ Step 2-R closed.                             │
└──────────┴────────────────────────────────────────────────────┴──────────────────────────────────────────────┘

### Step 2-R Convergence

Latest integrated findings: P0 = 0, P1 = 0. Step 2-R is closed.

## Step 1-R Checklist Completion Report

┌──────────────────────────────────────┬────────┬──────────────────────────────────────────────────────────────┐
│ Item                                 │ Status │ Notes                                                        │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ Target repository confirmed          │ Done   │ Worktree is `feat/issue-7-ses-email-sender` in bluetape4k-aws. │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ Durable knowledge searched           │ Done   │ GNO found prior SNS/KMS/S3 Spring Boot design and lesson refs. │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ Official API evidence checked        │ Done   │ AWS SES v2 API docs and Spring Boot auto-config docs checked. │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ Local source/API evidence checked    │ Done   │ SES v2 and JavaMail source jars inspected for current APIs.   │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ Repo reuse searched                  │ Done   │ SNS/SQS/KMS auto-config and existing SES v1 helpers inspected. │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ Boundaries clear                     │ Done   │ No awspring, no real AWS send, no issue #82 example module.   │
└──────────────────────────────────────┴────────┴──────────────────────────────────────────────────────────────┘
