# 이슈 #197 Ktor AWS Core 계획

날짜: 2026-05-26
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/197

## 작업

1. 공유 Ktor AWS 기본값을 추가한다.
   - `AwsKtorCore`, `AwsKtorDefaults`, customizer interface를 만든다.
   - Ktor application attribute에 기본값을 저장한다.

2. 서비스 통합을 연결한다.
   - `s3KtorClientOf(defaults = ...)`를 추가한다.
   - `SqsConsumer`가 공유 또는 서비스 로컬 설정에서 plugin 소유 client를 생성하고 닫게 한다.
   - `DynamoDbKtorPlugin`이 공유 region, endpoint, 자격 증명, HTTP engine, customizer를 상속하게 한다.

3. 테스트를 추가한다.
   - Core 기본값 저장.
   - S3 기본값 상속.
   - SQS 기본값 상속과 소유권.
   - DynamoDB 기본값 상속과 customizer 순서.

4. README 문서와 다이어그램을 갱신한다.
   - `README.md`와 `README.ko.md`를 갱신한다.
   - `aws-ktor-architecture-01.dot`, `.plain`, sketch SVG, 최종 SVG, PNG를 생성한다.
   - 다이어그램 완료를 선언하기 전에 렌더링된 PNG를 검사한다.

5. 검증한다.
   - `:bluetape4k-aws-ktor`를 compile한다.
   - 범위가 좁은 테스트를 실행한다.
   - 범위가 좁은 테스트를 통과하면 전체 `:bluetape4k-aws-ktor:test`를 실행한다.
   - `git diff --check`를 실행한다.
   - Claude advisor 코드 검토를 실행하고 P0/P1 = 0을 요구한다.

## 중단 조건

#197의 PR이 열려 있고, 로컬 검증을 통과한 증거, 갱신된 README 다이어그램 asset, lesson 항목이 있다.
