# 이슈 #228 Ktor S3 Access Grants 계획

날짜: 2026-06-08
이슈: #228

## 분류

Type B Fast Track. 기존 모듈에 범위가 좁은 선택적 통합을 추가하고 기존 Ktor plugin/runtime pattern을 따른다.

## 단계

1. 이슈 접수 및 소스 검토
   - 현재 #227 배경을 #228에 반영한다.
   - `AwsKtorCore`, CloudWatch/IMDS plugin pattern, S3 Ktor 문서, Spring Access Grants 구현을 읽는다.

2. 의존성 및 공유 기본값
   - `aws-ktor`에 `aws2.s3control`을 `compileOnly`와 `testImplementation`으로 추가한다.
   - `AwsKtorDefaults`와 `AwsKtorCoreConfig`에 `AwsKtorS3ControlAsyncClientCustomizer` 경로를 추가한다.

3. Ktor Access Grants API 구현
   - `S3AccessGrantsKtorOperations`를 추가한다.
   - `S3AccessGrantsKtorTemplate`을 추가한다.
   - `S3AccessGrantsKtorRuntime`을 추가한다.
   - `S3AccessGrantsKtorPluginConfig`를 추가한다.
   - `S3AccessGrantsKtorPlugin`과 application accessor를 추가한다.

4. 테스트
   - Plugin이 주입된 operation을 저장한다.
   - 비활성화된 plugin은 operation을 저장하지 않고 accessor는 실패한다.
   - 주입된 client는 application 소유로 유지된다.
   - Plugin 소유 client는 한 번 닫힌다.
   - 공유 customizer가 서비스 customizer보다 먼저 실행된다.
   - Template이 노출된 모든 method를 `S3ControlAsyncClient`에 위임한다.

5. README 및 학습 문서
   - `aws-ktor/README.md`와 `aws-ktor/README.ko.md`를 갱신한다.
   - 짧은 `docs/lessons` 항목을 추가한다.

6. 검증
   - `./gradlew :bluetape4k-aws-ktor:compileKotlin --no-daemon --max-workers=1`
   - `./gradlew :bluetape4k-aws-ktor:compileTestKotlin --no-daemon --max-workers=1`
   - `./gradlew :bluetape4k-aws-ktor:test --tests '*S3AccessGrants*' --no-daemon --max-workers=1`
   - 변경한 동작에 필요하면 관련 기본값/plugin 회귀 테스트
   - `git diff --check`

7. 검토 및 PR
   - `P0=0`, `P1=0`인 추적 가능한 7-tier 검토 artifact를 만든다.
   - Lore 프로토콜에 따라 commit한다.
   - `--body-file`로 PR을 생성하고 마지막 `##` 절이 `## DoD Status`인지 확인한다.
   - CI gate 전에 PR 검토 gate를 실행한다.

## 위험

- S3 Control SDK type은 production에서 compile-only이므로 공개 signature는 의존성을 선택 사항으로 유지하면서도 사용자가 runtime 의존성을 제공하도록 요구해야 한다.
- AWS SDK async client mocking이 수명 주기 소유권 동작을 숨기지 않아야 한다.
- Access Grants는 emulator 기반이 아니다. 테스트는 위임과 Ktor 수명 주기를 다루며 실제 AWS 동작을 주장하지 않아야 한다.
