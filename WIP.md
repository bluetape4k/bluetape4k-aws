# 진행 중인 작업 - bluetape4k-aws

스냅샷: 2026-07-28 KST
범위: 진행 중인 0.5.0 한국어 문서 및 KDoc 표준화.
오픈 수: 전체 open issue 10개, milestone 0.5.0 open issue 7개.

## 현재 방향

`0.5.0` queue는 repository-wide 한국어 문서 및 KDoc 표준화 epic에 집중합니다.
Issue #359가 parent epic이며, 각 pull request가 CI 대기나 merge 없이 PR 생성 지점에서 멈출 수 있도록
작업을 review 가능한 sub-issue로 나누어 진행합니다.

이 파일은 active repository management에 필요한 내용만 유지합니다.

- merge 후 public surface가 바뀌면 `CHANGELOG.md`를 갱신합니다.
- release 및 snapshot note는 `gradle.properties`, `gradle/libs.versions.toml`와 맞춥니다.
- 미래 service coverage 후보는 구현 준비가 되었을 때만 `Backlog`에서 구체적인 milestone으로 이동합니다.
- 완료된 milestone 작업은 active WIP로 다시 나열하지 않습니다.

AWS emulator 정책은 Floci-first를 유지합니다. 새로 만들거나 migration하는 emulator-aware test는
`-Dbluetape4k.aws.emulator=floci`를 우선 사용합니다. LocalStack은 Floci API coverage gap에 대한
명시적 fallback이며, MiniStack은 비교 전용입니다.

## 활성 Queue

