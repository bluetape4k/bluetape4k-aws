# Issue 272 코드 검토

## 범위

- Ktor Kinesis/STS operation, template, plugin configuration, lifecycle
- Kinesis/STS용 공유 `AwsKtorCore` Java SDK v2 customizer
- Root/`aws-ktor` README locale과 service coverage chart

## 결과

| 관점 | 심각도 | 결과 | 증거 |
|---|---:|---|---|
| API 계약 | P0 | Kinesis/STS는 원본 AWS SDK 응답을 반환한다. | SDK request를 capture하는 template test |
| Kinesis 수명 주기 | P0 | `recordFlow`는 cold/single-shard/caller-collected이며 취소가 pending AWS future에 전파된다. | cold/repeat/cancellation test |
| STS 매핑 | P0 | caller identity, assume-role, session-token과 duration 검증을 보존한다. | `StsKtorTemplateTest` |
| Ktor 소유권 | P0 | 주입 operation/client는 application 소유이고 plugin 소유 client는 한 번 닫힌다. | Kinesis/STS lifecycle test |
| 기본값/customization | P0 | `AwsKtorCore` 공유 customizer가 service-local customizer보다 먼저 실행된다. | `AwsKtorCoreTest`와 order test |
| 의존성 | P0 | Kinesis/STS SDK는 소비자 optional runtime dependency다. | `aws-ktor`의 `compileOnly`+`testImplementation` |
| 문서 | P0 | README locale에 dependency, usage, option, coverage를 기록했다. | README diff와 chart PNG 검사 |

## 검증 증거

- Production 구현 전 누락된 Kinesis/STS Ktor surface로 RED compile 실패
- `./gradlew :bluetape4k-aws-ktor:compileTestKotlin --warning-mode all --rerun-tasks`: PASS
- `./gradlew :bluetape4k-aws-ktor:test --tests '*KinesisKtor*Test' --tests '*StsKtor*Test' --warning-mode all --rerun-tasks`: PASS
- `rsvg-convert`로 service coverage chart PNG를 재생성하고 육안 검사

## 잔여 위험

- Kinesis emulator smoke는 추가하지 않았으며 범위는 unit-level mapping과 명시적 Flow 수명 주기다.
- STS helper는 하위 request helper이지 Ktor authentication provider가 아니다.
