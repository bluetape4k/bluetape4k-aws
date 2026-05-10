# AWS Ktor S3 Client Plan

## Scope

Implement issue #9 in `aws-ktor` only. Do not start `aws #1` until #9 has its
own verified commit and PR.

## Tasks

1. Baseline verification
   - Run `./gradlew :aws-ktor:compileKotlin :aws-ktor:test --no-daemon`.
   - Record any pre-existing failures separately.
   - Abort if baseline failure is in `AwsSigV4Plugin` or `aws-ktor` compile;
     otherwise record unrelated failure and continue.

2. Core model/API
   - Add `io.bluetape4k.aws.ktor.s3` package.
   - Add configuration, addressing style, request, and response model types.
   - Add Korean KDoc with runnable snippets for public API.

3. URL and signing support
   - Implement endpoint builder for virtual-hosted and path-style addressing.
   - Use `AwsSigV4Plugin` with `service = "s3"` for actual client requests.
   - Add deterministic presign helper using `AwsV4HttpSigner` query auth.
   - Enforce presign expiry range.
   - Force S3 signer properties:
     - `DOUBLE_URL_ENCODE=false`
     - `NORMALIZE_PATH=false`
     - `PAYLOAD_SIGNING_ENABLED=false`
     - `AUTH_LOCATION=HEADER` for real requests
     - `AUTH_LOCATION=QUERY_STRING` and `EXPIRATION_DURATION` for presign
   - Assert `x-amz-content-sha256` is present on signed S3 requests.

4. Object operations
   - 4a. Implement PutObject for `ByteArray` and `OutgoingContent`.
   - 4b. Implement GetObject returning bytes, streaming channel, and metadata.
   - 4c. Implement DeleteObject.
   - 4d. Implement ListObjectsV2 with pagination fields and continuation-token
     encoding tests.

5. Multipart operations
   - Implement CreateMultipartUpload.
   - Implement UploadPart for `ByteArray` and `OutgoingContent` with explicit
     `contentLength`.
   - Implement CompleteMultipartUpload XML serialization.
   - Implement AbortMultipartUpload.

6. XML support
   - Add small internal parser/serializer using JDK XML APIs.
   - Configure XML parsing with DTD/external-entity disabled.
   - Add typed `S3KtorException` from S3 XML error envelopes.
   - Cover parser behavior with unit tests.

7. Tests
   - Add MockEngine tests for request construction and response parsing.
   - Add presigned URL deterministic tests.
   - Add MockEngine tests for signer flags, `x-amz-content-sha256`, key slash
     preservation, path-style fallback, multipart ETag verbatim handling, and
     XML error parsing.
   - Add LocalStack integration smoke tagged with the existing project test
     convention if Ktor CIO and LocalStack endpoint work reliably in this repo.
   - Keep default `:aws-ktor:test` stable; run LocalStack command explicitly if
     it is tagged or environment-gated.
   - Keep tests targeted under `:aws-ktor:test`.

8. Documentation
   - Update `aws-ktor/README.md` and `aws-ktor/README.ko.md`.
   - Update root README only if it has an aws-ktor feature list that needs sync.

9. Verification
   - Run targeted compile/tests:
     - `./gradlew :aws-ktor:compileKotlin :aws-ktor:compileTestKotlin --no-daemon`
     - `./gradlew :aws-ktor:test --no-daemon`
     - `./gradlew :aws-ktor:detekt --no-daemon` if the module exposes a detekt task
   - Run `git diff --check`.
   - Perform Tier 4 code review over changed files.

10. Delivery
    - Commit with Lore protocol.
    - Push branch.
    - Open PR with `[feat]` title and Korean body, linking `Closes #9`.
    - Then start `aws #1` in a separate worktree/branch.

## Review Notes

- Use Ktor and AWS official docs as external API ground truth.
- Reject adding an XML dependency unless JDK XML proves insufficient.
- Keep S3 service client separate from `AwsSigV4Plugin` so #10/#11 can reuse
  plugin and endpoint/signing helpers later.
- LocalStack path-style is the default for endpoint override.

## Claude Code Opus Advisor

Artifact:
`/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/9-ktor-s3-client/.omx/artifacts/ask-claude-aws-ktor-s3-spec-plan-20260510-173549.md`

Accepted edits:

- Added S3-specific signer properties and presign expiration property.
- Added path-style fallback and key slash preservation tasks.
- Added `x-amz-content-sha256`, XML error, continuation token, and ETag tests.
- Added XXE-safe XML parsing and multipart namespace requirements.
- Split object operation tasks to keep streaming explicit.

Rejected/deferred:

- `Expect: 100-continue` handling is deferred unless tests show Ktor adds it in
  this client path.
- SigV4 chunked streaming is explicitly non-goal for issue #9.
