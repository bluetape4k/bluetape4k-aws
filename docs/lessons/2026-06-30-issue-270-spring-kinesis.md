# Issue #270 Spring Kinesis 교훈

## 배경

Issue #270에서는 `bluetape4k-aws-spring-boot`에 Spring Boot 4 Kinesis 지원을 추가했다.
저장소에는 이미 Java SDK v2 coroutine Kinesis helper와 Kotlin SDK Kinesis flow가 있었지만
Spring adapter는 일관되게 Java SDK v2 async client를 감싼다.

## 결정

`KinesisAsyncClient`와 `KinesisOperations`를 사용하고 Spring surface는 명시적인
operation으로 유지한다. Stream 생성, record 게시, shard iterator 조회, 제한된
`GetRecords`, 단일 shard의 cold `Flow<Record>`를 제공한다.

이 PR에서는 `@KinesisListener`나 checkpoint/lease management를 추가하지 않는다. 이
semantic은 application ownership, checkpoint storage, failure recovery, shard
coordination을 정의하므로 별도 설계가 필요하다.

## 결과

- Classpath와 `bluetape4k.aws.kinesis.enabled`로 보호하는 Kinesis 자동 구성 추가
- 이름 있는 request value와 설정 가능한 Flow polling/retry option 추가
- Conditional bean, property binding, request mapping, Flow coldness, 반복 collection,
  EOF, failure propagation, cancellation용 unit test 추가
- Create, put, describe, Flow collection용 Floci emulator 검증 추가
- 영문/한글 root/module README와 service coverage chart 갱신

## 검증

- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*Kinesis*' --no-configuration-cache`: 22개 통과
- `./gradlew :bluetape4k-aws-spring-boot:test --no-configuration-cache`: 243개 통과
- `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin --warning-mode all --no-configuration-cache --rerun-tasks`: BUILD SUCCESSFUL 확인
- `bluetape4k-aws-service-coverage-chart-05`의 SVG parse 및 PNG 재생성 통과

## 향후 작업

나중에 listener 지원을 추가한다면 annotation이나 container를 도입하기 전에 shard lease
coordination, checkpoint persistence, backpressure, retry/DLQ 동작, shutdown semantic을
먼저 설계한다.
