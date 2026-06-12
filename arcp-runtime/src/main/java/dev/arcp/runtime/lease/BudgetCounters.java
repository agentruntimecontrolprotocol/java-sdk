package dev.arcp.runtime.lease;

import dev.arcp.core.error.BudgetExhaustedException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * §9.6 per-currency budget counters. Reads are cheap; mutations are CAS-on-{@link AtomicReference}
 * to keep {@link BigDecimal} arithmetic exact without locking.
 */
public final class BudgetCounters {

  private final ConcurrentHashMap<String, AtomicReference<BigDecimal>> counters =
      new ConcurrentHashMap<>();

  /**
   * Creates counters initialized at the budgeted value per currency, as granted at job acceptance
   * (§9.6).
   *
   * @param initial the budgeted amount per currency, e.g. {@code USD → 5.00}
   */
  public BudgetCounters(Map<String, BigDecimal> initial) {
    for (var e : initial.entrySet()) {
      counters.put(e.getKey(), new AtomicReference<>(e.getValue()));
    }
  }

  /**
   * Tests whether a currency is budgeted; cost metrics in unbudgeted currencies are ignored (§9.6).
   *
   * @param currency the currency code from a {@code cost.*} metric's {@code unit}
   * @return {@code true} if a counter exists for {@code currency}
   */
  public boolean tracks(String currency) {
    return counters.containsKey(currency);
  }

  /**
   * Returns the remaining budget for a currency.
   *
   * @param currency the currency code
   * @return the remaining amount, or {@link BigDecimal#ZERO} for an untracked currency
   */
  public BigDecimal remaining(String currency) {
    var ref = counters.get(currency);
    return ref == null ? BigDecimal.ZERO : ref.get();
  }

  /**
   * Returns a point-in-time view of every counter, e.g. for the budget echoed in {@code
   * job.accepted} (§9.6).
   *
   * @return an unmodifiable map of remaining amount per currency
   */
  public Map<String, BigDecimal> snapshot() {
    return Collections.unmodifiableMap(
        counters.entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey, e -> e.getValue().get(), (a, b) -> a, LinkedHashMap::new)));
  }

  /**
   * Subtracts a reported cost from the currency's counter. Per §9.6, negative metric values produce
   * no decrement; untracked currencies are ignored.
   *
   * @param currency the currency code from the {@code cost.*} metric's {@code unit}
   * @param amount the reported cost to subtract
   */
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

  /**
   * Returns normally if all budgets remain positive.
   *
   * @throws BudgetExhaustedException if any counter reaches zero or below
   */
  public void ensureAllPositive() throws BudgetExhaustedException {
    for (var e : counters.entrySet()) {
      if (e.getValue().get().signum() <= 0) {
        throw new BudgetExhaustedException(
            "budget exhausted: " + e.getKey() + " remaining " + e.getValue().get());
      }
    }
  }
}
