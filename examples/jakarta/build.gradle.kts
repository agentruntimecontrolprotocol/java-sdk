plugins { application }

application {
    mainClass.set("dev.arcp.examples.jakarta.Main")
    applicationDefaultJvmArgs = listOf("-ea")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

dependencies {
    implementation(project(":arcp-middleware-jakarta"))
    implementation(project(":arcp-client"))
    implementation(libs.jetty.server)
    implementation(libs.jetty.ee10.servlet)
    implementation(libs.jetty.ee10.websocket.jakarta.server)
    runtimeOnly(libs.slf4j.simple)
}
