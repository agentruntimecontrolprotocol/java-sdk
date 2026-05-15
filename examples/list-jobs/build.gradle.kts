plugins {
    application
}

application {
    mainClass.set("dev.arcp.examples.listjobs.Main")
    applicationDefaultJvmArgs = listOf("-ea")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

dependencies {
    implementation(project(":arcp"))
    runtimeOnly(libs.slf4j.simple)
}
