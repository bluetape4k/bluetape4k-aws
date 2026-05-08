plugins {
    `java-platform`
    `maven-publish`
    signing
}

dependencies {
    constraints {
        rootProject.subprojects {
            if (name != "bluetape4k-aws-bom") {
                api(this)
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("BluetapeAws") {
            from(components["javaPlatform"])
            pom {
                name.set("bluetape4k-aws-bom")
                description.set("BOM for bluetape4k-aws — AWS SDK v2 + Kotlin SDK wrappers with Coroutines support")
                url.set("https://github.com/bluetape4k/bluetape4k-aws")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("debop")
                        name.set("Sunghyouk Bae")
                        email.set("sunghyouk.bae@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/bluetape4k/bluetape4k-aws.git")
                    developerConnection.set("scm:git:ssh://github.com/bluetape4k/bluetape4k-aws.git")
                    url.set("https://github.com/bluetape4k/bluetape4k-aws")
                }
            }
        }
    }
}

configurePublishingSigning("BluetapeAws")
