plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":arcp-runtime"))
    implementation(libs.jetty.server)
    implementation(libs.jetty.ee10.servlet)
    implementation(libs.jetty.ee10.websocket.jakarta.server)

    testImplementation(project(":arcp-client"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.slf4j.simple)
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "arcp-runtime-jetty"
            pom {
                name.set("arcp-runtime-jetty")
                description.set("Embedded Jetty 12 WebSocket transport for ARCP runtimes.")
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
