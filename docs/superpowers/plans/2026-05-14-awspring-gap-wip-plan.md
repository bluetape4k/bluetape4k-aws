# Spring Cloud AWS gap / Exposed WIP 계획

작성일: 2026-05-14
최종 갱신: 2026-05-26

## 범위

`bluetape4k-aws`와 Spring Cloud AWS 4.x를 비교하고 Spring Boot와 Ktor 모두에서 JDBC/database 지원을 Exposed-first로 만들기로 한 후속 결정에서 도출된 feature backlog를 추적한다.

## 방향

- awspring JDBC API를 복제하지 않는다.
- `bluetape4k-exposed`를 database access surface로 사용한다. 이 WIP와 연결된 이슈에서 "Exposed"는 standalone raw Exposed integration이 아니라 `bluetape4k-exposed` project와 그 repository, transaction, audit, column convention을 의미한다.
- AWS integration은 configuration, secret, token 생성, framework wiring을 제공한다.
- Spring Boot와 Ktor adapter는 공유 core 계약 위에서 얇게 유지한다.
- compatibility가 명확한 adoption value를 주지 않는 한 Spring Integration 또는 blocking adapter compatibility보다 coroutine-native API를 우선한다.

## 완료된 baseline

후속 이슈가 더 좁은 hardening 범위를 다시 열지 않는 한 다음 항목은 이미 제공된 것으로 처리한다.

- #1 종료 / PR #29 merge: Spring Boot S3 auto-configuration.
- #2 종료 / PR #30 merge: Spring Boot SQS listener와 coroutine template.
- #3 종료 / PR #31 merge: Spring Boot DynamoDB coroutine repository.
- #4 종료 / PR #55 merge: Spring Boot SNS coroutine publisher.
- #5 종료 / PR #58 merge: Spring Boot KMS encryption 지원.
- #8 종료 / PR #27 merge: Ktor SigV4 client plugin.
- #9 종료 / PR #28 merge: Ktor S3 client upload/download.
- #10 종료 / PR #60 merge: Ktor SQS consumer runtime.
- #12 종료 / PR #54 merge: Spring Boot S3 example.
- #13 종료: Spring Boot SQS/SNS example.
- #15 종료 / PR #54 merge: Ktor S3 example.
- #59 종료: `@KmsEncrypted` field-level encryption.
- #6 종료 / PR #57, PR #84, PR #86 merge: Secrets Manager / Parameter Store loading, refresh 지원, refresh snapshot race 수정.
- #11 종료 / PR #87 merge: `:aws-kotlin` 기반 Ktor DynamoDB server plugin과 repository facade.
- #78 종료 / PR #94 merge: Spring Boot S3 transfer operation과 고급 transfer configuration.
- #79 종료 / PR #93 merge: Spring Boot SQS parity hardening과 Spring Boot example AOT wiring.
- #80 종료 / PR #95 merge: Spring Boot SNS SMS publishing과 HTTP(S) endpoint message parsing.

## 상태 동기화 기록

- #71은 종료됐다. SNS, KMS, remote-config 기능의 README coverage는 더 이상 active WIP로 추적하지 않는다.
- 2026-05-26 AWSpring 4.x gap 검토 후 새 AWSpring-parity epic을 생성했다.
  - #204 `[Epic] AWSpring-parity Spring Boot integrations`
  - #205 `[Epic] AWSpring-parity Ktor integrations`
- 0.3.0은 의도적으로 S3/SQS production hardening과 S3/SQS 구현을 일관되게 유지하는 데 필요한 최소 shared configuration 기반으로 좁혔다.
- 후속 release 계획에서 명시적으로 앞당기지 않는 한 DynamoDB, Exposed/database, CloudWatch/Logs, IMDS, DAX 작업은 backlog에 유지한다.

## 0.3.0 범위: S3/SQS production hardening

### 0.3.0에 유지

- #190 `feat(aws-spring-boot): add shared AWS core properties and client customizers`
  - Spring Boot S3/SQS region, endpoint, credential, client customization 일관성을 위한 기반.
- #197 `feat(aws-ktor): add shared AWS defaults and client customizer hooks`
  - Ktor S3/SQS plugin 기본값, client ownership, lifecycle 일관성을 위한 기반.
- #193 `feat(aws-spring-boot): add advanced SQS listener conversion, ack, retry, and observability`
  - typed conversion, manual acknowledgement, retry/backoff, interceptor 기반 observability hook으로 구현한 Spring Boot SQS production control. 직접 Micrometer auto-instrumentation은 interceptor 계약을 기반으로 한 후속 작업으로 남길 수 있다.
- #199 `feat(aws-ktor): add advanced SQS conversion, manual ack, retry, and observability`
  - Ktor SQS consumer runtime 운영 제어.
- #192 `feat(aws-spring-boot): add advanced S3 encryption, config reload, access grants, and vector support`
  - 0.3.0 범위는 S3 Environment config import/reload와 KMS 기반 byte-array client-side encryption으로 구현했다. Access Grants와 S3 Vector는 production-hardening 범위를 넘어 optional SDK/client surface를 추가하므로 연기했다.
