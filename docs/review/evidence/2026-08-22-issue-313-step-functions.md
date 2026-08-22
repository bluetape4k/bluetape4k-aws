# Issue #313 Step Functions 검증 증거

## 기준선

| Command | Exit | 결과 |
|---|---:|---|
| `./gradlew :bluetape4k-aws-kotlin:test --tests "io.bluetape4k.aws.kotlin.lifecycle.ClientLifecycleTest" --no-daemon --max-workers=1 --console=plain` | 0 | `BUILD SUCCESSFUL`, 12 tests |
| `./gradlew :bluetape4k-aws-java:test --no-daemon --max-workers=1 --console=plain` | 0 | `BUILD SUCCESSFUL`, XML aggregate 397 tests / 0 failures / 0 errors / 14 skipped |
| `./gradlew :bluetape4k-aws-kotlin:test --no-daemon --max-workers=1 --console=plain` | 0 | `BUILD SUCCESSFUL`, 582 tests / 0 failures / 0 errors / 12 skipped |
| `./gradlew detekt --no-daemon --max-workers=1 --console=plain` | 0 | `BUILD SUCCESSFUL`, `verifyDetektCoverage` PASS |

Base commit: `c9350bc1ae14cd72056fb358d8f3a427467848f9`.

## Emulator

| SDK | Backend | 결과 | XML/근거 |
|---|---|---|---|
| Java v2 | Floci | PENDING | Task 8 receipt 예정 |
| Kotlin | Floci | PENDING | Task 8 receipt 예정 |
| Java v2 | LocalStack | PENDING | Task 8 receipt 예정 |
| Kotlin | LocalStack | PENDING | Task 8 receipt 예정 |

## Security boundary

- 실제 AWS IAM/KMS: `UNVERIFIED`
- emulator 성공은 IAM resource policy 또는 KMS key policy 증거가 아님
- ARN, execution name, input, output, error, cause, traceHeader, raw response payload는 운영 로그에 기록하지 않거나 redaction한다.
