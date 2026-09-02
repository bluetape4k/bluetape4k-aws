# WIP - bluetape4k-aws

- 기준일: 2026-09-02 KST
- 최신 안정 버전: `1.0.0`
- 안정 tag commit: `632e0f346b807c4d50e3195f7b2b72082def9460`
- 현재 개발선: `1.1.0-SNAPSHOT`
- 현재 milestone: `1.1.0`

## 현재 상태

`1.0.0` artifact와 GitHub Release 배포를 완료했다. `develop`은 `1.1.0` minor 개발선을 사용한다. 공개 AWS manual은 `1.0.0` tag source로 갱신한다.

## 다음 개발선 규칙

- `gradle.properties`는 `baseVersion=1.1.0`, 빈 `snapshotVersion`을 유지한다.
- SNAPSHOT workflow가 실행할 때만 `-PsnapshotVersion=-SNAPSHOT`을 주입한다.
- 중앙 catalog SHA는 `bluetape4k-dependencies`의 다음 개발선이 병합된 뒤 한 번만 갱신한다.
- Floci-first 테스트 정책과 최소 Testcontainers gate 계약은 그대로 유지한다.

## 추적

생태계 전체 후속 작업은 [bluetape4k-dependencies #235](https://github.com/bluetape4k/bluetape4k-dependencies/issues/235)에서 추적한다. 신규 기능과 버그는 `1.1.0` milestone에서 관리한다.
