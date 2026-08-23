# Issue #314 Lambda 에뮬레이터 준비 증거

작성일: 2026-08-23

## 실행 환경

구현 전에 공유 Docker 자원과 현재 에뮬레이터 이미지 상태를 확인했다.

```text
colima: running (macOS Virtualization.Framework)
arch: aarch64
runtime: docker
mountType: virtiofs
docker socket: unix:///Users/debop/.colima/default/docker.sock
docker context: default
Docker Server=29.2.1 Containers=1 Images=199
```

현재 로컬 이미지에는 다음 AWS 에뮬레이터와 Testcontainers 보조 이미지가 있다.

```text
floci/floci:1.6.0
floci/floci:1.5.27
floci/floci:1.5.17
localstack/localstack:4
testcontainers/ryuk:0.14.0
testcontainers/ryuk:0.13.0
testcontainers/ryuk:0.12.0
testcontainers/sshd:1.3.0
```

## Lambda capability 판정

현재 `bluetape4k-aws`의 공용 테스트 기반과 Java/Kotlin 테스트를 검색했다. S3,
DynamoDB, SQS 등 기존 서비스용 에뮬레이터 기반은 있으나, Lambda function을
생성·배포하고 호출할 수 있는 재사용 fixture는 없다.

`bluetape4k-projects`의 에뮬레이터 마이그레이션 명세도 Floci에서 Lambda를
stateful 서비스로 분류하며 신규 도입 범위에서 제외한다. 따라서 이번 Issue
#314에서는 function 배포, IAM 변경, Docker socket 노출, 컨테이너 기반 Lambda
smoke를 수행하지 않는다.

결정:

- 기본 emulator 정책은 계획대로 Floci-first를 유지한다.
- Lambda smoke 테스트는 `lambdaSmoke` 명시 옵션과 function name/region 입력이
  모두 있을 때만 실행한다.
- 현재 입력과 fixture가 없으므로 이번 로컬 검증에서는 smoke를 `N/A`로 분류한다.
- SDK codec, request builder, client lifecycle, raw response 보존은 deterministic
  unit/consumer 검증으로 완료한다.

## 재검증 명령

```bash
colima status
docker context show
docker info --format 'Server={{.ServerVersion}} Containers={{.Containers}} Images={{.Images}}'
docker images --format '{{.Repository}}:{{.Tag}}' | rg -i 'floci|localstack|testcontainers' || true
rg -n "Lambda|lambda|FlociServer|LocalStackServer" \
  aws-java/src/test aws-kotlin/src/test \
  ../../../bluetape4k-projects/docs/superpowers/specs/2026-04-26-aws-emulator-migration-design.md
```

