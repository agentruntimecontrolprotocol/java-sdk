package dev.arcp.core.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.arcp.core.wire.ArcpMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CredentialJsonRoundTripTest {
    @Test
    void credentialRoundTripsAndRedactsValue() throws Exception {
        Credential credential = new Credential(
                CredentialId.of("cred_123"),
                CredentialScheme.BEARER,
                "secret-token",
                "https://llm.example.test/v1",
                "fast",
                new CredentialConstraints(
                        List.of("USD:5.00"),
                        List.of("tier-fast/*"),
                        Instant.parse("2026-05-21T12:00:00Z")));

        String json = ArcpMapper.shared().writeValueAsString(credential);
        JsonNode parsed = ArcpMapper.shared().readTree(json);
        assertThat(parsed).isEqualTo(ArcpMapper.shared().readTree("""
                {
                  "id": "cred_123",
                  "scheme": "bearer",
                  "value": "secret-token",
                  "endpoint": "https://llm.example.test/v1",
                  "profile": "fast",
                  "constraints": {
                    "cost.budget": ["USD:5.00"],
                    "model.use": ["tier-fast/*"],
                    "expires_at": "2026-05-21T12:00:00Z"
                  }
                }
                """));
        assertThat(ArcpMapper.shared().readValue(json, Credential.class)).isEqualTo(credential);
        assertThat(credential.toString()).doesNotContain("secret-token");
    }
}
