# Kover coverage 정책

## 현재 상태

`bluetape4k-aws`는 Nightly에서 `aws`, `aws-kotlin`, `aws-spring-boot`,
`aws-ktor` 모듈의 Kover XML report를 생성합니다. 현재 실패를 유발하는 coverage
threshold가 설정된 모듈은 없습니다.

## 정책

상태: report-only 전환.

많은 test가 AWS SDK 동작, LocalStack, Ktor client, Spring Boot auto-configuration에
의존하므로 이 저장소는 integration 비중이 높습니다. 모듈별 baseline을 측정하기 전에는
저장소 전체에 적용되는 광범위한 gate를 활성화하지 않습니다.

## Threshold 계획

- Kover는 build gate가 아닌 추세 신호로 취급합니다.
- Nightly XML report와 기존 coverage artifact upload로 coverage regression을
  식별합니다.
- 모듈의 coverage 보완이 필요하면 범위가 명확한 issue를 엽니다. 실패 threshold를 기본
  강제 수단으로 도입하지 않습니다.
- example의 coverage는 참고 정보로만 사용합니다.

## CI/Nightly 계약

Nightly는 Kover XML artifact를 upload해 추세를 확인할 수 있게 합니다. 향후 issue에서
해당 gate를 명시적으로 다시 도입하지 않는 한, 모듈 coverage가 고정 비율보다 낮다는
이유만으로 CI와 Nightly가 실패해서는 안 됩니다.
