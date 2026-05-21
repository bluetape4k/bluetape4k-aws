# Issue #7 SES Email Sender Plan

## Scope

Implement issue #7 in `aws-spring-boot` from:

- Spec: `docs/superpowers/specs/2026-05-22-issue-7-ses-email-sender-design.md`
- Branch: `feat/issue-7-ses-email-sender`
- Base: `origin/develop`

## Steps

### 1. Build Configuration

- Add `spring-context-support` to the version catalog as a Spring Boot BOM
  managed alias.
- Add `compileOnly` and `testImplementation` dependencies for
  `software.amazon.awssdk:sesv2`.
- Add `compileOnly` and `testImplementation` dependencies for
  `org.springframework:spring-context-support`.
- Verify `spring-context-support` is Spring Boot BOM managed with
  `dependencyInsight`; if not, pin the Spring Framework version explicitly
  instead of guessing.
- Do not add awspring SES dependencies.

### 2. SES Properties and Value Objects

- Add package `io.bluetape4k.aws.spring.ses`.
- Add `SesProperties` with `enabled`, `region`, `endpointOverride`,
  `defaultFrom`, `configurationSetName`, and nested JavaMail adapter settings.
- Validate `defaultFrom` as non-blank and CR/LF/NUL-free when set, and
  `configurationSetName` as non-blank when set.
- Add serializable request/value objects:
  - `SesEmailAddressSet`
  - `SesEmailBody`
  - `SesEmailAttachment`
  - `SesEmailRequest`
  - `SesTemplateEmailRequest`
  - `SesRawEmailRequest`
- Keep validations close to the mapping boundary and use bluetape4k validation
  helpers when available.
- Add `serialVersionUID` to serializable public value objects.
- Implement content-aware equality/hashCode for byte-carrying models instead
  of relying on Kotlin data class `ByteArray` equality.
- Enumerate and map `AttachmentContentDisposition` (`ATTACHMENT`, `INLINE`)
  and `AttachmentContentTransferEncoding` (`BASE64`, `SEVEN_BIT`,
  `QUOTED_PRINTABLE`).
- Reject CR/LF characters in request headers before mapping to SES v2
  `MessageHeader`.
- Reject CR/LF/NUL characters in `subject`, `from`, `replyTo`, and every
  recipient string.
- Add a shared validation helper for header/address strings and an SES 40 MB
  message-size guard for raw bytes plus modeled attachments.
- Add English KDoc to public API.

### 3. Coroutine SES Sender

- Add `SesOperations`.
- Add `SesCoroutinesMailSender`.
- Add future-backed `SesOperations` methods used by the JavaMail adapter:
  `sendEmailAsync`, `sendTemplateEmailAsync`, `sendRawEmailAsync`, and
  `sendAsync`.
- Map simple email requests to SES v2 `EmailContent.simple`.
- Map template email requests to SES v2 `EmailContent.template`.
- Map raw email requests to SES v2 `EmailContent.raw`.
- Map attachments to SES v2 `Attachment`.
- Map request `headers` to SES v2 `MessageHeader` on `Message` and
  `Template`.
- Apply request overrides from `SesProperties`.
- Omit `from` and `configurationSetName` SDK fields entirely when both the
  request value and property default are null.
- Omit SDK `Destination` for raw email when `SesRawEmailRequest.destination`
  is null.
- Validate resolved defaults after fallback.
- Respect direct `send(SendEmailRequest)` exactly; do not apply property
  defaults to the escape hatch.
- Use `kotlinx.coroutines.future.await()` and avoid broad exception handling
  around suspend calls.
- Make suspend methods delegate through the same future-backed request mapping
  used by JavaMail, then `.await()` the future.
- Add a direct `send(SendEmailRequest)` escape hatch.
- Add a cancellation propagation test using a pending `CompletableFuture` from
  the mocked SDK client.
- Avoid logging body text, template data, attachment bytes, raw MIME bytes, or
  full recipient lists.

### 4. JavaMail Adapter

- Add `SesJavaMailSender` in the same SES package.
- Implement `JavaMailSender`:
  - `createMimeMessage()`
  - `createMimeMessage(InputStream)`
  - `send(MimeMessage...)`
  - `send(SimpleMailMessage...)`
