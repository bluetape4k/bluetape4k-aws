# Issue #618 CopyObject source RFC 3986 인코딩

## 배경

`aws-kotlin/s3`의 `copyObjectRequestOf(srcBucket, srcKey, ...)`가
`java.net.URLEncoder` 결과를 `x-amz-copy-source` 값으로 사용했다. `URLEncoder`는
HTML form/query 인코더라서 공백을 `+`로 바꾼다. S3 `CopyObject` API는 이 헤더를
URL-encoded path로 받으며, 공백은 `%20`으로 표현해야 한다.

관련 이슈: [#618](https://github.com/bluetape4k/bluetape4k-aws/issues/618)

## 실패한 가정과 발견 증거

기존 회귀 테스트는 `"src key"`가 `"src-bucket%2Fsrc+key"`가 되는 결과를 기대해
form encoding 동작을 고정했다. 테스트를 실제 계약으로 바꿔
`folder/a b+c/한글?#.txt`를 검증하자 다음 RED가 재현됐다.

```text
Expected "src-bucket%2Ffolder%2Fa+b%2Bc%2F..." to equal to
"src-bucket%2Ffolder%2Fa%20b%2Bc%2F..."
```

공백을 `+`로 보내면 literal `+`와 구분되지 않으므로 특수문자 키의 `copy`와
`move`가 원본을 찾지 못하거나 다른 객체를 가리킬 수 있다. S3 공식 문서도
[`x-amz-copy-source`를 URL-encode](https://docs.aws.amazon.com/AmazonS3/latest/API/API_CopyObject.html)하도록
요구한다.

## 수정 결정

- raw `srcBucket`/`srcKey` overload는 UTF-8 바이트를 RFC 3986 규칙으로 직접
  percent-encode한다.
- bucket과 key를 하나의 header 값으로 만들기 때문에 bucket/key 경계와 key 내부의
  slash를 모두 `%2F`로 인코딩한다. 공백은 `%20`, literal `+`는 `%2B`, `?`는
  `%3F`, `#`은 `%23`이 된다.
- 이미 URL-encoded된 `copySource` overload는 값을 그대로 전달해 이중 인코딩을
  막는다. KDoc 예시도 `%2F`가 포함된 값으로 갱신했다.
- 새 의존성은 추가하지 않았다. 이 규칙은 S3 header 계약에만 적용하며 다른 URL/query
  인코더의 동작은 변경하지 않는다.

## 결과

요청 필드 회귀 테스트가 공백, literal `+`, Unicode, `?`, `#`, slash를 모두
검증한다. Floci 기반 S3 endpoint 테스트는 특수문자 원본을 업로드한 뒤 `copy`하고
대상 객체를 다시 읽어 내용이 동일한지 확인한다. 따라서 문자열 필드와 실제 SDK
요청 경로를 각각 확인한다.

## 검증

- RED: `./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.s3.model.S3ModelSupportTest' --no-daemon --console=plain` — 13개 중 1개가 의도대로 실패
- GREEN targeted: `./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.s3.model.S3ModelSupportTest' --tests 'io.bluetape4k.aws.kotlin.s3.model.S3CopyObjectTest' --no-daemon --console=plain` — 19개 통과(Floci endpoint 복사 포함)
- 전체 모듈: `./gradlew :bluetape4k-aws-kotlin:test --no-daemon --console=plain` — 749개 통과, 13개 skip
- 정적 분석: `./gradlew :bluetape4k-aws-kotlin:detekt --no-daemon --console=plain` — 성공
- 변경 경계: `git diff --check` — 성공

## 향후 지침

- S3 header, path, query처럼 전달 위치가 정해진 값에는 `URLEncoder`를 바로 사용하지
  말고 해당 wire contract의 인코딩 규칙을 먼저 확인한다.
- raw bucket/key helper와 이미 인코딩된 pass-through overload를 한 호출에서 섞지
  않는다. 어느 경계에서 인코딩하는지 KDoc과 테스트에 남긴다.
- 특수문자 키 회귀에는 공백, literal `+`, Unicode, 예약 문자, slash를 포함하고,
  가능하면 request field 테스트와 emulator endpoint 테스트를 함께 실행한다.

## 검증 경계

이번 수정은 `aws-kotlin` S3 CopyObject 요청 생성과 그 호출 경로에 한정한다. 실제
AWS 계정에서의 복사와 hosted GitHub CI는 PR exact-head 단계에서 별도로 확인한다.
