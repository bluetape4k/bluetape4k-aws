# Issue #273 서비스 커버리지 예제 교훈

## 배경

Issue #273에서는 서비스 플러그인을 반영한 뒤에도 남은 AWS 서비스 커버리지 공백을
메우는 예제를 요청했다.

## 결정

SES/v2, SNS, CloudWatch, CloudWatch Logs, Kinesis, STS에 하나의 Ktor 서비스
커버리지 모듈을 사용한다. 작업 facade를 주입해 테스트의 결정성을 유지하고, 모든
대상 서비스가 동일한 emulator 지원을 제공한다고 가장하는 대신 대체 경로를
문서화한다.

## 결과

이제 모듈을 컴파일할 수 있고, 6개 서비스 영역의 route/plugin accessor 동작을
테스트한다. 또한 README 언어 묶음과 서비스 커버리지 chart를 갱신하고 CI/Nightly에
등록했다.

## 향후 지침

Emulator 동작이 균일하지 않은 서비스의 예제 커버리지를 추가할 때는 계약을 명확히
나눈다.

- 결정적인 route/plugin 테스트는 주입한 operations를 사용한다.
- Emulator 또는 live AWS 테스트는 명시적인 호환성 검사다.
- README에는 검증하는 경로를 명시해야 한다.
