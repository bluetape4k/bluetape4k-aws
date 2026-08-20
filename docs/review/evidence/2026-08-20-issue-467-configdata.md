# Issue #467 ConfigData 구현 검증 증적

작성일: 2026-08-21
대상 저장소: `bluetape4k/bluetape4k-aws`
대상 worktree: `feat/issue-467-configdata`
이슈: [#467](https://github.com/bluetape4k/bluetape4k-aws/issues/467)

## 기준과 범위

- 구현 전 기준 HEAD: `3b1d4e2525d665fcc12bcc95edff95df80693efe`
- `origin/develop` merge-base: `91feafa97fd289d0dbd22f59d7a518bb80b8143c`
- 설계: `docs/superpowers/specs/2026-08-20-issue-467-configdata-design.md`
- 계획: `docs/superpowers/plans/2026-08-20-issue-467-configdata-plan.md`
- 독립 review: `docs/review/2026-08-20-issue-467-configdata-spec-review.md`,
  `docs/review/2026-08-20-issue-467-configdata-plan-review.md`
- workflow run: `20260820T120448Z-645238e0`, main lane `main`
- human review gate: `N/A (single-developer lane)`; 독립 설계·계획 review와 CI gate는
  별도로 유지한다.

구현 범위는 `aws-spring-boot` 모듈의 S3, Parameter Store, Secrets Manager
ConfigData resolver/loader와 기존 EnvironmentPostProcessor의 호환·진단 경계다.
ConfigData는 startup 전용이며 lazy refresh는 legacy 경로에만 남긴다.

## TDD 증적

구현 전 RED 로그와 exit를 보존했다. 각 단계의 예상 실패가 실제 non-zero로
재현되었다.

| 단계 | RED exit | 로그 |
| --- | ---: | --- |
| Task 1 parser/resource | `1` | `/tmp/issue-467-configdata-task1-red.log` |
| Task 2 resolver/bridge/classpath | `1` | `/tmp/issue-467-configdata-task2-red.log` |
| Task 3 loader/failure policy | `1` | `/tmp/issue-467-configdata-task3-red.log` |
| Task 4 SPI/Boot precedence | `1` | `/tmp/issue-467-configdata-task4-red.log` |
| Task 5 emulator/docs parity | `1` | `/tmp/issue-467-configdata-task5-red.log` |

Task 5 RED의 문서 경로 실패는 모듈 working directory를 repository root로
가정한 테스트 결함이었고 제품·emulator assertion 실패가 아니었다. 테스트를
repository root 탐색 방식으로 고친 뒤 GREEN을 재실행했다.

## GREEN 및 통합 검증

### ConfigData·legacy 회귀

```text
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests '*AwsConfigData*' \
  --tests '*ConfigDataLegacyPrecedenceTest' \
  --no-configuration-cache
```

결과: **39 passing**. parser의 single decode/control-character 검증, opaque
identity, disabled no-op, optional/required not-found, sanitized failure, SPI
registry, public ABI, Bootstrap holder close, explicit bootstrap customizer,
Web Identity fail-closed, legacy precedence/redaction을 포함한다.

실제 Spring Boot lifecycle도 별도로 검증했다.

- comma-separated `spring.config.import`에서 세 backend binding과 뒤 import의
  override: 통과
- YAML indexed list(`spring.config.import[0..2]`)에서 세 backend binding: 통과
- declaring document보다 imported data가 우선하는 경계: 통과
- profile resolver가 원격 source suffix를 만들지 않는 경계: 통과

### Floci emulator

```text
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests '*AwsConfigDataEmulatorTest' \
  -Dbluetape4k.aws.emulator=floci \
  --no-configuration-cache
```

결과: **3 passing**. 실제 Floci payload로 S3 properties/prefix, Parameter Store
recursive + decryption, Secrets Manager JSON/prefix를 순차 검증했다. Docker 공유
자원 때문에 병렬 실행하지 않았다. Floci가 통과했으므로 LocalStack fallback은
실행하지 않았으며 skipped가 아니라 **N/A (fallback 조건 미충족)**로 분류한다.
실제 AWS 계정·credential을 사용하는 검증은 수행하지 않았다.

### 모듈 전체와 정적 검사

| 명령 | 결과 |
| --- | --- |
| `./gradlew :bluetape4k-aws-spring-boot:test --no-configuration-cache` | **539 passing** |
| `./gradlew :bluetape4k-aws-spring-boot:compileKotlin --no-configuration-cache` | 성공 |
| `./gradlew detekt --no-configuration-cache` | 성공 |
| `ruby scripts/manual/manual_contract_test.rb` | **9 runs, 44 assertions, 0 failures** |
| `git diff --check` | 성공 |
| `AwsConfigDataDocumentationParityTest` | **2 passing** |
| `AwsConfigDataSpiAbiTest` | **1 passing** |

`bluetape-writer` terminology audit의 2개 `snapshot` finding은 변경하지 않은
기존 `aws-spring-boot/README.ko.md` 281, 1051행에서만 재현되었다. 이번 ConfigData
문서 변경 구간에는 새 finding이 없으며, unrelated 기존 용어를 이번 rollback
집합에 섞지 않았다.

`javap`로 세 resolver가 `ConfigDataLocationResolver<AwsConfigDataResource>`를,
세 loader가 `ConfigDataLoader<AwsConfigDataResource>`를 구현하고 Spring Boot가
지원하는 constructor parameter만 노출하는 것을 확인했다. `AwsConfigDataResource`
생성자는 Kotlin synthetic marker만 JVM에 보이며 ABI test에서 private constructor
계약을 확인한다.

SDK 없는 경계는 다음 명령으로 확인했다.

```text
jdeps -v -filter:none -include '.*AwsConfigDataBootstrapBridge.*' <jar>
jdeps -v -filter:none -include '.*AwsConfigDataSupport.*' <jar>
```

두 결과 모두 `java.*`, Kotlin/Spring SPI와 모듈 내부 타입만 직접·재귀적으로
보였고 `software.amazon.awssdk` 의존성은 없었다. SDK typed builder는 별도 adapter와
bootstrap customizer 경계에만 존재한다. Gradle dependency 선언 변경은 없었다.

## 문서·보안·운영 경계

- EN/KO README는 canonical properties 예제와 manual 링크만 제공한다.
- EN/KO runtime manual은 properties와 YAML list, import precedence,
  optional/failure truth table, legacy migration, Floci/LocalStack, Web Identity
  fail-closed, explicit bootstrap customizer 계약을 같은 구조로 설명한다.
- ConfigData와 legacy startup 진단에는 raw bucket/key/path/secret ID/SDK message를
  남기지 않고 backend-scoped SHA-256 opaque identity와 예외 class만 남긴다.
- `optional:`은 backend-specific not-found만 생략한다. 인증·credential·network·
  parse·missing `SecretString`은 optional이어도 startup failure다.
- global/backend disabled는 SDK class guard, client supplier, network보다 먼저
  빈 no-op `ConfigData`를 반환한다.
- client는 bootstrap registry가 소유하며 실제 초기화된 singleton만 close listener에서
  한 번 닫는다. loader는 client를 닫지 않는다.

외부 AWS publisher latency/cleanup telemetry와 실제 heap·throughput 수치는 이
구현의 DoD에 포함하지 않았으며 계획에서 후속 작업으로 남겼다. Floci payload와
로컬 회귀만으로 실제 AWS latency나 운영 heap 상한을 주장하지 않는다.

## Rollback checkpoint

구현 변경은 계획·설계 문서 커밋과 분리한 단일 ConfigData 구현 커밋
(`HEAD`, 최종 보고에서 실제 SHA를 기록)으로 묶었다.
별도 detached rollback worktree에서 이 커밋을 `revert --no-commit`한 상태로
다음 legacy 회귀를 실행했다.

```text
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests '*EnvironmentPostProcessor*' \
  --no-configuration-cache
```

결과: **16 passing**, BUILD SUCCESSFUL. `spring.factories` ConfigData 등록, config
패키지, 세 loader, 테스트·문서 변경을 함께 되돌리는 단위에서 기존 S3, Parameter
Store, Secrets Manager emulator 및 global-disabled 경계를 모두 통과했다. 검증 후
rollback worktree는 원래 커밋으로 복원하여 제거했고 구현 worktree는 보존했다.

## DoD Status

- 계획 1–3: **완료**
- 계획 4 RED→GREEN 구현 및 검증: **완료**
- 계획 5/6 문서·emulator·전체 테스트·detekt·ABI: **완료**
- 구현 commit 및 rollback checkpoint: **완료** (현재 `HEAD`, legacy 16 passing)
- PR/CI/exact-head/merge: **대기** (사용자 merge 승인 별도)
- 최종 상태: **PENDING** — 로컬 구현·검증과 rollback은 GREEN이며, PR delivery gate가 남아 있다.

## Workflow receipt

- run: `20260820T120448Z-645238e0`
- `completion-check`: `complete=true`, `missing_main_verification=false`,
  `missing_components=[]`, `missing_lanes=[]`, `unresolved_failed_lanes=[]`
- `verify`: `event_count=11`, `sequence=11`, checksum
  `6a3b699b5d3434434b8d4a51d432e863554f3843dd3d7394449d6ce008866df9`
