# Issue #190 Spring AWS Core Foundation

## Context

`aws-spring-boot` service auto-configurations repeated credentials, region,
endpoint, and client-builder wiring. The 0.3.0 plan needs a shared foundation
before S3/SQS hardening work.

## Decision

Add shared `bluetape4k.aws` defaults and builder customizer hooks while keeping
service-specific properties higher precedence. Keep `AwsAutoConfiguration`
behind `bluetape4k.aws.enabled`, matching the Spring Boot auto-configuration
phase rule. Web-identity credentials remain opt-in and STS-classpath guarded.

## Outcome

S3, SQS, SNS, SES, KMS, and DynamoDB auto-configured clients now resolve common
region/endpoint defaults through the same helper. S3/SQS tests cover inheritance
and customizer ordering because they are the immediate 0.3.0 priority services.

## Verification

- `./gradlew --no-daemon --max-workers=1 :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin`
- `./gradlew --no-daemon --max-workers=1 :bluetape4k-aws-spring-boot:test --tests '*AwsAutoConfigurationTest' --tests '*S3AutoConfigurationTest' --tests '*SqsAutoConfigurationTest'`

## Future Guard

Add new Spring Boot AWS service clients through the shared defaults/customizer
helper first, then add service-specific behavior. Do not reintroduce per-service
credential/region/endpoint duplication.
