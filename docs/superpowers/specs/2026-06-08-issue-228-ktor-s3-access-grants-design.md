# 이슈 #228 Ktor S3 Access Grants 명세

날짜: 2026-06-08
이슈: #228
작업 유형: Type B Fast Track

## 배경

`aws-ktor`에는 이미 object operation용 REST 우선 `S3KtorClient`와 CloudWatch 같은 AWS SDK Java v2 서비스용 별도 Ktor server plugin이 있다. 이슈 #227은 AWS SDK Java v2 S3 Control 모듈을 통해 Spring Boot S3 Access Grants를 추가했다. Ktor는 해당 서비스 경계를 재사용해야 한다. Access Grants는 일반 S3 REST client가 아니라 S3 Control에 속한다.

## 목표

일반적인 read/data-access method의 coroutine operation을 노출하고, plugin 소유 및 caller 소유 client를 지원하며, 적절한 곳에서 `AwsKtorDefaults`를 상속하는 선택적 Ktor S3 Access Grants 통합을 추가한다.

## 제외 범위

- `S3KtorClient`에 S3 Access Grants method를 추가하지 않는다.
- `software.amazon.awssdk:s3control`을 필수 runtime 의존성으로 만들지 않는다.
- 이 이슈에서 administrative create/update/delete operation을 감싸지 않는다. 해당 기능은 raw S3 Control client를 통해 계속 사용할 수 있다.
- 실제 AWS 통합 테스트를 추가하지 않는다. Access Grants는 계정 수준 AWS 설정이 필요하며 emulator matrix 범위 밖이다.

## 공개 API 형태

- 패키지: `io.bluetape4k.aws.ktor.s3.accessgrants`.
- `S3AccessGrantsKtorOperations`: 다음 기능의 suspend facade.
  - `getDataAccess`
  - `listCallerAccessGrants`
  - `listAccessGrants`
  - `listAccessGrantsInstances`
  - `listAccessGrantsLocations`
- `S3AccessGrantsKtorTemplate`: `CompletableFuture.await()`를 사용하는 AWS SDK Java v2 `S3ControlAsyncClient` 기반 구현.
- `S3AccessGrantsKtorPlugin`: Ktor 애플리케이션 플러그인.
- `S3AccessGrantsKtorPluginConfig`: enabled flag, caller 소유 operation, caller 소유 async client, region, endpoint override, 자격 증명 provider, 서비스 customizer.
- `Application.s3AccessGrants()`와 `Application.s3AccessGrantsOrNull()`.

## 의존성 경계

`aws-ktor`에 `libs.aws2.s3control`을 `compileOnly`와 `testImplementation`으로 추가한다. 사용자는 plugin을 설치하거나 template을 사용할 때 `runtimeOnly("software.amazon.awssdk:s3control")` 또는 이에 상응하는 의존성을 추가해야 한다.

## 수명 주기

Plugin은 활성화되었을 때만 runtime과 operation facade를 저장한다. 주입된 operation은 client 생성과 검증을 우회한다. 주입된 client는 application 소유로 유지된다. Plugin이 생성한 async client는 기존 Ktor plugin 수명 주기 pattern을 사용해 `ApplicationStopping`에서 한 번 닫는다.

## 기본값 및 Customizer

Plugin이 생성한 client는 다음을 상속한다.

- plugin config의 region, 그다음 `AwsKtorDefaults.region`
- plugin config의 endpoint override, 그다음 `AwsKtorDefaults.javaEndpointOverride`
- plugin config의 자격 증명 provider, 그다음 `AwsKtorDefaults.javaCredentialsProvider`
- 공유 S3 Control customizer, 그다음 서비스 로컬 customizer

Endpoint override에는 비어 있지 않은 region이 필요하다.

## 문서

`README.md`와 `README.ko.md`에 다음 내용을 반영한다.

- runtime 의존성 snippet
- `AwsKtorCore` + `S3AccessGrantsKtorPlugin` 최소 설치 예제
- `S3AccessGrantsKtorTemplate`를 직접 사용하는 caller 소유 client 예제 또는 설명
- admin operation은 raw S3 Control client를 사용한다는 명시적 경계

## 검증

- `:bluetape4k-aws-ktor`를 compile한다.
- 범위가 좁은 Access Grants 테스트를 실행한다.
- 관련 Ktor 기본값/plugin 회귀 테스트를 실행한다.
- `git diff --check`를 실행한다.
- `P0=0`, `P1=0`인 7-tier 검토를 실행한다.
