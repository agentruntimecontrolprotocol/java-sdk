rootProject.name = "arcp"

include(
    ":arcp-core",
    ":arcp-client",
    ":arcp-runtime",
    ":arcp",
    ":arcp-otel",
    ":arcp-runtime-jetty",
    ":arcp-middleware-jakarta",
    ":arcp-middleware-spring-boot",
    ":arcp-middleware-vertx",
    ":arcp-tck",
    ":examples:submit-and-stream",
    ":examples:cancel",
    ":examples:heartbeat",
    ":examples:cost-budget",
    ":examples:result-chunk",
    ":examples:agent-versions",
    ":examples:list-jobs",
    ":examples:lease-expires-at",
    ":examples:idempotent-retry",
    ":examples:custom-auth",
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
