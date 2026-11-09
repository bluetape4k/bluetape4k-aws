# aws-java SFN inline evolution fixture

이 fixture는 이전 `SfnExtensions.kt`의 public helper를 inline한 소비자가
현재 JAR에서 계속 링크되는지 검증합니다. 소비자는 사전 변경 stub을 기준으로
컴파일하고, 실행 시에는 현재 `bluetape4k-aws-java` JAR만 사용합니다.
