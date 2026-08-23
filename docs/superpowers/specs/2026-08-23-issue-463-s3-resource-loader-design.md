# Issue #463 S3 `ResourceLoader` 프로토콜·패턴 resolver 설계

상태: 승인된 설계 기록, 구현 전 사용자 spec 검토 대기

대상: `bluetape4k-aws-spring-boot`, Epic #500

## 1. 문제와 목표

현재 모듈에는 `S3Resource`가 있어 하나의 `S3ObjectLocation`을 Spring
`Resource`로 읽을 수 있다. 그러나 `s3://bucket/key` 표기를 Spring의
`ResourceLoader` 계약에 연결하는 등록 지점과, 한 bucket 안에서 여러 객체를
찾는 `ResourcePatternResolver`가 없다. 그 결과 `@Value("s3://...")`,
`ApplicationContext.getResource(...)`, 패턴 기반 설정 파일 로딩을 호출자가
직접 AWS SDK와 변환 코드로 연결해야 한다.

Issue #463의 목표는 기존 읽기 전용 `S3Resource`를 재사용하면서 다음 두
계약을 제공하는 것이다.

1. 정확한 `s3://bucket/key` 위치는 Spring `ResourceLoader`의 protocol
   resolver로 해석한다.
2. `s3://bucket/path/*.json`, `s3://bucket/path/**` 패턴은 명시된 단일
   bucket의 `ListObjectsV2` 페이지를 모두 읽고 `AntPathMatcher`로 필터링한다.
3. bucket을 여러 개 열거하거나 bucket 이름에 wildcard를 적용하지 않는다.
   권한 범위와 비용을 호출한 문자열의 한 bucket으로 고정한다.
4. 기존 S3 endpoint, region, credentials, path-style 설정과 Floci 기반
   테스트 경로를 그대로 사용한다.

성공 기준은 승인된 범위의 코드·테스트·한국어 문서가 구현 계획으로
추적 가능하고, 구현 전 이 문서와 독립 리뷰가 P0/P1 없이 수렴하는 것이다.

## 2. 현재 근거와 적용 범위

### 2.1 저장소 근거

| 근거 | 확인된 사실 | 설계 영향 |
|---|---|---|
| `aws-spring-boot/.../s3/S3Resource.kt` | sync `S3Client`로 `headObject`, `getObject`를 수행하고, missing object만 `exists() == false`로 처리한다. | 새 resolver는 `S3Resource`의 stream·metadata·missing-object 의미를 재정의하지 않는다. |
| `S3ObjectLocation.kt` | bucket/key가 비어 있지 않은 직렬화 가능한 값이며 `s3://bucket/key`로 출력된다. | parser 결과는 이 값으로 정규화하고 빈 key를 거부한다. |
| `S3AutoConfiguration.kt` | `S3Client`, `S3AsyncClient`, `S3Presigner`를 조건부로 만들며 `S3Properties`의 endpoint·region과 credentials provider를 적용한다. | 새 auto-configuration은 기존 `S3Client`를 주입받고 별도 client를 만들지 않는다. |
| `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | S3 관련 auto-configuration이 명시적으로 등록된다. | resolver auto-configuration을 기존 S3 auto-configuration 다음에 별도 등록한다. |
| `aws-spring-boot/build.gradle.kts` | AWS S3 SDK는 `compileOnly`, 테스트에서는 `testImplementation`으로 이미 제공된다. | 새 의존성을 추가하지 않는다. |
| 과거 S3 설계 문서 | awspring을 도입하지 않고 sync resource bridge와 `ApplicationContextRunner`를 사용한다. | 동일한 의존성·테스트 방향을 유지한다. |

### 2.2 외부 근거

- Spring [`ProtocolResolver`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/io/ProtocolResolver.html)는 `ResourceLoader`가 알 수 없는 protocol을 resolver 체인으로 위임하는 계약이다.
- Spring [`ResourcePatternResolver`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/io/support/ResourcePatternResolver.html)는 `Resource[]`와 `IOException`을 반환하는 패턴 계약이다.
- Spring [`PathMatchingResourcePatternResolver`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/io/support/PathMatchingResourcePatternResolver.html)는 기존 classpath/file 패턴의 위임 대상이다.
- AWS SDK v2 [`ListObjectsV2Iterable`](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/paginators/ListObjectsV2Iterable.html)는 페이지 단위 S3 listing을 반복하는 sync paginator다.
- Spring Cloud AWS의 [S3 reference](https://github.com/awspring/spring-cloud-aws/blob/main/docs/src/main/asciidoc/s3.adoc)는 `s3://` 표기와 pattern resolver 사용 사례를 보여 주지만, 전체 bucket 검색은 비용·성능·권한 위험이 있으므로 본 설계는 literal bucket 한 개로 제한한다.

