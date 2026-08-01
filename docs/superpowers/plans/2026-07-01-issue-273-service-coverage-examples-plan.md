# 이슈 #273 서비스 커버리지 예제 계획

## 범위

새 Ktor 서비스 커버리지 예제 모듈을 제공하고 milestone `0.6.0`을 위해
저장소 문서, 다이어그램, CI에 연결한다.

## 단계

1. 남은 서비스 영역 6개 모두에 새 모듈 뼈대, 테스트 resource, route 테스트를 추가한다.
2. 기존 Ktor plugin config와 typed DTO로 `serviceCoverageExampleModule`을 구현한다.
3. Gradle 설정과 저장소 모듈 문서에 모듈을 등록한다.
4. root와 모듈 README를 영어와 한국어로 갱신한다.
5. 서비스 커버리지 SVG/PNG를 갱신하고 렌더링된 asset을 검사한다.
6. CI와 Nightly 예제 테스트 workflow에 모듈을 등록한다.
7. 범위가 좁은 검증을 실행한다.
   - `./gradlew :aws-ktor-service-coverage-examples:compileTestKotlin :aws-ktor-service-coverage-examples:test`
   - `./gradlew projects`
   - `actionlint`
   - `git diff --check`
8. 검토 및 lesson artifact를 기록한 다음 commit하고 #273에 연결된 PR을 생성한다.

## 위험

- 남은 서비스에 대한 emulator 지원은 균일하지 않다. operation을 주입해 테스트의 결정성을 유지하고 emulator fallback을 문서화한다.
- README와 chart 갱신은 locale 간 동등성을 유지해야 한다.
- CI 상태 집계가 명시적이므로 workflow 변경에 lint를 실행해야 한다.
