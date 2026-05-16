# Disabled Test Registry

Tests annotated with `@Disabled` are tracked here so their scope, reason, and
tracking issue are visible at a glance. Every `@Disabled` annotation **must**
include a reference to the tracking issue in the format `#NNN — <reason>`.

## Category Legend

| Category | Meaning |
|---|---|
| `unsupported-emulator` | Service or API variant not supported by LocalStack or floci |
| `out-of-band-protocol` | Flow requires a token/event delivered outside the emulator (e.g., SMS, email callback) |

## Registry

| Module | File | Test | Level | Category | Tracking Issue | Reason |
|---|---|---|---|---|---|---|
| `aws-kotlin` | `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sesv2/SesV2ClientExtensionsTest.kt` | *(entire class)* | class | `unsupported-emulator` | [#99](https://github.com/bluetape4k/bluetape4k-aws/issues/99) | LocalStack does not support SES V2; mock-based coverage tracked in #105 |
| `aws-kotlin` | `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sns/SnsClientExtensionsTest.kt` | `confirm subscription` | method | `out-of-band-protocol` | [#100](https://github.com/bluetape4k/bluetape4k-aws/issues/100) | SNS subscription token delivered out-of-band to subscriber endpoint; no emulator support |
| `aws-kotlin` | `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sns/examples/SnsClientExamples.kt` | `confirm subscription` | method | `out-of-band-protocol` | [#100](https://github.com/bluetape4k/bluetape4k-aws/issues/100) | SNS subscription token delivered out-of-band to subscriber endpoint; no emulator support |
| `aws` | `aws/src/test/kotlin/io/bluetape4k/aws/sns/SnsAsyncClientTest.kt` | `confirm subscription` | method | `out-of-band-protocol` | [#100](https://github.com/bluetape4k/bluetape4k-aws/issues/100) | SNS SMS token delivered out-of-band to subscriber; no emulator support |
| `aws` | `aws/src/test/kotlin/io/bluetape4k/aws/sns/SnsClientTest.kt` | `confirm subscription` | method | `out-of-band-protocol` | [#100](https://github.com/bluetape4k/bluetape4k-aws/issues/100) | SNS SMS token delivered out-of-band to subscriber; no emulator support |

## Annotation Format Convention

All `@Disabled` annotations must follow this exact format:

```
@Disabled("#NNN — <one-sentence reason>")
```

- `#NNN` — the GitHub issue number tracking this skip
- ` — ` — em dash with surrounding spaces
- reason — why the test cannot run (not what it tests)

**Valid examples:**

```kotlin
@Disabled("#99 — LocalStack does not support SES V2; mock-based coverage tracked in issue #105")
@Disabled("#100 — SNS SMS token is delivered out-of-band to subscriber; no emulator support")
```

**Invalid (no issue reference):**

```kotlin
@Disabled("not working")
@Disabled
```

## CI Format Validation

The `validate-disabled-annotations` job in CI rejects any `@Disabled` annotation
that does not match `@Disabled("#NNN — <reason>")`. PRs with non-conforming
annotations will fail CI automatically.
