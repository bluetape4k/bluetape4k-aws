# Issue #675 implementation baseline stale fixture

## 배경

`implementationBaselineCheck`가 canonical `develop`에서
`SqsOperations` source implementation baseline 불일치로 실패했다. public ABI
검증과 legacy consumer fixture는 정상이어야 하므로, implementation hash와
공개 signature를 분리해서 확인했다.

## 원인

`src/abi-fixtures/sqs-pre-change/`는 Issue #455 시점의 source/bytecode hash를
보존하고 있었지만, 이후 승인된 #460 train(`86f834ae`)이
`SqsQueueAttributesOperations` additive capability를 추가했고
`6e3b4d3b`가 같은 파일을 정리했다. `javap -public` 결과는 기존
`SqsOperations` signature와 동일했지만 source와 class bytes는 달라졌다.

## 결정

공개 signature fixture는 유지하고 implementation baseline 두 값만 현재 승인된
산출물로 갱신했다. fixture README에 baseline의 두 의미를 명시해 다음 변경이
public ABI 확장인지 구현 baseline 갱신인지 먼저 판별하도록 했다. 기존 hash는
Issue #675 재현 기록에 남아 있으므로 drift를 숨기지 않는다.

## 검증

- RED: `./gradlew implementationBaselineCheck --no-configuration-cache --no-build-cache --no-daemon --console=plain`
  가 기존 source hash
  `c449e2bf035ac15a21dcf80bd6b4a6d42d9e14893b393a9facd045e0364de83f`와 현재
  `29c21fd3cc7a7fb1d2cb140fec7cb8240b35dbee86cfdd3e2fc038331845ccb0`의
  불일치로 실패했다.
- `javap -public` 출력에서 `Compiled from` 줄을 제거한 결과는 기존
  `src/abi-fixtures/sqs-pre-change/javap.txt`와 일치했다.
- 현재 JAR의 `SqsOperations.class` hash는
  `4c5973ad765de3acc5cef57bf8e57af902afc176299b1216b5d1fdb813219bfe`로
  캡처했다.

## 재발 방지

같은 public owner의 source를 의도적으로 변경한 뒤에는 implementation baseline
task만 재실행하지 말고, 먼저 normalized `javap -public`와 legacy consumer
fixture를 확인한다. signature가 유지되면 승인된 source/bytecode를 다시
캡처하고 그 이유와 commit을 fixture README 및 PR DoD에 함께 기록한다.
