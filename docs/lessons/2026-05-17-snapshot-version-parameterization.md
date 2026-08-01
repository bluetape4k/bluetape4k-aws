# 스냅숏 버전 매개변수화

배경: Central Portal 릴리스에서 `-SNAPSHOT`을 제거하기 위해
`gradle.properties`를 수정하게 해서는 안 됩니다.

결정: `snapshotVersion=`는 기본적으로 비워 두고 `publish-snapshot.yml`이
`-PsnapshotVersion=-SNAPSHOT`을 전달하게 합니다.

결과: `develop`은 릴리스 준비 상태를 유지하고, 스냅숏 발행은 워크플로 명령에
명시적으로 남습니다.

검증: `actionlint .github/workflows/publish-snapshot.yml`.

향후 지침: `gradle.properties`에서 `snapshotVersion=-SNAPSHOT`을 기본값으로 다시
도입하지 않습니다.