- Serialize MIME messages to bytes and send as SES raw email.
- Extract `From` and all recipients from MIME headers before `sendRawEmail`
  when the caller did not provide an explicit envelope.
- Convert Spring simple messages to `SesEmailRequest`.
- Depend on `SesOperations` rather than `SesV2AsyncClient`, so a custom
  operations bean is respected by the adapter.
- Use a non-suspend/future-backed bridge for blocking `JavaMailSender` methods;
  do not use `runBlocking` inside the adapter.
- Throw `MailParseException` when `SimpleMailMessage.text` is null or blank.
- Translate send failures to Spring `MailException` subclasses where
  practical.
- Use the explicit mapping from the spec for `MailParseException`,
  `MailAuthenticationException`, `MailSendException`, `CompletionException`
  unwrapping, and `CancellationException` rethrow.
- Implement batch send to attempt every message and report failed-message
  details through `MailSendException`.
- Document that this adapter is blocking because Spring `JavaMailSender` is a
  blocking contract.

### 5. Auto-Configuration

- Add `SesAutoConfiguration` after `AwsAutoConfiguration`.
- Guard SES SDK classes with string-based `@ConditionalOnClass`.
- Apply `@ConditionalOnProperty` to the SES phase.
- Register `SesV2AsyncClient` with destroy method `close`.
- Register `SesCoroutinesMailSender` as `SesOperations`.
- Add `SesJavaMailSenderAutoConfiguration` as a separate class ordered after
  `SesAutoConfiguration`.
- Use `@AutoConfiguration(after = [SesAutoConfiguration::class])` on the
  JavaMail phase.
- Guard JavaMail types with string-based `@ConditionalOnClass`.
- Make the JavaMail phase conditional on `SesOperations`.
- Add `@ConditionalOnMissingBean(JavaMailSender::class)` to the JavaMail phase.
- Apply `@ConditionalOnProperty` to the JavaMail phase.
- Register both auto-configuration classes in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

### 6. Tests

- Add `SesAutoConfigurationTest` with ApplicationContextRunner coverage for:
  - default bean registration,
  - disabled property,
  - custom client backoff,
  - custom operations backoff,
  - SDK classpath absence,
  - endpoint override validation,
  - kebab binding for `bluetape4k.aws.ses.java-mail-sender.enabled`,
  - no JavaMail adapter when SES auto-configuration is disabled and
    `SesOperations` is absent,
  - JavaMail enabled/disabled registration,
  - JavaMail classpath absence,
  - custom JavaMailSender backoff.
- Add `SesCoroutinesMailSenderTest` with MockK coverage for:
  - simple email mapping,
  - template email mapping,
  - raw email mapping,
  - attachments,
  - property defaults,
  - CR/LF header rejection,
  - CR/LF/NUL subject/address rejection,
  - unsupported charset rejection,
  - template name/ARN XOR validation,
  - oversize raw and attachment payload rejection,
  - coroutine cancellation propagation,
  - validation failures before SDK calls.
- Add `SesJavaMailSenderTest` with MockK coverage for:
  - MIME raw send,
  - MIME-only envelope extraction,
  - simple message conversion,
  - null/blank `SimpleMailMessage.text` failure,
  - custom `SesOperations` delegation,
  - failure translation to Spring mail exceptions,
  - failure messages exclude body text and attachment/raw bytes,
  - preparator path if not already covered by Spring default methods.
- Use bluetape4k assertions only.
- Keep mocks as class-level fields and clear them in `@BeforeEach`.

### 7. README and Lesson

- Update `aws-spring-boot/README.md`.
- Update `aws-spring-boot/README.ko.md`.
- Add `docs/lessons/2026-05-22-issue-7-ses-email-sender.md` with context,
  decision, outcome, verification evidence, and future guardrails.

### 8. Verification

Run, in order:

1. `rg '[가-힣]' aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/ses`
2. `rg 'Ses|ses|java-mail-sender|sesv2' aws-spring-boot/README.md aws-spring-boot/README.ko.md`
3. `git diff --check`
4. `./gradlew :bluetape4k-aws-spring-boot:compileKotlin`
5. `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin`
6. `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.ses.*'`
7. `./gradlew :bluetape4k-aws-spring-boot:detekt`
8. `./gradlew :bluetape4k-aws-spring-boot:koverHtmlReport`
9. `./gradlew :bluetape4k-aws-spring-boot:koverVerify`
10. `./gradlew :bluetape4k-aws-spring-boot:test`

