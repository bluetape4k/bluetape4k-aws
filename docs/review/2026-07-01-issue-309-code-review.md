# Issue 309 코드 검토

## 범위

- Spring Boot EventBridge 자동 구성/property/coroutine operation template
- Ktor EventBridge server plugin/configuration/runtime ownership/coroutine-future facade
- EventBridge framework 통합용 root/module README locale

## 결과

| 관점 | 심각도 | 결과 | 증거 |
|---|---:|---|---|
| API | P0 | #308 core helper에 위임하고 operation당 SDK request 하나를 유지한다. | SDK request capture template test |
| 부분 실패 | P0 | `PutEvents`, `PutTargets`, `RemoveTargets`는 원본 응답을 반환한다. | Spring/Ktor raw response test |
| Spring 수명 주기 | P0 | EventBridge SDK classpath에 선택적이며 opt-out이고 사용자 client/facade가 있으면 물러난다. | `ApplicationContextRunner` test |
| Ktor 수명 주기 | P0 | injected operation/client는 application 소유이며 plugin client는 `ApplicationStopping`에서 한 번 닫힌다. | lifecycle test |
| 기본값/customization | P0 | region/endpoint/credentials/default/global/service customizer 순서를 기존 패턴과 맞춘다. | customization test |
| 검증 | P0 | 빈 default event bus와 region 없는 endpoint를 client 생성 전에 거부한다. | property/config test |
| Emulator | P1 | 로컬 scaffold가 없어 live smoke를 주장하지 않았다. | `find ... -name '*EventBridge*Emulator*'`=`0`, `rg` 결과 없음 |
| 문서 | P0 | dependency, Spring/Ktor usage, 부분 실패, non-goal을 기록했다. | README `rg EventBridge/eventbridge/Scheduler` |
| 다이어그램 | P0 | source 기반 class map과 관계 legend를 추가했다. | XML/render/geometry/connector/image-link 검사 |

## 검증 증거

- `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin :bluetape4k-aws-ktor:compileTestKotlin --warning-mode all`: baseline PASS
- 구현 전 누락 surface로 RED test 실패
- `./gradlew --no-daemon :bluetape4k-aws-spring-boot:test --tests "*EventBridge*" :bluetape4k-aws-ktor:test --tests "*EventBridge*" --no-configuration-cache`: PASS
- `./gradlew --no-daemon :bluetape4k-aws-spring-boot:compileTestKotlin :bluetape4k-aws-ktor:compileTestKotlin --warning-mode all`: PASS
- `git diff --check`: PASS
- `python3 -c "import xml.etree.ElementTree as ET; ET.parse(...)"`: `bluetape4k-aws-eventbridge-class-32.svg` PASS
- `~/.local/bin/cairosvg ... -s 2`: `bluetape4k-aws-eventbridge-class-32.png`, `3000 x 1960`
- `diagram-geometry-audit.py --fail-diagonal`: `geometry_failures=0`
- `diagram-endpoint-audit.py`: `PASS files=1`
- `diagram-mixed-corner-audit.py`: `paths=12 q_bends=4 failures=0`
- `diagram-connector-audit.py`: `markers=0 connectors=12 cards=11 intrusions=0 crossings=0`
- Full-size 검사 실패 후 canvas를 `1500 x 980`으로 확장하고 card 간격/delegate corridor/title 위치를 조정했으며 재검사에서 overlap/intrusion 문제 없음.
