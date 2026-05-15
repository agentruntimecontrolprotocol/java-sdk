plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":arcp-runtime"))
    compileOnly(libs.vertx.core)

    testImplementation(project(":arcp-client"))
    testImplementation(libs.vertx.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.slf4j.simple)
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "arcp-middleware-vertx"
            pom {
                name.set("arcp-middleware-vertx")
                description.set("Vert.x 5 WebSocket adapter for ARCP runtimes.")
            }
        }
    }
}