공식 문서는 Spring 계약과 AWS paginator의 동작 근거로만 사용한다. awspring
구현을 복사하거나 의존성으로 추가하지 않는다.

## 3. 승인된 경계

### 포함

- `s3://bucket/key` exact protocol resolution
- literal bucket 하나를 대상으로 한 `*`, `?`, `**` Ant pattern
- paginator 전체 소비, prefix 최적화, key 오름차순 정렬, 빈 결과 배열
- 기존 `S3Resource` 재사용과 caller-owned `InputStream` 수명
- 기존 `S3AutoConfiguration`의 enabled/class/client 조건과 custom bean backoff
- parser, resolver, auto-configuration, Floci S3 통합 및 Spring context 테스트
- `README.md`, `README.ko.md`, public KDoc와 호출 예시

### 제외

- `s3://**/path/*.txt` 같은 cross-bucket 또는 bucket enumeration
- prefix가 비어 있는 `s3://bucket/*.json`, `s3://bucket/**` 같은 root-level
  bucket 전체 listing
- bucket 전체를 내려받은 뒤 로컬에서 검색하는 동작
- 객체 생성·삭제·쓰기 stream·`Resource` output stream
- AWS CRT client auto-configuration, 새로운 AWS SDK 또는 awspring 의존성
- S3 Vectors, Access Grants, KMS 동작 변경
- release, publish, tag, GitHub merge와 같은 외부 irreversible action

## 4. 선택한 설계: 별도 protocol resolver와 pattern resolver

### 4.1 구성 요소

새로운 `S3ResourceAutoConfiguration`을 기존 `S3AutoConfiguration` 뒤에
등록한다. 구성 요소의 책임은 다음과 같다.

- `S3ProtocolResolver`: `s3://` exact location만 파싱해 `S3Resource`를
  반환한다. Spring `ConfigurableApplicationContext.addProtocolResolver(...)`
  를 사용하는 작은 infrastructure `BeanFactoryPostProcessor`를 통해 bean
  인스턴스화와 `@Value` 해석보다 먼저 등록한다. 이 post-processor는
  `S3Client`를 직접 주입하거나 조회하지 않고 `ObjectProvider<S3Client>`를
  가진 resolver를 지연 선택하므로 S3 client를 조기에 만들지 않는다.
- `S3ResourcePatternResolver`: `ResourcePatternResolver`를 구현한다.
  `s3://` 패턴은 자체 listing 경로로 처리하고, 그 외 문자열은 기존
  `PathMatchingResourcePatternResolver`에 위임한다. 기본 bean 이름은
  `s3ResourcePatternResolver`로 고정하며, 호출자는 `S3ResourcePatternResolver`
  concrete type으로 주입하거나 `@Qualifier("s3ResourcePatternResolver")`
  를 붙인 `ResourcePatternResolver`로 주입한다.
- `S3ResourceLocationParser`: protocol, literal bucket, key/pattern,
  wildcard 위치, percent-escape를 한 곳에서 검증한다. parser는 네트워크를
  호출하지 않는다.
- 기존 `S3Resource`: exact 결과의 `exists`, metadata, `getInputStream`을
  그대로 제공한다.

