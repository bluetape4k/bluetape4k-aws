# AWS Ktor S3 client 계획

## 범위

이슈 #9는 `aws-ktor`에만 구현한다. #9에 대해 검증된 별도 commit과 PR이 생길 때까지
`aws #1`을 시작하지 않는다.

## 작업

1. Baseline 검증
   - `./gradlew :aws-ktor:compileKotlin :aws-ktor:test --no-daemon`을 실행한다.
   - 기존 failure가 있으면 별도로 기록한다.
   - baseline failure가 `AwsSigV4Plugin` 또는 `aws-ktor` compile에 있으면 중단한다.
     그렇지 않으면 관련 없는 failure를 기록하고 계속한다.

2. 핵심 model/API
   - `io.bluetape4k.aws.ktor.s3` package를 추가한다.
   - configuration, addressing style, request, response model 타입을 추가한다.
   - public API에 실행 가능한 snippet이 포함된 한글 KDoc을 추가한다.

3. URL 및 signing 지원
   - virtual-hosted 및 path-style addressing용 endpoint builder를 구현한다.
   - 실제 client request에는 `service = "s3"`로 `AwsSigV4Plugin`을 사용한다.
   - `AwsV4HttpSigner` query auth를 사용하는 결정론적 presign helper를 추가한다.
   - presign 만료 범위를 강제한다.
   - 다음 S3 signer property를 강제한다.
     - `DOUBLE_URL_ENCODE=false`
     - `NORMALIZE_PATH=false`
     - `PAYLOAD_SIGNING_ENABLED=false`
     - 실제 request에는 `AUTH_LOCATION=HEADER`
     - presign에는 `AUTH_LOCATION=QUERY_STRING` 및 `EXPIRATION_DURATION`
   - signed S3 request에 `x-amz-content-sha256`가 있는지 검증한다.

4. Object operation
   - 4a. `ByteArray`와 `OutgoingContent`용 PutObject를 구현한다.
   - 4b. byte, streaming channel, metadata를 반환하는 GetObject를 구현한다.
   - 4c. DeleteObject를 구현한다.
   - 4d. pagination field와 continuation-token encoding test를 포함한 ListObjectsV2를 구현한다.

5. Multipart operation
   - CreateMultipartUpload을 구현한다.
   - 명시적인 `contentLength`와 함께 `ByteArray` 및 `OutgoingContent`용 UploadPart를 구현한다.
   - CompleteMultipartUpload XML serialization을 구현한다.
   - AbortMultipartUpload을 구현한다.

6. XML 지원
   - JDK XML API를 사용하는 작은 internal parser/serializer를 추가한다.
   - DTD/external-entity를 비활성화해 XML parsing을 구성한다.
   - S3 XML error envelope에서 typed `S3KtorException`을 생성한다.
   - unit test로 parser 동작을 검증한다.

7. Test
   - request 구성과 response parsing을 위한 MockEngine test를 추가한다.
   - 결정론적 presigned URL test를 추가한다.
   - signer flag, `x-amz-content-sha256`, key slash 보존, path-style fallback,
     multipart ETag 원문 처리, XML error parsing을 위한 MockEngine test를 추가한다.
   - 이 저장소에서 Ktor CIO와 LocalStack endpoint가 안정적으로 동작하면 기존 project
     test 규칙에 맞는 tag가 지정된 LocalStack integration smoke를 추가한다.
   - 기본 `:aws-ktor:test`를 안정적으로 유지한다. tag 또는 environment gate를
     적용한 경우 LocalStack command를 명시적으로 실행한다.
   - test는 `:aws-ktor:test` 아래에 집중한다.

8. 문서
   - `aws-ktor/README.md`와 `aws-ktor/README.ko.md`를 갱신한다.
   - root README에 동기화가 필요한 aws-ktor feature 목록이 있을 때만 갱신한다.

9. 검증
   - targeted compile/test를 실행한다.
     - `./gradlew :aws-ktor:compileKotlin :aws-ktor:compileTestKotlin --no-daemon`
     - `./gradlew :aws-ktor:test --no-daemon`
     - module이 detekt task를 제공하면 `./gradlew :aws-ktor:detekt --no-daemon`
   - `git diff --check`를 실행한다.
   - 변경 파일에 Tier 4 code review를 수행한다.

10. 전달
    - Lore protocol로 commit한다.
    - branch를 push한다.
    - `[feat]` title과 한글 body를 사용하고 `Closes #9`를 연결한 PR을 생성한다.
    - 그다음 별도의 worktree/branch에서 `aws #1`을 시작한다.

## Review 참고 사항

- Ktor 및 AWS 공식 문서를 external API의 근거 자료로 사용한다.
- JDK XML이 부족하다고 입증되지 않는 한 XML dependency 추가를 거부한다.
- 이후 #10/#11에서 plugin 및 endpoint/signing helper를 재사용할 수 있도록 S3 service client를 `AwsSigV4Plugin`과 분리한다.
- endpoint override에서는 LocalStack path-style을 기본으로 사용한다.

## Claude Code Opus 자문

Artifact:
`/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/9-ktor-s3-client/.omx/artifacts/ask-claude-aws-ktor-s3-spec-plan-20260510-173549.md`

수용한 수정:

- S3 전용 signer property와 presign expiration property를 추가했다.
- path-style fallback과 key slash 보존 작업을 추가했다.
- `x-amz-content-sha256`, XML error, continuation token, ETag test를 추가했다.
- XXE-safe XML parsing과 multipart namespace 요구 사항을 추가했다.
- streaming을 명시적으로 유지하도록 object operation 작업을 분할했다.

거부/보류:

- test에서 Ktor가 이 client path에 추가한다고 확인되지 않는 한 `Expect: 100-continue` 처리는 보류한다.
- SigV4 chunked streaming은 이슈 #9의 명시적인 non-goal이다.
