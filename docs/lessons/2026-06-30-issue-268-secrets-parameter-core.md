# Core Secrets And Parameters Boundary

## Context

Issue #268 added framework-neutral Secrets Manager and SSM Parameter Store
helpers to the Java SDK v2 and AWS Kotlin SDK core modules. The repository
already had Spring Environment and Exposed configuration source work, so the
main risk was blurring low-level SDK wrappers with higher-level configuration
loading, caching, refresh, and rotation concerns.

## Decision

Core modules should expose thin SDK-aligned helpers only:

- redacted `AwsSecretValue` wrappers for string secrets
- service client factories and caller-owned lifecycle helpers
- request builders and single-page get/list/put helpers
- raw SDK responses for partial batch failures and pagination tokens

Spring Environment loading, JSON flattening, cache/refresh policy, rotation
orchestration, IAM/KMS policy management, and hidden all-pages collection
helpers stay outside these core modules.

## Outcome

The Java SDK v2 module now has sync, async `CompletableFuture`, and coroutine
wrappers for Secrets Manager and SSM. The AWS Kotlin SDK module now has native
suspend helpers and client lifecycle helpers for the same services. README
locale sets and the service coverage chart were updated together so the public
documentation matches the new core API surface.

## Verification

- Targeted Java tests: 18 Secrets/SSM/redaction tests, 0 failures, 0 skipped.
- Targeted Kotlin tests: 15 Secrets/SSM/redaction tests, 0 failures, 0 skipped.
- `git diff --check` passed.
- Static grep found no custom retry/backoff/deadline/fan-out in touched helpers.
- Static grep found no README/source logging or printing of `reveal()`.
- Warning-mode compile passed for touched modules.
- README local links and code fence parity passed for root, Java, and Kotlin
  locale pairs.
- Service coverage SVG parsed with `xmllint`, rendered to a 3800 x 2080 PNG via
  CairoSVG, and was visually inspected at full size.

## Future Guidance

Keep future Secrets Manager and Parameter Store core work low-level unless a
new issue explicitly targets Spring/Exposed/rotation/cache behavior. If adding
all-pages helpers later, make them opt-in by name, cold/lazy where possible, and
preserve SDK pagination/error details.
