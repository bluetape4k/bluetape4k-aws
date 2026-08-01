# Issue 359 aws-kotlin STS KDoc 검토

- 범위: aws-kotlin auth/http/sts KDoc 영문화와 PR 검증 중 발견한 CI coverage artifact 검증 수정.
- 변경 형태: Kotlin 파일 7개의 주석만 변경했으며 import, 시그니처, 구현은 변경하지 않았다. Workflow 변경은 coverage artifact 검증과 다운로드 이름 정규화로 제한했다.
- 한국어 스캔: 변경 후 aws-kotlin auth/http/sts 아래 한국어 포함 파일은 0개였다.
- 검증: compileKotlin과 dokkaGenerateModuleHtml이 :bluetape4k-aws-kotlin에서 통과했다. Dokka는 범위 밖 Kinesis/SesV2 파일의 기존 미해결 링크 경고를 출력했다. 부분 모듈 실행의 coverage artifact 불일치를 수정한 뒤 actionlint, git diff --check, 로컬 셸 시뮬레이션이 통과했다.
- P0/P1: 발견하지 못했다.
