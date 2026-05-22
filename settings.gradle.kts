plugins {
    id("com.gradleup.nmcp.settings") version "1.5.0"
}

nmcpSettings {
    centralPortal {
        username = System.getenv("CENTRAL_USERNAME")?.ifBlank { null } ?: ""
        password = System.getenv("CENTRAL_PASSWORD")?.ifBlank { null } ?: ""
        publishingType = "AUTOMATIC"
    }
}

// Root project name must not clash with any subproject name. The :arcp
// subproject is the published aggregation BOM; name the root build
// differently so nmcp 1.5.0 can resolve the aggregation unambiguously.
rootProject.name = "arcp-sdk"

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
    ":examples:provisioned-credentials",
    ":examples:ack-backpressure",
    ":examples:delegate",
    ":examples:spring-boot",
    ":examples:jakarta",
    ":examples:lease-violation",
    ":examples:progress",
    ":examples:resume",
    ":examples:stdio",
    ":examples:subscribe",
    ":examples:tracing",
    ":examples:vendor-extensions",
    ":recipes:email-vendor-leases",
    ":recipes:mcp-skill",
    ":recipes:multi-agent-budget",
    ":recipes:stream-resume",
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
