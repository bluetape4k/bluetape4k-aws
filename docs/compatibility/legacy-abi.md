# Legacy ABI 호환성 게이트

## 목적

`bluetape4k-aws`의 공개 Spring Boot SQS/S3 API가 기존 consumer와 계속
호환되는지 확인한다. 게이트는 공개 signature baseline, legacy consumer
compile fixture, optional AWS SDK classpath 경계를 각각 확인한다.

## 실행

```bash
./gradlew compatibilityCheck --no-daemon --no-configuration-cache
./gradlew check --no-daemon --no-configuration-cache
```

`compatibilityCheck`는 다음을 하나의 진입점으로 실행한다.

- `verifySqsExtendedLegacyAbi`
- `verifyS3ExtendedLegacyAbi`
- `:bluetape4k-aws-spring-boot:compatibilityTest`의 optional SDK isolation 테스트
- SQS operations/properties/annotation/interceptor legacy consumer fixture
- SQS batch consumer fixture
- SNS operations legacy consumer fixture

결과 보고서는 `build/reports/compatibility/compatibility-check.json`에
생성되고, 개별 ABI task는 `build/reports/abi/issue-455/` 아래에 JSON을
생성한다.

구현 source/bytecode hash를 별도로 감사하려면 다음 명시적 task를 실행한다.

```bash
./gradlew implementationBaselineCheck --no-daemon --no-configuration-cache
```

## Baseline 의미

`src/abi-fixtures/{sqs,s3}-pre-change/`의 `javap.txt`는 공개 signature
baseline이다. `source.sha256`와 `bytecode.sha256`는 fixture provenance와
예상 binary 상태를 추적하는 구현 baseline이다. `compatibilityCheck`는
`javap -public` 결과만 공개 ABI gate로 판정하고, hash는 report에 관찰값으로
기록한다. hash 일치까지 강제하는 별도 `implementationBaselineCheck`는
compiler/JVM drift 또는 구현 binary 변경을 독립적으로 감사할 때만 실행한다.

현재 `aws-spring-boot`의 optional AWS SDK isolation은
`aws-spring-boot:compatibilityTest`가 실제 `FilteredClassLoader` 테스트를
실행하고, `verifyAwsConsumerFixturePublication`가 publication/classpath
leak을 검사한다. 따라서 ABI aggregate report는 이 경계를 명시하고, SDK
의존성을 runtime publication으로 승격시키지 않는다.

## 의도적인 API 변경 시 갱신 절차

1. 기존 baseline으로 `compatibilityCheck`를 실행하고 실패 signature를
   보존한다.
2. 변경 이유와 source/binary compatibility 영향을 설계·7-Tier review에
   기록한다.
3. 승인된 pre-change 또는 release fixture에서 새 `javap.txt`, source hash,
   bytecode hash를 재생성한다.
4. baseline 파일과 public API diff를 같은 변경에 포함하고 legacy consumer
   compile을 다시 실행한다.
5. PR body와 review artifact에 갱신 이유, fixture provenance, report 경로를
   기록한다.

실패를 숨기기 위해 현재 빌드에서 임의로 hash를 덮어쓰거나 fixture를
삭제해서는 안 된다.

## 실패 진단

- `public signature changed`: public API diff와 `javap.txt`를 함께 검토한다.
- `implementation baseline changed`: `implementationBaselineCheck`의 source/
  bytecode 차이가 compiler/JVM target drift인지 실제 binary break인지 구분한다.
- consumer compile 실패: 기존 consumer가 사용하는 source/API와 compileOnly
  dependency 경계를 확인한다.
- optional SDK isolation 실패: `aws-spring-boot:compatibilityTest`의 실패한
  `FilteredClassLoader` 테스트와 생성된 POM/module metadata에서 runtime leak
  여부를 확인한다.

## DoD Status

- 상태: #543 compatibility gate 문서화 완료
- 완료: 실행 명령, aggregate 범위, report 경로, baseline 정책, 갱신 절차,
  optional SDK isolation 경계
- 미완료: 구현 branch의 targeted/module/CI 검증은 PR DoD에서 갱신
