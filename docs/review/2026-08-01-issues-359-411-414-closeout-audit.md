# 이슈 #359 및 #411-#414 종료 감사

## 목적

이 문서는 milestone `0.5.0`의 한국어 문서·KDoc 표준화 epic #359와
하위 이슈 #411-#414가 현재 수용 기준을 충족하는지 확인한다. 병합된 PR의
범위와 현재 소스 트리를 함께 대조하며, 대표 슬라이스의 병합을 전체 이슈
완료로 간주하지 않는다.

감사 기준은 `origin/develop`의
`8c9a3c3733904d58fcaceb9958f3e5deeb31dc25`와 2026-08-01 KST의 live GitHub
issue/PR 상태다.

## 병합 PR 감사

| PR | 연결 이슈 | 실제 병합 범위 | 이슈 수용 기준 충족 여부 |
|---|---|---|---|
| #415 | #409 | 범위 인벤토리와 제외 규칙 | 충족. #409는 종료됨 |
| #416 | #410 | 네 모듈의 `DOKKA.md` | 충족. #410은 종료됨 |
| #417 | #411 | `aws-java`, `aws-kotlin`의 STS package | 미충족. PR 본문도 나머지 service package를 후속 범위로 명시함 |
| #418 | #412 | `aws-ktor`의 SQS consumer package | 미충족. PR 본문도 나머지 Ktor package를 제외함 |
| #419 | #413 | `aws-exposed/src/main/kotlin/**` | 부분 충족. Exposed 범위는 완료됐지만 Spring Boot 범위가 남음 |
| #420 | #413 | `aws-spring-boot/.../spring/exposed/**` | 미충족. PR 본문도 나머지 Spring Boot service package를 제외함 |
| #421 | #414 | `WIP.md`, `docs/disabled-tests.md` | 미충족. PR 본문이 나머지 단일 언어 문서를 제외함 |

PR #415-#421은 모두 `develop`에 병합됐다. 그러나 #417-#421은 의도적으로
`Refs #411`-`Refs #414`를 사용한 대표 슬라이스이므로, 병합 상태만으로 하위
이슈를 종료할 수 없다.

## 이번 복원 범위

현재 소스의 직접 조상에는 한국어 KDoc·주석을 영어로 바꾼 문서 전용
커밋이 남아 있었다. 새 문장을 기계 번역하지 않고 해당 커밋의 한국어 원문을
복원했다. 이미 개선된 STS, Ktor SQS, Exposed 슬라이스는 유지했고, deprecated
API의 정확한 `message` 문자열을 비롯한 코드·식별자·API 이름·명령·URL은
변경하지 않았다.

| 이슈 | 변경 파일 수 | 복원한 주요 범위 |
|---|---:|---|
| #411 | 272 | Java SDK의 DynamoDB, S3, SES, SNS, SQS, KMS, CloudWatch, Kinesis 및 공통 지원; AWS Kotlin SDK의 S3, DynamoDB, SES, SNS, SQS, KMS, CloudWatch, Kinesis 및 공통 지원 |
| #412 | 7 | Ktor SigV4와 S3 기본 통합 |
| #413 | 14 | Spring Boot S3/SQS 기본 통합 |

## 잔여 인벤토리

`src/main/kotlin`에서 KDoc(`/**`)가 있지만 한글이 전혀 없는 파일을 보수적인
잔여 후보로 집계했다. 이 검사는 기술 식별자가 포함된 한국어 KDoc을 실패로
분류하지 않지만, 영어 KDoc만 있는 파일은 빠뜨리지 않는다.

| 이슈 | 잔여 후보 | 판정 |
|---|---:|---|
| #411 | 57 files (`aws-java` 36, `aws-kotlin` 21) | EventBridge, Scheduler, Secrets Manager, Parameter Store, Bedrock Runtime, RDS IAM 등의 후속 package 작업 필요 |
| #412 | 65 files | Ktor CloudWatch, DynamoDB, EventBridge, IMDS, Kinesis, SES, SNS, STS, S3 advanced/access grants 등의 후속 작업 필요 |
| #413 | 93 files (`aws-spring-boot` 93, `aws-exposed` 0) | Exposed 범위는 완료됐지만 나머지 Spring Boot auto-configuration, properties, runtime/model KDoc 작업 필요 |

#414의 문서 경계를 같은 제외 규칙으로 다시 계산한 결과, 대상 문서 271개 중
한글이 전혀 없는 문서가 248개다. 주요 잔여 영역은 `CHANGELOG.md`,
`docs/governance/**`, `docs/lessons/**`, `docs/review/**`,
`docs/superpowers/**`, `docs/manual/templates/**`다.

## 제외 범위 검증

- README locale pair는 변경하지 않았다.
- `AGENTS.md`, `CLAUDE.md`와 다른 LLM-facing 운영문서는 변경하지 않았다.
- `docs/manual/en`과 `docs/manual/ko`는 각각 36개이며 상대 경로 누락은 0개다.
- `docs/manual/generated/**`, build/cache/runtime 산출물은 변경하지 않았다.

## 종료 판정

| 이슈 | 판정 | 근거 |
|---|---|---|
| #411 | 종료 불가 | 영어 전용 KDoc 후보 57개가 남음 |
| #412 | 종료 불가 | 영어 전용 KDoc 후보 65개가 남음 |
| #413 | 종료 불가 | Spring Boot 영어 전용 KDoc 후보 93개가 남음 |
| #414 | 종료 불가 | 한글이 없는 단일 언어 문서 248개가 남음 |
| #359 | 종료 불가 | 하위 이슈 #411-#414가 모두 열려 있고 수용 기준도 미충족 |

따라서 현재 live closeout 가능한 이슈는 없다. 각 잔여 영역을 review 가능한
package 또는 문서군 단위 후속 변경으로 완료하고, 대상 `compileKotlin`, 문서
링크 검사, manual parity 검사, `git diff --check`를 다시 통과한 뒤 이 판정을
갱신해야 한다.
