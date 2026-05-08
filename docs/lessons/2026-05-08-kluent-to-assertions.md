# Lessons Learned — Kluent → bluetape4k-assertions 마이그레이션 (2026-05-08)

**관련 PR**: #22
**영향 모듈**: aws, aws-kotlin, aws-spring-boot

## L1: mavenLocal()이 SNAPSHOT 캐시를 오염시킨다

### 문제
`bluetape4k-projects`에서 `./gradlew publishBluetape4kPublicationToBluetape4kRepository` 실행 시 `~/.m2` 로컬 maven 저장소에도 artifact가 발행된다. `mavenLocal()`이 repositories 첫 번째에 있으면 Gradle이 항상 `.m2`의 오래된 jar를 우선 사용한다. Sonatype에 새 SNAPSHOT을 발행해도 로컬 `.m2`에 구버전이 남아 있으면 새 함수가 보이지 않는다.

### 교훈
새 SNAPSHOT 배포 후 컴파일 오류("Unresolved reference") 발생 시 먼저 `~/.m2/repository/io/github/bluetape4k/<module>/` 삭제 후 재빌드. `--refresh-dependencies`와 transforms 캐시 삭제는 이 문제를 해결하지 못한다.

---

## L2: ast-grep의 `$_` capture 변수는 rewrite에서 치환되지 않는다

### 문제
`ast-grep --pattern 'import org.amshove.kluent.$_' --rewrite 'import io.bluetape4k.assertions.$_'` 사용 시 `$_`가 rewrite에서 치환되지 않아 `import io.bluetape4k.assertions.` (trailing dot) 같은 깨진 import가 생성된다.

### 교훈
`$_`는 anonymous capture로 rewrite에 참조 불가. Named capture (`$NAME` uppercase)를 사용하거나, 간단한 패턴은 `sed`로 대체하는 게 안전하다.
