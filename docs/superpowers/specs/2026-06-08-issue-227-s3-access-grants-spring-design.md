# 이슈 #227 Spring S3 Access Grants 설계

## 배경

이슈 #227은 이슈 #192에서 연기한 S3 Access Grants 범위의 0.4.0 후속 작업이다. Access Grants가 별도의 선택형 AWS SDK control-plane surface를 도입하므로 0.3.0 S3 Spring Boot 작업에서는 의도적으로 S3 config reload와 KMS 기반 client-side encryption만 제공했다.

현재 AWS SDK for Java v2 surface는 `software.amazon.awssdk.services.s3control.S3ControlClient`와 `S3ControlAsyncClient`에서 S3 Access Grants operation을 제공한다. 저장소는 현재 `aws2-s3`와 `aws2-s3-transfer-manager`를 선언하지만 아직 `aws2-s3control` catalog alias는 선언하지 않는다.

## 현재 근거

- GitHub 이슈 #227은 2026-06-08 `s3control` 방향으로 갱신됐다.
- AWS SDK Java API reference는 `S3ControlClient`에 `createAccessGrant`, `createAccessGrantsInstance`, `getDataAccess`, `listCallerAccessGrants` 같은 Access Grants method가 있음을 보여준다.
- AWS SDK BOM 2.46.0은 `software.amazon.awssdk:s3control` artifact를 포함한다.
- `./gradlew -q :bluetape4k-aws-spring-boot:dependencyInsight --dependency s3control --configuration compileClasspath`는 현재 일치하는 dependency가 없다고 보고한다.
- 이 worktree에는 CodeGraph가 초기화되지 않아 source 탐색에 GNO, Gradle dependency 검사, 공식 AWS SDK 문서, 직접 source 읽기를 사용했다.

## 목표

- S3 Access Grants를 위한 선택형 Spring Boot auto-configuration을 추가한다.
- 기본 S3 사용자에게 `s3control` runtime dependency를 요구하지 않는다.
- 기존 AWS core 기본값과 client customizer infrastructure를 재사용한다.
- `S3ControlAsyncClient` 기반 coroutine-first operation/template surface를 제공한다.
- user-facing property와 사용법을 `README.md`와 `README.ko.md`에 문서화한다.

## 목표가 아닌 항목

- Ktor Access Grants helper는 구현하지 않는다. 이슈 #228의 범위다.
- S3 Vector 지원은 구현하지 않는다. 이슈 #229의 범위다.
- account-level IAM Identity Center 또는 Access Grants resource가 필요한 실제 AWS Access Grants integration test는 실행하지 않는다.
- Access Grants를 `S3Operations`에 합치지 않는다. lifecycle과 permission이 다른 control-plane 기능이다.

## 제안 API

`io.bluetape4k.aws.spring.s3.accessgrants` 아래에 새 package를 추가한다.

- `S3AccessGrantsProperties`
- `S3AccessGrantsAutoConfiguration`
- `S3AccessGrantsOperations`
- `S3AccessGrantsCoroutinesTemplate`

property 접두사:

```properties
bluetape4k.aws.s3.access-grants
```

초기 property:

- `enabled`: 기본값 `false`.
- `region`: 선택형 service-specific override.
- `endpointOverride`: 선택형 service-specific endpoint override.

template은 최소한의 안정적인 operation set을 제공해야 한다.

- `getDataAccess(...)`
- `listCallerAccessGrants(...)`
- `listAccessGrants(...)`
- `listAccessGrantsInstances(...)`
- `listAccessGrantsLocations(...)`

관리용 create/delete/update API는 caller-owned `S3ControlClient`/`S3ControlAsyncClient` bean을 통해 계속 사용할 수 있다. 첫 Spring surface의 초점을 account bootstrap이 아니라 application access workflow에 맞춘다.

## auto-configuration 계약

