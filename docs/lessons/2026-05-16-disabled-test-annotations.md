# Add Issue References to @Disabled Test Annotations

**Date**: 2026-05-16
**Issues**: #99 (SES V2), #100 (SNS token)
**Branch**: fix/disabled-test-annotations

## Decision

Updated five `@Disabled` annotations to include issue numbers and English rationale:

| File | Old message | New message |
|---|---|---|
| `SesV2ClientExtensionsTest.kt` | `LocalStack에서 SES V2를 지원하지 않습니다.` | `#99 — LocalStack does not support SES V2; mock-based coverage tracked in issue #105` |
| `SnsClientExtensionsTest.kt` | `token은 SNS 구독 시에 클라이언트에 전송된다` | `#100 — SNS subscription token is delivered out-of-band…` |
| `SnsClientExamples.kt` | same | same |
| `SnsClientTest.kt` | `token은 SMS 구독 시에 클라이언트에 전송된다고 한다` | `#100 — SNS SMS token is delivered out-of-band…` |
| `SnsAsyncClientTest.kt` | same | same |

## Why

Without issue references, disabled tests are invisible to future maintainers and release reviews. The messages now:
1. State the root cause category (emulator limitation / out-of-band protocol)
2. Link to the tracking issue where resolution is planned
3. Reference future issue (#105) for mock-based coverage

## Verification

- `./gradlew :aws:test :aws-kotlin:test`: **444 passing, 5 pending, 0 failed**
- All 5 modified `@Disabled` messages now contain issue numbers

## Future Guidance

- Every `@Disabled` annotation must include: category + issue link
- Categories: `unsupported-emulator`, `out-of-band-protocol`, `bug`, `slow`
- Issue #106 tracks a CI gate that enforces this rule automatically