기본 resolver는 `S3ProtocolResolver`와 `S3ResourcePatternResolver`라는
S3 전용 확장 타입으로 식별한다. 두 타입은 custom replacement가 상속할 수
있는 계약이며, unrelated `ProtocolResolver`나 unrelated
`ResourcePatternResolver` bean은 backoff 조건에 영향을 주지 않는다. registrar는
선택된 S3 protocol resolver를 한 번만 context chain에 추가하고, custom
replacement도 같은 경로로 등록한다.

`S3ResourcePatternResolver`의 non-S3 delegate는 현재
`ApplicationContext`를 `PathMatchingResourcePatternResolver(applicationContext)`
로 감싼 하나의 인스턴스다. 따라서 `getResource`, `getClassLoader`, non-S3
`getResources`가 같은 context/resource-loader와 protocol chain을 공유한다.

`ApplicationContext.getResource("s3://bucket/key")`와 `@Value`의 exact
해석은 Spring context에 등록된 protocol resolver가 담당한다. 패턴 결과는
`S3ResourcePatternResolver` concrete type 또는 고정 qualifier가 붙은
`ResourcePatternResolver` bean을 주입해 얻는다. unrelated
`ResourcePatternResolver` bean이 함께 있어도 `@Primary`로 기존 주입 의미를
바꾸지 않는다. Spring
`AbstractApplicationContext`가 내부 `PathMatchingResourcePatternResolver`를
생성하는 구현 세부사항을 reflection으로 바꾸지 않으므로,
`ApplicationContext.getResources("s3://bucket/*.json")`를 자동으로 가로채는
계약은 이 기능의 범위에 포함하지 않는다. 문서와 테스트는 이 두 사용 경로를
구분해 오용을 막는다.

### 4.2 Exact 위치 문법

허용하는 exact 표기는 다음과 같다.

```text
s3://<literal-bucket>/<object-key>
```

- scheme은 대소문자를 구분하지 않고 `s3`만 허용한다.
- authority는 비어 있지 않은 하나의 literal bucket이어야 한다. userinfo(`@`),
  port(`:`), `*`, `?`, `[`, `]`, `/`, query, fragment는 bucket 또는 URI
  전체에서 허용하지 않는다.
- object key는 `/`를 포함할 수 있고 trailing slash도 보존한다. bucket root인
  `s3://bucket`과 빈 key인 `s3://bucket/`은 거부한다.
- percent escape는 정확히 한 번 decode한다. malformed escape는 조용히
  fallback하지 않고 `IllegalArgumentException`으로 거부한다. `%2F`, `%20`
  같은 escaped key 문자는 decoded key에 보존한다.
- decoded key가 빈 문자열이 되거나 URI authority와 path가 모호해지는 입력은
  거부한다.

알 수 없는 scheme과 non-S3 location은 protocol resolver에서 `null`을
반환해 기존 Spring resolver 체인에 위임한다. `s3:` scheme인데 문법이
잘못된 경우에는 오타를 숨기지 않도록 명시적인 입력 오류를 낸다.

### 4.3 Pattern 위치 문법과 단일 bucket 안전장치

허용하는 pattern은 다음처럼 bucket을 literal로 고정한다.

```text
s3://<literal-bucket>/<ant-path-pattern>
```

`*`와 `?`는 한 path segment 안의 일반 Ant wildcard이고 `**`는 여러
segment를 가로지를 수 있다. Spring `AntPathMatcher`가 지원하지 않는
character-class 표기 `[]`는 pattern에서 거부한다. pattern의 bucket authority에 wildcard가
들어가거나 userinfo·port·bucket 구분자를 여러 개 표현하면 즉시 거부한다.
따라서 한 번의 호출은 항상 한 bucket에 대해서만 `ListObjectsV2`를 실행한다.

패턴 처리 순서는 다음과 같다.

1. parser가 raw path와 decoded path를 함께 보존하면서 literal bucket과
   pattern을 검증하고, 첫 wildcard 앞의 key prefix를 계산한다. wildcard가
   없으면 exact resolver 경로로 위임한다.
   첫 wildcard 앞 prefix가 비어 있으면 bucket 전체 listing을 막기 위해
   즉시 거부한다. 호출자는 `config/*.json`처럼 비어 있지 않은 prefix를
   지정해야 한다.
