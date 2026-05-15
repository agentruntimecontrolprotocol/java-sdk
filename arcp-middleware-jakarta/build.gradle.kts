plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":arcp-runtime"))
    compileOnly(libs.jakarta.websocket.api)

    testImplementation(project(":arcp-client"))
    testImplementation(libs.jakarta.websocket.api)
    testImplementation(libs.jetty.server)
    testImplementation(libs.jetty.ee10.servlet)
    testImplementation(libs.jetty.ee10.websocket.jakarta.server)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.slf4j.simple)
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "arcp-middleware-jakarta"
            pom {
                name.set("arcp-middleware-jakarta")
                description.set("Jakarta WebSocket endpoint adapter for ARCP runtimes.")
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
