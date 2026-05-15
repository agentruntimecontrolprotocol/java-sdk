package dev.arcp.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.core.error.BudgetExhaustedException;
import dev.arcp.runtime.lease.BudgetCounters;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BudgetCountersTest {

    @Test
    void decrementsAccumulateExactlyOnBigDecimal() throws Exception {
        BudgetCounters counters = new BudgetCounters(
                Map.of("USD", new BigDecimal("5.00")));
        // 0.1 + 0.2 underflows to 0.30000000000000004 on double; we want exact equality.
        counters.decrement("USD", new BigDecimal("0.1"));
        counters.decrement("USD", new BigDecimal("0.2"));
        assertThat(counters.remaining("USD")).isEqualByComparingTo(new BigDecimal("4.70"));
        counters.ensureAllPositive();
    }

    @Test
    void negativeDecrementsIgnored() throws Exception {
        BudgetCounters counters = new BudgetCounters(
                Map.of("USD", new BigDecimal("1.00")));
        counters.decrement("USD", new BigDecimal("-0.5"));
        assertThat(counters.remaining("USD")).isEqualByComparingTo(new BigDecimal("1.00"));
    }

    @Test
    void exhaustionThrowsBudgetExhausted() {
        BudgetCounters counters = new BudgetCounters(
                Map.of("USD", new BigDecimal("0.01")));
        counters.decrement("USD", new BigDecimal("1.00"));
        assertThatThrownBy(counters::ensureAllPositive)
                .isInstanceOf(BudgetExhaustedException.class);
    }
}
