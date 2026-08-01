# 이슈 #273 서비스 커버리지 예제 설계

## 배경

이슈 #273은 SES/v2, SNS, CloudWatch, CloudWatch Logs, Kinesis, STS 지원이
라이브러리에 추가된 뒤 남은 AWS Ktor 예제 커버리지 차이를 해소한다.
기존 저장소 pattern은 사용자를 위한 Ktor 예제 영역마다 예제 모듈 하나를 두고,
root README에서 다루며 CI/Nightly에 등록하는 방식이다.

## 결정

남은 서비스 plugin을 다루는 단일 집중 모듈로
`examples/aws-ktor-service-coverage-examples`를 추가한다. 이 모듈은 기존
plugin config를 통해 operation interface를 주입하여 route 수준의 Ktor 사용법을 보여 준다.

- SES/v2 email 전송
- SNS topic 공개
- CloudWatch metric 공개
- CloudWatch Logs event 공개
- Kinesis record 공개
- STS caller identity 조회

테스트는 외부 emulator 대신 주입된 MockK operation을 사용한다. 따라서 예제의
결정성을 유지하면서도 Ktor plugin 설치, application accessor, 요청 mapping,
응답 mapping을 증명할 수 있다. README에는 실제 배포에서 AWS client/endpoint를
전달할 수 있고 emulator 커버리지는 대상 emulator의 서비스 지원에 따라 달라짐을 기록한다.

## 인수 조건

- `settings.gradle.kts`에 `:aws-ktor-service-coverage-examples`가 있다.
- 모듈이 compile되고 route 테스트를 통과한다.
- root `README.md`와 `README.ko.md`에 모듈과 해당 테스트 명령이 있다.
- 모듈 `README.md`와 `README.ko.md`가 route, plugin 설정, emulator/fallback 동작을 설명한다.
- 서비스 커버리지 chart가 SES/v2, SNS, CloudWatch, CloudWatch Logs, Kinesis, STS의 예제 커버리지를 표시한다.
- CI/Nightly workflow에 새 예제 모듈이 있다.
- `./gradlew projects`, 범위가 좁은 테스트, workflow lint, 다이어그램 render, `git diff --check`가 완료 증거를 제공한다.
