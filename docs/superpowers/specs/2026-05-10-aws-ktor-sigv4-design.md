# AWS Ktor SigV4 Client Plugin 설계

## 문제

이슈 #8은 API Gateway REST API 호출과 향후 Ktor AWS client에 AWS Signature Version 4 서명을 자동으로 적용하는 Ktor 3 `HttpClient` plugin을 요구한다.

## 근거

- 저장소 대상: `aws-ktor`는 WIP 모듈이며 현재 `build.gradle.kts`만 있고 source set은 아직 없다.
- 이슈 #8은 `AwsSigV4Plugin`, `install(AwsSigV4Plugin) { region = "ap-northeast-2"; service = "execute-api" }`, AWS Java SDK v2 credentials provider, header/path 서명 범위, API Gateway REST API 지원을 요구한다.
- Ktor 3.4.3 Context7 및 소스 근거: custom client plugin은 `createClientPlugin`을 사용한다. `Send` hook은 요청 본문이 `OutgoingContent`로 변환된 뒤 준비된 `HttpRequestBuilder`를 받는다.
- AWS SDK Java v2.44.4 로컬 소스 근거: `software.amazon.awssdk.auth.signer.Aws4Signer`는 deprecated이며 `http-auth-aws`의 `software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner`를 사용하라고 안내한다.
- `AwsV4HttpSigner`는 `SignRequest`를 통해 `SdkHttpRequest`에 서명하며 `SERVICE_SIGNING_NAME`, `REGION_NAME`, `AUTH_LOCATION`, `DOUBLE_URL_ENCODE`, `NORMALIZE_PATH`, `PAYLOAD_SIGNING_ENABLED`, `SIGNING_CLOCK` signer property를 지원한다.

## 설계

`io.bluetape4k.aws.ktor.client` 아래에 JVM 전용 Ktor client plugin을 추가한다.

공개 API:

- `createClientPlugin("AwsSigV4Plugin", ::AwsSigV4PluginConfig)`으로 생성하는 `AwsSigV4Plugin`.
- `region`, `service`, `credentialsProvider`, `authLocation`, `doubleUrlEncode`, `normalizePath`, `payloadSigningEnabled`, `signingClock`, `signer`를 제공하는 `AwsSigV4PluginConfig`.
- `Header`와 `QueryString`을 제공하는 `AwsSigV4AuthLocation` enum.

서명 동작:

- plugin 설치 시 `region`과 `service`가 비어 있지 않은지 검증한다.
- 각 outbound send마다 `AwsCredentialsProvider.resolveCredentials()`를 통해 자격 증명을 해석한다.
- 현재 `AwsV4HttpSigner` API를 직접 사용할 수 있도록 AWS SDK 자격 증명을 `AwsCredentialsIdentity` 또는 `AwsSessionCredentialsIdentity`로 변환한다.
- Ktor URL, method, header, query parameter, 지원하는 본문 payload를 `SdkHttpFullRequest`로 변환한다.
- 기본값으로 `AUTH_LOCATION=HEADER`를 사용한다. presigned 방식의 query parameter에는 `QUERY_STRING`을 사용할 수 있다.
- `proceed(request)` 전에 서명된 header와 query parameter를 Ktor request builder에 다시 적용한다.

Payload 지원:

- `OutgoingContent.NoContent`는 payload 없이 서명한다.
- `OutgoingContent.ByteArrayContent`는 정확한 byte array payload에 서명한다.
- client plugin에서 streaming 콘텐츠를 읽거나 replay하면 engine이 전송하기 전에 본문을 소비할 수 있으므로, `payloadSigningEnabled=true`일 때 다른 `OutgoingContent` 형식은 거부한다.
- `payloadSigningEnabled=false`이면 다른 콘텐츠 형식을 허용한다. signer는 payload를 받지 않고 signer property에 의존한다.

## 제외 범위

- Spring Boot를 변경하지 않는다.
- Ktor server를 통합하지 않는다.
- 이슈 #9의 S3 전용 client wrapper를 제공하지 않는다.
- custom SigV4 구현을 만들지 않는다.

## 테스트 요구사항

- 설정 검증을 단위 테스트한다.
- 결정론적 clock으로 header auth 직접 서명을 단위 테스트한다.
- session 자격 증명에 `X-Amz-Security-Token`이 포함되는지 단위 테스트한다.
- query-string auth가 SigV4 query parameter를 추가하는지 단위 테스트한다.
- payload 서명이 활성화되었을 때 지원하지 않는 streaming 본문이 실패하는지 단위 테스트한다.
- 로컬 test engine 또는 mock engine을 사용한 Ktor `HttpClient` smoke test로 plugin이 outbound 요청을 변경하는지 확인한다.

## Advisor 검토

- Claude Code advisor 시도: spec/plan 초안 작성 후 로컬 `claude -p --model claude-opus-4-7 --effort high`를 시작했지만 tool window 안에 출력이 없어 종료했다. Codex는 로컬 소스/Javadoc 근거와 범위가 좁은 테스트로 진행했다.
