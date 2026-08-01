# 2026-06-01 0.4.0 개발선 시작

## 배경

release train의 dependency를 맞추기 위해 `bluetape4k-aws` `0.3.1`을 발행했습니다.

## 결정

commit에 기록한 `baseVersion`을 `0.4.0`으로 올리고 `snapshotVersion=`는 비워 둬
release workflow가 snapshot qualifier를 명시적으로 주입하게 합니다.
직접 참조하는 bluetape4k BOM은 다음 catalog train snapshot에 맞춥니다.
`bluetape4k-bom:1.11.0-SNAPSHOT` and
`bluetape4k-exposed-bom:1.11.0-SNAPSHOT`.

## 결과

저장소가 다음 minor 개발선을 시작할 준비를 마쳤습니다.

## 검증

- `gradle.properties`는 `baseVersion=0.4.0`을 사용합니다.
- `snapshotVersion=`는 빈 값으로 유지됩니다.
- `./gradlew help --no-daemon --console=plain`이 갱신된 catalog를 해석합니다.
