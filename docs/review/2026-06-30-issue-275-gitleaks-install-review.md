# Issue 275 검토 - gitleaks installer

- 날짜: 2026-06-30 KST
- 범위: `.github/workflows/ci.yml`
- Issue: #275

## 검토 내용

- `gitleaks detect`와 `.gitleaks.toml` 사용은 유지했다.
- installer는 version 문자열로 tarball URL을 재구성하지 않는다.
- 고정된 `GITLEAKS_VERSION`을 유지하고 해당 tag의 GitHub release metadata에서 Linux x64 tarball을 찾는다.
- macOS에서 Linux binary를 실행할 수 없지만 `file`로 x86-64 Linux ELF를 확인했다.

## 검증

- `actionlint .github/workflows/ci.yml`: PASS
- `git diff --check`: PASS
- `rg -n -F "\\'" .github/workflows`: PASS, escaped single-quote 없음
- Release API asset probe: PASS, `gitleaks_8.30.1_linux_x64.tar.gz`
- `gitleaks detect --source . --redact --no-git --config .gitleaks.toml`: PASS, leak 없음

## P0/P1 판정

- P0: 0
- P1: 0
