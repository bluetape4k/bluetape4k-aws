# #475 S3 CSE provider 위험 예측

**대상:** `docs/superpowers/specs/2026-08-27-issue-475-s3-cse-providers-design.md`
및 `docs/superpowers/plans/2026-08-27-issue-475-s3-cse-providers.md`

**범위:** `aws-spring-boot`의 AES/RSA client-side encryption provider, bounded
read, ciphertext-only streaming/file transfer, Spring 조건부 선택과 Floci 검증.

## 위험 ledger

| ID | 위험·신호 | 예방/완화 | 검증·재실행 지점 | rollback/stop |
| --- | --- | --- | --- | --- |
| R-01 | metadata version/provider/algorithm/encoding/key-id mismatch 또는 GCM/RSA 인증 실패가 부분 평문을 남김 | reserved metadata collision을 업로드 전에 거부하고, canonical AAD 검증과 `S3ClientSideEncryptionException` 경계를 사용하며 인증 성공 뒤에만 평문을 반환/기록 | Task 2 음성 metadata·context·tag 테스트, Task 6 acceptance, Logback redaction capture | Task 2 Lore commit 전 원인 수정; 실패 시 다음 task와 PR을 중단 |
| R-02 | provider `close()`와 암호 작업의 race, AES key material·data key·AAD 임시 배열 잔존 | provider template lifecycle lock, closed-first 전이, AES 복사본 zeroization, RSA 참조 폐기, bounded/stream/file `finally` cleanup | Task 2/3 post-close·zeroization, Task 5/8 cancellation·cleanup 테스트와 source scan | task별 commit만 revert; caller key 객체는 건드리지 않고 원인 보존 |
| R-03 | stream completion 취소/동시 `complete`·`close`에서 delegate가 중복 완료되거나 누수됨 | `Mutex` 단일 terminal owner, state lock, outer dispatcher-entry cancellation cleanup, logical EOF/tag exactly-once | Task 5 dispatcher-entry cancellation, empty/truncated/double-terminal/concurrent 테스트 | Task 5 GREEN 전 transfer acceptance로 진행하지 않음 |
| R-04 | HEAD 이후 객체 교체 또는 remote size 변조로 plaintext temp/destination 경계가 우회됨 | HEAD의 `contentLength`·ETag 필수, GET nested request의 `bucket/key/ifMatch`, 실제 ciphertext size 재검사, authenticated decrypt 뒤 destination write | Task 5/6 ETag mismatch, oversize, existing destination, temp cleanup 테스트 | transfer adapter만 revert하고 provider byte API는 보존 |
| R-05 | Spring provider candidate 0/2개 선택, KMS 자동 fallback, 기존 bean 이름 변경으로 startup/호환성 회귀 | provider 조건을 명시적으로 분리하고 `getIfUnique`, KMS `@ConditionalOnBean` backoff, 기존 `s3ClientSideEncryptionOperations` 이름 유지 | Task 4 context matrix, `compatibilityCheck`, cause-chain assertion | compatibilityCheck/context 실패를 PENDING으로 남기고 계획 승인/구현을 닫지 않음 |
| R-06 | Floci/Testcontainers 공유 Docker 자원 또는 emulator API 차이로 검증이 skip/flake됨 | `@Execution(SAME_THREAD)`, `--max-workers=1`, Colima socket env, owner-token bucket/key, 공유 emulator 재시작 금지 | Task 6 exact Floci command, Task 8 module test와 workflow receipts | container failure는 skip하지 않고 환경/구현 원인별로 repair; 해결 전 DONE 금지 |
| R-07 | 전체 byte API와 RSA wrapping 비용·메모리 상한이 실제 요구를 초과함 | 기존 KMS byte contract 유지, bounded max 67,108,864 ciphertext, streaming은 chunk update, blocking FS는 injected IO dispatcher | Task 8 `performance-stability-scan.md`, bounded/stream tests, benchmark N/A 사유 기록 | throughput target이 없으므로 unsupported performance claim 금지; 초과 시 follow-up issue |
| R-08 | EN/KO 문서와 configuration/API contract가 drift함 | README/manual parity, provider bean 등록 예시, wire compatibility·rotation/HSM 비보장 명시 | Task 7 writer/manual contract/manifest check, final diff review | 문서 gate 실패 시 lesson/PR handoff를 보류 |

## 실행 규칙

- R-01~R-05는 보안·수명·호환성 P1 위험으로 구현 전에 계획된 음성 테스트와
  해당 task GREEN evidence가 필요하다.
- R-06은 공유 Docker 자원 때문에 모든 emulator lane을 순차 실행한다. 실패를
  `assumeTrue`나 silent skip으로 감추지 않는다.
- R-07 benchmark는 처리량 목표나 대표 payload 분포가 제공되지 않아 N/A다.
  bounded 상한·dispatcher 경계·allocation 관찰은 실행한다.
- 각 task의 Lore commit 뒤에는 해당 risk의 검증 receipt를 기록하고, 원인 불명
  3회 반복이 아니라도 동일한 재현 가능한 구현 실패는 다음 task를 차단한다.
- 외부 AWS, PR, push, merge, tag, release는 이 run의 권한 범위가 아니며 실행하지 않는다.

## Plan gate

| Gate | Result | Evidence |
| --- | --- | --- |
| SPW-01 | PASS | 이슈·승인 spec·계획의 위험 경계를 source path와 함께 고정했다. |
| SPW-02 | PASS | 각 위험에 신호, 완화, 검증 명령/단계, rollback/stop을 지정했다. |
| SPW-03 | PASS | 한국어 reader-facing risk prose와 원문 code/token 보존을 확인했다. |
| SPW-04 | PASS | crypto, lifecycle, transfer, Spring, emulator, docs 요구와 ledger를 trace했다. |
| SPW-05 | PASS | 표·heading·code fence read-back 및 `git diff --check`를 수행했다. |

**현재 상태:** 구현 승인 전. 위험 ledger가 해결 증거를 대신하지 않으며, 구현 후
Task 8에서 fresh test/detekt/workflow evidence를 추가한다.
