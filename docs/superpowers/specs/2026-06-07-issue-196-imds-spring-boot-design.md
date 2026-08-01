# 이슈 #196 - Spring Boot IMDS 통합 명세

날짜: 2026-06-07
이슈: #196 `feat(aws-spring-boot): add optional EC2 Instance Metadata Service integration`
작업 유형: Type A 전체 기능

## 배경

`aws-spring-boot`는 이미 공유 AWS 기본값과 S3, SQS, SNS, KMS, DynamoDB, SES, CloudWatch의 선택적 서비스 자동 설정을 제공한다. 아직 EC2 Instance Metadata Service facade는 노출하지 않는다. EC2에서 실행하는 애플리케이션이 instance id, region, availability zone, instance type, IAM role 이름 같은 metadata를 사용하려면 여전히 AWS SDK IMDS 호출에 직접 binding해야 한다.

현재 근거:

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring`에는 `imds` package가 없다.
- `gradle/libs.versions.toml`에는 `ec2`, `sts`, 서비스 client용 AWS SDK v2 alias가 있지만 `software.amazon.awssdk:imds`는 없다.
- Maven Central은 저장소 AWS SDK v2 version line과 일치하는 `software.amazon.awssdk:imds:2.46.0`을 제공한다.
- AWS SDK v2 `Ec2MetadataAsyncClient`는 `get(String)`, `endpoint(URI)`, `endpointMode(EndpointMode)`, `tokenTtl(Duration)`, `retryPolicy(Ec2MetadataRetryPolicy)`, async HTTP client 설정을 노출한다.

## 목표

- AWS SDK v2 IMDS용 선택적 Spring Boot 자동 설정을 추가한다.
- `Ec2MetadataAsyncClient`를 감싼 coroutine 친화적인 `ImdsOperations` facade를 제공한다.
- EC2 밖에서도 application startup이 안전하게 유지되도록 bean 생성 시 IMDS를 호출하지 않는다.
- 모든 metadata 호출을 operation timeout과 보수적인 retry 기본값으로 제한한다.
- EC2 전용 동작을 문서화하고 자격 증명 비노출을 명시한다.

## 제외 범위

- `DefaultCredentialsProvider`를 IMDS 전용 자격 증명 logic으로 교체하지 않는다.
- API, log, actuator data, 예제, README snippet을 통해 임시 자격 증명 값을 노출하지 않는다.
- Ktor IMDS 지원을 추가하지 않는다. 해당 adapter는 이슈 #200이 담당한다.
- 실제 EC2 또는 AWS 자격 증명 통합 테스트를 요구하지 않는다.

## 공개 API

`io.bluetape4k.aws.spring.imds` package를 추가한다.

예상 type:

- `ImdsProperties`
  - Prefix: `bluetape4k.aws.imds`
  - Field: `enabled`, `endpoint`, `endpointMode`, `tokenTtl`,
    `requestTimeout`, `retries`.
  - 기본값: 활성화, IPv4 endpoint mode, 6시간 token TTL, 짧은 operation timeout, 0 또는 매우 적은 retry 수.
- `ImdsOperations`
  - `suspend fun get(path: String): String`
  - `suspend fun getList(path: String): List<String>`
  - 공통 helper: `instanceId`, `availabilityZone`, `region`,
    `instanceType`, `localIpv4`, `iamRoleNames`.
- `ImdsCoroutinesTemplate`
  - `Ec2MetadataAsyncClient`에 위임한다.
  - 모든 호출에 `withTimeout(properties.requestTimeout)`을 적용한다.
  - bluetape4k 검증 helper로 호출자 경로를 검증한다.
- `ImdsAutoConfiguration`
  - `Ec2MetadataAsyncClient`와 `SdkAsyncHttpClient` class를 조건으로 보호한다.
  - 활성화되면 `Ec2MetadataAsyncClient`와 `ImdsOperations`를 생성한다.
  - 사용자가 제공한 client 또는 operation bean이 있으면 물러난다.

## 설계 규칙

- `compileOnly(libs.aws2.imds)`와 이에 대응하는 `testImplementation`을 사용한다.
- property class 하나, 자동 설정 class 하나, operation interface, coroutine template, `AutoConfiguration.imports` 등록으로 구성된 기존 AWS Spring Boot pattern을 우선한다.
- 자동 설정 중에 metadata probe를 실행하지 않는다.
- 기본적으로 `Ec2MetadataRetryPolicy.none()` 또는 이에 상응하는 제한된 retry policy를 사용한다.
- 사용할 수 있으면 `SdkAsyncHttpClient` bean을 재사용한다.
- IMDS 경로 helper를 low-level이며 명시적인 형태로 유지한다. IAM security-credentials endpoint에서 자격 증명을 추론하지 않는다.

## 테스트

필수 테스트:

- 활성화되면 자동 설정이 client와 operation을 등록한다.
- 비활성화되면 자동 설정이 물러난다.
- 사용자가 제공한 `Ec2MetadataAsyncClient`와 `ImdsOperations` bean이 우선한다.
- SDK IMDS class가 없으면 filtered class loader가 IMDS 연결을 비활성화한다.
- Property가 endpoint, endpoint mode, token TTL, request timeout, retry 수를 binding한다.
- `ImdsCoroutinesTemplate`이 빈 경로를 검증한다.
- `ImdsCoroutinesTemplate`이 string 및 list 응답을 변환한다.
- Timeout 처리가 완료되지 않는 future를 hang 없이 timeout 실패로 바꾼다.

## 문서

root 및 모듈 README locale 세트를 갱신한다.

- 서비스 커버리지 및 의존성 절에서 Spring Boot IMDS 지원을 언급한다.
- `bluetape4k.aws.imds`의 짧은 설정 snippet을 추가한다.
- `ImdsOperations` 사용 예제를 추가한다.
- IMDS는 EC2 전용이며 EKS/IRSA 대체품으로 사용해서는 안 된다고 설명한다.

## DoD

- Spec 검토: `P0=0`, `P1=0`.
- Plan 검토: `P0=0`, `P1=0`.
- 범위가 좁은 IMDS 테스트를 통과한다.
- 전체 `:bluetape4k-aws-spring-boot:test`를 통과한다.
- `:bluetape4k-aws-spring-boot:compileKotlin`을 통과한다.
- `dependencyInsight`에서 `software.amazon.awssdk:imds:2.46.0`을 확인한다.
- `git diff --check`를 통과한다.
- PR 본문이 `## DoD Status`로 끝난다.
