# Issue 292 0.4.0 release 문서

## 배경

0.4.0 milestone feature issue는 완료됐지만 게시 전에 release용 문서를 마지막으로
검토해야 했다.

## 결정

Publish workflow가 닫을 때까지 milestone을 열어 둔다. 다만 release 전에 README, WIP,
CHANGELOG, diagram evidence를 추적하도록 0.4.0 documentation issue를 만든다.

## 결과

- Root README installation snippet은 이제 `0.4.0`을 사용한다.
- `WIP.md`는 기존 post-0.3.1 development queue 대신 release documentation과 publish
  preflight를 설명한다.
- `CHANGELOG.md`에 DAX, CloudWatch/Logs, IMDS, S3 Access Grants, S3 Vectors, Micrometer,
  Floci 우선 emulator migration, Ktor ecosystem 재사용, CI/Nightly 강화를 다루는 0.4.0
  summary를 추가했다.
- `aws-kotlin` module README의 dependency example은 올바른
  `io.github.bluetape4k.aws` group을 사용한다.

## 검증

- `python3 tools/generate-root-readme-diagrams.py`가 통과했고 추적되는 diagram 변경은 다시
  생성하지 않았다.
- 대상 README scan으로 오래된 `0.3.1`, 오래된 AWS group coordinate, 0.4.0 feature
  keyword를 검사했다.
- `git diff --check` 통과

## 향후 지침

Release 준비 문서 issue에서는 feature work가 닫힌 뒤 `WIP.md`를 갱신해 완료된 active
backlog를 계속 알리지 않게 한다. Release milestone은 documentation cleanup PR이 아니라
publish workflow에서 닫는다.