| 우선순위 | Issue | Milestone | Notes |
|---|---|---|---|
| P0 | [#359](https://github.com/bluetape4k/bluetape4k-aws/issues/359) | 0.5.0 | 한국어 문서 및 주석 표준화 epic입니다. |
| P1 | [#409](https://github.com/bluetape4k/bluetape4k-aws/issues/409) | 0.5.0 | inventory 및 제외 범위 map입니다. PR #415가 열려 있습니다. |
| P1 | [#410](https://github.com/bluetape4k/bluetape4k-aws/issues/410) | 0.5.0 | Dokka module guide 작업입니다. PR #416이 열려 있습니다. |
| P1 | [#411](https://github.com/bluetape4k/bluetape4k-aws/issues/411) | 0.5.0 | aws-java/aws-kotlin KDoc 작업입니다. PR #417은 STS slice를 다룹니다. |
| P1 | [#412](https://github.com/bluetape4k/bluetape4k-aws/issues/412) | 0.5.0 | aws-ktor KDoc 작업입니다. PR #418은 SQS consumer slice를 다룹니다. |
| P1 | [#413](https://github.com/bluetape4k/bluetape4k-aws/issues/413) | 0.5.0 | Spring Boot 및 Exposed KDoc 작업입니다. PR #419와 #420이 초기 Exposed slice를 다룹니다. |
| P1 | [#414](https://github.com/bluetape4k/bluetape4k-aws/issues/414) | 0.5.0 | 제외되지 않은 남은 단일 언어 문서 재작성 작업입니다. |

## 열린 PR

| PR | 브랜치 | 설명 |
|---|---|---|
| [#415](https://github.com/bluetape4k/bluetape4k-aws/pull/415) | `docs/issue-409-korean-scope-inventory` -> `develop` | 한국어 표준화 inventory입니다. |
| [#416](https://github.com/bluetape4k/bluetape4k-aws/pull/416) | `docs/issue-410-korean-dokka` -> `develop` | Dokka module guide를 한국어로 재작성합니다. |
| [#417](https://github.com/bluetape4k/bluetape4k-aws/pull/417) | `docs/issue-411-aws-java-kotlin-kdoc-sts` -> `develop` | STS wrapper KDoc slice입니다. |
| [#418](https://github.com/bluetape4k/bluetape4k-aws/pull/418) | `docs/issue-412-ktor-sqs-kdoc` -> `develop` | Ktor SQS consumer KDoc slice입니다. |
| [#419](https://github.com/bluetape4k/bluetape4k-aws/pull/419) | `docs/issue-413-exposed-kdoc` -> `develop` | Exposed integration KDoc slice입니다. |
| [#420](https://github.com/bluetape4k/bluetape4k-aws/pull/420) | `docs/issue-413-spring-boot-kdoc` -> `develop` | Spring Exposed KDoc slice입니다. |

## 0.4.0 이후 완료된 작업

- [#268](https://github.com/bluetape4k/bluetape4k-aws/issues/268)은 core
  Secrets Manager 및 Parameter Store wrapper를 추가했습니다.
- [#269](https://github.com/bluetape4k/bluetape4k-aws/issues/269)와
  [#295](https://github.com/bluetape4k/bluetape4k-aws/issues/295)는 RDS IAM helper를
  core AWS module로 승격하고 JDBC refresh를 shared helper boundary로 위임했습니다.
- [#270](https://github.com/bluetape4k/bluetape4k-aws/issues/270)은 Spring Boot
  Kinesis 자동 구성과 coroutine operation을 추가했습니다.
- [#271](https://github.com/bluetape4k/bluetape4k-aws/issues/271)은 Ktor SES v2 및
  SNS integration support를 추가했습니다.
- [#180](https://github.com/bluetape4k/bluetape4k-aws/issues/180)과
  [#181](https://github.com/bluetape4k/bluetape4k-aws/issues/181)은 Spring Boot 및 Ktor
  `aws-exposed` settings integration을 완료했습니다.
- [#308](https://github.com/bluetape4k/bluetape4k-aws/issues/308)과
  [#309](https://github.com/bluetape4k/bluetape4k-aws/issues/309)는 EventBridge core wrapper,
  coroutine DSL, Spring Boot 통합, Ktor 통합, README diagram을 추가했습니다.
- [#272](https://github.com/bluetape4k/bluetape4k-aws/issues/272)는 Ktor Kinesis 및 STS helper를 추가했습니다.
- [#273](https://github.com/bluetape4k/bluetape4k-aws/issues/273)는 남은 service coverage gap을 위한
  Ktor example을 추가했습니다.
- [#275](https://github.com/bluetape4k/bluetape4k-aws/issues/275)는 gitleaks release asset lookup을 강화했습니다.
- [#284](https://github.com/bluetape4k/bluetape4k-aws/issues/284),
  [#285](https://github.com/bluetape4k/bluetape4k-aws/issues/285) 및
  [#286](https://github.com/bluetape4k/bluetape4k-aws/issues/286)은 남은 0.5.0 hygiene review item을 닫았습니다.

## Backlog

| 이슈 | 설명 |
|---|---|
| [#311](https://github.com/bluetape4k/bluetape4k-aws/issues/311) | S3 Tables support입니다. |
| [#313](https://github.com/bluetape4k/bluetape4k-aws/issues/313) | Step Functions execution helper입니다. |
| [#314](https://github.com/bluetape4k/bluetape4k-aws/issues/314) | Lambda invocation helper입니다. |
| [#369](https://github.com/bluetape4k/bluetape4k-aws/issues/369) | module test와 example application에서 Testcontainers reuse를 분리합니다. |

## 갱신 메모

- 2026-07-28 KST에 `gh`로 확인했습니다. `0.5.0`에는 open issue 7개
  (#359 및 #409-#414)가 있습니다.
- 2026-07-28 KST에 `gh`로 확인했습니다. repository에는 open issue 10개가 있고,
  문서 표준화 PR #415-#420은 `develop` 대상으로 열려 있습니다.
- 현재 unreleased development는 `baseVersion=0.5.0`을 사용합니다.
- local snapshot dependency는 다음과 같습니다.
  `io.github.bluetape4k:bluetape4k-bom:1.11.1-SNAPSHOT` and
  `io.github.bluetape4k.exposed:bluetape4k-exposed-bom:1.12.0-SNAPSHOT`.
