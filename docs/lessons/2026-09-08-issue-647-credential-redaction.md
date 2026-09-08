# #647 진단 문자열의 credential 노출 방지

`ToStringBuilder.add`는 값을 추가하는 순간 객체의 `toString()`을 호출한다. 최종 출력의 길이를 제한해도 provider와 customizer의 진단 문자열 실행을 막을 수 없다.

`AwsKtorDefaults`는 provider, HTTP engine, clock의 설정 여부와 customizer 개수만 출력한다. 자격증명 조회나 collaborator의 문자열 변환을 실행하지 않는다. equals/hashCode와 실행 시 설정 전달은 그대로 유지한다.

회귀 테스트는 비밀값을 반환하는 collaborator로 일반 및 길이 제한 문자열을 검사한다. 수정 전 두 테스트가 실제 실패했고, 수정 후 결과는 PR 검증 기록에 남긴다.

독립 리뷰에서 endpoint 문자열에도 userinfo/query/path 비밀값이 들어갈 수 있음을 확인했다. provider만 마스킹하면 충분하다는 범위를 바로잡아 endpoint도 설정 여부만 출력한다. endpoint marker 회귀가 실제 실패한 뒤 수정했다. 모든 외부 입력 문자열을 진단 출력에 추가할 때 자격증명 포함 가능성을 함께 점검한다.

검증: endpoint 보강 후 모듈 test/build/detekt를 재실행해 262개 테스트가 실패·건너뜀 없이 통과했다. javap -public으로 기준 develop의 AwsKtorDefaults와 공개 JVM 서명이 같은지 확인했다. worktree 문서는 공유 GNO 컬렉션에서 의도적으로 제외되므로 canonical 색인은 머지 후 수행한다.
