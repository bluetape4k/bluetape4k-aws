# AWS Ktor S3 client 설계

## 문제

이슈 #9는 AWS SDK v2 S3 client를 wrapping하지 않고 이미 merge된 이슈 #8의 `AwsSigV4Plugin` 기반을 사용하는 Ktor 기반 S3 HTTP client를 요청한다. client는 일반적인 object operation, multipart upload, presigned URL 생성, streaming 친화적인 전송 경로를 지원해야 한다.

## 근거

- 저장소 대상:
  `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/9-ktor-s3-client/aws-ktor`
- 이슈 #9는 `S3KtorClient`, PutObject/GetObject/DeleteObject/ListObjects, multipart upload, presigned URL 생성/download, content streaming을 요구한다.
- `aws-ktor`는 현재 `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/client/` 아래에서 `AwsSigV4Plugin`, `AwsSigV4PluginConfig`, `AwsSigV4AuthLocation`을 제공한다.
- 기존 `AwsSigV4Plugin`은 AWS SDK Java v2 `AwsV4HttpSigner`를 통해 header signing과 query-string signing을 지원한다.
- Ktor 3 공식 문서는 현재 client/test surface로 `HttpClient.get/post`, `setBody`, `body<T>()`, `MockEngine` request capture를 제시한다.
- AWS S3 공식 REST API reference 확인 결과:
  - `PutObject`: object body를 포함한 `PUT /{Key+}`.
  - `GetObject`: body와 metadata header를 포함한 `GET /{Key+}`.
  - `DeleteObject`: `DELETE /{Key+}`.
  - `ListObjectsV2`: `GET /?list-type=2`는 XML을 반환하고 pagination할 수 있다.
  - multipart: `POST /{Key+}?uploads`, `PUT /{Key+}?partNumber=N&uploadId=...`, `POST /{Key+}?uploadId=...`, `DELETE`를 통한 중단.
  - presigned URL: SigV4 query 인증은 `X-Amz-*` query parameter를 사용하며 최대 7일 동안 유효하다.

## API 설계

`io.bluetape4k.aws.ktor.s3` package를 추가한다.

주요 public API:

- `S3KtorClient`
  - Ktor `HttpClient`, region, credentials provider, endpoint setting, addressing mode를 소유한다.
  - caller-owned constructor는 전달받은 `HttpClient`를 닫지 않는다.
  - factory가 생성한 client는 내부 `HttpClient`를 소유하고 닫는다.
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
- request/response 모델:
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

작업:

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

편의 factory:

- `s3KtorClientOf(...)`

## HTTP / signing 설계

기본 endpoint:

```text
https://{bucket}.s3.{region}.amazonaws.com/{encoded-key}
```

`endpointOverride`를 설정하면 호출자가 virtual-hosted addressing을 명시적으로 선택하지 않는 한 path-style addressing을 기본값으로 사용한다. 이렇게 하면 LocalStack과 S3-compatible endpoint를 단순하게 유지할 수 있다.

```text
{endpointOverride}/{bucket}/{encoded-key}
```

bucket에 `.`이 포함되거나 TLS hostname에 충분히 DNS-compatible하지 않으면 virtual-hosted addressing이 path-style로 fallback한다.

```text
^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$
```

key encoding은 각 segment를 percent-encoding하면서 `/`를 path separator로 보존해야 한다. 전체 key를 하나의 Ktor `pathSegment`로 전달하면 object key 내부의 slash가 encoding되므로 그렇게 하지 않는다.

서명:

- 실제 request에는 `service = "s3"`로 `AwsSigV4Plugin`을 사용한다.
- 실제 request와 presigned URL에 S3 signer flag를 강제한다.

  | property | value | 근거 |
  |---|---|---|
  | `DOUBLE_URL_ENCODE` | `false` | S3 canonicalization에서 object key를 double-encode하지 않아야 한다. |
  | `NORMALIZE_PATH` | `false` | S3 key는 path와 유사한 segment를 포함할 수 있으며 normalize하면 안 된다. |
  | `PAYLOAD_SIGNING_ENABLED` | `false` 기본값 | streaming 친화적인 Ktor body에 `UNSIGNED-PAYLOAD`를 사용한다. |
  | `AUTH_LOCATION` | `HEADER` 또는 `QUERY_STRING` | 실제 request와 presigned URL을 구분한다. |
  | `EXPIRATION_DURATION` | presign 전용 | `X-Amz-Expires`에 필요하다. |

