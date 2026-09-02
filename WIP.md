# 진행 중인 작업 - bluetape4k-aws

기준 시점: 2026-09-02 KST
기준 브랜치: `develop` — 1.0.0 release-prep 기준 정보
범위: `0.5.0` 이후 병합된 공개 API와 `1.0.0` 정식 배포 경계
열린 이슈 수: 2개 (#608, #609)
문서 갱신 시점의 열린 PR 수: 0개 (release-prep PR 생성 전 확인)
최신 안정 릴리스: `0.5.0` (2026-08-06)

## 현재 방향

`0.5.0`은 안정 릴리스로 종료되었고, 저장소는 `1.0.0` 개발선의 공개 API와
문서를 유지합니다. 이 파일은 활성 저장소 관리에 필요한 상태와 다음 확인
지점만 기록합니다.

현재 Exposed 예제는 `bluetape4k-exposed` `2.0.0`의 typed cursor
pagination, Spring Data QBE·projection SQL pushdown, `DynamicPropertyRegistry`
bridge, 선택적 JDBC health/readiness를 사용합니다. 관련 이슈와 PR은 모두
순서대로 `develop`에 병합되었습니다.

- 공개 표면이 병합되면 `CHANGELOG.md`와 관련 README를 같은 변경 단위에서 갱신합니다.
- 릴리스 및 사전 공개 버전 의존성 표기는 `gradle.properties`와
  `gradle/libs.versions.toml`을 기준으로 확인합니다.
- release-pinned manual은 선언된 `releaseRef` 계약을 유지하므로, 개발선 설명은
  루트·모듈 README와 이 파일에서 관리합니다.
- AWS 에뮬레이터 정책은 Floci-first입니다. 새 테스트는
  `-Dbluetape4k.aws.emulator=floci`를 우선 사용하고, LocalStack은 Floci API
  지원 공백을 확인할 때만 명시적으로 사용합니다. MiniStack은 비교용입니다.

## 활성 Queue

| 우선순위 | 이슈 | 마일스톤 | 비고 |
|---|---|---|---|
| 1 | [#608](https://github.com/bluetape4k/bluetape4k-aws/issues/608) | 1.0.0 | Full Nightly `aws-spring-boot` timeout을 release-prep PR에서 함께 수정 |
| 2 | [#609](https://github.com/bluetape4k/bluetape4k-aws/issues/609) | 1.0.0 | catalog SHA·문서·exact-head 검증과 정식 배포 |

## 열린 PR

| PR | 브랜치 | 설명 |
|---|---|---|
| — | 없음 | 2026-08-30 PR #600 생성 전에 `gh pr list --state open` 결과 열린 PR이 없었습니다. |

## `0.5.0` 이후 완료된 작업

- **AWS SDK 핵심 표면** — S3 Tables [#311](https://github.com/bluetape4k/bluetape4k-aws/issues/311),
  Step Functions [#313](https://github.com/bluetape4k/bluetape4k-aws/issues/313),
  Lambda [#314](https://github.com/bluetape4k/bluetape4k-aws/issues/314), SNS
  batch·async publishing [#456](https://github.com/bluetape4k/bluetape4k-aws/issues/456),
  DynamoDB Streams [#469](https://github.com/bluetape4k/bluetape4k-aws/issues/469),
  Kinesis multi-shard consumer [#470](https://github.com/bluetape4k/bluetape4k-aws/issues/470),
  DynamoDB distributed lock·metadata store [#476](https://github.com/bluetape4k/bluetape4k-aws/issues/476)를
  병합했습니다.
- **Spring Boot S3·설정·관측성** — AppConfig runtime reload
  [#458](https://github.com/bluetape4k/bluetape4k-aws/issues/458), S3 ResourceLoader
  [#463](https://github.com/bluetape4k/bluetape4k-aws/issues/463), streaming·object
  converter [#464](https://github.com/bluetape4k/bluetape4k-aws/issues/464), CRT async
  transfer [#465](https://github.com/bluetape4k/bluetape4k-aws/issues/465), CloudWatch
  registry [#466](https://github.com/bluetape4k/bluetape4k-aws/issues/466), ConfigData
  import [#467](https://github.com/bluetape4k/bluetape4k-aws/issues/467), S3 AES·RSA
  client-side encryption [#475](https://github.com/bluetape4k/bluetape4k-aws/issues/475)을
  반영했습니다.
- **Spring Boot SQS·SNS** — visibility heartbeat
  [#453](https://github.com/bluetape4k/bluetape4k-aws/issues/453), batch·partial
  acknowledgement [#454](https://github.com/bluetape4k/bluetape4k-aws/issues/454),
  Extended Client [#455](https://github.com/bluetape4k/bluetape4k-aws/issues/455),
  listener backpressure·FIFO [#460](https://github.com/bluetape4k/bluetape4k-aws/issues/460),
  async batching [#461](https://github.com/bluetape4k/bluetape4k-aws/issues/461), SNS
  envelope [#462](https://github.com/bluetape4k/bluetape4k-aws/issues/462), Observation
  [#473](https://github.com/bluetape4k/bluetape4k-aws/issues/473), SNS HTTP signature
  verification [#457](https://github.com/bluetape4k/bluetape4k-aws/issues/457), composed
  endpoint adapter [#459](https://github.com/bluetape4k/bluetape4k-aws/issues/459), topic
  ARN resolver·cache [#474](https://github.com/bluetape4k/bluetape4k-aws/issues/474),
  batch strategy·converter [#541](https://github.com/bluetape4k/bluetape4k-aws/issues/541)를
  반영했습니다.
- **통합과 품질 경계** — DynamoDB Enhanced
  [#468](https://github.com/bluetape4k/bluetape4k-aws/issues/468), Spring Modulith
  event externalization [#471](https://github.com/bluetape4k/bluetape4k-aws/issues/471),
  AWS emulator `ServiceConnection` [#472](https://github.com/bluetape4k/bluetape4k-aws/issues/472)를
  추가했습니다. SNS signature fixture와 Floci 경계 [#513](https://github.com/bluetape4k/bluetape4k-aws/issues/513),
  fingerprint privacy hardening [#518](https://github.com/bluetape4k/bluetape4k-aws/issues/518),
  공개 KDoc 정렬 [#571](https://github.com/bluetape4k/bluetape4k-aws/issues/571),
  WebFlux cancellation·concurrency 회귀 검증 [#572](https://github.com/bluetape4k/bluetape4k-aws/issues/572),
  [#573](https://github.com/bluetape4k/bluetape4k-aws/issues/573), SQS observation
  후속 [#586](https://github.com/bluetape4k/bluetape4k-aws/issues/586)도 종료했습니다.

## `bluetape4k-exposed` 2.0.0 신규 기능 병합 현황

다음 네 건은 Exposed 2.0.0 신규 기능을 AWS 예제에서 검증하기 위한 순차 train이며,
모두 `develop`에 병합되어 현재 열린 후속 작업이 없습니다.

| 순서 | 이슈 | PR | 반영 내용 |
|---:|---|---|---|
| 1 | [#592](https://github.com/bluetape4k/bluetape4k-aws/issues/592) | [#596](https://github.com/bluetape4k/bluetape4k-aws/pull/596) | Ktor·Spring 주문 API에 typed `ExposedCursorPage` pagination과 customer filter를 적용하고 count query 없는 페이지 계약을 검증 |
| 2 | [#593](https://github.com/bluetape4k/bluetape4k-aws/issues/593) | [#597](https://github.com/bluetape4k/bluetape4k-aws/pull/597) | Spring Exposed Testcontainers 속성을 `DynamicPropertyRegistry` bridge로 AWS database prefix에 연결 |
| 3 | [#594](https://github.com/bluetape4k/bluetape4k-aws/issues/594) | [#598](https://github.com/bluetape4k/bluetape4k-aws/pull/598) | Spring Data Exposed QBE와 closed projection의 선택 column·정렬·limit SQL pushdown을 추가 |
| 4 | [#595](https://github.com/bluetape4k/bluetape4k-aws/issues/595) | [#599](https://github.com/bluetape4k/bluetape4k-aws/pull/599) | Ktor에 opt-in `/healthz/exposed` liveness와 `/readyz/exposed` JDBC `SELECT 1` readiness를 core/JDBC 경계로 추가 |

순차 train의 PR #599 병합 직후 `develop` head는
`248f62fa28a05bc61037ff0cc469cce8059f3681`이며, 해당 병합 후 develop CI도 성공했습니다.

## Backlog

현재 미종료 backlog는 1.0.0 release-prep #608, #609뿐입니다. 두 이슈는 같은
PR과 exact head로 종료해 배포 직전 SHA 변경을 최소화합니다.

## 갱신 메모

- 2026-08-30 KST, PR #600 생성 전에 `gh issue list --state open`과
  `gh pr list --state open`을 실행해 당시 열린 이슈와 PR이 각각 0개임을 확인했습니다.
- 2026-08-30 KST에 `gh release view 0.5.0`으로 최신 안정 릴리스와
  `2026-08-06` 게시 시각을 확인했습니다.
- 1.0.0 release-prep는 `gradle.properties`의 `baseVersion=1.0.0`, 빈
  `snapshotVersion`, 중앙 catalog SHA
  `8efed120b91c4e1b1cfbfe1269321df325b08aef`를 사용합니다.
- PR #599 병합 직후 `develop` head는 Exposed 순차 train의 최종 병합 커밋이며, 기본
  SNS batch diagnostic은 추정 가능한 fingerprint를 로그 문자열에서 제외하고
  호환성 getter는 deprecated 상태로 유지합니다
  (PR [#587](https://github.com/bluetape4k/bluetape4k-aws/pull/587)).
