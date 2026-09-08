# #649 구현 최종 검토

기준은 `55e976bd87ec4a668abf7e80a4804d6d1addbf1d`이며 이 이슈의 worktree diff만 검토했다. 설계·계획 검토와 구현 검토를 분리했다. 각 독립 lane은 하나의 관점에서 두 이슈를 모듈별로 구분해 검토했으며 빌드나 외부 상태 변경을 수행하지 않았다. 아래 provenance는 native tool에 선언된 실행 계약이다.

## 독립 관점과 통합

| 관점 | 독립 agent / role | 선언된 model / effort | 최종 결과 |
|---|---|---|---|
| 성능 | final_performance / code-reviewer | gpt-5.6-luna / max | P0/P1 0; 키의 네트워크 대기 전 소거 재검토 완료 |
| 안정성 | final_stability / verifier | gpt-5.6-luna / max | P0/P1 0; 잔여 수명 한계 분리 |
| 보안 | final_security / code-reviewer | gpt-5.6-luna / max | P0/P1 0; S3 수정 후 APPROVE |
| 운영 | final_ops / verifier | gpt-5.6-luna / max | 배포·종료 문서 수정 후 P0/P1 0 |
| 개발자/API | final_api / code-reviewer | gpt-5.6-luna / max | P0/P1 0; payload 편의 API 소거는 후속 범위 |
| 사용자/호출자 | final_caller / writer | gpt-5.6-luna / max | P0/P1 0; EN/KO 계약 일치 |
| 통합 | main / leader | 모델·effort 식별자 미기록 | 소스·호출부·검증·ABI·문서 근거 통합 |

추가 `architect_lifetimes` (`architect`, `gpt-5.6-sol / high`)는 소유권·공개 API 경계를 검토했다. 최초 clock 실패 및 직접 copy/close 경합 검증 공백은 테스트로 보강했다. `review648`/`review649`의 구현 검토도 수정에 반영했다.

## 근거와 수용 기준

- `KmsDataKey`: AutoCloseable, 멱등 close, getter/copy/close lock, 생성자 원본 비소유. 직접 경합 테스트로 완전한 snapshot 또는 closed 예외만 허용한다.
- `DataKeyCache`: put/get 독립 snapshot, map lock 안 퇴출·밖 close, TTL/LRU/교체/evict/clear 배열 소거, clock 실패 무보유, 동시 snapshot 생존을 검증한다. clock 실패로 미게시된 내부 snapshot은 외부 참조가 없어 직접 배열 관찰 대신 무보유·호출자 생존과 finally 소스 검토를 결합했다.
- `KmsCoroutinesEncryptor`: SDK 평문 복사 임시 배열 finally 소거, cache put 실패 시 반환하지 않는 key close, 기존 emulator에서 같은 인스턴스 요구를 독립 snapshot 계약으로 이관했다.
- `KmsAutoConfiguration`: 자동 생성 cache bean의 destroyMethod=clear, context 종료 테스트. custom bean 소유자는 정리 책임을 가진다.
- S3: 암호화·metadata 생성 직후 key use 종료, AWS upload 제출 전에 소거. 성공/실패/진행 중 취소·future 취소, 일반/bounded download 성공·인증 실패·취소를 검증한다.
- KMS text/field adapter는 새 decrypt 반환 배열 소유권 계약에 맞춰 문자열 변환 후 finally 소거한다. 회귀 2개의 RED를 확인했다.
- 기존 public 생성자/getter/cache/operations/S3 메서드 서명은 유지되고 close와 internal copy만 추가된다. `javap -v`로 제거된 `access$getMaxSize$p`가 이전 private 익명 map 구현의 ACC_SYNTHETIC accessor임을 확인했다. 전체 JVM 메서드 집합이 같다고 주장하지 않는다.
- root/module README EN/KO에 custom implementation 이관, canary, 동일 배포물 rollback, 기존 S3 객체 무이관, JVM/JCE/SDK 한계를 기록했다.

## 발견 사항과 처리