- 서명된 모든 S3 request에 `x-amz-content-sha256`가 포함되고 기본값이 `UNSIGNED-PAYLOAD`인지 assertion한다.
- ByteArray upload는 나중에 payload signing을 선택할 수 있지만, 첫 S3 client는 byte/stream 동작을 일관되게 유지하도록 unsigned payload mode를 사용한다.
- AWS SDK `AwsV4HttpSigner`와 `AUTH_LOCATION = QUERY_STRING`으로 presigned URL을 직접 생성한다. signing만을 위해 Ktor request를 보내지 않는다.
- presign 만료 범위를 1초부터 7일까지로 강제한다.
- future API가 명시적인 payload hash를 받기 전까지 `presignPutObject`는 body hash를 고정하지 않는다. 호출자는 필요한 모든 header를 서명해야 한다.
- `Transfer-Encoding`처럼 Ktor engine이 주입하는 header는 signed header set에 포함되지 않는다. signing 전에 `HttpRequestBuilder`에 둔 header는 서명하지만 생성된 transport header는 서명하지 않는다.

## XML 해석 / 직렬화

새 dependency를 추가하지 않는다. 작은 S3 XML surface에는 XXE-safe JDK XML API를 사용한다.

- `ListObjectsV2` response XML을 model value로 parse한다.
- multipart 시작 및 완료 XML을 parse한다.
- 정렬한 completed part로 `CompleteMultipartUpload` XML을 serialize한다.
- `DocumentBuilderFactory`에서 external entity와 DTD를 비활성화한다.
- namespace `http://s3.amazonaws.com/doc/2006-03-01/`을 사용해 `CompleteMultipartUpload`를 serialize한다.
- 따옴표를 포함한 multipart part ETag를 그대로 보존한다.
- non-2xx response의 S3 XML error response를 parse하고 typed `S3KtorException`을 노출한다.

이 방식은 `aws-ktor` dependency footprint를 작게 유지하고 public API가 Jackson XML에 결합되는 것을 피한다.

## 목표가 아닌 항목

- AWS SDK S3 client wrapper는 제공하지 않는다.
- 이슈 #9에서는 bucket 관리 API를 제공하지 않는다.
- S3 Select, object ACL, tagging, copy, retention, directory-bucket 전용 동작을 제공하지 않는다.
- 완전한 TransferManager 대체 기능을 제공하지 않는다.
- Ktor client configuration을 넘어서는 retry policy abstraction을 제공하지 않는다.
- 이 이슈에서는 SigV4 streaming chunk signature(`STREAMING-AWS4-HMAC-SHA256-PAYLOAD`)를 제공하지 않는다.

## test 요구 사항

- URL shape, method, header, query parameter, body를 검증하는 MockEngine test.
- `x-amz-content-sha256`, signed header coverage, S3 key path encoding, path-style fallback을 검증하는 MockEngine test.
- `ListObjectsV2`, multipart 시작 및 완료를 검증하는 XML parser test.
- S3 error envelope를 검증하는 XML error parsing test.
- `=`, `+`, `/`를 포함한 value의 continuation-token round-trip test.
- multipart ETag 원문 보존 및 sort-order test.
- deterministic clock으로 `X-Amz-*` parameter를 검증하는 presigned URL test.
- LocalStack 통합 smoke:
  - byte 저장
  - byte 조회
  - object 목록 조회
  - object 삭제
  - LocalStack이 해당 경로를 지원할 때 두 part로 multipart upload
- public API 사용법과 README.md, README.ko.md를 동기화한다.

## 위험

- 현재 `AwsSigV4Plugin`은 payload signing이 활성화되면 임의의 streaming content를 거부한다. replay 가능한 byte content를 제공할 수 없다면 S3 client는 unsigned payload mode를 기본값으로 설정해야 한다.
- S3-compatible endpoint마다 path-style과 virtual-hosted 지원이 다르므로 endpoint override에는 path-style을 기본값으로 유지한다.
- multipart 완료에는 upload part response의 정확한 part number와 ETag가 필요하다. client는 XML serialization 중 `partNumber`로 정렬해 호출자가 제공한 part 순서를 보존해야 한다.
- presigned URL은 7일을 초과할 수 없다.

## Claude Code Opus 자문

산출물:
`/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/9-ktor-s3-client/.omx/artifacts/ask-claude-aws-ktor-s3-spec-plan-20260510-173549.md`

수용한 high 발견 사항:

- S3 signer flag 강제: `doubleUrlEncode=false`, `normalizePath=false`, unsigned payload 기본값, presign expiration property.
- bucket DNS/TLS fallback 규칙 추가.
- `x-amz-content-sha256` test 요구.
- slash를 보존하는 key encoding 규칙 추가.
- streaming과 signed-header 경계 명확화.

수용한 medium 발견 사항:

- caller-owned와 factory-owned client의 close 의미.
- streaming upload part overload 추가.
- streaming get-object response 추가.
- XXE-safe XML 해석.
- multipart namespace와 ETag 보존.
- continuation-token encoding test 추가.
