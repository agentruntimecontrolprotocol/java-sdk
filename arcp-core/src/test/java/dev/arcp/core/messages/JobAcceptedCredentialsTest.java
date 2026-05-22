package dev.arcp.core.messages;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.credentials.CredentialScheme;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.wire.ArcpMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobAcceptedCredentialsTest {
    @Test
    void credentialsAreOptionalOnJobAccepted() throws Exception {
        JobAccepted withCredentials = new JobAccepted(
                JobId.of("job_1"),
                "agent@1.0.0",
                Lease.empty(),
                null,
                null,
                List.of(new Credential(
                        CredentialId.of("cred_1"),
                        CredentialScheme.BEARER,
                        "secret",
                        "https://llm.example.test/v1",
                        null,
                        null)),
                Instant.parse("2026-05-21T12:00:00Z"),
                null);

        assertThat(ArcpMapper.shared().readTree(
                ArcpMapper.shared().writeValueAsString(withCredentials)).has("credentials"))
                .isTrue();

        JobAccepted withoutCredentials = new JobAccepted(
                JobId.of("job_2"),
                "agent@1.0.0",
                Lease.empty(),
                null,
                null,
                null,
                Instant.parse("2026-05-21T12:00:00Z"),
                null);
        assertThat(ArcpMapper.shared().readTree(
                ArcpMapper.shared().writeValueAsString(withoutCredentials)).has("credentials"))
                .isFalse();
    }
}