2. `ListObjectsV2Request(bucket = literal bucket, prefix = prefix)`를 만들고
   `listObjectsV2Paginator`를 모든 페이지에 대해 소비한다. delimiter를
   사용하지 않아 `**`의 하위 경로를 누락하지 않는다.
3. 각 object key를 path matcher에 적용한다. `%2A`, `%3F`, `%5B`, `%5D`처럼
   percent-encoded 된 metacharacter는 wildcard 토큰이 아니라 literal key
   문자다. parser는 raw escape provenance를 보존한 token을 만들고, matcher
   앞뒤의 tokenization 계층이 literal metacharacter를 보호한 상태에서
   `AntPathMatcher`를 실행한다. raw `+`는 query form처럼 공백으로 바꾸지
   않는다.
4. 일치한 key를 exact string identity로 중복 제거하고
   locale-independent `String.compareTo` 오름차순으로 정렬한다.
5. key마다 기존 `S3Resource(s3Client, S3ObjectLocation(bucket, key))`를
   만들고 반환한다. 일치하는 key가 없으면 공유 가능한 empty array를
   반환한다.

AWS list 결과가 이미 정렬되어 있다는 가정에 의존하지 않는다. 모든 pattern은
비어 있지 않은 prefix와 literal bucket 한 곳에서만 실행되며, 전체 AWS 계정이나
모든 bucket을 검색하지 않는다.

### 4.4 오류·권한·수명

- parser 오류는 `IllegalArgumentException`으로 즉시 반환한다.
- `ListObjectsV2`의 `AccessDenied`, 403, endpoint/region 오류, network 오류와
  paginator 중간 실패는 삼키거나 빈 결과로 바꾸지 않는다. checked API
  경계에서는 bucket과 prefix만 포함한 `IOException`으로 감싸고 원인 예외를
  보존한다. credentials, authorization header, secret 값은 메시지·로그에
  넣지 않는다.
- exact `S3Resource`의 `exists()`는 기존 동작을 유지해 object 또는 bucket
  부재를 나타내는 `NoSuchBucket`, `NoSuchKey`, `NotFound`, 404를 false로
  취급한다. 그 밖의 permission/network 오류는 호출자에게 전파한다.
- resolver는 `S3Client`를 소유하거나 close하지 않는다. client 수명은 기존
  auto-configuration 또는 애플리케이션이 관리한다.
- `getInputStream()`이 반환한 stream은 호출자가 close한다. resolver는
  stream을 미리 열거나 버퍼링하지 않는다.
- auto-configured `S3Resource`는 owning `ApplicationContext`와
  `S3Client` 수명 안에서 사용하는 객체다. context 종료 후 resource를 다시
  읽는 동작은 지원하지 않으며, 종료 전에 caller가 stream을 닫아야 한다.
- retry, cache, bucket discovery를 새로 넣지 않는다. 한 호출의 listing
  결과만 사용해 stale cache와 권한 누적을 피한다.
- 동기 `ResourcePatternResolver` 호출의 blocking 시간과 transport timeout은
  주입된 `S3Client` 설정을 따른다. 새 executor, retry, cancellation layer를
  만들지 않는다.

### 4.5 Auto-configuration 조건과 backoff

`S3ResourceAutoConfiguration`은 다음 조건을 모두 만족할 때만 활성화한다.

- 기존 AWS 공통 enabled 조건과 `bluetape4k.aws.s3.enabled=true`
  (미설정 시 기존 S3 기본값)를 따른다.
- `S3Client`, Spring resource API가 classpath에 있고 기존 `S3Client` bean이
  제공된다.
