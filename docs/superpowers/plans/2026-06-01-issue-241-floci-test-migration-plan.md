# 이슈 241 Floci 테스트 이관 계획

이슈: [#241](https://github.com/bluetape4k/bluetape4k-aws/issues/241)
날짜: 2026-06-01

## 범위

LocalStack을 기본값으로 사용하는 AWS 테스트를 Floci 기본값으로 이관하면서 API 커버리지 차이에 대한 명시적 fallback으로 LocalStack을 보존한다.

MiniStack은 이 구현 범위에 포함하지 않는다. 자동 fallback chain은 실패한 emulator를 숨기고 container 사이에서 테스트 상태를 잃을 수 있으므로, 이미 MiniStack selector를 노출하는 모듈의 비교 backend로만 유지한다.

## 작업

1. AWS emulator 인식 테스트 작업에서 `bluetape4k.aws.emulator`의 기본값을 `floci`로 바꾼다.
2. 공유 `LocalStackServer` 테스트 fixture 직접 사용을 `floci`와 `localstack`을 지원하는 `AwsEmulatorServer` selector로 교체한다.
3. 호출부 변경을 최소화하도록 legacy `localStackServer` fixture 이름을 alias로 유지한다.
4. Floci가 지원하지 않는 KMS grant/key-state API와 SNS phone opt-out API를 JUnit assumption으로 보호하여 명시적인 LocalStack 실행에서는 계속 검증한다.
5. Java DynamoDB food 예제에서 LocalStack 전용 Spring 테스트 placeholder를 제거하고 emulator endpoint/credentials를 동적으로 주입한다.
6. Ktor 및 AWS 예제 모듈의 직접 LocalStack fixture를 emulator 인식 범위에서 같은 Floci 우선 selector로 이관한다.
7. README emulator 정책을 갱신하고 검증 증거를 기록한다.

## 검증

- Java/Kotlin 테스트 소스를 compile한다.
- 기본 Floci로 전체 Java SDK wrapper 테스트를 실행한다.
- 기본 Floci로 전체 Kotlin SDK wrapper 테스트를 실행한다.
- 기본 Floci로 영향을 받은 Ktor 및 AWS 예제 모듈 테스트를 실행한다.
- KMS grant/key-state와 SNS phone opt-out 동작을 위한 범위가 좁은 LocalStack fallback smoke test를 실행한다.
- `git diff --check`를 실행한다.

## 알려진 차이

역사적으로 `LocalStack`을 포함한 class 이름은 diff 범위를 좁게 유지하기 위해 바꾸지 않는다. 해당 runtime fixture는 이제 기본적으로 Floci를 선택한다.
