# 스냅숏 검증 개발선

## 배경

이전 릴리스가 끝난 뒤에는 일치하는 upstream bluetape4k 및 Exposed 스냅숏을 사용하면서 다음 개발선을 열어 스냅숏을 검증해야 했습니다.

## 결정

`baseVersion=0.2.2`를 설정하고 `snapshotVersion=`는 비워 둡니다. 또한 `bluetape4k-bom:1.9.2-SNAPSHOT`과 `bluetape4k-exposed-bom:1.9.2-SNAPSHOT`을 사용합니다.

## 결과

저장소는 snapshot suffix를 `gradle.properties`에 기록하지 않고도 `publish-snapshot.yml`로 `0.2.2-SNAPSHOT`을 게시할 수 있습니다.

## 검증

스냅숏 검증 작업에서 확인할 예정입니다.
