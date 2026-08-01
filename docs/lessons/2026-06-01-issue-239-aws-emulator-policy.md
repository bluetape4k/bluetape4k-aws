# Issue 239 AWS 에뮬레이터 정책

## 배경

과거 `bluetape4k-aws` 테스트는 대부분 LocalStack을 사용했다. 새 Spring Boot emulator
aware 테스트는 이미 Floci를 기본으로 사용하고
`-Dbluetape4k.aws.emulator=floci|localstack|ministack`으로 명시적으로 전환할 수 있다.
Issue #239는 LocalStack 가정에서 명확한 emulator policy로 저장소 전체를 migration하는
작업을 추적한다.

## 결정

저장소 정책은 **Floci 우선**으로 유지한다.

- 새로 만들거나 migration하는 emulator-aware test의 기본값으로 Floci를 우선한다.
- Legacy module이 여전히 의존하는 동안 LocalStack을 명시적 fallback으로 유지한다.
- 같은 AWS SDK smoke matrix가 반복해서 통과할 때까지 MiniStack은 기본 권장이 아니라
  service coverage gap을 평가하는 backend로만 취급한다.

## 결과

이제 root README, 한국어 README, repo-local agent guidance, Spring Boot README에서 같은
정책을 설명한다. Spring Boot README command도 실제 Gradle project path인
`:bluetape4k-aws-spring-boot:test`에 맞췄다.

## 검증

변경한 guidance에서 오래된 LocalStack 기본 표현과 잘못된 `:aws-spring-boot:test`
command를 대상 text search로 검사했다. `git diff
--check` passed. The Spring Boot `*AwsEmulatorTest` smoke 실행은 Floci에서
(`34 passing`) 통과했고 LocalStack에서도 (`34 passing`) 통과했다. MiniStack에서는 SQS FIFO message group id가 `orders`가 아니라 `null`이어서
`33 passing`, `1 failing`으로 실패했다.

## 향후 지침

Service count 주장만으로 저장소 기본값을 MiniStack으로 바꾸지 않는다. 이 저장소에서
Floci, LocalStack, MiniStack이 S3, SQS, SNS, DynamoDB, KMS, Secrets Manager, SSM의 같은
target smoke matrix를 통과한 뒤에만 기본값을 다시 검토한다.
