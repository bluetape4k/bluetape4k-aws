# AWS Ktor SigV4 Client Plugin 계획

## 범위

이슈 #8을 `bluetape4k-aws/aws-ktor`에만 구현한다.

## 작업

1. `aws-ktor` 소스/resource 뼈대와 명시적인 AWS auth/Ktor 테스트 의존성을 추가한다.
2. `AwsSigV4PluginConfig`와 `AwsSigV4AuthLocation`을 구현한다.
3. Ktor 요청을 AWS 요청으로 변환하는 helper를 구현한다.
4. Ktor `createClientPlugin`과 `on(Send)`를 사용해 `AwsSigV4Plugin`을 구현한다.
5. 서명 출력, 설정 검증, session token, query auth, 지원하지 않는 payload 동작, HttpClient 통합을 집중적으로 검사하는 단위 테스트를 추가한다.
6. 언어 전환, 의존성 사용법, plugin 설정, payload 제한을 설명하는 `aws-ktor/README.md`와 `aws-ktor/README.ko.md`를 추가한다.
7. `:aws-ktor:test`, `:aws-ktor:compileKotlin`, 관련 문서/소스 검토를 실행한다.
8. Lore 프로토콜에 따라 commit하고 branch를 push한 뒤, #8을 닫는 draft PR을 생성한다.

## 검토 기록

- `AwsV4HttpSigner`를 채택한다. 로컬 AWS SDK v2.44.4 소스에서 deprecated로 표시된 `Aws4Signer`는 사용하지 않는다.
- `Send`는 변환된 `OutgoingContent`를 볼 수 있으므로 `onRequest` 대신 Ktor `Send`를 사용한다.
- 첫 구현은 header/query 서명에 집중한다. streaming payload 지원은 replay 가능한 콘텐츠를 명시적으로 감싸는 방식으로 나중에 추가할 수 있다.
