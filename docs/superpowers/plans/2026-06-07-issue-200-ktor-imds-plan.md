# 이슈 #200 - Ktor IMDS Helper 계획

날짜: 2026-06-07
이슈: #200 `feat(aws-ktor): add optional EC2 Instance Metadata Service helpers`

## 작업 유형

Type A 전체 기능.

## 단계

1. 의존성 연결
   - Add `compileOnly(libs.aws2.imds)` and `testImplementation(libs.aws2.imds)`
     to `aws-ktor/build.gradle.kts`.
   - `dependencyInsight`로 검증한다.

2. Operation 및 template
   - 안전한 metadata helper를 제공하는 `ImdsKtorOperations`를 추가한다.
   - `Ec2MetadataAsyncClient` 기반 `ImdsKtorTemplate`을 추가한다.
   - bluetape4k helper로 경로를 검증하고 `withTimeout`을 적용한다.

3. Plugin 설정 및 runtime
   - enabled flag, 주입된 operation, 주입된 client, endpoint, endpoint mode, token TTL, request timeout, retry, customizer를 제공하는 `ImdsKtorPluginConfig`를 추가한다.
   - operation과 소유한 client 수명 주기를 보관하는 `ImdsKtorRuntime`을 추가한다.
   - `ImdsKtorPlugin`, attribute key, `Application.imds()`, `Application.imdsOrNull()`을 추가한다.

4. 테스트
   - 경로 검증, 경로 정규화, string/list parsing, timeout cancellation을 위한 template 테스트를 추가한다.
   - 비활성화 동작, attribute 저장, 주입된 operation/client 동작, startup no-call 동작, 검증, 소유 client close 동작을 위한 plugin/config 테스트를 추가한다.

5. 문서
   - root 및 모듈 README locale 세트에 의존성, 사용법, EC2 전용 주의 사항, timeout 동작, 자격 증명 비노출을 반영한다.

6. 검토 및 검증
   - `P0=0`, `P1=0`인 구현 검토 artifact를 추가한다.
   - lesson 항목을 추가한다.
   - 다음 명령을 실행한다.
     - `./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency imds --configuration compileClasspath`
     - `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.imds.*'`
     - `./gradlew :bluetape4k-aws-ktor:test`
     - `git diff --check`

## 중단 조건

#200의 PR이 열려 있고, 로컬 검증 통과 증거, commit된 spec/plan/review/lesson artifact, 검증된 PR 본문, 정식 PR 검토가 있으며 CI 상태를 모니터링한다.
