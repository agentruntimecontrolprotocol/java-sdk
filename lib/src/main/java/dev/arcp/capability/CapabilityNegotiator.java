package dev.arcp.capability;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Computes the negotiated intersection of two {@link Capabilities} sets (RFC
 * §7). Required-but-unsupported features yield a {@code session.rejected}
 * higher up; this class only computes the intersection — call sites enforce the
 * {@code required} contract.
 */
public final class CapabilityNegotiator {

	private CapabilityNegotiator() {
	}

	/** AND-intersection of the two sets, used during session.accepted. */
	public static Capabilities intersect(Capabilities a, Capabilities b) {
		Set<String> exts = new HashSet<>();
		if (a.extensions() != null) {
			exts.addAll(a.extensions());
		}
		if (b.extensions() != null) {
			exts.retainAll(b.extensions());
		} else {
			exts.clear();
		}
		List<String> encoding = intersectList(a.binaryEncoding(), b.binaryEncoding());
		String hbRecovery = a.heartbeatRecovery() != null && a.heartbeatRecovery().equals(b.heartbeatRecovery())
				? a.heartbeatRecovery()
				: null;
		int hbInterval = Math.min(positive(a.heartbeatIntervalSeconds(), 30),
				positive(b.heartbeatIntervalSeconds(), 30));
		return new Capabilities(a.anonymous() && b.anonymous(), a.streaming() && b.streaming(),
				a.humanInput() && b.humanInput(), a.permissions() && b.permissions(), a.artifacts() && b.artifacts(),
				a.subscriptions() && b.subscriptions(), a.interrupt() && b.interrupt(), hbRecovery, hbInterval,
				encoding, exts);
	}

	private static int positive(int v, int fallback) {
		return v <= 0 ? fallback : v;
	}

	private static List<String> intersectList(@Nullable List<String> a, @Nullable List<String> b) {
		if (a == null || b == null) {
			return List.of();
		}
		return a.stream().filter(b::contains).toList();
	}
}
