# Issue #147 S3 versioned bucket 강제 삭제

- 날짜: 2026-05-21
- 범위: `aws-kotlin` S3 bucket 정리 helper
- 이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/147

## 배경

`S3Client.forceDeleteBucket()`은 `listObjectsV2`가 반환한 현재 key만 삭제했다. 이전
object version과 delete marker가 bucket에 남을 수 있으므로 versioning이 활성화되었거나
중지된 bucket에는 충분하지 않았다.

## 결정

Version 정보를 포함한 `ObjectIdentifier` 값을 사용해 `listObjectVersions`로 object
version과 delete marker를 먼저 삭제한다. 그런 다음 `deleteBucket` 전에 기존 현재
object 정리 loop를 실행한다.

## 결과

이제 helper는 공개 계약을 좁히지 않으면서 version을 고려해 정리한다. 회귀 테스트는
LocalStack 대신 MockK를 사용하므로 계약이 emulator의 versioning 지원에 의존하지 않는다.

## 검증

- `./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.s3.S3ClientBucketMockTest'`
- `./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.s3.S3ClientExtensionsTest'`
- `./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.s3.*'`에서 테스트 83개가 통과했다.

## 향후 참고 사항

S3 bucket을 삭제할 때 `listObjectsV2`는 현재 object 정리용으로만 사용한다. Version이
있는 대상을 정리하려면 `listObjectVersions`와 delete-marker 처리가 필요하다.
