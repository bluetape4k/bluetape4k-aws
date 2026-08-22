# Issue #313 Step Functions 검증 증거

## 기준선

| Command | Exit | 결과 |
|---|---:|---|
| `./gradlew :bluetape4k-aws-kotlin:test --tests "io.bluetape4k.aws.kotlin.lifecycle.ClientLifecycleTest" --no-daemon --max-workers=1 --console=plain` | 0 | `BUILD SUCCESSFUL`, 12 tests |
| `./gradlew :bluetape4k-aws-java:test --no-daemon --max-workers=1 --console=plain` | 0 | `BUILD SUCCESSFUL`, XML aggregate 397 tests / 0 failures / 0 errors / 14 skipped |
| `./gradlew :bluetape4k-aws-kotlin:test --no-daemon --max-workers=1 --console=plain` | 0 | `BUILD SUCCESSFUL`, 582 tests / 0 failures / 0 errors / 12 skipped |
| `./gradlew detekt --no-daemon --max-workers=1 --console=plain` | 0 | `BUILD SUCCESSFUL`, `verifyDetektCoverage` PASS |

Base commit: `c9350bc1ae14cd72056fb358d8f3a427467848f9`.

## Task 1 SDK dependency boundary

| Command | Exit | 결과 |
|---|---:|---|
| raw SDK fixture RED: `compileAwsJavaServiceConsumerFixture compileAwsKotlinServiceConsumerFixture` | 1 | 의도한 `Unresolved reference 'sfn'` 및 `SfnClient` 확인 |
| `compileAwsJavaServiceConsumerFixture compileAwsKotlinServiceConsumerFixture verifyAwsConsumerFixturePublication --no-configuration-cache` | 0 | 두 consumer fixture와 publication compileOnly 검증 PASS |
| `compileAwsJavaServiceConsumerFixture -PconsumerFixtureOmit=aws-java:sfn --no-configuration-cache` | 1 | Java `SfnClient` 미해결 확인 |
| `compileAwsKotlinServiceConsumerFixture -PconsumerFixtureOmit=aws-kotlin:sfn --no-configuration-cache` | 1 | Kotlin `SfnClient` 미해결 확인 |

configuration cache를 켠 정상 publication 명령은 기존 `GenerateMavenPom`의 `withXml()` 오류
(`Cannot invoke ... ConfigurationContainer.detachedConfiguration ... delegate is null`)로 exit 1이었다.
이는 SDK fixture와 무관한 Gradle infrastructure 경로이므로 `--no-configuration-cache`로 재실행해 publication
검증을 PASS로 분리했다.

## Task 7 public consumer API

| Command | Exit | 결과 |
|---|---:|---|
| `compileAwsJavaServiceConsumerFixture compileAwsKotlinServiceConsumerFixture` (public helper surface) | 0 | Java/Kotlin lifecycle factory, custom HTTP client/engine, `with` helper, state-machine/Map Run list helper compile PASS |
| `compileAwsJavaServiceConsumerFixture compileAwsKotlinServiceConsumerFixture verifyAwsConsumerFixturePublication --no-configuration-cache` | 0 | public helper fixture와 publication metadata 검증 PASS |
| `compileAwsJavaServiceConsumerFixture -PconsumerFixtureOmit=aws-java:sfn --no-configuration-cache` | 1 | Java `sfn` extension 및 `SfnClient` 미해결 확인 |
| `compileAwsKotlinServiceConsumerFixture -PconsumerFixtureOmit=aws-kotlin:sfn --no-configuration-cache` | 1 | Kotlin `sfn` extension 및 `SfnClient` 미해결 확인 |

정상 명령은 compileOnly SDK를 외부 소비자 classpath에 명시한 경우에만 public API가 해석됨을 보여준다.
각 omission 명령의 실패는 publication이 AWS Step Functions SDK를 전이 노출하지 않는다는 negative evidence다.

## Emulator

