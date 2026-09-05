# Pagination 종료 조건은 token 존재가 아니라 진행을 검증한다

## 배경

[`forceDeleteBucket`](https://github.com/bluetape4k/bluetape4k-aws/issues/623)은
versioned S3 bucket의 객체 version과 delete marker를 모두 지운 뒤 bucket을 삭제한다.
`ListObjectVersions`가 truncated response를 반환하면 다음 marker 쌍으로 계속 조회하지만,
기존 구현은 marker가 하나라도 있는지만 검사했다.

## 원인과 실패 증거

동일한 `(keyMarker, versionIdMarker)`가 다시 오거나 A→B→A로 순환해도 marker 자체는
존재한다. 따라서 존재 여부 검사만으로는 pagination 종료를 보장하지 못한다. fake S3
response로 두 경우를 주입하자 기존 코드는 다음 요청까지 진행했고, truncated response에
marker가 없는 테스트는 오류 메시지에 bucket 이름을 포함했다.

Kinesis `ListShards`에는 이미 반복 token 집합과 `maxListShardsPages` 상한이 있었다.
두 구현이 서로 다른 방식으로 진행 여부를 검사하면 같은 결함을 다시 만들 수 있으므로,
서비스 타입과 오류 정책을 제외한 공통 부분을 분리했다.

## 결정

- 공통 `PaginationGuard`는 token 반복과 page 상한만 관리한다. AWS SDK 타입, 삭제 작업,
  로그와 서비스별 예외는 포함하지 않는다.
- S3 marker는 key와 version ID를 한 쌍으로 비교한다. key가 같아도 version ID가 다르면
  정상 진행으로 처리한다.
- S3 version 정리는 한 번에 최대 10,000 page를 처리한다. guard가 보관하는 token 수도
  이 상한을 넘지 않는다.
- S3의 missing marker, repeated marker, page limit 오류에는 bucket 이름과 원본 marker를
  넣지 않는다.
- Kinesis는 기존 `KinesisShardGraphException`, 반복 token 메시지와
  `maxListShardsPages` 의미를 유지한다.
- S3와 Kinesis의 반복문은 각 원격 호출 전에 `ensureActive()`로 취소를 확인한다.

## 결과

동일 marker와 A→B→A 순환은 각각 두 번과 세 번의 `ListObjectVersions` 호출 안에
실패하며 이후 `deleteBucket`을 호출하지 않는다. marker가 없는 truncated response도 즉시
실패한다. 정상 다중 page에서는 같은 key의 서로 다른 version marker, 객체 version과
delete marker 삭제를 모두 유지한다.

## 검증

- RED: S3 동일 marker, A→B→A, marker 누락 회귀 3개가 기존 구현에서 실패했다.
- targeted: `PaginationGuardTest`, `S3ClientBucketMockTest`,
  `KinesisConsumerFlowUnitTest` — 19개 통과
- 전체 모듈: `:bluetape4k-aws-kotlin:cleanTest :bluetape4k-aws-kotlin:test
  --no-build-cache` — 752개 통과, 13개 pending
- 정적 분석: `:bluetape4k-aws-kotlin:detekt` — 성공
- Floci S3: `:bluetape4k-aws-kotlin:test --tests
  'io.bluetape4k.aws.kotlin.s3.*' -Dbluetape4k.aws.emulator=floci --rerun-tasks
  --no-build-cache` — 99개 통과
- 변경 경계: `git diff --check` — 성공

hosted GitHub CI는 PR exact-head 단계에서 별도로 확인한다.

## 향후 지침

- pagination 방어는 `nextToken != null`만 확인하지 말고 반복 token과 전체 page 상한을
  함께 검증한다.
- 복합 marker는 각 필드를 따로 비교하지 말고 다음 요청을 식별하는 전체 tuple을 하나의
  token으로 취급한다.
- guard는 진행 상태만 관리하고 서비스별 예외 타입, 민감한 식별자 노출 정책과 재시도
  책임은 호출 경계에 남긴다.
- pagination 실패 테스트는 유한 호출 횟수와 후속 파괴 작업 미실행을 함께 검증한다.
