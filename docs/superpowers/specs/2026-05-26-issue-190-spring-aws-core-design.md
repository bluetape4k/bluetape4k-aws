# 이슈 #190 Spring Boot AWS Core 설계

날짜: 2026-05-26
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/190
Branch: `feat/190-spring-aws-core`

## 배경

`aws-spring-boot`는 이미 S3, SQS, SNS, SES, KMS, DynamoDB용 AWS SDK v2 client를
자동 설정한다. 각 서비스는 자격 증명, region, endpoint, HTTP-client 연결을 반복한다.
이슈 #190은 0.3.0 S3/SQS 강화 작업 전에 공유 기반을 도입한다.

## 목표

- 공유 `bluetape4k.aws.region`과 `bluetape4k.aws.endpoint-override` 기본값을 추가한다.
- core AWS 자동 설정이 서비스 자동 설정과 같은 조건부 phase 규칙을 따르도록 `bluetape4k.aws.enabled`를 추가한다.
- 기존 서비스별 `region`과 `endpoint-override` property의 source compatibility를 유지하고 공유 기본값보다 높은 우선순위를 부여한다.
- STS classpath로 보호되는 opt-in web-identity 자격 증명 지원을 추가한다.
- 순서가 지정된 전역 sync/async AWS SDK v2 builder customizer를 추가한다.
- typed 서비스별 builder customizer를 추가한다.
- S3와 SQS는 0.3.0 우선 서비스이므로 상속/customizer 동작을 직접 다룬다.

## 제외 범위

- AWSpring property 이름을 복제하지 않는다.
- awspring 의존성을 추가하지 않는다.
- 모든 서비스를 하나의 generic AWS client 추상화 뒤에 숨기지 않는다.
- #192/#193의 고급 S3 또는 SQS runtime 기능을 여기서 구현하지 않는다.

## 설계

prefix `bluetape4k.aws`에 `AwsProperties`를 추가한다. 서비스 자동 설정은 다음 우선순위로 client 기본값을 해석한다.

1. 서비스별 region/endpoint.
2. 공유 region/endpoint.
3. 둘 다 설정하지 않았을 때 AWS SDK 기본 동작.

Endpoint override에는 유효한 region이 필요하다. 서비스 endpoint는 공유 region을 사용할 수 있다. 공유 endpoint는 binding 시 공유 region을 요구한다.

Customizer는 다음과 같이 나눈다.

- 모든 sync AWS SDK v2 client builder용 `AwsSyncClientCustomizer`.
- 모든 async AWS SDK v2 client builder용 `AwsAsyncClientCustomizer`.
- typed 서비스별 builder용 `AwsClientCustomizer<B>`.

`AwsAutoConfiguration`이 공통 자격 증명 bean의 소유자로 유지된다.
`WebIdentityTokenFileCredentialsProvider`는 `bluetape4k.aws.credentials.web-identity.enabled=true`에서 opt-in이며 STS가 runtime classpath에 있을 때만 활성화된다.

## 위험

- `AwsClientCustomizer<B>`의 generic Spring bean 해석은 실제 `ApplicationContextRunner` 테스트로 검증해야 한다.
- endpoint 검증을 서비스 property class에서 유효 기본값 resolver로 옮겨도 기존 실패 동작을 보존해야 한다.
- Web-identity를 활성화하지 않은 애플리케이션에는 STS를 요구하지 않아야 한다.

## 검토 기록

구현은 공개 API KDoc을 영어로 유지하고 `README.md`와 `README.ko.md`를 모두 갱신해야 한다.
