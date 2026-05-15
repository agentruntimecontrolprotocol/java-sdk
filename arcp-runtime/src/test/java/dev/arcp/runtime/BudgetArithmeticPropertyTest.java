package dev.arcp.runtime;

import dev.arcp.runtime.lease.BudgetCounters;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * §9.6 budget arithmetic property: a sequence of decrements never silently
 * loses precision; the running counter equals {@code initial - sum(decrements)}
 * exactly. {@code BigDecimal} subtraction is associative — the test sweeps
 * adversarial inputs (0.1, 0.2 etc) where {@code double} arithmetic would
 * drift.
 */
class BudgetArithmeticPropertyTest {

    @Property
    boolean decrementsAreExactBigDecimal(
            @ForAll("initial") BigDecimal initial,
            @ForAll("decrements") List<BigDecimal> decrements) {
        BudgetCounters counters = new BudgetCounters(Map.of("USD", initial));
        BigDecimal expected = initial;
        for (BigDecimal d : decrements) {
            counters.decrement("USD", d);
            if (d.signum() >= 0) {
                expected = expected.subtract(d);
            }
        }
        // setScale aligns trailing-zero differences in BigDecimal equality.
        BigDecimal observed = counters.remaining("USD")
                .setScale(6, RoundingMode.HALF_UP);
        return observed.compareTo(expected.setScale(6, RoundingMode.HALF_UP)) == 0;
    }

    @Property
    boolean negativeDecrementsAreSilentlyDropped(
            @ForAll("initial") BigDecimal initial,
            @ForAll("negatives") BigDecimal negative) {
        BudgetCounters counters = new BudgetCounters(Map.of("USD", initial));
        counters.decrement("USD", negative);
        return counters.remaining("USD").compareTo(initial) == 0;
    }

    @Provide
    Arbitrary<BigDecimal> initial() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("1000.00"))
                .ofScale(2);
    }

    @Provide
    Arbitrary<List<BigDecimal>> decrements() {
        Arbitrary<BigDecimal> one = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.0001"), new BigDecimal("10.00"))
                .ofScale(4);
        return one.list().ofMinSize(1).ofMaxSize(20);
    }

    @Provide
    Arbitrary<BigDecimal> negatives() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-1000.00"), new BigDecimal("-0.01"))
                .ofScale(2);
    }
}
