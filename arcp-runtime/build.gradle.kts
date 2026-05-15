plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.pitest)
}

dependencies {
    api(project(":arcp-core"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
}

// PIT mutation testing — opt-in via `./gradlew :arcp-runtime:pitest`.
pitest {
    pitestVersion.set(libs.versions.pitest.core)
    junit5PluginVersion.set(libs.versions.pitest.junit5)
    targetClasses.set(listOf(
        "dev.arcp.runtime.lease.*",
        "dev.arcp.runtime.idempotency.*",
        "dev.arcp.runtime.agent.*",
        "dev.arcp.runtime.heartbeat.*",
    ))
    testPlugin.set("junit5")
    threads.set(4)
    timestampedReports.set(false)
    outputFormats.set(listOf("HTML", "XML"))
    mutationThreshold.set(0)
    coverageThreshold.set(0)
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "arcp-runtime"
            pom {
                name.set("arcp-runtime")
                description.set("ARCP runtime SDK.")
            }
        }
    }
}
