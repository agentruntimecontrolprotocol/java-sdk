package dev.arcp.recipes.emailvendorleases;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Recipe: Vendor leases for email delivery.
 *
 * <p>Grants only the capabilities needed for a vendor email integration — network access to the
 * mail server and permission to invoke the {@code send_email} tool. The agent authorizes both
 * before performing its work; requests that fall outside the lease are rejected automatically.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        MemoryTransport.Pair pair = MemoryTransport.pair();
        ArcpRuntime runtime =
                ArcpRuntime.builder()
                        .agent(
                                "email-sender",
                                "1.0.0",
                                (input, ctx) -> {
                                    // Authorize the specific vendor capabilities needed.
                                    ctx.authorize("net.fetch", "https://mail.example.com/send");
                                    ctx.authorize("tool.call", "send_email");
                                    return JobOutcome.Success.inline(
                                            JsonNodeFactory.instance
                                                    .objectNode()
                                                    .put("processed", true)
                                                    .put("vendor", "sendgrid"));
                                })
                        .build();
        runtime.accept(pair.runtime());

        try (ArcpClient client = ArcpClient.builder(pair.client()).build()) {
            client.connect(Duration.ofSeconds(5));

            // Lease grants exactly the two capabilities the email agent needs.
            Lease lease =
                    Lease.builder()
                            .allow("net.fetch", "https://mail.example.com/*")
                            .allow("tool.call", "send_email")
                            .build();

            JobHandle handle =
                    client.submit(
                            ArcpClient.jobSubmit(
                                    "email-sender@1.0.0",
                                    JsonNodeFactory.instance
                                            .objectNode()
                                            .put("to", "user@example.com")
                                            .put("subject", "Hello"),
                                    lease,
                                    null,
                                    null,
                                    null));

            JobResult result = handle.result().get(5, TimeUnit.SECONDS);
            assert result.result().get("processed").asBoolean()
                    : "expected processed=true, got: " + result.result();
            assert "sendgrid".equals(result.result().get("vendor").asText())
                    : "unexpected vendor: " + result.result();
            System.out.println("OK email-vendor-leases");
        }
        runtime.close();
    }
}
