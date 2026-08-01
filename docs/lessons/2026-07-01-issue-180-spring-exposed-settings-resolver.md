# Issue #180 Spring Exposed 설정 해석기 교훈

## 배경

#180에서는 Secrets Manager와 Parameter Store를 통해 `aws-exposed` 데이터베이스 설정을 해석하는 Spring Boot 통합을 요청했다.

## 결정

Exposed auto-configuration 내부에 별도 AWS 클라이언트 경로를 만들지 않고 기존 Spring Environment 후처리기를 재사용한다. Exposed 해석기는 설정한 `secret-source` / `parameter-source` 접두사를 읽고 Environment에 실제로 존재하는 키만 덮어쓴다.

## 결과

이 방식은 AWS 로딩, 갱신, 즉시 실패 동작을 기존 Environment 소스 계층에 유지한다. 동시에 `default-database.url`이 원격 소스에서만 제공되더라도 `AwsExposedAutoConfiguration`이 레지스트리를 생성할 수 있다.

## 향후 보호 장치

원격 설정용 프레임워크 어댑터를 추가할 때는 프레임워크에 이미 구성 소스 수명 주기가 있는지 먼저 확인한다. 어댑터에서 서비스 클라이언트를 직접 호출하기보다 해당 수명 주기를 사용하는 해석기를 우선한다.
