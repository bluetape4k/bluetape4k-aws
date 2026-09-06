# 공통 정책은 decoder가 아니라 판정 계약을 공유한다

## 배경

[Issue #635](https://github.com/bluetape4k/bluetape4k-aws/issues/635)은 Ktor와 Spring의
SNS HTTP parser가 비슷한 구조 검증을 서로 구현해 duplicate field, 인증서 host와 오류 분류가
어긋날 수 있는 문제를 다뤘다. 두 adapter는 공개 모델과 JSON decoder 주입 방식이 서로 다르다.

## 결정

- JSON decode와 기존 공개 모델 변환은 framework adapter에 남긴다.
- decode된 map에 적용하는 body·field·type·ARN·URI 정책과 rejection reason은 `aws-java`의
  framework-neutral policy가 소유한다.
- 두 adapter는 물리적으로 같은 Gradle test fixture corpus를 소비하고 reason과 redacted
  message까지 비교한다.
- byte 상한은 decode 전에 적용하고 JSON decode는 adapter당 한 번만 실행한다.
- cancellation은 동일 인스턴스로 전파하며, 일반 decode 원인은 외부 exception cause에 연결하지
  않는다.

## 결과

Ktor와 Spring parser에서 중복된 검증 helper를 제거했다. duplicate/non-object/malformed JSON,
256 KiB 경계, 필수 field, type/header, exact partition·region signing host와 URI 제한이 같은
typed 결과로 수렴한다. 기존 parser API, adapter별 `raw` 의미와 signature verification 책임은
유지했다.

## 검증

- core policy 11개와 양 adapter conformance 5개 통과
- affected module 전체: Java 530 passing/15 pending, Ktor 256 passing, Spring 1619 passing/6 pending
- affected 3 modules detekt와 `compatibilityCheck` 64개 통과
- Gradle module metadata와 Maven POM 생성 통과
- 한국어 terminology audit와 `git diff --check` 통과

## 놓치기 쉬운 점

정책을 공통화한다는 이유로 Jackson parser 전체를 core로 옮기면 framework dependency와 custom
mapper 계약을 불필요하게 결합한다. 반대로 같은 테스트 데이터를 두 파일에 복사하면 실제 판정
구현이 하나여도 경계 case가 다시 drift한다. 또한 broad decode exception 변환은
`CancellationException` identity를 파괴할 수 있다.

## 향후 지침

adapter 간 입력 정책을 맞출 때는 decoder 구현이 아니라 decoded value에 대한 판정 계약과 한
corpus를 공유한다. 새 field·host·type 규칙은 core policy와 공통 fixture를 같은 변경에서 갱신하고,
양 adapter가 reason과 redacted message까지 동일하게 반환하는지 확인한다.
