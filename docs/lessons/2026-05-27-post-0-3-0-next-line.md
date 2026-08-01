# 0.3.0 릴리스 후 다음 개발 버전

## 배경

AWS 0.3.0을 Maven Central과 GitHub Releases에 게시했다. 최신 안정 릴리스를 계속
표시해야 하는 공개 README 코드 조각은 변경하지 않으면서 다음 패치 버전 개발을
다시 시작해야 했다.

## 결정

`baseVersion=0.3.1`을 설정하고 `snapshotVersion=`은 비워 둔다. 스냅샷 게시는
계속 workflow에서 `-PsnapshotVersion=-SNAPSHOT`을 주입한다.

## 결과

이후 개발 스냅샷은 `0.3.1-SNAPSHOT`으로 해석되며, README 설치 예제는 계속 안정
버전인 `0.3.0` 아티팩트를 안내한다.

## 검증

- `./gradlew help --refresh-dependencies --no-daemon --no-configuration-cache --no-build-cache`
- `git diff --check`

## 향후 보호 장치

각 안정 릴리스 후 기능 개발을 시작하기 전에 별도 PR에서 `baseVersion`을 다음
패치 개발 버전으로 올린다.
