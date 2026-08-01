# Issue 272 Ktor Kinesis 및 STS

## 배경

Issue #272에서는 하위 계층 `aws-java` 래퍼와 Spring Kinesis 패턴을 사용할 수 있게 된
뒤 Ktor용 Kinesis 및 STS 도우미를 추가한다.

## 결정

Ktor 계층은 얇고 Spring에 의존하지 않게 유지한다. Kinesis에는 로컬 요청 모델,
플러그인 수명 주기, 명시적인 단일 샤드 cold `Flow`를 제공한다. STS에는 아이덴티티 및
임시 세션 요청 도우미를 제공한다. 두 계층 모두 원본 AWS SDK 응답을 반환한다.

## 결과

이제 `aws-ktor`는 `KinesisKtorPlugin`, `StsKtorPlugin`, 두 SDK 클라이언트용 공통
`AwsKtorCore` 사용자 지정 기능, 선택적 SDK 의존성 연결, README 언어 문서를 제공한다.
서비스 커버리지 차트는 Ktor Kinesis 및 STS 지원을 선택적 SDK 의존 지원으로 표시한다.

## 검증

- Kinesis 및 STS 대상 테스트가 통과했다.
- 강제 `aws-ktor` 테스트 컴파일이 통과했다.
- 서비스 커버리지 차트 PNG를 다시 생성하고 시각 검사했다.

## 향후 지침

`recordFlow`를 숨겨진 리스너 컨테이너로 만들지 않는다. 임대 조정, 체크포인트 기록,
KCL형 소비자는 별도 이슈에서만 추가한다. 전용 Ktor 인증 통합을 설계할 때까지 STS는
하위 계층 아이덴티티/세션 연산으로 취급한다.
