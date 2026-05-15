package dev.arcp.examples.customauth;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.Session;
import dev.arcp.core.auth.BearerVerifier;
import dev.arcp.core.auth.Principal;
import dev.arcp.core.error.UnauthenticatedException;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Demonstrates an HMAC-SHA-256 BearerVerifier SPI implementation. */
public final class Main {
    private static final byte[] SECRET = "shared-secret".getBytes(StandardCharsets.UTF_8);
    private static final String PRINCIPAL_ID = "alice@example.com";

    public static void main(String[] args) throws Exception {
        String validToken = mintToken(PRINCIPAL_ID, SECRET);

        BearerVerifier verifier = token -> {
            // Token shape: "<body>|<hex-sig>". Pipe delimiter avoids collision
            // with dots in email-style identifiers.
            int sep = token.lastIndexOf('|');
            if (sep < 0) {
                throw new UnauthenticatedException("malformed token");
            }
            String body = token.substring(0, sep);
            String sig = token.substring(sep + 1);
            String expected = sign(body, SECRET);
            if (!MessageDigest.isEqual(
                    sig.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8))) {
                throw new UnauthenticatedException("bad signature");
            }
            return new Principal(body);
        };

        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = ArcpRuntime.builder()
                .verifier(verifier)
                .agent("whoami", "1.0.0",
                        (input, ctx) -> JobOutcome.Success.inline(
                                JsonNodeFactory.instance.objectNode()
                                        .put("session", input.sessionId().value())))
                .build();
        runtime.accept(pair[0]);

        try (ArcpClient client = ArcpClient.builder(pair[1]).bearer(validToken).build()) {
            Session session = client.connect(Duration.ofSeconds(5));
            assert session.sessionId() != null;
            System.out.println("OK custom-auth");
        }
        runtime.close();
    }

    private static String mintToken(String body, byte[] secret) {
        return body + "|" + sign(body, secret);
    }

    private static String sign(String body, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
