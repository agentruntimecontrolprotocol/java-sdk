plugins {
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.errorprone) apply false
}

allprojects {
    group = "dev.arcp"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
