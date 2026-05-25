plugins {
    id("com.diffplug.spotless") version "8.5.1" apply false
}

allprojects {
    group = "dev.arcp"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("java-library") {
        apply(plugin = "jacoco")
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
            withSourcesJar()
            withJavadocJar()
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(
                listOf(
                    "-Xlint:all",
                    "-Xlint:-processing",
                    "-Xlint:-requires-automatic",
                    "-parameters",
                ),
            )
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            finalizedBy("jacocoTestReport")
        }
        tasks.withType<JacocoReport>().configureEach {
            dependsOn("test")
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }
        tasks.withType<Javadoc>().configureEach {
            (options as? StandardJavadocDocletOptions)?.apply {
                addStringOption("Xdoclint:none", "-quiet")
                encoding = "UTF-8"
                charSet = "UTF-8"
            }
        }
        apply(plugin = "com.diffplug.spotless")
        configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            java {
                googleJavaFormat()
                removeUnusedImports()
            }
        }
    }

    // Shared publishing metadata. Per-subproject build files still own the
    // artifactId and one-line description; everything else (licence, SCM,
    // developer, signing) is configured once here.
    plugins.withId("maven-publish") {
        apply(plugin = "signing")

        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications.withType<MavenPublication>().configureEach {
                    pom {
                        url.set("https://github.com/nficano/arpc")
                        licenses {
                            license {
                                name.set("Apache-2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                distribution.set("repo")
                            }
                        }
                        developers {
                            developer {
                                id.set("nficano")
                                name.set("Nick Ficano")
                                email.set("nficano@gmail.com")
                            }
                        }
                        scm {
                            url.set("https://github.com/nficano/arpc")
                            connection.set("scm:git:https://github.com/nficano/arpc.git")
                            developerConnection.set("scm:git:ssh://git@github.com/nficano/arpc.git")
                        }
                        issueManagement {
                            system.set("GitHub")
                            url.set("https://github.com/nficano/arpc/issues")
                        }
                    }
                }
            }

            // Signing is only wired when the developer has supplied a PGP key.
            // Local builds and CI without credentials skip it silently.
            // Note: GitHub Actions injects "" for unset secrets, so normalise
            // blank strings to null before the presence check.
            extensions.configure<org.gradle.plugins.signing.SigningExtension> {
                val signingKey = (findProperty("signingKey") as String?)?.ifBlank { null }
                        ?: System.getenv("GPG_SIGNING_KEY")?.ifBlank { null }
                val signingPassword = (findProperty("signingPassword") as String?)?.ifBlank { null }
                        ?: System.getenv("GPG_SIGNING_PASSWORD")?.ifBlank { null }
                isRequired = signingKey != null && signingPassword != null
                if (isRequired) {
                    useInMemoryPgpKeys(signingKey, signingPassword)
                    val publishing =
                            extensions.getByType(PublishingExtension::class.java)
                    sign(publishing.publications)
                }
            }
        }
    }
}