If IntelliJ diagnostics tools are unavailable, record the gap and use Gradle
compile/tests as fallback evidence.

### 9. Review, Commit, PR

- Run Step 6-R code review after implementation using the required
  `bluetape4k-design` references.
- Resolve all P0/P1 findings.
- Commit with Lore trailers.
- Push branch.
- Open PR against `develop`, assigned to `debop`, linked to issue #7.
- Do not merge the PR unless the user asks.

## Review Checklist

- No awspring or Spring Cloud AWS runtime dependency.
- SES SDK and JavaMail support remain optional at compile/runtime boundaries.
- `compileOnly` types in bean signatures are guarded by string-based
  `@ConditionalOnClass`.
- Each auto-configuration phase has its own `@ConditionalOnProperty`.
- Request value objects avoid ambiguous same-typed positional parameters.
- Attachments use SES v2 native `Attachment` for simple/template sends.
- JavaMail adapter sends raw MIME and is documented as blocking.
- JavaMail adapter has no `runBlocking` usage.
- Coroutine sender does not catch or swallow cancellation.
- Public API KDoc is English.
- README and Korean README stay in sync.
- Tests do not require real AWS credentials or verified SES identities.

## Step 2 Checklist Completion Report

┌──────────────────────────────────────┬────────┬──────────────────────────────────────────────────────────────┐
│ Item                                 │ Status │ Notes                                                        │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ Spec written inside feature worktree │ Done   │ `docs/superpowers/specs/2026-05-22-issue-7-ses-email-sender-design.md` │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ Goals and non-goals recorded         │ Done   │ Scope excludes awspring, real AWS send, bulk/inbound SES, examples. │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ API and auto-config shape specified  │ Done   │ Coroutine sender, optional JavaMail phase, properties, value objects. │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ Tests and acceptance criteria listed │ Done   │ Auto-config, mapping, JavaMail, README, verification criteria included. │
└──────────────────────────────────────┴────────┴──────────────────────────────────────────────────────────────┘

## Step 3 Checklist Completion Report

┌──────────────────────────────────────┬────────┬──────────────────────────────────────────────────────────────┐
│ Item                                 │ Status │ Notes                                                        │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ Plan written inside feature worktree │ Done   │ `docs/superpowers/plans/2026-05-22-issue-7-ses-email-sender-plan.md` │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ Ordered implementation tasks listed  │ Done   │ Build, API, sender, JavaMail, auto-config, tests, docs, verify. │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ Verification commands listed         │ Done   │ Targeted compile/tests and full module test included.        │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ Review and PR stop condition listed  │ Done   │ P0/P1 review convergence and no auto-merge rule recorded.    │
└──────────────────────────────────────┴────────┴──────────────────────────────────────────────────────────────┘

## Step 3-R Review Notes

### Claude Code Opus Advisor

Artifacts:

- `.omx/artifacts/claude-issue-7-ses-plan-review-2026-05-22.md`
- `.omx/artifacts/claude-issue-7-ses-plan-rereview-2026-05-22.md`
- `.omx/artifacts/claude-issue-7-ses-plan-final-rereview-2026-05-22.md`

┌──────────┬────────────────────────────────────────────────────┬──────────────────────────────────────────────┐
│ Priority │ Finding                                            │ Decision                                     │
├──────────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ P1       │ Raw MIME envelope extraction, JavaMail blocking     │ Accepted. Added explicit plan tasks.         │
│          │ bridge, and CR/LF validation scope were incomplete. │                                              │
├──────────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ P2       │ Header mapping, raw destination omission, property  │ Accepted. Added concrete Step 2/3/5/6/8      │
│          │ validation, serialVersionUID, JavaMail backoff, and │ tasks.                                       │
│          │ review hygiene were underspecified.                │                                              │
├──────────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ 0        │ Final rerun reported P0 = 0 and P1 = 0.             │ Step 3-R closed.                             │
└──────────┴────────────────────────────────────────────────────┴──────────────────────────────────────────────┘
