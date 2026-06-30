# Issue 275 Review - gitleaks installer

- Date: 2026-06-30 KST
- Scope:
  - `.github/workflows/ci.yml`
- Issue: #275

## Review Notes

- The `gitleaks detect` command remains unchanged and still uses `.gitleaks.toml`.
- The installer no longer reconstructs the tarball URL from the version string.
- The workflow keeps the pinned `GITLEAKS_VERSION` value and resolves the Linux x64 tarball from GitHub release metadata for that tag.
- The local macOS host cannot execute the downloaded Linux binary, but `file` confirms the selected asset is an x86-64 Linux ELF binary.

## Validation

- `actionlint .github/workflows/ci.yml`: PASS
- `git diff --check`: PASS
- `rg -n -F "\\'" .github/workflows`: PASS, no escaped single-quote hits
- Release API asset probe: PASS, selected `gitleaks_8.30.1_linux_x64.tar.gz`
- `gitleaks detect --source . --redact --no-git --config .gitleaks.toml`: PASS, no leaks found

## P0/P1 Verdict

- P0: 0
- P1: 0
