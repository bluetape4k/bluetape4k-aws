# #549 Ktor validation 7-Tier review

## 결론

`AwsExposedPluginConfig`의 단순 timeout positive validation을
`io.bluetape4k.support.requireGt`로 교체했다. `assertFailsWith`와
`shouldContain`을 사용해 0/음수 실패 경계와 parameter 진단을 고정했으며,
Ktor Exposed plugin의 start/stop lifecycle 테스트는 변경하지 않고 전체 회귀로
검증했다. P0/P1 발견 사항은 0건이다.

## 변경 근거

- [AwsExposedPluginConfig.kt](../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/exposed/AwsExposedPluginConfig.kt#L107-L125)
  에서 `startTimeout`과 `stopTimeout`을
  `requireGt(Duration.ZERO, name)`으로 검증한다.
- DSL 방식 혼용과 중복 이름 거부는 다중 상태 관계형 불변식이므로 기존
  명시적 `require`를 유지했다.
- [AwsExposedPluginTest.kt](../../aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/exposed/AwsExposedPluginTest.kt#L275-L306)
  에서 `io.bluetape4k.assertions.assertFailsWith`로 0/음수 경계 실패,
  `shouldContain`으로 `startTimeout`/`stopTimeout` 진단, 1ms 유효 경계를
  검증한다.
- 기존 start timeout, stop timeout, registry close-once, suspend transaction
  테스트가 plugin lifecycle과 리소스 수명주기 보존을 검증한다.

## 7-Tier 결과

| Tier | 판정 | 확인 내용 |
|---|---|---|
| 1. 요구사항/범위 | PASS | #549의 두 timeout 단항 검증만 수정하고 관계형 DSL 계약은 보존했다. |
| 2. Kotlin 패턴 | PASS | `io.bluetape4k.support.requireGt`와 기존 coroutine/Ktor 테스트 도구를 재사용했다. |
| 3. bluetape4k assertions | PASS | 대상 테스트의 예외 검증은 `io.bluetape4k.assertions.assertFailsWith`만 사용하며 raw JUnit/kotlin.test 예외 assertion이 없다. |
| 4. API/호환성 | PASS | 공개 API와 `IllegalArgumentException` 계약, timeout 값 전달을 유지했다. |
| 5. 동시성/수명주기 | PASS | `runSuspendIO`, start/stop timeout, registry close-once 회귀를 전체 모듈 테스트로 통과했다. |
| 6. 테스트/정적분석 | PASS | targeted 13/13, `aws-ktor` 250/250, detekt 성공, raw scan 및 `git diff --check` 성공. |
| 7. 운영/문서 | PASS | 계획과 본 review를 Korean 문서로 기록하고 stacked PR DoD에 반영한다. |

## 검증 증거

- `./gradlew :bluetape4k-aws-ktor:test --tests "io.bluetape4k.aws.ktor.exposed.AwsExposedPluginTest"`
  — 13/13 passing.
- `./gradlew :bluetape4k-aws-ktor:test` — 250/250 passing, 49.9s.
- `./gradlew :bluetape4k-aws-ktor:detekt` — BUILD SUCCESSFUL.
- 대상 production raw positive validation scan — clean.
- 대상 test raw `assertThrows`/`kotlin.test.assertFailsWith` scan — clean.
- `git diff --check` — clean.

## 남은 위험

- AWS 실제 서비스 credential smoke는 이 단순 설정 validation 변경의 범위가
  아니며 실행하지 않았다. Ktor module emulator/통합 회귀는 hosted CI에서
  exact head를 확인한다.

## DoD Status

- 상태: 구현·로컬 검증 완료, PR/hosted CI/merge 대기
- 완료: bluetape4k helper 재사용, `assertFailsWith` 경계 회귀, lifecycle 회귀,
  7-Tier review, 계획 문서
- 남은 항목: PR 생성, metadata/hosted CI exact-head 확인, merge 후 develop 동기화
