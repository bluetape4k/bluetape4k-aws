# cleanup 실패는 primary를 바꾸지 않고 bounded signal로 보존한다

## 배경

[Issue #637](https://github.com/bluetape4k/bluetape4k-aws/issues/637)은 S3 client-side
encryption transfer가 실패하거나 취소될 때 delegate discard와 temporary ciphertext file
삭제 실패까지 함께 사라지는 문제를 다룬다. cleanup을 `NonCancellable`에서 실행하는 것만으로는
최종 실패와 residue를 운영자가 확인할 수 없었다.

## 결정

- cleanup은 구성 dispatcher와 `Dispatchers.IO`에서 최대 두 번만 시도한다.
- 원래 failure 또는 cancellation instance를 primary로 유지한다.
- 두 cleanup 시도가 모두 실패하면 고정 operation, 시도 횟수, failure class 이름만 담은
  sanitized exception을 suppressed로 붙인다.
- primary 없이 cleanup만 실패하면 sanitized exception을 직접 던진다.
- close 또는 delete에 실패한 owned resource reference는 성공할 때까지 유지해 두 번째 시도가 같은
  residue를 다시 정리할 수 있게 한다.

## 결과

dispatcher rejection 뒤 IO fallback이 성공하면 기존 primary만 반환한다. 두 dispatcher가 모두
실패하면 cleanup failure가 더 이상 소실되지 않는다. temporary file 삭제가 실패하면 path reference를
먼저 지우지 않으므로 fallback 또는 명시적 discard가 동일한 파일을 다시 정리할 수 있다.
upload가 성공하고 cleanup만 실패한 `S3OutputStream`은 다음 `close()`에서 upload를 반복하지 않고
보존한 owned resource cleanup만 재시도한다.

## 보안 경계

cleanup signal은 raw cause와 message를 보존하지 않는다. bucket, key, local path, credential,
encryption context와 provider material도 포함하지 않는다. failure class 이름은 운영 분류에 필요한
최소 정보로만 사용한다.

## 검증

- cancellation + dispatcher rejection + delete failure
- upload primary failure + delete/discard 반복 실패
- authentication failure + temporary delete 2회 실패
- fallback 성공 후 residue 삭제와 suppressed 미생성
- cleanup-only failure 뒤 두 번째 `close()`의 cleanup-only 재시도와 upload 1회 유지
- primary exception identity와 민감 path/message 비노출

## 향후 보호 장치

resource cleanup은 단순히 best-effort로 소비하지 않는다. bounded retry, primary precedence, 재시도 가능한
ownership reference, redacted final signal을 함께 설계하고 cancellation 및 dispatcher rejection을
deterministic fake로 고정한다.
