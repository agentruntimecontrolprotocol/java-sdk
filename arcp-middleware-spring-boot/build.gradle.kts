plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":arcp-runtime"))
    compileOnly(libs.spring.web)
    compileOnly(libs.spring.websocket)
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.boot.autoconfigure)

    testImplementation(project(":arcp-client"))
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.websocket)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    // Intentionally NOT including slf4j-simple here: spring-boot-starter pulls
    // logback, and two SLF4J bindings on one classpath fail Spring's logging
    // initializer.
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "arcp-middleware-spring-boot"
            pom {
                name.set("arcp-middleware-spring-boot")
                description.set("Spring Boot 3.x adapter for ARCP runtimes.")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
