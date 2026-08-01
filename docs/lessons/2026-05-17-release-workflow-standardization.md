# 릴리스 워크플로 표준화

배경: Central Portal 릴리스 작업에서는 `bluetape4k-projects`의 릴리스 워크플로 구성을 표준으로 사용합니다.

결정: 워크플로 표시 이름은 변경하지 않고 릴리스 준비 워크플로 파일의 이름을 `nightly-tests.yml` 및 `publish-snapshot.yml`로 변경합니다.

결과: 릴리스 준비 스크립트는 bluetape4k 저장소 전체에서 동일한 워크플로 파일 이름을 사용할 수 있습니다.

검증: `actionlint .github/workflows/nightly-tests.yml .github/workflows/publish-snapshot.yml .github/workflows/release.yml`.

향후 지침: 저장소별 예외를 `AGENTS.md`에 문서화하지 않는 한 릴리스 워크플로 파일 이름을 `bluetape4k-projects`에 맞춰 유지합니다.