`S3AccessGrantsAutoConfiguration`을 `AwsAutoConfiguration`과 `S3AutoConfiguration` 뒤에 등록한다.

string class guard를 사용한다.

```kotlin
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.services.s3control.S3ControlClient",
        "software.amazon.awssdk.services.s3control.S3ControlAsyncClient",
    ]
)
```

property guard를 사용한다.

- `bluetape4k.aws.s3.enabled=true` 또는 누락.
- `bluetape4k.aws.s3.access-grants.enabled=true`.

등록할 bean:

- `S3ControlClient`, `destroyMethod = "close"`, caller bean이 있으면 back off.
- `S3ControlAsyncClient`, `destroyMethod = "close"`, caller bean이 있으면 back off.
- `S3AccessGrantsOperations`, caller bean이 있으면 back off.

client builder는 다음 항목을 재사용해야 한다.

- `AwsProperties.resolveClientDefaults(...)`
- `applyAwsDefaults(...)`
- `applyGlobalCustomizers("s3control", ...)`
- `applyServiceCustomizers(...)`

## dependency 계약

`gradle/libs.versions.toml`에 추가한다.

```toml
aws2-s3control = { module = "software.amazon.awssdk:s3control", version.ref = "aws2" }
```

`aws-spring-boot/build.gradle.kts`에 추가한다.

- `compileOnly(libs.aws2.s3control)`
- `testImplementation(libs.aws2.s3control)`

`s3control`을 위한 `api` 또는 `runtimeOnly`를 추가하지 않는다.

## test 전략

`ApplicationContextRunner`, MockK, bluetape4k assertion을 사용한다.

필수 test:

- Access Grants auto-configuration은 기본적으로 비활성화된다.
- Access Grants를 활성화하면 sync/async S3 Control client와 operation을 등록한다.
- `s3control` class가 없으면 `FilteredClassLoader`를 통해 정상적으로 back off한다.
- caller-provided `S3ControlClient` / `S3ControlAsyncClient` bean을 재사용한다.
- caller-provided `S3AccessGrantsOperations`가 있으면 template이 back off한다.
- global 및 service-specific client customizer를 service name `s3control`로 적용한다.
- region 없는 endpoint override는 공유 AWS 기본값 규칙을 통해 실패한다.
- template이 async SDK 호출을 위임하고 완료를 기다린다.

이 이슈에서는 Access Grants emulator test를 추가하지 않는다. 현재 이 저장소의 local emulator로는 account-level Access Grants workflow를 입증할 수 없다.

## 문서

갱신 대상:

- 루트 `README.md`
- 루트 `README.ko.md`

문서에는 Access Grants가 opt-in이고 호출자가 `software.amazon.awssdk:s3control`을 추가해야 하며 기본 S3 object operation과 분리된다고 명시해야 한다.

## 위험

- `S3Control`은 Access Grants 외에도 많은 S3 control-plane API를 다룬다. 광범위한 S3 Control compatibility를 실수로 확정하지 않도록 public template의 첫 surface를 좁게 유지해야 한다.
- Access Grants 설정에는 account-level permission과 IAM Identity Center 연결이 필요한 경우가 많다. unit/slice test로 wiring을 입증할 수 있지만 실제 AWS 동작은 범위에서 제외한다.
- `bluetape4k-dependencies`에 생성된 alias가 추가될 때까지 local catalog alias 추가는 중앙에서 관리하는 AWS artifact alias를 중복한다. version은 기존 `aws2` line을 유지하므로 허용한다.

## DoD

- 명세 검토에서 `P0=0`, `P1=0`을 보고한다.
- 계획 검토에서 `P0=0`, `P1=0`을 보고한다.
- `:bluetape4k-aws-spring-boot` compile 및 targeted test가 통과한다.
- README locale set을 갱신한다.
- `docs/lessons/` 아래에 간결한 lesson을 추가한다.
- 최종 code review에서 `P0=0`, `P1=0`을 보고한다.
