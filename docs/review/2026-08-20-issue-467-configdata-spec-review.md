# Issue #467 ConfigData import 설계 검토

## 검토 범위

- 대상: `docs/superpowers/specs/2026-08-20-issue-467-configdata-design.md`
- 대상 commit: `23dc877` 및 identity/SPI clarification 후속 변경
- 대상 문서 SHA-256: `98d980280a04973d756403b5da418dfb242ba96b605035fa9c8cd82a16440ff5`
- 검토 방식: 설계 단계 read-only 검토. production/test 실행, GitHub mutation,
  AWS 실계정 호출은 하지 않았다.
- 독립 근거: Issue #467 live read-back, Spring Boot 4.0.6 cached source,
  Spring Cloud AWS 공식 resolver/loader source 링크, 기존 세 loader와 EPP
  source read-back.

## 관점별 판정

| 관점 | 판정 | 확인한 계약과 근거 |
|---|---|---|
| API·호환성 | PASS | `internal load(client, source, failurePolicy)`와 legacy facade 분리(`design.md:184-202`), public SPI carrier와 구체 generic/constructor 경계(`:176-187`), resource equality/toString identity(`:171-176`), concrete test/registration 이름(`:292-310`)을 고정했다. |
| 운영·수명주기 | PASS | backend별 not-found 집합, `registerIfAbsent`·`addCloseListener` exactly-once, web identity fail-closed, loader non-owner를 명시했다(`design.md:234-253`). |
| 사용자·마이그레이션 | PASS | 표준 comma-separated properties와 YAML list(`:110-123`), profile 문서 import와 remote profile suffix 비지원(`:147-151`), later-wins/imported-data precedence와 legacy EPP winner(`:257-270`), EN/KO exact paths(`:316-328`)를 고정했다. |
| 성능·자원 | PASS | 동일 backend client singleton 공유와 disabled 경로 client/network 0회(`:163-202`), close ownership(`:246-253`)을 설계했다. 실측 heap/throughput은 구현 후 증적으로 남긴다. |
| 보안·진단 | PASS | optional/source/query를 포함한 canonical hash input/encoding과 opaque identity(`:229-245`), SDK 원본 cause 비연결, raw identifier/value 비노출, log/exception assertion(`:229-253`)을 명시했다. |
| 안정성·회귀 | PASS | S3/SSM/Secrets not-found 분류와 403·credential·parse·network·no-SecretString 실패 경계(`:234-253`), EPP/lazy refresh 보존(`:11-15,43-46,257-282`)을 고정했다. |

## 통합 검토

### 결정

설계 게이트는 **PASS**다. P0/P1 blocker는 최신 문서에서 해소됐다. 기존
검토에서 발견된 indexed import 예제, broad exception swallowing, raw
identifier logging, Bootstrap client ownership, profile/precedence 및 loader
주입 경계는 모두 문서의 구체적 API·분류·테스트 항목으로 연결됐다.

### P2 후속 증적

- 구현 후 `AwsConfigDataLocationResolverTest`, `AwsConfigDataLoaderTest`,
  `AwsConfigDataImportApplicationTest`, `AwsConfigDataFactoryRegistrationTest`
  와 기존 EPP 회귀 테스트를 실행해야 한다.
- Floci 우선 및 LocalStack fallback 실행 결과는
  `docs/review/evidence/2026-08-20-issue-467-configdata.md`에 남긴다.
- absent-SDK `ClassLoader` fixture, Bootstrap close exactly-once, EN/KO manual
  parity, rollback rerun은 구현 계획의 검증 단계에서 fresh evidence를 만든다.
- 실제 AWS publisher latency/비용과 heap·throughput 실측은 이번 이슈의 설계
  범위 밖이며 별도 후속 증적으로 유지한다.

## 작성 품질 게이트

- SPW-01 범위·독자·근거: PASS
- SPW-02 설계·대안·오류·호환성·DoD·rollback: PASS
- SPW-03 한국어 용어 감사: PASS (`audit-korean-terms.mjs`, `findings: []`)
- SPW-04 로컬 구현·공식 문서·Issue #467 traceability: PASS
- SPW-05 Markdown readback 및 `git diff --check`: PASS

## 다음 게이트

구현 계획은 이 설계 문서에 대한 사용자 검토 후 작성한다. 계획에는 concrete
파일 목록, RED→GREEN 테스트 순서, Gradle 명령, Floci/LocalStack 증적 경로,
rollback checkpoint를 포함하고, 계획과 설계 문서를 구현 전에 커밋한다.

## 설계 clarification addendum

계획 검토에서 resource equality와 redaction identity가 같은 canonical 값을
사용해야 한다는 점을 확인했다. 따라서 optional 상태와 decoded source/query를
포함한 canonical serializer를 SHA-256 입력으로 고정했고, Spring SPI의 public
resource/generic/constructor 경계와 SDK 없는 classpath용 bridge를 설계 계약에
명시했다. 이는 기능 범위를 넓히지 않는 ABI·redaction 정합성 보정이다.
