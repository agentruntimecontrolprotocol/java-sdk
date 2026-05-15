plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":arcp-core"))
    api(project(":arcp-client"))
    api(project(":arcp-runtime"))
    api(libs.junit.jupiter)
    api(libs.assertj)
    implementation(libs.awaitility)

    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "arcp-tck"
            pom {
                name.set("arcp-tck")
                description.set("Conformance harness for ARCP runtime implementations.")
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
