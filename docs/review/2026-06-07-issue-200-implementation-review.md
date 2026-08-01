# Issue #200 구현 검토

날짜: 2026-06-07
범위: `aws-ktor` Ktor EC2 IMDS helper

## 판정

PASS (P0: 0, P1: 0, P2: 0). 차단 문제 없음.

## 검토 증거

- Source: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/imds/`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/imds/`
- Build: `aws-ktor/build.gradle.kts`
- 문서: `README.md`, `README.ko.md`, `aws-ktor/README.md`, `aws-ktor/README.ko.md`
- Workflow: `docs/superpowers/specs/2026-06-07-issue-200-ktor-imds-design.md`, `docs/superpowers/plans/2026-06-07-issue-200-ktor-imds-plan.md`

## 검사

- `P0=0`, `P1=0`; PR 검증으로 진행 가능.
- Plugin 설치는 operation을 저장하지만 startup에 IMDS를 호출하지 않는다.
- Request timeout이 무제한 metadata read를 막는다.
- Public helper는 안전한 metadata/IAM role name만 노출하고 temporary credential document는 노출하지 않는다.
- IMDS endpoint는 명시적이며 일반 service endpoint override를 상속하지 않는다.
- Ktor attribute, validation/assertion, AWS async provider, coroutine `await`를 재사용한다.

## 검증 증거

- `./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency imds --configuration compileClasspath`: `software.amazon.awssdk:imds:2.46.0`
- `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.imds.*'`: 13 PASS
- `./gradlew :bluetape4k-aws-ktor:test`: 82 PASS
- `git diff --check`: PASS
