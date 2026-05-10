import net.ltgt.gradle.errorprone.errorprone

plugins {
    application
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(project(":lib"))
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)
}

application {
    mainClass.set("dev.arcp.examples.Example01MinimalSession")
}

// Phase 7 will register one task per example via JavaExec; v0.1 examples are
// added under :examples:run01 ... :examples:run06.

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-Werror",
            "-Xlint:all",
            "-Xlint:-processing",
            "-parameters",
        ),
    )
    options.errorprone {
        disableWarningsInGeneratedCode.set(true)
        option("NullAway:AnnotatedPackages", "dev.arcp")
        error("NullAway")
    }
}

spotless {
    java {
        target("src/**/*.java")
        eclipse()
        removeUnusedImports()
        endWithNewline()
        trimTrailingWhitespace()
    }
}
