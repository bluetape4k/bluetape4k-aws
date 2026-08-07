---
manualId: "bluetape4k-aws-bom"
id: "bluetape4k-aws-bom"
title: "AWS BOM"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-aws-bom"
sourceDir: "bom"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-bom
---

# AWS BOM

> 0.5.0 릴리스 소스를 기준으로 작성한 라이브러리 매뉴얼입니다.

## 제공하는 기능 {#problem}

공개된 bluetape4k AWS 라이브러리의 버전을 맞추는 저장소 전용 BOM입니다. 일반 애플리케이션에서는 이 BOM보다 중앙 `bluetape4k-dependencies` 플랫폼을 사용하세요.

## 사용하기 좋은 경우 {#when-to-use}

다른 bluetape4k 저장소와 분리해 AWS 라이브러리만 관리해야 하는 빌드에서 사용합니다.

## 의존성 좌표 {#coordinates}

애플리케이션에서는 중앙 BOM 버전 하나만 선택합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-bom")
}
```

AWS 서비스 SDK는 `compileOnly` 정책 때문에 실제 사용하는 모듈을 런타임 의존성으로 별도 추가해야 합니다.

## 핵심 개념 {#concepts}

BOM에는 런타임 코드가 없고 의존성 제약만 있습니다. `bluetape4k-dependencies`가 이 BOM과 다른 저장소 BOM을 하나의 검증된 묶음으로 합칩니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:s3")
}
```

애플리케이션이 직접 선택하는 bluetape4k 버전은 `<version>` 하나뿐입니다.

## 작업별 API {#api-by-task}

플랫폼을 가져온 뒤 `bluetape4k-aws-java`, `-kotlin`, `-exposed`, `-spring-boot`, `-ktor`에는 버전을 쓰지 않습니다.

## 권장 패턴 {#patterns}

client와 백그라운드 작업의 소유자를 한 곳으로 정하고, region·credentials·endpoint를 호출마다 만들지 말고 애플리케이션 경계에서 구성하세요.

## 연동 {#integrations}

BOM은 bluetape4k 라이브러리 버전을 맞춥니다. AWS 서비스 SDK는 라이브러리에서 `compileOnly`로 선언하므로 실제로 쓰는 모듈을 애플리케이션이 명시해야 합니다.

## 설정 {#configuration}

중앙 플랫폼은 convention plugin이나 공통 의존성 블록 한 곳에서 선언하세요.

## 실패 유형과 해결 방법 {#failures}

서비스 SDK가 빠지면 클래스 로딩에 실패하거나 자동 설정이 활성화되지 않습니다. 저장소 BOM 버전을 따로 고정하면 다른 bluetape4k 라이브러리와 어긋날 수 있습니다.

## 운영 {#operations}

중앙 BOM 버전은 한 번에 올리고, 실제로 활성화한 서비스의 컴파일 및 통합 테스트를 실행하세요.

## 테스트 {#testing}

이 저장소에서는 `./gradlew :bluetape4k-aws-bom:dependencies`, 소비 애플리케이션에서는 dependency insight를 확인하세요.

## 학습 경로와 예제 {#workshops}

중앙 플랫폼을 먼저 적용하고 라이브러리 하나와 서비스 SDK 하나를 고른 뒤 S3, DynamoDB, SQS, Exposed 예제로 이어가세요.

## 제약 사항 {#limitations}

이 BOM은 AWS SDK 서비스 JAR을 추가하거나 클라이언트를 만들고 자격 증명을 설정하지 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### bom 아키텍처

[![bom 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bom-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bom-architecture-01.svg)

_배포본 README: [`bom/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/bom/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [릴리스 소스: `bom/build.gradle.kts`](../../../../bom/build.gradle.kts)