- #203 `feat(aws-ktor): add advanced S3 encryption, access grants, vector, and config helpers`
  - 0.3.0 범위는 S3 encryption과 content-type/config helper다. Access Grants와 S3 Vector가 release 범위를 확장하면 분리하거나 연기할 수 있다.
- #182 `test: stabilize SNS-to-SQS fanout LocalStack coverage`
  - SQS hardening train을 위한 regression/stability 지원.
- #206 `feat(examples): add Spring Boot AWSpring-parity examples`
  - 0.3.0의 stretch 범위: Spring Boot S3/SQS example만 포함.
- #207 `feat(examples): add Ktor advanced AWS integration examples`
  - 0.3.0의 stretch 범위: Ktor S3/SQS example만 포함.

### backlog로 이동 / 유지

- #179 `feat: add aws-ktor DynamoDB integration`
- #180 `feat: wire aws-exposed settings through Spring Boot Secrets Manager and Parameter Store`
- #181 `feat: add Ktor AWS database settings plugin for exposed integration`
- #183 `test: share DynamoDB Local Testcontainers launcher across AWS and downstream repos`
- #191 `feat(aws-spring-boot): add optional DynamoDB DAX client integration`
- #194 `feat(aws-spring-boot): add CloudWatch and CloudWatch Logs auto-configuration`
- #196 `feat(aws-spring-boot): add optional EC2 Instance Metadata Service integration`
- #200 `feat(aws-ktor): add optional EC2 Instance Metadata Service helpers`
- #201 `feat(aws-ktor): add CloudWatch and CloudWatch Logs plugins`

### 권장 0.3.0 실행 순서

1. 기반 PR train: #190, #197.
2. SQS PR train: #193, #199, 이후 regression coverage로 #182.
3. S3 PR train: 좁힌 0.3.0 범위의 #192와 #203.
4. example PR train: 관련 S3/SQS API를 사용할 수 있게 된 뒤에만 #206과 #207.

## 활성 backlog

### Exposed-first AWS database 통합

- #74 `feat(aws): Exposed-first AWS database integration foundation` (종료)
  - shared database property, secret/config loading 계약, `bluetape4k-exposed` database factory, named database registry.
- #75 `feat(aws-spring-boot): Exposed database auto-configuration` (종료)
  - AWS config/secret 기반 `bluetape4k-exposed` database를 위한 Spring Boot 4 auto-configuration.
- #76 `feat(aws-ktor): AwsExposedPlugin for AWS-backed Exposed databases` (종료)
  - Ktor server plugin, application attribute, `bluetape4k-exposed` suspend transaction helper 제공.
- #77 `feat(aws): RDS IAM auth token provider for Exposed integrations` (종료)
  - `bluetape4k-exposed` database 생성 경로를 위한 IAM token password provider.
- #82 `feat(examples): Spring Boot and Ktor Exposed AWS database examples` (종료)
  - `bluetape4k-exposed`, Testcontainers PostgreSQL, local/mock AWS config를 사용하는 adoption example.
- #180 `feat: wire aws-exposed settings through Spring Boot Secrets Manager and Parameter Store`
  - 0.3.0 범위 축소 후 backlog.
- #181 `feat: add Ktor AWS database settings plugin for exposed integration`
  - 0.3.0 범위 축소 후 backlog.

권장 실행 순서:

1. #74 shared database 기반.
2. #75 Spring Boot Exposed 자동 구성.
3. #76 Ktor `AwsExposedPlugin` 적용.
4. #77 RDS IAM auth token provider 구현.
5. #82 예제.

### 남은 adoption/example

- #14 `feat(examples): spring-boot-dynamodb` (종료)
  - Spring Boot 4 + DynamoDB 예제.
- #16 `feat(examples): ktor-sqs` (종료)
  - Ktor + SQS 예제.
- #17 `feat(examples): ktor-dynamodb` (종료)
  - Ktor + DynamoDB example. PR #87로 unblock됨.
  - #206과 #207은 다음 S3/SQS 중심 example을 추적하며 non-S3/SQS example은 후속 작업으로 연기한다.

### AWSpring gap 강화

- #7 `feat(aws-spring-boot): SES email sender` (종료)
  - Spring Boot SES sender와 coroutine template.
- #81 `feat(aws): Kinesis and DynamoDB Streams coroutine Flow support` (종료)
  - Spring Integration/Kinesis binder style API를 대체하는 coroutine Flow 기반 streaming.
  - #204와 #205는 남은 AWSpring-parity backlog의 현재 parent epic이다.

## 낮은 우선순위 / 명시적 연기

- 전체 Spring Integration adapter 복제.
- awspring JDBC API 호환성.
- JPA/Hibernate 지원.
- 구체적인 bluetape4k 사용 사례가 나타나지 않는 한 이전 awspring 2.x의 RDS/EC2/ElastiCache/CloudFormation compatibility 작업.
- 실제 credential이 필요한 production AWS integration test.

## 향후 PR 검증 확인 목록

- 단순하지 않은 이슈 작업은 `docs/lessons/YYYY-MM-DD-{slug}.md`를 추가하거나 갱신한다.
- targeted module test를 실행한다.
- PR 생성 전에 관련 module build/check gate를 실행한다.
- `git diff --check`를 실행한다.
- 실제 실행한 명령을 PR 본문에 문서화한다.
