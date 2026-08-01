# 비활성화 테스트 등록부

`@Disabled`가 붙은 테스트는 범위, 사유, 추적 이슈를 한눈에 확인할 수 있도록 여기에서
관리합니다. 모든 `@Disabled` annotation은 반드시 `#NNN — <reason>` 형식으로 추적 이슈를
포함해야 합니다.

## 범주 설명

| 범주 | 의미 |
|---|---|
| `unsupported-emulator` | LocalStack 또는 floci가 지원하지 않는 service 또는 API variant |
| `out-of-band-protocol` | SMS, email callback처럼 token/event를 emulator 밖에서 전달해야 하는 flow |

## 등록 목록

| 모듈 | 파일 | 테스트 | 수준 | 범주 | 추적 이슈 | 사유 |
|---|---|---|---|---|---|---|
| `aws-kotlin` | `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sesv2/SesV2ClientExtensionsTest.kt` | *(entire class)* | class | `unsupported-emulator` | [#99](https://github.com/bluetape4k/bluetape4k-aws/issues/99) | LocalStack이 SES V2를 지원하지 않음; mock 기반 coverage는 #105에서 추적 |
| `aws-kotlin` | `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sns/SnsClientExtensionsTest.kt` | `confirm subscription` | method | `out-of-band-protocol` | [#100](https://github.com/bluetape4k/bluetape4k-aws/issues/100) | SNS subscription token이 subscriber endpoint로 out-of-band 전달됨; emulator 지원 없음 |
| `aws-kotlin` | `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/sns/examples/SnsClientExamples.kt` | `confirm subscription` | method | `out-of-band-protocol` | [#100](https://github.com/bluetape4k/bluetape4k-aws/issues/100) | SNS subscription token이 subscriber endpoint로 out-of-band 전달됨; emulator 지원 없음 |
| `aws` | `aws/src/test/kotlin/io/bluetape4k/aws/sns/SnsAsyncClientTest.kt` | `confirm subscription` | method | `out-of-band-protocol` | [#100](https://github.com/bluetape4k/bluetape4k-aws/issues/100) | SNS SMS token이 subscriber로 out-of-band 전달됨; emulator 지원 없음 |
| `aws` | `aws/src/test/kotlin/io/bluetape4k/aws/sns/SnsClientTest.kt` | `confirm subscription` | method | `out-of-band-protocol` | [#100](https://github.com/bluetape4k/bluetape4k-aws/issues/100) | SNS SMS token이 subscriber로 out-of-band 전달됨; emulator 지원 없음 |

## Annotation 형식 규칙

모든 `@Disabled` annotation은 다음 형식을 정확히 따라야 합니다.

```kotlin
@Disabled("#NNN — <one-sentence reason>")
```

- `#NNN` — skip을 추적하는 GitHub issue number
- ` — ` — 양쪽에 공백이 있는 em dash
- reason — 테스트 대상을 설명하는 문장이 아니라 테스트를 실행할 수 없는 이유

**유효한 예:**

```kotlin
@Disabled("#99 — LocalStack does not support SES V2; mock-based coverage tracked in issue #105")
@Disabled("#100 — SNS SMS token is delivered out-of-band to subscriber; no emulator support")
```

**유효하지 않은 예: issue reference가 없음**

```kotlin
@Disabled("not working")
@Disabled
```

## CI 형식 검증

CI의 `validate-disabled-annotations` job은 `@Disabled("#NNN — <reason>")` 형식과 일치하지
않는 `@Disabled` annotation을 거부합니다. 형식을 지키지 않은 annotation이 포함된 PR은
CI에서 자동으로 실패합니다.
