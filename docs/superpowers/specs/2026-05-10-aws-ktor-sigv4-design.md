# AWS Ktor SigV4 Client Plugin Design

## Problem

Issue #8 asks for a Ktor 3 `HttpClient` plugin that applies AWS Signature Version 4 signing automatically for API Gateway REST API calls and future Ktor AWS clients.

## Evidence

- Repository target: `aws-ktor` is a WIP module and currently has only `build.gradle.kts`; no source set exists yet.
- Issue #8 requires `AwsSigV4Plugin`, `install(AwsSigV4Plugin) { region = "ap-northeast-2"; service = "execute-api" }`, AWS Java SDK v2 credentials providers, header/path signing scope, and API Gateway REST API support.
- Ktor 3.4.3 Context7 and source evidence: custom client plugins use `createClientPlugin`, and the `Send` hook receives the prepared `HttpRequestBuilder` after the request body is transformed to `OutgoingContent`.
- AWS SDK Java v2.44.4 local source evidence: `software.amazon.awssdk.auth.signer.Aws4Signer` is deprecated and says to use `software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner` from `http-auth-aws`.
- `AwsV4HttpSigner` signs `SdkHttpRequest` through `SignRequest` and supports signer properties for `SERVICE_SIGNING_NAME`, `REGION_NAME`, `AUTH_LOCATION`, `DOUBLE_URL_ENCODE`, `NORMALIZE_PATH`, `PAYLOAD_SIGNING_ENABLED`, and `SIGNING_CLOCK`.

## Design

Add a JVM-only Ktor client plugin under `io.bluetape4k.aws.ktor.client`.

Public API:

- `AwsSigV4Plugin`, created with `createClientPlugin("AwsSigV4Plugin", ::AwsSigV4PluginConfig)`.
- `AwsSigV4PluginConfig` with `region`, `service`, `credentialsProvider`, `authLocation`, `doubleUrlEncode`, `normalizePath`, `payloadSigningEnabled`, `signingClock`, and `signer`.
- `AwsSigV4AuthLocation` enum with `Header` and `QueryString`.

Signing behavior:

- Validate that `region` and `service` are not blank at plugin install time.
- Resolve credentials through `AwsCredentialsProvider.resolveCredentials()` for each outbound send.
- Convert AWS SDK credentials to `AwsCredentialsIdentity` or `AwsSessionCredentialsIdentity` so the current `AwsV4HttpSigner` API is used directly.
- Convert Ktor URL, method, headers, query parameters, and supported body payload to `SdkHttpFullRequest`.
- Use `AUTH_LOCATION=HEADER` by default; `QUERY_STRING` is available for presigned-style query parameters.
- Apply the signed headers and query parameters back to the Ktor request builder before `proceed(request)`.

Payload support:

- `OutgoingContent.NoContent` signs without a payload.
- `OutgoingContent.ByteArrayContent` signs the exact byte array payload.
- Other `OutgoingContent` types are rejected when `payloadSigningEnabled=true`, because reading or replaying streaming content in a client plugin can consume the body before the engine sends it.
- Other content types are allowed when `payloadSigningEnabled=false`; the signer receives no payload and relies on signer properties.

## Non-goals

- No Spring Boot changes.
- No Ktor server integration.
- No S3-specific client wrapper for issue #9.
- No custom SigV4 implementation.

## Test Requirements

- Unit-test config validation.
- Unit-test direct signing for header auth with deterministic clock.
- Unit-test session credentials include `X-Amz-Security-Token`.
- Unit-test query-string auth adds SigV4 query parameters.
- Unit-test unsupported streaming body fails when payload signing is enabled.
- Ktor `HttpClient` smoke test using a local test engine or mock engine to verify the plugin mutates outbound requests.

## Advisor Review

- Claude Code advisor attempt: local `claude -p --model claude-opus-4-7 --effort high` was started after spec/plan drafting but produced no output within the tool window and was terminated. Codex proceeded with local source/Javadoc evidence and targeted tests.
