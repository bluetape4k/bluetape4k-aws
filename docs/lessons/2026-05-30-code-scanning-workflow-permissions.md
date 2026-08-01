# 코드 스캔 workflow 권한

## 배경

GitHub CodeQL이 스냅샷 게시 및 release workflow에서
`actions/missing-workflow-permissions` 경고를 보고했다.

## 결정

checkout을 사용하는 workflow에는 workflow 수준의 `contents: read` 권한을
명시하고, 토큰이 필요 없는 작업은 `permissions: {}`로 재정의하며,
`contents: write`는 릴리스를 만드는 GitHub Release 작업에만 유지한다.

## 결과

게시 또는 릴리스 동작을 변경하지 않으면서 경고가 발생한 작업의 workflow token
기본값에 최소 권한을 적용했다.

## 검증

- `actionlint .github/workflows/publish-snapshot.yml .github/workflows/release.yml`
- `yq`를 사용한 workflow 및 작업 권한 검사
- `git diff --check`

## 향후 보호 장치

앞으로 GitHub Actions를 수정할 때는 먼저 workflow 수준의 `permissions` 블록을
명시하고, 특정 단계에 쓰기 권한이 필요할 때만 개별 작업의 권한을 넓힌다.
