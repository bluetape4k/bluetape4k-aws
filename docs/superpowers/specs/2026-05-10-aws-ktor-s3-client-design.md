# AWS Ktor S3 Client Design

## Problem

Issue #9 asks for an S3 HTTP client built on Ktor, using the already merged
`AwsSigV4Plugin` foundation from issue #8 instead of wrapping the AWS SDK v2
S3 client. The client must cover common object operations, multipart upload,
presigned URL generation, and streaming-friendly transfer paths.

## Evidence

- Repository target:
  `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/9-ktor-s3-client/aws-ktor`
- Issue #9 requires `S3KtorClient`, PutObject/GetObject/DeleteObject/ListObjects,
  multipart upload, presigned URL generation/download, and content streaming.
- `aws-ktor` currently exposes `AwsSigV4Plugin`, `AwsSigV4PluginConfig`, and
  `AwsSigV4AuthLocation` under
  `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/client/`.
- Existing `AwsSigV4Plugin` supports header signing and query-string signing
  through AWS SDK Java v2 `AwsV4HttpSigner`.
- Ktor 3 official docs show `HttpClient.get/post`, `setBody`, `body<T>()`,
  and `MockEngine` request capture as the current client/test surface.
- AWS S3 official REST API reference checked:
  - `PutObject`: `PUT /{Key+}` with object body.
  - `GetObject`: `GET /{Key+}` with body plus metadata headers.
  - `DeleteObject`: `DELETE /{Key+}`.
  - `ListObjectsV2`: `GET /?list-type=2` returns XML and can paginate.
  - Multipart: `POST /{Key+}?uploads`, `PUT /{Key+}?partNumber=N&uploadId=...`,
    `POST /{Key+}?uploadId=...`, and abort via `DELETE`.
  - Presigned URL: SigV4 query authentication uses `X-Amz-*` query parameters
    and is valid for at most seven days.

## API Design

Add package `io.bluetape4k.aws.ktor.s3`.

Primary public API:

- `S3KtorClient`
  - Owns a Ktor `HttpClient`, region, credentials provider, endpoint settings,
    and addressing mode.
  - Caller-owned constructor does not close the supplied `HttpClient`.
  - Factory-created client owns an internal `HttpClient` and closes it.
- `S3KtorClientConfig`
  - `region: String`
  - `credentialsProvider: AwsCredentialsProvider`
  - `endpointOverride: Url?`
  - `addressingStyle: S3KtorAddressingStyle`
  - `forcePathStyle: Boolean`
  - `payloadSigningEnabled: Boolean`
- `S3KtorAddressingStyle`
  - `VirtualHosted`
  - `Path`
- Request/response models:
  - `S3KtorObjectRef(bucket, key)`
  - `S3KtorPutObjectRequest`
  - `S3KtorPutObjectResponse`
  - `S3KtorGetObjectResponse`
  - `S3KtorDeleteObjectResponse`
  - `S3KtorListObjectsRequest`
  - `S3KtorListObjectsResponse`
  - `S3KtorObjectSummary`
  - `S3KtorMultipartUpload`
  - `S3KtorCompletedPart`
  - `S3KtorCompleteMultipartUploadResponse`

Operations:

- `putObject(bucket, key, bytes, contentType?, metadata?)`
- `putObject(request, body: OutgoingContent)`
- `getObject(bucket, key): S3KtorGetObjectResponse`
- `getObjectBytes(bucket, key): ByteArray`
- `getObjectStream(bucket, key): S3KtorStreamingObjectResponse`
- `deleteObject(bucket, key)`
- `listObjectsV2(request): S3KtorListObjectsResponse`
- `createMultipartUpload(bucket, key, contentType?, metadata?)`
- `uploadPart(bucket, key, uploadId, partNumber, bytes)`
- `uploadPart(bucket, key, uploadId, partNumber, body: OutgoingContent, contentLength: Long)`
- `completeMultipartUpload(bucket, key, uploadId, parts)`
- `abortMultipartUpload(bucket, key, uploadId)`
- `presignGetObject(bucket, key, expires)`
- `presignPutObject(bucket, key, expires)`

Convenience factory:

- `s3KtorClientOf(...)`

## HTTP / Signing Design

Default endpoint:

```text
https://{bucket}.s3.{region}.amazonaws.com/{encoded-key}
```

When `endpointOverride` is set, default to path-style addressing unless the
caller explicitly chooses virtual-hosted addressing. This keeps LocalStack and
S3-compatible endpoints straightforward:

```text
{endpointOverride}/{bucket}/{encoded-key}
```

Virtual-hosted addressing falls back to path-style when the bucket contains `.`
or is not DNS-compatible enough for TLS hostnames:

```text
^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$
```

Key encoding must preserve `/` as path separators while percent-encoding each
segment. Do not pass a full key as one Ktor `pathSegment`, because that encodes
slashes inside object keys.

