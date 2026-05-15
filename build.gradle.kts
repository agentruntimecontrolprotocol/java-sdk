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

                // Sonatype Central. Credentials read from gradle.properties or
                // ORG_GRADLE_PROJECT_* environment variables; the deploy target
                // resolves to the OSS staging endpoint by default and to the
                // snapshots repository when `version` carries the SNAPSHOT
                // qualifier.
                repositories {
                    maven {
                        name = "central"
                        val releases = uri(
                                "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                        val snapshots = uri(
                                "https://s01.oss.sonatype.org/content/repositories/snapshots/")
                        url = if (version.toString().endsWith("SNAPSHOT")) snapshots else releases
                        credentials {
                            username = (findProperty("ossrhUsername") as String?)
                                    ?: System.getenv("OSSRH_USERNAME") ?: ""
                            password = (findProperty("ossrhPassword") as String?)
                                    ?: System.getenv("OSSRH_PASSWORD") ?: ""
                        }
                    }
                }
            }

            // Signing is only wired when the developer has supplied a PGP key.
            // Local builds and CI without credentials skip it silently.
            extensions.configure<org.gradle.plugins.signing.SigningExtension> {
                val signingKey = findProperty("signingKey") as String?
                        ?: System.getenv("GPG_SIGNING_KEY")
                val signingPassword = findProperty("signingPassword") as String?
                        ?: System.getenv("GPG_SIGNING_PASSWORD")
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
