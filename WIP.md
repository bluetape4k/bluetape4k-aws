# WIP — bluetape4k-aws

Work in progress tracker for `bluetape4k-aws`.

---

## 모듈 현황

| 모듈 | 상태 | 설명 |
|------|------|------|
| `aws` | ✅ 이관 완료 | AWS SDK v2 Java wrapper (168 main + 76 test kt) |
| `aws-kotlin` | ✅ 이관 완료 | AWS Kotlin SDK wrapper (124 main + 87 test kt) |
| `aws-spring-boot` | 🚧 스켈레톤 | Spring Boot 4 자동설정 — awspring 미사용, 자체 구현 |
| `aws-ktor` | 🚧 스켈레톤 | Ktor 3.4.3 통합 |

---

## 계획된 작업

### aws-spring-boot (자체 구현, awspring 불사용)

[awspring/spring-cloud-aws](https://github.com/awspring/spring-cloud-aws)를 **참고**하되,
`bluetape4k-aws` 자체 구현으로 제공. Kotlin Coroutines 최우선.

| 서비스 | 이슈 | 우선순위 |
|--------|------|----------|
| S3 autoconfiguration + Coroutines extension | — | High |
| SQS listener / template + Coroutines | — | High |
| SNS publisher + Coroutines | — | Medium |
| DynamoDB repository + Coroutines | — | High |
| KMS encryption support | — | Medium |
| SES email sender | — | Low |
| Secrets Manager / Parameter Store | — | Medium |

### aws-ktor

Ktor 3.4.3 + AWS SDK v2 / AWS Kotlin SDK 통합.

| 기능 | 이슈 | 우선순위 |
|------|------|----------|
| Ktor client plugin: AWS request signing (SigV4) | — | High |
| S3 upload / download via Ktor client | — | High |
| SQS consume / publish via Ktor server | — | Medium |
| DynamoDB repository via Ktor server | — | Medium |

### 예제 (examples)

| 예제 | 이슈 |
|------|------|
| `examples/spring-boot-s3` — Spring Boot 4 + S3 | — |
| `examples/spring-boot-sqs` — Spring Boot 4 + SQS/SNS | — |
| `examples/spring-boot-dynamodb` — Spring Boot 4 + DynamoDB | — |
| `examples/ktor-s3` — Ktor + S3 | — |
| `examples/ktor-sqs` — Ktor + SQS | — |
| `examples/ktor-dynamodb` — Ktor + DynamoDB | — |

---

## awspring vs bluetape4k-aws 비교

| 기능 | awspring | bluetape4k-aws |
|------|----------|----------------|
| S3 Template | `S3Template` | TBD |
| SQS Listener (`@SqsListener`) | ✅ | TBD (Coroutines-native) |
| SNS Publisher | ✅ | TBD |
| DynamoDB Enhanced | ✅ | TBD (Coroutines-first) |
| Kotlin Coroutines | 부분적 | 완전 지원 |
| Ktor 지원 | ❌ | ✅ |
| AWS Kotlin SDK 지원 | ❌ | ✅ |
| Spring Boot 4 | ✅ (v4.0) | TBD |
| Reactor 의존성 | 있음 | 없음 (순수 Coroutines) |

---

## 이슈 트래커

- PR-A (완료): `bluetape4k-aws` 초기 설정 → #258
- PR-B (대기): `bluetape4k-projects`에서 `aws/**` 제거 → CI 통과 후
