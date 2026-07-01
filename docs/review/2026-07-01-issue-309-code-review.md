# Issue 309 Code Review

## Scope

- Spring Boot EventBridge auto-configuration, properties, and coroutine operations template.
- Ktor EventBridge server plugin, configuration, runtime ownership, and coroutine/future operations facade.
- Root and module README locale pairs for EventBridge framework integration.

## Findings

| Lens | Severity | Finding | Resolution |
|---|---:|---|---|
| API contract | P0 | None. Framework layers delegate to the focused #308 EventBridge core helpers and keep one SDK request per operation. | Verified by template tests that capture SDK requests. |
| Partial failures | P0 | None. `PutEvents`, `PutTargets`, and `RemoveTargets` return raw SDK responses instead of collapsing failures to Boolean success. | Verified by Spring and Ktor raw response tests. |
| Spring Boot lifecycle | P0 | None. Auto-configuration is opt-out, optional on the EventBridge SDK classpath, and backs off when users provide their own client or operations facade. | Verified by `ApplicationContextRunner` tests. |
| Ktor lifecycle | P0 | None. Injected operations and clients remain application-owned, while plugin-owned clients are closed once on `ApplicationStopping`. | Verified by Ktor plugin lifecycle tests. |
| Defaults and customization | P0 | None. Region, endpoint override, credentials, shared AWS defaults, global customizers, and EventBridge-specific customizers compose in the same order as existing Spring/Ktor integrations. | Verified by Spring and Ktor customization tests. |
| Validation | P0 | None. Blank default event bus names and endpoint-without-region are rejected before client construction. | Verified by Spring property and Ktor config tests. |
| Emulator | P1 | No repository-local EventBridge emulator scaffold exists for Spring Boot or Ktor. A live Floci/LocalStack smoke was therefore not claimed in this issue. | Recorded with `find ... -name '*EventBridge*Emulator*'` returning `0` and an EventBridge emulator `rg` probe returning no matches. |
| Documentation | P0 | None. README locale pairs now document dependency requirements, Spring operations, Ktor plugin usage, partial-failure handling, and non-goals. | Verified by README `rg EventBridge/eventbridge/Scheduler` review. |
| Diagram | P0 | None. README locale pairs now include a source-backed EventBridge Spring/Ktor class map with a solid/dashed relationship legend. | Verified by XML parse, CairoSVG render, full-size PNG inspection, endpoint/geometry/mixed-corner/connector audits, and README image-link checks. |

## Verification Evidence

- Baseline compile before changes passed:
  `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin :bluetape4k-aws-ktor:compileTestKotlin --warning-mode all`
- RED test pass failed on the missing EventBridge framework surfaces before implementation.
- Targeted tests passed after implementation:
  `./gradlew --no-daemon :bluetape4k-aws-spring-boot:test --tests "*EventBridge*" :bluetape4k-aws-ktor:test --tests "*EventBridge*" --no-configuration-cache`
- Compile verification passed after implementation:
  `./gradlew --no-daemon :bluetape4k-aws-spring-boot:compileTestKotlin :bluetape4k-aws-ktor:compileTestKotlin --warning-mode all`
- `git diff --check` passed.
- Diagram evidence:
  - `python3 -c "import xml.etree.ElementTree as ET; ET.parse(...)"` passed for `bluetape4k-aws-eventbridge-class-32.svg`.
  - `~/.local/bin/cairosvg ... -s 2` rendered `bluetape4k-aws-eventbridge-class-32.png` at `2400 x 1520`.
  - `diagram-geometry-audit.py --fail-diagonal` reported `geometry_failures=0`.
  - `diagram-endpoint-audit.py` reported `PASS files=1`.
  - `diagram-mixed-corner-audit.py` reported `paths=13 q_bends=12 failures=0`.
  - `diagram-connector-audit.py` reported `markers=0 connectors=13 cards=11 intrusions=0 crossings=0`.
  - Full-size PNG inspection confirmed no text/card/connector overlap after moving Ktor relationship labels and shared-core delegate routes.
