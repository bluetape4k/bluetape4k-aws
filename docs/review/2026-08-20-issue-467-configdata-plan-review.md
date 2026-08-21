# Issue #467 ConfigData import 구현 계획 검토

## 검토 범위

- 명세: `docs/superpowers/specs/2026-08-20-issue-467-configdata-design.md`
- 계획: `docs/superpowers/plans/2026-08-20-issue-467-configdata-plan.md`
- 명세 SHA-256: `98d980280a04973d756403b5da418dfb242ba96b605035fa9c8cd82a16440ff5`
- 계획 SHA-256: `7283575d9b7b36264c05b978752ad30e80f141947ce56d65005c0f6d29eac3ef`
- 구현 대상: `aws-spring-boot` ConfigData SPI와 기존 세 property-source loader
- 검토 방식: Type A 여섯 관점 독립 검토와 통합 검토
- 실행 경계: read-only 계획 검토. production/test 수정, build/test 실행,
  GitHub mutation은 검토자 관점에서 수행하지 않았다.

## 관점별 판정

| 관점 | 판정 | 확인한 계약 |
|---|---|---|
| API·ABI·호환성 | PASS | public `AwsConfigDataResource` carrier, JVM-private constructor/internal factory, 세 resolver/loader의 정확한 `ConfigData…<AwsConfigDataResource>` generic, Boot-supported constructor, nullable `ConfigData?` 반환, `AwsConfigDataSpiAbiTest`를 고정했다. |
| 운영·수명주기 | PASS | SDK-free bridge와 delayed service adapter, disabled/classpath guard, STS guard, web-identity fail-closed, `FailurePolicy` throw-only core, bootstrap singleton 및 initialized-only close를 고정했다. |
| 사용자·마이그레이션·테스트 | PASS | canonical properties/YAML/profile/precedence 계약, legacy EPP winner, `bluetape4k-assertions` matcher, `Base58.randomString(16)`의 식별자 한정, 단계별 RED exit/log 증적과 EN/KO parity test를 고정했다. |
| 성능·자원 | PASS | resolver remote I/O 지연, backend별 client singleton, 미사용 supplier를 shutdown에서 깨우지 않는 holder, ConfigData에 lazy refresh를 붙이지 않는 경계를 고정했다. 실제 latency/heap/throughput은 후속 이슈로 남겼다. |
| 보안·진단 | PASS | optional/source/query를 포함한 canonical SHA-256 opaque identity, raw identifier/value·SDK cause message 비노출, sanitized failure/log assertion, STS/provider 설정 오류의 fail-closed를 고정했다. |
| 안정성·회귀·운영 전달 | PASS | backend별 not-found truth table, existing EPP 회귀, Floci 우선/인프라 또는 API gap일 때만 LocalStack fallback, CI required-check 집계, exact-head receipt, rollback 집합을 고정했다. |

## 통합 판정

계획 게이트는 **PASS**다. P0/P1 blocker와 계획 자체의 미해결 P2/P3는 없다.
다음 구현 증적은 계획상 후속 조건이며 아직 완료를 의미하지 않는다.

- Task 1–5의 RED non-zero exit와 stdout/stderr artifact를 GREEN 구현 전에 남긴다.
- Floci 제품 assertion 실패는 즉시 BLOCK하고 LocalStack으로 숨기지 않는다.
- absent SDK filtered-ClassLoader, `SpringFactoriesLoader`, `jdeps`/ABI,
  bootstrap close exactly-once, EN/KO parity, legacy EPP, rollback rerun을 fresh
  evidence로 남긴다.
- 구현 PR의 human review gate는 1인 개발자 정책상 `N/A`이며, 독립 plan review,
  CI, exact-head/metadata/mergeability read-back은 필수다.

## 검토 중 반영한 정합성 보정

- resource equality와 opaque identity가 같은 canonical serializer를 사용하도록
  optional/source/query 전체를 hash 입력에 포함했다.
- broad legacy catch와 ConfigData strict 오류를 `AwsConfigDataFailurePolicy`
  및 fetch/parse throw-only core로 분리했다.
- Spring SPI descriptor에서 AWS SDK 참조를 제거하고, classpath guard 이후에만
  service adapter/provider를 지연 로드하도록 했다.
- Bootstrap close listener가 `get(clientType)`로 미사용 supplier를 깨우지 않고
  initialized holder만 닫도록 명시했다.
- 실제 assertion 함수명 `shouldBeLessOrEqualTo`와 deterministic fixture 범위를
  저장소 패턴에 맞췄다.

## 다음 게이트

사용자에게 이 계획의 실행 승인을 받은 뒤 계획·명세 clarification·review를
분리된 Lore commit으로 고정하고, Task 1 RED부터 구현을 시작한다. 계획 승인만으로
PR merge를 수행하지 않으며, merge는 exact-head CI와 별도 사용자 승인 이후에만
진행한다.
