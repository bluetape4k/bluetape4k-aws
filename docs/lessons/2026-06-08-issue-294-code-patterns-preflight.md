# Issue 294 Code Patterns 사전 검증

## 배경

초기 cleanup 범위가 너무 좁았다. 사용자는 나열한 example이 signal일 뿐이고 실제 목표는
0.4.0 전에 저장소 전체에서 `bluetape4k-code-patterns`를 준수하고 bluetape4k ecosystem
재사용을 강화하는 것이라고 명확히 했다.

## 결정

- 전체 workflow gate를 유지한다. Spec review P0/P1=0, plan review P0/P1=0을 차례로
  확인한 뒤 구현한다.
- Data class 직렬화, coroutine blocking 경계, raw assertion, ecosystem helper 재사용을
  별도 scan lane으로 취급한다.
- 교체가 정확히 일치하는 곳에는 `bluetape4k-jdbc`의 `hikariDataSourceOf`를 사용한다.
  RDS IAM `DriverManager` custom `DataSource`를 같은 PR에서 위험한 abstraction으로 억지로
  바꾸지는 않는다.

## 결과

- `AwsSecretString`은 이제 보호된 factory를 통해 생성한다.
- 게시하는 production 및 변경한 test data class는 `serialVersionUID`와 함께
  `Serializable`을 구현한다.
- Ktor 종료 경로와 선택한 blocking call은 `runInterruptible(Dispatchers.IO)`을 사용한다.
- 변경했거나 중요한 test의 raw assertion import를 `bluetape4k-assertions`로 교체했다.
- 저장소 전체 Kotlin source의 `!!` scan은 이제 0건을 반환한다. AWS SDK nullable response
  test는 `shouldNotBeNull()`을 사용한다.
- 남은 RDS IAM JDBC abstraction은 후속 #295에서 추적한다.

## 검증

- 게시 module compile: PASS
- Exposed/Kotlin/Spring 대상 test: PASS
- 전체 `aws-kotlin:test`: PASS, 489개 통과 + 12개 pending
- Ktor 대상 suite: PASS, 테스트 150개
- Static scan: marker가 없는 data class 0, raw assertion import 0, `!!` 0,
  중첩 `withContext(IO)+runInterruptible` 0

## 향후 규칙

광범위한 release 전 cleanup에서는 사용자가 든 example에서 멈추지 않는다. Skill rule에서
scan lane을 구성해 안전한 P0/P1 및 확신도 높은 P2 항목을 수정하고, 동작을 바꾸는
ecosystem abstraction은 후속 issue로 만든다.
