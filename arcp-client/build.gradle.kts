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
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "arcp-client"
            pom {
                name.set("arcp-client")
                description.set("ARCP client SDK.")
            }
        }
    }
}
