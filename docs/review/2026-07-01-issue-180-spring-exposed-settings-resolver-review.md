# Issue #180 Spring Exposed Settings Resolver 검토

날짜: 2026-07-01
범위: `aws-spring-boot` Exposed 자동 구성, Spring Environment 기반 database settings resolver, README locale.

## 판정

P0: 0
P1: 0

## 검토 내용

| Tier | 결과 | 증거 |
|---|---|---|
| Tier 4 정확성 | PASS | `SpringEnvironmentAwsDatabaseSettingsResolver`는 `secretSource`/`parameterSource` 접두사의 기존 key만 덮어쓰고 미설정 field를 유지한다. 필수 source 누락은 즉시 실패하고 선택 source 누락은 명시적 설정을 유지한다. |
| Tier 5 통합 | PASS | `AwsExposedAutoConfiguration`은 `default-database.url` 또는 source descriptor prefix가 있으면 registry를 만들고 둘 다 없으면 물러난다. |
| Tier 7 문서/증거 | PASS | `README.md`와 `README.ko.md`는 Spring Environment source 흐름과 Exposed가 별도 AWS client 경로를 만들지 않음을 설명한다. |
| 보안/secret | PASS | 원격 `password`를 `AwsSecretString`으로 감싸고 raw value를 logging하지 않고 reveal 동작을 검증한다. |
| 회귀 | PASS | `:bluetape4k-aws-spring-boot:test` 247 tests, 0 failures, 0 errors, 0 skipped. |
| Graph 영향 | PASS | CodeGraph affected-flow 0. 현재 graph는 Spring 자동 구성 runtime 경로를 모델링하지 않는다. |

## 검증

- `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin --warning-mode all --no-daemon --stacktrace`: PASS
- `./gradlew :bluetape4k-aws-spring-boot:test --no-daemon --stacktrace`: PASS
- Test XML: `tests=247 failures=0 errors=0 skipped=0`
- `git diff --check`: PASS

## 잔여 위험

Resolver는 기존 Secrets Manager/Parameter Store post-processor가 게시한 Spring Environment property name에 의존한다. AWS 값을 직접 가져오지 않으므로 애플리케이션은 해당 Environment source를 계속 구성해야 한다.
