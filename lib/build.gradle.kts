import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    `maven-publish`
    jacoco
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.jsr310)
    api(libs.slf4j.api)
    api(libs.jspecify)

    implementation(libs.sqlite.jdbc)
    implementation(libs.java.websocket)
    implementation(libs.nimbus.jwt)
    implementation(libs.ulid)
    implementation(libs.json.schema.validator)

    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-Werror",
            "-Xlint:all",
            "-Xlint:-processing",
            // ulid-creator is an automatic module (no module descriptor).
            "-Xlint:-requires-automatic",
            "-parameters",
        ),
    )
    options.errorprone {
        disableWarningsInGeneratedCode.set(true)
        option("NullAway:AnnotatedPackages", "dev.arcp")
        error("NullAway")
        // `@return ...` is an idiomatic Javadoc summary; the Google style
        // checker disagrees but we accept it.
        disable("MissingSummary")
    }
}

tasks.named<JavaCompile>("compileTestJava").configure {
    options.errorprone {
        disable("NullAway")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<Javadoc>().configureEach {
    (options as? StandardJavadocDocletOptions)?.apply {
        addStringOption("Xdoclint:none", "-quiet")
        encoding = "UTF-8"
        charSet = "UTF-8"
    }
}

spotless {
    java {
        target("src/**/*.java")
        // RFC-side note: Palantir Java Format 2.68.0 (latest at Phase 0) is
        // incompatible with JDK 25 javac internals (Log$DeferredDiagnosticHandler
        // signature changed). Eclipse JDT is JDK-agnostic; revisit Palantir when
        // an updated version ships. See PLAN.md §A4 / §A9.
        eclipse()
        removeUnusedImports()
        endWithNewline()
        trimTrailingWhitespace()
    }
}

jacoco {
    // JaCoCo 0.8.13+ adds support for Java 25 class major version 69.
    toolVersion = "0.8.13"
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "arcp"
            pom {
                name.set("arcp")
                description.set("Java reference implementation of the Agent Runtime Control Protocol (ARCP) v1.0.")
                url.set("https://github.com/nficano/arpc")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("nficano")
                        name.set("Nick Ficano")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/nficano/arpc.git")
                    developerConnection.set("scm:git:ssh://git@github.com/nficano/arpc.git")
                    url.set("https://github.com/nficano/arpc")
                }
            }
        }
    }
}
