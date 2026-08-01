# Issue #145 S3 `listAllObjects` 흐름

- 날짜: 2026-05-21
- 범위: `aws` S3 coroutine 확장
- 이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/145

## 배경

`S3AsyncClient` coroutine helper는 object get/put/delete 형태의 작업을 제공했지만
페이지를 순회하는 목록 helper는 없었다. `ListObjectsV2` 요청 하나만 사용하는 호출자는
아무 경고 없이 첫 S3 page에서 멈출 수 있었다.

## 결정

`S3AsyncClient.listAllObjects(bucket, prefix)`를 cold `Flow<S3Object>`로 추가한다.
Flow를 collect할 때만 `ListObjectsV2`를 실행하고, S3가 결과가 더 이상 잘리지 않았다고
보고할 때까지 `nextContinuationToken`을 따라간다.

## 결과

이제 핵심 `aws` 모듈은 Spring template API에 의존하지 않고 상위 계층에 필요한 공통
pagination primitive를 제공한다. 모듈 README 예제에는 upload/download와 페이지 목록
조회가 모두 포함된다.

## 검증

- `./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.s3.S3AsyncClientListAllObjectsTest'`
- `./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.s3.S3AsyncClientCoroutinesExtensionsTest' --tests 'io.bluetape4k.aws.s3.S3AsyncClientListAllObjectsTest'`에서 테스트 14개가 통과했다.

## 향후 참고 사항

S3 object 목록 조회에는 중앙 Flow helper를 우선한다. 호출자에게 page 수준 metadata가
필요한 경우가 아니라면 continuation-token loop를 호출자마다 중복하지 않는다.
