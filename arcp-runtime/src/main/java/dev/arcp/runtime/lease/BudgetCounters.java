package dev.arcp.runtime.lease;

import dev.arcp.core.error.BudgetExhaustedException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * §9.6 per-currency budget counters. Reads are cheap; mutations are
 * CAS-on-{@link AtomicReference} to keep {@link BigDecimal} arithmetic exact
 * without locking.
 */
public final class BudgetCounters {

    private final ConcurrentHashMap<String, AtomicReference<BigDecimal>> counters =
            new ConcurrentHashMap<>();

    public BudgetCounters(Map<String, BigDecimal> initial) {
        for (var e : initial.entrySet()) {
            counters.put(e.getKey(), new AtomicReference<>(e.getValue()));
        }
    }

    public boolean tracks(String currency) {
        return counters.containsKey(currency);
    }

    public BigDecimal remaining(String currency) {
        var ref = counters.get(currency);
        return ref == null ? BigDecimal.ZERO : ref.get();
    }

    public Map<String, BigDecimal> snapshot() {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (var e : counters.entrySet()) {
            out.put(e.getKey(), e.getValue().get());
        }
        return Collections.unmodifiableMap(out);
    }

    /** §9.6: negative metric values produce no decrement. */
    public void decrement(String currency, BigDecimal amount) {
        if (amount.signum() < 0) {
            return;
        }
        var ref = counters.get(currency);
        if (ref == null) {
            return;
        }
        ref.updateAndGet(b -> b.subtract(amount));
    }

    /** Returns null if all budgets are positive; otherwise throws. */
    public void ensureAllPositive() throws BudgetExhaustedException {
        for (var e : counters.entrySet()) {
            if (e.getValue().get().signum() <= 0) {
                throw new BudgetExhaustedException(
                        "budget exhausted: " + e.getKey() + " remaining " + e.getValue().get());
            }
        }
    }
}
