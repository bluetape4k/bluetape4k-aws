# 0.1.0 출시 전 사용 중단 XxxFactory class 제거

**날짜**: 2026-05-16
**이슈**: #98
**PR**: (PR link 참조)
**브랜치**: fix/remove-deprecated-factories

## 결정

첫 공개 release(0.1.0) 전에 사용 중단한 factory object 네 개를 제거했다.

- `S3Factory` → `S3ClientFactory`로 교체
- `SesFactory` → `SesClientFactory`로 교체
- `SnsFactory` → `SnsClientFactory`로 교체
- `SqsFactory` → `SqsClientFactory`로 교체

**근거**: 첫 GA release에 deprecated API를 게시하면 이 class를 본 적 없는 소비자에게
불필요한 하위 호환성 부채를 만든다. 아직 게시된 artifact에 의존하는 외부 소비자가
없으므로 0.1.0 전 제거는 호환성을 깨지 않는다.

## 근본 원인

Deprecated object는 프로젝트 초기에 scaffold했고 나중에 `XxxClientFactory` 대응 구현으로
대체했다. `@Deprecated` annotation은 올바르게 적용했지만 제거를 추적할 issue가 없어
미뤄졌다.

## 검증

- 모든 `.kt` 파일을 `grep`해 factory 파일 자체를 제외하면 `S3Factory`, `SesFactory`,
  `SnsFactory`, `SqsFactory` 사용이 0건임을 확인했다.
- `SqsClientFactoryTest.kt`에는 코드가 아니라 *test display name*에만 `SqsFactory`가
  있었으며 이 PR에서 함께 고쳤다.
- `./gradlew :aws:test`: **252개 통과, 기존 @Disabled 2개 pending, 실패 0개**
- README, build script, YAML 파일에서 참조를 찾지 못했다.
- Binary compatibility: 해당 없음 — 아직 0.1.0 artifact를 게시하지 않았다.

## 향후 지침

- Deprecated object/class를 추가할 때는 목표 제거 version을 명시한 추적 issue도 함께
  연다.
- 첫 release 전 프로젝트에서는 deprecation cycle을 유지하기보다 GA 전에 deprecated
  stub을 제거한다.
