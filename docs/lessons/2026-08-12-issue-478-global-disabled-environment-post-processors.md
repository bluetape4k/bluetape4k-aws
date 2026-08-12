---
title: EnvironmentPostProcessor 전역 AWS 비활성화 경계
date: 2026-08-12
issue: 478
module: aws-spring-boot
---

# Issue #478 EnvironmentPostProcessor 전역 AWS 비활성화 경계

## 배경

`bluetape4k.aws.enabled=false`는 자동 구성의 전역 비활성화 스위치였지만,
`spring.factories`로 등록된 S3 config, Secrets Manager, Parameter Store
`EnvironmentPostProcessor`는 서비스별 속성만 확인했다. 따라서 전역 스위치가 꺼져도
원격 source 설정을 바인딩하고 AWS SDK class 확인과 loader 호출까지 진행할 수 있었다.

## 결정 또는 발견 사항

- 세 후처리기는 서비스 속성을 바인딩하기 전에 공유 `bluetape4k.aws.enabled` 값을
  확인한다.
- 전역 값이 `false`이면 서비스 source와 SDK class를 읽지 않고 즉시 반환한다.
- 전역 값이 없으면 기존 기본값(`true`)을 유지하며 서비스별 `enabled`와 source 검사는
  그대로 적용한다.

## 결과

자동 구성과 startup Environment source가 같은 전역 스위치 계약을 공유한다. 구성된
원격 source가 있어도 전역 비활성화 상태에서는 SDK client 생성이나 property source
추가가 발생하지 않는다.

## 검증

- RED: 전역 `false`와 구성된 source에서 세 loader가 호출되지 않아야 한다는 테스트가
  기존 구현에서 모두 실패했다.
- GREEN: 세 후처리기의 enabled/disabled bootstrap 회귀 테스트 6개를 통과했다.
- Floci emulator 기반 S3, Secrets Manager, Parameter Store 후처리기 테스트 10개를
  통과했다.
- `:bluetape4k-aws-spring-boot:test -PskipAwsEmulatorTests=true` 모듈 테스트 284개와
  `:bluetape4k-aws-spring-boot:detekt`를 통과했다.

## 향후 지침

`spring.factories` 또는 다른 초기 bootstrap extension으로 AWS 접근을 추가할 때는
자동 구성 annotation만으로 전역 비활성화 계약이 완성되지 않는다. 서비스 속성 바인딩,
SDK class 확인, client/loader 호출보다 먼저 공유 전역 스위치를 확인하고, enabled와
disabled 양쪽의 bootstrap 테스트를 함께 추가한다.
