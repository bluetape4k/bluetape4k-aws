# Issue #273 코드 검토

## 범위

`examples/aws-ktor-service-coverage-examples`, repository 등록, README locale, service coverage chart, CI/Nightly workflow.

## 결과

- P0/P1 없음.
- 새 route module은 기존 Ktor plugin과 application accessor를 사용한다.
- `ServiceCoverageExampleOptions`로 같은 타입 resource name의 위치 실수를 피한다.
- Route test는 injected facade로 SES/v2, SNS, CloudWatch, CloudWatch Logs, Kinesis, STS mapping을 검증한다.
- Service별 emulator 지원이 달라 외부 emulator를 사용하지 않으며 README에 Floci-first와 LocalStack/real AWS fallback을 기록한다.
- CI path/status, Nightly, `settings.gradle.kts`, `AGENTS.md`, root README locale이 새 module을 참조한다.

## 검증 증거

- `./gradlew :aws-ktor-service-coverage-examples:compileTestKotlin :aws-ktor-service-coverage-examples:test --no-daemon --rerun-tasks`: PASS
- `./gradlew projects --no-daemon`: PASS, `:aws-ktor-service-coverage-examples` 포함
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`: PASS
- `xmllint --noout docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg`: PASS
- `rsvg-convert docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg -o docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.png`: PASS
- `git diff --check`: PASS

## 잔여 위험

Ktor integration/mapping을 결정적으로 증명하지만 service별 live AWS/emulator compatibility test를 대체하지는 않는다. 이 제약을 숨기지 않고 fallback 정책으로 문서화했다.
