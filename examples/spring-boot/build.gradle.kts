plugins { application }

application {
    mainClass.set("dev.arcp.examples.springboot.Main")
    applicationDefaultJvmArgs = listOf("-ea")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

dependencies {
    implementation(project(":arcp-middleware-spring-boot"))
    implementation(project(":arcp-client"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.websocket)
    // Spring Boot pulls in logback; do NOT add slf4j-simple here
}
