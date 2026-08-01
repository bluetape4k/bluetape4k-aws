# source로 검증한 README 다이어그램

## 배경

AWS class 다이어그램에 source tree에 존재하지 않는 임의의 extension-class label이 남아
있었습니다.

## 결정

생성 과정에서 채워 넣은 이름을 현재 source file/API anchor인
`DynamoDbAsyncTableExtensions`와 `SqsAsyncClientCoroutinesExtensions`로 교체합니다.

## 검증

게시하기 전에 다이어그램의 주요 class label을 source tree에서 모두 검색하고, SVG를
parse한 뒤 갱신한 SVG에서 PNG를 다시 rendering합니다.

## 향후 지침

Kotlin extension file에 임의의 `*Ext` class label을 만들지 않습니다. 실제 file name이나
receiver type과 function name 조합을 사용합니다.
