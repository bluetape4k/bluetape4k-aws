# 테스트 자원은 생성 직후 정리 책임을 확보한다

## 발견 경위

[PR #622](https://github.com/bluetape4k/bluetape4k-aws/pull/622)의 독립 리뷰에서
`S3CopyObjectTest`의 버킷 생성 실패 경로가 누락된 것을 확인했다. 정상 복사 테스트는
통과했지만, 두 번째 버킷 생성이 실패하면 첫 번째 버킷은 `finally`의 보호를 받지 못했다.
사용자가 후속 수정을 요청해 [Issue #625](https://github.com/bluetape4k/bluetape4k-aws/issues/625)로 분리했다.

## 잘못된 가정

두 자원을 모두 확보한 뒤 `try/finally`를 시작해도 정리가 보장된다고 가정했다.
실제로는 다음 자원을 확보하는 과정도 실패할 수 있다. 또한 첫 번째 정리 작업이
실패하면 나머지 정리 작업과 원래 실패의 전달까지 영향을 받는다.

## 수정 원칙

- 테스트가 생성에 성공한 자원은 다음 단계에 들어가기 전에 정리 책임을 확보한다.
- 테스트 전용 fixture를 실제 endpoint 테스트와 fake 기반 실패 테스트에서 함께 사용한다.
- 생성·본문·정리 실패를 각각 주입해 모든 소유 자원의 정리 시도를 확인한다.
- coroutine 취소 후 정리가 필요하면 정리 구간에만 `NonCancellable`을 적용하고 취소를 다시 전달한다.
- 원래 실패가 있으면 정리 실패는 suppressed exception으로 보존한다.

## 검증과 적용 범위

정상 S3 응답만 확인하는 endpoint 테스트에 더해 생성 실패·본문 실패·정리 실패·실제
작업 취소를 결정적으로 검증한다. 검증 명령과 결과는 연결된 PR의 `## DoD Status`에
기록한다. 이 변경은 테스트 fixture에 한정하며 production S3 API는 변경하지 않는다.

최종 로컬 검증은 `:bluetape4k-aws-kotlin:test`와 `:bluetape4k-aws-kotlin:detekt`를
함께 실행해 성공했다(755개 테스트 보고, 13개 skipped). 독립 리뷰에서 지적된 취소
전파 검증도 보강했다. `job.isCancelled`만으로는 helper가 취소 예외를 삼키지 않았다는
사실을 증명할 수 없으므로, 본문에서 관찰한 취소 예외와 helper 밖으로 전달된 예외를
직접 비교한다. 서로 다른 정리 실패가 suppressed exception으로 남는지도 확인한다.

## 다음 리뷰에서 확인할 사항

자원을 순서대로 여러 개 생성하는 테스트는 각 생성 직후의 실패를 검토한다.
정상 종료의 `finally` 존재만 확인하지 말고 부분 생성, 정리 실패, 취소 경로를 각각 확인한다.