Signing:

- Use `AwsSigV4Plugin` with `service = "s3"` for real requests.
- Force S3 signer flags for real requests and presigned URLs:

  | Property | Value | Reason |
  |---|---|---|
  | `DOUBLE_URL_ENCODE` | `false` | S3 canonicalization must not double-encode object keys. |
  | `NORMALIZE_PATH` | `false` | S3 keys can contain path-like segments and must not be normalized. |
  | `PAYLOAD_SIGNING_ENABLED` | `false` default | Enables `UNSIGNED-PAYLOAD` for streaming-friendly Ktor bodies. |
  | `AUTH_LOCATION` | `HEADER` or `QUERY_STRING` | Real request vs presigned URL. |
  | `EXPIRATION_DURATION` | presign only | Required for `X-Amz-Expires`. |

- Assert every signed S3 request includes `x-amz-content-sha256`; default value
  is `UNSIGNED-PAYLOAD`.
- ByteArray uploads may opt into payload signing later, but the first S3 client
  keeps unsigned payload mode for consistent byte/stream behavior.
- Generate presigned URLs directly with AWS SDK `AwsV4HttpSigner` and
  `AUTH_LOCATION = QUERY_STRING`; do not send a Ktor request just to sign.
- Enforce presign expiry range: 1 second through 7 days.
- `presignPutObject` does not pin a body hash unless a future API accepts an
  explicit payload hash; callers must sign any headers they require.
- Ktor engine-injected headers such as `Transfer-Encoding` are not part of the
  signed header set. Headers placed on the `HttpRequestBuilder` before signing
  are signed; generated transport headers are not.

## XML Parsing / Serialization

Avoid a new dependency. Use XXE-safe JDK XML APIs for the small S3 XML surface:

- Parse `ListObjectsV2` response XML into model values.
- Parse multipart initiation and completion XML.
- Serialize `CompleteMultipartUpload` XML from sorted completed parts.
- Disable external entities and DTDs in `DocumentBuilderFactory`.
- Serialize `CompleteMultipartUpload` with namespace
  `http://s3.amazonaws.com/doc/2006-03-01/`.
- Preserve multipart part ETags verbatim, including quotes.
- Parse S3 XML error responses for non-2xx responses and surface a typed
  `S3KtorException`.

This keeps `aws-ktor` dependency footprint small and avoids coupling public API
to Jackson XML.

## Non-goals

- No AWS SDK S3 client wrapper.
- No bucket management API in issue #9.
- No S3 Select, object ACL, tagging, copy, retention, or directory-bucket
  specialized behavior.
- No full TransferManager replacement.
- No retry policy abstraction beyond Ktor client configuration.
- No SigV4 streaming chunk signature
  (`STREAMING-AWS4-HMAC-SHA256-PAYLOAD`) in this issue.

## Test Requirements

- MockEngine tests for URL shape, method, headers, query parameters, and body.
- MockEngine tests for `x-amz-content-sha256`, signed header coverage, S3 key
  path encoding, and path-style fallback.
- XML parser tests for `ListObjectsV2`, multipart initiation, and completion.
- XML error parsing tests for S3 error envelopes.
- Continuation-token round-trip tests for values containing `=`, `+`, and `/`.
- Multipart ETag verbatim and sort-order tests.
- Presigned URL tests with deterministic clock validating `X-Amz-*` parameters.
- LocalStack integration smoke:
  - put bytes
  - get bytes
  - list objects
  - delete object
  - multipart upload with two parts when LocalStack supports the path
- README.md and README.ko.md synchronized with public API usage.

## Risks

- `AwsSigV4Plugin` currently rejects arbitrary streaming content when payload
  signing is enabled. The S3 client must set unsigned payload mode by default
  unless it can provide replayable byte content.
- S3-compatible endpoints vary in path-style and virtual-hosted support. Keep
  endpoint override path-style by default.
- Multipart completion requires exact part numbers and ETags from upload part
  responses. The client should preserve caller-provided part order by sorting by
  `partNumber` during XML serialization.
- Presigned URLs must not exceed seven days.

## Claude Code Opus Advisor

Artifact:
`/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/9-ktor-s3-client/.omx/artifacts/ask-claude-aws-ktor-s3-spec-plan-20260510-173549.md`

Accepted high findings:

- Force S3 signer flags: `doubleUrlEncode=false`, `normalizePath=false`,
  unsigned payload default, presign expiration property.
- Add bucket DNS/TLS fallback rule.
- Require `x-amz-content-sha256` tests.
- Add slash-preserving key encoding rule.
- Clarify streaming and signed-header boundaries.

Accepted medium findings:

- Caller-owned vs factory-owned client close semantics.
- Streaming upload part overload.
- Streaming get-object response.
- XXE-safe XML parsing.
- Multipart namespace and ETag preservation.
- Continuation-token encoding tests.
