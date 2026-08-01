# Issue #197 Ktor AWS 핵심

## 배경

`aws-ktor`에도 #190에서 도입한 Spring AWS 핵심 기본값과 같은 운영 개념이 필요했다.
다만 Ktor는 Spring bean 대신 application/plugin 설정을 사용해야 한다.

## 결정

공통 기본값을 application attribute에 저장하는 opt-in `AwsKtorCore` plugin을 사용한다.
서비스 plugin은 설치 중 해당 기본값을 읽으며 서비스 로컬 설정이 우선한다. 공통
기본값은 bluetape4k `AbstractValueObject`로 modeling하되 live provider, engine,
customizer는 runtime collaborator이므로 transient로 유지한다.

## 결과

- S3에 기본값 기반 factory overload를 추가했다.
- 이제 SQS는 plugin 소유 client를 생성하고 한 번만 닫는다.
- DynamoDB plugin이 생성한 client는 공통 기본값과 customizer를 상속한다.
- README에 Graphviz를 근거로 한 `aws-ktor` 아키텍처 다이어그램을 추가했다.

## 검증 증거

- 구현 중 대상 compile과 테스트를 실행했다.
- SVG에서 아키텍처 PNG를 렌더링하고 시각적으로 검사했다.

## 향후 보호 장치

Ktor 통합에서는 주입한 client 소유권과 plugin이 생성한 소유권을 분리한다. README
다이어그램을 변경하면 `bluetape4k-diagram`을 적용한다. Graphviz 근거, SVG+PNG,
README PNG embed, 렌더링한 PNG 검사는 DoD에 포함된다.
