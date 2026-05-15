plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":arcp-core"))
    api(libs.opentelemetry.api)
    implementation(libs.opentelemetry.context)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.slf4j.simple)
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "arcp-otel"
            pom {
                name.set("arcp-otel")
                description.set("OpenTelemetry adapter for ARCP transports.")
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
