# 이슈 #649 KMS 평문 데이터 키 수명 교훈

## 배경

KMS/S3가 평문 키를 사용한 뒤 배열을 지우지 않았고, 기존 캐시는 같은 키 객체를 공유했다. 단순히 소비자가 key를 닫으면 동시 사용자를 깨뜨리므로 소거 시점과 소유권을 함께 바꿔야 했다.

## 결정

`KmsDataKey`는 `AutoCloseable`을 구현하고 내부 평문 배열을 소유한다. `close()`는
멱등적으로 배열을 소거하며, 닫힌 객체의 `plaintext` 접근은 실패한다. 생성자 입력과
getter가 반환한 배열은 호출자 소유로 남긴다. 평문 getter·내부 snapshot 복사·소거는
같은 잠금으로 직렬화해 복사 중 부분 소거를 막는다.

`DataKeyCache`는 입력 객체의 소유권을 가져가지 않고 독립 snapshot을 저장한다. `get`은
호출자가 닫을 독립 snapshot을 반환한다. 인메모리 구현은 TTL 확인, 교체, LRU 축출,
`evict`, `clear`에서 캐시가 보유한 키를 소거한다. map에서 제거하는 작업은 잠금 안에서
끝내고 실제 `close`는 잠금 밖에서 실행해 사용자 코드가 잠금을 오래 점유하지 않게 한다.

## 실패에서 배운 점

- 캐시 저장이 실패하면 encryptor가 반환하지 못한 생성 키를 닫아야 한다. SDK가 반환한
  평문 임시 배열도 `KmsDataKey`에 복사한 뒤 `finally`에서 소거해야 한다.
- Spring이 만든 캐시는 `destroyMethod = "clear"`를 명시해야 context 종료 시 보유 키를
  지운다. 사용자 정의 캐시의 종료 수명은 해당 Bean 제공자가 관리한다.
- 과거 emulator 테스트의 동일 인스턴스 기대는 독립 snapshot 계약과 충돌했다. 캐시
  재사용은 객체 identity가 아니라 암호문·평문 값과 독립적인 close 수명으로 검증한다.
- Java SDK Consumer overload를 MockK로 설정할 때 `Consumer<GenerateDataKeyRequest.Builder>`
  타입을 명시해야 한다. 다른 response builder 타입을 사용한 이전 RED 로그는 실제 API
  overload와 맞지 않아 compile 단계에서 실패했다.

- S3 키의 `use`가 upload await까지 이어지면 네트워크 지연 동안 평문이 남는다. 로컬 암호화·metadata 생성 직후 close하고 AWS 제출 시점의 배열 소거를 회귀로 고정했다.

## 검증 방어선

- `KmsDataKeyTest`는 멱등 close, 내부 배열 소거, 원본 배열 보존, 닫힌 객체 접근 거부를
  확인하며 직접 copy/close 경합도 반복 검증한다.
- `InMemoryDataKeyCacheTest`는 독립 snapshot, TTL, LRU, 교체, `evict`, `clear`,
  삽입·조회·축출의 동시 실행을 확인하고 retained plaintext가 부분 소거되지 않는지
  확인한다.
- `KmsCoroutinesEncryptorTest`는 cache `put` 실패 시 생성 키 소거와 성공 시 SDK 임시
  plaintext 소거를 확인한다.
- `KmsAdapterPlaintextLifetimeTest`는 `KmsTextEncryptor`와 `KmsEncryptedFieldCodec`가
  문자열로 변환한 뒤 `KmsOperations.decrypt`의 반환 배열을 소거하는지 확인한다.

## 한계와 운영 이관

JVM·JCE·AWS SDK 내부 복사본과 GC가 남긴 메모리는 라이브러리가 완전히 소거할 수 없다.
lazy TTL은 엄격한 최대 보존시간을 보장하지 않으므로 유휴 캐시의 보안상 보존 상한이
필요하면 캐시를 비활성화하거나 애플리케이션의 명시적 `clear` 정책을 사용해야 한다.
사용자 정의 `DataKeyCache`는 독립 snapshot, `put` 실패 원자성, 종료 시 `clear` 계약을
통합 테스트로 확인해야 한다.

Spring context 종료의 `clear`와 진행 중인 KMS 작업의 `put` 사이에는 순서 경합이 남는다.
애플리케이션은 context를 닫기 전에 KMS 작업을 quiesce, cancel 또는 drain해야 한다. 이 PR은
재사용 가능한 cache를 영구적으로 닫거나 늦은 `put`을 거부하는 별도 상태 API를 추가하지 않는다.

## DoD Status

- [x] KMS 키 객체·캐시의 독립 소유권과 소거 계약을 코드/KDoc에 반영
- [x] 생성 실패·context 종료·캐시 경쟁 회귀 테스트 추가
- [x] 최종 KMS/S3 회귀43개(기존 emulator 포함)와 detekt PASS
- [x] 모듈 전체 test/build/detekt/Kover PASS: 1649개·실패0·건너뜀3(명시적 SNS 측정)
- [x] compatibilityCheck 및 별도64개 테스트 PASS
- [ ] PR CI 최종 증거
- [ ] PR exact-head 검토; merge는 이번 요청에 따라 보류

**상태: PENDING — 로컬 구현·검증 완료, PR 게시와 exact-head CI 확인 대기.**

## 향후 지침

키 객체의 lifetime과 getter가 반환한 배열의 lifetime을 각각 검증한다. opaque한 미게시 내부 복사본은 새 production test hook을 만들기보다 실패 원자성·호출자 생존과 finally 소스 근거를 함께 확인한다. custom cache 이관 canary와 동일 배포물 rollback을 유지한다. worktree의 교훈은 canonical GNO 제외 정책상 머지 뒤 색인한다.