| SDK | Backend | 결과 | XML/근거 |
|---|---|---|---|
| Java v2 | Floci | UNVERIFIED | exit 0, tests=1, failures=0, errors=0, skipped=1; exact skip `live integration unverified: Floci does not support Step Functions`; `build/test-results/issue-313/floci-java.xml`; SHA-256 `b9ac07f6668c7656990a0682b457939c70e664739f0dc078d6bb4670c1f69a87` |
| Kotlin | Floci | UNVERIFIED | exit 0, tests=1, failures=0, errors=0, skipped=1; exact skip `live integration unverified: Floci does not support Step Functions`; `build/test-results/issue-313/floci-kotlin.xml`; SHA-256 `9282f1e1065dd9dfb53bc004240b5522aeb7f2358b6f5feac1f93b9e728fed18` |
| Java v2 | LocalStack | PASS | exit 0, tests=1, failures=0, errors=0, skipped=0; `build/test-results/issue-313/localstack-java.xml`; SHA-256 `5cb1d8102ec99dbd779b70eb9e460a6409ffd78b05108fd7795f91c5d8dd6d3a` |
| Kotlin | LocalStack | PASS | exit 0, tests=1, failures=0, errors=0, skipped=0; `build/test-results/issue-313/localstack-kotlin.xml`; SHA-256 `4cef315855ce800377fb4f9e032923ab553f4ed63fe5897d27c4a94ea7173f51` |

네 실행은 다음 순서로 `--rerun-tasks`와 `--no-configuration-cache`를 사용해 fresh XML과 receipt를 만들었다.

```text
./gradlew :bluetape4k-aws-java:test --tests io.bluetape4k.aws.sfn.SfnSmokeTest -Dbluetape4k.aws.emulator=floci --rerun-tasks --no-daemon --max-workers=1 --no-configuration-cache --console=plain
./gradlew :bluetape4k-aws-kotlin:test --tests io.bluetape4k.aws.kotlin.sfn.SfnSmokeTest -Dbluetape4k.aws.emulator=floci --rerun-tasks --no-daemon --max-workers=1 --no-configuration-cache --console=plain
./gradlew :bluetape4k-aws-java:test --tests io.bluetape4k.aws.sfn.SfnSmokeTest -Dbluetape4k.aws.emulator=localstack --rerun-tasks --no-daemon --max-workers=1 --no-configuration-cache --console=plain
./gradlew :bluetape4k-aws-kotlin:test --tests io.bluetape4k.aws.kotlin.sfn.SfnSmokeTest -Dbluetape4k.aws.emulator=localstack --rerun-tasks --no-daemon --max-workers=1 --no-configuration-cache --console=plain
```

receipt 원본은 `build/test-results/issue-313/{floci-java,floci-kotlin,localstack-java,localstack-kotlin}.receipt.txt`에
남겼다. Floci의 `UNVERIFIED`는 기능 미지원으로 인한 skip이며 live PASS가 아니다. LocalStack PASS는
생성·시작·조회·목록·중지·삭제 lifecycle만 증명하며 실제 AWS IAM/KMS 권한을 증명하지 않는다.

## 최종 회귀 검증

| 영역 | Exit | 결과 |
|---|---:|---|
| Java `io.bluetape4k.aws.sfn.*` targeted | 0 | 39 tests, 0 failures, 0 errors, 1 Floci smoke skip; `BUILD SUCCESSFUL` |
| Kotlin `io.bluetape4k.aws.kotlin.sfn.*` targeted | 0 | 37 tests, 0 failures, 0 errors, 1 Floci smoke skip; `BUILD SUCCESSFUL` |
| Java module 전체 | 0 | 436 tests, 0 failures, 0 errors, 15 skipped; baseline 397/0/0/14 대비 Issue #313 추가 39 tests와 1 documented Floci skip |
| Kotlin module 전체 | 0 | 619 tests, 0 failures, 0 errors, 13 skipped; baseline 582/0/0/12 대비 Issue #313 추가 37 tests와 1 documented Floci skip |
| `detekt --no-configuration-cache` | 0 | `BUILD SUCCESSFUL`, `verifyDetektCoverage` PASS |
| consumer/publication compile | 0 | Java/Kotlin public fixture와 publication metadata PASS (`--no-configuration-cache`) |
| manual/manifest contract | 0 | manual contract 9 runs/44 assertions, manifest check PASS, EN/KO anchors PASS, releaseRef 4개·release SHA 36개 보존 |

Korean terminology audit는 기존 문서 baseline 10건만 보고했으며 Issue #313 추가 diff에는 새 finding이 없다.
기존 finding은 관련 없는 기존 문서 표현이므로 이 작업에서 임의로 변경하지 않았다. configuration cache를
활성화한 publication 명령은 기존 `GenerateMavenPom` infrastructure 오류가 재현되어, 해당 검증만
`--no-configuration-cache`로 분리했다.

## Security boundary

- 실제 AWS IAM/KMS: `UNVERIFIED`
- emulator 성공은 IAM resource policy 또는 KMS key policy 증거가 아님
- ARN, execution name, input, output, error, cause, traceHeader, raw response payload는 운영 로그에 기록하지 않거나 redaction한다.
