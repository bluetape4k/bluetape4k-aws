# AWS Spring Boot assertion 정리

## 배경

PR #62 이후 후속 정리에서 `aws-spring-boot/src/test/kotlin`에 남은 AssertJ 사용을
제거하고 변경한 테스트를 `bluetape4k-assertions`에 맞췄다.

- 범위: `aws-spring-boot/src/test/kotlin`
- 변경한 파일: 9개
- 검토 기반 보정 반복: 1회
- 수정한 검토 발견 사항: P0=0, P1=0, P2=1

## 결정

변경한 테스트에서는 AssertJ와 저장소 native assertion을 섞지 않고
`bluetape4k-assertions`를 일관되게 사용해야 한다.

`ApplicationContextRunner` 테스트에서는 `getBeansOfType(...).size`,
`startupFailure.shouldBeNull()`, infix 동등성 검사로 bean 존재 여부를 더 명확히 표현한다.

## 결과

- S3/SNS/SQS auto-configuration 테스트에서 AssertJ를 제거했다.
- S3/SNS/SQS LocalStack 통합 테스트에서 AssertJ를 제거했다.
- Parameter Store 및 Secrets Manager environment 후처리기 테스트에서 AssertJ를 제거했다.
- `shouldBeEqualTo`, `shouldContain`, `shouldEndWith`, `shouldHaveSize`, `shouldBeEmpty`, `shouldNotBeBlank`, `assertFailsWith`로 assertion 의도를 명확히 유지했다.
- `shouldContain`이 원래 `endsWith` assertion을 약화한다는 검토 결과에 따라 `shouldEndWith`로 SNS topic ARN suffix 검사를 복원했다.
- 이제 `aws-spring-boot/src/test/kotlin`에는 AssertJ 사용이 남아 있지 않다.

## 검증

- `./gradlew :aws-spring-boot:compileTestKotlin`
- `./gradlew :aws-spring-boot:test`
- `git diff --check`
- Claude CLI 검토: 첫 검사에서 P0=0, P1=0, P2=1이었고 수정 후 다시 확인했다.

결과: `aws-spring-boot` 테스트 68개가 통과했다.

## 향후 보호 장치

bluetape4k Kotlin 테스트를 변경할 때는 편의를 위해 assertion style을 섞어 두지 말고,
변경한 assertion block을 즉시 `bluetape4k-assertions`로 옮긴다.
