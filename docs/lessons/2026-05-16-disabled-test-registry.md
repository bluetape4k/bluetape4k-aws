# 비활성화 테스트 등록부 패턴

**날짜**: 2026-05-16
**이슈**: #104
**브랜치**: feat/issue-104-disabled-registry

## 배경

과거 `bluetape4k-aws`에서 `@Disabled`로 표시한 테스트는 중앙 기록이 없었다. 어떤
테스트를 왜 건너뛰는지, 관련 이슈로 추적하는지 감사하기 어려웠다.

## 결정

모든 `@Disabled` 테스트의 표준 등록부로 `docs/disabled-tests.md`를 만든다. 각 항목에
모듈, 파일 경로, 테스트 이름, 범위(class/method), 범주, 추적 이슈, 사유를 기록한다.

`@Disabled` annotation에는 다음 형식 규칙을 적용한다.

```
@Disabled("#NNN — <one-sentence reason>")
```

## 정의한 범주

- `unsupported-emulator` — LocalStack 또는 floci가 구현하지 않은 service/API
- `out-of-band-protocol` — emulator 밖에서 전달되는 입력이 필요한 flow(SMS token,
  email callback, webhook 같은 입력)

## 연기한 작업

`#NNN — ` 접두사가 없는 `@Disabled` annotation을 거부하는 CI 검사를 처음에는 계획했다.
이 작업은 issue #104의 후속 항목으로 연기했다. 현재는 규칙과 PR review로 형식을
강제한다.

## 교훈

테스트가 쌓인 뒤가 아니라 **지금** 등록부를 만든다. 사후 audit는 annotation을 추가할
때 등록부를 갱신하는 것보다 비용이 크다. 새 `@Disabled` annotation을 추가하는 PR의
설명에는 reviewer가 `docs/disabled-tests.md` 갱신을 확인하도록 안내한다.
