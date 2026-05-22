plugins {
    id("com.gradleup.nmcp") version "0.0.8"
    id("com.diffplug.spotless") version "6.25.0" apply false
}

// Publish all subproject maven-publish publications to Maven Central via the
// Central Publisher Portal REST API (replaces the retired s01.oss.sonatype.org
// OSSRH staging endpoint).  Credentials are read from CENTRAL_USERNAME /
// CENTRAL_PASSWORD environment variables (or the corresponding Gradle
// properties).  publicationType = "AUTOMATIC" auto-releases after upload.
nmcp {
    publishAllProjectsProbablyBreakingProjectIsolation {
        username = (findProperty("centralUsername") as String?)?.ifBlank { null }
                ?: System.getenv("CENTRAL_USERNAME")?.ifBlank { null } ?: ""
        password = (findProperty("centralPassword") as String?)?.ifBlank { null }
                ?: System.getenv("CENTRAL_PASSWORD")?.ifBlank { null } ?: ""
        publicationType = "AUTOMATIC"
    }
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
                // No maven repository block here — nmcp handles Central Portal
                // upload at the root project level.
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