- 선언부는 `@AutoConfiguration(after = [S3AutoConfiguration::class])`와
  `@ConditionalOnBean(S3Client::class)`를 사용하고, S3 SDK optional classpath
  guard는 기존 name-based `@ConditionalOnClass` 패턴을 따른다. imports 파일의
  줄 순서는 보조 정보이며 auto-configuration ordering을 대신하지 않는다.
- protocol/pattern resolver의 사용자 정의 bean이 있으면 해당 기본 bean을
  만들지 않는다. 사용자 resolver 등록은 자동 등록과 중복되지 않아야 한다.

기존 `S3AutoConfiguration`이 비활성화되거나 S3 SDK가 없으면 resolver도
등록되지 않는다. endpoint, region, credentials, path-style은 새 properties나
client를 만들지 않고 기존 `S3Properties`와 주입된 `S3Client`를 그대로
사용한다. resolver 조건은 S3 전용 확장 타입의 missing-bean을 기준으로
판정하며, 사용자가 custom `S3ProtocolResolver` 또는
`S3ResourcePatternResolver`를 등록하면 해당 기본 구현만 backoff한다. custom
resolver와 무관한 다른 protocol resolver는 함께 동작해야 한다.
pattern 기본 bean은 `s3ResourcePatternResolver` 이름을 유지하고
`@Primary`로 선언하지 않는다. 따라서 unrelated `ResourcePatternResolver`
bean이 공존해도 concrete type 또는 qualifier 주입만 모호하지 않게 한다.

auto-configuration imports에는 새 구성을 기존 S3 항목 다음에 추가한다.

## 5. 대안과 기각 사유

| 대안 | 장점 | 기각 사유 |
|---|---|---|
| A. protocol과 pattern을 별도 resolver로 제공 | exact와 listing의 책임·오류·수명을 분리하고 기존 Spring 위임을 보존한다. | 선택안. pattern은 주입 가능한 resolver라는 사용 경계를 문서화해야 한다. |
| B. `ApplicationContext`의 전역 resolver를 reflection 또는 context subclass로 교체 | `ApplicationContext.getResources`까지 한 호출로 보일 수 있다. | Boot auto-configuration이 이미 생성한 context의 private lifecycle에 의존하며 Spring 버전 호환성과 초기화 순서를 깨뜨린다. 권한·오동작 범위를 넓힌다. |
| C. resolver bean만 노출하고 protocol 자동 등록은 하지 않음 | 구현이 단순하고 side effect가 작다. | `@Value`와 `ApplicationContext.getResource`의 표준 exact 계약을 충족하지 못한다. |

대안 A는 Spring이 공개한 extension point만 사용하면서 사용자가 실수로
cross-bucket listing을 요청하는 경로를 차단한다. pattern resolver를
`ApplicationContext` 내부 구현에 강제로 주입하지 않는 것은 의도적인
호환성 경계다.

## 6. 실패 모드와 완화책

| 실패 모드 | 관찰되는 결과 | 완화·검증 |
|---|---|---|
| bucket wildcard, 다중 bucket 또는 빈 prefix 입력 | parser가 즉시 입력 오류를 반환하고 AWS 호출이 0회다. | parser negative test와 단일 bucket 호출 횟수 검증을 둔다. |
| 권한이 없는 `ListObjectsV2` | 403/`AccessDenied`가 빈 배열로 위장되지 않고 원인과 함께 전파된다. | MockK 또는 AWS 응답 fixture로 예외 전파를 검증하고 secret 비노출을 확인한다. |
| paginator 중간 페이지 실패 | 앞 페이지 일부를 성공으로 반환하지 않고 전체 호출이 실패한다. | 다중 페이지 후 실패 fixture와 순차 Floci 시나리오를 둔다. |
| key에 escaped slash·공백·wildcard가 포함됨 | exact key는 decode 후 보존되고, escaped wildcard는 literal로 매칭되며 raw `+`는 보존된다. malformed escape와 지원하지 않는 `[]` pattern은 거부된다. | parser와 matcher 경계 테스트를 별도로 둔다. |
| missing object와 head 권한 오류 혼동 | object/bucket 부재 또는 404만 `exists == false`, permission/network는 예외다. | 기존 `S3Resource` 회귀 테스트와 resolver 통합 테스트를 함께 실행한다. |
| custom resolver와 기본 resolver 중복 | 사용자 S3 전용 bean이 우선하고 protocol 등록이 두 번 일어나지 않으며, unrelated resolver는 backoff를 유발하지 않는다. | `ApplicationContextRunner` custom replacement·unrelated coexistence 및 등록 횟수 테스트를 둔다. |
| context 종료 뒤 resource 재사용 | 이미 닫힌 client를 참조해 읽기가 실패할 수 있다. | context lifecycle test와 caller-owned stream close 문서를 둔다. |

