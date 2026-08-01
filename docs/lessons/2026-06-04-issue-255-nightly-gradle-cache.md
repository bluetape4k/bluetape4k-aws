# 2026-06-04 이슈 255 Nightly Gradle cache

## 배경

bluetape4k 저장소의 Nightly build가 GitHub runner에서 관리형 dependency를 간헐적으로
`group:artifact:.`로 해석했습니다.

## 결정

예약 실행이 오래된 dependency-management 상태를 재사용하지 않도록 Nightly job에서
`gradle/actions/setup-gradle` cache restore/write를 비활성화합니다.

## 결과

모든 Nightly `setup-gradle` block에서 명시적인 Gradle dependency refresh는 유지하면서
`cache-disabled: true`를 설정합니다.

## 검증

- `.github/workflows/nightly-tests.yml` 검사 결과 setup-gradle block과 cache-disabled
  block 수가 일치했습니다.
- 예정된 검증: `actionlint`, `git diff --check`.

## 향후 지침

Nightly workflow가 snapshot 또는 BOM으로 관리하는 bluetape4k dependency를 사용할 때는
cache restore가 오래된 metadata를 재사용하지 않는다는 최신 CI 근거가 나오기 전까지
Gradle action cache를 비활성화한 상태로 유지합니다.
