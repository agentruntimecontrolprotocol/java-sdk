package dev.arcp.runtime.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arcp.core.agents.AgentRef;
import dev.arcp.runtime.agent.AgentRegistry;
import dev.arcp.runtime.agent.JobOutcome;
import dev.arcp.runtime.heartbeat.HeartbeatTracker;
import dev.arcp.runtime.lease.BudgetCounters;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Small remaining branches: budget counters, registry fallback, heartbeat thresholds (#33). */
class MiscBranchTest {

  @Test
  void budgetCountersIgnoreUnknownCurrenciesAndNegativeAmounts() {
    BudgetCounters counters = new BudgetCounters(Map.of("usd", new BigDecimal("10")));
    assertThat(counters.tracks("usd")).isTrue();
    assertThat(counters.tracks("eur")).isFalse();
    assertThat(counters.remaining("eur")).isEqualByComparingTo(BigDecimal.ZERO);

    counters.decrement("eur", new BigDecimal("5")); // untracked: no-op
    counters.decrement("usd", new BigDecimal("-5")); // negative: no-op (§9.6)
    assertThat(counters.remaining("usd")).isEqualByComparingTo("10");

    counters.decrement("usd", new BigDecimal("4"));
    assertThat(counters.remaining("usd")).isEqualByComparingTo("6");
    assertThat(counters.snapshot()).containsEntry("usd", new BigDecimal("6"));
  }

  @Test
  void registryFallsBackToFirstVersionWhenDefaultIsUnregistered() throws Exception {
    AgentRegistry registry = new AgentRegistry();
    registry.register("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()));
    registry.register("echo", "2.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()));
    registry.setDefault("echo", "9.9.9"); // points at a version that does not exist

    AgentRegistry.Resolved resolved = registry.resolve(AgentRef.parse("echo"));
    assertThat(resolved.wire()).isEqualTo("echo@1.0.0");

    registry.setDefault("echo", "2.0.0");
    assertThat(registry.resolve(AgentRef.parse("echo")).wire()).isEqualTo("echo@2.0.0");
    assertThat(registry.describe()).hasSize(1);
    assertThat(registry.describe().getFirst().versions()).containsExactly("1.0.0", "2.0.0");
  }

  @Test
  void heartbeatThresholdsFollowSpecIntervals() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    HeartbeatTracker tracker = new HeartbeatTracker(clock);
    Duration interval = Duration.ofSeconds(10);

    assertThat(tracker.shouldPing(interval)).isFalse();
    assertThat(tracker.shouldClose(interval)).isFalse();

    clock.advance(Duration.ofSeconds(10)); // exactly one interval
    assertThat(tracker.shouldPing(interval)).isTrue();
    assertThat(tracker.shouldClose(interval)).isFalse();

    clock.advance(Duration.ofSeconds(10)); // exactly two intervals: still not "more than" 2x
    assertThat(tracker.shouldClose(interval)).isFalse();

    clock.advance(Duration.ofSeconds(1)); // beyond two intervals
    assertThat(tracker.shouldClose(interval)).isTrue();
    assertThat(tracker.sinceLastInbound()).isEqualTo(Duration.ofSeconds(21));

    tracker.onInbound();
    assertThat(tracker.shouldPing(interval)).isFalse();
    assertThat(tracker.shouldClose(interval)).isFalse();
  }
}
