# Issue #635 SNS HTTP envelope 구현 위험

| 위험 | 신호 | 완화 | 중지·rollback |
| --- | --- | --- | --- |
| adapter API/ABI drift | 기존 parser call 또는 `javap` descriptor 불일치 | 기존 class/object, constructor, parse signature와 반환 model 유지 | public descriptor가 사라지면 구현 중지 후 mapping 경계 재설계 |
| duplicate detector 우회 | 공통 duplicate case가 한 adapter에서 PASS | 두 기본 decoder에 strict Jackson factory 사용 | custom mapper 계약으로 해결되지 않으면 spec 재개 |
| 과도한 host 허용 | extra label/partition mismatch case가 PASS | partition별 exact label 수와 suffix 검사 | 공식 endpoint 근거 없는 host 추가 금지 |
| decoder 예외 정보 노출 | 오류 message에 payload, signature, token, 전체 URL 포함 | core `INVALID_JSON` fixed message와 enum reason 사용 | redaction assertion 실패 시 PR 차단 |
| test fixture variant 영향 | Gradle metadata, fixture compile, module build 실패 | 기존 Gradle `java-test-fixtures` 기능만 사용, 새 dependency 없음 | variant가 repository 규칙과 충돌하면 root shared resource 대안으로 rollback |
| raw map 의미 변화 | Spring MessageAttributes 또는 Ktor raw 회귀 | adapter별 기존 raw type 변환을 명시적으로 유지 | 회귀 테스트 실패 시 core raw를 adapter input에서 분리 |
| byte 상한 성능 회귀 | decoder가 oversized body에서 호출됨 | counter decoder test와 decode-before-limit 순서 고정 | 호출 횟수/순서 실패 시 구현 중지 |

이 변경은 HTTP parsing과 보안 경계를 건드리므로 Step 4-P performance/stability scan을 실행한다.
