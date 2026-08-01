# 이슈 #272 설계 - Ktor Kinesis 및 STS 통합

## 배경

이슈 #272의 대상 milestone은 `0.6.0`이다. `aws-java`는 이미 Kinesis 및 STS용 AWS SDK v2 coroutine helper를 제공하고, `aws-spring-boot`는 Kinesis stream operation과 명시적인 single-shard record `Flow`를 노출한다. `aws-ktor`는 Spring Boot에 의존하지 않고 같은 runtime-safe 서비스 표면을 노출해야 한다.

## 근거

- `aws-ktor` 서비스 plugin은 `Operations` interface, SDK 기반 `Template`, `Runtime`, `PluginConfig`, Ktor `Application` accessor를 사용한다.
- `AwsKtorCore`는 공유 Java SDK v2 region, endpoint, 자격 증명, 서비스 builder customizer를 소유한다.
- `aws-spring-boot` Kinesis는 장시간 실행 consumer를 명시적으로 유지한다. shard 하나, caller-collected cold `Flow`, lease coordination 없음, `CompletableFuture.await()`를 통한 cancellation으로 구성한다.
- STS helper는 background runtime 작업이 아니라 identity/session 요청이다. 호출자가 account, ARN, assumed-role 자격 증명, session metadata를 유지하도록 raw AWS SDK 응답을 반환해야 한다.

## 목표

1. Ktor Kinesis operation과 plugin 수명 주기 지원을 추가한다.
2. Ktor STS identity/session operation과 plugin 수명 주기 지원을 추가한다.
3. 계약이 일치하는 곳에서 기존 `aws-java` coroutine adapter를 재사용한다.
4. Kinesis 소비를 명시적이고 cold이며 cancellable한 single-shard 형태로 유지한다.
5. 사용자에게 서비스 SDK 의존성을 선택 사항으로 유지한다.
6. 영어와 한국어 README 커버리지를 갱신한다.

## 제외 범위

- Kinesis Client Library lease coordination 또는 checkpoint 저장소를 추가하지 않는다.
- 숨겨진 background consumer, 자동 retry 공개, listener container를 추가하지 않는다.
- 실제 AWS 테스트를 추가하지 않는다.
- 이 이슈에서 STS request-scoped identity를 Ktor authentication provider로 만들지 않는다.

## 선택한 설계

### Kinesis 통합

`io.bluetape4k.aws.ktor.kinesis` package를 추가한다.

공개 API:

- `KinesisKtorOperations`
- `KinesisKtorTemplate`
- `KinesisKtorPluginConfig`
- `KinesisKtorRuntime`
- `KinesisKtorPlugin`
- `Application.kinesis()`
- `Application.kinesisOrNull()`
- record 공개, shard iterator, stream 선언, record `Flow`를 위한 request/option value object.

Template은 간단한 stream 및 공개 호출을 `aws-java` coroutine helper에 위임한다. `recordFlow`는 Ktor application 수명 주기에 관한 기능이며 Spring에 의존하지 않아야 하므로 로컬에서 구현한다.

### STS 통합

`io.bluetape4k.aws.ktor.sts` package를 추가한다.

공개 API:

- `StsKtorOperations`
- `StsKtorTemplate`
- `StsKtorPluginConfig`
- `StsKtorRuntime`
- `StsKtorPlugin`
- `Application.sts()`
- `Application.stsOrNull()`
- `StsAssumeRoleRequest`
- `StsSessionTokenRequest`

Template은 Ktor 로컬 request 객체를 AWS SDK v2 request로 mapping하고 기반 future를 기다린다. 현재 `aws-java` duration validator는 internal이므로 표준 STS duration 범위를 로컬에서 검증한다.

## 인수 조건

- Kinesis put/get/flow request mapping을 테스트한다.
- Kinesis `recordFlow`는 cold이며 coroutine cancellation 시 대기 중인 AWS future를 취소한다.
- STS caller identity, assume role, session token request mapping을 테스트한다.
- Kinesis 및 STS plugin 테스트가 주입된 operation, 비활성화된 accessor, application 소유 client, plugin 소유 client 종료, 공유 customizer와 서비스 customizer의 순서를 다룬다.
- `AwsKtorCore`가 Kinesis 및 STS 공유 customizer를 노출한다.
- `aws-ktor`가 Kinesis 및 STS SDK 의존성을 `compileOnly`와 `testImplementation`으로 선언한다.
- README locale 쌍이 Kinesis 및 STS Ktor 커버리지를 문서화한다.

## 완료 조건

- production 소스를 편집하기 전에 spec과 plan이 있다.
- #272의 담당자가 `debop`이고 milestone은 `0.6.0`으로 유지된다.
- `git diff --check`를 통과한다.
- 범위가 좁은 Kinesis/STS Ktor 테스트를 통과한다.
- `./gradlew :bluetape4k-aws-ktor:compileTestKotlin --warning-mode all`을 통과한다.
- PR metadata가 issue 담당자, milestone, label을 반영하고 마지막에 `## DoD Status`가 있다.