## 7. 테스트와 acceptance traceability

구현 계획은 아래 acceptance를 각각 RED/GREEN 증적으로 연결해야 한다.

| Acceptance | 테스트 증거 |
|---|---|
| exact `s3://bucket/key`가 `Resource`로 로드되고 stream close가 caller 소유임 | `S3ProtocolResolverTest`, `ApplicationContextRunner` exact integration, 기존 `S3Resource` stream/metadata 회귀 테스트. resolve 중 I/O·close가 없고 caller close만 일어나는 상호작용을 검증한다. |
| `@Value`와 `ApplicationContext.getResource` exact 경로가 동작함 | protocol resolver가 context 초기화·placeholder 처리 전에 등록되고 S3 client를 조기에 만들지 않는 context test. `FilteredClassLoader`로 S3 SDK 부재와 `bluetape4k.aws.s3.enabled=false`도 각각 검증한다. |
| `*`, `**`, prefix와 paginator 전체 페이지가 동작함 | parser/prefix unit test와 multi-page `ListObjectsV2Iterable` fixture. no-delimiter, 중복 제거, 중간 페이지 실패 시 partial result 금지와 `IOException` cause 보존을 검증한다. |
| key 순서와 empty result가 안정적임 | unsorted page fixture, duplicate key fixture, no-match empty-array assertion. paginator는 한 호출에서 한 번만 소비하고 retry하지 않는 상호작용을 검증한다. |
| cross-bucket 및 root-level 전체 listing이 불가능함 | wildcard authority, 다중 bucket 문법, 빈 prefix, zero AWS-call negative test |
| endpoint·region·credentials와 Floci가 동작함 | `ApplicationContextRunner` properties test와 `*AwsEmulatorTest` 이름의 `-Dbluetape4k.aws.emulator=floci` S3 integration test. Floci capability gap에 한해 LocalStack fallback을 허용하고, emulator test는 공유 Docker 자원을 위해 `--max-workers=1`로 순차 실행한다. |
| non-S3 location은 기존 resolver에 위임됨 | `PathMatchingResourcePatternResolver(applicationContext)` delegate를 통한 classpath/file/custom-protocol delegation test와 concrete type/qualifier 주입 test |
| disabled/missing SDK/custom bean backoff가 보장됨 | auto-configuration positive, existing S3 disabled, `FilteredClassLoader` missing class, custom S3 resolver replacement, unrelated resolver coexistence, duplicate protocol registration backoff test |
| 사용법과 unsupported scope가 공개 문서에 반영됨 | `README.md`·`README.ko.md` 구조 비교와 예시 검토 |

parser는 public API로 노출하지 않는 `internal` 계약으로 둔다. 최소 계약은
`parseExact(String): S3ObjectLocation`, `parsePattern(String): S3Pattern`이며,
`S3Pattern`은 literal bucket, non-empty prefix, decoded key token, wildcard
kind를 보존한다. 기존 `AwsConfigDataLocationParser`의 internal 직접 테스트
패턴을 따른다. parser unit test는 malformed escape, query/fragment,
`%2A/%3F/%5B/%5D`, raw `+`, escaped slash, unsupported `[]`, trailing slash,
root rejection을 각각 하나의 assertion으로 분리한다.

실제 AWS credential 기반 smoke test는 자격 증명이 제공되지 않는 환경에서
N/A로 기록한다. N/A는 Floci 및 mock/fixture 검증을 대체하지 않는다.

