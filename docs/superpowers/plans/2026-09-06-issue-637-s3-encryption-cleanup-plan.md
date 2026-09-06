# Issue #637 S3 암호화 전송 cleanup 실패 보존 구현 계획

## Task 0: baseline과 checkpoint

- live issue, base SHA, 대상 파일과 `S3OutputStream` 기존 failure precedence를 확인한다.
- `S3ClientSideEncryptionTransferTest`, `S3OutputStreamTest` baseline을 실행한다.
- 설계, spec review, plan review와 risk ledger를 terminology/diff audit한 뒤 commit한다.

## Task 1: fake-first RED

- `S3OutputStreamTest`에 delete failure 시 file reference와 sanitized failure가 보존되는 테스트를 추가한다.
- CSE transfer test에 cancellation/dispatcher rejection + fallback discard failure를 추가한다.
- download authentication primary + temporary delete 두 번 실패를 추가한다.
- primary identity, suppressed operation, bounded attempt count, residue와 민감 path 비노출을 assertion한다.

## Task 2: cleanup failure model과 retryable discard

- 고정 operation vocabulary를 가진 module-internal sanitized cleanup exception을 추가한다.
- `S3OutputStream`이 성공한 close/delete reference만 비우고 실패한 temporary file을 retry 대상으로
  유지하게 한다.
- public constructor와 upload/download SPI는 그대로 둔다.

## Task 3: CSE bounded fallback

- write, complete, create, download temporary cleanup 경계를 공통 2-attempt helper로 수렴한다.
- primary/cancellation이 있으면 cleanup failure를 suppressed로 붙이고 같은 instance를 다시 던진다.
- cleanup-only failure는 sanitized exception으로 반환한다.

## Task 4: 문서·리뷰·lesson

- `aws-spring-boot` EN/KO README에 failure precedence, 2회 bounded cleanup과 redaction을 같은 구조로
  기록한다.
- main-session six-lens fallback review P0/P1와 lesson을 남기고 index를 갱신한다.

## Task 5: 검증과 PR

- targeted RED/GREEN, `aws-spring-boot` full clean test, detekt, compatibilityCheck, publication
  metadata/POM, terminology와 `git diff --check`를 실행한다.
- base `develop`, head `fix/issue-637-s3-encryption-cleanup`, assignee `debop`, milestone `1.1.0`, issue
  labels를 mirror한 한국어 PR을 만든다.
- exact-head CI를 확인하고 세 PR 종합 merge approval 전에는 merge하지 않는다.

## DoD

- [x] deterministic RED와 bounded GREEN
- [x] primary/cancellation identity와 suppressed cleanup signal
- [x] residue retry와 sensitive path redaction
- [x] existing API/ABI와 full module regression
- [x] six-lens P0=0, P1=0
- [ ] exact-head CI terminal success
