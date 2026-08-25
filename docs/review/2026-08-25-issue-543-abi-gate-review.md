# #543 legacy ABI compatibility gate 7-Tier code review

## 검토 범위

- 기준 base: `develop` at `44da91ff` (#542 merge)
- 변경: `build.gradle.kts`, `aws-spring-boot/build.gradle.kts`,
  `.github/workflows/ci.yml`, `docs/compatibility/legacy-abi.md`
- 이슈: #543
- 검토 방식: touched build/CI surface와 실행 증거를 기준으로 한 source-read-only
  7-Tier 통합 검토

## 7-Tier 결과

| Tier | 관점 | 결과 | 증거 |
|---|---|---|---|
| 1 | 보안/공급망 | PASS | compatibility job은 공개 ABI와 compile fixture만 검사하고, AWS service SDK를 새 runtime dependency로 추가하지 않는다. publication guard와 optional SDK isolation test를 함께 실행한다. |
| 2 | 운영/CI | PASS | `compatibilityCheck`를 root `check`와 PR 전용 `Compatibility / legacy ABI` job에 연결했다. 관련 root/build/fixture/test 변경 path가 gate를 선택한다. |
| 3 | 구조/API·ABI | PASS | `javap -public` signature baseline은 source/bytecode hash와 분리되고, legacy SQS/SNS consumer fixture compile이 aggregate에 포함된다. implementation hash는 별도 `implementationBaselineCheck`로 명시 실행한다. |
| 4 | Kotlin/Gradle 패턴 | PASS | Gradle task 입력·출력 property를 선언하고, Spring Boot compatibility test는 JUnit Platform과 module test runtime classpath를 사용한다. 새 production Kotlin API는 추가하지 않았다. |
| 5 | 테스트/회귀 | PASS | SQS/S3 ABI 2개, legacy consumer fixture 6개, `FilteredClassLoader` optional SDK compatibility test 56개를 fresh 실행했다. |
| 6 | 성능/안정성 | PASS | compatibility test는 기존 non-emulator auto-configuration test의 제한된 집합만 실행하고, implementation audit는 compatibility gate와 분리하여 PR 경로의 비용과 false positive를 줄였다. |
| 7 | 문서/릴리스 | PASS | report 경로, baseline 의미, 의도적 갱신 절차, 실패 진단, artifact 보존 정책을 Korean 문서와 PR DoD에 기록한다. |

## 판정

- P0 = 0
- P1 = 0
- 결정: PASS. PR 생성과 exact-head CI 검증을 진행할 수 있다.

## 검증 증거

- RED: 구현 전 `compatibilityCheck` task-not-found (`/tmp/issue-543-red.log`).
- GREEN: `./gradlew compatibilityCheck --no-daemon --no-configuration-cache
  --no-build-cache` 통과, compatibility test 56개 포함.
- 구현 baseline 분리: `./gradlew implementationBaselineCheck
  --no-daemon --no-configuration-cache --no-build-cache` 통과.
- root check: `./gradlew check -PskipAwsEmulatorTests=true
  --no-daemon --no-configuration-cache --console=plain` 통과.
- task graph: `./gradlew check --dry-run --no-daemon
  --no-configuration-cache`에서 `verifySqsExtendedLegacyAbi`,
  `verifyS3ExtendedLegacyAbi`, `aws-spring-boot:compatibilityTest`,
  `compatibilityCheck` 확인.
- static: `actionlint .github/workflows/ci.yml`, Ruby YAML parse,
  `git diff --check` 통과.

## 알려진 경계

- 전체 Floci emulator matrix는 이 build/CI gate의 범위가 아니다. 기존 emulator
  fixture 안정성은 별도 후속 이슈로 유지한다.
- implementation baseline은 의도적으로 PR `compatibilityCheck`의 public ABI
  판정에서 제외했다. compiler/JVM drift 또는 binary 변경을 감사해야 할 때
  `implementationBaselineCheck`를 별도로 실행한다.

## DoD Status

- 상태: PR 생성 전 review 통과
- P0/P1: 0/0
- 완료: compatibility gate, ABI/hash 분리, optional SDK isolation,
  legacy fixture, CI path, 문서와 검증 evidence
- 미완료: Lore commit, push, PR exact-head CI, merge
