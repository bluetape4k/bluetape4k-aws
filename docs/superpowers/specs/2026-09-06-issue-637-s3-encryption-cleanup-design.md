# Issue #637 S3 암호화 전송 cleanup 실패 보존 설계

## 목표와 범위

`S3EncryptedOutputStream`과 암호화 파일 download가 primary failure 또는 cancellation을 그대로
유지하면서 delegate discard와 temporary file delete의 최종 실패를 관측 가능한 suppressed signal로
보존한다. 기존 공개 transfer API, client/provider 소유권과 암호화 payload 형식은 바꾸지 않는다.

## 현재 결함

- write/complete/create 실패 뒤 `discardBlocking()` 예외를 `runCatching`으로 버린다.
- configured dispatcher가 cleanup 진입을 거절하면 `Dispatchers.IO`로 한 번 더 시도하지만 두 번째
  실패도 버린다.
- download temporary ciphertext 삭제가 두 dispatcher 모두에서 실패해도 성공/primary failure만
  반환한다.
- `S3OutputStream.discardBlocking()`은 삭제 전에 file reference를 비워 재시도가 residue를 찾지 못한다.

## 결정

### Bounded cleanup

cleanup은 configured dispatcher와 `Dispatchers.IO`에서 최대 두 번 시도한다. 첫 시도가 실패하고
fallback이 성공하면 최종 cleanup 성공으로 처리한다. 두 시도가 모두 실패하면 고정 operation과
failure class 이름만 담은 sanitized cleanup exception을 만든다.

### Failure precedence

- primary failure/cancellation이 있으면 같은 instance를 다시 던지고 cleanup exception을 suppressed로
  붙인다.
- primary가 없고 cleanup만 실패하면 sanitized cleanup exception을 던진다.
- raw local path, bucket, key, metadata, credential, provider material과 원래 exception message는 cleanup
  signal에 넣지 않는다.

### Retryable residue ownership

`S3OutputStream`은 close/delete가 성공한 resource reference만 비운다. delete가 실패하면 temporary
file reference를 보존해 fallback discard가 같은 owned resource를 다시 정리할 수 있게 한다.
cleanup 시도 횟수는 caller가 아니라 이 경계에서 두 번으로 제한한다.

### Test seam

`S3OutputStream`에 module-internal temporary-file delete function을 둔다. public constructor와 API는
유지하면서 fake가 delete failure와 recovery를 결정적으로 주입한다. production 기본값은
`Files.deleteIfExists`다.

## 수용 조건

- [x] primary exception/cancellation identity를 보존하고 최종 cleanup failure를 suppressed로 기록한다.
- [x] configured dispatcher rejection 뒤 fallback cleanup을 한 번만 실행한다.
- [x] delete/discard 두 시도 실패와 residue 존재를 deterministic fake로 검증한다.
- [x] fallback 성공 시 owned temporary file을 제거한다.
- [x] cleanup signal에 raw path, bucket, key, credential과 원문 failure message가 없다.
- [x] 기존 공개 API/ABI, encryption/decryption 결과와 transfer lifecycle이 유지된다.

## 비목표

- background cleanup worker, unbounded retry, 새 metrics backend 또는 새 dependency를 추가하지 않는다.
- caller-owned S3 client/provider를 닫거나 외부 bucket/object를 삭제하지 않는다.
- multipart upload abort 정책을 wrapper가 새로 소유하지 않는다.
