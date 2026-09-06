# Issue #637 S3 암호화 전송 cleanup 위험 장부

| 위험 | 완화 | 검증 |
|---|---|---|
| cleanup exception이 primary/cancellation을 대체 | primary instance에 sanitized cleanup을 suppressed로 부착 | identity assertion |
| retry 전에 file reference를 잃어 residue 영구화 | 성공한 close/delete reference만 clear | delete fail→retry test |
| fallback이 unbounded retry가 됨 | configured + `Dispatchers.IO` 두 attempt 고정 | call-count assertion |
| exception/log가 local path나 S3 key 노출 | operation과 failure class만 보존하고 original message/cause 제외 | hostile marker assertion |
| internal seam이 공개 API를 변경 | 기존 constructor 유지, module-internal function property만 추가 | compatibilityCheck |
| fallback 성공인데 failure signal을 남김 | 최종 두 attempt가 모두 실패할 때만 outer cleanup signal 생성 | first-fail/second-success test |
| unrelated object/client를 정리 | delegate-owned temp file과 CSE-created ciphertext file만 대상 | fake ownership assertion |

## Stop condition

primary identity, bounded attempt count, residue 상태 또는 redaction assertion 중 하나라도 실패하면 PR을
생성하지 않는다. 실제 AWS 호출은 이 cleanup state-machine 검증에 필요하지 않으므로 N/A다.
