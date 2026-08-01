# 이슈 #272 Ktor Kinesis 및 STS 구현 계획

목표: 명시적 수명 주기, cancellation, raw AWS SDK 응답 계약을 보존하면서 기존 `aws-java` SDK wrapper 위에 Ktor Kinesis 및 STS helper를 추가한다.

## 작업 1 - RED 테스트 및 의존성 연결

- [x] `aws-ktor`에 `libs.aws2.kinesis`와 `libs.aws2.sts`를 `compileOnly` 및 `testImplementation`으로 추가한다.
- [x] request mapping, record `Flow` 시작 위치, cold collection, 반복 collection, cancellation, 실패 future 전파를 위한 Kinesis template 테스트를 추가한다.
- [x] caller identity, assume-role, session-token, duration 검증, cancellation, 실패 future 전파를 위한 STS template 테스트를 추가한다.
- [x] Kinesis 및 STS plugin 수명 주기 테스트를 추가한다.
- [x] `:bluetape4k-aws-ktor:compileTestKotlin`을 실행하고 production 구현 전에 예상한 RED 실패를 기록한다.

## 작업 2 - Kinesis Ktor 통합

- [x] `AwsKtorCore`에 `AwsKtorKinesisAsyncClientCustomizer`를 추가한다.
- [x] Kinesis request, stream, starting-position, flow option 모델을 추가한다.
- [x] `KinesisKtorOperations`와 `KinesisKtorTemplate`을 추가한다.
- [x] `KinesisKtorRuntime`, `KinesisKtorPluginConfig`, `KinesisKtorPlugin`을 추가한다.
- [x] `recordFlow`를 single-shard, caller-collected, cold, cancellable로 유지한다.

## 작업 3 - STS Ktor 통합

- [x] `AwsKtorCore`에 `AwsKtorStsAsyncClientCustomizer`를 추가한다.
- [x] duration 검증을 포함한 STS request 모델을 추가한다.
- [x] `StsKtorOperations`와 `StsKtorTemplate`을 추가한다.
- [x] `StsKtorRuntime`, `StsKtorPluginConfig`, `StsKtorPlugin`을 추가한다.
- [x] identity/session metadata를 위해 raw AWS SDK 응답 객체를 보존한다.

## 작업 4 - 문서, 검토 및 검증

- [x] Kinesis와 STS의 root 및 `aws-ktor` README locale 쌍을 갱신한다.
- [x] 검토 및 lesson artifact를 추가한다.
- [x] 범위가 좁은 Kinesis/STS 테스트를 실행한다.
- [x] `./gradlew :bluetape4k-aws-ktor:compileTestKotlin --warning-mode all`을 실행한다.
- [x] `git diff --check`를 실행한다.
- [ ] Lore trailer를 포함해 commit하고 이슈 metadata와 동등하며 마지막 `## DoD Status`를 갖춘 #272 연결 PR을 생성한다.

## 검증 Matrix

| 요구사항 | 증거 |
|---|---|
| Kinesis request mapping 검증 | `KinesisKtorTemplateTest` |
| Kinesis flow cancellation 검증 | `KinesisKtorTemplateTest` |
| STS identity/session mapping 검증 | `StsKtorTemplateTest` |
| Ktor plugin 수명 주기 | `KinesisKtorPluginTest`, `StsKtorPluginTest` |
| 선택적 SDK 의존성 | `aws-ktor/build.gradle.kts` |
| README locale 동등성 | root 및 `aws-ktor` README diff |
| 최종 build 상태 | 범위가 좁은 테스트, compileTestKotlin, `git diff --check` |
