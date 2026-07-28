# Issue #409 한국어 문서/주석 표준화 범위 인벤토리

## 목적

이 문서는 #359 epic의 하위 작업을 시작하기 전에 한국어 표준화 대상과
제외 대상을 고정한다. GitHub issue/PR 메타데이터는 저장소 규칙에 따라
영어로 유지하지만, 사용자 협업용 문서와 Kotlin KDoc/주석은 명시된
예외를 제외하고 한국어로 통일한다.

## 분류 규칙

| 분류 | 경로 규칙 | 처리 |
|---|---|---|
| 단일 언어 문서 | `README*`, LLM 운영문서, bilingual manual, generated/build/runtime 산출물이 아닌 `*.md`, `*.mdx`, `*.adoc`, `*.txt` | 한국어 재작성 대상 |
| README locale pair | `README.md`, `README.ko.md` | 이번 epic의 primary rewrite 대상에서 제외 |
| LLM-facing 운영문서 | `AGENTS.md`, `CLAUDE.md`, prompts/skills/workflow guidance | 영어 유지 |
| bilingual manual | `docs/manual/en/**/*.md`, `docs/manual/ko/**/*.md` | primary rewrite 제외, parity 검증 대상 |
| manual template | `docs/manual/templates/*.md` | 단일 언어 템플릿이므로 한국어 재작성 대상 |
| generated/build/cache/runtime | `build/**`, `.gradle/**`, `.omx/**`, `.omc/**`, `docs/manual/generated/**` | 직접 수정 금지 |
| test resource fixture | `src/test/resources/**`의 텍스트 fixture | 테스트 fixture 의미 보존, 이번 문서 재작성 대상에서 제외 |
| Kotlin main source | `*/src/main/kotlin/**/*.kt` | KDoc/비자명 주석 한국어화 대상 |

## 현재 스냅샷

2026-07-28 기준 `origin/develop`의 `a1de07a6bc5c60af1239a331cdfba194907b503f`
에서 산출한 수치다.

| 항목 | 수량 | 근거 |
|---|---:|---|
| 문서 파일 전체 | 380 | generated/build/runtime 제외 후 문서 확장자 집계 |
| 단일 언어 문서 대상 | 270 | 위 분류 규칙상 한국어 재작성 대상 |
| README locale pair | 34 | `README.md`, `README.ko.md` |
| LLM-facing 운영문서 | 2 | `AGENTS.md`, `CLAUDE.md` |
| test resource fixture | 2 | `src/test/resources` 텍스트 샘플 |
| bilingual manual parity 대상 | 72 | `docs/manual/en`, `docs/manual/ko` 각각 36개 |
| manual parity 누락 | 0 | en-only 0, ko-only 0 |
| Kotlin main source | 555 | `src/main/kotlin/**/*.kt` |
| KDoc/주석 후보 Kotlin 파일 | 544 | KDoc, line comment, `internal class`, `data class` 검색 |

## 하위 이슈 매핑

| 이슈 | 담당 범위 | 대상 규칙 |
|---|---|---|
| #410 | Dokka 모듈 가이드 | `aws-java/DOKKA.md`, `aws-kotlin/DOKKA.md`, `aws-ktor/DOKKA.md`, `aws-spring-boot/DOKKA.md` |
| #411 | Java SDK/AWS Kotlin SDK wrapper KDoc/주석 | `aws-java/src/main/kotlin/**`, `aws-kotlin/src/main/kotlin/**` |
| #412 | Ktor integration KDoc/주석 | `aws-ktor/src/main/kotlin/**` |
| #413 | Spring Boot/Exposed integration KDoc/주석 | `aws-spring-boot/src/main/kotlin/**`, `aws-exposed/src/main/kotlin/**` |
| #414 | 나머지 단일 언어 문서 | `CHANGELOG.md`, `WIP.md`, `docs/disabled-tests.md`, `docs/governance/**`, `docs/lessons/**`, `docs/review/**`, `docs/superpowers/**`, `docs/manual/templates/**` |

## 검증 명령 맵

| 이슈 | 필수 검증 |
|---|---|
| #410 | `git diff --check`, Dokka 관련 task 확인, 변경 모듈의 `compileKotlin` |
| #411 | `git diff --check`, `./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-kotlin:compileKotlin` |
| #412 | `git diff --check`, `./gradlew :bluetape4k-aws-ktor:compileKotlin` |
| #413 | `git diff --check`, `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-exposed:compileKotlin` |
| #414 | `git diff --check`, 문서 링크/참조 확인, manual parity guard |

## 실행 원칙

- API 이름, class/function/property identifier, command, URL, anchor, 정확한
  오류 메시지는 번역하지 않는다.
- KDoc과 주석은 자연스러운 한국어로 쓰되, 동작 변경은 하지 않는다.
- `internal class`, `data class`의 constructor/property 설명은 의미, 기본값,
  lifecycle, 운영 제약이 비자명할 때 상세히 보강한다.
- README locale pair와 bilingual manual은 이번 epic의 primary rewrite 대상이
  아니다. README는 변경하지 않고, manual은 parity guard로만 검증한다.
- 하위 PR은 각 이슈 범위 안에서 작게 만든다. 한 PR에서 모든 단일 언어
  문서를 한꺼번에 기계 번역하지 않는다.
