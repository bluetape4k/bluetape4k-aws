# README Structural Alignment — aws-kotlin and aws-ktor

**Date**: 2026-05-16
**Issue**: #103
**Branch**: docs/readme-alignment

## Root Cause

`aws-kotlin/README.ko.md` was missing `## 아키텍처` (Architecture) section that exists in the English README. Instead, four architecture diagram sub-sections were exposed as standalone `##` headings at the bottom of the document:

- `## 클라이언트 패턴 클래스 다이어그램`
- `## Java SDK v2 vs Kotlin SDK 비교 다이어그램`
- `## DSL 지원 서비스`

This caused heading count mismatch (English: 7 `##`, Korean: 10 `##`) and structural misalignment.

`aws-ktor/README.ko.md` was already structurally aligned — both English and Korean had identical 7 `##` headings. No changes were needed.

## Decision

Rewrote `aws-kotlin/README.ko.md` to:
1. Add `## 아키텍처` section near the top (after intro paragraph, before `## 제공 서비스`) — matching English `## Architecture` position
2. Added `### 클라이언트 생성 패턴 다이어그램` sub-section (present in English but missing in Korean)
3. Moved the three diagram sections under `## 아키텍처` as `###` sub-sections
4. Preserved all existing content; only restructured heading hierarchy

## Outcome

Both locales now have matching 7 `##` heading structure:

| English | 한국어 |
|---------|--------|
| `## Architecture` | `## 아키텍처` |
| `## Supported Services` | `## 제공 서비스` |
| `## Java SDK v2 vs Kotlin SDK Comparison` | `## Java SDK v2 vs Kotlin SDK 비교` |
| `## Client Creation Patterns` | `## 클라이언트 생성 패턴` |
| `## Usage Examples` | `## 사용 예시` |
| `## Test Environment` | `## 테스트 환경` |
| `## Adding the Dependency` | `## 설치` |

## Future Guidance

- When adding a new `##` section to any README locale, immediately mirror the heading in all other locale files.
- CLAUDE.md rule: "Keep `README.md` and `README.ko.md` structurally aligned." — verify heading counts match before PR.
- Use `grep "^## " README.md README.ko.md` as a quick alignment check.
