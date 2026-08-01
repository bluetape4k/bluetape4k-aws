# 이슈 #197 Ktor AWS Core 설계

날짜: 2026-05-26
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/197
Branch: `feat/197-ktor-aws-core`

## 목표

`aws-ktor`에 opt-in 공유 AWS 기본값 계층을 추가한다. 그러면 S3, SQS, DynamoDB,
AWS 기반 Exposed Ktor 통합이 기존 서비스별 API를 보존하면서 하나의 application 수준 설정 모델을 문서화할 수 있다.

## 현재 근거

- `S3KtorClient`는 `s3KtorClientOf`로 생성된 경우에만 내부 Ktor `HttpClient`를 소유한다.
- `SqsConsumer`는 이전에 주입된 `SqsAsyncClient`를 요구했고 이를 닫지 않았다.
- `DynamoDbKtorPlugin`은 이미 주입된 client와 plugin이 생성한 client의 소유권을 모두 지원한다.
- `AwsExposedPlugin`은 Exposed registry 수명 주기를 소유하며 AWS SDK client는 소유하지 않는다.
- `aws-ktor/README.md`에는 SQS sequence 이미지가 있지만 통합 경계를 설명하는 모듈 수준 아키텍처 이미지는 없었다.

## 설계

application attribute에 `AwsKtorDefaults`를 저장하는 Ktor application plugin `AwsKtorCore`를 도입한다.

- `region`
- `endpointOverride`
- AWS SDK Java v2 자격 증명 provider
- AWS SDK for Kotlin 자격 증명 provider
- signing clock
- AWS SDK for Kotlin용 HTTP engine
- Ktor `HttpClient`, SQS async client builder, DynamoDB client builder용 customizer

서비스별 설정은 공유 기본값을 덮어쓴다. `AwsKtorDefaults`는 `AbstractValueObject`를 확장하여 bluetape4k value-object pattern을 따른다. Runtime collaborator는 transient이고 endpoint override는 serializable string 상태로 저장하며 공개 접근에서는 Ktor `Url`을 계속 노출한다.

## 범위

- `AwsKtorCore`와 기본값/customizer type을 추가한다.
- `AwsKtorDefaults`를 받는 S3 factory overload를 추가한다.
- client가 주입되지 않았을 때 `SqsConsumer`가 plugin 소유 SQS client를 생성하게 한다.
- `DynamoDbKtorPlugin`이 plugin 생성 client에 공유 기본값을 상속하게 한다.
- 영어/한국어 README를 갱신한다.
- `aws-ktor`에 Graphviz 기반 아키텍처 PNG/SVG를 추가한다.

## 제외 범위

- Spring Boot 또는 awspring 의존성을 추가하지 않는다.
- 서비스별 동작을 숨기는 generic AWS client 추상화를 만들지 않는다.
- Exposed AWS SDK client를 자동으로 생성하지 않는다. Exposed는 registry 중심으로 유지한다.

## 위험

- customizer interface의 공개 compile-only AWS 서비스 type을 사용하는 통합에서는 애플리케이션이 해당 런타임 의존성을 추가해야 한다.
- plugin이 생성한 SQS client는 한 번만 닫아야 하며 주입된 client의 소유권을 바꾸지 않아야 한다.
- README 아키텍처 다이어그램은 소스에서 도출하고 시각적으로 검증해야 하며 Mermaid 색상만 바꾼 형태여서는 안 된다.