| 우선순위 | 항목 | 처리 |
|---|---|---|
| P1 | KMS text/field adapter의 decrypt 반환 배열 누락 | 지역 변수와 finally fill(0), RED2 후 GREEN |
| P1 → P2 | Ops가 제기한 배포 버전 혼용 위험 | runtime classpath/bytecode 혼용으로 범위를 정정; 불변 배포물·canary·rollback 절차 보강, 재검토 수렴 |
| P2 | S3 upload await 동안 key 내부 평문 유지 | 암호화 직후 use 종료, 제출 시점 배열 소거 RED 후 GREEN, 성능·보안 재검토 완료 |
| P2 | 직접 close 경합·clock 실패·TTL/LRU 소거 검증 공백 | 회귀와 동시성 테스트 보강 |
| P2 | lazy TTL 및 context clear 뒤 late put | 최대 보존 상한/terminal close 보장 없음; 요청 중단·진행 작업 종료 후 context close 문서화 |
| P2 | S3 downloadEncryptedText 및 encrypt text adapter의 일반 payload 임시 배열 | 데이터 키와 별개의 기존 payload convenience 경로. 이번 key lifetime 범위에서 유보; 전체 payload 소거를 주장하지 않음 |
| P3 | cache hit snapshot 할당·lock 비용 | 소유권 분리에 필요한 비용. throughput 개선/무비용 주장은 하지 않음 |

## 검증

최종 KMS·S3 테스트 43개(기존 emulator 포함)와 detekt PASS. 캐시 공유 문제, close 누락, SDK/cache 실패, S3 cleanup, adapter 반환 배열 및 upload 대기 전 소거를 단계별 RED로 확인했다. 동시성 fixture는 sleep 대신 latch·future와 반복을 사용한다.

최종 전체 Spring 모듈 test/build/detekt/Kover PASS: 1649개·실패0·건너뜀3이며 세 건은 명시적으로 활성화하는 SNS 측정 테스트다. 건너뜀을 테스트 통과로 집계하지 않는다. compatibilityCheck 및 별도 optional SDK compatibilityTest64개 PASS. PR CI는 게시된 head에서 별도로 확인한다. hosted CI와 머지 준비 상태는 exact-head PR DoD를 기준으로 한다.

## 통합 판정과 범위

구현 검토 P0=0, P1=0. 남은 P2/P3는 위에서 이유와 한계를 명시했다. 구조적 재사용·호출부·테스트·API/ABI·문서·CI·설계 위험을 서로 대체하지 않는다. 새 의존성·CI workflow·release version·암호문 포맷은 변경하지 않았으며 CHANGELOG/release publication은 이번 PR 준비 범위가 아니다. 자동·수동 머지는 실행하지 않는다.

## 문서 검증

이 검토, 설계, 계획, 교훈의 독자는 유지보수자이며 한국어 기술 문서로 작성한다. SPW-01(독자/근거), SPW-02(문서 계약), SPW-03(한국어 기술 문체), SPW-04(소스·수용 기준 추적), SPW-05(최종 읽기) 결과를 PR 준비 evidence에서 각 문서별 확인한다. 식별자·오류·검증 한계를 보존한다.

| 문서 | SPW-01 | SPW-02 | SPW-03 | SPW-04 | SPW-05 |
|---|---|---|---|---|---|
| 설계 | PASS | PASS | PASS | PASS | PASS |
| 구현 계획 | PASS | PASS | PASS | PASS | PASS |
| 설계·계획 검토 | PASS | PASS | PASS | PASS | PASS |
| 이 최종 검토 | PASS | PASS | PASS | PASS | PASS |
| 교훈 | PASS | PASS | PASS | PASS | PASS |

한국어 용어 감사가 지적한 snapshot 표현은 모두 키의 독립 복사본을 뜻하는 기술 용어다. 예약 업무 문맥의 blanket loanword 규칙 대신 이 구체적인 소유권 의미를 유지하는 예외로 분류했다. 현재 source와 문서의 계약, 예제, locale 연결, 표·명령·검증 수치를 대조했으며 미래 CI 성공은 포함하지 않았다.
