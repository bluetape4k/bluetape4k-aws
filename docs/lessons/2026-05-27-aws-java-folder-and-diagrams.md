# AWS Java 폴더 이름 및 다이어그램 글꼴 갱신

## 배경

공개 Gradle 모듈과 아티팩트는 `bluetape4k-aws-java`였지만 실제 `aws/` 모듈
directory는 여전히 이전 핵심 모듈 이름처럼 보였다. README 다이어그램에도 Helvetica로
렌더링한 오래된 Graphviz 결과가 남아 있었다.

## 결정

실제 directory의 이름을 `aws-java/`로 바꾸고 공개 Gradle 모듈은
`:bluetape4k-aws-java`로 유지한다. Node text에는 `Architects Daughter`, edge
label에는 `Comic Mono`를 사용해 DOT 근거에서 README 다이어그램 asset을 다시
생성한다.

## 결과

이제 Java, Kotlin, Exposed 모듈 README는 최신 아키텍처, flow, sequence
다이어그램을 사용한다. 루트 README 다이어그램과 chart asset은 예상한 글꼴 모음으로
다시 렌더링했다.

## 검증

- `./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-kotlin:compileKotlin :bluetape4k-aws-exposed:compileKotlin --no-daemon --max-workers=1`
- 변경한 SVG asset에 `xmllint --noout` 실행
- README PNG/SVG 링크 존재 여부 검사
- 글꼴 검사에서 루트 및 현재 변경한 DOT 또는 SVG 다이어그램 asset에
  Helvetica/Arial/sans-Serif가 없음을 확인

## 향후 지침

macOS에서 Font Book으로 설치한 글꼴을 확인할 때 `fc-match`에만 의존하지 않는다.
인계하기 전에 생성한 SVG의 `font-family` 값을 확인하고 렌더링한 PNG를 검사한다.