검증 순서는 parser/unit → `ApplicationContextRunner` → Floci emulator smoke →
전체 모듈 테스트로 고정한다. Gradle 작업은 공유 emulator 자원을 고려해
동시에 실행하지 않는다. 구현 후 최소 증거 명령은 다음 순서로 실행한다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test -PskipAwsEmulatorTests=true --no-daemon
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests "*S3ResourceLoaderAwsEmulatorTest" \
  -Dbluetape4k.aws.emulator=floci --max-workers=1 --no-daemon
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon
./gradlew detekt
./gradlew build -x test --parallel
```

첫 명령은 parser와 `ApplicationContextRunner`를 포함한 non-emulator 검증이고,
두 번째 명령은 Floci smoke만 실행한다. Floci capability gap에 한해 같은
대상으로 LocalStack fallback을 허용한다. 세 번째 명령은 전체 모듈 test다.
각 명령의 기대 결과와 skip/실패 원인을 plan에 기록한다. S3 runtime SDK가
`compileOnly`라는 계약은 resolved POM 또는 Gradle metadata에서 확인하고,
새 dependency가 추가되지 않았는지 검증한다.

## 8. 호환성·롤백·문서 계약

- 기존 `S3Resource`, `S3ObjectLocation`, `S3Operations`, `S3Properties`의
  public signature를 바꾸지 않는다.
- 새 auto-configuration이 꺼지거나 classpath 조건이 충족되지 않으면 현재
  동작과 동일하다. resolver 등록 실패는 기존 S3 client bean 생성을 가리지
  않도록 별도 조건과 테스트로 격리한다.
- 구현 중 parser/listing 계약이 불명확해지면 source code보다 이 spec의
  승인된 경계로 되돌아가 plan을 수정하고, public contract가 바뀌면 사용자
  spec 승인을 다시 받는다.
- rollback은 새 resolver class와 auto-configuration import 및 테스트·문서를
  함께 되돌리는 단위로 수행한다. 기존 S3 client/config는 롤백 대상이 아니다.
- `aws-spring-boot/README.md`와 `README.ko.md`는 동일한 heading·예시 구조를
  유지하고, `docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md`
  와 `docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md`
  도 exact/pattern 사용 경계와 IAM 단일 bucket 예시를 함께 반영한다. 한국어
  설명은 literal translation이 아니라 caller가 제한사항을 이해할 수 있는
  자연스러운 문장으로 작성한다.

## 9. 설계 DoD

- [x] Issue #463와 Epic #500의 live 범위, 단일 bucket 제약, 기존 S3 근거를
      문서화했다.
- [x] protocol exact와 pattern listing의 공개 사용 경로를 분리했다.
- [x] paginator, prefix, wildcard, 정렬, empty result, escaped key, 오류·권한
      전파 semantics를 고정했다.
- [x] auto-configuration 조건, custom backoff, client 수명과 stream 소유권을
      고정했다.
- [x] 실행 가능한 실패 모드, acceptance-to-test traceability, rollback을
      기록했다.
- [x] cross-bucket search, CRT, output stream/converter, release/publish는
      명시적으로 제외했다.
- [ ] 구현 source, RED/GREEN, Floci integration, Detekt, 전체 build는
      구현·검증 단계에서 수행한다.
- [ ] 사용자 spec 검토와 이후 implementation plan 승인은 아직 남아 있다.

### 구현 단계 N/A

- 전역 bucket 검색: 단일 bucket 경계에 의해 해당 없음
- 신규 dependency/catalog 변경: 기존 Spring/AWS SDK와 `S3Resource` 재사용
- release/publish/tag/merge: 현재 설계 단계 범위 밖
- 실제 AWS smoke: credential 부재 시 N/A, Floci·fixture 검증은 필수

이 문서는 승인된 설계를 구현 가능한 계약으로 고정한 뒤, 사용자 spec
검토가 끝날 때까지 코드 구현을 시작하지 않는 기준 문서다.
