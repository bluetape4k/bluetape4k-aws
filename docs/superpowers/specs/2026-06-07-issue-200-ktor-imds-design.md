# 이슈 #200 - Ktor IMDS Helper 명세

날짜: 2026-06-07
이슈: #200 `feat(aws-ktor): add optional EC2 Instance Metadata Service helpers`
작업 유형: Type A 전체 기능

## 배경

`aws-spring-boot`는 이제 #196 / PR #277에서 추가한 선택적 EC2 Instance Metadata Service 지원을 제공한다. `aws-ktor`는 `AwsKtorCore`를 통해 공유 application AWS 기본값을 제공하며 SigV4, S3, SQS, DynamoDB, AWS 기반 Exposed 데이터베이스의 Ktor 통합도 갖추고 있다. 아직 IMDS package는 노출하지 않는다.

현재 근거:

- `gradle/libs.versions.toml`에 `aws2-imds`가 있다.
- `aws-ktor/build.gradle.kts`에는 아직 IMDS 의존성이 없다.
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor`에는 `imds` package가 없다.
- Spring Boot IMDS 공개 동작은 이미 `ImdsOperations`, `ImdsCoroutinesTemplate`, 제한된 request timeout, 자격 증명 문서 비노출로 정의되어 있다.
- `AwsKtorCore`는 공유 region, endpoint override, 자격 증명 provider, clock, engine, 서비스 customizer를 Ktor application attribute에 저장한다.

## 목표

- AWS SDK v2 `Ec2MetadataAsyncClient` 기반 선택적 Ktor IMDS helper를 추가한다.
- 안전한 EC2 metadata 읽기를 위한 coroutine operation을 제공한다.
- IMDS operation을 application attribute에 저장하는 Ktor application plugin을 제공한다.
- EC2 밖에서도 startup이 안전하게 유지되도록 plugin 설치 시 IMDS를 호출하지 않는다.
- 모든 metadata operation을 request timeout으로 제한한다.
- application 종료 시 plugin이 생성한 IMDS client만 닫는다.
- EC2 전용 동작과 자격 증명 비노출을 문서화한다.

## 제외 범위

- IMDS를 기본 자격 증명 전략으로 사용하지 않는다.
- API, route, log, metric, DTO, 예제를 통해 임시 자격 증명 문서를 노출하지 않는다.
- Spring Boot API 또는 property binding을 추가하지 않는다.
- 실제 EC2 또는 AWS 자격 증명 테스트를 요구하지 않는다.
- `AwsKtorCore.endpointOverride`를 자동 상속하지 않는다. IMDS endpoint 설정은 metadata service 전용이며 일반 AWS 서비스 endpoint가 아니다.

## 공개 API

`io.bluetape4k.aws.ktor.imds` package를 추가한다.

예상 type:

- `ImdsKtorOperations`
  - `suspend fun get(path: String): String`
  - `suspend fun getList(path: String): List<String>`
  - 공통 helper: `instanceId`, `instanceType`, `availabilityZone`, `region`,
    `localIpv4`, `iamRoleNames`.
- `ImdsKtorTemplate`
  - AWS SDK v2 `Ec2MetadataAsyncClient`에 위임한다.
  - 모든 호출에 `withTimeout(requestTimeout.toMillis())`를 적용한다.
  - bluetape4k 검증 helper로 경로를 검증한다.
- `ImdsKtorPluginConfig`
  - `enabled`
  - `ec2MetadataAsyncClient`
  - `imdsOperations`
  - `endpoint`
  - `endpointMode`
  - `tokenTtl`
  - `requestTimeout`
  - `retries`
  - client builder customizer 목록
- `ImdsKtorRuntime`
  - operation과 선택적으로 소유한 client 수명 주기를 보관한다.
- `ImdsKtorPlugin`
  - 활성화되면 runtime/operation을 application attribute에 저장한다.
  - 설치/startup 중에는 metadata endpoint를 호출하지 않는다.
- Accessor:
  - `fun Application.imds(): ImdsKtorOperations`
  - `fun Application.imdsOrNull(): ImdsKtorOperations?`

## 설계 규칙

- `compileOnly(libs.aws2.imds)`와 이에 대응하는 `testImplementation`을 추가한다.
- config class, plugin, runtime, attribute key, application accessor로 구성된 현재 Ktor plugin pattern을 따른다.
- retry가 0이면 `Ec2MetadataRetryPolicy.none()`을 사용한다.
- 명시적으로 설정한 경우 `endpoint`를 사용하고, 그렇지 않으면 `endpointMode`를 사용한다.
- 주입된 `ImdsKtorOperations`를 직접 사용하고 client를 만들지 않는다.
- 주입된 `Ec2MetadataAsyncClient`를 닫지 않고 사용한다.
- plugin이 생성한 `Ec2MetadataAsyncClient`는 `ApplicationStopping`에서 닫는다.
- helper를 low-level이며 명시적인 형태로 유지한다. `iamRoleNames()`는 role 이름을 나열할 수 있지만 공개 helper는 role 자격 증명 문서를 읽지 않아야 한다.

## 테스트

필수 테스트:

- 활성화되면 plugin이 runtime/operation을 설치하고 attribute를 저장한다.
- 비활성화되면 plugin이 attribute를 저장하지 않는다.
- 없을 때 `imdsOrNull()`은 null을 반환하고 `imds()`는 실패한다.
- 주입된 operation이 우선하며 client를 생성하지 않는다.
- 주입된 client가 우선하며 startup 중에는 호출하지 않는다.
- plugin이 생성한 client는 주입된 client를 닫지 않고 종료 시 닫을 수 있다.
- Config가 양수 `tokenTtl`, 양수 `requestTimeout`, 음수가 아닌 `retries`를 검증한다.
- Template이 빈 경로를 검증하고 경로를 정규화하며 string/list 응답을 parse하고 완료되지 않는 future에 timeout을 적용한다.

## 문서

README locale 세트를 갱신한다.

- `README.md`
- `README.ko.md`
- `aws-ktor/README.md`
- `aws-ktor/README.ko.md`

문서는 의존성, plugin 설치, operation 사용법, startup 안전 동작, timeout 동작, 자격 증명 비노출을 다뤄야 한다.

## DoD

- Spec 검토: `P0=0`, `P1=0`.
- Plan 검토: `P0=0`, `P1=0`.
- 범위가 좁은 IMDS 테스트를 통과한다.
- 전체 `:bluetape4k-aws-ktor:test`를 통과한다.
- `:bluetape4k-aws-ktor:compileKotlin`을 통과한다.
- `dependencyInsight`에서 `software.amazon.awssdk:imds:2.46.0`을 확인한다.
- `git diff --check`를 통과한다.
- PR 본문이 `## DoD Status`로 끝난다.
