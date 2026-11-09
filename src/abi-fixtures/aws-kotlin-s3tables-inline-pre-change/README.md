# aws-kotlin S3 Tables inline evolution fixture

이 fixture는 이전에 inline된 S3 Tables request builder가 호출하던
검증 helper의 JVM bridge를 고정합니다. 소비자는 사전 변경 stub으로
컴파일하고, 실행 시 현재 `bluetape4k-aws-kotlin` JAR을 링크합니다.
