package dev.arcp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.error.AgentVersionNotAvailableException;
import dev.arcp.runtime.agent.AgentRegistry;
import dev.arcp.runtime.agent.JobOutcome;
import org.junit.jupiter.api.Test;

class AgentVersionTest {

    @Test
    void bareNameResolvesDefault() throws Exception {
        AgentRegistry registry = new AgentRegistry()
                .register("echo", "1.0.0", (i, c) -> JobOutcome.Success.inline(i.payload()))
                .register("echo", "2.0.0", (i, c) -> JobOutcome.Success.inline(i.payload()))
                .setDefault("echo", "2.0.0");

        AgentRegistry.Resolved resolved = registry.resolve(AgentRef.parse("echo"));
        assertThat(resolved.version()).isEqualTo("2.0.0");
        assertThat(resolved.wire()).isEqualTo("echo@2.0.0");
    }

    @Test
    void explicitVersionRequiresMatch() {
        AgentRegistry registry = new AgentRegistry()
                .register("echo", "1.0.0", (i, c) -> JobOutcome.Success.inline(i.payload()));

        try {
            registry.resolve(AgentRef.parse("echo@9.9.9"));
        } catch (AgentVersionNotAvailableException e) {
            assertThat(e.getMessage()).contains("echo@9.9.9");
            return;
        } catch (Exception e) {
            throw new AssertionError("expected AgentVersionNotAvailableException, got " + e);
        }
        throw new AssertionError("expected throw");
    }
}
