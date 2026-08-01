# AWS 0.3.0 릴리스 준비

## 배경

AWS 0.3.0 마일스톤에서는 고급 예제와 문서 갱신을 포함해 Spring Boot 및 Ktor의
S3/SQS 운영 환경 강화 작업을 마무리했다.

## 결정

`baseVersion=0.3.0`, `snapshotVersion=`, `0.3.0`을 사용하는 README 의존성
코드 조각, 그리고 `bluetape4k-bom:1.9.2`와 `bluetape4k-exposed-bom:1.9.2`로
고정한 상위 BOM import로 릴리스 태그를 준비한다.

## 결과

릴리스 메타데이터와 공개 설치 코드 조각은 이제 변경할 수 없는 0.3.0 버전을
가리킨다. 안정 버전을 게시하려면 병합한 준비 상태에서 최신 Nightly(full),
스냅샷 검증, 릴리스 태그, release workflow dispatch가 여전히 필요하다.

## 검증

- `./gradlew help --refresh-dependencies --no-daemon --no-configuration-cache --no-build-cache`
- `./gradlew clean generatePomFileForBluetapeAwsPublication --no-daemon --no-configuration-cache --no-build-cache`
- 생성한 POM을 검사한 결과 `SNAPSHOT`, 예제, 데모, 벤치마크 아티팩트가
  유출되지 않았다.
- 생성한 POM은 AWS 아티팩트에 `0.3.0`, 상위 bluetape4k 및 Exposed BOM
  import에 `1.9.2`를 포함한다.
- `./gradlew publishToMavenLocal -x collectReachabilityMetadata --no-daemon --no-configuration-cache --no-build-cache -Dorg.gradle.parallel=false`
- `./gradlew build -x test -x koverVerify -x collectReachabilityMetadata --no-daemon --no-configuration-cache --no-build-cache -Dorg.gradle.parallel=false`

## 향후 보호 장치

릴리스 준비 PR이 병합되고 현재 `develop` SHA에 최신 Nightly(full) 및 스냅샷 게시
증거가 생길 때까지 `0.3.0` 태그를 만들지 않는다. `org.gradle.parallel`을
활성화하면 전체 `build publishToMavenLocal` 결합 경로가 여전히 Gradle의 GraalVM
reachability metadata exclusive-lock 보호 장치에 걸릴 수 있다. 따라서 릴리스
준비용 컴파일과 게시 검사를 분리하거나 병렬 실행을 비활성화한다.
