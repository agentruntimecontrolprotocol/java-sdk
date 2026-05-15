plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":arcp-core"))

    testImplementation(project(":arcp-runtime"))
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
            artifactId = "arcp-client"
            pom {
                name.set("arcp-client")
                description.set("ARCP client SDK.")
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
