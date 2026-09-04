# Issue #616 CodeQL 빌드 캐시 차단

## 배경

`develop`의 `Code Quality Analysis`에서 Gradle 빌드는 성공했지만 CodeQL
`java-kotlin` 분석은 종료 코드 32로 실패했다. 실패 run의 주요
`compileKotlin` task는 모두 `FROM-CACHE`였고, CodeQL은 처리한 Java/Kotlin
소스가 없다고 보고했다.

## 놓친 점

Gradle build 성공만으로 CodeQL source extraction 성공을 증명할 수 없다.
CodeQL이 컴파일러 실행을 추적해야 하는 workflow에서 Gradle build cache가
compile task를 대체하면, 정상 산출물이 있어도 CodeQL database는 비어 있을 수
있다.

## 결정

CodeQL의 Java/Kotlin build에는 `--no-build-cache`를 지정한다. 또한 CI에서
`code_quality_workflow_test.py`를 실행해 이 옵션이 제거되면 계약 검사가
실패하도록 한다. `actions` 분석 matrix와 다른 Gradle workflow의 cache 정책은
변경하지 않는다.

## 결과

CodeQL workflow의 build command만 no-cache로 전환했고, 별도 계약 테스트로
회귀를 잠갔다. 실제 GitHub CodeQL 결과는 변경 branch의 수동 실행에서 별도로
확인한다.

## 검증

- `python3 .github/scripts/code_quality_workflow_test.py`
- `python3 .github/scripts/run_gradle_with_classified_retry_test.py`
- `actionlint .github/workflows/code-quality.yml .github/workflows/ci.yml`
- `./gradlew build -x test --no-daemon --no-build-cache`
- `./gradlew detekt --no-daemon --no-configuration-cache`
- `git diff --check`

## 향후 보호 장치

CodeQL의 compiled-language build를 변경할 때는 Gradle task 성공뿐 아니라
compile task가 실제로 실행됐는지 확인한다. `FROM-CACHE`만 나타난 run은 source
extraction 증거로 사용하지 않고, CodeQL database finalize와 analyze의 terminal
status를 함께 확인한다.
