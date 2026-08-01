# Issue #203 Ktor 고급 S3 helper

## 배경

`aws-ktor`에는 핵심 object 작업을 위한 REST 우선 `S3KtorClient`가 이미 있었다. Issue
#203에서는 기본 S3 사용에 service-client 의존성을 강제하지 않으면서 고급 S3 helper를
요청했다.

## 결정

기본 API에 S3 Control, S3 Vector, KMS SDK 의존성을 도입하지 않고 기존 REST client
위에 opt-in helper를 추가한다. Client-side 암호화는 data-key provider interface를
사용해 모든 사용자에게 KMS 의존성을 강제하지 않고 필요한 애플리케이션만 연결한다.

## 결과

- Content-type을 감지하는 upload helper를 추가했다.
- SSE-S3, SSE-KMS/DSSE-KMS, bucket key, SSE-C용 server-side 암호화 header model을 추가했다.
- 암호화한 data key와 nonce를 S3 metadata에 저장하는 AES-GCM client-side envelope 암호화를 추가했다.
- S3 text config object put/get helper를 추가했다.
- Floci 기반 S3 round-trip 검증을 추가했다.
- README 아키텍처 및 sequence 다이어그램을 SVG source와 PNG embed로 추가했다.

## 검증 증거

- `:bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:compileTestKotlin`
  통과했다.
- `:bluetape4k-aws-ktor:test --tests '*S3KtorClientTest' --tests '*S3KtorClientFlociTest' --rerun-tasks`
  테스트 15개가 통과했다.
- `:bluetape4k-aws-ktor:test`에서 테스트 63개가 통과했다.
- `git diff --check`가 통과했다.
- `gitleaks detect --source . --redact --no-git --config .gitleaks.toml`
  entropy가 높은 test key 문자열을 생성한 byte로 바꾼 뒤 통과했다.
- README 다이어그램 SVG가 `xmllint --noout`을 통과했다. PNG는 1160x760 및
  1180x700으로 렌더링되며 `README.md`와 `README.ko.md`에 모두 연결되어 있다.

## 향후 보호 장치

S3 Access Grants 또는 S3 Vector SDK client를 `aws-ktor`의 필수 runtime 의존성으로
추가하지 않는다. 서비스 API와 의존성 footprint가 wrapper를 만들 만큼 안정될 때까지
선택 사항 또는 애플리케이션 소유로 유지한다.
