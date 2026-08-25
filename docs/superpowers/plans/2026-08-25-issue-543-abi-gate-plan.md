# #543 legacy ABI compatibility gate 실행 계획

## 목표

수동 실행에 머물던 SQS/S3 legacy ABI와 Spring Boot consumer fixture 검증을
`compatibilityCheck` 단일 진입점으로 묶고 root `check` 및 PR CI에서 항상
실행한다. 공개 signature baseline은 유지하되 구현 source/bytecode hash와
분리된 변경 절차를 문서화한다.

## 범위

- `build.gradle.kts`
  - `compatibilityCheck` aggregate task 추가
  - SQS/S3 public ABI fixture 검증과 Spring SQS/SNS legacy consumer compile 연결
  - source/bytecode implementation baseline audit를 public ABI gate와 분리
  - root `check` 의존성 연결
  - 사람이 읽을 수 있는 JSON report 생성
- `.github/workflows/ci.yml`
  - compatibility path filter 추가
  - non-skipped `Compatibility / legacy ABI` job 추가
  - ABI report artifact 업로드
- `aws-spring-boot/build.gradle.kts`
  - 실제 `FilteredClassLoader` 기반 optional SDK isolation compatibility test task 추가
- `docs/compatibility/legacy-abi.md`
  - baseline 의미, 갱신 절차, 실패 진단, optional SDK isolation 경계 기록

## 수용 기준

1. 구현 전 `./gradlew compatibilityCheck`가 task-not-found RED를 재현한다.
2. 구현 후 `./gradlew compatibilityCheck --no-daemon --no-configuration-cache`
   가 SQS/S3 ABI와 legacy consumer compile을 통과한다.
3. `./gradlew check`의 task graph에 `compatibilityCheck`가 포함된다.
4. PR CI가 root build와 독립된 compatibility job을 실행하며 ABI JSON report를
   artifact로 보존한다.
5. optional AWS SDK isolation은 `aws-spring-boot:compatibilityTest`가 실제
   FilteredClassLoader 테스트를 실행하고 publication/classpath guard가
   runtime leak을 검사하며, compatibility report에 그 결과를 명시한다.
6. public ABI gate는 `javap -public` baseline만 강제하고 source/bytecode
   hash는 `implementationBaselineCheck`로 분리한다.
7. baseline 갱신은 의도적인 public API 변경, 새 fixture, javap 검토, 7-Tier
   review와 함께 수행하며 단순히 실패를 숨기기 위한 hash 갱신은 금지한다.

## 검증 순서

1. RED 기록
2. Gradle aggregate task 및 report 구현
3. CI job/path filter 구현
4. compatibilityCheck targeted GREEN
5. `:bluetape4k-aws-spring-boot:compatibilityTest`, non-emulator module test
   및 `check` task graph 검증
6. `implementationBaselineCheck`, diff/static/Kotlin pattern/7-Tier review
7. Lore commit, push, Korean PR 생성, exact-head CI readback

## 제외 범위

- public API 변경과 fixture baseline 갱신
- AWS credential smoke, release, tag, publication
- 기존 Floci fixture 자체의 lifecycle 수정

## DoD Status

- 상태: 구현 및 로컬 검증 완료
- 완료: 목표, 파일 경계, aggregate 구성, CI gate, ABI/hash 분리, optional SDK
  isolation, 7-Tier review, targeted/root check evidence
- 미완료: Lore commit, push, PR exact-head CI, merge
