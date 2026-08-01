# @Disabled 테스트 Annotation에 이슈 참조 추가

**날짜**: 2026-05-16
**이슈**: #99 (SES V2), #100 (SNS token)
**브랜치**: fix/disabled-test-annotations

## 결정

`@Disabled` annotation 다섯 개에 이슈 번호와 영문 사유를 추가했다.

| 파일 | 이전 메시지 | 새 메시지 |
|---|---|---|
| `SesV2ClientExtensionsTest.kt` | `LocalStack에서 SES V2를 지원하지 않습니다.` | `#99 — LocalStack does not support SES V2; mock-based coverage tracked in issue #105` |
| `SnsClientExtensionsTest.kt` | `token은 SNS 구독 시에 클라이언트에 전송된다` | `#100 — SNS subscription token is delivered out-of-band…` |
| `SnsClientExamples.kt` | 동일 | 동일 |
| `SnsClientTest.kt` | `token은 SMS 구독 시에 클라이언트에 전송된다고 한다` | `#100 — SNS SMS token is delivered out-of-band…` |
| `SnsAsyncClientTest.kt` | 동일 | 동일 |

## 이유

비활성화한 테스트에 이슈 참조가 없으면 향후 유지보수 담당자와 release review에서 발견하기
어렵다. 변경한 메시지는 이제 다음 정보를 제공한다.

1. 근본 원인 범주(emulator 제한 / out-of-band protocol)를 밝힌다.
2. 해결 계획을 추적하는 이슈에 연결한다.
3. Mock 기반 coverage를 위한 향후 이슈(#105)를 참조한다.

## 검증

- `./gradlew :aws:test :aws-kotlin:test`: **444개 통과, 5개 pending, 실패 0개**
- 변경한 `@Disabled` 메시지 5개에 모두 이슈 번호가 포함됨

## 향후 지침

- 모든 `@Disabled` annotation에 범주와 이슈 link를 포함한다.
- 범주: `unsupported-emulator`, `out-of-band-protocol`, `bug`, `slow`
- 이 규칙을 자동으로 강제하는 CI gate는 Issue #106에서 추적한다.
