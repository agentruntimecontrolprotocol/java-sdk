plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.pitest)
}

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.jsr310)
    api(libs.slf4j.api)
    api(libs.jspecify)
    implementation(libs.ulid)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
}

// PIT mutation testing — opt-in via `./gradlew :arcp-core:pitest`.
// Excluded from the default check task to keep PR builds fast.
pitest {
    pitestVersion.set(libs.versions.pitest.core)
    junit5PluginVersion.set(libs.versions.pitest.junit5)
    targetClasses.set(listOf(
        "dev.arcp.core.wire.*",
        "dev.arcp.core.capabilities.*",
        "dev.arcp.core.error.*",
        "dev.arcp.core.lease.*",
        "dev.arcp.core.agents.*",
    ))
    excludedClasses.set(listOf("dev.arcp.core.error.*Exception"))
    testPlugin.set("junit5")
    threads.set(4)
    timestampedReports.set(false)
    outputFormats.set(listOf("HTML", "XML"))
    mutationThreshold.set(0)   // do not fail build on threshold; report only
    coverageThreshold.set(0)
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "arcp-core"
            pom {
                name.set("arcp-core")
                description.set("ARCP core wire types, capabilities, and errors.")
            }
        }
    }
}
