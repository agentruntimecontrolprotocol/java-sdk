plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":arcp-client"))
    api(project(":arcp-runtime"))
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "arcp"
            pom {
                name.set("arcp")
                description.set("ARCP umbrella; re-exports client + runtime.")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
