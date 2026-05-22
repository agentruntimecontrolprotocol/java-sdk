plugins { application }

application {
    mainClass.set("dev.arcp.examples.tracing.Main")
    applicationDefaultJvmArgs = listOf("-ea")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

dependencies {
    implementation(project(":arcp"))
    implementation(project(":arcp-otel"))
    implementation(libs.opentelemetry.sdk.testing)
    runtimeOnly(libs.slf4j.simple)
}
