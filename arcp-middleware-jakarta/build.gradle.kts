plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":arcp-runtime"))
    compileOnly(libs.jakarta.websocket.api)
    compileOnly(libs.jakarta.websocket.client.api)

    testImplementation(project(":arcp-client"))
    testImplementation(libs.jakarta.websocket.api)
    testImplementation(libs.jakarta.websocket.client.api)
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
            }
        }
    }
}
