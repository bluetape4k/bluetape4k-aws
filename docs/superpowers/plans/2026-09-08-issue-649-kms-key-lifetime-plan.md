# #649 구현 계획

설계: [KMS 키 수명](../specs/2026-09-08-issue-649-kms-key-lifetime-design.md)

1. [완료] 설계의 성능, 안정성, 보안, 운영, API, 호출자 관점 검토와 통합 판단을 기록한다.
2. [완료] `InMemoryDataKeyCacheTest`에서 저장 객체와 조회 객체가 독립이어야 한다는 회귀를 추가하고 RED를 확인한다.
3. [완료] `KmsDataKey.kt`에 동기화된 close와 내부 복사를 추가한다. `DataKeyCache.kt`에 소유권 KDoc과 퇴출 소거를 구현한다. 수명 및 경합 테스트를 확장한다.
4. [완료] `KmsCoroutinesEncryptor.kt`의 생성 중간 배열 및 실패 경로를 정리한다. `S3ClientSideEncryptionOperations.kt`의 업로드/다운로드 키를 finally/use로 소거하고 정상·실패 테스트를 추가한다.
5. [완료] 기존 emulator 동일 인스턴스 기대를 독립 소유 계약으로 바꾸고 README 양 언어에 사용/이관 계약을 간결하게 반영한다.
6. [완료] 관련 단위 및 emulator 테스트, 모듈 build/detekt/ABI를 직렬 실행한다. 독립 코드 및 아키텍처 리뷰에서 발견한 범위 내 결함을 수정한다.
7. [진행 중] 교훈과 검증 한계를 기록하고 한국어 Lore commit 및 issue metadata를 반영한 PR을 생성한다. PR head와 CI를 다시 확인하며 머지는 보류한다.

## 변경 경계

`aws-spring-boot` KMS/S3 구현, KmsOperations KDoc, KmsAutoConfiguration Bean 종료와 테스트, README 양 언어, 본 설계/계획/교훈 문서만 변경한다. 다른 이슈의 fingerprint 구현을 가져오지 않는다. 캐시 인터페이스에 메서드를 추가하지 않으며 암호문 포맷을 변경하지 않는다.

## 검증 실행

먼저 `:bluetape4k-aws-spring-boot:test --tests '*InMemoryDataKeyCacheTest'`로 RED를 관찰한다. 이후 실제 변경 테스트 selector와 기존 KMS/S3 emulator 테스트를 실행하고 전체 모듈 test/build 및 detekt를 수행한다. 공유 Docker와 Gradle 작업은 리더가 직렬 예약한다. 실패/skip은 통과로 기록하지 않는다.

## 검토로 보강한 작업

- KmsAutoConfiguration 캐시 Bean에 clear 종료 hook을 명시하고 context close 시 보유 배열 소거를 검증한다.
- LRU 제거를 명시적으로 구현하고 모든 퇴출 사유별 retained-array zeroization을 검증한다.
- KmsOperations 생성/복호화 결과의 호출자 소유권과 사용자 정의 구현 이관을 KDoc/README에 명시한다.
- 업로드 future 취소 및 다운로드 AWS 대기 취소 전파를 검사하고, 복호화 성공/인증 실패 후 배열 소거를 확인한다.

- removeEldestEntry override를 제거하고 put에서 초과 entry를 명시적으로 분리한다. get 복사는 lock 안, 퇴출 배열 소거는 분리 후 lock 밖으로 제한한다. put 복사본의 삽입 실패도 소거한다.
- 동시 cache get/evict/put과 key copy/close 스트레스 테스트를 추가한다. 임의 시간 임계값 대신 손상/예외/진행 완료를 검증하며 미측정 throughput은 성능 보장으로 주장하지 않는다.

## API 검토 반영

- Task 4는 S3 정상/실패/취소 및 KMS 캐시 저장 실패 회귀 테스트를 먼저 작성·RED 확인한 뒤 production을 바꾸는 두 단계로 실행한다.
- PR DoD는 기존 생성자/getter/캐시 메서드의 binary compatibility와 사용자 정의 캐시/operations의 behavioral migration을 구분한다. AutoCloseable/close 추가는 의도한 additive API이며 기존 메서드 삭제·서명 변경과 별도로 확인한다.
- 사용자 정의 구현의 독립 소유권 이관 KDoc/README가 없으면 PR 단계로 진행하지 않는다.
- README 운영 항목에 lazy TTL의 보존 상한 부재, 외부 캐시 종료 책임, custom cache 독립성 점검, 전체 릴리스 단위 rollback을 기록한다.
- DataKeyCache.put의 예외 시 새 키 무보유·snapshot 소거 계약을 KDoc에 명시하고 clock 실패 및 실패하는 custom cache fixture로 검증한다. generate/get use, plaintext/decrypt finally 예제를 포함한다.

## 검토 수정 근거

KMS text/field adapter의 반환 평문 누락은 RED2 뒤 finally로 고쳤다. S3키가 네트워크 대기 전에 소거돼야 한다는 RED를 추가한 뒤 use 범위를 줄였다. 최종 KMS/S3 43개와 detekt PASS, 공개 서명 비교에서 기존 source API 누락0. 제거된 private 익명 map의 synthetic accessor는 별도 기록한다.

최종 전체 Spring 모듈1649개(실패0·건너뜀3), build/detekt/Kover와 compatibilityCheck(별도64개) PASS. PR 게시와 exact-head CI는 단계7에서 확인한다.
