# Issue #463 설계 spec review

대상 문서: `docs/superpowers/specs/2026-08-23-issue-463-s3-resource-loader-design.md`

검토 단계: Type A Step 2-R

검토 기준: Issue #463 live body, `S3Resource.kt`, `S3ObjectLocation.kt`,
`S3AutoConfiguration.kt`, auto-configuration imports, `aws-spring-boot/build.gradle.kts`,
기존 `ApplicationContextRunner`·Floci 테스트 구조, Spring
`ProtocolResolver`·`ResourcePatternResolver`·`PathMatchingResourcePatternResolver`,
AWS SDK v2 `ListObjectsV2Iterable` 공식 문서.

## 독립 관점 결과

| 관점 | 우선순위 | 근거와 판정 | 처분 | 재검증 |
|---|---|---|---|---|
| Architecture | P0/P1 없음 | 공개 `addProtocolResolver` extension point, literal single bucket, lazy `S3Client`, concrete/qualifier pattern injection, context/client lifecycle이 §4.1–§4.5에 고정됐다. | PASS | Architecture lane 수정 후 재검토 PASS |
| Performance | P0/P1 없음, P2 관찰 | prefix가 좁아도 결과 배열과 natural sort는 listing 크기에 비례한다. root-level empty prefix는 거부하고 caller가 non-empty prefix를 선택하므로 cross-bucket·무제한 root scan은 차단된다. | P2는 구현 plan의 page 규모·정렬 검증으로 추적한다. 새 cache/limit은 public contract를 넓히므로 현재 추가하지 않는다. | 주 세션 통합 |
| Stability | P0/P1 없음 | paginator 중간 실패의 partial result 금지, retry 없음, sync client timeout 위임, caller-owned stream, context 종료 후 resource 재사용 금지가 §4.4와 §7에 있다. | PASS | Build/CI/tests lane 최종 PASS |
| Security | P0/P1 없음, P2 관찰 | literal bucket·userinfo·port·wildcard authority를 거부하고 permission/list 오류를 삼키지 않으며 secret을 메시지에 넣지 않는다. percent escape provenance도 보존한다. | P2는 parser negative test와 zero-AWS-call 증적으로 추적한다. | 주 세션 통합 |
| Operator/Ops | P0/P1 없음, P2 관찰 | 새 retry/cache/credential surface가 없고 기존 endpoint·region·credentials·client timeout을 재사용한다. resolver 오류는 bucket/prefix와 원인을 보존하되 secret은 제외한다. | 별도 운영 endpoint·metric은 범위 밖으로 명시하고, 구현 plan에서 오류·rollback 증거를 남긴다. | 주 세션 통합 |
| Developer/API | P0/P1 없음 | `*`, `?`, `**`만 Ant contract로 지원하고 `[]`는 negative case로 고정했다. internal parser signature, auto-config ordering/class guard, existing 404 semantics, locale-independent sort를 실제 API와 정렬했다. | PASS | Developer/API lane 최종 PASS |
| User/caller | P0/P1 없음, P2 관찰 | exact는 `@Value`/`ApplicationContext.getResource`, pattern은 concrete type 또는 `@Qualifier("s3ResourcePatternResolver")` 주입으로 분리하고 `ApplicationContext.getResources` pattern interception은 명시적으로 제외했다. | README·manual의 exact/pattern 예시와 unsupported 경계를 구현 단계에서 동기화한다. | 주 세션 통합 |

## 통합 검토

초기 Architecture lane에서 확인된 P1 세 건(조기 client 생성, unrelated
resolver backoff, delegate context 유실)과 P2 두 건(existing 404 semantics,
resource lifecycle)을 spec에 반영하고 해당 lane을 재실행했다. Developer/API
lane에서는 Spring `AntPathMatcher`가 `[]` character class를 지원하지 않는
불일치, empty prefix 문서 충돌, escaped wildcard tokenization, auto-config
ordering, parser signature를 지적했다. Build/CI/tests lane에서는 Floci 전용
test class, `-PskipAwsEmulatorTests=true`, `--max-workers=1`, paginator
중간 실패·no-retry·lifecycle·POM/Detekt/README parity 증거를 요구했고 모두
spec에 반영한 뒤 재검토했다.

현재 통합 결과는 다음과 같다.

| 결과 | 값 |
|---|---:|
| P0 | 0 |
| P1 | 0 |
| P2 | 3 (성능 규모, 운영 관측, caller 문서 경계; 모두 구현 plan 추적) |
| P3 | 0 |

P2는 승인된 단일 bucket·구현 전 설계 범위를 바꾸지 않는 항목으로, 위 표의
처분과 구현 plan의 검증 명령에 연결했다. 새 P0/P1 또는 승인 범위를 바꾸는
수정은 발견되지 않았다.

## Writer gate

- SPW-01 PASS: 한국어 Type A 설계 review이며, Issue #463, local anchors,
  Spring/AWS 공식 URL, exact identifiers와 불확실성 경계를 source ledger에
  기록했다.
- SPW-02 PASS: 검토 범위, 6개 관점, severity, evidence, disposition,
  rerun, 통합 verdict를 모두 포함했다.
- SPW-03 PASS: 한국어 기술 문체를 사용하고 API·명령·URL·정확한 오류 의미를
  보존했다. `[]`는 지원 표기가 아니라 negative case로 구분했다.
- SPW-04 PASS: spec의 parser, auto-config, paginator, lifecycle, docs/Floci
  acceptance와 본 review 처분을 대조했고, P1 수정 후 재검토 결과를 반영했다.
- SPW-05 PASS: 최종 Markdown을 read-back하고 heading/table/code token과
  목록 흐름을 확인했다. 남은 P2는 각각 구현 plan 추적 대상으로 명시했다.

## Verdict

**PASS — P0=0, P1=0.** 승인된 설계는 구현 plan 단계로 진행할 수 있다.
다만 이 review artifact와 spec을 사용자가 검토·승인하기 전에는
`writing-plans`를 호출하거나 source code를 수정하지 않는다.
