# Issue #190 Spring Boot AWS Core Design

Date: 2026-05-26
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/190
Branch: `feat/190-spring-aws-core`

## Context

`aws-spring-boot` already auto-configures AWS SDK v2 clients for S3, SQS, SNS,
SES, KMS, and DynamoDB. Each service repeats credentials, region, endpoint, and
HTTP-client wiring. Issue #190 introduces a shared foundation before the 0.3.0
S3/SQS hardening train.

## Goals

- Add shared `bluetape4k.aws.region` and `bluetape4k.aws.endpoint-override`
  defaults.
- Add `bluetape4k.aws.enabled` so the core AWS auto-configuration follows the
  same conditional phase rule as service auto-configurations.
- Keep existing service-specific `region` and `endpoint-override` properties
  source-compatible and higher precedence than shared defaults.
- Add opt-in web-identity credentials support guarded by the STS classpath.
- Add ordered global sync/async AWS SDK v2 builder customizers.
- Add typed service-specific builder customizers.
- Cover S3 and SQS inheritance/customizer behavior directly because they are
  the 0.3.0 priority services.

## Non-Goals

- Do not clone AWSpring property names.
- Do not add an awspring dependency.
- Do not hide all services behind one generic AWS client abstraction.
- Do not implement advanced S3 or SQS runtime features from #192/#193 here.

## Design

Add `AwsProperties` at prefix `bluetape4k.aws`. Service auto-configurations
resolve client defaults with this precedence:

1. Service-specific region/endpoint.
2. Shared region/endpoint.
3. AWS SDK default behavior when neither is set.

Endpoint override requires an effective region. A service endpoint can use a
shared region; a shared endpoint requires a shared region during binding.

Customizers are split into:

- `AwsSyncClientCustomizer` for every sync AWS SDK v2 client builder.
- `AwsAsyncClientCustomizer` for every async AWS SDK v2 client builder.
- `AwsClientCustomizer<B>` for typed service-specific builders.

`AwsAutoConfiguration` remains the owner of the common credentials bean.
`WebIdentityTokenFileCredentialsProvider` is opt-in under
`bluetape4k.aws.credentials.web-identity.enabled=true` and only activates when
STS is on the runtime classpath.

## Risks

- Generic Spring bean resolution for `AwsClientCustomizer<B>` must be verified
  with real `ApplicationContextRunner` tests.
- Moving endpoint validation from service property classes to the effective
  default resolver must preserve existing failure behavior.
- Web-identity support must not require STS for applications that do not enable
  it.

## Review Notes

Implementation must keep public API KDoc in English and update both
`README.md` and `README.ko.md`.
