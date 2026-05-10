rootProject.name = "arcp"

include(":lib", ":cli", ":examples")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
